package io.github.luigidemasi.camelkit.knowledge.indexer;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads GitHub repository snapshots as ZIP archives for document indexing.
 * Uses Apache HttpClient 5 for reliable large file downloads with resume support.
 *
 * GitHub ZIP URLs:
 *   Branch: https://github.com/{org}/{repo}/archive/refs/heads/{branch}.zip
 *   Tag:    https://github.com/{org}/{repo}/archive/refs/tags/{tag}.zip
 */
public class GitRepoFetcher {

    private static final int MAX_RETRIES = 5;
    private static final int BUFFER_SIZE = 65536;

    private final Path baseDir;
    private final CloseableHttpClient httpClient;

    public GitRepoFetcher(Path baseDir) {
        this.baseDir = baseDir;
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(30))
                        .setResponseTimeout(Timeout.ofMinutes(10))
                        .build())
                .build();
    }

    /**
     * Download and extract a repo snapshot at a specific branch or tag.
     * If already extracted, returns the cached directory.
     */
    public Path fetchRepo(String repoUrl, String refName, String localName) throws IOException {
        Path repoDir = baseDir.resolve(localName);

        if (Files.isDirectory(repoDir) && hasFiles(repoDir)) {
            System.out.printf("  Cache hit: %s%n", localName);
            System.out.flush();
            return repoDir;
        }

        String baseUrl = repoUrl.replaceAll("\\.git$", "");

        // Try tag first for version-like refs, fall back to branch
        String zipUrl;
        if (refName.matches("\\d+\\..*") || refName.matches(".*-\\d+\\.\\d+\\.\\d+$")) {
            zipUrl = resolveUrl(baseUrl, refName);
        } else {
            zipUrl = baseUrl + "/archive/refs/heads/" + refName + ".zip";
        }

        Path zipFile = baseDir.resolve(localName + ".zip");
        long start = System.currentTimeMillis();

        // Download with resume + retry
        downloadWithRetry(zipUrl, zipFile, localName);

        // Extract
        System.out.printf("  Extracting %s ...%n", localName);
        System.out.flush();
        Files.createDirectories(repoDir);
        int fileCount = extractZip(zipFile, repoDir);

        Files.deleteIfExists(zipFile);

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        System.out.printf("  Done: %s — %d files (%ds)%n", localName, fileCount, elapsed);
        System.out.flush();

        return repoDir;
    }

    /**
     * Resolve whether ref is a tag or branch by trying tag URL first (HEAD request).
     */
    private String resolveUrl(String baseUrl, String refName) throws IOException {
        String tagUrl = baseUrl + "/archive/refs/tags/" + refName + ".zip";
        HttpHead head = new HttpHead(tagUrl);
        try {
            int status = httpClient.execute(head, ClassicHttpResponse::getCode);
            if (status >= 200 && status < 400) return tagUrl;
        } catch (Exception ignored) {}

        String branchUrl = baseUrl + "/archive/refs/heads/" + refName + ".zip";
        System.out.printf("  Tag not found, using branch URL%n");
        return branchUrl;
    }

    /**
     * Download a file with retry + resume. On failure, keeps the partial file
     * and resumes from where it stopped using HTTP Range header.
     */
    private void downloadWithRetry(String url, Path target, String label) throws IOException {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long existingBytes = Files.exists(target) ? Files.size(target) : 0;

            HttpGet get = new HttpGet(url);
            if (existingBytes > 0) {
                get.setHeader("Range", "bytes=" + existingBytes + "-");
                System.out.printf("  Resuming %s from %s ...%n", label, formatSize(existingBytes));
            } else {
                System.out.printf("  Downloading %s ...%n", url);
            }
            System.out.flush();

            try {
                httpClient.execute(get, response -> {
                    int status = response.getCode();

                    if (status == 416) {
                        // Range not satisfiable — already complete
                        System.out.printf("  %s already fully downloaded%n", label);
                        return null;
                    }

                    if (status != 200 && status != 206) {
                        throw new IOException("HTTP " + status);
                    }

                    boolean resuming = (status == 206);
                    long contentLength = response.getEntity().getContentLength();
                    long totalBytes = resuming && contentLength > 0
                            ? existingBytes + contentLength
                            : contentLength;

                    try (InputStream in = response.getEntity().getContent()) {
                        downloadWithProgress(in, target, existingBytes, totalBytes, label, resuming);
                    }
                    return null;
                });
                return; // success

            } catch (IOException e) {
                long partialSize = Files.exists(target) ? Files.size(target) : 0;
                if (attempt == MAX_RETRIES) {
                    Files.deleteIfExists(target);
                    throw new IOException("Failed to download " + url +
                            " after " + MAX_RETRIES + " attempts: " + e.getMessage(), e);
                }
                System.out.printf("%n  WARN: Download failed at %s (attempt %d/%d): %s — resuming...%n",
                        formatSize(partialSize), attempt, MAX_RETRIES, e.getMessage());
                System.out.flush();
            }
        }
    }

    /**
     * Stream download to file with progress bar. Supports appending for resume.
     */
    private void downloadWithProgress(InputStream in, Path target, long alreadyDownloaded,
                                       long totalBytes, String label, boolean append) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long downloaded = alreadyDownloaded;
        int barWidth = 30;
        long lastPrint = 0;

        var openOptions = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};

        try (OutputStream out = Files.newOutputStream(target, openOptions)) {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                long now = System.currentTimeMillis();
                if (now - lastPrint >= 200) {
                    lastPrint = now;
                    printProgress(label, downloaded, totalBytes, barWidth);
                }
            }
        }

        printProgress(label, downloaded, totalBytes, barWidth);
        System.out.println();
        System.out.flush();
    }

    private void printProgress(String label, long downloaded, long totalBytes, int barWidth) {
        String dlStr = formatSize(downloaded);
        if (totalBytes > 0) {
            double pct = (double) downloaded / totalBytes;
            int filled = (int) (pct * barWidth);
            String bar = "=".repeat(Math.min(filled, barWidth))
                    + " ".repeat(Math.max(barWidth - filled, 0));
            System.out.printf("\r  %-20s %8s / %8s  [%s]  %3.0f%%",
                    label, dlStr, formatSize(totalBytes), bar, pct * 100);
        } else {
            System.out.printf("\r  %-20s %8s downloaded", label, dlStr);
        }
        System.out.flush();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * Extract ZIP, stripping the top-level directory that GitHub adds.
     */
    private int extractZip(Path zipFile, Path targetDir) throws IOException {
        int fileCount = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            String stripPrefix = null;

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (stripPrefix == null) {
                    int slash = name.indexOf('/');
                    stripPrefix = slash > 0 ? name.substring(0, slash + 1) : "";
                }

                String relativePath = name.startsWith(stripPrefix)
                        ? name.substring(stripPrefix.length()) : name;
                if (relativePath.isEmpty()) continue;

                Path target = targetDir.resolve(relativePath);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target);
                    fileCount++;
                }
                zis.closeEntry();
            }
        }
        return fileCount;
    }

    public Path refreshRepo(String repoUrl, String refName, String localName) throws IOException {
        Path repoDir = baseDir.resolve(localName);
        if (Files.isDirectory(repoDir)) deleteDirectory(repoDir);
        return fetchRepo(repoUrl, refName, localName);
    }

    private boolean hasFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return stream.iterator().hasNext();
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    public List<Path> listFiles(Path dir, String glob) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) result.add(path);
            }
        }
        return result;
    }

    public List<Path> listFilesRecursive(Path dir, String glob) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> matchesGlob(p.getFileName().toString(), glob))
                    .forEach(result::add);
        }
        return result;
    }

    private boolean matchesGlob(String name, String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*");
        return name.matches(regex);
    }
}
