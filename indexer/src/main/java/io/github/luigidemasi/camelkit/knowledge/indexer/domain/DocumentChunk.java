package io.github.luigidemasi.camelkit.knowledge.indexer.domain;

import java.util.List;

/**
 * A single chunk of documentation, ready for indexing. Produced by parsers/chunkers, consumed by the IndexBuilder.
 */
public record DocumentChunk(
        String id,              // unique chunk ID
        String source,          // e.g., "apache-camel"
        String docType,         // e.g., "component", "release-notes", "cve"
        String sourceVersion,   // e.g., "4.14" (nullable)
        String targetVersion,   // e.g., "4.x" (nullable)
        String component,       // exact component name for lookup (nullable)
        String sectionTitle,    // section heading
        String content,         // chunk text content
        // Runtime (multi-valued: "quarkus", "spring-boot", "main")
        List<String> runtimes,
        // JIRA issue IDs referenced in this chunk (multi-valued, nullable)
        List<String> jiraIds,
        // Security-advisory fields (all nullable for non-security chunks; erratumId/advisoryType are
        // only populated by legacy errata indexes and kept for compatibility with them)
        String erratumId,       // legacy errata only (nullable)
        String advisoryType,    // legacy errata only (nullable)
        String severity,        // e.g., "LOW", "MEDIUM", "HIGH", "CRITICAL"
        List<String> cveIds,    // CVE identifiers (multi-valued)
        List<String> fixedInVersions // versions where the advisory is fixed (multi-valued)
) {
    /** Convenience constructor for non-errata chunks (backwards compatible). */
    public DocumentChunk(String id, String source, String docType,
                         String sourceVersion, String targetVersion, String component,
                         String sectionTitle, String content) {
        this(id, source, docType, sourceVersion, targetVersion, component,
             sectionTitle, content, null, null, null, null, null, null, null);
    }
}
