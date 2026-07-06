package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the manifest-based index resolver, using file:// fixtures — no network.
 */
class IndexDownloaderTest {

    @TempDir
    Path tmp;

    private Path publishDir;
    private Path cacheDir;
    private Path localIndexDir;

    private IndexDownloader downloader(String path, String url) {
        IndexResolverConfig config = new IndexResolverConfig() {
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
        return new IndexDownloader(config);
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
}
