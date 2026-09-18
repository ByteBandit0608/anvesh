package dev.bhavya.anvesh.ingest;

import dev.bhavya.anvesh.document.DocumentRepository;
import dev.bhavya.anvesh.embedding.EmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Two-phase ingestion:
 *  1. {@link #submit} — synchronous, cheap: hash body, insert document row, return 202 + id.
 *  2. {@link #index}  — async on the ingest pool: chunk -> embed -> bulk insert -> mark INDEXED.
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
    private final Timer indexTimer;

    public IngestService(DocumentRepository documents, TextChunker chunker, EmbeddingService embeddings, MeterRegistry metrics) {
        this.documents = documents;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.indexTimer = Timer.builder("anvesh.ingest.index").description("Time to chunk+embed+store a document").register(metrics);
    }

    public record Submission(UUID id, boolean duplicate) {}

    public Submission submit(String title, String source, String language, String metadataJson, String body) {
        String hash = sha256Hex(body);
        UUID id = documents.insertOrGetExisting(title, source, language, metadataJson, hash);
        boolean isNew = documents.isNew(id);
        return new Submission(id, !isNew);
    }

    @Async("ingestExecutor")
    public void indexAsync(UUID id, String body) {
        index(id, body);
    }

    /**
     * NOTE: deliberately not @Transactional yet. Called from indexAsync() in the same bean,
     * so a Spring proxy annotation here would silently be ignored (self-invocation).
     * Week-2 task: make chunk insert + status update atomic via TransactionTemplate.
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
                documents.insertChunks(id, chunks, vectors);
                documents.markIndexed(id);
                log.info("Indexed document {} into {} chunks", id, chunks.size());
            } catch (Exception e) {
                log.error("Indexing failed for {}", id, e);
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
