package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxEmbeddingProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the knowledge index from a JSON manifest published as a GitHub Release asset (or any https/file URL).
 * Downloaded versions live in a local cache and are opened in place — no per-startup extraction.
 *
 * <p>
 * Resolution order:
 * <ol>
 * <li>{@code knowledge.index.path} — a local index directory, used directly (dev/tests/air-gapped)</li>
 * <li>{@code knowledge.index.url} manifest → compare version with the cache's {@code current} marker → download +
 * sha256-verify + unzip + atomic swap only when a new version is available; offline falls back to the cached
 * version</li>
 * </ol>
 * The legacy classpath extraction fallback lives in {@link LuceneSearchService}.
 */
@ApplicationScoped
public class IndexDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(IndexDownloader.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MANIFEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ASSET_TIMEOUT = Duration.ofMinutes(5);

    private final IndexResolverConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** A resolved index directory; temporary directories are deleted at shutdown. */
    public record ResolvedIndex(Path dir, boolean temporary) {
    }

    record Manifest(String version, String sha256, String asset, String embeddingModel) {
    }

    @Inject
    public IndexDownloader(IndexResolverConfig config) {
        this.config = config;
    }

    public ResolvedIndex resolve() throws IOException {
        // 1. Explicit local path — no network, no cache
        if (config.path().isPresent() && !config.path().get().isBlank()) {
            Path path = Path.of(config.path().get()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new IOException("knowledge.index.path is not a directory: " + path);
            }
            LOG.info("Using local knowledge index: {}", path);
            return new ResolvedIndex(path, false);
        }

        // 2. Manifest + local cache
        Path cacheDir = Path.of(config.cacheDir());
        Files.createDirectories(cacheDir);
        Path marker = cacheDir.resolve("current");
        String cachedVersion = Files.isRegularFile(marker) ? Files.readString(marker).trim() : null;
        Path cachedDir = cachedVersion != null && !cachedVersion.isEmpty() ? cacheDir.resolve(cachedVersion) : null;
        boolean haveCached = cachedDir != null && Files.isDirectory(cachedDir);

        URI manifestUri = URI.create(config.url());
        Manifest manifest;
        try {
            manifest = fetchManifest(manifestUri, cacheDir, haveCached);
            if (manifest == null) {
                // 304 Not Modified — the cached version is current
                LOG.info("Knowledge index up to date (version {})", cachedVersion);
                return new ResolvedIndex(cachedDir, false);
            }
        } catch (Exception e) {
            if (haveCached) {
                LOG.warn("Index manifest check failed ({}); using cached version {}", e.getMessage(), cachedVersion);
                return new ResolvedIndex(cachedDir, false);
            }
            throw new IOException(
                    "Cannot fetch index manifest from " + manifestUri + " and no cached index exists in " + cacheDir
                                  + ". Set knowledge.index.path to a local index directory, or knowledge.index.url "
                                  + "to a reachable manifest.",
                    e);
        }

        if (manifest.version().equals(cachedVersion) && haveCached) {
            LOG.info("Knowledge index up to date (version {})", cachedVersion);
            return new ResolvedIndex(cachedDir, false);
        }

        // Informational precheck — the authoritative guard runs on the __index_meta__ stamp at open time
        String runtimeModel = new OnnxEmbeddingProvider().modelId();
        if (manifest.embeddingModel() != null && !manifest.embeddingModel().equals(runtimeModel)) {
            LOG.warn("Manifest embedding model '{}' differs from runtime model '{}' — vector search will be disabled",
                    manifest.embeddingModel(), runtimeModel);
        }

        Path versionDir = cacheDir.resolve(manifest.version());
        if (!Files.isDirectory(versionDir)) {
            downloadAndUnpack(manifestUri, manifest, cacheDir, versionDir);
        }

        Files.writeString(marker, manifest.version());
        prune(cacheDir, manifest.version(), cachedVersion);
        LOG.info("Knowledge index version {} ready at {}", manifest.version(), versionDir);
        return new ResolvedIndex(versionDir, false);
    }

    /** Fetches and parses the manifest. Returns null on HTTP 304 (only sent when an ETag is cached). */
    private Manifest fetchManifest(URI uri, Path cacheDir, boolean haveCached)
            throws IOException, InterruptedException {
        byte[] body;
        if ("file".equals(uri.getScheme())) {
            body = Files.readAllBytes(Path.of(uri));
        } else {
            Path etagFile = cacheDir.resolve("etag");
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(MANIFEST_TIMEOUT).GET();
            if (haveCached && Files.isRegularFile(etagFile)) {
                request.header("If-None-Match", Files.readString(etagFile).trim());
            }
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 304) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IOException("Manifest fetch returned HTTP " + response.statusCode());
            }
            response.headers().firstValue("ETag").ifPresent(etag -> {
                try {
                    Files.writeString(etagFile, etag);
                } catch (IOException ignored) {
                }
            });
            body = response.body();
        }

        JsonNode root = mapper.readTree(new String(body, StandardCharsets.UTF_8));
        String version = root.path("version").asText(null);
        String sha256 = root.path("sha256").asText(null);
        if (version == null || sha256 == null) {
            throw new IOException("Manifest is missing required fields (version, sha256)");
        }
        return new Manifest(
                version, sha256,
                root.path("asset").asText("knowledge-index.zip"),
                root.path("embeddingModel").asText(null));
    }

    private void downloadAndUnpack(URI manifestUri, Manifest manifest, Path cacheDir, Path versionDir)
            throws IOException {
        URI assetUri = manifestUri.resolve(manifest.asset());
        LOG.info("Downloading knowledge index {} from {} ...", manifest.version(), assetUri);

        Path zipFile = Files.createTempFile(cacheDir, "index-", ".zip.part");
        Path partDir = cacheDir.resolve(manifest.version() + ".part");
        try {
            String actualSha = downloadTo(assetUri, zipFile);
            if (!actualSha.equalsIgnoreCase(manifest.sha256())) {
                throw new IOException(
                        "Index archive sha256 mismatch: expected " + manifest.sha256()
                                      + " but downloaded " + actualSha);
            }

            deleteRecursively(partDir);
            unzip(zipFile, partDir);
            deleteRecursively(versionDir);
            Files.move(partDir, versionDir, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(zipFile);
            deleteRecursively(partDir);
        }
    }

    /** Downloads the asset to the target file, returning its sha256 hex. */
    private String downloadTo(URI uri, Path target) throws IOException {
        try (InputStream in = openStream(uri)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Index download failed from " + uri, e);
        }
    }

    private InputStream openStream(URI uri) throws IOException, InterruptedException {
        if ("file".equals(uri.getScheme())) {
            return Files.newInputStream(Path.of(uri));
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(ASSET_TIMEOUT).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Index download returned HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
    }

    /** Unzips, stripping the single top-level directory the archive is rooted at (e.g. "knowledge-index/"). */
    private void unzip(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                if (name.isEmpty()) {
                    continue;
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir)) {
                    continue; // zip-slip guard
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Keeps the current and previous versions; deletes anything older. */
    private void prune(Path cacheDir, String currentVersion, String previousVersion) {
        try (var entries = Files.list(cacheDir)) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> {
                        String name = dir.getFileName().toString();
                        return !name.equals(currentVersion) && !name.equals(previousVersion)
                                && !name.endsWith(".part");
                    })
                    .forEach(IndexDownloader::deleteRecursively);
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
