package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retrieval quality evaluation against the real production index, using the externalized ground truth in
 * {@code eval/queries.tsv} + {@code eval/qrels.tsv} (see {@link EvalDataset}).
 *
 * <ul>
 * <li>{@link #productionSearchQuality()} — always on: evaluates the full production path
 * ({@code LuceneSearchService.search()}, hybrid + reranker) and asserts regression floors on nDCG@10 / MRR@10 /
 * Recall@10.</li>
 * <li>{@link #weightSweep()} — gated with {@code -Deval.weights=true}: sweeps BM25/vector weight configs. Since the
 * cross-encoder reranker owns final ordering, the tuning objective is <b>Recall@30</b> (did the relevant docs make the
 * rerank pool), not top-rank metrics.</li>
 * <li>{@link #dumpCorpus()} — gated with {@code -Deval.dump=true}: prints doc-type/ID statistics of the shipped index,
 * used to curate qrel keys.</li>
 * </ul>
 */
@QuarkusTest
class RetrievalQualityTest {

    private static final Logger LOG = Logger.getLogger(RetrievalQualityTest.class);

    private static final String DOMAIN = "apache_camel";

    /**
     * Regression floors for the production search path, ~20% below measured so real regressions fail while index
     * refreshes don't cause flakiness. Tests evaluate the committed in-repo index (knowledge.index.path in test
     * application.properties). Baselines measured 2026-07-06 over 20 queries, both BM25 + reranker (the guards disable
     * the vector leg on both known indexes): legacy committed index 0.541/0.612/0.529; a clean community rebuild scored
     * 0.624/0.680/0.708. Floors cover the lower baseline — raise them once the CI-built release index (sound vectors,
     * gated by -Deval.requireVectors) becomes the committed baseline.
     */
    private static final double MIN_NDCG_AT_10 = 0.43;
    private static final double MIN_MRR_AT_10 = 0.49;
    private static final double MIN_RECALL_AT_10 = 0.42;

    @Inject
    LuceneSearchService searchService;

    record WeightConfig(float bm25Weight, float vectorWeight, String label) {
    }

    private static List<WeightConfig> buildWeightConfigs() {
        return List.of(
                new WeightConfig(1.0f, 0.0f, "BM25 100"),
                new WeightConfig(0.6f, 0.4f, "BM25 60/Vec 40"),
                new WeightConfig(0.4f, 0.6f, "BM25 40/Vec 60"),
                new WeightConfig(0.2f, 0.8f, "BM25 20/Vec 80"),
                new WeightConfig(0.0f, 1.0f, "Vec 100"));
    }

    /**
     * End-to-end evaluation of the production search path (hybrid candidates + cross-encoder rerank).
     */
    @Test
    void productionSearchQuality() throws Exception {
        // CI release gate: a freshly built index must serve WORKING vectors, not degrade gracefully.
        // Production tolerates a disabled vector leg; a release with one must not ship.
        if (Boolean.getBoolean("eval.requireVectors")) {
            assertTrue(searchService.getEmbeddingProvider() != null,
                    "Vector search is disabled (model mismatch or failed embedding self-check) — "
                                                                     + "the index under evaluation must not be released");
        }

        EvalDataset dataset = EvalDataset.load();

        Map<String, List<double[]>> byType = new TreeMap<>(); // type -> [ndcg, mrr, recall] per query
        StringBuilder sb = new StringBuilder("\n=== Production Search Quality (hybrid + reranker) ===\n\n");

        for (EvalDataset.EvalQuery q : dataset.queries()) {
            List<LuceneSearchService.SearchResult> results = searchService.search(DOMAIN, q.text(), null, null, 10);
            List<String> ids = results.stream().map(LuceneSearchService.SearchResult::id).toList();
            List<EvalDataset.Qrel> qrels = dataset.qrels(q.id());

            double ndcg = EvalDataset.ndcgAtK(ids, qrels, 10);
            double mrr = EvalDataset.mrrAtK(ids, qrels, 10);
            double recall = EvalDataset.recallAtK(ids, qrels, 10);
            byType.computeIfAbsent(q.type(), k -> new ArrayList<>()).add(new double[]{ndcg, mrr, recall});

            sb.append(String.format("%-28s [%-9s] nDCG@10=%.3f MRR@10=%.3f R@10=%.3f  \"%s\"%n",
                    q.id(), q.type(), ndcg, mrr, recall, q.text()));
            if (mrr == 0) {
                sb.append("    MISS — top3: ");
                for (int i = 0; i < Math.min(3, ids.size()); i++) {
                    sb.append(ids.get(i)).append(i < 2 ? ", " : "");
                }
                sb.append('\n');
            }
        }

        sb.append("\n--- By type (mean) ---\n");
        double[] overall = new double[3];
        int n = 0;
        for (var e : byType.entrySet()) {
            double[] mean = mean(e.getValue());
            sb.append(String.format("%-12s (%2d queries): nDCG@10=%.3f MRR@10=%.3f R@10=%.3f%n",
                    e.getKey(), e.getValue().size(), mean[0], mean[1], mean[2]));
            for (double[] row : e.getValue()) {
                for (int i = 0; i < 3; i++) {
                    overall[i] += row[i];
                }
                n++;
            }
        }
        for (int i = 0; i < 3; i++) {
            overall[i] /= n;
        }
        sb.append(String.format("%-12s (%2d queries): nDCG@10=%.3f MRR@10=%.3f R@10=%.3f%n",
                "OVERALL", n, overall[0], overall[1], overall[2]));
        LOG.info(sb.toString());

        assertTrue(overall[0] >= MIN_NDCG_AT_10,
                "nDCG@10 regressed: " + overall[0] + " < " + MIN_NDCG_AT_10);
        assertTrue(overall[1] >= MIN_MRR_AT_10,
                "MRR@10 regressed: " + overall[1] + " < " + MIN_MRR_AT_10);
        assertTrue(overall[2] >= MIN_RECALL_AT_10,
                "Recall@10 regressed: " + overall[2] + " < " + MIN_RECALL_AT_10);
    }

    /**
     * Weight sweep for hybrid candidate generation. Objective: Recall@30 (the reranker pool). Run with
     * {@code mvn test -pl mcp -Dtest=RetrievalQualityTest#weightSweep -Deval.weights=true}
     */
    @Test
    @EnabledIfSystemProperty(named = "eval.weights", matches = "true")
    void weightSweep() throws Exception {
        EvalDataset dataset = EvalDataset.load();
        List<WeightConfig> configs = buildWeightConfigs();

        StringBuilder sb = new StringBuilder("\n=== Hybrid Weight Sweep (objective: Recall@30 = rerank pool) ===\n\n");
        sb.append(String.format("%-18s %10s %10s %10s%n", "Config", "R@30", "nDCG@10", "MRR@10"));

        for (WeightConfig wc : configs) {
            double sumRecall = 0, sumNdcg = 0, sumMrr = 0;
            for (EvalDataset.EvalQuery q : dataset.queries()) {
                List<LuceneSearchService.SearchResult> results = LuceneSearchService.hybridSearch(
                        searchService.getSearcher(), searchService.getEmbeddingProvider(),
                        DOMAIN, q.text(), null, null, 30, wc.bm25Weight(), wc.vectorWeight());
                List<String> ids = results.stream().map(LuceneSearchService.SearchResult::id).toList();
                List<EvalDataset.Qrel> qrels = dataset.qrels(q.id());
                sumRecall += EvalDataset.recallAtK(ids, qrels, 30);
                sumNdcg += EvalDataset.ndcgAtK(ids, qrels, 10);
                sumMrr += EvalDataset.mrrAtK(ids, qrels, 10);
            }
            int n = dataset.queries().size();
            sb.append(String.format("%-18s %10.3f %10.3f %10.3f%n",
                    wc.label(), sumRecall / n, sumNdcg / n, sumMrr / n));
        }
        LOG.info(sb.toString());
    }

    /**
     * Prints doc-type statistics and sample IDs from the shipped index — used to curate qrel keys. Run with
     * {@code mvn test -pl mcp -Dtest=RetrievalQualityTest#dumpCorpus -Deval.dump=true}
     */
    @Test
    @EnabledIfSystemProperty(named = "eval.dump", matches = "true")
    void dumpCorpus() throws Exception {
        IndexReader reader = searchService.getSearcher().getIndexReader();

        Map<String, Integer> docTypeCounts = new TreeMap<>();
        Map<String, List<String>> samplesByType = new LinkedHashMap<>();
        TreeSet<String> versions = new TreeSet<>();
        TreeSet<String> components = new TreeSet<>();

        for (int i = 0; i < reader.maxDoc(); i++) {
            Document doc = reader.document(i);
            String docType = String.valueOf(doc.get("doc_type"));
            docTypeCounts.merge(docType, 1, Integer::sum);
            List<String> samples = samplesByType.computeIfAbsent(docType, k -> new ArrayList<>());
            if (samples.size() < 15) {
                samples.add(doc.get("id"));
            }
            if (doc.get("source_version") != null) {
                versions.add(doc.get("source_version"));
            }
            if (doc.get("component") != null && components.size() < 400) {
                components.add(doc.get("component"));
            }
        }

        StringBuilder sb = new StringBuilder("\n=== Index Corpus Dump ===\n");
        sb.append("Total docs: ").append(reader.maxDoc()).append('\n');
        sb.append("Versions: ").append(versions).append("\n\n");
        for (var e : docTypeCounts.entrySet()) {
            sb.append(String.format("doc_type=%s (%d docs), sample ids:%n", e.getKey(), e.getValue()));
            for (String id : samplesByType.get(e.getKey())) {
                sb.append("  ").append(id).append('\n');
            }
        }
        sb.append("\nComponents (first 400): ").append(components).append('\n');
        LOG.info(sb.toString());
    }

    private static double[] mean(List<double[]> rows) {
        double[] m = new double[3];
        for (double[] r : rows) {
            for (int i = 0; i < 3; i++) {
                m[i] += r[i];
            }
        }
        for (int i = 0; i < 3; i++) {
            m[i] /= rows.size();
        }
        return m;
    }
}
