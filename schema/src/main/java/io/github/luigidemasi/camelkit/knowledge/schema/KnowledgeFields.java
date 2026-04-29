package io.github.luigidemasi.camelkit.knowledge.schema;

/**
 * Lucene field name constants shared between the indexer and the MCP server. Any field name change here is caught at
 * compile time in both projects.
 */
public final class KnowledgeFields {

    private KnowledgeFields() {
    }

    // Identity & filtering
    public static final String ID = "id";
    public static final String DOMAIN = "domain";
    public static final String SOURCE = "source";
    public static final String DOC_TYPE = "doc_type";
    public static final String SOURCE_VERSION = "source_version";
    public static final String TARGET_VERSION = "target_version";

    // Optional exact-match field for component/EIP/dataformat name
    public static final String COMPONENT = "component";

    // Searchable text
    public static final String SECTION_TITLE = "section_title";
    public static final String CONTENT = "content";

    // Vector embedding for semantic search (KnnFloatVectorField)
    public static final String EMBEDDING = "embedding";

    // Runtime (multi-valued: "quarkus", "spring-boot", "main")
    public static final String RUNTIME = "runtime";

    // JIRA issue ID for exact lookup (multi-valued: may have upstream + downstream IDs)
    public static final String JIRA_ID = "jira_id";

    // Errata-specific structured fields
    public static final String ERRATUM_ID = "erratum_id";
    public static final String ADVISORY_TYPE = "advisory_type";
    public static final String SEVERITY = "severity";
    public static final String CVE_IDS = "cve_ids";                 // multi-valued
    public static final String FIXED_IN_VERSIONS = "fixed_in_versions"; // multi-valued

    // CVE-specific fields (for Apache Camel community CVE advisories)
    public static final String CVE_ID = "cve_id";                     // exact CVE identifier
    public static final String AFFECTED_VERSIONS = "affected_versions"; // multi-valued version ranges
    public static final String AFFECTED_COMPONENT = "affected_component"; // component name affected
    public static final String PUBLISHED_DATE = "published_date";       // advisory publication date
    public static final String CVSS_SCORE = "cvss_score";               // NVD CVSS 3.1 base score
    public static final String CVSS_VECTOR = "cvss_vector";             // NVD CVSS vector string
    public static final String CWE_ID = "cwe_id";                       // CWE classification

    // Domain metadata (stored once per domain, JSON-encoded)
    public static final String DOMAIN_META = "_domain_meta";
}
