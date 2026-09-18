package dev.bhavya.anvesh.document;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC on purpose: pgvector's {@code <=>} operator, tsvector queries and
 * {@code ON CONFLICT} don't map cleanly onto JPA, and being explicit about SQL is
 * a better story in interviews than "Hibernate did it".
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Document> MAPPER = (rs, i) -> new Document(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("source"),
            rs.getString("language"),
            rs.getString("metadata"),
            rs.getString("content_hash"),
            Document.Status.valueOf(rs.getString("status")),
            rs.getString("error"),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("indexed_at")).map(Timestamp::toInstant).orElse(null)
    );

    /** Inserts, or returns the existing id when an identical body was already ingested. */
    public UUID insertOrGetExisting(String title, String source, String language, String metadataJson, String contentHash) {
        UUID id = jdbc.query("""
                INSERT INTO documents (title, source, language, metadata, content_hash)
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (content_hash) DO NOTHING
                RETURNING id
                """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                title, source, language, metadataJson, contentHash);
        if (id != null) return id;
        return jdbc.queryForObject("SELECT id FROM documents WHERE content_hash = ?", UUID.class, contentHash);
    }

    public Optional<Document> findById(UUID id) {
        return jdbc.query("SELECT * FROM documents WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Document> findAll(int limit, int offset) {
        return jdbc.query("SELECT * FROM documents ORDER BY created_at DESC LIMIT ? OFFSET ?", MAPPER, limit, offset);
    }

    public boolean isNew(UUID id) {
        Integer chunks = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id);
        return chunks != null && chunks == 0;
    }

    public void markIndexed(UUID id) {
        jdbc.update("UPDATE documents SET status = 'INDEXED', indexed_at = now(), error = NULL WHERE id = ?", id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("UPDATE documents SET status = 'FAILED', error = ? WHERE id = ?", error, id);
    }

    public int delete(UUID id) {
        return jdbc.update("DELETE FROM documents WHERE id = ?", id);
    }

    public void insertChunks(UUID documentId, List<String> contents, List<float[]> embeddings) {
        if (contents.size() != embeddings.size()) throw new IllegalArgumentException("contents/embeddings size mismatch");
        jdbc.batchUpdate("""
                INSERT INTO chunks (document_id, ordinal, content, embedding)
                VALUES (?, ?, ?, ?::vector)
                ON CONFLICT (document_id, ordinal) DO NOTHING
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setObject(1, documentId);
                ps.setInt(2, i);
                ps.setString(3, contents.get(i));
                ps.setString(4, toVectorLiteral(embeddings.get(i)));
            }
            @Override
            public int getBatchSize() { return contents.size(); }
        });
    }

    /** pgvector accepts the textual form "[0.1,0.2,...]" — simplest driver-agnostic path. */
    public static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
