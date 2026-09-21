package dev.bhavya.anvesh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code anvesh.*} block in application.yml. */
@ConfigurationProperties(prefix = "anvesh")
public record AnveshProperties(Embedding embedding, Chunking chunking, Search search, Ingest ingest,
                               Security security, RateLimit rateLimit, Cache cache) {

    public record Embedding(String provider, int dimension, Onnx onnx) {
        public record Onnx(String modelPath, String tokenizerPath, int maxTokens, boolean serializeInference) {}
    }

    public record Chunking(int maxChars, int overlapChars) {}

    public record Search(int defaultLimit, int maxLimit, int rrfK, int candidateMultiplier) {}

    public record Ingest(int maxBatchSize) {}

    public record Security(boolean requireApiKey, String anonymousOwner) {
        public Security {
            if (anonymousOwner == null || anonymousOwner.isBlank()) anonymousOwner = "public";
        }
    }

    public record RateLimit(boolean enabled, int requestsPerMinute, int burstCapacity) {}

    public record Cache(boolean enabled, int maxSize, int ttlMinutes) {}
}
