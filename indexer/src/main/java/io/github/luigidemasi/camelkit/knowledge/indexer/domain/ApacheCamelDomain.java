package io.github.luigidemasi.camelkit.knowledge.indexer.domain;

import io.github.luigidemasi.camelkit.knowledge.indexer.GitRepoFetcher;
import io.github.luigidemasi.camelkit.knowledge.indexer.JiraFetcher;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.ReleaseNotesChunker;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.ReleaseNotesChunker.ResolvedIssue;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.SectionChunker;
import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.SectionChunker.Section;
import io.github.luigidemasi.camelkit.knowledge.indexer.parser.AsciidocConverter;
import io.github.luigidemasi.camelkit.knowledge.indexer.parser.CveParser;
import io.github.luigidemasi.camelkit.knowledge.indexer.parser.CveParser.CveAdvisory;
import io.github.luigidemasi.camelkit.knowledge.schema.DomainMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apache Camel community documentation domain.
 *
 * Sources:
 * <ul>
 *   <li>apache/camel — component docs, EIP patterns, user manual, migration guides</li>
 *   <li>apache/camel-quarkus — Quarkus extension docs</li>
 *   <li>apache/camel-spring-boot — Spring Boot starter docs</li>
 *   <li>apache/camel-website — release notes and CVE advisories</li>
 * </ul>
 *
 * Documents are fetched by cloning Git repos (via JGit), AsciiDoc files are
 * rendered to HTML (via AsciidoctorJ), then chunked by section headings.
 * Release notes are chunked per JIRA issue with optional enrichment.
 * CVE advisories are parsed from Markdown frontmatter with optional NVD enrichment.
 */
public class ApacheCamelDomain implements DocumentDomain {

    // ── Version matrix ──────────────────────────────────────────────────
    //
    // Each version specifies branches for the three main repos.
    // Null branch means "skip this repo for that version."
    record VersionSpec(String label, String camelBranch,
                       String quarkusBranch, String springBootBranch) {}

    static final List<VersionSpec> VERSIONS = List.of(
            new VersionSpec("4.10", "camel-4.10.x", "3.27.x", "4.10.x"),
            new VersionSpec("4.14", "camel-4.14.x", "3.27.x", "4.14.x"),
            new VersionSpec("4.18", "camel-4.18.x", "3.33.x", "4.18.x"),
            new VersionSpec("4.19", "camel-4.19.0", null, null)
    );

    // ── Repo URLs ───────────────────────────────────────────────────────

    private static final String CAMEL_REPO     = "https://github.com/apache/camel.git";
    private static final String QUARKUS_REPO   = "https://github.com/apache/camel-quarkus.git";
    private static final String SPRING_REPO    = "https://github.com/apache/camel-spring-boot.git";
    private static final String WEBSITE_REPO   = "https://github.com/apache/camel-website.git";

    // ── Release notes version filter ────────────────────────────────────

    private static final Pattern RELEASE_VERSION_PATTERN =
            Pattern.compile("release-(\\d+)\\.(\\d+)");

    private static final double MIN_RELEASE_VERSION = 4.10;

    // ── Component name extraction ───────────────────────────────────────

    private static final Pattern COMPONENT_SUFFIX_PATTERN =
            Pattern.compile("^(.+)-component$");

    private static final Pattern CAMEL_COMPONENT_PATTERN =
            Pattern.compile("\\bcamel-([a-z][a-z0-9-]+)");

    private static final Pattern THE_COMPONENT_PATTERN =
            Pattern.compile("\\bthe\\s+([a-z][a-z0-9-]+)\\s+(?:component|extension|endpoint)",
                    Pattern.CASE_INSENSITIVE);

    // ── Fields ──────────────────────────────────────────────────────────

    private final GitRepoFetcher repoFetcher;
    private final AsciidocConverter adocConverter;
    private final SectionChunker sectionChunker;
    private final JiraFetcher jiraFetcher;
    private final Path reposDir;
    private final Path cveCacheDir;

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

        this.repoFetcher = new GitRepoFetcher(reposDir);
        this.adocConverter = new AsciidocConverter();
        this.sectionChunker = new SectionChunker();
        this.jiraFetcher = new JiraFetcher(jiraCacheDir);
    }

    @Override
    public DomainMetadata metadata() {
        return new DomainMetadata(
                "apache_camel",
                "camel_docs",
                "Apache Camel documentation — component reference, EIPs, getting started, " +
                        "migration, release notes, security advisories",
                true,
                true
        );
    }

    // ── buildChunks — the 3-phase pipeline ──────────────────────────────

    @Override
    public List<DocumentChunk> buildChunks() throws IOException, InterruptedException {

        // Phase 1: Clone repos + render AsciiDoc to HTML, chunk into ConvertedDocs
        System.out.println("Phase 1: Fetching repos and converting AsciiDoc...");
        List<ConvertedDoc> convertedDocs = fetchAndConvertAll();

        // Phase 2: Pre-fetch JIRA issues from release notes
        System.out.println("Phase 2: Pre-fetching JIRA issues...");
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
            System.out.printf("  Fetching %d JIRA issues (%d threads)...%n",
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
                        System.out.printf("  JIRA fetch progress: %d/%d%n", done, total);
                    }
                });
            }

            jiraPool.shutdown();
            jiraPool.awaitTermination(2, TimeUnit.HOURS);
            System.out.printf("  JIRA fetch complete: %d/%d enriched%n",
                    jiraCache.size(), total);
        }

        // Phase 3: Build DocumentChunk objects
        System.out.println("Phase 3: Building document chunks...");
        List<DocumentChunk> chunks = new ArrayList<>();

        for (ConvertedDoc doc : convertedDocs) {

            if ("release-notes".equals(doc.docType)) {
                ReleaseNotesChunker.ChunkResult result = chunkResults.get(doc.key());
                int enriched = 0;

                for (ResolvedIssue issue : result.issues()) {
                    String primaryId = issue.jiraIds().get(0);
                    String id = chunkId(doc.version, doc.docType, doc.shortName, primaryId.toLowerCase());

                    String content;
                    String component;
                    List<String> allJiraIds = new ArrayList<>(issue.jiraIds());

                    JiraFetcher.JiraIssue jiraIssue = jiraCache.get(primaryId);
                    if (jiraIssue != null) {
                        content = jiraIssue.toEnrichedContent();
                        component = jiraIssue.components().isEmpty()
                                ? extractComponentName(issue.description())
                                : jiraIssue.components().get(0).toLowerCase()
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
                            null, null, null, null, null
                    ));
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
                            null, null, null, null, null
                    ));
                }

                System.out.printf("  Chunked %s/%s: %d JIRA issues (%d enriched) + %d sections%n",
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
                    String component = doc.component != null ? doc.component
                            : extractComponentName(section.title());

                    chunks.add(new DocumentChunk(
                            id, "apache-camel", doc.docType,
                            doc.version, null, component,
                            section.title(), section.content(),
                            doc.runtimes, null,
                            null, null, null, null, null
                    ));
                }

                System.out.printf("  Chunked %s/%s (%s): %d sections%n",
                        doc.version, doc.shortName, doc.docType, sections.size());
            }
        }

        // CVE advisory chunks (version-independent, from camel-website)
        List<DocumentChunk> cveChunks = buildCveChunks(convertedDocs);
        chunks.addAll(cveChunks);
        System.out.printf("  CVE advisories: %d documents indexed%n", cveChunks.size());

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
     */
    private List<ConvertedDoc> fetchAndConvertAll() throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();

        // Clone camel-website once (release notes + CVEs are version-independent)
        Path websiteRepo = repoFetcher.fetchRepo(WEBSITE_REPO, "main", "camel-website");
        docs.addAll(convertReleaseNotes(websiteRepo));
        docs.addAll(collectCveFiles(websiteRepo));

        // Per-version repos
        for (VersionSpec ver : VERSIONS) {
            String label = ver.label();

            // apache/camel
            String camelLocalName = "camel-" + ver.camelBranch();
            Path camelRepo = repoFetcher.fetchRepo(CAMEL_REPO, ver.camelBranch(), camelLocalName);
            docs.addAll(convertCamelDocs(camelRepo, label));

            // apache/camel-quarkus (skip if branch is null)
            if (ver.quarkusBranch() != null) {
                String qLocalName = "camel-quarkus-" + ver.quarkusBranch();
                Path quarkusRepo = repoFetcher.fetchRepo(QUARKUS_REPO, ver.quarkusBranch(), qLocalName);
                docs.addAll(convertQuarkusDocs(quarkusRepo, label));
            }

            // apache/camel-spring-boot (skip if branch is null)
            if (ver.springBootBranch() != null) {
                String sbLocalName = "camel-spring-boot-" + ver.springBootBranch();
                Path springBootRepo = repoFetcher.fetchRepo(SPRING_REPO, ver.springBootBranch(), sbLocalName);
                docs.addAll(convertSpringBootDocs(springBootRepo, label));
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
     * Convert all .adoc files in a directory to ConvertedDocs.
     * Skips Antora includes (filenames starting with _ or nav).
     */
    private List<ConvertedDoc> convertAdocDir(Path dir, String version, String docType)
            throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();
        List<Path> adocFiles = repoFetcher.listFiles(dir, "*.adoc");

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
                String html = adocConverter.toHtml(file);
                // SectionChunker expects Markdown headings; AsciiDoc→HTML gives us HTML.
                // We pass the HTML through the SectionChunker which works with Markdown headings.
                // AsciidoctorJ output is HTML, so we need a simple conversion.
                String markdown = htmlToSimpleMarkdown(html);

                docs.add(new ConvertedDoc(version, baseName, docType, markdown,
                        component, runtimes, file));
            } catch (Exception e) {
                System.out.printf("  WARN: Failed to convert %s: %s (skipping)%n",
                        file, e.getMessage());
            }
        }

        return docs;
    }

    /**
     * Collect release notes from camel-website (Markdown files).
     * Filters to versions >= 4.10.
     */
    private List<ConvertedDoc> convertReleaseNotes(Path websiteRepo) throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();
        Path releasesDir = websiteRepo.resolve("content/releases");
        List<Path> releaseFiles = repoFetcher.listFiles(releasesDir, "release-*.md");

        for (Path file : releaseFiles) {
            String fileName = file.getFileName().toString().replace(".md", "");
            Matcher m = RELEASE_VERSION_PATTERN.matcher(fileName);
            if (!m.find()) continue;

            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            double ver = major + minor / 100.0;
            if (ver < MIN_RELEASE_VERSION) continue;

            String versionLabel = major + "." + minor;
            String markdown = Files.readString(file);

            docs.add(new ConvertedDoc(versionLabel, fileName, "release-notes",
                    markdown, null, null, file));
        }

        return docs;
    }

    /**
     * Collect CVE advisory files from camel-website for later processing.
     * These are stored as ConvertedDocs with docType "cve" and processed
     * separately in buildCveChunks().
     */
    private List<ConvertedDoc> collectCveFiles(Path websiteRepo) throws IOException {
        List<ConvertedDoc> docs = new ArrayList<>();
        Path securityDir = websiteRepo.resolve("content/security");
        List<Path> cveFiles = repoFetcher.listFiles(securityDir, "CVE-*.md");

        for (Path file : cveFiles) {
            String fileName = file.getFileName().toString().replace(".md", "");
            String markdown = Files.readString(file);
            docs.add(new ConvertedDoc(null, fileName, "cve", markdown,
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
            if (!"cve".equals(doc.docType)) continue;

            try {
                CveAdvisory cve = CveParser.parse(doc.markdown);
                if (cve == null || cve.cveId() == null) {
                    System.out.printf("  WARN: Could not parse CVE from %s (skipping)%n",
                            doc.shortName);
                    continue;
                }

                // Enrich with NVD data (best-effort)
                cve = CveParser.enrichWithNvd(cve, cveCacheDir);

                String id = "apache-camel-cve-" + cve.cveId().toLowerCase();

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
                        null,       // erratumId (Red Hat concept)
                        null,       // advisoryType (Red Hat concept)
                        cve.severity(),
                        List.of(cve.cveId()),
                        cve.fixedVersions().isEmpty() ? null : cve.fixedVersions()
                ));
            } catch (Exception e) {
                System.out.printf("  WARN: Failed to process CVE %s: %s%n",
                        doc.shortName, e.getMessage());
            }
        }

        return chunks;
    }

    // ── Utility methods ─────────────────────────────────────────────────

    /**
     * Extract component name from AsciiDoc filename.
     * {@code kafka-component.adoc} -> {@code kafka}
     * {@code rest-dsl.adoc} -> {@code rest-dsl}
     */
    static String extractComponentFromFilename(String baseName) {
        if (baseName == null) return null;
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
        if (title == null) return null;
        String lower = title.toLowerCase().trim();

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
            return theMatcher.group(1).toLowerCase();
        }

        return null;
    }

    /**
     * Infer runtime(s) from document type.
     */
    static List<String> inferRuntimes(String docType) {
        if (docType == null) return null;
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
        if (text == null) return "unknown";
        return text.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    /**
     * Minimal HTML-to-Markdown conversion so SectionChunker can find headings.
     * Converts {@code <h1>...<h6>} tags to Markdown {@code #} headings,
     * strips remaining tags, and preserves text content.
     */
    static String htmlToSimpleMarkdown(String html) {
        if (html == null || html.isEmpty()) return "";

        String result = html;

        // Convert heading tags to Markdown headings
        for (int level = 1; level <= 6; level++) {
            String hashes = "#".repeat(level);
            result = result.replaceAll(
                    "(?i)<h" + level + "[^>]*>(.*?)</h" + level + ">",
                    "\n" + hashes + " $1\n");
        }

        // Convert <p> to double newline
        result = result.replaceAll("(?i)<p[^>]*>", "\n\n");
        result = result.replaceAll("(?i)</p>", "");

        // Convert <br> to newline
        result = result.replaceAll("(?i)<br\\s*/?>", "\n");

        // Convert <li> to "- "
        result = result.replaceAll("(?i)<li[^>]*>", "\n- ");

        // Convert <code> to backticks
        result = result.replaceAll("(?i)<code[^>]*>(.*?)</code>", "`$1`");

        // Convert <a> to [text](href)
        result = result.replaceAll(
                "(?i)<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");

        // Strip all remaining HTML tags
        result = result.replaceAll("<[^>]+>", "");

        // Decode common HTML entities
        result = result.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");

        // Collapse excessive blank lines
        result = result.replaceAll("\n{3,}", "\n\n");

        return result.trim();
    }
}
