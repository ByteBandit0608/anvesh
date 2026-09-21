package dev.bhavya.anvesh.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<ApiKey> MAPPER = (rs, i) -> new ApiKey(
            rs.getObject("id", UUID.class),
            rs.getString("key_hash"),
            rs.getString("name"),
            rs.getString("owner_id"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("last_used_at")).map(Timestamp::toInstant).orElse(null)
    );

    public Optional<ApiKey> findByHash(String hash) {
        return jdbc.query("SELECT * FROM api_keys WHERE key_hash = ? AND is_active = true", MAPPER, hash)
                .stream().findFirst();
    }

    public List<ApiKey> findByOwner(String ownerId) {
        return jdbc.query("SELECT * FROM api_keys WHERE owner_id = ? ORDER BY created_at DESC", MAPPER, ownerId);
    }

    public List<ApiKey> findAll() {
        return jdbc.query("SELECT * FROM api_keys ORDER BY created_at DESC", MAPPER);
    }

    public UUID insert(String keyHash, String name, String ownerId) {
        return jdbc.queryForObject("""
                INSERT INTO api_keys (key_hash, name, owner_id)
                VALUES (?, ?, ?)
                RETURNING id
                """, UUID.class, keyHash, name, ownerId);
    }

    public void touchLastUsed(UUID id) {
        jdbc.update("UPDATE api_keys SET last_used_at = now() WHERE id = ?", id);
    }

    public int deactivate(UUID id) {
        return jdbc.update("UPDATE api_keys SET is_active = false WHERE id = ?", id);
    }

    public Optional<ApiKey> findById(UUID id) {
        return jdbc.query("SELECT * FROM api_keys WHERE id = ?", MAPPER, id).stream().findFirst();
    }
}
