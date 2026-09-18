package dev.bhavya.anvesh.document;

import dev.bhavya.anvesh.common.NotFoundException;
import dev.bhavya.anvesh.ingest.IngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Ingest and manage documents")
public class DocumentController {

    private final IngestService ingest;
    private final DocumentRepository documents;

    public DocumentController(IngestService ingest, DocumentRepository documents) {
        this.ingest = ingest;
        this.documents = documents;
    }

    public record IngestRequest(
            @NotBlank @Size(max = 500) String title,
            @Size(max = 2000) String source,
            @Size(max = 8) String language,
            Map<String, Object> metadata,
            @NotBlank @Size(max = 1_000_000) String body
    ) {}

    public record IngestResponse(UUID id, String status, boolean duplicate) {}

    @PostMapping
    @Operation(summary = "Submit a document for indexing (async). Identical bodies are deduplicated.")
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest req) {
        String lang = req.language() == null || req.language().isBlank() ? "und" : req.language();
        String metaJson = toJson(req.metadata());
        IngestService.Submission sub = ingest.submit(req.title(), req.source(), lang, metaJson, req.body());
        if (!sub.duplicate()) ingest.indexAsync(sub.id(), req.body());

        HttpStatus status = sub.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/v1/documents/" + sub.id()))
                .body(new IngestResponse(sub.id(), sub.duplicate() ? "INDEXED" : "PENDING", sub.duplicate()));
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable UUID id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("Document " + id + " not found"));
    }

    @GetMapping
    public List<Document> list(@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return documents.findAll(Math.min(limit, 100), Math.max(offset, 0));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (documents.delete(id) == 0) throw new NotFoundException("Document " + id + " not found");
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
