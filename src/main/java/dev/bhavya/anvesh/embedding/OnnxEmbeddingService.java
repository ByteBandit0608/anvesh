package dev.bhavya.anvesh.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a sentence-transformers model exported to ONNX, fully in-process.
 *
 * <p>Default model: {@code paraphrase-multilingual-MiniLM-L12-v2} (384-dim, 50+ languages
 * including Telugu). Pipeline: tokenize -> transformer -> mean-pool over attention mask -> L2 normalise.
 * That pooling step is exactly what sentence-transformers does in Python; getting it right is
 * the difference between "works" and "returns garbage", so it's covered by a test.
 */
public class OnnxEmbeddingService implements EmbeddingService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingService.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int dimension;
    private final boolean needsTokenTypeIds;
    /** Non-null only when inference is serialised (see constructor). */
    private final Object inferenceLock;

    public OnnxEmbeddingService(Path modelPath, Path tokenizerPath, int maxTokens, int dimension) {
        this(modelPath, tokenizerPath, maxTokens, dimension, false);
    }

    /**
     * @param serializeInference if true, only one embedAll() runs at a time (Week-0 behaviour).
     *   WHY it's a flag and not just deleted: OrtSession.run() and the HuggingFace tokenizer are both
     *   documented thread-safe, so the lock was never needed for correctness — but whether removing it
     *   is *faster* depends on core count: ONNX Runtime already parallelises inside one run() call
     *   (intra-op threads), so N concurrent runs on N cores mostly fight each other. Measure with
     *   scripts/bench_ingest.py before deciding; record the numbers in docs/EMBEDDINGS.md.
     */
    public OnnxEmbeddingService(Path modelPath, Path tokenizerPath, int maxTokens, int dimension, boolean serializeInference) {
        this.inferenceLock = serializeInference ? new Object() : null;
        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = env.createSession(modelPath.toString(), opts);
            this.tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optMaxLength(maxTokens)
                    .optTruncation(true)
                    .optPadding(true)
                    .build();
            this.dimension = dimension;
            this.needsTokenTypeIds = session.getInputNames().contains("token_type_ids");
            log.info("Loaded ONNX embedding model {} (inputs={}, serializeInference={})", modelPath, session.getInputNames(), serializeInference);
        } catch (OrtException | java.io.IOException e) {
            throw new IllegalStateException("Failed to load ONNX embedding model from " + modelPath, e);
        }
    }

    @Override
    public int dimension() { return dimension; }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (inferenceLock == null) return doEmbed(texts);
        synchronized (inferenceLock) { return doEmbed(texts); }
    }

    private List<float[]> doEmbed(List<String> texts) {
        Encoding[] encodings = tokenizer.batchEncode(texts);
        int batch = encodings.length;
        int seqLen = encodings[0].getIds().length;

        long[][] ids = new long[batch][];
        long[][] mask = new long[batch][];
        long[][] types = new long[batch][seqLen];
        for (int i = 0; i < batch; i++) {
            ids[i] = encodings[i].getIds();
            mask[i] = encodings[i].getAttentionMask();
        }

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, ids));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, mask));
            if (needsTokenTypeIds) inputs.put("token_type_ids", OnnxTensor.createTensor(env, types));

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue(); // [batch][seq][hidden]
                return java.util.stream.IntStream.range(0, batch)
                        .mapToObj(i -> EmbeddingService.l2Normalise(meanPool(hidden[i], mask[i])))
                        .toList();
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    /** Mean of token vectors, ignoring padding positions (mask == 0). */
    static float[] meanPool(float[][] tokenVectors, long[] attentionMask) {
        int hidden = tokenVectors[0].length;
        float[] out = new float[hidden];
        int count = 0;
        for (int t = 0; t < tokenVectors.length; t++) {
            if (attentionMask[t] == 0) continue;
            count++;
            for (int h = 0; h < hidden; h++) out[h] += tokenVectors[t][h];
        }
        if (count == 0) return out;
        for (int h = 0; h < hidden; h++) out[h] /= count;
        return out;
    }

    @Override
    public void close() throws Exception {
        session.close();
        tokenizer.close();
    }
}
