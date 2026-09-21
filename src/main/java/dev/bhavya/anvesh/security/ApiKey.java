package dev.bhavya.anvesh.security;

import java.time.Instant;
import java.util.UUID;

public record ApiKey(
        UUID id,
        String keyHash,
        String name,
        String ownerId,
        boolean active,
        Instant createdAt,
        Instant lastUsedAt
) {}
