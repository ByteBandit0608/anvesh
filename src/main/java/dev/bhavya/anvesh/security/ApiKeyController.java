package dev.bhavya.anvesh.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/keys")
@Tag(name = "API Keys", description = "Create and manage API keys for multi-tenant access")
public class ApiKeyController {

    private final ApiKeyService service;
    private final ApiKeyRepository repo;

    public ApiKeyController(ApiKeyService service, ApiKeyRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    public record CreateRequest(@NotBlank String name, String ownerId) {}
    public record CreateResponse(UUID id, String rawKey, String ownerId, String name, String message) {}
    public record KeyInfo(UUID id, String name, String ownerId, boolean active, String createdAt, String lastUsedAt) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new API key. Raw key is shown only once — store it securely.")
    public CreateResponse create(@RequestBody CreateRequest req) {
        ApiKeyService.CreatedKey ck;
        if (req.ownerId() != null && !req.ownerId().isBlank()) {
            ck = service.createForOwner(req.name(), req.ownerId());
        } else {
            ck = service.create(req.name());
        }
        return new CreateResponse(ck.id(), ck.rawKey(), ck.ownerId(), ck.name(),
                "Store this key securely — it will not be shown again. Use header X-API-Key: " + ck.rawKey());
    }

    @GetMapping
    @Operation(summary = "List all API keys (hashes not shown)")
    public List<KeyInfo> list() {
        return repo.findAll().stream().map(k -> new KeyInfo(
                k.id(), k.name(), k.ownerId(), k.active(),
                k.createdAt().toString(),
                k.lastUsedAt() != null ? k.lastUsedAt().toString() : null
        )).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate an API key")
    public void deactivate(@PathVariable UUID id) {
        repo.deactivate(id);
    }
}
