package dev.bhavya.anvesh.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxEmbeddingServiceTest {

    @Test
    void meanPoolIgnoresPaddedPositions() {
        float[][] tokens = {
                {1f, 2f},   // real
                {3f, 4f},   // real
                {100f, 100f} // padding — must be ignored
        };
        long[] mask = {1, 1, 0};
        assertThat(OnnxEmbeddingService.meanPool(tokens, mask)).containsExactly(2f, 3f);
    }

    @Test
    void meanPoolWithAllMaskedReturnsZeros() {
        float[][] tokens = {{1f, 1f}};
        assertThat(OnnxEmbeddingService.meanPool(tokens, new long[]{0})).containsExactly(0f, 0f);
    }
}
