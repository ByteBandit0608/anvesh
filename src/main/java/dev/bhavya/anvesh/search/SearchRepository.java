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
     * Keyword search with Postgres full-text, using OR semantics.
     *
     * <p>Why OR: {@code websearch_to_tsquery} ANDs every term, and the {@code 'simple'} config
     * (chosen so Telugu isn't mangled by English stemming) does not remove stop words. So the
     * natural-language query "why does model accuracy drop over time" became
     * {@code why & does & model & ... & time} and matched nothing — silently turning hybrid
     * search into vector-only search. With OR, any matching term qualifies a chunk, and
     * {@code ts_rank_cd} still ranks chunks that match more terms higher. RRF then only
     * consumes the rank order, so recall goes up without hurting precision at the top.
     *
     * <p>Stop-word noise ("why", "does") is tolerable: those terms are rare in indexed text,
     * so they add little to the score when they do match.
     */
    public List<SearchHit> keywordSearch(String query, int limit) {
        String tsquery = toOrQuery(query);
        if (tsquery.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                       ts_rank_cd(c.tsv, q) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.document_id,
                     to_tsquery('simple', ?) q
                WHERE d.status = 'INDEXED' AND c.tsv @@ q
                ORDER BY score DESC
                LIMIT ?
                """, MAPPER, tsquery, limit);
    }

    /**
     * Builds a {@code to_tsquery}-compatible string: terms joined with {@code |}, each quoted
     * so tsquery syntax characters in user input ({@code & | ! ( ) : *}) can't break the query.
     *
     * <p>Splits on anything that is not a letter, digit <em>or combining mark</em> ({@code \\p{M}}).
     * The mark class matters: Telugu vowel signs and the virama (e.g. {@code ి} in {@code వి},
     * {@code ్} in {@code త్}) are combining marks, and splitting on them shreds
     * {@code విద్యుత్} into {@code వ ద య త}. Same applies to Hindi, Tamil, Arabic diacritics, etc.
     */
    static String toOrQuery(String query) {
        if (query == null) return "";
        List<String> terms = new java.util.ArrayList<>();
        for (String t : query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (!t.isEmpty()) terms.add("'" + t.replace("'", "''") + "'");
        }
        return String.join(" | ", terms);
    }
}
