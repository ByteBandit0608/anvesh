package dev.bhavya.anvesh.security;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ApiKeyService {

    private final ApiKeyRepository repo;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repo) { this.repo = repo; }

    public record CreatedKey(UUID id, String rawKey, String ownerId, String name) {}

    /**
     * Generates a raw key like anv_<base64url>, stores only its SHA-256 hash.
     * WHY raw key shown only once: if DB leaks, attacker gets hashes not usable keys.
     * Owner id is deterministic from name for now (name -> slug), but could be a user id later.
     */
    public CreatedKey create(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        String ownerId = slugify(name) + "-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String raw = "anv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256Hex(raw);
        UUID id = repo.insert(hash, name, ownerId);
        return new CreatedKey(id, raw, ownerId, name);
    }

    public CreatedKey createForOwner(String name, String ownerId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId required");
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String raw = "anv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256Hex(raw);
        UUID id = repo.insert(hash, name, ownerId);
        return new CreatedKey(id, raw, ownerId, name);
    }

    static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
