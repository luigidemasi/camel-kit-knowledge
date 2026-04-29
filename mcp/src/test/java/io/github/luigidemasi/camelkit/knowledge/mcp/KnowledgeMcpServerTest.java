package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that boots the full Quarkus app, loads the real Lucene index from classpath resources, and exercises
 * the MCP tools end-to-end.
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
        assertTrue(domains.contains("apache_camel"), "Expected apache_camel domain, got: " + domains);
    }

    // ── apache_camel domain ──────────────────────────────────────

    @Test
    void camelDocsSearch_findsDocumentation() throws Exception {
        List<LuceneSearchService.SearchResult> results = searchService.search("apache_camel", "quarkus", null, null, 5);

        assertFalse(results.isEmpty(), "Expected results for query 'quarkus'");
        assertTrue(results.stream().allMatch(r -> "apache-camel".equals(r.source())),
                "All results should have source 'apache-camel'");
    }

    @Test
    void camelDocsSearch_withVersionFilter() throws Exception {
        List<LuceneSearchService.SearchResult> results
                = searchService.search("apache_camel", "getting started", "4.14", null, 5);

        assertFalse(results.isEmpty(), "Expected results for 'getting started' with version 4.14");
    }

    @Test
    void camelDocsComponentInfoTool_returnsJson() {
        String json = mcpServer.camel_docs_component_info("kafka", "4.14", null);

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\""), "Should contain found field");
    }

    @Test
    void camelDocsComponentInfoTool_withRuntime() {
        String json = mcpServer.camel_docs_component_info("platform-http", "4.14", "quarkus");

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\":true"), "Should find platform-http for quarkus: " + json);
    }

    @Test
    void camelDocsSearchTool_returnsJson() {
        String json = mcpServer.camel_docs_search("release notes", "4.14", 5);

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\""), "Should contain found field");
        assertTrue(json.contains("\"results\""), "Should contain results array");
    }

    // ── Edge cases ─────────────────────────────────────────────────

    @Test
    void lookupNonExistentComponent_returnsEmpty() throws Exception {
        List<LuceneSearchService.SearchResult> results
                = searchService.lookupComponent("apache_camel", "zzz-no-such-component-999", null, null);

        assertTrue(results.isEmpty(), "Should return empty for nonexistent component");
    }

    @Test
    void lookupNonExistentComponentTool_fallsBackToSearch() {
        String json = mcpServer.camel_docs_component_info("zzz-no-such-component-999", null, null);

        assertFalse(json.contains("\"error\""), "Should not return error: " + json);
        assertTrue(json.contains("\"found\""), "Should contain found field");
    }
}
