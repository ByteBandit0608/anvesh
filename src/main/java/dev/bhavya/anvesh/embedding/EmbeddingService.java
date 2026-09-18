package dev.bhavya.anvesh.embedding;

import java.util.List;

/**
 * Turns text into a fixed-size, L2-normalised float vector.
 * Implementations must be thread-safe: the ingest executor calls this concurrently.
 */
public interface EmbeddingService {

    int dimension();

    float[] embed(String text);

    /** Batch variant — ONNX models are far more efficient when given several texts at once. */
    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** Normalise in place to unit length so cosine similarity == dot product. */
    static float[] l2Normalise(float[] v) {
        double sumSq = 0;
        for (float x : v) sumSq += x * x;
        float norm = (float) Math.sqrt(sumSq);
        if (norm == 0f) return v;
        for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }
}
