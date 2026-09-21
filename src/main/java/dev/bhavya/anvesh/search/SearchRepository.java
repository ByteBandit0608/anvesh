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
     * @param ownerId   tenant filter — null means no owner filter (backward compat for tests that don't set it)
     */
    public record Filter(String language, String metadataJson, String ownerId) {
        public static final Filter NONE = new Filter(null, null, null);
        public Filter(String language, String metadataJson) { this(language, metadataJson, null); }
        boolean isEmpty() { return language == null && metadataJson == null && ownerId == null; }
    }

    /** Cosine similarity via pgvector's {@code <=>} (cosine distance) operator; uses the HNSW index. */
    public List<SearchHit> vectorSearch(float[] queryVector, int limit) {
        return vectorSearch(queryVector, limit, Filter.NONE);
    }

    /**
     * WHY the filter is a WHERE clause on the same query, not a Java post-filter: with HNSW, "top-40 then
     * filter to language=te" can return 0 rows if the first 40 neighbours are all English. Postgres
     * applies the filter *while* walking the index (pgvector >=0.5 does this), so the caller still gets
     * `limit` rows. Trade-off: very selective filters make HNSW scan further — fine at our scale.
     */
    public List<SearchHit> vectorSearch(float[] queryVector, int limit, Filter f) {
        String vec = DocumentRepository.toVectorLiteral(queryVector);
        List<Object> args = new ArrayList<>(List.of(vec));
        String where = filterSql(f, args);
        args.add(vec);
        args.add(limit);
        // WHY explicit "\n" before ORDER BY / LIMIT: text-block concatenation is brittle.
        String sql = """
                SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.document_id
                WHERE d.status = 'INDEXED'
                """ + where + "\nORDER BY c.embedding <=> ?::vector\nLIMIT ?\n";
        return jdbc.query(sql, MAPPER, args.toArray());
    }

    public List<SearchHit> keywordSearch(String query, int limit) {
        return keywordSearch(query, limit, Filter.NONE);
    }

    public List<SearchHit> keywordSearch(String query, int limit, Filter f) {
        String tsquery = toOrQuery(query);
        if (tsquery.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(List.of(tsquery));
        String where = filterSql(f, args);
        args.add(limit);
        String sql = """
                SELECT c.id AS chunk_id, c.document_id, d.title, c.content,
                       ts_rank_cd(c.tsv, q) AS score
                FROM chunks c
                JOIN documents d ON d.id = c.document_id,
                     to_tsquery('simple', ?) q
                WHERE d.status = 'INDEXED' AND c.tsv @@ q
                """ + where + "\nORDER BY score DESC\nLIMIT ?\n";
        return jdbc.query(sql, MAPPER, args.toArray());
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
        if (f.ownerId() != null) {
            sb.append(" AND d.owner_id = ?");
            args.add(f.ownerId());
        }
        return sb.toString();
    }

    static String toOrQuery(String query) {
        if (query == null) return "";
        List<String> terms = new ArrayList<>();
        for (String t : query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (!t.isEmpty()) terms.add("'" + t.replace("'", "''") + "'");
        }
        return String.join(" | ", terms);
    }
}
