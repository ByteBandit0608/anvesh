package dev.bhavya.anvesh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code anvesh.*} block in application.yml. */
@ConfigurationProperties(prefix = "anvesh")
public record AnveshProperties(Embedding embedding, Chunking chunking, Search search, Ingest ingest) {

    public record Embedding(String provider, int dimension, Onnx onnx) {
        public record Onnx(String modelPath, String tokenizerPath, int maxTokens, boolean serializeInference) {}
    }

    public record Chunking(int maxChars, int overlapChars) {}

    public record Search(int defaultLimit, int maxLimit, int rrfK, int candidateMultiplier) {}

    public record Ingest(int maxBatchSize) {}
}
