package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.luigidemasi.camelkit.knowledge.indexer.domain.DocumentChunk;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads the Apache Camel Catalog JAR from Maven Central and extracts component/EIP metadata as indexable
 * {@link DocumentChunk}s.
 *
 * <p>
 * Each component JSON is converted to a human-readable text representation listing component properties, endpoint
 * properties, and message headers. Each EIP model JSON is similarly converted with its properties.
 */
public class CamelCatalogIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(CamelCatalogIndexer.class);

    private static final String MAVEN_CENTRAL_URL
            = "https://repo1.maven.org/maven2/org/apache/camel/camel-catalog/%s/camel-catalog-%s.jar";

    private static final String COMPONENTS_PREFIX = "org/apache/camel/catalog/components/";
    private static final String MODELS_PREFIX = "org/apache/camel/catalog/models/";

    /** Pattern to extract version from a camelTag like "camel-4.14.6". */
    private static final Pattern CAMEL_TAG_PATTERN = Pattern.compile("^camel-(.+)$");

    private final Path cacheDir;

    /**
     * @param cacheDir directory where downloaded catalog JARs are cached
     */
    public CamelCatalogIndexer(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Downloads the camel-catalog JAR (if not cached), extracts component and EIP JSON files, and converts them to
     * {@link DocumentChunk}s.
     *
     * @param  camelTag     the Git tag, e.g. "camel-4.14.6"
     * @param  versionLabel the minor version label, e.g. "4.14"
     * @return              list of document chunks for all components and EIPs
     */
    public List<DocumentChunk> indexCatalog(String camelTag, String versionLabel)
            throws IOException, InterruptedException {

        String version = extractVersion(camelTag);
        Path jarPath = downloadCatalogJar(version);

        List<DocumentChunk> chunks = new ArrayList<>();
        int componentCount = 0;
        int eipCount = 0;

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(COMPONENTS_PREFIX) && entryName.endsWith(".json")) {
                    String json = readEntry(jarFile, entry);
                    DocumentChunk chunk = buildComponentChunk(json, versionLabel, version);
                    if (chunk != null) {
                        chunks.add(chunk);
                        componentCount++;
                    }
                } else if (entryName.startsWith(MODELS_PREFIX) && entryName.endsWith(".json")) {
                    String json = readEntry(jarFile, entry);
                    DocumentChunk chunk = buildEipChunk(json, versionLabel, version);
                    if (chunk != null) {
                        chunks.add(chunk);
                        eipCount++;
                    }
                }
            }
        }

        LOG.info("Extracted {} components, {} EIPs from catalog", componentCount, eipCount);
        return chunks;
    }

    // ── Package-private static methods for testability ──────────────────

    /**
     * Builds a {@link DocumentChunk} from a component catalog JSON string.
     *
     * @param  json         the raw JSON content of a component descriptor
     * @param  versionLabel the minor version label, e.g. "4.18"
     * @param  fullVersion  the full version, e.g. "4.18.2"
     * @return              the document chunk, or null if the JSON is malformed
     */
    static DocumentChunk buildComponentChunk(String json, String versionLabel, String fullVersion) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject component = root.getJSONObject("component");
            String name = component.getString("name");
            String title = component.getString("title");
            String description = component.optString("description", "");

            StringBuilder content = new StringBuilder();
            content.append(title).append(" Component Options\n\n");
            content.append(description).append("\n");

            // Component Properties
            JSONObject componentProps = root.optJSONObject("componentProperties");
            if (componentProps != null && !componentProps.isEmpty()) {
                content.append("\nComponent Properties:\n");
                appendProperties(content, componentProps);
            }

            // Endpoint Properties
            JSONObject endpointProps = root.optJSONObject("properties");
            if (endpointProps != null && !endpointProps.isEmpty()) {
                content.append("\nEndpoint Properties:\n");
                appendProperties(content, endpointProps);
            }

            // Message Headers
            JSONObject headers = root.optJSONObject("headers");
            if (headers != null && !headers.isEmpty()) {
                content.append("\nMessage Headers:\n");
                for (String headerName : headers.keySet()) {
                    JSONObject header = headers.getJSONObject(headerName);
                    String headerDesc = header.optString("description", "");
                    if (headerDesc.isEmpty())
                        continue;
                    content.append("- ").append(headerName).append(": ").append(headerDesc).append("\n");
                }
            }

            String id = "apache-camel-" + versionLabel + "-catalog-component-" + name;

            return new DocumentChunk(
                    id, "apache-camel", "catalog-component",
                    versionLabel, null, name,
                    title + " Component Options", content.toString().trim());
        } catch (Exception e) {
            LOG.warn("  Failed to parse component JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds a {@link DocumentChunk} from an EIP model catalog JSON string.
     *
     * @param  json         the raw JSON content of an EIP model descriptor
     * @param  versionLabel the minor version label, e.g. "4.18"
     * @param  fullVersion  the full version, e.g. "4.18.2"
     * @return              the document chunk, or null if the JSON is malformed
     */
    static DocumentChunk buildEipChunk(String json, String versionLabel, String fullVersion) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject model = root.getJSONObject("model");
            String name = model.getString("name");
            String title = model.getString("title");
            String description = model.optString("description", "");

            StringBuilder content = new StringBuilder();
            content.append(title).append(" EIP Options\n\n");
            content.append(description).append("\n");

            // Properties
            JSONObject properties = root.optJSONObject("properties");
            if (properties != null && !properties.isEmpty()) {
                content.append("\nProperties:\n");
                appendProperties(content, properties);
            }

            String id = "apache-camel-" + versionLabel + "-catalog-eip-" + name;

            return new DocumentChunk(
                    id, "apache-camel", "catalog-eip",
                    versionLabel, null, name,
                    title + " EIP Options", content.toString().trim());
        } catch (Exception e) {
            LOG.warn("  Failed to parse EIP JSON: {}", e.getMessage());
            return null;
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Appends formatted property entries to the content builder. Each property is formatted as:
     * {@code - displayName (type, required): description. Default: value}
     */
    private static void appendProperties(StringBuilder sb, JSONObject properties) {
        for (String propName : properties.keySet()) {
            JSONObject prop = properties.getJSONObject(propName);
            String desc = prop.optString("description", "");
            if (desc.isEmpty())
                continue;

            String displayName = prop.optString("displayName", propName);
            String type = prop.optString("type", "");
            boolean required = prop.optBoolean("required", false);
            String defaultValue = prop.optString("defaultValue", "");

            sb.append("- ").append(displayName);
            if (!type.isEmpty()) {
                sb.append(" (").append(type);
                if (required) {
                    sb.append(", required");
                }
                sb.append(")");
            }
            sb.append(": ").append(desc);
            if (!defaultValue.isEmpty()) {
                sb.append(" Default: ").append(defaultValue);
            }
            sb.append("\n");
        }
    }

    /**
     * Extracts the version number from a camelTag (e.g. "camel-4.14.6" -> "4.14.6").
     */
    private String extractVersion(String camelTag) {
        Matcher m = CAMEL_TAG_PATTERN.matcher(camelTag);
        if (m.matches()) {
            return m.group(1);
        }
        return camelTag;
    }

    /**
     * Downloads the camel-catalog JAR from Maven Central if not already cached.
     *
     * @param  version the Camel version, e.g. "4.14.6"
     * @return         path to the downloaded JAR
     */
    private Path downloadCatalogJar(String version) throws IOException, InterruptedException {
        String[] parts = version.split("\\.");
        if (parts.length != 3) {
            throw new IOException("Invalid version format: " + version);
        }

        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);

        HttpClient client = HttpClient.newHttpClient();

        for (int p = patch; p >= 0; p--) {
            String tryVersion = major + "." + minor + "." + p;
            String jarName = "camel-catalog-" + tryVersion + ".jar";
            Path jarPath = cacheDir.resolve(jarName);

            if (Files.exists(jarPath)) {
                if (p != patch) {
                    LOG.info("  Using cached {} (latest available for {})", jarName, version);
                } else {
                    LOG.info("  Using cached {}", jarName);
                }
                return jarPath;
            }

            String url = String.format(Locale.ROOT, MAVEN_CENTRAL_URL, tryVersion, tryVersion);

            // HEAD request first to check availability without downloading
            HttpRequest headRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> headResponse = client.send(headRequest,
                    HttpResponse.BodyHandlers.discarding());

            if (headResponse.statusCode() == 200) {
                LOG.info("  Downloading {}{}...", jarName,
                        p != patch ? " (latest available for " + version + ")" : "");

                HttpRequest getRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<Path> response = client.send(getRequest,
                        HttpResponse.BodyHandlers.ofFile(jarPath));

                if (response.statusCode() == 200) {
                    return jarPath;
                }
                Files.deleteIfExists(jarPath);
            }
        }

        throw new IOException("No camel-catalog JAR available for " + major + "." + minor + ".x on Maven Central");
    }

    /**
     * Reads the content of a JAR entry as a UTF-8 string.
     */
    private static String readEntry(JarFile jarFile, JarEntry entry) throws IOException {
        try (InputStream is = jarFile.getInputStream(entry)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
