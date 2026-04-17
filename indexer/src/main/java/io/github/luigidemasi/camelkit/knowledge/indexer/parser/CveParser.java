package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Apache Camel CVE advisories from Markdown files with YAML frontmatter.
 * Optionally enriches with NVD data (CVSS score, CWE classification).
 */
public class CveParser {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

    private static final Pattern JIRA_PATTERN =
            Pattern.compile("\\b(CAMEL-\\d+)\\b");

    private static final Pattern VERSION_RANGE_PATTERN =
            Pattern.compile("[Ff]rom\\s+([\\d.]+)\\s+before\\s+([\\d.]+)");

    private static final Pattern CAMEL_COMPONENT_PATTERN =
            Pattern.compile("\\bcamel-([a-z][a-z0-9-]+)");

    private static final Pattern THE_COMPONENT_PATTERN =
            Pattern.compile("\\b[Cc]amel\\s+([A-Z][a-zA-Z]+)\\s+(?:component|extension)", Pattern.CASE_INSENSITIVE);

    private static final String NVD_API_URL =
            "https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=";

    public record CveAdvisory(
            String cveId,
            String severity,
            String summary,
            String description,
            String affected,
            List<String> fixedVersions,
            List<String> affectedVersions,
            String affectedComponent,
            List<String> jiraIds,
            String publishedDate,
            String mitigation,
            String body,
            // NVD enrichment (nullable)
            String cvssScore,
            String cvssVector,
            String cweId
    ) {}

    /**
     * Parse a CVE advisory from Markdown content with YAML frontmatter.
     */
    public static CveAdvisory parse(String markdownContent) {
        Matcher fmMatcher = FRONTMATTER_PATTERN.matcher(markdownContent);
        if (!fmMatcher.find()) {
            return null;
        }

        String frontmatter = fmMatcher.group(1);
        String body = markdownContent.substring(fmMatcher.end()).trim();

        String cveId = extractField(frontmatter, "cve");
        String severity = extractField(frontmatter, "severity");
        String summary = extractField(frontmatter, "summary");
        String description = extractField(frontmatter, "description");
        String affected = extractField(frontmatter, "affected");
        String fixed = extractField(frontmatter, "fixed");
        String mitigation = extractField(frontmatter, "mitigation");
        String dateStr = extractField(frontmatter, "date");

        // Parse fixed versions (comma-separated, with optional "and")
        List<String> fixedVersions = new ArrayList<>();
        if (fixed != null) {
            for (String v : fixed.replace(" and ", ", ").split(",\\s*")) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) fixedVersions.add(trimmed);
            }
        }

        // Parse affected version ranges
        List<String> affectedVersions = parseAffectedVersions(affected);

        // Extract component from summary + description
        String component = extractComponent(
                (summary != null ? summary : "") + " " + (description != null ? description : ""));

        // Extract JIRA IDs from body
        List<String> jiraIds = new ArrayList<>();
        Matcher jiraMatcher = JIRA_PATTERN.matcher(body);
        while (jiraMatcher.find()) {
            jiraIds.add(jiraMatcher.group(1));
        }

        // Parse date (extract YYYY-MM-DD from ISO 8601)
        String publishedDate = dateStr != null && dateStr.length() >= 10
                ? dateStr.substring(0, 10) : null;

        return new CveAdvisory(cveId, severity, summary, description, affected,
                fixedVersions, affectedVersions, component, jiraIds, publishedDate,
                mitigation, body, null, null, null);
    }

    /**
     * Parse a CVE file from disk.
     */
    public static CveAdvisory parseFile(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /**
     * Enrich a CveAdvisory with NVD data. Best-effort — returns original if NVD unavailable.
     */
    public static CveAdvisory enrichWithNvd(CveAdvisory cve, Path cacheDir) {
        if (cve == null || cve.cveId() == null) return cve;

        Path cacheFile = cacheDir.resolve(cve.cveId() + ".json");

        String json;
        if (Files.exists(cacheFile)) {
            try {
                json = Files.readString(cacheFile);
            } catch (IOException e) {
                return cve;
            }
        } else {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(NVD_API_URL + cve.cveId()))
                        .header("Accept", "application/json")
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return cve;
                json = response.body();
                Files.createDirectories(cacheDir);
                Files.writeString(cacheFile, json);
            } catch (Exception e) {
                System.out.printf("  WARN: NVD lookup failed for %s: %s%n", cve.cveId(), e.getMessage());
                return cve;
            }
        }

        // Extract CVSS 3.1 data and CWE from NVD JSON
        String cvssScore = extractJsonValue(json, "baseScore");
        String cvssVector = extractJsonValue(json, "vectorString");
        String cweId = extractCweFromNvd(json);

        return new CveAdvisory(cve.cveId(), cve.severity(), cve.summary(), cve.description(),
                cve.affected(), cve.fixedVersions(), cve.affectedVersions(), cve.affectedComponent(),
                cve.jiraIds(), cve.publishedDate(), cve.mitigation(), cve.body(),
                cvssScore, cvssVector, cweId);
    }

    /**
     * Extract component name from CVE summary/description text.
     */
    public static String extractComponent(String text) {
        if (text == null) return null;

        // Pattern 1: "camel-xyz"
        Matcher m1 = CAMEL_COMPONENT_PATTERN.matcher(text.toLowerCase());
        if (m1.find()) return m1.group(1);

        // Pattern 2: "Camel XYZ component/extension"
        Matcher m2 = THE_COMPONENT_PATTERN.matcher(text);
        if (m2.find()) return m2.group(1).toLowerCase();

        return null;
    }

    /**
     * Parse "From X.Y.Z before A.B.C" ranges into "X.Y.Z-A.B.C" strings.
     */
    public static List<String> parseAffectedVersions(String affected) {
        List<String> ranges = new ArrayList<>();
        if (affected == null) return ranges;
        Matcher m = VERSION_RANGE_PATTERN.matcher(affected);
        while (m.find()) {
            ranges.add(m.group(1) + "-" + m.group(2));
        }
        return ranges;
    }

    private static String extractField(String frontmatter, String fieldName) {
        Pattern p = Pattern.compile("^" + fieldName + ":\\s*\"?(.*?)\"?\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(frontmatter);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String extractJsonValue(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^,\"\\}]+)\"?");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String extractCweFromNvd(String json) {
        Pattern p = Pattern.compile("\"value\"\\s*:\\s*\"(CWE-\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
