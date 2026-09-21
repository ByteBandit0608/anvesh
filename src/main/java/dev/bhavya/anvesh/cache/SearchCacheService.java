package dev.bhavya.anvesh.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.bhavya.anvesh.config.AnveshProperties;
import dev.bhavya.anvesh.search.SearchHit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine in-memory cache for search results.
 * Key = hash of (owner + query + mode + limit + lang + filters + rrfK + mult)
 * WHY Caffeine not Redis (for now): zero infra, fast, good enough for single instance.
 * Week 5 adds Redis: docker-compose redis service + RedisSearchCache that implements same interface.
 * For now we record hit/miss counters so Grafana can show hit rate.
 */
@Service
public class SearchCacheService {

    private final Cache<String, List<SearchHit>> cache;
    private final boolean enabled;
    private final Counter hits;
    private final Counter misses;

    public SearchCacheService(AnveshProperties props, MeterRegistry registry) {
        this.enabled = props.cache() != null && props.cache().enabled();
        int maxSize = props.cache() != null ? props.cache().maxSize() : 1000;
        int ttlMin = props.cache() != null ? props.cache().ttlMinutes() : 5;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(ttlMin))
                .recordStats()
                .build();
        this.hits = Counter.builder("anvesh.cache.hits").description("Search cache hits").register(registry);
        this.misses = Counter.builder("anvesh.cache.misses").description("Search cache misses").register(registry);
        // Expose Caffeine stats via Micrometer? For now counters are enough.
    }

    public List<SearchHit> get(String key) {
        if (!enabled) return null;
        List<SearchHit> v = cache.getIfPresent(key);
        if (v != null) hits.increment(); else misses.increment();
        return v;
    }

    public void put(String key, List<SearchHit> hits) {
        if (!enabled) return;
        cache.put(key, hits);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public void invalidateByOwner(String ownerId) {
        // Caffeine doesn't support prefix scan efficiently; for simplicity clear all on ingest.
        // In production with Redis you'd do SCAN + DEL per owner.
        cache.invalidateAll();
    }

    public static String key(String ownerId, String query, String mode, int limit, String lang, String filterJson, Integer rrfK, Integer mult) {
        // Simple concatenation + hash to keep key short but unique. Use raw string as key for readability in debug.
        return ownerId + "|" + query + "|" + mode + "|" + limit + "|" + (lang == null ? "" : lang) + "|" + (filterJson == null ? "" : filterJson) + "|" + (rrfK == null ? "" : rrfK) + "|" + (mult == null ? "" : mult);
    }
}
