package dev.bhavya.anvesh.security;

import dev.bhavya.anvesh.config.AnveshProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-owner token bucket.
 * WHY Bucket4j: battle-tested, no Redis needed for single-instance. For multi-instance,
 * swap to Bucket4j + Redis (or move to Redis Cell) — same API.
 * Config: requestsPerMinute + burstCapacity from application.yml.
 */
@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AnveshProperties props;
    private final Counter allowed;
    private final Counter denied;

    public RateLimitService(AnveshProperties props, MeterRegistry registry) {
        this.props = props;
        this.allowed = Counter.builder("anvesh.ratelimit.allowed").description("Rate-limit allowed requests").register(registry);
        this.denied = Counter.builder("anvesh.ratelimit.denied").description("Rate-limit denied requests").register(registry);
    }

    public boolean tryConsume(String ownerId) {
        if (props.rateLimit() == null || !props.rateLimit().enabled()) return true;
        Bucket b = buckets.computeIfAbsent(ownerId, this::newBucket);
        boolean ok = b.tryConsume(1);
        if (ok) allowed.increment(); else denied.increment();
        return ok;
    }

    private Bucket newBucket(String ownerId) {
        int rpm = props.rateLimit().requestsPerMinute();
        int burst = props.rateLimit().burstCapacity();
        Bandwidth bw = Bandwidth.builder()
                .capacity(burst)
                .refillGreedy(rpm, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(bw).build();
    }

    public void reset(String ownerId) {
        buckets.remove(ownerId);
    }
}
