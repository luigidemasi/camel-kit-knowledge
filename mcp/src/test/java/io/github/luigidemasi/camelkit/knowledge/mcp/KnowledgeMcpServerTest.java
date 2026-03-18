package io.github.luigidemasi.camelkit.knowledge.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that boots the full Quarkus app, loads the real Lucene index
 * from classpath resources, and exercises the MCP tools end-to-end.
 */
@QuarkusTest
class KnowledgeMcpServerTest {

    @Inject
    LuceneSearchService searchService;

    @Inject
    KnowledgeMcpServer mcpServer;

    // ── Index loading ──────────────────────────────────────────────

    @Test
    void indexLoadsAndContainsDomain() throws Exception {
        Set<String> domains = searchService.getDomains();
        assertTrue(domains.contains("rh_build_camel"), "Expected rh_build_camel domain, got: " + domains);
    }

    // ── rh_build_camel domain ──────────────────────────────────────

    @Test
    void rhBuildSearch_findsDocumentation() throws Exception {
        List<LuceneSearchService.SearchResult> results =
                searchService.search("rh_build_camel", "quarkus", null, null, 5);

        assertFalse(results.isEmpty(), "Expected results for query 'quarkus'");
        assertTrue(results.stream().allMatch(r -> "red-hat-build-camel".equals(r.source())),
                "All results should have source 'red-hat-build-camel'");
    }

    @Test
    void rhBuildSearch_withVersionFilter() throws Exception {
        List<LuceneSearchService.SearchResult> results =
                searchService.search("rh_build_camel", "getting started", "4.14", null, 5);

        assertFalse(results.isEmpty(), "Expected results for 'getting started' with version 4.14");
    }

    @Test
    void rhBuildComponentInfoTool_returnsJson() {
        String json = mcpServer.camel_rh_build_component_info("kafka", "4.14", null);

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\""), "Should contain found field");
    }

    @Test
    void rhBuildComponentInfoTool_withRuntime() {
        String json = mcpServer.camel_rh_build_component_info("platform-http", "4.14", "quarkus");

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\":true"), "Should find platform-http for quarkus: " + json);
    }

    @Test
    void rhBuildSearchTool_returnsJson() {
        String json = mcpServer.camel_rh_build_search("release notes", "4.14", 5);

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\""), "Should contain found field");
        assertTrue(json.contains("\"results\""), "Should contain results array");
    }

    // ── Edge cases ─────────────────────────────────────────────────

    @Test
    void lookupNonExistentComponent_returnsEmpty() throws Exception {
        List<LuceneSearchService.SearchResult> results =
                searchService.lookupComponent("rh_build_camel", "zzz-no-such-component-999", null, null);

        assertTrue(results.isEmpty(), "Should return empty for nonexistent component");
    }

    @Test
    void lookupNonExistentComponentTool_fallsBackToSearch() {
        // With the hybrid search fallback, even nonexistent components may return
        // results from the search index. Verify no error occurs.
        String json = mcpServer.camel_rh_build_component_info("zzz-no-such-component-999", null, null);

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\""), "Should contain found field");
    }
}
