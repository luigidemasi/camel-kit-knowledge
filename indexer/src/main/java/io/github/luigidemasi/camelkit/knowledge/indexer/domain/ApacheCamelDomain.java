package io.github.luigidemasi.camelkit.knowledge.indexer.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.luigidemasi.camelkit.knowledge.indexer.CamelCatalogIndexer;
import io.github.luigidemasi.camelkit.knowledge.indexer.GitRepoFetcher;
import io.github.luigidemasi.camelkit.knowledge.indexer.JiraFetcher;
import io.github.luigidemasi.camelkit.knowledge.indexer.VersionResolver;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.ReleaseNotesChunker;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.ReleaseNotesChunker.ResolvedIssue;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.SectionChunker;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.SectionChunker.Section;
import io.github.luigidemasi.camelkit.knowledge.indexer.parser.AsciidocConverter;
import io.github.luigidemasi.camelkit.knowledge.indexer.parser.CveParser;
import io.github.luigidemasi.camelkit.knowledge.indexer.parser.CveParser.CveAdvisory;
import io.github.luigidemasi.camelkit.knowledge.schema.DomainMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apache Camel community documentation domain.
 *
 * Sources:
 * <ul>
 * <li>apache/camel — component docs, EIP patterns, user manual, migration guides</li>
 * <li>apache/camel-quarkus — Quarkus extension docs</li>
 * <li>apache/camel-spring-boot — Spring Boot starter docs</li>
 * <li>apache/camel-website — release notes and CVE advisories</li>
 * </ul>
 *
 * Documents are fetched by cloning Git repos (via JGit), AsciiDoc files are rendered to HTML (via AsciidoctorJ), then
 * chunked by section headings. Release notes are chunked per JIRA issue with optional enrichment. CVE advisories are
 * parsed from Markdown frontmatter with optional NVD enrichment.
 */
public class ApacheCamelDomain implements DocumentDomain {

    private static final Logger LOG = LoggerFactory.getLogger(ApacheCamelDomain.class);

    // ── Resolved version matrix (populated dynamically in constructor) ──

    // ── Repo URLs ───────────────────────────────────────────────────────

    private static final String CAMEL_REPO = "https://github.com/apache/camel.git";
    private static final String QUARKUS_REPO = "https://github.com/apache/camel-quarkus.git";
    private static final String SPRING_REPO = "https://github.com/apache/camel-spring-boot.git";
    private static final String WEBSITE_REPO = "https://github.com/apache/camel-website.git";

    // ── Release notes version filter ────────────────────────────────────

    private static final Pattern RELEASE_VERSION_PATTERN = Pattern.compile("release-(\\d+)\\.(\\d+)");

    // ── Component name extraction ───────────────────────────────────────

    private static final Pattern COMPONENT_SUFFIX_PATTERN = Pattern.compile("^(.+)-component$");

    private static final Pattern CAMEL_COMPONENT_PATTERN = Pattern.compile("\\bcamel-([a-z][a-z0-9-]+)");

    private static final Pattern THE_COMPONENT_PATTERN
            = Pattern.compile("\\bthe\\s+([a-z][a-z0-9-]+)\\s+(?:component|extension|endpoint)",
                    Pattern.CASE_INSENSITIVE);

    // ── Fields ──────────────────────────────────────────────────────────

    private final GitRepoFetcher repoFetcher;
    private final AsciidocConverter adocConverter;
    private final SectionChunker sectionChunker;
    private final JiraFetcher jiraFetcher;
    private final CamelCatalogIndexer catalogIndexer;
    private final Path reposDir;
    private final Path cveCacheDir;
    private final Path websiteRepoPath;
    private final List<VersionResolver.ResolvedVersion> versions;
    private final double minReleaseVersion;

    /**
     * @param cacheDir     reserved for future caching (not used directly)
     * @param resourcesDir base path; git repos, JIRA cache, and CVE cache are created underneath
     */
    public ApacheCamelDomain(Path cacheDir, Path resourcesDir) throws IOException {
        this.reposDir = resourcesDir.resolve("apache-camel/repos");
        Files.createDirectories(reposDir);

        Path jiraCacheDir = resourcesDir.resolve("apache-camel/jira-cache");
        Files.createDirectories(jiraCacheDir);

        this.cveCacheDir = resourcesDir.resolve("apache-camel/cve-cache");
        Files.createDirectories(cveCacheDir);

        Path catalogCacheDir = resourcesDir.resolve("apache-camel/catalog-cache");
        Files.createDirectories(catalogCacheDir);
        this.catalogIndexer = new CamelCatalogIndexer(catalogCacheDir);

        this.repoFetcher = new GitRepoFetcher(reposDir);
        this.adocConverter = new AsciidocConverter();
        this.sectionChunker = new SectionChunker();
        this.jiraFetcher = new JiraFetcher(jiraCacheDir);

        // Clone website repo first (needed for version resolution, reused in fetchAndConvertAll)
        try {
            this.websiteRepoPath = repoFetcher.fetchRepo(WEBSITE_REPO, "main", "camel-website");
            this.versions = VersionResolver.resolveVersions(websiteRepoPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Version resolution interrupted", e);
        }

        // Minimum release version for release notes filtering = oldest active version
        this.minReleaseVersion = versions.stream()
                .mapToDouble(v -> {
                    String[] parts = v.label().split("\\.");
                    return Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]) / 100.0;
                })
                .min()
                .orElse(4.10);
    }

    @Override
    public DomainMetadata metadata() {
        return new DomainMetadata(
                "apache_camel",
                "camel_docs",
                "Apache Camel documentation — component reference, EIPs, getting started, " +
                              "migration, release notes, security advisories",
                true,
                true);
    }

    // ── buildChunks — the 3-phase pipeline ──────────────────────────────

    @Override
    public List<DocumentChunk> buildChunks() throws IOException, InterruptedException {

        // Phase 1: Clone repos + render AsciiDoc to HTML, chunk into ConvertedDocs
        LOG.info("Phase 1: Fetching repos and converting AsciiDoc...");
        List<ConvertedDoc> convertedDocs = fetchAndConvertAll();

        // Phase 2: Pre-fetch JIRA issues from release notes
        LOG.info("Phase 2: Pre-fetching JIRA issues...");
        ReleaseNotesChunker releaseNotesChunker = new ReleaseNotesChunker();
        Map<String, ReleaseNotesChunker.ChunkResult> chunkResults = new LinkedHashMap<>();
        Set<String> allJiraIdsToFetch = new LinkedHashSet<>();

        for (ConvertedDoc doc : convertedDocs) {
            if ("release-notes".equals(doc.docType)) {
                ReleaseNotesChunker.ChunkResult result = releaseNotesChunker.chunk(doc.markdown);
                chunkResults.put(doc.key(), result);
                for (ResolvedIssue issue : result.issues()) {
                    allJiraIdsToFetch.add(issue.jiraIds().get(0));
                }
            }
        }

        Map<String, JiraFetcher.JiraIssue> jiraCache = new ConcurrentHashMap<>();
        if (!allJiraIdsToFetch.isEmpty()) {
            int parallelism = Integer.parseInt(System.getProperty("jira.parallelism", "4"));
            LOG.info("  Fetching {} JIRA issues ({} threads)...",
                    allJiraIdsToFetch.size(), parallelism);
            ExecutorService jiraPool = Executors.newFixedThreadPool(parallelism);
            AtomicInteger fetched = new AtomicInteger();
            int total = allJiraIdsToFetch.size();

            for (String jiraId : allJiraIdsToFetch) {
                jiraPool.submit(() -> {
                    JiraFetcher.JiraIssue issue = jiraFetcher.fetch(jiraId);
                    if (issue != null) {
                        jiraCache.put(jiraId, issue);
                    }
                    int done = fetched.incrementAndGet();
                    if (done % 100 == 0 || done == total) {
                        LOG.info("  JIRA fetch progress: {}/{}", done, total);
                    }
                });
            }

            jiraPool.shutdown();
            jiraPool.awaitTermination(2, TimeUnit.HOURS);
            LOG.info("  JIRA fetch complete: {}/{} enriched",
                    jiraCache.size(), total);
        }

        // Phase 3: Build DocumentChunk objects
        LOG.info("Phase 3: Building document chunks...");
        List<DocumentChunk> chunks = new ArrayList<>();

        for (ConvertedDoc doc : convertedDocs) {

            if ("release-notes".equals(doc.docType)) {
                ReleaseNotesChunker.ChunkResult result = chunkResults.get(doc.key());
                int enriched = 0;

                for (ResolvedIssue issue : result.issues()) {
                    String primaryId = issue.jiraIds().get(0);
                    String id = chunkId(doc.version, doc.docType, doc.shortName, primaryId.toLowerCase(Locale.ROOT));

                    String content;
                    String component;
                    List<String> allJiraIds = new ArrayList<>(issue.jiraIds());

                    JiraFetcher.JiraIssue jiraIssue = jiraCache.get(primaryId);
                    if (jiraIssue != null) {
                        content = jiraIssue.toEnrichedContent();
                        component = jiraIssue.components().isEmpty()
                                ? extractComponentName(issue.description())
                                : jiraIssue.components().get(0).toLowerCase(Locale.ROOT)
                                        .replaceFirst("^camel-", "");
                        enriched++;
                    } else {
                        content = primaryId + ": " + issue.description();
                        component = extractComponentName(issue.description());
                    }

                    chunks.add(new DocumentChunk(
                            id, "apache-camel", doc.docType,
                            doc.version, null, component,
                            issue.sectionTitle() + " — " + primaryId,
                            content,
                            doc.runtimes, allJiraIds,
                            null, null, null, null, null));
                }

                for (Section section : result.otherSections()) {
                    String slug = slugify(section.title());
                    String id = chunkId(doc.version, doc.docType, doc.shortName, slug);
                    String component = extractComponentName(section.title());

                    chunks.add(new DocumentChunk(
                            id, "apache-camel", doc.docType,
                            doc.version, null, component,
                            section.title(), section.content(),
                            doc.runtimes, null,
                            null, null, null, null, null));
                }

                LOG.info("  Chunked {}/{}: {} JIRA issues ({} enriched) + {} sections",
                        doc.version, doc.shortName, result.issues().size(),
                        enriched, result.otherSections().size());

            } else if ("cve".equals(doc.docType)) {
                // CVE docs are handled separately below
                continue;
            } else {
                // Standard section-based chunking for component docs, EIPs, user manual, etc.
                List<Section> sections = sectionChunker.chunk(doc.markdown);

                for (Section section : sections) {
                    String slug = slugify(section.title());
                    String id = chunkId(doc.version, doc.docType, doc.shortName, slug);
                    String component = doc.component != null
                            ? doc.component
                            : extractComponentName(section.title());

                    chunks.add(new DocumentChunk(
                            id, "apache-camel", doc.docType,
                            doc.version, null, component,
                            section.title(), section.content(),
                            doc.runtimes, null,
                            null, null, null, null, null));
                }

                LOG.info("  Chunked {}/{} ({}): {} sections",
                        doc.version, doc.shortName, doc.docType, sections.size());
            }
        }

        // CVE advisory chunks (version-independent, from camel-website)
        List<DocumentChunk> cveChunks = buildCveChunks(convertedDocs);
        chunks.addAll(cveChunks);
        LOG.info("  CVE advisories: {} documents indexed", cveChunks.size());

        // Phase 4: Index Camel Catalog metadata (component/EIP options)
        LOG.info("Phase 4: Indexing Camel Catalog metadata...");
        for (VersionResolver.ResolvedVersion ver : versions) {
            try {
                List<DocumentChunk> catalogChunks = catalogIndexer.indexCatalog(ver.camelTag(), ver.label());
                chunks.addAll(catalogChunks);
                LOG.info("  {}: {} catalog entries indexed", ver.label(), catalogChunks.size());
            } catch (IOException e) {
                LOG.warn("  {}: catalog not available ({})", ver.label(), e.getMessage());
            }
        }

        return chunks;
    }

    // ── Phase 1 internals ───────────────────────────────────────────────

    /**
     * Holds a converted document ready for chunking.
     */
    private record ConvertedDoc(
            String version,
            String shortName,
            String docType,
            String markdown,
            String component,
            List<String> runtimes,
            Path sourceFile     // original file path (used for CVE parsing)
    ) {
        String key() {
            return version + "/" + shortName;
        }
    }

    /**
     * Fetch all repos and convert AsciiDoc/Markdown to chunked ConvertedDocs.
     *
     * Downloads all repos in parallel (network-bound), then converts sequentially (AsciidoctorJ is not thread-safe —
     * single JRuby runtime).
     */
    private List<ConvertedDoc> fetchAndConvertAll() throws IOException {
        record RepoTask(String url, String ref, String localName, boolean isTag) {
        }

        // Build deduplicated task list (website repo already cloned in constructor)
        Map<String, RepoTask> uniqueTasks = new LinkedHashMap<>();

        for (VersionResolver.ResolvedVersion ver : versions) {
            String camelLocal = "camel-" + ver.label();
            uniqueTasks.putIfAbsent(camelLocal,
                    new RepoTask(CAMEL_REPO, ver.camelTag(), camelLocal, true));

            if (ver.quarkusTag() != null) {
                String quarkusLocal = "quarkus-" + ver.quarkusTag();
                uniqueTasks.putIfAbsent(quarkusLocal,
                        new RepoTask(QUARKUS_REPO, ver.quarkusTag(), quarkusLocal, true));
            }

            if (ver.springBootTag() != null) {
                String sbLocal = "spring-boot-" + ver.label();
                uniqueTasks.putIfAbsent(sbLocal,
                        new RepoTask(SPRING_REPO, ver.springBootTag(), sbLocal, true));
            }
        }

        List<RepoTask> tasks = new ArrayList<>(uniqueTasks.values());
        int parallelism = Integer.parseInt(System.getProperty("download.parallelism", "4"));
        LOG.info("  Downloading {} repos ({} parallel)...", tasks.size(), parallelism);

        Map<String, Path> repoPaths = new ConcurrentHashMap<>();
        List<String> errors = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        long dlStart = System.currentTimeMillis();

        for (RepoTask task : tasks) {
            pool.submit(() -> {
                try {
                    Path path = repoFetcher.fetchRepo(
                            task.url(), task.ref(), task.localName(), task.isTag());
                    repoPaths.put(task.localName(), path);
                } catch (IOException e) {
                    synchronized (errors) {
                        errors.add(task.localName() + ": " + e.getMessage());
                    }
                    LOG.error("  Failed to download {}: {}", task.localName(), e.getMessage());
                }
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download phase interrupted", e);
        }

        long dlElapsed = (System.currentTimeMillis() - dlStart) / 1000;
        LOG.info("  All repos downloaded: {}/{} succeeded ({}s)",
                repoPaths.size(), tasks.size(), dlElapsed);
        if (!errors.isEmpty()) {
            LOG.warn("  {} repos failed: {}", errors.size(), errors);
        }

        // ── Phase 1b: Convert docs sequentially (AsciidoctorJ not thread-safe) ──
        List<ConvertedDoc> docs = new ArrayList<>();

        // Website repo was already cloned in constructor for version resolution
        docs.addAll(convertReleaseNotes(websiteRepoPath));
        docs.addAll(collectCveFiles(websiteRepoPath));

        for (VersionResolver.ResolvedVersion ver : versions) {
            String label = ver.label();

            Path camelRepo = repoPaths.get("camel-" + label);
            if (camelRepo != null) {
                docs.addAll(convertCamelDocs(camelRepo, label));
            }

            if (ver.quarkusTag() != null) {
                Path quarkusRepo = repoPaths.get("quarkus-" + ver.quarkusTag());
                if (quarkusRepo != null) {
                    docs.addAll(convertQuarkusDocs(quarkusRepo, label));
                }
            }

            if (ver.springBootTag() != null) {
                Path springBootRepo = repoPaths.get("spring-boot-" + label);
                if (springBootRepo != null) {
                    docs.addAll(convertSpringBootDocs(springBootRepo, label));
                }
            }
        }

        return docs;
    }

    /**
     * Convert apache/camel docs: components, EIPs, user manual.
     */
    private List<ConvertedDoc> convertCamelDocs(Path repoDir, String version) throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();

        // Component docs
        Path componentsDir = repoDir.resolve("docs/components/modules/ROOT/pages");
        docs.addAll(convertAdocDir(componentsDir, version, "component"));

        // EIP patterns
        Path eipsDir = repoDir.resolve("core/camel-core-engine/src/main/docs/modules/eips/pages");
        docs.addAll(convertAdocDir(eipsDir, version, "eip"));

        // User manual (migration guides, getting started, etc.)
        Path manualDir = repoDir.resolve("docs/user-manual/modules/ROOT/pages");
        docs.addAll(convertAdocDir(manualDir, version, "user-manual"));

        return docs;
    }

    /**
     * Convert apache/camel-quarkus extension docs.
     */
    private List<ConvertedDoc> convertQuarkusDocs(Path repoDir, String version) throws IOException {
        Path extensionsDir = repoDir.resolve("docs/modules/ROOT/pages/extensions");
        return convertAdocDir(extensionsDir, version, "quarkus-extension");
    }

    /**
     * Convert apache/camel-spring-boot docs.
     */
    private List<ConvertedDoc> convertSpringBootDocs(Path repoDir, String version) throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();

        Path componentsDir = repoDir.resolve("docs/components");
        docs.addAll(convertAdocDir(componentsDir, version, "spring-boot-starter"));

        Path springBootDir = repoDir.resolve("docs/spring-boot");
        docs.addAll(convertAdocDir(springBootDir, version, "spring-boot-starter"));

        return docs;
    }

    /**
     * Convert all .adoc files in a directory to ConvertedDocs. Skips Antora includes (filenames starting with _ or
     * nav).
     */
    private List<ConvertedDoc> convertAdocDir(Path dir, String version, String docType)
            throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();
        List<Path> adocFiles = repoFetcher.listFiles(dir, "*.adoc");

        // Antora partials dir is a sibling of the pages dir
        Path partialsDir = dir.getParent().resolve("partials");

        for (Path file : adocFiles) {
            String fileName = file.getFileName().toString();

            // Skip Antora include/nav files
            if (fileName.startsWith("_") || fileName.startsWith("nav")) {
                continue;
            }

            String baseName = fileName.replace(".adoc", "");
            String component = extractComponentFromFilename(baseName);
            List<String> runtimes = inferRuntimes(docType);

            try {
                Path examplesDir = dir.getParent().resolve("examples");
                String markdown = adocConverter.toMarkdown(file, partialsDir, examplesDir);

                docs.add(new ConvertedDoc(
                        version, baseName, docType, markdown,
                        component, runtimes, file));
            } catch (Exception e) {
                LOG.warn("  Failed to convert {}: {} (skipping)", file, e.getMessage());
            }
        }

        return docs;
    }

    /**
     * Collect release notes from camel-website (Markdown files). Filters to versions >= 4.10.
     */
    private List<ConvertedDoc> convertReleaseNotes(Path websiteRepo) throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();
        Path releasesDir = websiteRepo.resolve("content/releases");
        List<Path> releaseFiles = repoFetcher.listFiles(releasesDir, "release-*.md");

        for (Path file : releaseFiles) {
            String fileName = file.getFileName().toString().replace(".md", "");
            Matcher m = RELEASE_VERSION_PATTERN.matcher(fileName);
            if (!m.find())
                continue;

            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            double ver = major + minor / 100.0;
            if (ver < minReleaseVersion)
                continue;

            String versionLabel = major + "." + minor;
            String markdown = Files.readString(file);

            docs.add(new ConvertedDoc(
                    versionLabel, fileName, "release-notes",
                    markdown, null, null, file));
        }

        return docs;
    }

    /**
     * Collect CVE advisory files from camel-website for later processing. These are stored as ConvertedDocs with
     * docType "cve" and processed separately in buildCveChunks().
     */
    private List<ConvertedDoc> collectCveFiles(Path websiteRepo) throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();
        Path securityDir = websiteRepo.resolve("content/security");
        List<Path> cveFiles = repoFetcher.listFiles(securityDir, "CVE-*.md");

        for (Path file : cveFiles) {
            String fileName = file.getFileName().toString().replace(".md", "");
            String markdown = Files.readString(file);
            docs.add(new ConvertedDoc(
                    null, fileName, "cve", markdown,
                    null, null, file));
        }

        return docs;
    }

    // ── CVE chunk building ──────────────────────────────────────────────

    /**
     * Build CVE advisory chunks from collected CVE ConvertedDocs.
     */
    private List<DocumentChunk> buildCveChunks(List<ConvertedDoc> allDocs) {
        List<DocumentChunk> chunks = new ArrayList<>();

        for (ConvertedDoc doc : allDocs) {
            if (!"cve".equals(doc.docType))
                continue;

            try {
                CveAdvisory cve = CveParser.parse(doc.markdown);
                if (cve == null || cve.cveId() == null) {
                    LOG.warn("  Could not parse CVE from {} (skipping)", doc.shortName);
                    continue;
                }

                // Enrich with NVD data (best-effort)
                cve = CveParser.enrichWithNvd(cve, cveCacheDir);

                String id = "apache-camel-cve-" + cve.cveId().toLowerCase(Locale.ROOT);

                // Build content
                StringBuilder content = new StringBuilder();
                content.append(cve.cveId());
                if (cve.severity() != null) {
                    content.append(" (").append(cve.severity()).append(")");
                }
                content.append(": ").append(cve.summary() != null ? cve.summary() : "");
                if (cve.description() != null) {
                    content.append("\n\n").append(cve.description());
                }
                if (cve.affected() != null) {
                    content.append("\nAffected: ").append(cve.affected());
                }
                if (!cve.fixedVersions().isEmpty()) {
                    content.append("\nFixed in: ").append(String.join(", ", cve.fixedVersions()));
                }
                if (cve.mitigation() != null) {
                    content.append("\nMitigation: ").append(cve.mitigation());
                }
                if (cve.body() != null && !cve.body().isBlank()) {
                    content.append("\n\n").append(cve.body());
                }
                // NVD enrichment
                if (cve.cvssScore() != null) {
                    content.append("\nCVSS: ").append(cve.cvssScore());
                }
                if (cve.cvssVector() != null) {
                    content.append(" (").append(cve.cvssVector()).append(")");
                }
                if (cve.cweId() != null) {
                    content.append("\nCWE: ").append(cve.cweId());
                }

                chunks.add(new DocumentChunk(
                        id, "apache-camel", "cve",
                        null, null, cve.affectedComponent(),
                        cve.cveId() + ": " + (cve.summary() != null ? cve.summary() : ""),
                        content.toString(),
                        null,
                        cve.jiraIds().isEmpty() ? null : cve.jiraIds(),
                        null,       // erratumId
                        null,       // advisoryType
                        cve.severity(),
                        List.of(cve.cveId()),
                        cve.fixedVersions().isEmpty() ? null : cve.fixedVersions()));
            } catch (Exception e) {
                LOG.warn("  Failed to process CVE {}: {}", doc.shortName, e.getMessage());
            }
        }

        return chunks;
    }

    // ── Utility methods ─────────────────────────────────────────────────

    /**
     * Extract component name from AsciiDoc filename. {@code kafka-component.adoc} -> {@code kafka}
     * {@code rest-dsl.adoc} -> {@code rest-dsl}
     */
    static String extractComponentFromFilename(String baseName) {
        if (baseName == null)
            return null;
        Matcher m = COMPONENT_SUFFIX_PATTERN.matcher(baseName);
        if (m.matches()) {
            return m.group(1);
        }
        return baseName;
    }

    /**
     * Extract component name from section title, similar to RhBuildCamelDomain.
     */
    private String extractComponentName(String title) {
        if (title == null)
            return null;
        String lower = title.toLowerCase(Locale.ROOT).trim();

        if (lower.startsWith("camel-")) {
            return lower.substring("camel-".length()).replaceAll("[^a-z0-9-]", "");
        }

        if (lower.matches("[a-z][a-z0-9-]+") && !lower.contains(" ")) {
            return lower;
        }

        Matcher camelMatcher = CAMEL_COMPONENT_PATTERN.matcher(lower);
        if (camelMatcher.find()) {
            return camelMatcher.group(1);
        }

        Matcher theMatcher = THE_COMPONENT_PATTERN.matcher(title);
        if (theMatcher.find()) {
            return theMatcher.group(1).toLowerCase(Locale.ROOT);
        }

        return null;
    }

    /**
     * Infer runtime(s) from document type.
     */
    static List<String> inferRuntimes(String docType) {
        if (docType == null)
            return null;
        return switch (docType) {
            case "quarkus-extension" -> List.of("quarkus");
            case "spring-boot-starter" -> List.of("spring-boot");
            default -> null;
        };
    }

    /**
     * Generate a unique chunk ID.
     */
    private static String chunkId(String version, String docType, String shortName, String suffix) {
        StringBuilder sb = new StringBuilder("apache-camel-");
        if (version != null) {
            sb.append(version).append("-");
        }
        sb.append(docType).append("-").append(shortName).append("-").append(suffix);
        return sb.toString();
    }

    /**
     * Slugify a string for use in chunk IDs.
     */
    private static String slugify(String text) {
        if (text == null)
            return "unknown";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

}
