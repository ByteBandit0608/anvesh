package dev.bhavya.anvesh.ingest;

import dev.bhavya.anvesh.common.ConflictException;
import dev.bhavya.anvesh.common.NotFoundException;
import dev.bhavya.anvesh.document.DocumentRepository;
import dev.bhavya.anvesh.embedding.EmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Two-phase ingestion:
 *  1. {@link #submit} — synchronous, cheap: hash body, insert document row (+ body), return 202 + id.
 *  2. {@link #index}  — async on the ingest pool: chunk -> embed -> [delete old chunks -> insert -> mark INDEXED] atomically.
 *
 * Clients poll GET /documents/{id} for status. Week-5 upgrade: replace the in-process
 * executor with a Redis/Kafka queue so indexing survives restarts and scales out.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final DocumentRepository documents;
    private final TextChunker chunker;
    private final EmbeddingService embeddings;
    private final TransactionTemplate tx;
    private final Timer indexTimer;

    public IngestService(DocumentRepository documents, TextChunker chunker, EmbeddingService embeddings,
                         TransactionTemplate tx, MeterRegistry metrics) {
        this.documents = documents;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.tx = tx;
        this.indexTimer = Timer.builder("anvesh.ingest.index").description("Time to chunk+embed+store a document").register(metrics);
    }

    public record Submission(UUID id, boolean duplicate) {}

    public Submission submit(String title, String source, String language, String metadataJson, String body) {
        String hash = sha256Hex(body);
        UUID id = documents.insertOrGetExisting(title, source, language, metadataJson, hash, body);
        boolean isNew = documents.isNew(id);
        return new Submission(id, !isNew);
    }

    /**
     * Re-run chunk -> embed -> store for an existing document (after a model change, or to retry a FAILED one).
     * Runs on the caller's thread only up to the status flip; the heavy work is async.
     *
     * @throws NotFoundException if the id doesn't exist
     * @throws ConflictException if the document is currently PENDING (already being indexed), or has no stored body
     */
    public void reindex(UUID id) {
        documents.findById(id).orElseThrow(() -> new NotFoundException("Document " + id + " not found"));
        String body = documents.findBody(id)
                .orElseThrow(() -> new ConflictException("Document " + id + " has no stored body (ingested before V2); re-ingest it"));
        // WHY a conditional UPDATE and not "read status, then update": two requests could both read
        // INDEXED and both start indexing. The DB's row lock makes exactly one UPDATE win.
        if (!documents.markPendingForReindex(id)) {
            throw new ConflictException("Document " + id + " is already being indexed");
        }
        indexAsync(id, body);
    }

    @Async("ingestExecutor")
    public void indexAsync(UUID id, String body) {
        index(id, body);
    }

    /**
     * WHY TransactionTemplate and not @Transactional: this is called from indexAsync() on the same bean.
     * Spring's @Transactional lives in a proxy around the bean; a call via `this` never touches the proxy,
     * so the annotation would be silently ignored. TransactionTemplate is explicit and can't be bypassed.
     *
     * WHY embedding is OUTSIDE the transaction: embedAll() can take seconds for a large document.
     * A transaction holds a pooled DB connection (Hikari default pool = 10) for its whole duration.
     * Holding one per in-flight embedding would starve search requests of connections while doing zero DB work.
     * Rule: keep transactions short — compute first, then open the transaction only for the writes.
     */
    public void index(UUID id, String body) {
        indexTimer.record(() -> {
            try {
                List<String> chunks = chunker.chunk(body);
                if (chunks.isEmpty()) {
                    documents.markFailed(id, "Document body produced no chunks");
                    return;
                }
                List<float[]> vectors = embeddings.embedAll(chunks);

                tx.executeWithoutResult(status -> {
                    // WHY delete first: makes index() idempotent. A retry after a crash, or a reindex,
                    // replaces the chunk set instead of appending duplicates.
                    documents.deleteChunks(id);
                    documents.insertChunks(id, chunks, vectors);
                    documents.markIndexed(id);
                    // If markIndexed throws, the delete and insert above are rolled back with it:
                    // the document keeps its previous chunks (if any) and the catch below marks it FAILED.
                });

                log.info("Indexed document {} into {} chunks", id, chunks.size());
            } catch (Exception e) {
                log.error("Indexing failed for {}", id, e);
                // WHY this is outside the transaction: it must COMMIT even though the main work rolled back.
                // A status write inside a rolled-back transaction would vanish too, leaving the doc PENDING forever.
                documents.markFailed(id, e.getMessage());
            }
        });
    }

    static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
