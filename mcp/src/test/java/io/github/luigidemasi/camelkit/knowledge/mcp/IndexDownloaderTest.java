package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the manifest-based index resolver, using file and loopback HTTP fixtures.
 */
class IndexDownloaderTest {

    @TempDir
    Path tmp;

    private Path publishDir;
    private Path cacheDir;
    private Path localIndexDir;

    private IndexDownloader downloader(String path, String url) {
        return new IndexDownloader(config(path, url));
    }

    private IndexResolverConfig config(String path, String url) {
        return new IndexResolverConfig() {
            @Override
            public Optional<String> path() {
                return Optional.ofNullable(path);
            }

            @Override
            public String url() {
                return url != null ? url : "file://" + publishDir.resolve("index.json");
            }

            @Override
            public String cacheDir() {
                return cacheDir.toString();
            }
        };
    }

    /** Publishes a fake index release (zip + manifest) into publishDir; returns the manifest URL. */
    private String publish(String version, String content, String sha256Override) throws Exception {
        publishDir = tmp.resolve("publish");
        Files.createDirectories(publishDir);
        cacheDir = tmp.resolve("cache");

        Path zip = publishDir.resolve("knowledge-index.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("knowledge-index/segments_1"));
            zos.write(content.getBytes());
            zos.closeEntry();
        }

        String sha = sha256Override != null
                ? sha256Override
                : HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        Files.writeString(publishDir.resolve("index.json"), String.format(
                "{\"version\":\"%s\",\"sha256\":\"%s\",\"asset\":\"knowledge-index.zip\","
                                                                          + "\"embeddingModel\":\"granite-embedding-small-english-r2-q8\"}",
                version, sha));
        return "file://" + publishDir.resolve("index.json");
    }

    @Test
    void explicitPathIsUsedDirectly() throws Exception {
        localIndexDir = tmp.resolve("local-index");
        Files.createDirectories(localIndexDir);
        cacheDir = tmp.resolve("cache");
        publishDir = tmp.resolve("publish");

        IndexDownloader.ResolvedIndex resolved = downloader(localIndexDir.toString(), null).resolve();

        assertEquals(localIndexDir.toAbsolutePath(), resolved.dir());
        assertFalse(resolved.temporary());
        assertFalse(Files.exists(cacheDir.resolve("current")), "Path mode must not touch the cache");
    }

    @Test
    void downloadVerifySwapAndReuse() throws Exception {
        String url = publish("2026.07.06", "index-bytes", null);

        IndexDownloader.ResolvedIndex first = downloader(null, url).resolve();
        assertEquals(cacheDir.resolve("2026.07.06"), first.dir());
        assertEquals("index-bytes", Files.readString(first.dir().resolve("segments_1")),
                "Top-level zip dir must be stripped");
        assertEquals("2026.07.06", Files.readString(cacheDir.resolve("current")).trim());

        // Second resolve: same version — must reuse the cache without re-downloading
        Files.delete(publishDir.resolve("knowledge-index.zip"));
        IndexDownloader.ResolvedIndex second = downloader(null, url).resolve();
        assertEquals(first.dir(), second.dir());
    }

    @Test
    void sha256MismatchIsRejectedAndCacheUntouched() throws Exception {
        String url = publish("2026.07.06", "index-bytes", "0".repeat(64));

        assertThrows(IOException.class, () -> downloader(null, url).resolve());
        assertFalse(Files.exists(cacheDir.resolve("2026.07.06")), "Corrupted download must not be installed");
        assertFalse(Files.exists(cacheDir.resolve("current")));
    }

    @Test
    void unreachableUrlFallsBackToCachedVersion() throws Exception {
        String url = publish("2026.07.06", "index-bytes", null);
        downloader(null, url).resolve();

        String deadUrl = "file://" + publishDir.resolve("missing.json");
        IndexDownloader.ResolvedIndex resolved = downloader(null, deadUrl).resolve();
        assertEquals(cacheDir.resolve("2026.07.06"), resolved.dir());
    }

    @Test
    void unreachableUrlWithEmptyCacheFailsWithClearError() throws Exception {
        publishDir = tmp.resolve("publish");
        Files.createDirectories(publishDir);
        cacheDir = tmp.resolve("cache");

        String deadUrl = "file://" + publishDir.resolve("missing.json");
        IOException e = assertThrows(IOException.class, () -> downloader(null, deadUrl).resolve());
        assertTrue(e.getMessage().contains("knowledge.index.path"),
                "Error must point the user at the escape hatches: " + e.getMessage());
    }

    @Test
    void newVersionReplacesOldAndPrunes() throws Exception {
        String url = publish("2026.07.01", "old-bytes", null);
        downloader(null, url).resolve();

        // Publish a newer version at the same URL
        Path zip = publishDir.resolve("knowledge-index.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("knowledge-index/segments_1"));
            zos.write("new-bytes".getBytes());
            zos.closeEntry();
        }
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        Files.writeString(publishDir.resolve("index.json"), String.format(
                "{\"version\":\"2026.07.08\",\"sha256\":\"%s\",\"asset\":\"knowledge-index.zip\"}", sha));

        IndexDownloader.ResolvedIndex resolved = downloader(null, "file://" + publishDir.resolve("index.json"))
                .resolve();
        assertEquals(cacheDir.resolve("2026.07.08"), resolved.dir());
        assertEquals("new-bytes", Files.readString(resolved.dir().resolve("segments_1")));
        assertTrue(Files.isDirectory(cacheDir.resolve("2026.07.01")), "Previous version is retained");
        assertEquals("2026.07.08", Files.readString(cacheDir.resolve("current")).trim());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedHttpUpdateRetriesUnchangedManifest(boolean corruptArchive) throws Exception {
        publish("v1", "old-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            Path first = downloader(null, http.url()).resolve().dir();
            assertEquals(first, downloader(null, http.url()).resolve().dir());
            assertEquals("\"v1\"", http.ifNoneMatch);
            assertEquals(1, http.notModified.get());
            assertEquals(1, http.downloads.get(), "HTTP 304 must reuse the installed index");
            String validator = Files.readString(cacheDir.resolve("etag"));

            publish("v2", "new-bytes", null);
            http.etag = "\"v2\"";
            http.assetStatus = corruptArchive ? 200 : 503;
            http.corruptArchive = corruptArchive;
            IOException failure = assertThrows(IOException.class, () -> downloader(null, http.url()).resolve());
            assertTrue(failure.getMessage().contains(corruptArchive ? "sha256 mismatch" : "HTTP 503"));
            assertEquals("v1", Files.readString(cacheDir.resolve("current")));
            assertEquals(validator, Files.readString(cacheDir.resolve("etag")));
            assertEquals("old-bytes", Files.readString(first.resolve("segments_1")));
            assertFalse(Files.exists(cacheDir.resolve("v2")));
            assertNoPartialFiles();

            http.assetStatus = 200;
            http.corruptArchive = false;
            Path recovered = downloader(null, http.url()).resolve().dir();
            assertEquals("\"v1\"", http.ifNoneMatch, "Failed v2 must not suppress the recovery download");
            assertEquals("new-bytes", Files.readString(recovered.resolve("segments_1")));
            assertEquals("v2", Files.readString(cacheDir.resolve("current")));
            assertEquals(recovered, downloader(null, http.url()).resolve().dir());
            assertEquals("\"v2\"", http.ifNoneMatch);
            assertEquals(2, http.notModified.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedMarkerReplacementPreservesPreviousCache(boolean atomicMoveUnsupported) throws Exception {
        publish("v1", "old-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            Path previous = downloader(null, http.url()).resolve().dir();
            String validator = Files.readString(cacheDir.resolve("etag"));
            publish("v2", "new-bytes", null);
            http.etag = "\"v2\"";
            IndexDownloader failing = new IndexDownloader(config(null, http.url())) {
                @Override
                void moveAtomically(Path source, Path target) throws IOException {
                    if (target.equals(cacheDir.resolve("current"))) {
                        assertEquals("v1", Files.readString(target));
                        assertEquals("v2", Files.readString(source));
                        if (atomicMoveUnsupported) {
                            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                        }
                        throw new IOException("Marker replacement failed");
                    }
                    super.moveAtomically(source, target);
                }
            };
            assertThrows(IOException.class, failing::resolve);
            assertEquals("v1", Files.readString(cacheDir.resolve("current")));
            assertEquals(validator, Files.readString(cacheDir.resolve("etag")));
            assertNoPartialFiles();

            http.manifestStatus = 503;
            assertEquals(previous, downloader(null, http.url()).resolve().dir());
            assertEquals("old-bytes", Files.readString(previous.resolve("segments_1")));
            http.manifestStatus = 200;
            assertEquals(cacheDir.resolve("v2"), downloader(null, http.url()).resolve().dir());
            assertEquals(2, http.downloads.get(), "Recovery reuses the fully downloaded inactive version");
        }
    }

    @Test
    void activationReplacesMarkerInsteadOfTruncatingIt() throws Exception {
        String url = publish("v1", "old-bytes", null);
        downloader(null, url).resolve();
        Path oldMarker = tmp.resolve("old-marker");
        Files.createLink(oldMarker, cacheDir.resolve("current"));

        publish("v2", "new-bytes", null);
        downloader(null, url).resolve();

        assertEquals("v1", Files.readString(oldMarker), "The previous marker inode must remain intact");
        assertEquals("v2", Files.readString(cacheDir.resolve("current")));
        assertNoPartialFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"v2\"", "{"})
    void legacyOrIncompleteValidatorCannotSuppressUpdate(String staleValidator) throws Exception {
        publish("v1", "old-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            downloader(null, http.url()).resolve();
            Files.writeString(cacheDir.resolve("etag"), staleValidator);
            publish("v2", "new-bytes", null);
            http.etag = "\"v2\"";

            assertEquals(cacheDir.resolve("v2"), downloader(null, http.url()).resolve().dir());
            assertNull(http.ifNoneMatch);
        }
    }

    @Test
    void validatorIsBoundToManifestUrl() throws Exception {
        publish("v1", "old-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            downloader(null, http.url()).resolve();
            publish("v2", "new-bytes", null);
            // A different manifest may use the same ETag for different content.
            assertEquals(cacheDir.resolve("v2"), downloader(null, http.url() + "?channel=other").resolve().dir());
            assertNull(http.ifNoneMatch);
        }
    }

    @Test
    void failedValidatorSaveDoesNotBindOldEtagToNewVersion() throws Exception {
        publish("v1", "old-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            downloader(null, http.url()).resolve();
            publish("v2", "new-bytes", null);
            http.etag = "\"v2\"";
            IndexDownloader failing = new IndexDownloader(config(null, http.url())) {
                @Override
                void moveAtomically(Path source, Path target) throws IOException {
                    if (target.equals(cacheDir.resolve("etag"))) {
                        throw new IOException("Validator replacement failed");
                    }
                    super.moveAtomically(source, target);
                }
            };
            assertEquals(cacheDir.resolve("v2"), failing.resolve().dir());
            assertNoPartialFiles();

            assertEquals(cacheDir.resolve("v2"), downloader(null, http.url()).resolve().dir());
            assertNull(http.ifNoneMatch, "A validator for v1 cannot validate the active v2");
            assertEquals(cacheDir.resolve("v2"), downloader(null, http.url()).resolve().dir());
            assertEquals("\"v2\"", http.ifNoneMatch);
            assertEquals(2, http.downloads.get());
        }
    }

    private void assertNoPartialFiles() throws IOException {
        try (var files = Files.list(cacheDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private class HttpFixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger downloads = new AtomicInteger();
        final AtomicInteger notModified = new AtomicInteger();
        volatile String etag = "\"v1\"";
        volatile String ifNoneMatch;
        volatile int manifestStatus = 200;
        volatile int assetStatus = 200;
        volatile boolean corruptArchive;

        HttpFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    boolean manifest = exchange.getRequestURI().getPath().equals("/index.json");
                    int status = manifest ? manifestStatus : assetStatus;
                    if (manifest) {
                        ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                        if (etag != null) {
                            exchange.getResponseHeaders().set("ETag", etag);
                        }
                        if (status == 200 && etag != null && etag.equals(ifNoneMatch)) {
                            notModified.incrementAndGet();
                            exchange.sendResponseHeaders(304, -1);
                            return;
                        }
                    } else {
                        downloads.incrementAndGet();
                    }
                    byte[] body = !manifest && corruptArchive
                            ? new byte[]{0}
                            : Files.readAllBytes(publishDir.resolve(manifest ? "index.json" : "knowledge-index.zip"));
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/index.json";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
