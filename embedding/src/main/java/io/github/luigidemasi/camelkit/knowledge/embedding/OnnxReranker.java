package io.github.luigidemasi.camelkit.knowledge.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-encoder reranker (ms-marco-MiniLM-L-6-v2 via ONNX Runtime). Scores a (query, passage) pair by reading both
 * together — far more precise than bi-encoder similarity for the final top-N ordering. Thread-safe. Model loaded lazily
 * on first call.
 */
public class OnnxReranker {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxReranker.class);
    private static final int MAX_SEQ_LENGTH = 512;
    private static final String MODEL_FILE = "models/reranker_quantized.onnx";
    private static final String TOKENIZER_FILE = "models/reranker-tokenizer.json";

    private volatile OrtSession session;
    private volatile OrtEnvironment env;
    private volatile HuggingFaceTokenizer tokenizer;
    private volatile boolean needsTokenTypeIds;
    private final Object lock = new Object();

    /**
     * Relevance score for a (query, passage) pair, mapped to (0, 1) via sigmoid. Higher is more relevant.
     */
    public float score(String query, String passage) {
        ensureInitialized();
        try {
            Encoding encoding = tokenizer.encode(query, passage);
            long[] inputIds = encoding.getIds();
            long[] shape = {1, inputIds.length};

            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));
            inputs.put("attention_mask",
                    OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.getAttentionMask()), shape));
            if (needsTokenTypeIds) {
                inputs.put("token_type_ids",
                        OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.getTypeIds()), shape));
            }

            try (OrtSession.Result result = session.run(inputs)) {
                float logit = ((float[][]) result.get(0).getValue())[0][0];
                return (float) (1.0 / (1.0 + Math.exp(-logit)));
            } finally {
                inputs.values().forEach(OnnxTensor::close);
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX reranker inference failed", e);
        }
    }

    private void ensureInitialized() {
        if (session == null) {
            synchronized (lock) {
                if (session == null) {
                    try {
                        try (InputStream is = getClass().getClassLoader().getResourceAsStream(TOKENIZER_FILE)) {
                            if (is == null) {
                                throw new RuntimeException(TOKENIZER_FILE + " not found on classpath");
                            }
                            tokenizer = HuggingFaceTokenizer.newInstance(is, Map.of(
                                    "padding", "false",
                                    "truncation", "true",
                                    "maxLength", String.valueOf(MAX_SEQ_LENGTH)));
                        }

                        byte[] modelBytes;
                        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MODEL_FILE)) {
                            if (is == null) {
                                throw new RuntimeException(MODEL_FILE + " not found on classpath");
                            }
                            modelBytes = is.readAllBytes();
                        }

                        env = OrtEnvironment.getEnvironment();
                        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                        OrtSession s = env.createSession(modelBytes, opts);
                        Set<String> inputNames = s.getInputNames();
                        needsTokenTypeIds = inputNames.contains("token_type_ids");
                        session = s;

                        LOG.info("ONNX reranker loaded (ms-marco-MiniLM-L-6-v2, inputs: {})", inputNames);
                    } catch (IOException | OrtException e) {
                        throw new RuntimeException("Failed to load ONNX reranker model", e);
                    }
                }
            }
        }
    }
}
