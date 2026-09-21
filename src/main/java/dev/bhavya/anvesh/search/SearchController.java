package dev.bhavya.anvesh.search;

import dev.bhavya.anvesh.cache.SearchCacheService;
import dev.bhavya.anvesh.common.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search")
public class SearchController {

    private final SearchService search;
    private final SearchCacheService cache;

    public SearchController(SearchService search, SearchCacheService cache) {
        this.search = search;
        this.cache = cache;
    }

    public record SearchResponse(String query, String mode, int count, long tookMs, List<SearchHit> hits, boolean cached) {}

    @GetMapping
    @Operation(summary = "Search indexed chunks. mode = hybrid (default) | vector | keyword. "
            + "Filters: lang=te, filter=topic:ml (repeatable, matched via JSONB containment). "
            + "rrfK / candidateMultiplier override application.yml for tuning experiments.")
    public SearchResponse search(@RequestParam String q,
                                 @RequestParam(defaultValue = "hybrid") String mode,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String lang,
                                 @RequestParam(required = false) List<String> filter,
                                 @RequestParam(required = false) Integer rrfK,
                                 @RequestParam(required = false) Integer candidateMultiplier) {
        SearchService.Mode m;
        try {
            m = SearchService.Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be one of hybrid|vector|keyword");
        }
        String ownerId = RequestContext.ownerId();
        var baseFilter = new SearchRepository.Filter(blankToNull(lang), toMetadataJson(filter), ownerId);
        var opts = new SearchService.Options(baseFilter, rrfK, candidateMultiplier);
        long t0 = System.nanoTime();
        // Check cache manually to report cached flag
        String cacheKey = SearchCacheService.key(ownerId, q, m.name(), limit == null ? 10 : limit,
                baseFilter.language(), baseFilter.metadataJson(), rrfK, candidateMultiplier);
        boolean wasCached = cache.get(cacheKey) != null;
        List<SearchHit> hits = search.search(q, m, limit, opts);
        long took = (System.nanoTime() - t0) / 1_000_000;
        return new SearchResponse(q, m.name().toLowerCase(), hits.size(), took, hits, wasCached);
    }

    @DeleteMapping("/cache")
    @Operation(summary = "Invalidate search cache for current owner")
    public void invalidateCache() {
        cache.invalidateByOwner(RequestContext.ownerId());
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    /**
     * ["topic:ml", "year:2024"] -> {"topic":"ml","year":"2024"}. Values stay strings: metadata was stored
     * from JSON so {"year":2024} (number) would NOT be contained by {"year":"2024"} — documented in README.
     */
    static String toMetadataJson(List<String> filters) {
        if (filters == null || filters.isEmpty()) return null;
        var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for (String f : filters) {
            int i = f.indexOf(':');
            if (i <= 0 || i == f.length() - 1) throw new IllegalArgumentException("filter must be key:value, got '" + f + "'");
            node.put(f.substring(0, i), f.substring(i + 1));
        }
        return node.toString();
    }
}
