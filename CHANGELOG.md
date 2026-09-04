# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Added

- **Cross-encoder reranker** — `ms-marco-MiniLM-L-6-v2` (~23 MB ONNX) reranks the top-30 hybrid
  candidates; a sigmoid relevance floor drops noise so out-of-corpus queries return empty instead
  of confidently-scored garbage.
- **`camel_docs_validate_endpoint` MCP tool** — deterministic Camel endpoint URI validation backed
  by `camel-catalog` (unknown options, missing required, enum/syntax errors, catalog version in the
  response).
- **`camel_docs_index_info` MCP tool** — index metadata: covered Camel versions, document counts per
  type, embedding model, and the active search mode, so clients can detect stale coverage.
- **GitHub Releases index distribution** — the knowledge index is published as a release asset
  (`knowledge-index.zip` + sha256-carrying `index.json` manifest) by the new `Index Release`
  workflow (manually dispatched, cut together with version releases). The MCP server resolves the stable
  `releases/latest/download/` manifest URL, downloads/verifies/atomically swaps new versions into
  `~/.camel-kit/knowledge-index/`, opens the index in place (no per-startup extraction), and falls
  back to the cached version offline. `knowledge.index.path` serves a local directory directly
  (dev, tests, air-gapped).
- **Embedding cache** — chunk embeddings cached on disk keyed by model + truncation window + text;
  incremental index rebuilds only embed new or changed chunks (minutes instead of hours), persisted
  across CI runs.
- **Vector-soundness guards** — the index carries an embedding-model stamp (`__index_meta__`) and
  the MCP server runs a startup self-check that re-embeds sampled chunks; on model mismatch,
  dimension mismatch, or unverifiable vectors the vector leg is disabled (BM25 + reranker) with a
  loud warning. The release workflow makes a failed check a hard gate (`-Deval.requireVectors`).
- **Retrieval-quality evaluation harness** — `RetrievalQualityTest` with externalized qrels
  (`mcp/src/test/resources/eval/`), nDCG@10/MRR@10/Recall metrics, and regression floors asserted
  on every build; gated weight-sweep (Recall@30 objective) and corpus-dump helpers;
  `ModelComparisonTest` rebuilt on the production index's stored chunks.
- **Search response metadata** — every search result set carries `search_mode`
  (`hybrid+rerank` / `bm25-only+rerank`) and component lookups carry `match: exact/fuzzy/none`;
  zero-hit queries are logged as a coverage backlog.
- **Java 25** added to the CI build matrix (17, 21, 25).

### Fixed

- **CVE search returned zero results for every query** — the searcher required `doc_type:"errata"`
  while the indexer writes `"cve"`; security searches now match both document generations, guarded
  by an end-to-end test.
- **Version filters were soft (or absent)** — `version`/`docType` are now hard MUST filters in both
  the BM25 leg and the KNN pre-filter; `camel_docs_release_info` actually filters release notes and
  all tools normalize patch versions (`4.14.1` → `4.14`) before exact term matching.
- **`ParseException` on natural-language queries** — free text is escaped before Lucene's
  `QueryParser` (queries containing `:`, `?`, `(` no longer error or query phantom fields).
- **Chunker correctness** — heading markers inside fenced code blocks no longer split sections;
  sections are capped at 6,000 chars with paragraph-boundary splitting (oversized single
  paragraphs are hard-split); section titles carry the parent-heading breadcrumb
  (`"Kafka Component > Endpoint Options"`).
- **Duplicate documents on rebuild** — `IndexWriter` opens with `OpenMode.CREATE` instead of
  appending into an existing index.
- **Silent partial indexes** — repo download and JIRA pre-fetch failures abort the build
  (`-Dindex.allowPartial=true` to override); AsciiDoc partial/include failures are logged instead
  of swallowed; JUL log suppression is scoped to the asciidoctor logger instead of the root logger.
- **CVE advisory parsing** — multi-line YAML frontmatter values are preserved (SnakeYAML with regex
  fallback); NVD CVSS data is parsed structurally with explicit v3.1 preference instead of
  first-match regex scraping.
- **Robustness** — full JSON control-character escaping in MCP responses; `max_results` clamped;
  CVE ids case-normalized; atomic catalog JAR downloads; JIRA 404 negative-caching; deterministic
  quarkus→camel version mapping; zip-slip guard on index extraction; component exact-match field
  restricted to component document types.

### Changed

- **Camel 4.22 coverage** — endpoint validation now uses `camel-catalog` 4.22.0, and index release
  gates require the 4.22 corpus while retaining the latest non-LTS release for historical searches.
- **Embedding window raised 512 → 2,048 tokens** (`-Dembedding.maxLength`); requires a full reindex
  to take effect on stored vectors.
- **Hybrid search defaults** re-validated by the eval harness; the reranker owns final ordering, so
  hybrid weights are tuned on Recall@30.
- **Tests are hermetic** — the mcp test suite evaluates the committed in-repo index via
  `knowledge.index.path`; no Maven artifact or network access required.

### Removed

- **Maven-based index resolution** — `IndexResolver` and the six `maven-resolver*` dependencies are
  gone; the index is data, not a Maven artifact.
- **Docling machinery** — the vestigial docling-serve container start/stop in the `rebuild-index`
  profile; reindexing no longer requires Docker.
- **Dead code** — `DocumentFetcher`, the inert `WeightEvaluationTest` (superseded by
  `RetrievalQualityTest`), and stale Red Hat–era references (errata wording, `RhBuildCamelDomain`
  mentions).
