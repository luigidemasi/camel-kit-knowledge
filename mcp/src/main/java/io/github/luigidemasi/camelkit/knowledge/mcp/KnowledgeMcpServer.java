package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.catalog.EndpointValidationResult;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server exposing Apache Camel community documentation search tools (camel_docs_* prefix).
 */
public class KnowledgeMcpServer {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeMcpServer.class);

    private static final String DOMAIN = "apache_camel";
    private static final int MAX_RESULTS_CAP = 25;
    private static final Pattern MINOR_VERSION_PATTERN = Pattern.compile("^(\\d+\\.\\d+)");

    @Inject
    LuceneSearchService searchService;

    /** Catalog for deterministic endpoint validation. Bundles a single Camel version's metadata. */
    private final CamelCatalog catalog = new DefaultCamelCatalog(true);

    @Tool(description = "Look up Apache Camel documentation for a specific component. " +
                        "Returns component reference, usage examples, and configuration options. " +
                        "The 'match' field distinguishes exact catalog hits from fuzzy full-text fallback — " +
                        "'fuzzy' means the component name was NOT found verbatim and results are nearest matches only.")
    public String camel_docs_component_info(
            @ToolArg(description = "Component name, e.g., 'kafka', 'http', 'amqp'") String component,
            @ToolArg(description = "Camel version (major.minor, e.g., '4.14'). Optional — omit for all versions.",
                     required = false) String version,
            @ToolArg(description = "Runtime filter: 'quarkus' or 'spring-boot'. Optional.",
                     required = false) String runtime) {
        try {
            String normalizedRuntime = normalizeRuntime(runtime);
            // Index versions are major.minor — '4.14.1' would silently zero out the exact term filter
            String normalizedVersion = normalizeMinor(version);

            List<LuceneSearchService.SearchResult> results
                    = searchService.lookupComponent(DOMAIN, component, normalizedVersion, normalizedRuntime);

            if (results.isEmpty() && normalizedRuntime != null) {
                results = searchService.lookupComponent(DOMAIN, component, normalizedVersion, null);
            }

            String match = "exact";
            if (results.isEmpty()) {
                match = "fuzzy";
                String query = "camel-" + component + " component";
                results = searchService.search(DOMAIN, query, normalizedVersion, null, 5);
            }

            if (results.isEmpty()) {
                logZeroHit("component_info", component);
                return "{\"found\":false,\"match\":\"none\",\"results\":[]}";
            }

            return formatResults(results, match);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @Tool(description = "Search Apache Camel documentation by keyword. " +
                        "Covers component references, EIP patterns, user manual, migration guides, " +
                        "getting started guides, and release notes.")
    public String camel_docs_search(
            @ToolArg(description = "Search query, e.g., 'configure SSL for HTTP component'") String query,
            @ToolArg(description = "Camel version filter (major.minor, e.g., '4.14'). Optional.",
                     required = false) String version,
            @ToolArg(description = "Maximum results to return (default 5, max 25)",
                     required = false) Integer max_results) {
        try {
            List<LuceneSearchService.SearchResult> results
                    = searchService.search(DOMAIN, query, normalizeMinor(version), null, clamp(max_results, 5));
            if (results.isEmpty()) {
                logZeroHit("search", query);
            }
            return formatResults(results, null);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @Tool(description = "Search Apache Camel CVE security advisories. " +
                        "Query by CVE ID, affected component, severity, or affected version. " +
                        "Returns CVE details, affected versions, fixed versions, and CVSS scores.")
    public String camel_docs_cve_search(
            @ToolArg(description = "CVE identifier, e.g., 'CVE-2024-22369'. Optional.", required = false) String cve_id,
            @ToolArg(description = "Component name to find CVEs for, e.g., 'sql', 'cxf'. Optional.",
                     required = false) String component,
            @ToolArg(description = "Severity filter: 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'. Optional.",
                     required = false) String severity,
            @ToolArg(description = "Camel version to check for CVEs, e.g., '4.14'. Optional.",
                     required = false) String version,
            @ToolArg(description = "Maximum results (default 10, max 25)", required = false) Integer max_results) {
        try {
            if (cve_id != null && !cve_id.isBlank()) {
                List<LuceneSearchService.ErrataSearchResult> results
                        = searchService.searchByCve(cve_id.trim().toUpperCase(Locale.ROOT));
                if (results.isEmpty()) {
                    logZeroHit("cve_search", cve_id);
                }
                return formatErrataResults(results);
            }

            StringBuilder query = new StringBuilder();
            if (component != null)
                query.append("camel-").append(component).append(" ");
            if (severity != null)
                query.append(severity).append(" ");
            query.append("CVE security vulnerability");

            List<LuceneSearchService.ErrataSearchResult> results
                    = searchService.searchErrata(null, severity, version, query.toString().trim(),
                            clamp(max_results, 10));
            if (results.isEmpty()) {
                logZeroHit("cve_search", query.toString());
            }
            return formatErrataResults(results);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @Tool(description = "Get release notes for a specific Apache Camel version. " +
                        "Returns new features, bug fixes, and JIRA issues included in the release.")
    public String camel_docs_release_info(
            @ToolArg(description = "Camel version, e.g., '4.14', '4.18.1'") String version,
            @ToolArg(description = "Maximum results (default 20, max 25)", required = false) Integer max_results) {
        try {
            List<LuceneSearchService.SearchResult> results = searchService.search(
                    DOMAIN, "release " + version, normalizeMinor(version), null, "release-notes",
                    clamp(max_results, 20));
            if (results.isEmpty()) {
                logZeroHit("release_info", version);
            }
            return formatResults(results, null);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @Tool(description = "Look up a JIRA issue to find in which Apache Camel release " +
                        "it was fixed or implemented. Returns release version, description, and context.")
    public String camel_docs_jira_lookup(
            @ToolArg(description = "JIRA issue ID, e.g., 'CAMEL-22784'") String jira_id) {
        try {
            List<LuceneSearchService.SearchResult> results
                    = searchService.searchByJiraId(jira_id.trim().toUpperCase(Locale.ROOT));

            if (results.isEmpty()) {
                logZeroHit("jira_lookup", jira_id);
                return "{\"found\":false,\"jira_id\":\"" + escape(jira_id) + "\",\"results\":[]}";
            }

            return formatResults(results, null);
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @Tool(description = "Validate a Camel endpoint URI against the Camel catalog. " +
                        "Deterministically checks that the component exists and that every option name, type, and " +
                        "enum value is valid — use this to verify generated endpoint URIs instead of guessing. " +
                        "Validation runs against a single bundled catalog version (reported in the response).")
    public String camel_docs_validate_endpoint(
            @ToolArg(description = "Endpoint URI to validate, e.g., 'kafka:myTopic?brokers=localhost:9092'") String uri) {
        try {
            EndpointValidationResult result = catalog.validateEndpointProperties(uri);

            StringBuilder sb = new StringBuilder();
            sb.append("{\"valid\":").append(result.isSuccess());
            sb.append(",\"catalog_version\":\"").append(escape(catalog.getCatalogVersion())).append('"');
            sb.append(",\"uri\":\"").append(escape(uri)).append('"');
            if (result.getUnknownComponent() != null) {
                sb.append(",\"unknown_component\":\"").append(escape(result.getUnknownComponent())).append('"');
            }
            if (result.getSyntaxError() != null) {
                sb.append(",\"syntax_error\":\"").append(escape(result.getSyntaxError())).append('"');
            }
            appendStringArray(sb, "unknown_options", result.getUnknown());
            appendStringArray(sb, "missing_required", result.getRequired());
            if (!result.isSuccess()) {
                String summary = result.summaryErrorMessage(false);
                if (summary != null) {
                    sb.append(",\"summary\":\"").append(escape(summary)).append('"');
                }
            }
            sb.append('}');
            return sb.toString();
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    @Tool(description = "Get knowledge index metadata: covered Camel versions, document counts per type, " +
                        "embedding model, and the active search mode. Use this to check whether a version or topic " +
                        "is covered before trusting empty search results.")
    public String camel_docs_index_info() {
        try {
            LuceneSearchService.IndexInfo info = searchService.indexInfo();
            String docTypes = info.docTypeCounts().entrySet().stream()
                    .map(e -> "\"" + escape(e.getKey()) + "\":" + e.getValue())
                    .collect(Collectors.joining(","));
            String versions = info.versions().stream()
                    .map(v -> "\"" + escape(v) + "\"")
                    .collect(Collectors.joining(","));
            return "{\"total_docs\":" + info.totalDocs()
                   + ",\"embedding_model\":\""
                   + escape(info.embeddingModel() != null ? info.embeddingModel() : "unknown") + "\""
                   + ",\"search_mode\":\"" + escape(info.searchMode()) + "\""
                   + ",\"versions\":[" + versions + "]"
                   + ",\"doc_types\":{" + docTypes + "}}";
        } catch (Exception e) {
            return errorJson(e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static int clamp(Integer maxResults, int defaultValue) {
        int v = maxResults != null ? maxResults : defaultValue;
        return Math.max(1, Math.min(v, MAX_RESULTS_CAP));
    }

    /** Index versions are major.minor — normalize '4.18.1' to '4.18' so exact term filters match. */
    private static String normalizeMinor(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        Matcher m = MINOR_VERSION_PATTERN.matcher(version.trim());
        return m.find() ? m.group(1) : version.trim();
    }

    private void logZeroHit(String tool, String query) {
        // Coverage backlog: what clients needed and the index could not answer
        LOG.info("zero-hit: tool={} query=\"{}\"", tool, query);
    }

    private String normalizeRuntime(String runtime) {
        if (runtime == null || runtime.isBlank())
            return null;
        String lower = runtime.toLowerCase(Locale.ROOT).trim();
        return switch (lower) {
            case "springboot", "spring-boot", "spring_boot" -> "spring-boot";
            case "quarkus" -> "quarkus";
            case "main", "standalone" -> null;
            default -> lower;
        };
    }

    private String errorJson(Exception e) {
        return "{\"error\":\"" + escape(e.getMessage()) + "\"}";
    }

    private static void appendStringArray(StringBuilder sb, String name, java.util.Set<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append(",\"").append(name).append("\":[");
        sb.append(values.stream().map(v -> "\"" + escape(v) + "\"").collect(Collectors.joining(",")));
        sb.append(']');
    }

    private String formatResults(List<LuceneSearchService.SearchResult> results, String match) {
        String items = results.stream()
                .map(r -> {
                    String runtimeArray = r.runtimes().stream()
                            .map(rt -> "\"" + escape(rt) + "\"")
                            .collect(Collectors.joining(","));
                    return String.format(Locale.ROOT,
                            "{\"id\":\"%s\",\"source\":\"%s\",\"doc_type\":\"%s\"," +
                                                      "\"source_version\":\"%s\",\"target_version\":\"%s\"," +
                                                      "\"runtimes\":[%s]," +
                                                      "\"section_title\":\"%s\",\"content\":\"%s\",\"score\":%.2f}",
                            escape(r.id()), escape(r.source()), escape(r.docType()),
                            escape(r.sourceVersion()), escape(r.targetVersion()),
                            runtimeArray,
                            escape(r.sectionTitle()), escape(r.content()), r.score());
                })
                .collect(Collectors.joining(","));

        return "{\"found\":" + !results.isEmpty()
               + ",\"total_hits\":" + results.size()
               + (match != null ? ",\"match\":\"" + match + "\"" : "")
               + ",\"search_mode\":\"" + escape(searchService.searchMode()) + "\""
               + ",\"results\":[" + items + "]}";
    }

    private String formatErrataResults(List<LuceneSearchService.ErrataSearchResult> results) {
        String items = results.stream()
                .map(r -> {
                    String cveArray = r.cveIds().stream()
                            .map(c -> "\"" + escape(c) + "\"")
                            .collect(Collectors.joining(","));
                    String versionArray = r.fixedInVersions().stream()
                            .map(v -> "\"" + escape(v) + "\"")
                            .collect(Collectors.joining(","));
                    return String.format(Locale.ROOT,
                            "{\"erratum_id\":\"%s\",\"advisory_type\":\"%s\"," +
                                                      "\"severity\":\"%s\",\"section_title\":\"%s\"," +
                                                      "\"cve_ids\":[%s],\"fixed_in_versions\":[%s]," +
                                                      "\"content\":\"%s\",\"score\":%.2f}",
                            escape(r.erratumId()), escape(r.advisoryType()),
                            escape(r.severity()), escape(r.sectionTitle()),
                            cveArray, versionArray,
                            escape(r.content()), r.score());
                })
                .collect(Collectors.joining(","));

        return "{\"found\":" + !results.isEmpty()
               + ",\"total_hits\":" + results.size()
               + ",\"results\":[" + items + "]}";
    }

    /** JSON string escaping including all control characters (tabs and friends appear in doc content). */
    private static String escape(String s) {
        if (s == null)
            return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

}
