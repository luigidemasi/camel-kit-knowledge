package io.github.luigidemasi.camelkit.knowledge.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP server exposing Apache Camel community documentation search tools.
 * 5 tools with camel_docs_* prefix.
 */
public class KnowledgeMcpServer {

    private static final String DOMAIN = "apache_camel";

    @Inject
    LuceneSearchService searchService;

    @Tool(description = "Look up Apache Camel documentation for a specific component. " +
            "Returns component reference, usage examples, configuration options, and related CVEs. " +
            "Use to check if a component exists and get its documentation.")
    public String camel_docs_component_info(
            @ToolArg(description = "Component name, e.g., 'kafka', 'http', 'amqp'") String component,
            @ToolArg(description = "Camel version, e.g., '4.14'. Optional — omit for all versions.", required = false) String version,
            @ToolArg(description = "Runtime filter: 'quarkus' or 'spring-boot'. Optional.", required = false) String runtime
    ) {
        try {
            String normalizedRuntime = normalizeRuntime(runtime);

            List<LuceneSearchService.SearchResult> results =
                    searchService.lookupComponent(DOMAIN, component, version, normalizedRuntime);

            if (results.isEmpty() && normalizedRuntime != null) {
                results = searchService.lookupComponent(DOMAIN, component, version, null);
            }

            if (results.isEmpty()) {
                String query = "camel-" + component + " component";
                results = searchService.search(DOMAIN, query, version, null, 5);
            }

            if (results.isEmpty()) {
                return "{\"found\":false,\"results\":[]}";
            }

            return formatResults(results);
        } catch (Exception e) {
            return "{\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "Search Apache Camel documentation by keyword. " +
            "Covers component references, EIP patterns, user manual, migration guides, " +
            "getting started guides, and release notes.")
    public String camel_docs_search(
            @ToolArg(description = "Search query, e.g., 'configure SSL for HTTP component'") String query,
            @ToolArg(description = "Camel version filter, e.g., '4.14'. Optional.", required = false) String version,
            @ToolArg(description = "Maximum results to return (default 5)", required = false) Integer max_results
    ) {
        try {
            int maxResults = max_results != null ? max_results : 5;
            List<LuceneSearchService.SearchResult> results =
                    searchService.search(DOMAIN, query, version, null, maxResults);
            return formatResults(results);
        } catch (Exception e) {
            return "{\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "Search Apache Camel CVE security advisories. " +
            "Query by CVE ID, affected component, severity, or affected version. " +
            "Returns CVE details, affected versions, fixed versions, and CVSS scores.")
    public String camel_docs_cve_search(
            @ToolArg(description = "CVE identifier, e.g., 'CVE-2024-22369'. Optional.", required = false) String cve_id,
            @ToolArg(description = "Component name to find CVEs for, e.g., 'sql', 'cxf'. Optional.", required = false) String component,
            @ToolArg(description = "Severity filter: 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'. Optional.", required = false) String severity,
            @ToolArg(description = "Camel version to check for CVEs, e.g., '4.14'. Optional.", required = false) String version,
            @ToolArg(description = "Maximum results (default 10)", required = false) Integer max_results
    ) {
        try {
            int maxResults = max_results != null ? max_results : 10;

            if (cve_id != null && !cve_id.isBlank()) {
                List<LuceneSearchService.ErrataSearchResult> results =
                        searchService.searchByCve(cve_id);
                return formatErrataResults(results);
            }

            StringBuilder query = new StringBuilder();
            if (component != null) query.append("camel-").append(component).append(" ");
            if (severity != null) query.append(severity).append(" ");
            query.append("CVE security vulnerability");

            List<LuceneSearchService.ErrataSearchResult> results =
                    searchService.searchErrata(null, severity, version, query.toString().trim(), maxResults);
            return formatErrataResults(results);
        } catch (Exception e) {
            return "{\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "Get release notes for a specific Apache Camel version. " +
            "Returns new features, bug fixes, and JIRA issues included in the release.")
    public String camel_docs_release_info(
            @ToolArg(description = "Camel version, e.g., '4.14', '4.18.1'") String version,
            @ToolArg(description = "Maximum results (default 20)", required = false) Integer max_results
    ) {
        try {
            int maxResults = max_results != null ? max_results : 20;
            List<LuceneSearchService.SearchResult> results =
                    searchService.search(DOMAIN, "release " + version, version, "release-notes", maxResults);
            return formatResults(results);
        } catch (Exception e) {
            return "{\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "Look up a JIRA issue to find in which Apache Camel release " +
            "it was fixed or implemented. Returns release version, description, and context.")
    public String camel_docs_jira_lookup(
            @ToolArg(description = "JIRA issue ID, e.g., 'CAMEL-22784'") String jira_id
    ) {
        try {
            List<LuceneSearchService.SearchResult> results =
                    searchService.searchByJiraId(jira_id.toUpperCase());

            if (results.isEmpty()) {
                return "{\"found\":false,\"jira_id\":\"" + escape(jira_id) + "\",\"results\":[]}";
            }

            return formatResults(results);
        } catch (Exception e) {
            return "{\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    private String normalizeRuntime(String runtime) {
        if (runtime == null || runtime.isBlank()) return null;
        String lower = runtime.toLowerCase().trim();
        return switch (lower) {
            case "springboot", "spring-boot", "spring_boot" -> "spring-boot";
            case "quarkus" -> "quarkus";
            case "main", "standalone" -> null;
            default -> lower;
        };
    }

    private String formatResults(List<LuceneSearchService.SearchResult> results) {
        String items = results.stream()
                .map(r -> {
                    String runtimeArray = r.runtimes().stream()
                            .map(rt -> "\"" + escape(rt) + "\"")
                            .collect(Collectors.joining(","));
                    return String.format(
                        "{\"id\":\"%s\",\"source\":\"%s\",\"doc_type\":\"%s\"," +
                        "\"source_version\":\"%s\",\"target_version\":\"%s\"," +
                        "\"runtimes\":[%s]," +
                        "\"section_title\":\"%s\",\"content\":\"%s\",\"score\":%.2f}",
                        escape(r.id()), escape(r.source()), escape(r.docType()),
                        escape(r.sourceVersion()), escape(r.targetVersion()),
                        runtimeArray,
                        escape(r.sectionTitle()), escape(r.content()), r.score()
                    );
                })
                .collect(Collectors.joining(","));

        return "{\"found\":true,\"total_hits\":" + results.size() + ",\"results\":[" + items + "]}";
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
                    return String.format(
                            "{\"erratum_id\":\"%s\",\"advisory_type\":\"%s\"," +
                            "\"severity\":\"%s\",\"section_title\":\"%s\"," +
                            "\"cve_ids\":[%s],\"fixed_in_versions\":[%s]," +
                            "\"content\":\"%s\",\"score\":%.2f}",
                            escape(r.erratumId()), escape(r.advisoryType()),
                            escape(r.severity()), escape(r.sectionTitle()),
                            cveArray, versionArray,
                            escape(r.content()), r.score()
                    );
                })
                .collect(Collectors.joining(","));

        return "{\"found\":true,\"total_hits\":" + results.size() + ",\"results\":[" + items + "]}";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
