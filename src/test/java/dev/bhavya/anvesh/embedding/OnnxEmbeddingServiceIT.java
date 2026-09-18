package dev.bhavya.anvesh.embedding;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs the real multilingual model. Only enabled when the model files are present locally
 * (they are git-ignored), so CI and fresh clones are unaffected.
 *
 * Expected values were produced with the Python reference implementation:
 *   SentenceTransformer("paraphrase-multilingual-MiniLM-L12-v2").encode(text, normalize_embeddings=True)
 */
@EnabledIf("modelPresent")
class OnnxEmbeddingServiceIT {

    static final Path MODEL = Path.of("models/paraphrase-multilingual-MiniLM-L12-v2/model.onnx");
    static final Path TOKENIZER = Path.of("models/paraphrase-multilingual-MiniLM-L12-v2/tokenizer.json");
    static OnnxEmbeddingService svc;

    static boolean modelPresent() {
        return Files.exists(MODEL) && Files.exists(TOKENIZER);
    }

    @BeforeAll
    static void load() {
        svc = new OnnxEmbeddingService(MODEL, TOKENIZER, 256, 384);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    @Test
    void producesUnitLengthVectorsOfCorrectSize() {
        float[] v = svc.embed("Hello world");
        assertThat(v).hasSize(384);
        assertThat(cosine(v, v)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void semanticallySimilarEnglishBeatsUnrelated() {
        float[] q = svc.embed("summer power consumption");
        float[] related = svc.embed("Electricity demand peaks in the summer months when air-conditioning load rises.");
        float[] unrelated = svc.embed("Pagination limits how many records an API returns per request.");
        System.out.printf("EN  related=%.3f unrelated=%.3f%n", cosine(q, related), cosine(q, unrelated));
        assertThat(cosine(q, related)).isGreaterThan(0.4);
        assertThat(cosine(q, related)).isGreaterThan(cosine(q, unrelated) + 0.2);
    }

    @Test
    void crossLingualTeluguEnglish() {
        float[] te = svc.embed("వేసవిలో విద్యుత్ డిమాండ్ పెరుగుతుంది");   // "electricity demand rises in summer"
        float[] en = svc.embed("Electricity demand rises in summer.");
        float[] off = svc.embed("How do I reset my password?");
        System.out.printf("TE->EN same=%.3f different=%.3f%n", cosine(te, en), cosine(te, off));
        assertThat(cosine(te, en)).isGreaterThan(0.6);
        assertThat(cosine(te, en)).isGreaterThan(cosine(te, off) + 0.3);
    }

    @Test
    void batchAndSingleAgree() {
        List<float[]> batch = svc.embedAll(List.of("short text", "a considerably longer sentence that pads the shorter one"));
        float[] single = svc.embed("short text");
        // Padding must not materially change the result (mean pooling respects the attention mask).
        // Tolerance is 0.01, not 0.001: the int8-quantised model accumulates small numeric
        // differences when sequence length changes. With the fp32 model this is ~1e-6.
        assertThat(cosine(batch.get(0), single)).isCloseTo(1.0, within(1e-2));
    }
}
