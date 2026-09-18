package dev.bhavya.anvesh.search;

import java.util.UUID;

/** One chunk-level result. {@code score} semantics depend on the retriever / fusion used. */
public record SearchHit(long chunkId, UUID documentId, String title, String snippet, double score) {
    public SearchHit withScore(double newScore) {
        return new SearchHit(chunkId, documentId, title, snippet, newScore);
    }
}
