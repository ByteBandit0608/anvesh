package dev.bhavya.anvesh.search;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) { this.search = search; }

    public record SearchResponse(String query, String mode, int count, long tookMs, List<SearchHit> hits) {}

    @GetMapping
    @Operation(summary = "Search indexed chunks. mode = hybrid (default) | vector | keyword")
    public SearchResponse search(@RequestParam String q,
                                 @RequestParam(defaultValue = "hybrid") String mode,
                                 @RequestParam(required = false) Integer limit) {
        SearchService.Mode m;
        try {
            m = SearchService.Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be one of hybrid|vector|keyword");
        }
        long t0 = System.nanoTime();
        List<SearchHit> hits = search.search(q, m, limit);
        long took = (System.nanoTime() - t0) / 1_000_000;
        return new SearchResponse(q, m.name().toLowerCase(), hits.size(), took, hits);
    }
}
