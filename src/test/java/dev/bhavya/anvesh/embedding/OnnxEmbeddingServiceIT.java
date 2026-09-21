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
 * Prefers the quantized model (118 MB) over fp32 (470 MB) to avoid OOM on low-RAM laptops.
 */
@EnabledIf("modelPresent")
class OnnxEmbeddingServiceIT {

    static final Path MODEL_FP32 = Path.of("models/paraphrase-multilingual-MiniLM-L12-v2/model.onnx");
    static final Path MODEL_Q = Path.of("models/paraphrase-multilingual-MiniLM-L12-v2/model_quint8_avx2.onnx");
    static final Path TOKENIZER = Path.of("models/paraphrase-multilingual-MiniLM-L12-v2/tokenizer.json");
    static OnnxEmbeddingService svc;
    static Path chosenModel;

    static boolean modelPresent() {
        return (Files.exists(MODEL_FP32) || Files.exists(MODEL_Q)) && Files.exists(TOKENIZER);
    }

    @BeforeAll
    static void load() {
        // Prefer quantized (smaller, faster, less RAM) if present
        chosenModel = Files.exists(MODEL_Q) ? MODEL_Q : MODEL_FP32;
        System.out.println("Loading ONNX model: " + chosenModel);
        svc = new OnnxEmbeddingService(chosenModel, TOKENIZER, 256, 384);
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
        assertThat(cosine(batch.get(0), single)).isCloseTo(1.0, within(1e-2));
    }
}
