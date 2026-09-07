package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
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
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

    // ponytail: one configured cache per server; serialize JVM callers to avoid overlapping file locks.
    private static final ReentrantLock CACHE_LOCK = new ReentrantLock();
    private static final Pattern STAGING_NAME = Pattern.compile(
            "(?:\\.index|current|etag)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part"
                                                                + "|index-[0-9]+\\.zip\\.part");

    private final IndexResolverConfig config;
    private final Duration lockTimeout;
    private final Duration manifestTimeout;
    private final Duration assetTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** A resolved index directory; temporary directories are deleted at shutdown. */
    public record ResolvedIndex(Path dir, boolean temporary) {
    }

    record Manifest(String version, String sha256, String asset, String embeddingModel, String etag) {
    }

    @Inject
    public IndexDownloader(IndexResolverConfig config) {
        this(config, LOCK_TIMEOUT, MANIFEST_TIMEOUT, ASSET_TIMEOUT);
    }

    IndexDownloader(IndexResolverConfig config, Duration lockTimeout, Duration manifestTimeout, Duration assetTimeout) {
        this.config = config;
        this.lockTimeout = lockTimeout;
        this.manifestTimeout = manifestTimeout;
        this.assetTimeout = assetTimeout;
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
        Path cacheDir = Path.of(config.cacheDir()).toAbsolutePath().normalize();
        Files.createDirectories(cacheDir);
        boolean locked = false;
        long deadline = System.nanoTime() + lockTimeout.toNanos();
        try {
            locked = CACHE_LOCK.tryLock();
            if (!locked && cachedDirectory(cacheDir) == null) {
                locked = CACHE_LOCK.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            if (!locked) {
                throw new IOException("Index cache update lock is busy");
            }
            // Keep this file: deleting a lock file would let processes lock different inodes.
            try (FileChannel channel = FileChannel.open(cacheDir.resolve(".update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = acquireFileLock(channel, cacheDir, deadline)) {
                cleanupStaging(cacheDir);
                return resolveCached(cacheDir);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Index cache update interrupted", e);
        } catch (IOException e) {
            // Re-read after lock/update failure; another process may have activated a version.
            Path cachedDir = cachedDirectory(cacheDir);
            if (cachedDir != null) {
                LOG.warn("Index update failed ({}); using cached version {}", e.toString(),
                        cachedDir.getFileName());
                return new ResolvedIndex(cachedDir, false);
            }
            throw e;
        } finally {
            if (locked) {
                CACHE_LOCK.unlock();
            }
        }
    }

    private FileLock acquireFileLock(FileChannel channel, Path cacheDir, long deadline)
            throws IOException, InterruptedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException e) {
                // A caller outside this resolver may hold a lock in this JVM.
            }
            long remaining = deadline - System.nanoTime();
            if (cachedDirectory(cacheDir) != null || remaining <= 0) {
                throw new IOException("Index cache update lock is busy");
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
        }
    }

    private ResolvedIndex resolveCached(Path cacheDir) throws IOException {
        Path marker = cacheDir.resolve("current");
        Path cachedDir = cachedDirectory(cacheDir);
        String cachedVersion = cachedDir != null ? cachedDir.getFileName().toString() : null;
        boolean haveCached = cachedDir != null;

        URI manifestUri = URI.create(config.url());
        Manifest manifest;
        try {
            manifest = fetchManifest(manifestUri, cacheDir, haveCached ? cachedVersion : null);
            if (manifest == null) {
                // 304 Not Modified — the cached version is current
                LOG.info("Knowledge index up to date (version {})", cachedVersion);
                return new ResolvedIndex(cachedDir, false);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (haveCached) {
                LOG.warn("Index manifest check failed ({}); using cached version {}", e.toString(), cachedVersion);
                return new ResolvedIndex(cachedDir, false);
            }
            throw new IOException(
                    "Cannot fetch index manifest from " + manifestUri + " and no cached index exists in " + cacheDir
                                  + ". Set knowledge.index.path to a local index directory, or knowledge.index.url "
                                  + "to a reachable manifest.",
                    e);
        }

        if (manifest.version().equals(cachedVersion) && haveCached) {
            saveValidator(cacheDir, manifestUri, manifest);
            LOG.info("Knowledge index up to date (version {})", cachedVersion);
            return new ResolvedIndex(cachedDir, false);
        }

        // Informational precheck — the authoritative guard runs on the __index_meta__ stamp at open time
        if (manifest.embeddingModel() != null && !manifest.embeddingModel().equals(OnnxEmbeddingProvider.MODEL_ID)) {
            LOG.warn("Manifest embedding model '{}' differs from runtime model '{}' — vector search will be disabled",
                    manifest.embeddingModel(), OnnxEmbeddingProvider.MODEL_ID);
        }

        Path versionDir = versionDirectory(cacheDir, manifest.version());
        if (!Files.isDirectory(versionDir)) {
            downloadAndUnpack(manifestUri, manifest, cacheDir, versionDir);
        }

        writeAtomically(marker, manifest.version());
        saveValidator(cacheDir, manifestUri, manifest);
        // Pruning follows activation and the best-effort validator-save attempt.
        prune(cacheDir, manifest.version(), cachedVersion);
        LOG.info("Knowledge index version {} ready at {}", manifest.version(), versionDir);
        return new ResolvedIndex(versionDir, false);
    }

    private Path cachedDirectory(Path cacheDir) {
        try {
            Path marker = cacheDir.resolve("current");
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                Path dir = versionDirectory(cacheDir, Files.readString(marker).trim());
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    return dir;
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot use cached index marker: {}", e.toString());
        }
        return null;
    }

    private static void validateVersion(String version) throws IOException {
        String lower = version.toLowerCase(Locale.ROOT);
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || version.endsWith(".")
                || lower.endsWith(".part") || lower.equals("current") || lower.equals("etag")
                || lower.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
            throw new IOException("Index version must be a safe directory name: " + version);
        }
    }

    private static Path versionDirectory(Path cacheDir, String version) throws IOException {
        validateVersion(version);
        Path dir = cacheDir.resolve(version);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Index version path is not a real directory: " + dir);
        }
        return dir;
    }

    /** Fetches and parses the manifest. Returns null on HTTP 304 (only sent when an ETag is cached). */
    private Manifest fetchManifest(URI uri, Path cacheDir, String cachedVersion)
            throws IOException, InterruptedException {
        byte[] body;
        String etag = null;
        if ("file".equals(uri.getScheme())) {
            body = Files.readAllBytes(Path.of(uri));
        } else {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(manifestTimeout).GET();
            String cachedEtag = readValidator(cacheDir, uri, cachedVersion);
            if (cachedEtag != null) {
                request.header("If-None-Match", cachedEtag);
            }
            HttpResponse<InputStream> response = send(request.build());
            try (InputStream in = response.body()) {
                if (response.statusCode() == 304) {
                    if (cachedEtag == null) {
                        throw new IOException("Manifest returned HTTP 304 without a matching cached validator");
                    }
                    return null;
                }
                if (response.statusCode() != 200) {
                    throw new IOException("Manifest fetch returned HTTP " + response.statusCode());
                }
                etag = response.headers().firstValue("ETag").orElse(null);
                body = in.readAllBytes();
            }
        }

        JsonNode root = mapper.readTree(new String(body, StandardCharsets.UTF_8));
        String version = root.path("version").asText(null);
        String sha256 = root.path("sha256").asText(null);
        if (version == null || sha256 == null) {
            throw new IOException("Manifest is missing required fields (version, sha256)");
        }
        validateVersion(version);
        return new Manifest(
                version, sha256,
                root.path("asset").asText("knowledge-index.zip"),
                root.path("embeddingModel").asText(null), etag);
    }

    private String readValidator(Path cacheDir, URI uri, String cachedVersion) {
        if (cachedVersion == null) {
            return null;
        }
        try {
            JsonNode validator = mapper.readTree(Files.readString(cacheDir.resolve("etag")));
            if (validator != null && uri.toString().equals(validator.path("url").asText())
                    && cachedVersion.equals(validator.path("version").asText())) {
                String etag = validator.path("etag").asText(null);
                return etag != null && !etag.isBlank() ? etag : null;
            }
        } catch (IOException e) {
            // Missing or malformed JSON is not a usable validator.
        }
        // Valid JSON scalars (including strong legacy ETags) and mismatched bindings also require a fresh check.
        return null;
    }

    private void saveValidator(Path cacheDir, URI uri, Manifest manifest) {
        try {
            if (manifest.etag() == null) {
                Files.deleteIfExists(cacheDir.resolve("etag"));
            } else {
                String validator = mapper.createObjectNode()
                        .put("url", uri.toString())
                        .put("version", manifest.version())
                        .put("etag", manifest.etag()).toString();
                writeAtomically(cacheDir.resolve("etag"), validator);
            }
        } catch (IOException e) {
            LOG.warn("Cannot save index manifest validator: {}", e.toString());
        }
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Set<PosixFilePermission> permissions = null;
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                permissions = view.readAttributes().permissions();
            }
        }
        // A regular new file honors the process umask; createTempFile would restrict POSIX readers to the owner.
        Path pending
                = Files.createFile(target.resolveSibling(target.getFileName() + "-" + UUID.randomUUID() + ".part"));
        try {
            Files.writeString(pending, content);
            // Creation applies umask; preserve explicitly configured permissions on an existing metadata file.
            if (permissions != null) {
                Files.setPosixFilePermissions(pending, permissions);
            }
            moveAtomically(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    // Kept separate to inject a failed filesystem replacement in recovery tests.
    void moveAtomically(Path source, Path target) throws IOException {
        // Metadata must never disappear or truncate; a version directory must never be installed partially.
        // Plain moves do not guarantee either invariant, so activation requires atomic filesystem moves.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void downloadAndUnpack(URI manifestUri, Manifest manifest, Path cacheDir, Path versionDir)
            throws IOException {
        final URI assetUri;
        try {
            assetUri = manifestUri.resolve(manifest.asset());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid index asset URI: " + manifest.asset(), e);
        }
        LOG.info("Downloading knowledge index {} from {} ...", manifest.version(), assetUri);

        Path zipFile = Files.createTempFile(cacheDir, "index-", ".zip.part");
        Path partDir = cacheDir.resolve(".index-" + UUID.randomUUID() + ".part");
        boolean partCreated = false;
        try {
            String actualSha = downloadTo(assetUri, zipFile);
            if (!actualSha.equalsIgnoreCase(manifest.sha256())) {
                throw new IOException(
                        "Index archive sha256 mismatch: expected " + manifest.sha256()
                                      + " but downloaded " + actualSha);
            }

            Files.createDirectory(partDir);
            partCreated = true;
            unzip(zipFile, partDir);
            moveAtomically(partDir, versionDir);
        } finally {
            Files.deleteIfExists(zipFile);
            if (partCreated) {
                deleteRecursively(partDir);
            }
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Index download interrupted from " + uri, e);
        } catch (Exception e) {
            throw new IOException("Index download failed from " + uri, e);
        }
    }

    private InputStream openStream(URI uri) throws IOException, InterruptedException {
        if ("file".equals(uri.getScheme())) {
            return Files.newInputStream(Path.of(uri));
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(assetTimeout).GET().build();
        HttpResponse<InputStream> response = send(request);
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Index download returned HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + request.timeout().orElseThrow().toNanos();
        return httpClient.send(request, info -> HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofPublisher(), publisher -> new TimedBody(publisher, deadline)));
    }

    /** Bounded HTTP buffering; only the resolving thread reads or writes index files. */
    private static final class TimedBody extends InputStream implements Flow.Subscriber<List<ByteBuffer>> {
        private static final List<ByteBuffer> END = List.of(ByteBuffer.allocate(0));
        private final ArrayBlockingQueue<List<ByteBuffer>> buffers = new ArrayBlockingQueue<>(2);
        private final long deadline;
        private Iterator<ByteBuffer> current = List.<ByteBuffer>of().iterator();
        private ByteBuffer buffer = ByteBuffer.allocate(0);
        private Flow.Subscription subscription;
        private volatile Throwable failure;
        private boolean closed;
        private boolean requestNext;
        private boolean ended;

        TimedBody(Flow.Publisher<List<ByteBuffer>> publisher, long deadline) {
            this.deadline = deadline;
            publisher.subscribe(this);
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription next) {
            if (closed) {
                next.cancel();
            } else {
                subscription = next;
                next.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            buffers.add(item);
        }

        @Override
        public void onError(Throwable error) {
            failure = error;
            buffers.add(END);
        }

        @Override
        public void onComplete() {
            buffers.add(END);
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Index HTTP response interrupted");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new HttpTimeoutException("Index HTTP response body timed out");
                }
                if (buffer.hasRemaining()) {
                    int count = Math.min(length, buffer.remaining());
                    buffer.get(bytes, offset, count);
                    return count;
                }
                if (current.hasNext()) {
                    buffer = current.next();
                } else if (ended) {
                    return -1;
                } else {
                    if (requestNext) {
                        subscription.request(1);
                    }
                    final List<ByteBuffer> next;
                    try {
                        next = buffers.poll(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Index HTTP response interrupted", e);
                    }
                    if (next == null) {
                        throw new HttpTimeoutException("Index HTTP response body timed out");
                    }
                    if (next == END) {
                        if (failure != null) {
                            throw new IOException("Index HTTP response failed", failure);
                        }
                        ended = true;
                    } else {
                        current = next.iterator();
                        requestNext = true;
                    }
                }
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (subscription != null) {
                subscription.cancel();
            }
        }
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

    /** Called only while holding the update lock, so matching staging files belong to interrupted attempts. */
    private void cleanupStaging(Path cacheDir) {
        try (var entries = Files.list(cacheDir)) {
            entries.filter(IndexDownloader::isStaging)
                    .forEach(IndexDownloader::deleteRecursively);
        } catch (IOException e) {
            LOG.warn("Cannot clean interrupted index downloads: {}", e.toString());
        }
    }

    private static boolean isStaging(Path path) {
        String name = path.getFileName().toString();
        if (STAGING_NAME.matcher(name).matches()) {
            return true;
        }
        if (name.endsWith(".part") && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                validateVersion(name.substring(0, name.length() - ".part".length()));
                return true;
            } catch (IOException e) {
                // Unknown directories and symlinks are not abandoned legacy downloads.
            }
        }
        return false;
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
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
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
