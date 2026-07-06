package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.github.luigidemasi.camelkit.knowledge.embedding.EmbeddingProvider;
import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxEmbeddingProvider;
import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxReranker;
import io.github.luigidemasi.camelkit.knowledge.schema.KnowledgeFields;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded Lucene search service that reads the pre-built knowledge index. The index is loaded from classpath resources
 * at startup.
 */
@ApplicationScoped
public class LuceneSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneSearchService.class);

    private static final float BM25_WEIGHT = 0.2f;
    private static final float VECTOR_WEIGHT = 0.8f;
    /** How many hybrid-search candidates the cross-encoder reranker scores before cutting to maxResults. */
    private static final int RERANK_CANDIDATES = 30;
    /**
     * Relevance floor on the reranker's sigmoid score. Irrelevant (query, passage) pairs score near 0 on ms-marco
     * cross-encoders; anything below this is noise and is dropped rather than returned with a confident-looking
     * normalized score.
     */
    private static final float MIN_RERANK_SCORE = 0.02f;

    @Inject
    IndexResolver indexResolver;

    private IndexReader reader;
    private IndexSearcher searcher;
    private EmbeddingProvider embeddingProvider;
    private OnnxReranker reranker;
    private Path indexTempDir;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    @PostConstruct
    void init() {
        try {
            indexTempDir = resolveIndex();
            reader = DirectoryReader.open(FSDirectory.open(indexTempDir));
            searcher = new IndexSearcher(reader);
        } catch (Exception e) {
            throw new RuntimeException("Failed to open knowledge index", e);
        }

        // Initialize embedding provider (graceful degradation if model not available)
        try {
            embeddingProvider = new OnnxEmbeddingProvider();
            embeddingProvider.embed("warmup");
        } catch (Exception e) {
            LOG.warn("ONNX embedding model not available, falling back to BM25-only search");
            embeddingProvider = null;
        }

        // Vectors from a different model are silently incompatible even at matching dimensions —
        // disable the vector leg unless the index was built with the runtime model.
        if (embeddingProvider != null) {
            String indexModel = readIndexEmbeddingModel();
            if (!embeddingProvider.modelId().equals(indexModel)) {
                LOG.warn("Index embeddings were built with model '{}' but runtime model is '{}' — "
                         + "disabling vector search (BM25 + reranker only). Rebuild and republish the index.",
                        indexModel != null ? indexModel : "unknown (no index metadata)",
                        embeddingProvider.modelId());
                embeddingProvider = null;
            }
        }

        // Initialize cross-encoder reranker (graceful degradation if model not available)
        try {
            reranker = new OnnxReranker();
            reranker.score("warmup", "warmup");
        } catch (Exception e) {
            LOG.warn("ONNX reranker model not available, returning hybrid-search order");
            reranker = null;
        }
    }

    /** Reads the embedding model the index was built with, or null for legacy indexes without metadata. */
    private String readIndexEmbeddingModel() {
        try {
            TopDocs td = searcher.search(
                    new TermQuery(new Term(KnowledgeFields.ID, KnowledgeFields.INDEX_META_ID)), 1);
            if (td.scoreDocs.length > 0) {
                return searcher.doc(td.scoreDocs[0].doc).get(KnowledgeFields.EMBEDDING_MODEL);
            }
        } catch (IOException e) {
            LOG.warn("Failed to read index embedding-model metadata: {}", e.getMessage());
        }
        return null;
    }

    /** Package-private — used by evaluation tests to access the real index. */
    IndexSearcher getSearcher() {
        return searcher;
    }

    /** Package-private — used by evaluation tests to access the embedding provider. */
    EmbeddingProvider getEmbeddingProvider() {
        return embeddingProvider;
    }

    @PreDestroy
    void close() {
        try {
            if (reader != null)
                reader.close();
        } catch (IOException e) {
            // ignore
        }
        if (indexTempDir != null) {
            deleteRecursively(indexTempDir);
        }
    }

    private static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    /**
     * Get all unique domain IDs in the index.
     */
    public Set<String> getDomains() throws IOException {
        Set<String> domains = new HashSet<>();
        for (int i = 0; i < reader.maxDoc(); i++) {
            Document doc = reader.document(i);
            String domain = doc.get(KnowledgeFields.DOMAIN);
            if (domain != null) {
                domains.add(domain);
            }
        }
        return domains;
    }

    /**
     * Get domain metadata JSON for a given domain.
     */
    public String getDomainMeta(String domainId) throws IOException {
        for (int i = 0; i < reader.maxDoc(); i++) {
            Document doc = reader.document(i);
            if (domainId.equals(doc.get(KnowledgeFields.DOMAIN))) {
                String meta = doc.get(KnowledgeFields.DOMAIN_META);
                if (meta != null)
                    return meta;
            }
        }
        return null;
    }

    /**
     * Exact component lookup within a domain, with optional runtime filtering.
     */
    public List<SearchResult> lookupComponent(String domain, String component, String sourceVersion, String runtime)
            throws IOException {
        BooleanQuery.Builder qb = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(KnowledgeFields.DOMAIN, domain)), BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(KnowledgeFields.COMPONENT, component)), BooleanClause.Occur.MUST);

        if (sourceVersion != null) {
            qb.add(new TermQuery(new Term(KnowledgeFields.SOURCE_VERSION, sourceVersion)), BooleanClause.Occur.MUST);
        }
        if (runtime != null) {
            qb.add(new TermQuery(new Term(KnowledgeFields.RUNTIME, runtime)), BooleanClause.Occur.MUST);
        }

        return executeSearch(qb.build(), 10);
    }

    /**
     * Full-text search within a domain, using hybrid BM25 + vector scoring when available, followed by cross-encoder
     * reranking of the top candidates when the reranker model is available.
     */
    public List<SearchResult> search(
            String domain, String query, String sourceVersion, String targetVersion, int maxResults)
            throws IOException, ParseException {
        return search(domain, query, sourceVersion, targetVersion, null, maxResults);
    }

    /**
     * Full-text search with an optional hard doc_type filter (e.g. "release-notes").
     */
    public List<SearchResult> search(
            String domain, String query, String sourceVersion, String targetVersion, String docType, int maxResults)
            throws IOException, ParseException {
        int candidates = reranker != null ? Math.max(maxResults, RERANK_CANDIDATES) : maxResults;
        List<SearchResult> results = hybridSearch(searcher, embeddingProvider, domain, query, sourceVersion,
                targetVersion, docType, candidates, BM25_WEIGHT, VECTOR_WEIGHT);
        return rerank(query, results, maxResults);
    }

    /** Human-readable description of the active retrieval pipeline — surfaced to MCP clients. */
    String searchMode() {
        return (embeddingProvider != null ? "hybrid" : "bm25-only")
               + (reranker != null ? "+rerank" : "");
    }

    /** Index-level statistics — lets clients detect stale coverage instead of trusting silently degraded answers. */
    public record IndexInfo(int totalDocs, String embeddingModel, String searchMode,
            Map<String, Integer> docTypeCounts, List<String> versions) {
    }

    public IndexInfo indexInfo() throws IOException {
        Map<String, Integer> docTypes = new TreeMap<>();
        Terms docTypeTerms = MultiTerms.getTerms(reader, KnowledgeFields.DOC_TYPE);
        if (docTypeTerms != null) {
            TermsEnum te = docTypeTerms.iterator();
            while (te.next() != null) {
                docTypes.put(te.term().utf8ToString(), te.docFreq());
            }
        }
        List<String> versions = new ArrayList<>();
        Terms versionTerms = MultiTerms.getTerms(reader, KnowledgeFields.SOURCE_VERSION);
        if (versionTerms != null) {
            TermsEnum te = versionTerms.iterator();
            while (te.next() != null) {
                versions.add(te.term().utf8ToString());
            }
        }
        return new IndexInfo(reader.numDocs(), readIndexEmbeddingModel(), searchMode(), docTypes, versions);
    }

    /**
     * Rerank hybrid-search candidates with the cross-encoder and cut to maxResults. Falls back to the input order
     * (truncated) when the reranker is unavailable. Results scoring below {@link #MIN_RERANK_SCORE} are dropped — the
     * cross-encoder sigmoid is an absolute relevance signal, so a query about content absent from the corpus returns an
     * empty list instead of confidently-scored noise.
     */
    private List<SearchResult> rerank(String query, List<SearchResult> candidates, int maxResults) {
        if (reranker == null || candidates.size() <= 1) {
            return candidates.size() > maxResults ? candidates.subList(0, maxResults) : candidates;
        }

        List<SearchResult> scored = new ArrayList<>(candidates.size());
        for (SearchResult r : candidates) {
            String passage = (r.sectionTitle() != null ? r.sectionTitle() + " " : "") + r.content();
            float score = reranker.score(query, passage);
            if (score < MIN_RERANK_SCORE) {
                continue;
            }
            scored.add(new SearchResult(
                    r.id(), r.source(), r.docType(), r.sourceVersion(), r.targetVersion(),
                    r.runtimes(), r.sectionTitle(), r.content(), score));
        }
        scored.sort((a, b) -> Float.compare(b.score(), a.score()));
        return scored.size() > maxResults ? scored.subList(0, maxResults) : scored;
    }

    /**
     * Hybrid BM25 + vector search. Package-private and static so tests can call it directly with an in-memory index and
     * embedding provider.
     */
    static List<SearchResult> hybridSearch(
            IndexSearcher searcher, EmbeddingProvider embeddingProvider,
            String domain, String query, String sourceVersion,
            String targetVersion, int maxResults,
            float bm25Weight, float vectorWeight)
            throws IOException, ParseException {
        return hybridSearch(searcher, embeddingProvider, domain, query, sourceVersion, targetVersion, null,
                maxResults, bm25Weight, vectorWeight);
    }

    /**
     * Hybrid BM25 + vector search with an optional hard doc_type filter. sourceVersion/targetVersion/docType are hard
     * (MUST) filters applied to both the BM25 leg and the KNN pre-filter — a soft version boost that mostly works is
     * worse than a hard filter, because it fails rarely and invisibly.
     */
    static List<SearchResult> hybridSearch(
            IndexSearcher searcher, EmbeddingProvider embeddingProvider,
            String domain, String query, String sourceVersion,
            String targetVersion, String docType, int maxResults,
            float bm25Weight, float vectorWeight)
            throws IOException, ParseException {

        StandardAnalyzer analyzer = new StandardAnalyzer();

        // Hard filters shared by the BM25 leg and the KNN pre-filter
        BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(KnowledgeFields.DOMAIN, domain)), BooleanClause.Occur.MUST);
        if (sourceVersion != null) {
            filterBuilder.add(new TermQuery(new Term(KnowledgeFields.SOURCE_VERSION, sourceVersion)),
                    BooleanClause.Occur.MUST);
        }
        if (targetVersion != null) {
            filterBuilder.add(new TermQuery(new Term(KnowledgeFields.TARGET_VERSION, targetVersion)),
                    BooleanClause.Occur.MUST);
        }
        if (docType != null) {
            filterBuilder.add(new TermQuery(new Term(KnowledgeFields.DOC_TYPE, docType)),
                    BooleanClause.Occur.MUST);
        }
        BooleanQuery filter = filterBuilder.build();

        // --- BM25 text search --- (query text is escaped: natural-language questions routinely contain
        // Lucene syntax chars like ':', '?', '(' that would throw ParseException or query phantom fields)
        QueryParser parser = new QueryParser(KnowledgeFields.CONTENT, analyzer);
        BooleanQuery bm25Query = new BooleanQuery.Builder()
                .add(filter, BooleanClause.Occur.MUST)
                .add(parser.parse(QueryParser.escape(query)), BooleanClause.Occur.MUST)
                .build();

        int fetchSize = Math.max(1, maxResults) * 3;
        TopDocs bm25Docs = searcher.search(bm25Query, fetchSize);

        // --- Vector search (if embedding provider available) ---
        TopDocs vectorDocs = null;
        if (embeddingProvider != null) {
            float[] queryVector = embeddingProvider.embed(query);
            KnnFloatVectorQuery vectorQuery = new KnnFloatVectorQuery(
                    KnowledgeFields.EMBEDDING, queryVector, fetchSize, filter);
            vectorDocs = searcher.search(vectorQuery, fetchSize);
        }

        // --- Hybrid merge ---
        Map<Integer, float[]> scoreMap = new LinkedHashMap<>();

        float bm25Max = 0;
        for (ScoreDoc sd : bm25Docs.scoreDocs) {
            bm25Max = Math.max(bm25Max, sd.score);
        }
        for (ScoreDoc sd : bm25Docs.scoreDocs) {
            float normalizedBm25 = bm25Max > 0 ? sd.score / bm25Max : 0;
            scoreMap.put(sd.doc, new float[]{normalizedBm25, 0});
        }

        if (vectorDocs != null && vectorDocs.scoreDocs.length > 0) {
            float vectorMax = 0;
            for (ScoreDoc sd : vectorDocs.scoreDocs) {
                vectorMax = Math.max(vectorMax, sd.score);
            }
            for (ScoreDoc sd : vectorDocs.scoreDocs) {
                float normalizedVector = vectorMax > 0 ? sd.score / vectorMax : 0;
                float[] scores = scoreMap.get(sd.doc);
                if (scores != null) {
                    scores[1] = normalizedVector;
                } else {
                    scoreMap.put(sd.doc, new float[]{0, normalizedVector});
                }
            }
        }

        List<Map.Entry<Integer, Float>> combined = new ArrayList<>();
        for (Map.Entry<Integer, float[]> entry : scoreMap.entrySet()) {
            float[] scores = entry.getValue();
            float combinedScore = bm25Weight * scores[0] + vectorWeight * scores[1];
            combined.add(Map.entry(entry.getKey(), combinedScore));
        }
        combined.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(combined.size(), maxResults); i++) {
            Map.Entry<Integer, Float> entry = combined.get(i);
            Document doc = searcher.doc(entry.getKey());
            String[] rtVals = doc.getValues(KnowledgeFields.RUNTIME);
            List<String> runtimes = rtVals != null && rtVals.length > 0 ? List.of(rtVals) : List.of();
            results.add(new SearchResult(
                    doc.get(KnowledgeFields.ID),
                    doc.get(KnowledgeFields.SOURCE),
                    doc.get(KnowledgeFields.DOC_TYPE),
                    doc.get(KnowledgeFields.SOURCE_VERSION),
                    doc.get(KnowledgeFields.TARGET_VERSION),
                    runtimes,
                    doc.get(KnowledgeFields.SECTION_TITLE),
                    doc.get(KnowledgeFields.CONTENT),
                    entry.getValue()));
        }

        return results;
    }

    /**
     * Search by JIRA issue ID (exact match on multi-valued jira_id field).
     */
    public List<SearchResult> searchByJiraId(String jiraId) throws IOException {
        BooleanQuery query = new BooleanQuery.Builder()
                .add(new TermQuery(new Term(KnowledgeFields.JIRA_ID, jiraId)), BooleanClause.Occur.MUST)
                .build();

        return executeSearch(query, 20);
    }

    /**
     * Security documents live under doc_type "cve" (Apache advisories) or "errata" (legacy Red Hat errata) depending on
     * the indexer era — match both. This clause exists because indexer and searcher used to disagree on the value
     * ("errata" vs "cve"), which made every CVE search silently return zero results.
     */
    static BooleanQuery securityDocTypeClause() {
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term(KnowledgeFields.DOC_TYPE, "cve")), BooleanClause.Occur.SHOULD)
                .add(new TermQuery(new Term(KnowledgeFields.DOC_TYPE, "errata")), BooleanClause.Occur.SHOULD)
                .build();
    }

    /** Case variants for exact StringField matching — indexed severities vary ("HIGH" vs "Important"). */
    private static BooleanQuery caseVariantsClause(String field, String value) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String v : Set.of(value, value.toUpperCase(Locale.ROOT), value.toLowerCase(Locale.ROOT),
                value.isEmpty()
                        ? value
                        : Character.toUpperCase(value.charAt(0))
                          + value.substring(1).toLowerCase(Locale.ROOT))) {
            b.add(new TermQuery(new Term(field, v)), BooleanClause.Occur.SHOULD);
        }
        return b.build();
    }

    /**
     * Search security advisories by CVE ID (exact match on multi-valued cve_ids field).
     */
    public List<ErrataSearchResult> searchByCve(String cveId) throws IOException {
        return searchByCve(searcher, cveId, 20);
    }

    /** Package-private and static so tests can run it against an in-memory index. */
    static List<ErrataSearchResult> searchByCve(IndexSearcher searcher, String cveId, int maxResults)
            throws IOException {
        BooleanQuery query = new BooleanQuery.Builder()
                .add(securityDocTypeClause(), BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(KnowledgeFields.CVE_IDS, cveId)), BooleanClause.Occur.MUST)
                .build();

        return executeErrataSearch(searcher, query, maxResults);
    }

    /**
     * Search security advisories with structured filters (advisory type, severity, version) and optional free-text.
     */
    public List<ErrataSearchResult> searchErrata(
            String advisoryType, String severity,
            String version, String freeText, int maxResults)
            throws IOException, ParseException {

        BooleanQuery.Builder qb = new BooleanQuery.Builder()
                .add(securityDocTypeClause(), BooleanClause.Occur.MUST);

        if (advisoryType != null) {
            qb.add(new TermQuery(new Term(KnowledgeFields.ADVISORY_TYPE, advisoryType)), BooleanClause.Occur.MUST);
        }
        if (severity != null) {
            qb.add(caseVariantsClause(KnowledgeFields.SEVERITY, severity), BooleanClause.Occur.MUST);
        }
        if (version != null) {
            qb.add(new TermQuery(new Term(KnowledgeFields.FIXED_IN_VERSIONS, version)), BooleanClause.Occur.MUST);
        }
        if (freeText != null && !freeText.isBlank()) {
            QueryParser parser = new QueryParser(KnowledgeFields.CONTENT, analyzer);
            qb.add(parser.parse(QueryParser.escape(freeText)), BooleanClause.Occur.MUST);
        }

        return executeErrataSearch(searcher, qb.build(), maxResults);
    }

    /**
     * Search security advisories by fixed-in version (exact match on multi-valued fixed_in_versions field).
     */
    public List<ErrataSearchResult> searchByVersion(String version, String advisoryType, int maxResults)
            throws IOException {

        BooleanQuery.Builder qb = new BooleanQuery.Builder()
                .add(securityDocTypeClause(), BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term(KnowledgeFields.FIXED_IN_VERSIONS, version)), BooleanClause.Occur.MUST);

        if (advisoryType != null) {
            qb.add(new TermQuery(new Term(KnowledgeFields.ADVISORY_TYPE, advisoryType)), BooleanClause.Occur.MUST);
        }

        return executeErrataSearch(searcher, qb.build(), maxResults);
    }

    private static List<ErrataSearchResult> executeErrataSearch(IndexSearcher searcher, Query query, int maxResults)
            throws IOException {
        TopDocs topDocs = searcher.search(query, maxResults);
        List<ErrataSearchResult> results = new ArrayList<>();

        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            String[] cveIds = doc.getValues(KnowledgeFields.CVE_IDS);
            String[] fixedIn = doc.getValues(KnowledgeFields.FIXED_IN_VERSIONS);

            results.add(new ErrataSearchResult(
                    doc.get(KnowledgeFields.ID),
                    doc.get(KnowledgeFields.ERRATUM_ID),
                    doc.get(KnowledgeFields.ADVISORY_TYPE),
                    doc.get(KnowledgeFields.SEVERITY),
                    doc.get(KnowledgeFields.SECTION_TITLE),
                    doc.get(KnowledgeFields.CONTENT),
                    cveIds != null ? List.of(cveIds) : List.of(),
                    fixedIn != null ? List.of(fixedIn) : List.of(),
                    scoreDoc.score));
        }

        return results;
    }

    private List<SearchResult> executeSearch(Query query, int maxResults) throws IOException {
        TopDocs topDocs = searcher.search(query, maxResults);
        List<SearchResult> results = new ArrayList<>();

        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            String[] rtVals = doc.getValues(KnowledgeFields.RUNTIME);
            List<String> runtimes = rtVals != null && rtVals.length > 0 ? List.of(rtVals) : List.of();
            results.add(new SearchResult(
                    doc.get(KnowledgeFields.ID),
                    doc.get(KnowledgeFields.SOURCE),
                    doc.get(KnowledgeFields.DOC_TYPE),
                    doc.get(KnowledgeFields.SOURCE_VERSION),
                    doc.get(KnowledgeFields.TARGET_VERSION),
                    runtimes,
                    doc.get(KnowledgeFields.SECTION_TITLE),
                    doc.get(KnowledgeFields.CONTENT),
                    scoreDoc.score));
        }

        return results;
    }

    /**
     * Resolve the knowledge index. Tries IndexResolver first (Maven artifact download), falls back to classpath
     * extraction for backward compatibility.
     */
    private Path resolveIndex() throws IOException {
        // Try IndexResolver (downloads from Maven repo)
        try {
            Path resolved = indexResolver.resolve();
            LOG.info("LuceneSearchService: IndexResolver succeeded, path = {}", resolved);
            String[] files = resolved.toFile().list();
            LOG.info("LuceneSearchService: extracted {} files", files != null ? files.length : 0);
            return resolved;
        } catch (IndexResolver.IndexResolverException e) {
            LOG.warn("IndexResolver failed ({}), falling back to classpath extraction", e.getMessage());
        }

        // Fallback: extract from classpath (legacy — index bundled in uber-jar)
        Path classpath = extractIndexFromClasspath();
        String[] files = classpath.toFile().list();
        LOG.info("LuceneSearchService: classpath fallback, path = {}, files = {}",
                classpath, files != null ? files.length : 0);
        return classpath;
    }

    private Path extractIndexFromClasspath() throws IOException {
        Path tempDir = Files.createTempDirectory("knowledge-index");

        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Read manifest to discover index files
        List<String> indexFiles;
        try (InputStream manifestIs = cl.getResourceAsStream("knowledge-index/INDEX_FILES")) {
            if (manifestIs != null) {
                indexFiles = new String(manifestIs.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .filter(l -> !l.isBlank())
                        .toList();
            } else {
                // Fallback to known files
                indexFiles = List.of("segments_1", "_0.cfs", "_0.cfe", "_0.si");
            }
        }

        for (String file : indexFiles) {
            try (InputStream is = cl.getResourceAsStream("knowledge-index/" + file)) {
                if (is != null) {
                    Files.copy(is, tempDir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        return tempDir;
    }

    /**
     * Search result record.
     */
    public record SearchResult(
            String id,
            String source,
            String docType,
            String sourceVersion,
            String targetVersion,
            List<String> runtimes,
            String sectionTitle,
            String content,
            float score) {
    }

    /**
     * Errata-specific search result with structured fields.
     */
    public record ErrataSearchResult(
            String id,
            String erratumId,
            String advisoryType,
            String severity,
            String sectionTitle,
            String content,
            List<String> cveIds,
            List<String> fixedInVersions,
            float score) {
    }
}
