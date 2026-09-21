package dev.bhavya.anvesh.search;

import dev.bhavya.anvesh.cache.SearchCacheService;
import dev.bhavya.anvesh.common.RequestContext;
import dev.bhavya.anvesh.config.AnveshProperties;
import dev.bhavya.anvesh.embedding.EmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class SearchService {

    public enum Mode { HYBRID, VECTOR, KEYWORD }

    private final SearchRepository repo;
    private final EmbeddingService embeddings;
    private final AnveshProperties.Search cfg;
    private final MeterRegistry metrics;
    private final SearchCacheService cache;

    public SearchService(SearchRepository repo, EmbeddingService embeddings, AnveshProperties props,
                         MeterRegistry metrics, SearchCacheService cache) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.cfg = props.search();
        this.metrics = metrics;
        this.cache = cache;
    }

    /**
     * Per-request knobs. {@code rrfK} / {@code candidateMultiplier} default to application.yml and exist
     * so the eval harness can sweep them without restarting the app. Not something a normal client sets.
     */
    public record Options(SearchRepository.Filter filter, Integer rrfK, Integer candidateMultiplier) {
        public static final Options DEFAULT = new Options(SearchRepository.Filter.NONE, null, null);
    }

    public List<SearchHit> search(String query, Mode mode, Integer requestedLimit) {
        return search(query, mode, requestedLimit, Options.DEFAULT);
    }

    public List<SearchHit> search(String query, Mode mode, Integer requestedLimit, Options opt) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        int limit = requestedLimit == null ? cfg.defaultLimit() : Math.min(Math.max(requestedLimit, 1), cfg.maxLimit());
        // Merge owner from RequestContext into filter
        String ownerId = RequestContext.ownerId();
        SearchRepository.Filter base = opt.filter() == null ? SearchRepository.Filter.NONE : opt.filter();
        // If filter already has owner, respect it; else use current request owner, but only if not public?
        // For multi-tenant isolation: always filter by owner unless explicitly public search.
        // For backward compat with tests that have no owner, we treat "public" as owner filter too (public docs).
        SearchRepository.Filter f = new SearchRepository.Filter(
                base.language(),
                base.metadataJson(),
                ownerId // always scope to owner; public owner sees only public docs
        );
        Options withOwner = new Options(f, opt.rrfK(), opt.candidateMultiplier());

        // Cache check (only for exact same query+mode+limit+filter)
        String cacheKey = SearchCacheService.key(ownerId, query, mode.name(), limit,
                f.language(), f.metadataJson(), withOwner.rrfK(), withOwner.candidateMultiplier());
        List<SearchHit> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        Timer.Sample sample = Timer.start(metrics);
        try {
            List<SearchHit> result = switch (mode) {
                case VECTOR -> repo.vectorSearch(embeddings.embed(query), limit, f);
                case KEYWORD -> repo.keywordSearch(query, limit, f);
                case HYBRID -> hybrid(query, limit, f, withOwner);
            };
            cache.put(cacheKey, result);
            return result;
        } finally {
            sample.stop(metrics.timer("anvesh.search", "mode", mode.name().toLowerCase()));
        }
    }

    private List<SearchHit> hybrid(String query, int limit, SearchRepository.Filter f, Options opt) {
        int k = opt.rrfK() == null ? cfg.rrfK() : Math.max(1, opt.rrfK());
        int mult = opt.candidateMultiplier() == null ? cfg.candidateMultiplier() : Math.max(1, Math.min(opt.candidateMultiplier(), 20));
        int candidates = limit * mult;
        // Run both retrievers concurrently — they hit different indexes and are independent.
        CompletableFuture<List<SearchHit>> vec = CompletableFuture.supplyAsync(() -> repo.vectorSearch(embeddings.embed(query), candidates, f));
        CompletableFuture<List<SearchHit>> kw = CompletableFuture.supplyAsync(() -> repo.keywordSearch(query, candidates, f));
        return ReciprocalRankFusion.fuse(k, limit, vec.join(), kw.join());
    }
}
