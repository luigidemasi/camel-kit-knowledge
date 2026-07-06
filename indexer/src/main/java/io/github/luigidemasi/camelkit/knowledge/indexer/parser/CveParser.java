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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses Apache Camel CVE advisories from Markdown files with YAML frontmatter. Optionally enriches with NVD data (CVSS
 * score, CWE classification).
 */
public class CveParser {

    private static final Logger LOG = LoggerFactory.getLogger(CveParser.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

    private static final Pattern JIRA_PATTERN = Pattern.compile("\\b(CAMEL-\\d+)\\b");

    private static final Pattern VERSION_RANGE_PATTERN = Pattern.compile("[Ff]rom\\s+([\\d.]+)\\s+before\\s+([\\d.]+)");

    private static final Pattern CAMEL_COMPONENT_PATTERN = Pattern.compile("\\bcamel-([a-z][a-z0-9-]+)");

    private static final Pattern THE_COMPONENT_PATTERN
            = Pattern.compile("\\b[Cc]amel\\s+([A-Z][a-zA-Z]+)\\s+(?:component|extension)", Pattern.CASE_INSENSITIVE);

    private static final String NVD_API_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=";

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
            String cweId) {
    }

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

        // YAML parse handles multi-line values (advisory descriptions routinely span many lines —
        // a line-based regex truncates them mid-sentence); regex is the fallback for invalid YAML.
        Map<String, Object> yaml = parseYamlFrontmatter(frontmatter);

        String cveId = field(yaml, frontmatter, "cve");
        String severity = field(yaml, frontmatter, "severity");
        String summary = field(yaml, frontmatter, "summary");
        String description = field(yaml, frontmatter, "description");
        String affected = field(yaml, frontmatter, "affected");
        String fixed = field(yaml, frontmatter, "fixed");
        String mitigation = field(yaml, frontmatter, "mitigation");
        // date is a typed YAML timestamp — the regex form preserves the ISO string
        String dateStr = extractField(frontmatter, "date");

        // Parse fixed versions (comma-separated, with optional "and")
        List<String> fixedVersions = new ArrayList<>();
        if (fixed != null) {
            for (String v : fixed.replace(" and ", ", ").split(",\\s*")) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty())
                    fixedVersions.add(trimmed);
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

        return new CveAdvisory(
                cveId, severity, summary, description, affected,
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
        if (cve == null || cve.cveId() == null)
            return cve;

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
                if (response.statusCode() != 200) {
                    // NVD rate-limits unauthenticated clients (~5 req/30s) — without this log,
                    // missing enrichment on a fresh cache is invisible
                    LOG.warn("  NVD returned HTTP {} for {} — skipping enrichment", response.statusCode(),
                            cve.cveId());
                    return cve;
                }
                json = response.body();
                Files.createDirectories(cacheDir);
                Files.writeString(cacheFile, json);
            } catch (Exception e) {
                LOG.warn("  NVD lookup failed for {}: {}", cve.cveId(), e.getMessage());
                return cve;
            }
        }

        // Extract CVSS data (v3.1 preferred) and CWE from NVD JSON
        String[] cvss = extractCvss(json);
        String cvssScore = cvss[0];
        String cvssVector = cvss[1];
        String cweId = extractCweFromNvd(json);

        return new CveAdvisory(
                cve.cveId(), cve.severity(), cve.summary(), cve.description(),
                cve.affected(), cve.fixedVersions(), cve.affectedVersions(), cve.affectedComponent(),
                cve.jiraIds(), cve.publishedDate(), cve.mitigation(), cve.body(),
                cvssScore, cvssVector, cweId);
    }

    /**
     * Extract component name from CVE summary/description text.
     */
    public static String extractComponent(String text) {
        if (text == null)
            return null;

        // Pattern 1: "camel-xyz"
        Matcher m1 = CAMEL_COMPONENT_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        if (m1.find())
            return m1.group(1);

        // Pattern 2: "Camel XYZ component/extension"
        Matcher m2 = THE_COMPONENT_PATTERN.matcher(text);
        if (m2.find())
            return m2.group(1).toLowerCase(Locale.ROOT);

        return null;
    }

    /**
     * Parse "From X.Y.Z before A.B.C" ranges into "X.Y.Z-A.B.C" strings.
     */
    public static List<String> parseAffectedVersions(String affected) {
        List<String> ranges = new ArrayList<>();
        if (affected == null)
            return ranges;
        Matcher m = VERSION_RANGE_PATTERN.matcher(affected);
        while (m.find()) {
            ranges.add(m.group(1) + "-" + m.group(2));
        }
        return ranges;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYamlFrontmatter(String frontmatter) {
        try {
            Object parsed = new Yaml().load(frontmatter);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            LOG.warn("  Frontmatter is not valid YAML, falling back to line-based extraction: {}", e.getMessage());
            return null;
        }
    }

    /** YAML value when available and string-typed, line-based regex otherwise. */
    private static String field(Map<String, Object> yaml, String frontmatter, String fieldName) {
        if (yaml != null && yaml.get(fieldName) instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return extractField(frontmatter, fieldName);
    }

    private static String extractField(String frontmatter, String fieldName) {
        Pattern p = Pattern.compile("^" + fieldName + ":\\s*\"?(.*?)\"?\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(frontmatter);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Extracts [baseScore, vectorString] from an NVD 2.0 response, preferring CVSS v3.1 explicitly — the response
     * carries multiple metric blocks (v4.0/v3.1/v3.0/v2) in unspecified order, so grabbing the first "baseScore" in the
     * raw JSON can silently return a v2 or v4 score.
     */
    private static String[] extractCvss(String json) {
        try {
            JSONArray vulns = new JSONObject(json).optJSONArray("vulnerabilities");
            if (vulns == null || vulns.isEmpty()) {
                return new String[]{null, null};
            }
            JSONObject metrics = vulns.getJSONObject(0).getJSONObject("cve").optJSONObject("metrics");
            if (metrics == null) {
                return new String[]{null, null};
            }
            for (String key : new String[]{"cvssMetricV31", "cvssMetricV30", "cvssMetricV40", "cvssMetricV2"}) {
                JSONArray metricArray = metrics.optJSONArray(key);
                if (metricArray != null && !metricArray.isEmpty()) {
                    JSONObject cvssData = metricArray.getJSONObject(0).getJSONObject("cvssData");
                    String score = cvssData.has("baseScore") ? String.valueOf(cvssData.get("baseScore")) : null;
                    return new String[]{score, cvssData.optString("vectorString", null)};
                }
            }
        } catch (Exception e) {
            LOG.warn("  Failed to parse NVD CVSS data: {}", e.getMessage());
        }
        return new String[]{null, null};
    }

    private static String extractCweFromNvd(String json) {
        Pattern p = Pattern.compile("\"value\"\\s*:\\s*\"(CWE-\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
