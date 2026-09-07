package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class IndexDownloaderTimeoutTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(600);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tmp;

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void stalledHttpBodyExpiresAndReleasesTheLock(boolean manifest, boolean cached) throws Exception {
        try (HttpFixture http = new HttpFixture(manifest)) {
            if (cached) {
                seedCache();
            }
            var result = http.callers.submit(() -> downloader(http.url(), SHORT_TIMEOUT, SHORT_TIMEOUT).resolve());
            assertTrue(http.bodyStarted.await(3, TimeUnit.SECONDS));
            if (cached) {
                assertEquals(cache().resolve("v1"), result.get(3, TimeUnit.SECONDS).dir());
                assertEquals("v1", Files.readString(cache().resolve("current")));
                assertEquals("original-validator", Files.readString(cache().resolve("etag")));
            } else {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> result.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
                assertTrue(hasCause(failure, HttpTimeoutException.class), failure.toString());
                assertFalse(Files.exists(cache().resolve("current")));
            }
            assertNoPartialFiles();
            assertFalse(Files.exists(cache().resolve("v2")));
            assertEquals(1, http.releaseBody.getCount(), "The server is still withholding the old response body");

            // A new update can finish while the old server response remains blocked: no writer retained the lock.
            http.stall = false;
            assertEquals(cache().resolve("v2"), http.callers.submit(
                    () -> downloader(http.url(), SHORT_TIMEOUT, LONG_TIMEOUT).resolve()).get(3, TimeUnit.SECONDS)
                    .dir());
            String validator = Files.readString(cache().resolve("etag"));
            http.releaseBody.countDown();
            assertTrue(http.bodyFinished.await(3, TimeUnit.SECONDS));
            assertEquals("v2", Files.readString(cache().resolve("current")));
            assertEquals(validator, Files.readString(cache().resolve("etag")));
            assertEquals("new-index", Files.readString(cache().resolve("v2/segments_1")));
            assertNoPartialFiles();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameJvmContentionUsesWarmCacheImmediatelyOrBoundsColdWait(boolean cached) throws Exception {
        try (HttpFixture http = new HttpFixture(false)) {
            if (cached) {
                seedCache();
            }
            var owner = http.callers.submit(() -> new IndexDownloader(config(http.url())).resolve());
            assertTrue(http.bodyStarted.await(3, TimeUnit.SECONDS));
            long started = System.nanoTime();
            var contender = http.callers.submit(() -> cached
                    ? new IndexDownloader(config(http.url())).resolve()
                    : downloader(http.url(), SHORT_TIMEOUT, LONG_TIMEOUT).resolve());
            if (cached) {
                assertEquals(cache().resolve("v1"), contender.get(2, TimeUnit.SECONDS).dir());
            } else {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> contender.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("update lock is busy"));
                assertTrue(System.nanoTime() - started >= Duration.ofMillis(300).toNanos());
            }
            assertFalse(owner.isDone(), "The contender must not wait for the stalled owner");
            assertFalse(Files.exists(cache().resolve("v2")));
            try (var files = Files.list(cache())) {
                assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".zip.part")),
                        "A contender must leave the owner's staging archive alone");
            }
            http.releaseBody.countDown();
            assertEquals(cache().resolve("v2"), owner.get(3, TimeUnit.SECONDS).dir());
            assertNoPartialFiles();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crossJvmContentionUsesWarmCacheImmediatelyOrBoundsColdWait(boolean cached) throws Exception {
        if (cached) {
            seedCache();
        }
        Files.createDirectories(cache());
        Process holder = startLockHolder();
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            assertEquals("locked", callers.submit(() -> holder.inputReader().readLine()).get(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            var contender = callers.submit(() -> downloader(tmp.resolve("missing.json").toUri().toString(),
                    cached ? LONG_TIMEOUT : SHORT_TIMEOUT, LONG_TIMEOUT).resolve());
            if (cached) {
                assertEquals(cache().resolve("v1"), contender.get(2, TimeUnit.SECONDS).dir());
            } else {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> contender.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("update lock is busy"));
                assertTrue(System.nanoTime() - started >= Duration.ofMillis(300).toNanos());
            }
            assertTrue(holder.isAlive(), "The holder is still keeping the OS lock");
            assertNoPartialFiles();
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(5, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
            }
            callers.shutdownNow();
        }
    }

    @Test
    void interruptedBodyReadPreservesInterruptAndReleasesTheLock() throws Exception {
        try (HttpFixture http = new HttpFixture(false)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread resolver = new Thread(() -> {
                try {
                    downloader(http.url(), LONG_TIMEOUT, LONG_TIMEOUT).resolve();
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            resolver.start();
            try {
                assertTrue(http.bodyStarted.await(3, TimeUnit.SECONDS));
                resolver.interrupt();
                resolver.join(2000);
                assertFalse(resolver.isAlive());
                assertInstanceOf(IOException.class, failure.get());
                assertTrue(interrupted.get());
                assertNoPartialFiles();
                http.stall = false;
                assertEquals(cache().resolve("v2"), http.callers.submit(
                        () -> downloader(http.url(), SHORT_TIMEOUT, LONG_TIMEOUT).resolve()).get(3, TimeUnit.SECONDS)
                        .dir());
            } finally {
                http.releaseBody.countDown();
                resolver.interrupt();
                resolver.join(3000);
            }
        }
    }

    @Test
    void interruptedJvmLockWaitPreservesInterrupt() throws Exception {
        try (HttpFixture http = new HttpFixture(false)) {
            var owner = http.callers.submit(() -> downloader(http.url(), LONG_TIMEOUT, LONG_TIMEOUT).resolve());
            assertTrue(http.bodyStarted.await(3, TimeUnit.SECONDS));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            CountDownLatch started = new CountDownLatch(1);
            Thread contender = new Thread(() -> {
                started.countDown();
                try {
                    downloader(http.url(), LONG_TIMEOUT, LONG_TIMEOUT).resolve();
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            contender.start();
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                contender.interrupt();
                contender.join(2000);
                assertFalse(contender.isAlive());
                assertInstanceOf(IOException.class, failure.get());
                assertTrue(interrupted.get());
                assertFalse(owner.isDone());
            } finally {
                http.releaseBody.countDown();
                contender.interrupt();
                contender.join(3000);
            }
            assertEquals(cache().resolve("v2"), owner.get(3, TimeUnit.SECONDS).dir());
        }
    }

    private IndexDownloader downloader(String url, Duration lockTimeout, Duration responseTimeout) {
        return new IndexDownloader(config(url), lockTimeout, responseTimeout, responseTimeout);
    }

    private IndexResolverConfig config(String url) {
        return new IndexResolverConfig() {
            @Override
            public Optional<String> path() {
                return Optional.empty();
            }

            @Override
            public String url() {
                return url;
            }

            @Override
            public String cacheDir() {
                return cache().toString();
            }
        };
    }

    private Path cache() {
        return tmp.resolve("cache");
    }

    private void seedCache() throws IOException {
        Files.createDirectories(cache().resolve("v1"));
        Files.writeString(cache().resolve("v1/segments_1"), "old-index");
        Files.writeString(cache().resolve("current"), "v1");
        Files.writeString(cache().resolve("etag"), "original-validator");
    }

    private void assertNoPartialFiles() throws IOException {
        try (var files = Files.list(cache())) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private Process startLockHolder() throws IOException {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                IndexDownloaderTimeoutTest.class.getName(), cache().resolve(".update.lock").toString())
                .redirectError(tmp.resolve("lock-holder.log").toFile()).start();
    }

    /** A separate JVM holds the real OS lock without involving the resolver's JVM lock. */
    public static void main(String[] args) throws Exception {
        try (FileChannel channel
                = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            System.out.println("locked");
            System.in.read();
        }
    }

    private final class HttpFixture implements AutoCloseable {
        final ExecutorService requests = Executors.newCachedThreadPool();
        final ExecutorService callers = Executors.newCachedThreadPool();
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch releaseBody = new CountDownLatch(1);
        final CountDownLatch bodyFinished = new CountDownLatch(1);
        final HttpServer server;
        volatile boolean stall = true;

        HttpFixture(boolean stallManifest) throws Exception {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(encoded)) {
                zip.putNextEntry(new ZipEntry("knowledge-index/segments_1"));
                zip.write("new-index".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            byte[] archive = encoded.toByteArray();
            byte[] manifest = new ObjectMapper().createObjectNode()
                    .put("version", "v2")
                    .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)))
                    .put("asset", "knowledge-index.zip").toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(requests);
            server.createContext("/", exchange -> {
                boolean isManifest = exchange.getRequestURI().getPath().equals("/index.json");
                boolean stalled = stall && isManifest == stallManifest;
                byte[] body = isManifest ? manifest : archive;
                try (exchange) {
                    exchange.getResponseHeaders().set("ETag", "\"v2\"");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    if (stalled) {
                        bodyStarted.countDown();
                        try {
                            if (!releaseBody.await(20, TimeUnit.SECONDS)) {
                                throw new IOException("Test did not release the HTTP body");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } catch (IOException e) {
                    // Timeout and interruption close the old response while the server is still withholding bytes.
                } finally {
                    if (stalled) {
                        bodyFinished.countDown();
                    }
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/index.json";
        }

        @Override
        public void close() throws InterruptedException {
            releaseBody.countDown();
            server.stop(0);
            requests.shutdownNow();
            callers.shutdownNow();
            assertTrue(requests.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
