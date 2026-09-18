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

    public List<SearchHit> search(String query, Mode mode, Integer requestedLimit) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        int limit = requestedLimit == null ? cfg.defaultLimit() : Math.min(Math.max(requestedLimit, 1), cfg.maxLimit());
        Timer.Sample sample = Timer.start(metrics);
        try {
            return switch (mode) {
                case VECTOR -> repo.vectorSearch(embeddings.embed(query), limit);
                case KEYWORD -> repo.keywordSearch(query, limit);
                case HYBRID -> hybrid(query, limit);
            };
        } finally {
            sample.stop(metrics.timer("anvesh.search", "mode", mode.name().toLowerCase()));
        }
    }

    private List<SearchHit> hybrid(String query, int limit) {
        int candidates = limit * cfg.candidateMultiplier();
        // Run both retrievers concurrently — they hit different indexes and are independent.
        CompletableFuture<List<SearchHit>> vec = CompletableFuture.supplyAsync(() -> repo.vectorSearch(embeddings.embed(query), candidates));
        CompletableFuture<List<SearchHit>> kw = CompletableFuture.supplyAsync(() -> repo.keywordSearch(query, candidates));
        return ReciprocalRankFusion.fuse(cfg.rrfK(), limit, vec.join(), kw.join());
    }
}
