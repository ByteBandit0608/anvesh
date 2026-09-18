package dev.bhavya.anvesh.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HashEmbeddingServiceTest {

    private final HashEmbeddingService svc = new HashEmbeddingService(384);

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // both unit-length
    }

    @Test
    void isDeterministicAndUnitLength() {
        float[] a = svc.embed("electricity demand in Telangana");
        float[] b = svc.embed("electricity demand in Telangana");
        assertThat(a).containsExactly(b);
        assertThat(cosine(a, a)).isCloseTo(1.0, within(1e-5));
        assertThat(a).hasSize(384);
    }

    @Test
    void similarTextsAreCloserThanUnrelatedOnes() {
        float[] q = svc.embed("weather data and electricity demand");
        float[] related = svc.embed("electricity demand depends on weather");
        float[] unrelated = svc.embed("java spring boot rest api");
        assertThat(cosine(q, related)).isGreaterThan(cosine(q, unrelated));
    }

    @Test
    void handlesTeluguWithoutCrashing() {
        float[] v = svc.embed("తెలుగు భాష చాలా అందమైనది");
        assertThat(cosine(v, v)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void blankTextIsZeroVector() {
        assertThat(svc.embed("   ")).containsOnly(0f);
    }
}
