package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval evaluation dataset: queries + graded relevance judgments (qrels), loaded from TSV resources.
 *
 * <p>
 * Ground truth is judged at a <em>stable key</em> level: each qrel is a substring that must appear in a chunk ID (e.g.
 * {@code -catalog-component-kafka} or {@code -component-kafka-component-}). Chunk IDs are deterministic
 * ({@code apache-camel-{version}-{docType}-{shortName}-{slug}}), so keys survive reindexing and chunking changes as
 * long as the source file exists.
 *
 * <p>
 * Metrics (all treat a qrel key as "found" at the rank of its <em>first</em> matching result; later chunks matching the
 * same key gain nothing, so a wall of chunks from one relevant file cannot inflate scores):
 * <ul>
 * <li>{@link #ndcgAtK} — graded nDCG, gain 2^grade-1, ideal ranking = keys sorted by grade</li>
 * <li>{@link #mrrAtK} — reciprocal rank of the first relevant result</li>
 * <li>{@link #recallAtK} — fraction of distinct relevant keys matched within top k</li>
 * </ul>
 *
 * File formats (tab-separated, {@code #} comments and blank lines ignored):
 *
 * <pre>
 * eval/queries.tsv:  queryId &lt;TAB&gt; type &lt;TAB&gt; query text
 * eval/qrels.tsv:    queryId &lt;TAB&gt; idSubstring &lt;TAB&gt; grade (1=relevant, 2=primary)
 * </pre>
 */
final class EvalDataset {

    record EvalQuery(String id, String type, String text) {
    }

    record Qrel(String key, int grade) {
    }

    private final List<EvalQuery> queries;
    private final Map<String, List<Qrel>> qrelsByQuery;

    private EvalDataset(List<EvalQuery> queries, Map<String, List<Qrel>> qrelsByQuery) {
        this.queries = queries;
        this.qrelsByQuery = qrelsByQuery;
    }

    List<EvalQuery> queries() {
        return queries;
    }

    List<Qrel> qrels(String queryId) {
        return qrelsByQuery.getOrDefault(queryId, List.of());
    }

    /** Loads {@code eval/queries.tsv} + {@code eval/qrels.tsv} from the classpath. */
    static EvalDataset load() throws IOException {
        List<EvalQuery> queries = new ArrayList<>();
        for (String[] cols : readTsv("eval/queries.tsv", 3)) {
            queries.add(new EvalQuery(cols[0], cols[1], cols[2]));
        }

        Map<String, List<Qrel>> qrels = new LinkedHashMap<>();
        for (String[] cols : readTsv("eval/qrels.tsv", 3)) {
            qrels.computeIfAbsent(cols[0], k -> new ArrayList<>())
                    .add(new Qrel(cols[1], Integer.parseInt(cols[2])));
        }

        Set<String> queryIds = new HashSet<>();
        for (EvalQuery q : queries) {
            queryIds.add(q.id());
            if (!qrels.containsKey(q.id())) {
                throw new IllegalStateException("Query '" + q.id() + "' has no qrels");
            }
        }
        for (String qid : qrels.keySet()) {
            if (!queryIds.contains(qid)) {
                throw new IllegalStateException("Qrels reference unknown query '" + qid + "'");
            }
        }

        return new EvalDataset(queries, qrels);
    }

    private static List<String[]> readTsv(String resource, int expectedCols) throws IOException {
        try (InputStream is = EvalDataset.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException(resource + " not found on test classpath");
            }
            List<String[]> rows = new ArrayList<>();
            for (String line : new String(is.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                if (cols.length != expectedCols) {
                    throw new IOException(
                            resource + ": expected " + expectedCols + " columns, got "
                                          + cols.length + " in line: " + line);
                }
                rows.add(cols);
            }
            return rows;
        }
    }

    // ── Metrics ─────────────────────────────────────────────────────────

    /**
     * For each rank, the grade of the qrel key first matched at that rank (0 if none or already matched earlier).
     */
    private static int[] gainsPerRank(List<String> resultIds, List<Qrel> qrels, int k) {
        int n = Math.min(resultIds.size(), k);
        int[] gains = new int[n];
        Set<String> matched = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String id = resultIds.get(i);
            for (Qrel qrel : qrels) {
                if (!matched.contains(qrel.key()) && id.contains(qrel.key())) {
                    matched.add(qrel.key());
                    gains[i] = Math.max(gains[i], qrel.grade());
                }
            }
        }
        return gains;
    }

    static double ndcgAtK(List<String> resultIds, List<Qrel> qrels, int k) {
        int[] gains = gainsPerRank(resultIds, qrels, k);
        double dcg = 0;
        for (int i = 0; i < gains.length; i++) {
            dcg += (Math.pow(2, gains[i]) - 1) / log2(i + 2);
        }

        List<Integer> ideal = new ArrayList<>(qrels.stream().map(Qrel::grade).sorted((a, b) -> b - a).toList());
        double idcg = 0;
        for (int i = 0; i < Math.min(ideal.size(), k); i++) {
            idcg += (Math.pow(2, ideal.get(i)) - 1) / log2(i + 2);
        }
        return idcg > 0 ? dcg / idcg : 0;
    }

    static double mrrAtK(List<String> resultIds, List<Qrel> qrels, int k) {
        int n = Math.min(resultIds.size(), k);
        for (int i = 0; i < n; i++) {
            String id = resultIds.get(i);
            for (Qrel qrel : qrels) {
                if (id.contains(qrel.key())) {
                    return 1.0 / (i + 1);
                }
            }
        }
        return 0;
    }

    static double recallAtK(List<String> resultIds, List<Qrel> qrels, int k) {
        if (qrels.isEmpty()) {
            return 0;
        }
        Set<String> matched = new HashSet<>();
        int n = Math.min(resultIds.size(), k);
        for (int i = 0; i < n; i++) {
            String id = resultIds.get(i);
            for (Qrel qrel : qrels) {
                if (id.contains(qrel.key())) {
                    matched.add(qrel.key());
                }
            }
        }
        return (double) matched.size() / qrels.size();
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
