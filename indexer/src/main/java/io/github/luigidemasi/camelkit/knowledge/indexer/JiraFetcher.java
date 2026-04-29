package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches JIRA issue details from Apache JIRA. Results are cached as JSON files to avoid re-fetching on every indexer
 * run.
 *
 * <p>
 * Routing by prefix:
 * <ul>
 * <li>CAMEL-* -> issues.apache.org (public, no auth)</li>
 * </ul>
 */
public class JiraFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(JiraFetcher.class);

    private static final String APACHE_JIRA_URL = "https://issues.apache.org/jira/rest/api/2/issue/";
    private static final String FIELDS
            = "summary,description,fixVersions,components,status,priority,labels,issuetype,resolution";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 10;
    private static final long INITIAL_BACKOFF_MS = 2000;
    private static final long MAX_BACKOFF_MS = 60_000;

    private static final Set<String> KNOWN_PREFIXES = Set.of("CAMEL");

    private final HttpClient httpClient;
    private final Path cacheDir;

    public JiraFetcher(Path cacheDir) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.cacheDir = cacheDir;
    }

    /**
     * Parsed JIRA issue with the fields we care about.
     */
    public record JiraIssue(
            String key,
            String summary,
            String description,
            List<String> fixVersions,
            List<String> components,
            String status,
            String priority,
            String issueType,
            String resolution) {
        /**
         * Format the issue as enriched content for indexing.
         */
        public String toEnrichedContent() {
            StringBuilder sb = new StringBuilder();
            sb.append(key).append(": ").append(summary);
            if (issueType != null) {
                sb.append("\nType: ").append(issueType);
            }
            if (status != null) {
                sb.append("\nStatus: ").append(status);
            }
            if (resolution != null) {
                sb.append("\nResolution: ").append(resolution);
            }
            if (priority != null) {
                sb.append("\nPriority: ").append(priority);
            }
            if (!fixVersions.isEmpty()) {
                sb.append("\nFix Versions: ").append(String.join(", ", fixVersions));
            }
            if (!components.isEmpty()) {
                sb.append("\nComponents: ").append(String.join(", ", components));
            }
            if (description != null && !description.isBlank()) {
                // Truncate very long descriptions to keep chunks manageable
                String desc = description.length() > 2000
                        ? description.substring(0, 2000) + "..."
                        : description;
                sb.append("\n\n").append(desc);
            }
            return sb.toString();
        }
    }

    /**
     * Fetch a JIRA issue, using cache if available. Returns null if the issue cannot be fetched (network error, auth
     * issue, etc.).
     */
    public JiraIssue fetch(String jiraId) {
        try {
            Path cacheFile = cacheDir.resolve(jiraId + ".json");
            String json;

            if (Files.exists(cacheFile) && Files.size(cacheFile) > 0) {
                json = Files.readString(cacheFile);
            } else {
                json = downloadIssue(jiraId);
                if (json == null)
                    return null;
                Files.writeString(cacheFile, json);
            }

            return parseIssue(jiraId, json);
        } catch (Exception e) {
            LOG.warn("  Failed to fetch JIRA {}: {}", jiraId, e.getMessage());
            return null;
        }
    }

    private String downloadIssue(String jiraId) {
        String prefix = jiraId.contains("-") ? jiraId.substring(0, jiraId.indexOf('-')) : null;
        if (prefix == null)
            return null;

        if (!KNOWN_PREFIXES.contains(prefix)) {
            LOG.warn("  Unknown JIRA prefix: {} (skipping)", prefix);
            return null;
        }

        String url = APACHE_JIRA_URL + jiraId + "?fields=" + FIELDS;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response.body();
                } else if (response.statusCode() == 429) {
                    if (attempt < MAX_RETRIES) {
                        long backoff = Math.min(INITIAL_BACKOFF_MS * (1L << attempt), MAX_BACKOFF_MS);
                        // Honor Retry-After header if present
                        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                        if (retryAfter != null) {
                            try {
                                backoff = Math.min(Long.parseLong(retryAfter) * 1000, MAX_BACKOFF_MS);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        LOG.warn("  JIRA {} rate-limited (429), retrying in {}s (attempt {}/{})",
                                jiraId, backoff / 1000, attempt + 1, MAX_RETRIES);
                        Thread.sleep(backoff);
                        continue;
                    }
                    LOG.warn("  JIRA {} rate-limited (429), giving up after {} retries",
                            jiraId, MAX_RETRIES);
                    return null;
                } else if (response.statusCode() == 404) {
                    LOG.warn("  JIRA {} not found (404)", jiraId);
                    return null;
                } else {
                    LOG.warn("  JIRA {} returned HTTP {}", jiraId, response.statusCode());
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException e) {
                LOG.warn("  Failed to download JIRA {}: {}", jiraId, e.getMessage());
                return null;
            }
        }
        return null;
    }

    private JiraIssue parseIssue(String jiraId, String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject fields = root.getJSONObject("fields");

            String summary = fields.optString("summary", "");
            String description = fields.isNull("description") ? null : fields.optString("description", null);

            List<String> fixVersions = new ArrayList<>();
            JSONArray fv = fields.optJSONArray("fixVersions");
            if (fv != null) {
                for (int i = 0; i < fv.length(); i++) {
                    fixVersions.add(fv.getJSONObject(i).optString("name", ""));
                }
            }

            List<String> components = new ArrayList<>();
            JSONArray comps = fields.optJSONArray("components");
            if (comps != null) {
                for (int i = 0; i < comps.length(); i++) {
                    components.add(comps.getJSONObject(i).optString("name", ""));
                }
            }

            String status = null;
            if (!fields.isNull("status")) {
                status = fields.getJSONObject("status").optString("name", null);
            }

            String priority = null;
            if (!fields.isNull("priority")) {
                priority = fields.getJSONObject("priority").optString("name", null);
            }

            String issueType = null;
            if (!fields.isNull("issuetype")) {
                issueType = fields.getJSONObject("issuetype").optString("name", null);
            }

            String resolution = null;
            if (!fields.isNull("resolution")) {
                resolution = fields.getJSONObject("resolution").optString("name", null);
            }

            return new JiraIssue(
                    jiraId, summary, description, fixVersions, components,
                    status, priority, issueType, resolution);
        } catch (Exception e) {
            LOG.warn("  Failed to parse JIRA JSON for {}: {}", jiraId, e.getMessage());
            return null;
        }
    }

}
