package dev.bhavya.anvesh.search;

import dev.bhavya.anvesh.document.DocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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

    /**
     * Optional restrictions applied to both retrievers.
     * @param language  exact match on documents.language (e.g. "te"), or null
     * @param metadata  JSONB containment: documents.metadata @> metadata (e.g. {"topic":"ml"}), or null.
     *                  Containment uses the GIN index from V1 — this is the whole reason metadata is JSONB.
     */
    public record Filter(String language, String metadataJson) {
        public static final Filter NONE = new Filter(null, null);
        boolean isEmpty() { return language == null && metadataJson == null; }
    }

    /** Cosine similarity via pgvector's {@code <=>} (cosine distance) operator; uses the HNSW index. */
    public List<SearchHit> vectorSearch(float[] queryVector, int limit) {
        return vectorSearch(queryVector, limit, Filter.NONE);
    }

    /**
     * WHY the filter is a WHERE clause on the same query, not a Java post-filter: with HNSW, "top-40 then
     * filter to language=te" can return 0 rows if the first 40 neighbours are all English. Postgres
     * applies the filter *while* walking the index (pgvector ≥0.5 does this), so the caller still gets
     * `limit` rows. Trade-off: very selective filters make HNSW scan further — fine at our scale.
     */
    public List<SearchHit> vectorSearch(float[] queryVector, int limit, Filter f) {
        String vec = DocumentRepository.toVectorLiteral(queryVector);
        List<Object> args = new ArrayList<>(List.of(vec));
        String where = filterSql(f, args);
        args.add(vec);
        args.add(limit);
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.document_id
                WHERE d.status = 'INDEXED'
                """ + where + """
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """, MAPPER, args.toArray());
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
        return keywordSearch(query, limit, Filter.NONE);
    }

    public List<SearchHit> keywordSearch(String query, int limit, Filter f) {
        String tsquery = toOrQuery(query);
        if (tsquery.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(List.of(tsquery));
        String where = filterSql(f, args);
        args.add(limit);
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                       ts_rank_cd(c.tsv, q) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.document_id,
                     to_tsquery('simple', ?) q
                WHERE d.status = 'INDEXED' AND c.tsv @@ q
                """ + where + """
                ORDER BY score DESC
                LIMIT ?
                """, MAPPER, args.toArray());
    }

    /** Appends parameterised predicates; never interpolates user input into SQL. */
    static String filterSql(Filter f, List<Object> args) {
        if (f == null || f.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (f.language() != null) {
            sb.append(" AND d.language = ?");
            args.add(f.language());
        }
        if (f.metadataJson() != null) {
            sb.append(" AND d.metadata @> ?::jsonb");
            args.add(f.metadataJson());
        }
        return sb.toString();
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
        List<String> terms = new ArrayList<>();
        for (String t : query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (!t.isEmpty()) terms.add("'" + t.replace("'", "''") + "'");
        }
        return String.join(" | ", terms);
    }
}
