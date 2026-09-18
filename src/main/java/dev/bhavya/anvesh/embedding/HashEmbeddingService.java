package dev.bhavya.anvesh.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Dependency-free "feature hashing" embedder.
 *
 * <p>Each word (and each character trigram, so Telugu / agglutinative text still gets
 * signal without a tokenizer) is hashed to a bucket in a {@code dimension}-sized vector.
 * Texts that share vocabulary end up close in cosine space, so it behaves like a crude
 * bag-of-words model — good enough to make the whole pipeline testable without a
 * 400 MB model download. Swap in {@link OnnxEmbeddingService} for real semantics.
 */
public class HashEmbeddingService implements EmbeddingService {

    private final int dimension;

    public HashEmbeddingService(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public int dimension() { return dimension; }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimension];
        if (text == null || text.isBlank()) return v;

        String norm = text.toLowerCase(Locale.ROOT);
        for (String word : norm.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            addFeature(v, "w:" + word, 1.0f);
            // Character trigrams give partial-match signal (e.g. inflected forms).
            if (word.length() >= 3) {
                for (int i = 0; i + 3 <= word.length(); i++) {
                    addFeature(v, "t:" + word.substring(i, i + 3), 0.3f);
                }
            }
        }
        return EmbeddingService.l2Normalise(v);
    }

    private void addFeature(float[] v, String feature, float weight) {
        byte[] h = sha256(feature);
        int bucket = Math.floorMod(bytesToInt(h, 0), dimension);
        // Second hash decides the sign — the standard "hashing trick" to reduce collision bias.
        float sign = (h[4] & 1) == 0 ? 1f : -1f;
        v[bucket] += sign * weight;
    }

    private static int bytesToInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
