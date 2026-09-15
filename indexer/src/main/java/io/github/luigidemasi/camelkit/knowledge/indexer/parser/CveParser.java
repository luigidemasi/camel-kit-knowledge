package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    // CIRCL's FKIE feed preserves the NVD record format; the unprefixed CVE endpoint serves CVE JSON 5 instead.
    private static final String CIRCL_API_URL = "https://vulnerability.circl.lu/api/vulnerability/fkie_";

    private static final EnrichmentClient ENRICHMENT_CLIENT
            = new EnrichmentClient(NVD_API_URL, CIRCL_API_URL, Duration.ofSeconds(15), Duration.ofMillis(6100));

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
     * Enrich with cached NVD data, then NVD directly, then CIRCL's FKIE NVD mirror. Best-effort — returns the original
     * advisory if neither service provides a matching record.
     */
    public static CveAdvisory enrichWithNvd(CveAdvisory cve, Path cacheDir) {
        return enrichWithNvd(cve, cacheDir, ENRICHMENT_CLIENT);
    }

    static CveAdvisory enrichWithNvd(CveAdvisory cve, Path cacheDir, EnrichmentClient client) {
        if (cve == null || cve.cveId() == null || !cve.cveId().matches("(?i)CVE-\\d{4}-\\d{4,}"))
            return cve;

        Path cacheFile = cacheDir.resolve(cve.cveId() + ".json");
        String json = null;
        if (Files.exists(cacheFile)) {
            try {
                json = Files.readString(cacheFile);
                nvdRecord(json, cve.cveId(), false);
            } catch (IOException | RuntimeException e) {
                LOG.warn("  Ignoring unreadable CVE cache for {}: {}", cve.cveId(), e.getMessage());
                json = null;
            }
        }
        if (json == null) {
            try {
                json = client.lookup(cve.cveId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cve;
            }
            if (json == null) {
                LOG.warn("  No NVD enrichment available from NVD or CIRCL for {} — retaining Apache advisory",
                        cve.cveId());
                return cve;
            }
            try {
                Files.createDirectories(cacheDir);
                Files.writeString(cacheFile, json);
            } catch (IOException e) {
                LOG.warn("  Could not cache CVE enrichment for {}: {}", cve.cveId(), e.getMessage());
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

    private static JSONObject nvdRecord(String json, String cveId, boolean mirror) {
        JSONObject response = new JSONObject(json);
        JSONObject record
                = mirror ? response : response.getJSONArray("vulnerabilities").getJSONObject(0).getJSONObject("cve");
        if (!cveId.equalsIgnoreCase(record.getString("id"))) {
            throw new IllegalArgumentException("CVE response does not match " + cveId);
        }
        return record;
    }

    /** One bounded request per service, with shared pacing and server-requested cooldowns. */
    static final class EnrichmentClient {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final String nvdUrl;
        private final String circlUrl;
        private final Duration timeout;
        private final Duration interval;
        private final Map<String, Instant> retryAfter = new HashMap<>();
        private long nextRequestNanos = System.nanoTime();

        EnrichmentClient(String nvdUrl, String circlUrl, Duration timeout, Duration interval) {
            this.nvdUrl = nvdUrl;
            this.circlUrl = circlUrl;
            this.timeout = timeout;
            this.interval = interval;
        }

        synchronized String lookup(String cveId) throws InterruptedException {
            String json = fetch(cveId, nvdUrl, false);
            return json != null ? json : fetch(cveId, circlUrl, true);
        }

        private String fetch(String cveId, String baseUrl, boolean mirror) throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (Instant.now().isBefore(retryAfter.getOrDefault(baseUrl, Instant.EPOCH))) {
                return null;
            }
            String source = mirror ? "CIRCL/FKIE NVD" : "NVD";
            String url = baseUrl + (mirror ? cveId.toLowerCase(Locale.ROOT) : cveId);
            TimeUnit.NANOSECONDS.sleep(Math.max(0, nextRequestNanos - System.nanoTime()));
            nextRequestNanos = System.nanoTime() + interval.toNanos();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .header("User-Agent",
                                "camel-kit-knowledge (+https://github.com/luigidemasi/camel-kit-knowledge)")
                        .build();
                var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> response;
                try {
                    // Bound the entire response, including a stalled body after successful headers.
                    response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } finally {
                    pending.cancel(true);
                }
                if (response.statusCode() != 200) {
                    if (response.statusCode() == 429 || response.statusCode() == 503) {
                        retryAfter.put(baseUrl, retryAt(response.headers().firstValue("Retry-After").orElse("30")));
                    }
                    LOG.warn("  {} returned HTTP {} for {}", source, response.statusCode(), cveId);
                    return null;
                }
                JSONObject record = nvdRecord(response.body(), cveId, mirror);
                LOG.debug("  Enriched {} from {}", cveId, source);
                return new JSONObject()
                        .put("vulnerabilities", new JSONArray().put(new JSONObject().put("cve", record)))
                        .put("enrichmentSource", source)
                        .put("enrichmentUrl", url)
                        .put("enrichmentFetchedAt", Instant.now().toString())
                        .toString();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.warn("  {} lookup failed for {}: {}", source, cveId, e.getMessage());
                return null;
            }
        }

        private static Instant retryAt(String value) {
            try {
                return Instant.now().plusSeconds(Math.max(0, Long.parseLong(value)));
            } catch (RuntimeException e) {
                try {
                    return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                } catch (RuntimeException ignored) {
                    return Instant.now().plusSeconds(30);
                }
            }
        }
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
        JSONObject record = new JSONObject(json).getJSONArray("vulnerabilities").getJSONObject(0).getJSONObject("cve");
        JSONArray weaknesses = record.optJSONArray("weaknesses");
        if (weaknesses != null) {
            for (int i = 0; i < weaknesses.length(); i++) {
                JSONObject weakness = weaknesses.optJSONObject(i);
                if (weakness == null)
                    continue;
                JSONArray descriptions = weakness.optJSONArray("description");
                if (descriptions == null)
                    continue;
                for (int j = 0; j < descriptions.length(); j++) {
                    JSONObject description = descriptions.optJSONObject(j);
                    if (description == null)
                        continue;
                    String value = description.optString("value");
                    if (value.matches("CWE-\\d+"))
                        return value;
                }
            }
        }
        return null;
    }
}
