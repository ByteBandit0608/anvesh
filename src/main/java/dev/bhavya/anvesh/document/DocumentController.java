package dev.bhavya.anvesh.document;

import dev.bhavya.anvesh.common.NotFoundException;
import dev.bhavya.anvesh.common.RequestContext;
import dev.bhavya.anvesh.config.AnveshProperties;
import dev.bhavya.anvesh.ingest.IngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Ingest and manage documents")
public class DocumentController {

    private final IngestService ingest;
    private final DocumentRepository documents;
    private final int maxBatchSize;

    public DocumentController(IngestService ingest, DocumentRepository documents, AnveshProperties props) {
        this.ingest = ingest;
        this.documents = documents;
        this.maxBatchSize = props.ingest().maxBatchSize();
    }

    public record IngestRequest(
            @NotBlank @Size(max = 500) String title,
            @Size(max = 2000) String source,
            @Size(max = 8) String language,
            Map<String, Object> metadata,
            @NotBlank @Size(max = 1_000_000) String body
    ) {}

    public record IngestResponse(UUID id, String status, boolean duplicate) {}

    public record BatchRequest(@NotEmpty @Valid List<IngestRequest> documents) {}
    public record BatchResponse(int accepted, int duplicates, List<IngestResponse> results) {}

    @PostMapping
    @Operation(summary = "Submit a document for indexing (async). Identical bodies are deduplicated per owner.")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest req) {
        String lang = req.language() == null || req.language().isBlank() ? "und" : req.language();
        String metaJson = toJson(req.metadata());
        String ownerId = RequestContext.ownerId();
        IngestService.Submission sub = ingest.submit(req.title(), req.source(), lang, metaJson, req.body(), ownerId);
        if (sub.needsIndex()) ingest.indexAsync(sub.id(), req.body());

        HttpStatus status = sub.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/v1/documents/" + sub.id()))
                .body(new IngestResponse(sub.id(), sub.status().name(), sub.duplicate()));
    }

    /**
     * WHY a batch endpoint at all: one HTTP round-trip per document dominates ingest time for
     * small docs, and clients with 1000 files want one call. WHY each document is submitted
     * independently rather than in one transaction: partial success is the useful behaviour here —
     * 99 good docs shouldn't be rejected because #57 is a duplicate. The per-item result tells the
     * client exactly what happened to each.
     */
    @PostMapping("/batch")
    @Operation(summary = "Submit up to anvesh.ingest.max-batch-size documents in one call. Each is deduplicated and indexed independently.")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BatchResponse ingestBatch(@Valid @RequestBody BatchRequest req) {
        if (req.documents().size() > maxBatchSize) {
            throw new IllegalArgumentException("batch too large: " + req.documents().size() + " > " + maxBatchSize);
        }
        List<IngestResponse> results = new ArrayList<>(req.documents().size());
        int dups = 0;
        for (IngestRequest d : req.documents()) {
            IngestResponse r = ingest(d).getBody();
            results.add(r);
            if (r != null && r.duplicate()) dups++;
        }
        return new BatchResponse(results.size() - dups, dups, results);
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable UUID id) {
        String ownerId = RequestContext.ownerId();
        // Try owner-scoped first, then fallback to any (for public backward compat)
        return documents.findByIdAndOwner(id, ownerId)
                .or(() -> documents.findById(id))
                .orElseThrow(() -> new NotFoundException("Document " + id + " not found"));
    }

    @GetMapping
    public List<Document> list(@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        String ownerId = RequestContext.ownerId();
        return documents.findAllByOwner(ownerId, Math.min(limit, 100), Math.max(offset, 0));
    }

    /**
     * WHY 202 and not 200: same contract as POST — work is queued, poll GET /{id}.
     * WHY 409 on PENDING: the doc is already being indexed; a second run would race the first.
     */
    @PostMapping("/{id}/reindex")
    @Operation(summary = "Re-chunk and re-embed an existing document (retry a FAILED one, or after a model change). 409 if already in progress.")
    public ResponseEntity<IngestResponse> reindex(@PathVariable UUID id) {
        ingest.reindex(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/documents/" + id))
                .body(new IngestResponse(id, "PENDING", false));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        String ownerId = RequestContext.ownerId();
        int deleted = documents.deleteByOwner(id, ownerId);
        if (deleted == 0) {
            // Fallback: if owner is public, allow deleting public docs (backward compat)
            if ("public".equals(ownerId)) deleted = documents.delete(id);
        }
        if (deleted == 0) throw new NotFoundException("Document " + id + " not found");
    }

    private static String toJson(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("metadata is not serialisable", e);
        }
    }
}
