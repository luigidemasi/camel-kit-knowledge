# Camel Kit Knowledge

Knowledge layer for [camel-kit](https://github.com/luigidemasi/camel-kit) -- semantic search over Apache Camel documentation, release notes, CVE advisories, and component metadata.

## Modules

| Module | Description |
|--------|-------------|
| **schema** | Shared Lucene field constants and `KnowledgeDocument` builder |
| **embedding** | ONNX-based embedding provider (Granite Embedding Small English R2, 384-dim, Q8) |
| **indexer** | Document ingestion pipeline -- Git repos, AsciiDoc conversion, Camel Catalog JARs |
| **index** | Pre-built Lucene index artifact (independently versioned via `${revision}`) |
| **mcp** | Quarkus MCP server exposing search tools to AI agents |

## Prerequisites

- Java 17+
- Maven 3.9+

No Docker. No Docling. No external tokens.

## Data Sources

| Source | Content | Format |
|--------|---------|--------|
| `apache/camel` | Component docs, EIP patterns, user manual | AsciiDoc |
| `apache/camel-website` | Release notes, CVE advisories | Markdown (YAML frontmatter) |
| `apache/camel-quarkus` | Quarkus extension docs | AsciiDoc |
| `apache/camel-spring-boot` | Spring Boot starter docs | AsciiDoc |
| Camel Catalog JAR | Component/EIP option metadata (properties, types, defaults) | JSON |

## Dynamic Version Resolution

Versions are discovered at build time -- nothing is hardcoded.

1. **Active versions** -- the indexer clones `apache/camel-website` and parses release frontmatter (`content/releases/release-*.md`) to find active LTS versions (where `eol > today`) and the latest non-LTS release
2. **Release tags** -- latest release tags are resolved via `git ls-remote` (JGit) for `camel`, `camel-spring-boot`, and `camel-quarkus`
3. **Quarkus-to-Camel mapping** -- resolved by fetching each Quarkus release tag's `pom.xml` from GitHub and reading the `camel.major.minor` property
4. **Tag-aware cloning** -- repos are cloned at immutable release tags (not moving branches). `.fetched-tag` marker files enable cache reuse between builds

## AsciiDoc Conversion

Component documentation is converted directly from AsciiDoc to Markdown via a custom AsciidoctorJ converter (`MarkdownConverter`). No HTML intermediate, no Docling container.

- Antora `partial$` includes resolved by inlining partial files with attribute substitution
- Antora `jsonpath$` / `jsonpathcount$` extensions reimplemented in Java (`JsonPathIncludeProcessor`, pre-processing pipeline)
- `jsonpathTable::` block macros render option tables from component JSON data
- Component docs render with real data (e.g. "The Kafka component supports 127 options")

## Camel Catalog Integration

`CamelCatalogIndexer` downloads the `camel-catalog` JAR from Maven Central for each active version, extracts component and EIP JSON metadata, and creates document chunks with structured option data (properties, types, defaults, descriptions). If the exact version JAR is not on Maven Central (unreleased), it falls back to previous patch versions.

## Build

### Normal build (uses pre-built index)

```bash
mvn clean install
```

The MCP module downloads the ONNX embedding model from HuggingFace during `generate-resources` (skipped if already cached).

### Rebuild index from source

```bash
mvn clean install -pl index -Prebuild-index -Drevision=$(date +%Y%m%d%H%M) -am
```

This will:
1. Clone/update Git repos at latest release tags
2. Render AsciiDoc to Markdown with resolved option tables
3. Download Camel Catalog JARs for structured option data
4. Parse release notes and CVE advisories
5. Enrich release notes with Apache JIRA issue details (public, no auth required)
6. Generate embeddings (384-dim vectors)
7. Write Lucene index to `index/src/main/resources/knowledge-index/`
8. Package and install the index JAR with a timestamp version

## Running the MCP Server

```bash
# Via JBang (recommended)
jbang io.github.luigidemasi:camel-kit-knowledge-mcp:0.0.1-SNAPSHOT:runner

# Or directly
java -jar mcp/target/camel-kit-knowledge-mcp-0.0.1-SNAPSHOT-runner.jar
```

The MCP server resolves the index JAR from Maven repositories at startup. To pin a specific index version:

```properties
# In application.properties or via -D flag
knowledge.index.version=202603131454
```

## MCP Tools

5 tools with `camel_docs_*` prefix:

| Tool | Description |
|------|-------------|
| `camel_docs_search` | General hybrid search across all documentation |
| `camel_docs_component_info` | Component lookup with usage, options, and related CVEs |
| `camel_docs_cve_search` | CVE search by ID, component, severity, or version |
| `camel_docs_release_info` | Release notes by version |
| `camel_docs_jira_lookup` | CAMEL-* JIRA issue details and fix version |

## Search Architecture

**Hybrid search** combining:
- **BM25 text search** (20% weight) -- keyword matching via Lucene's standard analyzer
- **Vector search** (80% weight) -- semantic similarity via KNN float vectors (384-dim Granite embeddings)

Component lookups (`camel_docs_component_info`) use pure BM25 for exact matching.

## Configuration Reference

### System properties

| Property | Purpose | Default |
|----------|---------|---------|
| `-Djira.parallelism` | JIRA fetch thread pool size | `4` |
| `-Ddownload.parallelism` | Git repo download thread pool size | `4` |

### MCP application properties

| Property | Purpose | Default |
|----------|---------|---------|
| `knowledge.index.version` | Pin a specific index JAR version | latest SNAPSHOT |
| `knowledge.index.group-id` | Index artifact group ID | `io.github.luigidemasi` |
| `knowledge.index.artifact-id` | Index artifact ID | `camel-kit-knowledge-index` |

### Cache locations (gitignored)

| Path | Contents |
|------|----------|
| `indexer/src/main/resources/apache-camel/repos/` | Cloned Git repositories |
| `indexer/src/main/resources/apache-camel/catalog-cache/` | Downloaded Camel Catalog JARs |

Apache JIRA (CAMEL-* issues) is public and requires no authentication.

## License

Apache License 2.0
