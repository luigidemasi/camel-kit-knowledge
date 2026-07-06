package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import io.github.luigidemasi.camelkit.knowledge.embedding.EmbeddingProvider;
import io.github.luigidemasi.camelkit.knowledge.schema.KnowledgeDocument;
import io.github.luigidemasi.camelkit.knowledge.schema.KnowledgeFields;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Empirical comparison of embedding models. The corpus is read from the <em>production index's stored fields</em> (so
 * it always matches what the real chunker/indexer produced), re-embedded with each candidate model into an in-memory
 * index, and evaluated with the shared {@link EvalDataset} ground truth.
 *
 * <p>
 * The primary signal is <b>vector-only</b> retrieval (BM25 is identical across models and would mask differences);
 * hybrid 0.2/0.8 is reported as a secondary, production-shaped signal.
 *
 * <p>
 * The corpus is sampled to bound runtime: all chunks matching a qrel key are always included, plus every Nth remaining
 * chunk up to {@code model.comparison.corpus} (default 3000). The sampling is deterministic and the dropped count is
 * printed — no silent truncation.
 *
 * Run with: {@code mvn test -pl mcp -Dmodel.comparison=true -Dtest=ModelComparisonTest}
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "model.comparison", matches = "true")
class ModelComparisonTest {

    private static final String HF_RESOLVE = "https://huggingface.co/%s/resolve/main/%s";
    private static final Path MODELS_DIR = Path.of("target/test-models");
    private static final String DOMAIN = "apache_camel";

    /** Matches production embedding truncation (see OnnxEmbeddingProvider). */
    private static final int MAX_SEQ_LENGTH = Integer.getInteger("embedding.maxLength", 2048);

    private static final int CORPUS_CAP = Integer.getInteger("model.comparison.corpus", 3000);

    @Inject
    LuceneSearchService searchService;

    record ModelConfig(String name, String repo, String[] onnxFiles, String tokenizerFile, int dimensions) {
    }

    static final ModelConfig GRANITE_SMALL = new ModelConfig(
            "granite-small-r2 (Q8, 384d)",
            "onnx-community/granite-embedding-small-english-r2-ONNX",
            new String[]{"onnx/model_quantized.onnx", "onnx/model_quantized.onnx_data"},
            "tokenizer.json",
            384);

    static final ModelConfig GRANITE_BASE = new ModelConfig(
            "granite-base-r2 (Q8, 768d)",
            "onnx-community/granite-embedding-english-r2-ONNX",
            new String[]{"onnx/model_quantized.onnx", "onnx/model_quantized.onnx_data"},
            "tokenizer.json",
            768);

    record DocChunk(String id, String sectionTitle, String content) {
    }

    static class ModelResult {
        String name;
        long modelSizeMB;
        double avgEmbedMs;
        // [ndcg@10, mrr@10, r@10, r@30] means, vector-only and hybrid
        double[] vectorOnly;
        double[] hybrid;
    }

    @Test
    void compareModels() throws Exception {
        EvalDataset dataset = EvalDataset.load();
        List<DocChunk> chunks = loadCorpusFromProductionIndex(dataset);
        assertFalse(chunks.isEmpty(), "Corpus is empty — production index has no stored chunks");

        System.out.printf("%nCorpus: %d chunks sampled from production index (cap %d)%n%n",
                chunks.size(), CORPUS_CAP);

        List<ModelResult> results = new ArrayList<>();
        for (ModelConfig config : List.of(GRANITE_SMALL, GRANITE_BASE)) {
            System.out.printf("Preparing %s...%n", config.name());
            Path modelDir = ensureModelDownloaded(config);
            try (FileBasedEmbeddingProvider provider = new FileBasedEmbeddingProvider(
                    modelDir, "model_quantized.onnx", config.dimensions(), MAX_SEQ_LENGTH)) {
                ModelResult mr = evaluateModel(config.name(), provider, chunks, dataset);
                mr.modelSizeMB = dirSizeMB(modelDir);
                results.add(mr);
            }
        }

        printComparison(results, dataset);
    }

    /**
     * Reads (id, sectionTitle, content) from the production index. All qrel-matching chunks are kept; the rest is
     * downsampled deterministically to the cap.
     */
    private List<DocChunk> loadCorpusFromProductionIndex(EvalDataset dataset) throws IOException {
        List<String> allKeys = new ArrayList<>();
        for (EvalDataset.EvalQuery q : dataset.queries()) {
            for (EvalDataset.Qrel qrel : dataset.qrels(q.id())) {
                allKeys.add(qrel.key());
            }
        }

        IndexReader reader = searchService.getSearcher().getIndexReader();
        List<DocChunk> relevant = new ArrayList<>();
        List<DocChunk> others = new ArrayList<>();

        for (int i = 0; i < reader.maxDoc(); i++) {
            Document doc = reader.document(i);
            String id = doc.get(KnowledgeFields.ID);
            String content = doc.get(KnowledgeFields.CONTENT);
            if (id == null || content == null) {
                continue;
            }
            DocChunk chunk = new DocChunk(id, doc.get(KnowledgeFields.SECTION_TITLE), content);
            if (allKeys.stream().anyMatch(id::contains)) {
                relevant.add(chunk);
            } else {
                others.add(chunk);
            }
        }

        int room = Math.max(0, CORPUS_CAP - relevant.size());
        List<DocChunk> sampled = new ArrayList<>(relevant);
        if (room > 0 && !others.isEmpty()) {
            int step = Math.max(1, others.size() / room);
            for (int i = 0; i < others.size() && sampled.size() < CORPUS_CAP; i += step) {
                sampled.add(others.get(i));
            }
        }
        System.out.printf("Corpus sampling: %d qrel-matching + %d distractors (dropped %d of %d total)%n",
                relevant.size(), sampled.size() - relevant.size(),
                reader.maxDoc() - sampled.size(), reader.maxDoc());
        return sampled;
    }

    private ModelResult evaluateModel(
            String name, EmbeddingProvider provider, List<DocChunk> chunks, EvalDataset dataset)
            throws Exception {
        System.out.printf("  Embedding + indexing %d chunks with %s...%n", chunks.size(), name);

        Directory dir = new ByteBuffersDirectory();
        long start = System.nanoTime();
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            int count = 0;
            for (DocChunk chunk : chunks) {
                String title = chunk.sectionTitle() != null ? chunk.sectionTitle() : "";
                float[] embedding = provider.embed(title + " " + chunk.content());
                writer.addDocument(new KnowledgeDocument(chunk.id(), DOMAIN)
                        .source("eval").docType("eval")
                        .sectionTitle(title)
                        .content(chunk.content())
                        .embedding(embedding)
                        .build());
                if (++count % 500 == 0) {
                    System.out.printf("    %d/%d...%n", count, chunks.size());
                }
            }
            writer.commit();
        }
        double avgMs = (System.nanoTime() - start) / (chunks.size() * 1_000_000.0);

        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(dir));

        ModelResult mr = new ModelResult();
        mr.name = name;
        mr.avgEmbedMs = avgMs;
        mr.vectorOnly = evaluateWeights(searcher, provider, dataset, 0.0f, 1.0f);
        mr.hybrid = evaluateWeights(searcher, provider, dataset, 0.2f, 0.8f);
        return mr;
    }

    /** Returns mean [nDCG@10, MRR@10, Recall@10, Recall@30] over all queries. */
    private double[] evaluateWeights(
            IndexSearcher searcher, EmbeddingProvider provider, EvalDataset dataset,
            float bm25Weight, float vectorWeight)
            throws Exception {
        double[] sums = new double[4];
        for (EvalDataset.EvalQuery q : dataset.queries()) {
            List<LuceneSearchService.SearchResult> results = LuceneSearchService.hybridSearch(
                    searcher, provider, DOMAIN, q.text(), null, null, 30, bm25Weight, vectorWeight);
            List<String> ids = results.stream().map(LuceneSearchService.SearchResult::id).toList();
            List<EvalDataset.Qrel> qrels = dataset.qrels(q.id());
            sums[0] += EvalDataset.ndcgAtK(ids, qrels, 10);
            sums[1] += EvalDataset.mrrAtK(ids, qrels, 10);
            sums[2] += EvalDataset.recallAtK(ids, qrels, 10);
            sums[3] += EvalDataset.recallAtK(ids, qrels, 30);
        }
        int n = dataset.queries().size();
        for (int i = 0; i < sums.length; i++) {
            sums[i] /= n;
        }
        return sums;
    }

    private void printComparison(List<ModelResult> results, EvalDataset dataset) {
        System.out.printf("%n=== MODEL COMPARISON (%d queries, embed+index time) ===%n%n", dataset.queries().size());
        System.out.printf("%-28s %-12s %10s %10s %10s %10s %14s %10s%n",
                "Model", "Mode", "nDCG@10", "MRR@10", "R@10", "R@30", "embed(ms/chk)", "size(MB)");
        for (ModelResult mr : results) {
            System.out.printf("%-28s %-12s %10.3f %10.3f %10.3f %10.3f %14.1f %10d%n",
                    mr.name, "vector-only",
                    mr.vectorOnly[0], mr.vectorOnly[1], mr.vectorOnly[2], mr.vectorOnly[3],
                    mr.avgEmbedMs, mr.modelSizeMB);
            System.out.printf("%-28s %-12s %10.3f %10.3f %10.3f %10.3f%n",
                    "", "hybrid .2/.8",
                    mr.hybrid[0], mr.hybrid[1], mr.hybrid[2], mr.hybrid[3]);
        }
        System.out.println();
    }

    // ── Model download ──────────────────────────────────────────────────

    private Path ensureModelDownloaded(ModelConfig config) throws Exception {
        String dirName = config.repo().substring(config.repo().indexOf('/') + 1);
        Path modelDir = MODELS_DIR.resolve(dirName);
        Files.createDirectories(modelDir);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String onnxFile : config.onnxFiles()) {
            String fileName = onnxFile.substring(onnxFile.lastIndexOf('/') + 1);
            Path target = modelDir.resolve(fileName);
            if (!Files.exists(target)) {
                downloadFile(client, String.format(HF_RESOLVE, config.repo(), onnxFile), target);
            }
        }

        Path tokenizerTarget = modelDir.resolve("tokenizer.json");
        if (!Files.exists(tokenizerTarget)) {
            downloadFile(client, String.format(HF_RESOLVE, config.repo(), config.tokenizerFile()), tokenizerTarget);
        }

        return modelDir;
    }

    private void downloadFile(HttpClient client, String url, Path target) throws Exception {
        System.out.printf("  Downloading %s ...%n", url);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + url);
        }
        try (InputStream is = response.body()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.printf("  Downloaded %s (%d MB)%n", target.getFileName(), Files.size(target) / (1024 * 1024));
    }

    private long dirSizeMB(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            long bytes = stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
            return bytes / (1024 * 1024);
        }
    }

    // ── File-based embedding provider for downloaded models ─────────────

    static class FileBasedEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

        private final int dimensions;
        private final OrtSession session;
        private final OrtEnvironment env;
        private final HuggingFaceTokenizer tokenizer;
        private final boolean needsTokenTypeIds;

        FileBasedEmbeddingProvider(Path modelDir, String modelFile, int dimensions, int maxSeqLength)
                                                                                                      throws Exception {
            this.dimensions = dimensions;

            Path tokenizerPath = modelDir.resolve("tokenizer.json");
            try (InputStream is = Files.newInputStream(tokenizerPath)) {
                tokenizer = HuggingFaceTokenizer.newInstance(is, Map.of(
                        "padding", "false",
                        "truncation", "true",
                        "maxLength", String.valueOf(maxSeqLength)));
            }

            env = OrtEnvironment.getEnvironment();
            session = env.createSession(modelDir.resolve(modelFile).toString());
            needsTokenTypeIds = session.getInputNames().contains("token_type_ids");
        }

        @Override
        public float[] embed(String text) {
            try {
                Encoding encoding = tokenizer.encode(text);
                long[] inputIds = encoding.getIds();
                long[] attentionMask = encoding.getAttentionMask();
                long[] shape = {1, inputIds.length};

                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape));
                inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape));
                if (needsTokenTypeIds) {
                    inputs.put("token_type_ids",
                            OnnxTensor.createTensor(env, LongBuffer.wrap(new long[inputIds.length]), shape));
                }

                try (OrtSession.Result result = session.run(inputs)) {
                    Object output = result.get(0).getValue();
                    float[] pooled;
                    if (output instanceof float[][][]) {
                        pooled = meanPool(((float[][][]) output)[0], attentionMask);
                    } else if (output instanceof float[][]) {
                        pooled = ((float[][]) output)[0];
                    } else {
                        throw new RuntimeException("Unexpected ONNX output type: " + output.getClass());
                    }
                    return l2Normalize(pooled);
                } finally {
                    inputs.values().forEach(OnnxTensor::close);
                }
            } catch (OrtException e) {
                throw new RuntimeException("ONNX inference failed", e);
            }
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
            int dims = tokenEmbeddings[0].length;
            float[] pooled = new float[dims];
            float maskSum = 0;
            for (int t = 0; t < tokenEmbeddings.length; t++) {
                float mask = attentionMask[t];
                maskSum += mask;
                for (int d = 0; d < dims; d++) {
                    pooled[d] += tokenEmbeddings[t][d] * mask;
                }
            }
            if (maskSum > 0) {
                for (int d = 0; d < dims; d++) {
                    pooled[d] /= maskSum;
                }
            }
            return pooled;
        }

        private float[] l2Normalize(float[] vector) {
            float norm = 0;
            for (float v : vector) {
                norm += v * v;
            }
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < vector.length; i++) {
                    vector[i] /= norm;
                }
            }
            return vector;
        }

        @Override
        public void close() throws Exception {
            if (session != null) {
                session.close();
            }
        }
    }
}
