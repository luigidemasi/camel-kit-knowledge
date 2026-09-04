# Camel Kit Knowledge

Knowledge layer for [camel-kit](https://github.com/luigidemasi/camel-kit) -- semantic search over Apache Camel documentation, release notes, CVE advisories, and component metadata.

## Modules

| Module | Description |
|--------|-------------|
| **schema** | Shared Lucene field constants and `KnowledgeDocument` builder |
| **embedding** | ONNX-based embedding provider (Granite Embedding Small English R2, 384-dim, Q8) |
| **indexer** | Document ingestion pipeline -- Git repos, AsciiDoc conversion, Camel Catalog JARs |
| **index** | Pre-built Lucene index (published as a GitHub Release asset, see Index Distribution) |
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
./mvnw install -pl index -Prebuild-index -am -B
```

This will:
1. Clone/update Git repos at latest release tags
2. Render AsciiDoc to Markdown with resolved option tables
3. Download Camel Catalog JARs for structured option data
4. Parse release notes and CVE advisories
5. Enrich release notes with Apache JIRA issue details (public, no auth required)
6. Generate embeddings (384-dim vectors; cached in `apache-camel/embedding-cache/`, so
   incremental rebuilds only embed new or changed chunks)
7. Write the Lucene index to `index/src/main/resources/knowledge-index/` plus an
   `index.json` manifest skeleton

## Index Distribution

The index is **data, not code** — it is published as a GitHub Release asset, not a Maven artifact.
Reindexing is performed on a branch; the generated index is committed with a signed commit and reviewed through a PR.
After that PR is merged, the `Index Release` workflow (manually dispatched from `main`, cut together with version releases)
builds the reviewed index without regenerating it, runs the retrieval-quality gate (`RetrievalQualityTest`, with working
vectors required), verifies that the build did not modify the index, and publishes the reviewed `knowledge-index.zip` +
`index.json` to a release tagged `index-YYYY.MM.DD-HHMMSS`.

At startup the MCP server resolves the index in this order:

1. **`knowledge.index.path`** — a local index directory, used in place (dev, tests, air-gapped)
2. **`knowledge.index.url`** manifest (default: `https://github.com/luigidemasi/camel-kit-knowledge/releases/latest/download/index.json`)
   — compared against the local cache in `~/.camel-kit/knowledge-index/`; a new version is
   downloaded, sha256-verified, unzipped, and atomically swapped in. Offline or unreachable URL
   falls back to the cached version. The index is opened directly from the cache — no per-startup extraction.
3. **Classpath** — legacy fallback for uber-jars bundling the index

Two runtime guards protect the vector leg: the index carries an embedding-model stamp
(`__index_meta__`), and a startup self-check re-embeds a few stored chunks and verifies the
stored vectors match — on any mismatch, vector search is disabled and search degrades to
BM25 + reranker with a loud log line.

## Running the MCP Server

```bash
# Via JBang (recommended)
jbang --repos central_snap=https://central.sonatype.com/repository/maven-snapshots/ \
  io.github.luigidemasi:camel-kit-knowledge-mcp:0.0.1-SNAPSHOT:runner

# Or directly
java -jar mcp/target/camel-kit-knowledge-mcp-0.0.1-SNAPSHOT-runner.jar
```

To pin a specific index or run air-gapped:

```properties
# In application.properties or via -D flag
knowledge.index.url=file:///opt/mirrors/camel-kit/index.json   # pinned/mirrored manifest
knowledge.index.path=/opt/camel-kit/knowledge-index            # local dir, no download
```

## MCP Tools

7 tools with `camel_docs_*` prefix:

| Tool | Description |
|------|-------------|
| `camel_docs_search` | General hybrid search across all documentation |
| `camel_docs_component_info` | Component lookup with usage, options, and related CVEs |
| `camel_docs_cve_search` | CVE search by ID, component, severity, or version |
| `camel_docs_release_info` | Release notes by version |
| `camel_docs_jira_lookup` | CAMEL-* JIRA issue details and fix version |
| `camel_docs_validate_endpoint` | Deterministic endpoint URI validation against the Camel catalog |
| `camel_docs_index_info` | Index metadata: covered versions, doc counts, embedding model, search mode |

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
| `knowledge.index.path` | Local index directory, used in place (no download) | _(unset)_ |
| `knowledge.index.url` | Index manifest URL (https:// or file://) | latest GitHub release |
| `knowledge.index.cache-dir` | Local cache for downloaded index versions | `~/.camel-kit/knowledge-index` |

### Cache locations (gitignored)

| Path | Contents |
|------|----------|
| `indexer/src/main/resources/apache-camel/repos/` | Cloned Git repositories |
| `indexer/src/main/resources/apache-camel/catalog-cache/` | Downloaded Camel Catalog JARs |
| `indexer/src/main/resources/apache-camel/embedding-cache/` | Chunk embeddings keyed by model+text (incremental rebuilds) |

Apache JIRA (CAMEL-* issues) is public and requires no authentication.

## License

Apache License 2.0
