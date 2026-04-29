package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import io.github.luigidemasi.camelkit.knowledge.embedding.EmbeddingProvider;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.lucene.search.IndexSearcher;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Evaluation test that compares BM25/vector weight ratios against the real production Lucene index using human-curated
 * ground truth.
 *
 * <h3>Workflow</h3>
 * <ol>
 * <li>Run {@link #dumpSearchResults()} to see top results for each query across all weight configs</li>
 * <li>Manually review the dump and pick the best document ID for each query</li>
 * <li>Set the {@code expectedId} in {@link #buildQueries()} for each query</li>
 * <li>Run {@link #evaluateWeightRatios()} for unbiased evaluation against the curated ground truth</li>
 * </ol>
 *
 * <h3>When to re-run the dump</h3>
 * <p>
 * Document IDs are deterministic ({@code apache-camel-{version}-{shortName}-{chunkIndex}}), so they only change when
 * source documents are added/removed or chunking logic changes. After such changes, re-run {@code dumpSearchResults}
 * and update the expected IDs.
 * </p>
 */
@QuarkusTest
class WeightEvaluationTest {

    private static final Logger LOG = Logger.getLogger(WeightEvaluationTest.class);

    @Inject
    LuceneSearchService searchService;

    record EvalQuery(String query, String description, String expectedId) {
    }

    record WeightConfig(float bm25Weight, float vectorWeight, String label) {
    }

    record QueryResult(String expectedId, int rank, float score,
            List<ResultSnippet> top3) {
    }

    record ResultSnippet(String id, String title, float score) {
    }

    /**
     * Shared query definitions with human-curated expected document IDs.
     *
     * To curate: run {@link #dumpSearchResults()}, review the output, and set the expectedId to the most relevant
     * document for each query. Set to {@code null} for queries that don't have a clear "right" answer (e.g., garbage
     * queries).
     */
    private static List<EvalQuery> buildQueries() {
        return List.of(
                new EvalQuery(
                        "camel-http component configuration",
                        "Keyword: exact component name",
                        null),  // TODO: curate after running dumpSearchResults
                new EvalQuery(
                        "how to set up a REST API with Camel on Quarkus",
                        "Semantic: natural language question",
                        null),
                new EvalQuery(
                        "MSA to MSA migration Spring Boot to Quarkus",
                        "Semantic: paraphrase of migration path",
                        null),
                new EvalQuery(
                        "CVE-2025-27636 camel-bean header injection",
                        "Keyword: CVE ID + component",
                        null),
                new EvalQuery(
                        "which JDK versions are supported",
                        "Semantic: supported configurations",
                        null),
                new EvalQuery(
                        "camel-kafka MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA",
                        "Keyword: exact component + noise",
                        null),
                new EvalQuery(
                        "MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA",
                        "Garbage: no meaningful content",
                        null),  // intentionally null — no correct answer for garbage
                new EvalQuery(
                        "MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA MSA",
                        "Garbage: lots of noise, tests robustness",
                        null),  // intentionally null
                new EvalQuery(
                        "database connection pooling configuration for PostgreSQL",
                        "Semantic: infrastructure question",
                        null),
                new EvalQuery(
                        "RHSA security advisory critical vulnerability",
                        "Keyword: errata terminology",
                        null),
                new EvalQuery(
                        "how to handle errors and retries in a route",
                        "Semantic: error handling pattern",
                        null),
                new EvalQuery(
                        "release notes 4.14",
                        "Keyword: release notes for specific version",
                        null));
    }

    private static List<WeightConfig> buildWeightConfigs() {
        return List.of(
                new WeightConfig(1.0f, 0.0f, "BM25 100"),
                new WeightConfig(0.6f, 0.4f, "BM25 60/Vec 40"),
                new WeightConfig(0.5f, 0.5f, "BM25 50/Vec 50"),
                new WeightConfig(0.4f, 0.6f, "BM25 40/Vec 60"),
                new WeightConfig(0.3f, 0.7f, "BM25 30/Vec 70"),
                new WeightConfig(0.2f, 0.8f, "BM25 20/Vec 80"),
                new WeightConfig(0.0f, 1.0f, "Vec 100"));
    }

    /**
     * Discovery tool — run this first to see what the index returns for each query across all weight configurations.
     * Review the output and pick the best document ID for each query, then set it as the expectedId in
     * {@link #buildQueries()}.
     *
     * Run with: mvn test -pl mcp -Dtest=WeightEvaluationTest#dumpSearchResults
     */
    @Test
    void dumpSearchResults() throws Exception {
        IndexSearcher searcher = searchService.getSearcher();
        EmbeddingProvider embeddingProvider = searchService.getEmbeddingProvider();
        List<EvalQuery> queries = buildQueries();
        List<WeightConfig> configs = buildWeightConfigs();

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Search Results Dump (for ground truth curation) ===\n");
        sb.append("Review each query's results and pick the best document ID.\n");
        sb.append("Then set it as expectedId in buildQueries().\n\n");

        for (int qi = 0; qi < queries.size(); qi++) {
            EvalQuery eq = queries.get(qi);
            sb.append(String.format("━━━ Query %d: \"%s\"%n", qi + 1, eq.query()));
            sb.append(String.format("    Type: %s%n", eq.description()));
            if (eq.expectedId() != null) {
                sb.append(String.format("    Curated: %s%n", eq.expectedId()));
            } else {
                sb.append("    Curated: (not yet set)\n");
            }
            sb.append('\n');

            for (WeightConfig wc : configs) {
                List<LuceneSearchService.SearchResult> searchResults
                        = LuceneSearchService.hybridSearch(searcher, embeddingProvider,
                                "apache_camel", eq.query(), null, null, 5,
                                wc.bm25Weight(), wc.vectorWeight());

                sb.append(String.format("  %-15s:%n", wc.label()));
                for (int r = 0; r < searchResults.size(); r++) {
                    LuceneSearchService.SearchResult sr = searchResults.get(r);
                    String title = sr.sectionTitle() != null ? sr.sectionTitle() : "(no title)";
                    String content = sr.content() != null ? sr.content() : "";
                    String contentSnippet = content.length() > 200
                            ? content.substring(0, 200).replace('\n', ' ') + "..."
                            : content.replace('\n', ' ');
                    sb.append(String.format("    #%d [%.4f] %s%n", r + 1, sr.score(), sr.id()));
                    sb.append(String.format("       title:   %s%n", title));
                    sb.append(String.format("       content: %s%n", contentSnippet));
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        LOG.info(sb.toString());
    }

    /**
     * Unbiased weight evaluation using human-curated ground truth. Queries with null expectedId are skipped (garbage
     * queries, or not yet curated).
     *
     * Run with: mvn test -pl mcp -Dtest=WeightEvaluationTest#evaluateWeightRatios
     */
    @Test
    void evaluateWeightRatios() throws Exception {
        IndexSearcher searcher = searchService.getSearcher();
        EmbeddingProvider embeddingProvider = searchService.getEmbeddingProvider();
        List<EvalQuery> allQueries = buildQueries();
        List<WeightConfig> configs = buildWeightConfigs();

        // Filter to only curated queries (non-null expectedId)
        List<EvalQuery> queries = allQueries.stream()
                .filter(q -> q.expectedId() != null)
                .toList();

        if (queries.isEmpty()) {
            LOG.warn(
                    "No curated queries found. Run dumpSearchResults first, then set expectedId values in buildQueries().");
            return;
        }

        QueryResult[][] results = new QueryResult[queries.size()][configs.size()];

        for (int qi = 0; qi < queries.size(); qi++) {
            EvalQuery eq = queries.get(qi);

            for (int ci = 0; ci < configs.size(); ci++) {
                WeightConfig wc = configs.get(ci);
                List<LuceneSearchService.SearchResult> searchResults
                        = LuceneSearchService.hybridSearch(searcher, embeddingProvider,
                                "apache_camel", eq.query(), null, null, 10,
                                wc.bm25Weight(), wc.vectorWeight());

                int rank = 0;
                float score = 0;
                for (int r = 0; r < searchResults.size(); r++) {
                    if (eq.expectedId().equals(searchResults.get(r).id())) {
                        rank = r + 1;
                        score = searchResults.get(r).score();
                        break;
                    }
                }

                List<ResultSnippet> top3 = new ArrayList<>();
                for (int r = 0; r < Math.min(3, searchResults.size()); r++) {
                    LuceneSearchService.SearchResult sr = searchResults.get(r);
                    top3.add(new ResultSnippet(sr.id(), sr.sectionTitle(), sr.score()));
                }

                results[qi][ci] = new QueryResult(eq.expectedId(), rank, score, top3);
            }
        }

        // Build report
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Hybrid Search Weight Evaluation (Human-Curated Ground Truth) ===\n\n");

        for (int qi = 0; qi < queries.size(); qi++) {
            EvalQuery eq = queries.get(qi);
            sb.append(String.format("Query %d: \"%s\"%n", qi + 1, eq.query()));
            sb.append(String.format("  Type: %s%n", eq.description()));
            sb.append(String.format("  Expected: %s%n%n", eq.expectedId()));

            for (int ci = 0; ci < configs.size(); ci++) {
                WeightConfig wc = configs.get(ci);
                QueryResult qr = results[qi][ci];
                String rankStr = qr.rank() == 0 ? "NOT FOUND" : "#" + qr.rank();
                sb.append(String.format("  %-15s: expected_rank=%-10s score=%.4f%n",
                        wc.label(), rankStr, qr.score()));
                for (int t = 0; t < qr.top3().size(); t++) {
                    ResultSnippet rs = qr.top3().get(t);
                    String marker = rs.id().equals(qr.expectedId()) ? " <--" : "";
                    String titleSnippet = rs.title() != null && rs.title().length() > 60
                            ? rs.title().substring(0, 60) + "..."
                            : rs.title();
                    sb.append(String.format("    #%d [%.4f] %s | %s%s%n",
                            t + 1, rs.score(), rs.id(), titleSnippet, marker));
                }
            }
            sb.append('\n');
        }

        // Summary table
        int numQueries = queries.size();
        sb.append("=== SUMMARY ===\n");
        int col = 16;
        sb.append(String.format("%-20s", ""));
        for (WeightConfig wc : configs) {
            sb.append(String.format("  %-" + col + "s", wc.label()));
        }
        sb.append('\n');

        sb.append(String.format("%-20s", "Avg rank:"));
        for (int ci = 0; ci < configs.size(); ci++) {
            double sum = 0;
            for (int qi = 0; qi < numQueries; qi++) {
                int rank = results[qi][ci].rank();
                sum += rank > 0 ? rank : 11; // not found = rank 11 (worse than top 10)
            }
            sb.append(String.format("  %-" + col + ".1f", sum / numQueries));
        }
        sb.append('\n');

        sb.append(String.format("%-20s", "#1 hits:"));
        for (int ci = 0; ci < configs.size(); ci++) {
            int hits = 0;
            for (int qi = 0; qi < numQueries; qi++) {
                if (results[qi][ci].rank() == 1)
                    hits++;
            }
            sb.append(String.format("  %-" + col + "s", hits + "/" + numQueries));
        }
        sb.append('\n');

        sb.append(String.format("%-20s", "Top-3 hits:"));
        for (int ci = 0; ci < configs.size(); ci++) {
            int hits = 0;
            for (int qi = 0; qi < numQueries; qi++) {
                int rank = results[qi][ci].rank();
                if (rank >= 1 && rank <= 3)
                    hits++;
            }
            sb.append(String.format("  %-" + col + "s", hits + "/" + numQueries));
        }
        sb.append('\n');

        sb.append(String.format("%-20s", "Worst rank:"));
        for (int ci = 0; ci < configs.size(); ci++) {
            int worst = 0;
            for (int qi = 0; qi < numQueries; qi++) {
                int rank = results[qi][ci].rank();
                if (rank == 0)
                    rank = 11;
                worst = Math.max(worst, rank);
            }
            sb.append(String.format("  %-" + col + "d", worst));
        }
        sb.append('\n');

        sb.append(String.format("%-20s", "Avg score:"));
        for (int ci = 0; ci < configs.size(); ci++) {
            double sum = 0;
            for (int qi = 0; qi < numQueries; qi++) {
                sum += results[qi][ci].score();
            }
            sb.append(String.format("  %-" + col + ".4f", sum / numQueries));
        }
        sb.append('\n');

        LOG.info(sb.toString());
    }
}
