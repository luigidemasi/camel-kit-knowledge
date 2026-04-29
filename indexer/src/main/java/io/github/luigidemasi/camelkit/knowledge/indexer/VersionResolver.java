package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Resolves active Apache Camel versions, their Git tags across three repos (camel, camel-spring-boot, camel-quarkus),
 * and the quarkus-to-camel version mapping.
 *
 * <p>
 * Workflow:
 * <ol>
 * <li>Parse camel-website release frontmatter to discover active versions</li>
 * <li>List remote tags from each repo via {@link GitRepoFetcher#listRemoteTags}</li>
 * <li>For quarkus: fetch pom.xml from GitHub to discover camel version mapping</li>
 * <li>For each active version, resolve the latest tag in each repo</li>
 * </ol>
 */
public class VersionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(VersionResolver.class);

    private static final String CAMEL_REPO = "https://github.com/apache/camel.git";
    private static final String QUARKUS_REPO = "https://github.com/apache/camel-quarkus.git";
    private static final String SPRING_REPO = "https://github.com/apache/camel-spring-boot.git";

    /** Regex to extract YAML frontmatter between --- markers. */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

    /** Regex to extract minor version from release filename (e.g., release-4.14.0.md -> 4.14). */
    private static final Pattern RELEASE_FILENAME_PATTERN = Pattern.compile("release-(\\d+\\.\\d+)\\.\\d+\\.md");

    // ── Records ─────────────────────────────────────────────────────────

    /** A parsed Camel release from the website frontmatter. */
    public record CamelRelease(
            String minor,       // e.g., "4.14"
            String version,     // e.g., "4.14.0"
            boolean lts,
            LocalDate eol,      // null for non-LTS
            LocalDate date) {
    }

    /** Mapping from a quarkus pom.xml: which camel minor version it targets. */
    public record QuarkusMapping(String camelMinor, String camelVersion) {
    }

    /** A fully resolved version with tags for all three repos. */
    public record ResolvedVersion(
            String label,
            String camelTag,
            String quarkusTag,
            String springBootTag) {
    }

    // ── 1. Parse camel-website release frontmatter ──────────────────────

    /**
     * Reads {@code content/releases/release-*.md} files (top-level only, not subdirectories) from the camel-website
     * repo and parses their YAML frontmatter.
     *
     * @param  websiteRepoDir root of the camel-website checkout
     * @return                list of parsed releases with {@code category: camel} only
     */
    public static List<CamelRelease> parseReleases(Path websiteRepoDir) throws IOException {
        Path releasesDir = websiteRepoDir.resolve("content/releases");
        if (!Files.isDirectory(releasesDir)) {
            return List.of();
        }

        List<CamelRelease> releases = new ArrayList<>();
        Yaml yaml = new Yaml();

        // Only top-level release files (not subdirectories like q/, sb/, etc.)
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(releasesDir, "release-*.md")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file))
                    continue;

                String content = Files.readString(file);
                Matcher m = FRONTMATTER_PATTERN.matcher(content);
                if (!m.find())
                    continue;

                Map<String, Object> frontmatter = yaml.load(m.group(1));
                if (frontmatter == null)
                    continue;

                // Only include category: camel
                String category = (String) frontmatter.get("category");
                if (!"camel".equals(category))
                    continue;

                // Extract minor version from filename
                String fileName = file.getFileName().toString();
                Matcher fm = RELEASE_FILENAME_PATTERN.matcher(fileName);
                if (!fm.matches())
                    continue;
                String minor = fm.group(1);

                // Full version from filename
                String version = fileName.replace("release-", "").replace(".md", "");

                // LTS?
                boolean lts = "lts".equals(frontmatter.get("kind"));

                // EOL date (only on LTS)
                LocalDate eol = null;
                Object eolObj = frontmatter.get("eol");
                if (eolObj != null) {
                    eol = toLocalDate(eolObj);
                }

                // Release date
                LocalDate date = null;
                Object dateObj = frontmatter.get("date");
                if (dateObj != null) {
                    date = toLocalDate(dateObj);
                }

                releases.add(new CamelRelease(minor, version, lts, eol, date));
            }
        }

        // Sort by version descending (latest first)
        releases.sort((a, b) -> compareVersions(b.minor(), a.minor()));
        return releases;
    }

    /**
     * Filters releases to active versions:
     * <ul>
     * <li>LTS releases with {@code eol > today}</li>
     * <li>The single latest non-LTS release (by minor version)</li>
     * </ul>
     */
    public static List<CamelRelease> activeVersions(Path websiteRepoDir, LocalDate today)
            throws IOException {
        List<CamelRelease> all = parseReleases(websiteRepoDir);

        // Deduplicate by minor (keep the one with the highest patch version)
        Map<String, CamelRelease> byMinor = new HashMap<>();
        for (CamelRelease r : all) {
            CamelRelease existing = byMinor.get(r.minor());
            if (existing == null || compareVersions(r.version(), existing.version()) > 0) {
                byMinor.put(r.minor(), r);
            }
        }

        List<CamelRelease> active = new ArrayList<>();

        // Active LTS: eol > today
        for (CamelRelease r : byMinor.values()) {
            if (r.lts() && r.eol() != null && r.eol().isAfter(today)) {
                active.add(r);
            }
        }

        // Latest non-LTS: the single non-LTS release with the highest minor version
        byMinor.values().stream()
                .filter(r -> !r.lts())
                .max((a, b) -> compareVersions(a.minor(), b.minor()))
                .ifPresent(active::add);

        // Sort by minor version ascending
        active.sort((a, b) -> compareVersions(a.minor(), b.minor()));
        return active;
    }

    // ── 2. Find latest tag per minor version ────────────────────────────

    /**
     * Finds the tag with the highest patch number for a given minor version.
     *
     * @param  allTags flat list of tag names (e.g., "camel-4.14.0", "camel-4.14.1")
     * @param  prefix  tag prefix (e.g., "camel-", "camel-spring-boot-", or "" for quarkus)
     * @param  minor   minor version (e.g., "4.14")
     * @return         the full tag name with the highest patch, or null if no match
     */
    public static String findLatestTag(List<String> allTags, String prefix, String minor) {
        String tagPrefix = prefix + minor + ".";

        return allTags.stream()
                .filter(tag -> tag.startsWith(tagPrefix))
                .filter(tag -> {
                    // Verify the suffix after the prefix+minor. is a number (patch version)
                    String suffix = tag.substring(tagPrefix.length());
                    return suffix.matches("\\d+");
                })
                .max((a, b) -> {
                    int patchA = Integer.parseInt(a.substring(tagPrefix.length()));
                    int patchB = Integer.parseInt(b.substring(tagPrefix.length()));
                    return Integer.compare(patchA, patchB);
                })
                .orElse(null);
    }

    // ── 3. Parse quarkus pom.xml for camel version mapping ──────────────

    /**
     * Extracts {@code camel.major.minor} and {@code camel.version} properties from a camel-quarkus pom.xml. Performs
     * Maven property interpolation (replaces {@code ${camel.major.minor}} in {@code camel.version}).
     *
     * @param  pomXml the raw XML content of the pom.xml
     * @return        the mapping, or null if properties are missing
     */
    public static QuarkusMapping parseQuarkusPom(String pomXml) {
        String camelMinor = extractXmlProperty(pomXml, "camel.major.minor");
        String camelVersion = extractXmlProperty(pomXml, "camel.version");

        if (camelMinor == null || camelVersion == null) {
            return null;
        }

        // Maven property interpolation: replace ${camel.major.minor} with actual value
        camelVersion = camelVersion.replace("${camel.major.minor}", camelMinor);

        return new QuarkusMapping(camelMinor, camelVersion);
    }

    // ── 4. Top-level orchestrator ───────────────────────────────────────

    /**
     * Resolves all active versions with their latest tags across the three repos.
     *
     * <ol>
     * <li>Discovers active Camel minors from the website repo</li>
     * <li>Lists remote tags for camel, spring-boot, and quarkus repos</li>
     * <li>For quarkus: fetches pom.xml from GitHub to build camel-minor-to-quarkus-minor mapping</li>
     * <li>Resolves the latest tag per minor in each repo</li>
     * </ol>
     *
     * @param  websiteRepoDir root of the camel-website checkout
     * @return                list of resolved versions with tags
     */
    public static List<ResolvedVersion> resolveVersions(Path websiteRepoDir)
            throws IOException, InterruptedException {

        // Step 1: Active camel minors
        List<CamelRelease> active = activeVersions(websiteRepoDir, LocalDate.now(ZoneId.systemDefault()));
        LOG.info("Active versions: {}",
                active.stream().map(CamelRelease::minor).toList());

        // Step 2: List remote tags for all three repos
        LOG.info("Listing remote tags...");
        List<String> camelTags = GitRepoFetcher.listRemoteTags(CAMEL_REPO);
        List<String> springTags = GitRepoFetcher.listRemoteTags(SPRING_REPO);
        List<String> quarkusTags = GitRepoFetcher.listRemoteTags(QUARKUS_REPO);
        LOG.info("  camel: {} tags, spring-boot: {} tags, quarkus: {} tags",
                camelTags.size(), springTags.size(), quarkusTags.size());

        // Step 3: Build quarkus minor -> camel minor reverse mapping
        // Find unique quarkus minors from tags
        Set<String> quarkusMinors = new LinkedHashSet<>();
        for (String tag : quarkusTags) {
            if (tag.matches("\\d+\\.\\d+\\.\\d+")) {
                quarkusMinors.add(extractMinor(tag));
            }
        }

        // Fetch pom.xml for the latest tag of each quarkus minor to get the mapping
        Map<String, String> camelMinorToQuarkusMinor = new HashMap<>();
        HttpClient httpClient = HttpClient.newHttpClient();

        for (String qMinor : quarkusMinors) {
            String latestQTag = findLatestTag(quarkusTags, "", qMinor);
            if (latestQTag == null)
                continue;

            try {
                String pomUrl = String.format(Locale.ROOT,
                        "https://raw.githubusercontent.com/apache/camel-quarkus/%s/pom.xml",
                        latestQTag);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(pomUrl))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    QuarkusMapping mapping = parseQuarkusPom(response.body());
                    if (mapping != null) {
                        camelMinorToQuarkusMinor.put(mapping.camelMinor(), qMinor);
                        LOG.info("  Quarkus {} -> Camel {}", qMinor, mapping.camelMinor());
                    }
                }
            } catch (Exception e) {
                LOG.warn("  Failed to fetch quarkus pom for {}: {}", latestQTag, e.getMessage());
            }
        }

        // Step 4: Resolve tags for each active version
        List<ResolvedVersion> resolved = new ArrayList<>();

        for (CamelRelease release : active) {
            String minor = release.minor();

            String camelTag = findLatestTag(camelTags, "camel-", minor);
            String springTag = findLatestTag(springTags, "camel-spring-boot-", minor);

            // Quarkus: look up which quarkus minor maps to this camel minor
            String quarkusTag = null;
            String qMinor = camelMinorToQuarkusMinor.get(minor);
            if (qMinor != null) {
                quarkusTag = findLatestTag(quarkusTags, "", qMinor);
            }

            resolved.add(new ResolvedVersion(minor, camelTag, quarkusTag, springTag));
            LOG.info("  {} -> camel={}, quarkus={}, spring-boot={}", minor, camelTag, quarkusTag, springTag);
        }

        return resolved;
    }

    // ── Utility methods ─────────────────────────────────────────────────

    /**
     * Compares two dot-separated version strings segment by segment as integers.
     *
     * @return negative if a < b, positive if a > b, zero if equal
     */
    public static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);

        for (int i = 0; i < len; i++) {
            int segA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int segB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (segA != segB)
                return Integer.compare(segA, segB);
        }
        return 0;
    }

    /**
     * Extracts the minor version (first two segments) from a version string. Example: "4.14.6" -> "4.14"
     */
    public static String extractMinor(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2)
            return version;
        return parts[0] + "." + parts[1];
    }

    /**
     * Converts a SnakeYAML-parsed date object (java.util.Date) to LocalDate. Also handles String dates (ISO format).
     */
    private static LocalDate toLocalDate(Object obj) {
        if (obj instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }
        if (obj instanceof String s) {
            return LocalDate.parse(s);
        }
        return null;
    }

    /**
     * Extracts a Maven property value from raw XML. Looks for {@code <propertyName>value</propertyName>} inside
     * {@code <properties>}.
     */
    private static String extractXmlProperty(String xml, String propertyName) {
        // Match <propertyName>value</propertyName> (property names may contain dots)
        String escaped = Pattern.quote(propertyName);
        Pattern pattern = Pattern.compile("<" + escaped + ">([^<]+)</" + escaped + ">");
        Matcher m = pattern.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
}
