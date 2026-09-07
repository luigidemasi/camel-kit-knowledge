package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        Files.writeString(publishDir.resolve("index.json"), new ObjectMapper().createObjectNode()
                .put("version", version).put("sha256", sha).put("asset", "knowledge-index.zip")
                .put("embeddingModel", "granite-embedding-small-english-r2-q8").toString());
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
            assertEquals(first, downloader(null, http.url()).resolve().dir(),
                    "A failed update must immediately serve the previous active index");
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
            assertEquals(previous, failing.resolve().dir(), "An unmarked download is not safe to serve");
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

    @Test
    void failedFirstActivationDoesNotExposeUnmarkedIndex() throws Exception {
        String url = publish("v1", "index-bytes", null);
        IndexDownloader failing = new IndexDownloader(config(null, url)) {
            @Override
            void moveAtomically(Path source, Path target) throws IOException {
                if (target.equals(cacheDir.resolve("current"))) {
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                }
                super.moveAtomically(source, target);
            }
        };
        assertThrows(AtomicMoveNotSupportedException.class, failing::resolve);
        assertFalse(Files.exists(cacheDir.resolve("current")));
        assertFalse(Files.exists(cacheDir.resolve("etag")));
        assertNoPartialFiles();
    }

    @Test
    void metadataHonorsUmaskAndPreservesExistingPermissions() throws Exception {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"));
        var defaultPermissions = Files.getPosixFilePermissions(Files.createFile(tmp.resolve("ordinary-file")));
        publish("v1", "old-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            downloader(null, http.url()).resolve();
            Path marker = cacheDir.resolve("current");
            Path validator = cacheDir.resolve("etag");
            assertEquals(defaultPermissions, Files.getPosixFilePermissions(marker));
            assertEquals(defaultPermissions, Files.getPosixFilePermissions(validator));
            // Preserve both a read-only marker and a group-writable validator despite the process umask.
            var markerPermissions = PosixFilePermissions.fromString("r--r-----");
            var validatorPermissions = PosixFilePermissions.fromString("rw-rw-r--");
            Files.setPosixFilePermissions(marker, markerPermissions);
            Files.setPosixFilePermissions(validator, validatorPermissions);
            publish("v2", "new-bytes", null);
            http.etag = "\"v2\"";

            assertEquals(cacheDir.resolve("v2"), downloader(null, http.url()).resolve().dir());
            assertEquals(markerPermissions, Files.getPosixFilePermissions(marker));
            assertEquals(validatorPermissions, Files.getPosixFilePermissions(validator));
        }
    }

    @Test
    void unavailableUpdateLockStillAllowsCachedReaders() throws Exception {
        String url = publish("v1", "index-bytes", null);
        Path previous = downloader(null, url).resolve().dir();
        Files.deleteIfExists(cacheDir.resolve(".update.lock"));
        Files.createDirectory(cacheDir.resolve(".update.lock"));
        publish("v2", "new-bytes", null);
        assertEquals(previous, downloader(null, url).resolve().dir());
        assertFalse(Files.exists(cacheDir.resolve("v2")));
        assertEquals("v1", Files.readString(cacheDir.resolve("current")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsolicitedNotModifiedRequiresUsableCache(boolean haveCache) throws Exception {
        publish("v1", "index-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            if (haveCache) {
                downloader(null, http.url()).resolve();
                Files.delete(cacheDir.resolve("etag"));
            }
            http.manifestStatus = 304;
            if (haveCache) {
                assertEquals(cacheDir.resolve("v1"), downloader(null, http.url()).resolve().dir());
            } else {
                IOException failure = assertThrows(IOException.class, () -> downloader(null, http.url()).resolve());
                assertTrue(failure.getCause().getMessage().contains("304 without a matching cached validator"));
                assertFalse(Files.exists(cacheDir.resolve("current")));
            }
            assertNull(http.ifNoneMatch);
            assertEquals(haveCache ? 1 : 0, http.downloads.get());
            assertFalse(Files.exists(cacheDir.resolve("etag")));
        }
    }

    @Test
    void successfulHttpResponseWithoutEtagRemovesPreviousValidator() throws Exception {
        publish("v1", "index-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            Path previous = downloader(null, http.url()).resolve().dir();
            assertTrue(Files.exists(cacheDir.resolve("etag")));
            http.etag = null;
            assertEquals(previous, downloader(null, http.url()).resolve().dir());
            assertEquals("\"v1\"", http.ifNoneMatch);
            assertFalse(Files.exists(cacheDir.resolve("etag")));
            assertEquals(previous, downloader(null, http.url()).resolve().dir());
            assertNull(http.ifNoneMatch);
            assertEquals(1, http.downloads.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../outside", "/tmp/outside", "..\\outside", "C:\\outside", ".", "..", "",
            "current", "CURRENT", "etag", "ETAG", ".update.lock", "v1.part", "v1.PART", "v1.", "v 1",
            "CON", "nul.txt", "COM1", "LPT9.zip", "v1\u0000"})
    void unsafeManifestVersionsCannotTouchCache(String version) throws Exception {
        publish(version, "index-bytes", null);
        try (HttpFixture http = new HttpFixture()) {
            IOException failure = assertThrows(IOException.class, () -> downloader(null, http.url()).resolve());
            assertTrue(failure.getCause().getMessage().contains("safe directory name"));
            assertEquals(0, http.downloads.get(), "Reject the version before downloading or constructing paths");
            assertFalse(Files.exists(cacheDir.resolve("current")));
            assertNoPartialFiles();
        }
    }

    @Test
    void unsafeUpdateAndCachedMarkerCannotEscapeCache() throws Exception {
        String url = publish("v1", "old-bytes", null);
        Path previous = downloader(null, url).resolve().dir();
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "untouched");
        publish("../outside", "new-bytes", null);
        assertEquals(previous, downloader(null, url).resolve().dir());
        assertEquals("v1", Files.readString(cacheDir.resolve("current")));

        Files.writeString(cacheDir.resolve("current"), "../outside");
        assertThrows(IOException.class, () -> downloader(null, url).resolve());
        assertEquals("untouched", Files.readString(sentinel));
        assertFalse(Files.exists(outside.resolve("segments_1")));
    }

    @Test
    void versionSymlinkIsNeitherActivatedNorUsedAsCachedFallback() throws Exception {
        String url = publish("v1", "index-bytes", null);
        Files.createDirectories(cacheDir);
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep"), "untouched");
        Files.createSymbolicLink(cacheDir.resolve("v1"), outside);
        assertThrows(IOException.class, () -> downloader(null, url).resolve());
        assertFalse(Files.exists(cacheDir.resolve("current")));

        Files.writeString(cacheDir.resolve("current"), "v1");
        assertThrows(IOException.class, () -> downloader(null, "file://" + tmp.resolve("missing.json")).resolve());
        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test
    void downloadDoesNotDeleteAnotherInvocationsStagingDirectory() throws Exception {
        String url = publish("v1", "index-bytes", null);
        Path otherStage = Files.createDirectories(cacheDir.resolve("v1.part"));
        Path sentinel = Files.writeString(otherStage.resolve("keep"), "another download");
        assertEquals(cacheDir.resolve("v1"), downloader(null, url).resolve().dir());
        assertEquals("another download", Files.readString(sentinel));
    }

    @Test
    void cachedResolveCleansInterruptedStagingFiles() throws Exception {
        String url = publish("v1", "index-bytes", null);
        Path previous = downloader(null, url).resolve().dir();
        String suffix = "-12345678-1234-1234-1234-123456789abc.part";
        Path stage = Files.createDirectory(cacheDir.resolve(".index" + suffix));
        Files.writeString(stage.resolve("segments_1"), "partial extraction");
        Files.writeString(cacheDir.resolve("index-12345678.zip.part"), "partial download");
        Files.writeString(cacheDir.resolve("current" + suffix), "partial marker");
        Files.writeString(cacheDir.resolve("etag" + suffix), "partial validator");
        assertEquals(previous, downloader(null, url).resolve().dir());
        assertNoPartialFiles();
        assertEquals("index-bytes", Files.readString(previous.resolve("segments_1")));
    }

    @Test
    void failedDirectoryActivationKeepsPreviousCacheAndRetriesDownload() throws Exception {
        String url = publish("v1", "old-bytes", null);
        Path previous = downloader(null, url).resolve().dir();
        publish("v2", "new-bytes", null);
        IndexDownloader failing = new IndexDownloader(config(null, url)) {
            @Override
            void moveAtomically(Path source, Path target) throws IOException {
                if (target.equals(cacheDir.resolve("v2"))) {
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
                }
                super.moveAtomically(source, target);
            }
        };
        assertEquals(previous, failing.resolve().dir());
        assertEquals("v1", Files.readString(cacheDir.resolve("current")));
        assertFalse(Files.exists(cacheDir.resolve("v2")), "Never leave a partially installed version to reuse");
        assertNoPartialFiles();
        assertEquals(cacheDir.resolve("v2"), downloader(null, url).resolve().dir());
        assertEquals("new-bytes", Files.readString(cacheDir.resolve("v2/segments_1")));
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 129})
    void versionLengthLimitLeavesRoomForFilesystemNames(int length) throws Exception {
        String version = "v".repeat(length);
        String url = publish(version, "index-bytes", null);
        if (length == 128) {
            assertEquals(cacheDir.resolve(version), downloader(null, url).resolve().dir());
        } else {
            assertThrows(IOException.class, () -> downloader(null, url).resolve());
            assertFalse(Files.exists(cacheDir.resolve("current")));
        }
        assertNoPartialFiles();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentResolversSerializeTheWholeUpdate(boolean separateProcesses) throws Exception {
        publish("v1", "index-bytes", null);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Process firstProcess = null;
        Process secondProcess = null;
        try (HttpFixture http = new HttpFixture()) {
            http.releaseDownload = new CountDownLatch(1);
            Path firstLog = tmp.resolve("first.log");
            Path secondLog = tmp.resolve("second.log");
            var first = separateProcesses ? null : callers.submit(() -> downloader(null, http.url()).resolve());
            if (separateProcesses) {
                firstProcess = startResolverProcess(http.url(), firstLog);
            }
            try {
                assertTrue(http.downloadStarted.await(20, TimeUnit.SECONDS));
                CountDownLatch secondStarted = new CountDownLatch(1);
                var second = separateProcesses ? null : callers.submit(() -> {
                    IndexDownloader resolver = downloader(null, http.url());
                    secondStarted.countDown();
                    return resolver.resolve();
                });
                if (separateProcesses) {
                    secondProcess = startResolverProcess(http.url(), secondLog);
                    Process process = secondProcess;
                    var ready = callers.submit(() -> {
                        try (var output = process.inputReader()) {
                            return output.readLine();
                        }
                    });
                    assertEquals("ready", ready.get(20, TimeUnit.SECONDS));
                } else {
                    assertTrue(secondStarted.await(20, TimeUnit.SECONDS));
                }
                assertFalse(http.secondManifest.await(1, TimeUnit.SECONDS),
                        "The second resolver must wait before reading the manifest and cache state");
                http.releaseDownload.countDown();
                if (separateProcesses) {
                    assertTrue(firstProcess.waitFor(20, TimeUnit.SECONDS));
                    assertTrue(secondProcess.waitFor(20, TimeUnit.SECONDS));
                    assertEquals(0, firstProcess.exitValue(), Files.readString(firstLog));
                    assertEquals(0, secondProcess.exitValue(), Files.readString(secondLog));
                } else {
                    assertEquals(cacheDir.resolve("v1"), first.get(20, TimeUnit.SECONDS).dir());
                    assertEquals(cacheDir.resolve("v1"), second.get(20, TimeUnit.SECONDS).dir());
                }
                assertEquals(1, http.downloads.get());
                assertEquals(1, http.notModified.get());
                assertEquals("v1", Files.readString(cacheDir.resolve("current")));
                assertNoPartialFiles();
            } finally {
                http.releaseDownload.countDown();
            }
        } finally {
            if (firstProcess != null) {
                firstProcess.destroyForcibly();
            }
            if (secondProcess != null) {
                secondProcess.destroyForcibly();
            }
            callers.shutdownNow();
        }
    }

    private Process startResolverProcess(String url, Path log) throws IOException {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                IndexDownloaderTest.class.getName(), cacheDir.toString(), url)
                .redirectError(log.toFile()).start();
    }

    /** Child entry point exercises real OS locking with the same resolver and cache as the parent test. */
    public static void main(String[] args) throws Exception {
        IndexDownloaderTest fixture = new IndexDownloaderTest();
        fixture.cacheDir = Path.of(args[0]);
        IndexDownloader resolver = fixture.downloader(null, args[1]);
        System.out.println("ready");
        resolver.resolve();
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
        final AtomicInteger manifests = new AtomicInteger();
        final CountDownLatch downloadStarted = new CountDownLatch(1);
        final CountDownLatch secondManifest = new CountDownLatch(1);
        final ExecutorService requests = Executors.newCachedThreadPool();
        volatile CountDownLatch releaseDownload = new CountDownLatch(0);
        volatile String etag = "\"v1\"";
        volatile String ifNoneMatch;
        volatile int manifestStatus = 200;
        volatile int assetStatus = 200;
        volatile boolean corruptArchive;

        HttpFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(requests);
            server.createContext("/", exchange -> {
                try (exchange) {
                    boolean manifest = exchange.getRequestURI().getPath().equals("/index.json");
                    int status = manifest ? manifestStatus : assetStatus;
                    if (manifest) {
                        if (manifests.incrementAndGet() == 2) {
                            secondManifest.countDown();
                        }
                        ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                        if (etag != null) {
                            exchange.getResponseHeaders().set("ETag", etag);
                        }
                        if (status == 304 || (status == 200 && etag != null && etag.equals(ifNoneMatch))) {
                            notModified.incrementAndGet();
                            exchange.sendResponseHeaders(304, -1);
                            return;
                        }
                    } else {
                        downloads.incrementAndGet();
                        downloadStarted.countDown();
                        try {
                            if (!releaseDownload.await(30, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out waiting for test to release download");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
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
            releaseDownload.countDown();
            server.stop(0);
            requests.shutdownNow();
        }
    }
}
