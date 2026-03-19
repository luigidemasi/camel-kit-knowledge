# Camel Kit Knowledge

Knowledge layer for [camel-kit](https://github.com/luigidemasi/camel-kit) — semantic search over Red Hat Build of Apache Camel documentation, KB articles, errata, and CVEs.

## Modules

| Module | Description |
|--------|-------------|
| **schema** | Shared Lucene field constants and `KnowledgeDocument` builder |
| **embedding** | ONNX-based embedding provider (Granite Embedding Small English R2, 384-dim, Q8) |
| **indexer** | Document ingestion pipeline — HTML guides, KB articles, errata, CVEs via Docling |
| **index** | Pre-built Lucene index artifact (independently versioned via `${revision}`) |
| **mcp** | Quarkus MCP server exposing search tools to AI agents |

## Prerequisites

- Java 17+
- Maven 3.9+
- Docker (only for rebuilding the index — runs [Docling](https://github.com/docling-project/docling-serve) container)

## Build

```bash
mvn clean install
```

This builds all modules using the **pre-built index** shipped in `index/src/main/resources/knowledge-index/`.

The MCP module downloads the ONNX embedding model from HuggingFace during `generate-resources` (skipped if already cached).

## Rebuilding the Index

The pre-built index covers 5 product versions of Red Hat Build of Apache Camel (4.0, 4.4, 4.8, 4.10, 4.14) with HTML guides, KB articles, errata, and CVEs. To rebuild it from source documents:

### 1. Start the Docling container

The indexer uses [docling-serve](https://github.com/docling-project/docling-serve) to convert HTML documentation to Markdown.

```bash
docker run -d --name camel-kit-docling -p 5001:5001 quay.io/docling-project/docling-serve:latest
```

Wait for it to be ready:

```bash
curl -sf http://localhost:5001/health
```

If Docling is running on a different host or port, set the URL:

```bash
export DOCLING_URL="http://myhost:5001"
```

### 2. Set up JIRA access (optional)

The indexer enriches errata with linked JIRA issue details. Apache JIRA (CAMEL-\* issues) is public, but Red Hat JIRA (CEQ-\*, ENTESB-\*, etc.) requires a Personal Access Token:

```bash
export JIRA_RH_TOKEN="your_red_hat_jira_pat"
```

Or pass it as a system property:

```bash
-Djira.rh.token="your_red_hat_jira_pat"
```

Without the token, Red Hat JIRA issues are silently skipped — the index still builds, just without those enrichments.

### 3. Download KB articles (requires Red Hat subscription)

KB articles on access.redhat.com are subscriber-only. A script automates discovery and download using an offline token:

```bash
# Generate a token at https://access.redhat.com/management/api
export RH_OFFLINE_TOKEN="your_offline_token"

cd indexer
./scripts/download-kb-articles.sh
```

The script discovers Camel/Fuse KB articles via the Hydra API, authenticates via Red Hat SSO, and downloads HTML to `indexer/src/main/resources/rh-build-camel/kb-articles/`. It is idempotent (skips already-downloaded files); use `--force` to re-download all.

If KB articles are missing, the indexer skips them with a warning — the index still builds without KB content.

### 4. Run the indexer

```bash
mvn clean install -pl index -Prebuild-index -Drevision=$(date +%Y%m%d%H%M) -am
```

To tune JIRA parallel fetching (default 4 threads):

```bash
mvn clean install -pl index -Prebuild-index -Drevision=$(date +%Y%m%d%H%M) \
    -Djira.parallelism=8 -am
```

This will:
- Download the ONNX embedding model (~52 MB)
- Fetch errata from the Red Hat Hydra API (public, no auth required)
- Fetch CVE details for Security Advisories (public Hydra CVE endpoint)
- Enrich errata with linked JIRA issues (optional, requires `JIRA_RH_TOKEN` for Red Hat issues)
- Parse all documents from `indexer/src/main/resources/rh-build-camel/`
- Generate embeddings for each chunk (384-dim vectors)
- Write the Lucene index to `index/src/main/resources/knowledge-index/`
- Package and install the index JAR with a timestamp version

All fetched data (errata JSON, CVE JSON, JIRA responses) is cached locally under `indexer/src/main/resources/rh-build-camel/` — subsequent rebuilds skip already-cached items.

### 5. Stop Docling

```bash
docker rm -f camel-kit-docling
```

### Index contents

The indexer processes:
- **HTML guides** — downloaded from docs.redhat.com, cached in `indexer/src/main/resources/rh-build-camel/{version}/`
- **KB articles** — Red Hat Knowledgebase articles from `indexer/src/main/resources/rh-build-camel/kb-articles/`
- **Errata** — fetched from Red Hat Hydra API (public), cached as JSON in `indexer/src/main/resources/rh-build-camel/errata/`
- **CVEs** — enriched with CVSS scores, CWE IDs, and affected packages from `indexer/src/main/resources/rh-build-camel/errata/cves/`
- **JIRA issues** — linked from errata, cached in `indexer/src/main/resources/rh-build-camel/jira-cache/`

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

## Search Architecture

The MCP server uses **hybrid search** combining:
- **BM25 text search** (20% weight) — keyword matching via Lucene's standard analyzer
- **Vector search** (80% weight) — semantic similarity via KNN float vectors (384-dim Granite embeddings)

Component lookups (`lookupComponent`) use pure BM25 for exact matching.

## Indexer Configuration Reference

### Download scripts

| Script | Purpose | Auth |
|--------|---------|------|
| `indexer/scripts/download-kb-articles.sh` | Download subscriber-only KB articles | `RH_OFFLINE_TOKEN` (required) |
| `indexer/scripts/download-rh-docs.sh` | Download HTML guides from docs.redhat.com | None (public) |

### Environment variables and system properties

| Variable | Type | Purpose | Required | Default |
|----------|------|---------|----------|---------|
| `RH_OFFLINE_TOKEN` | env | Red Hat SSO offline token for KB article download | For KB articles | None |
| `DOCLING_URL` / `-Ddocling.url` | env / sys prop | Docling service URL | For index rebuild | `http://localhost:5001` |
| `JIRA_RH_TOKEN` / `-Djira.rh.token` | env / sys prop | Bearer token for Red Hat JIRA | No | None (RH issues skipped) |
| `-Djira.parallelism` | sys prop | JIRA fetch thread pool size | No | `4` |
| `-Ddocling.parallelism` | sys prop | Docling conversion thread pool size | No | `4` |
| `-Ddocling.retries` | sys prop | Max retry attempts for Docling | No | `3` |
| `-Ddocling.timeout.minutes` | sys prop | HTTP timeout for Docling requests | No | `10` |

Errata, CVEs (Hydra API), HTML guides (docs.redhat.com), and Apache JIRA are all public. KB articles require a Red Hat subscription (`RH_OFFLINE_TOKEN`). Red Hat JIRA requires a PAT (`JIRA_RH_TOKEN`).

## License

Apache License 2.0
