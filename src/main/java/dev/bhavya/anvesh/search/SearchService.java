package dev.bhavya.anvesh.search;

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

    public SearchService(SearchRepository repo, EmbeddingService embeddings, AnveshProperties props, MeterRegistry metrics) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.cfg = props.search();
        this.metrics = metrics;
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
        SearchRepository.Filter f = opt.filter() == null ? SearchRepository.Filter.NONE : opt.filter();
        Timer.Sample sample = Timer.start(metrics);
        try {
            return switch (mode) {
                case VECTOR -> repo.vectorSearch(embeddings.embed(query), limit, f);
                case KEYWORD -> repo.keywordSearch(query, limit, f);
                case HYBRID -> hybrid(query, limit, f, opt);
            };
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
