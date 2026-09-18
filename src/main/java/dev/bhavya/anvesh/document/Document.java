package dev.bhavya.anvesh.document;

import java.time.Instant;
import java.util.UUID;

public record Document(
        UUID id,
        String title,
        String source,
        String language,
        String metadataJson,
        String contentHash,
        Status status,
        String error,
        Instant createdAt,
        Instant indexedAt
) {
    public enum Status { PENDING, INDEXED, FAILED }
}
