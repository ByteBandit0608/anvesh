package dev.bhavya.anvesh.search;

import dev.bhavya.anvesh.document.DocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class SearchRepository {

    private final JdbcTemplate jdbc;

    public SearchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<SearchHit> MAPPER = (rs, i) -> new SearchHit(
            rs.getLong("chunk_id"),
            rs.getObject("document_id", UUID.class),
            rs.getString("title"),
            rs.getString("content"),
            rs.getDouble("score"));

    /** Cosine similarity via pgvector's {@code <=>} (cosine distance) operator; uses the HNSW index. */
    public List<SearchHit> vectorSearch(float[] queryVector, int limit) {
        String vec = DocumentRepository.toVectorLiteral(queryVector);
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.document_id
                WHERE d.status = 'INDEXED'
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """, MAPPER, vec, vec, limit);
    }

    /**
     * Keyword search with Postgres full-text. {@code websearch_to_tsquery} understands
     * quoted phrases and "-exclusions" like a search box; 'simple' config keeps it language-neutral.
     */
    public List<SearchHit> keywordSearch(String query, int limit) {
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                       ts_rank_cd(c.tsv, q) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.document_id,
                     websearch_to_tsquery('simple', ?) q
                WHERE d.status = 'INDEXED' AND c.tsv @@ q
                ORDER BY score DESC
                LIMIT ?
                """, MAPPER, query, limit);
    }
}
