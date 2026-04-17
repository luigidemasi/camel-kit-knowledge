package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads GitHub repository snapshots as ZIP archives for document indexing.
 * Pure Java — no git binary or JGit needed.
 *
 * GitHub ZIP URLs:
 *   Branch: https://github.com/{org}/{repo}/archive/refs/heads/{branch}.zip
 *   Tag:    https://github.com/{org}/{repo}/archive/refs/tags/{tag}.zip
 *
 * ZIP archives are faster than git clone (no protocol negotiation, no .git directory)
 * and contain only the working tree files we need for indexing.
 */
public class GitRepoFetcher {

    private final Path baseDir;
    private final HttpClient httpClient;

    private static final int MAX_RETRIES = 3;

    public GitRepoFetcher(Path baseDir) {
        this.baseDir = baseDir;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)  // HTTP/2 gets RST_STREAM on large GitHub downloads
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Download and extract a repo snapshot at a specific branch or tag.
     * If already extracted, returns the cached directory.
     *
     * @param repoUrl   Git remote URL, e.g., "https://github.com/apache/camel.git"
     * @param refName   Branch (e.g., "main", "camel-4.14.x") or tag (e.g., "camel-4.19.0")
     * @param localName Local directory name under baseDir
     * @return Path to the extracted repo directory
     */
    public Path fetchRepo(String repoUrl, String refName, String localName) throws IOException {
        Path repoDir = baseDir.resolve(localName);

        if (Files.isDirectory(repoDir) && hasFiles(repoDir)) {
            System.out.printf("  Cache hit: %s%n", localName);
            System.out.flush();
            return repoDir;
        }

        // Build GitHub ZIP URL from the git remote URL
        // "https://github.com/apache/camel.git" -> "https://github.com/apache/camel"
        String baseUrl = repoUrl.replaceAll("\\.git$", "");

        // Determine if refName is a tag (contains only version-like chars with dots)
        // or a branch. Tags use /refs/tags/, branches use /refs/heads/
        String zipUrl;
        if (refName.matches("\\d+\\..*") || refName.matches(".*-\\d+\\.\\d+\\.\\d+$")) {
            // Looks like a tag (e.g., "camel-4.19.0", "4.19.0")
            // Try tags first, GitHub will 404 if it's not a tag
            zipUrl = baseUrl + "/archive/refs/tags/" + refName + ".zip";
        } else {
            zipUrl = baseUrl + "/archive/refs/heads/" + refName + ".zip";
        }

        long start = System.currentTimeMillis();
        Path zipFile = baseDir.resolve(localName + ".zip");

        // Resolve tag vs branch URL (only once, before retry loop)
        zipUrl = resolveZipUrl(zipUrl, baseUrl, refName);

        // Download with retry + resume — large files can fail mid-stream
        downloadWithRetry(zipUrl, zipFile, localName);

        // Extract ZIP — GitHub ZIPs have a top-level directory like "camel-camel-4.14.x/"
        // We strip that prefix and extract directly into repoDir
        System.out.printf("  Extracting %s ...%n", localName);
        System.out.flush();
        Files.createDirectories(repoDir);
        int fileCount = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            String stripPrefix = null;

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                // Detect top-level directory prefix from first entry
                if (stripPrefix == null) {
                    int slash = name.indexOf('/');
                    if (slash > 0) {
                        stripPrefix = name.substring(0, slash + 1);
                    } else {
                        stripPrefix = "";
                    }
                }

                // Strip the top-level directory
                String relativePath = name.startsWith(stripPrefix)
                        ? name.substring(stripPrefix.length())
                        : name;

                if (relativePath.isEmpty()) continue;

                Path target = repoDir.resolve(relativePath);

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

        // Clean up zip file
        Files.deleteIfExists(zipFile);

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        System.out.printf("  Extracted %s: %d files (%ds)%n", localName, fileCount, elapsed);
        System.out.flush();

        return repoDir;
    }

    /**
     * Delete and re-download a repo (for forced refresh).
     */
    public Path refreshRepo(String repoUrl, String refName, String localName) throws IOException {
        Path repoDir = baseDir.resolve(localName);
        if (Files.isDirectory(repoDir)) {
            deleteDirectory(repoDir);
        }
        return fetchRepo(repoUrl, refName, localName);
    }

    /**
     * Resolve whether the ref is a tag or branch by trying tag URL first.
     * If GitHub returns 404 on the tag URL, falls back to branch URL.
     */
    private String resolveZipUrl(String tagUrl, String baseUrl, String refName) throws IOException {
        if (!tagUrl.contains("/refs/tags/")) return tagUrl;

        try {
            HttpRequest head = HttpRequest.newBuilder()
                    .uri(URI.create(tagUrl))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = httpClient.send(head, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 404) return tagUrl;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted resolving URL", e);
        }

        String branchUrl = baseUrl + "/archive/refs/heads/" + refName + ".zip";
        System.out.printf("  Tag not found, using branch: %s%n", branchUrl);
        return branchUrl;
    }

    /**
     * Download a file with retry + resume support.
     * On failure, keeps the partial file and resumes from where it stopped
     * using HTTP Range header.
     */
    private void downloadWithRetry(String url, Path target, String label) throws IOException {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long existingBytes = Files.exists(target) ? Files.size(target) : 0;

            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET();

                if (existingBytes > 0) {
                    reqBuilder.header("Range", "bytes=" + existingBytes + "-");
                    System.out.printf("  Resuming %s from %s ...%n", label, formatSize(existingBytes));
                } else {
                    System.out.printf("  Downloading %s ...%n", url);
                }
                System.out.flush();

                HttpResponse<InputStream> response = httpClient.send(
                        reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();

                if (status == 416) {
                    // Range not satisfiable — file is already complete
                    System.out.printf("  %s already fully downloaded%n", label);
                    return;
                }

                boolean resuming = (status == 206);
                if (status != 200 && status != 206) {
                    throw new IOException("HTTP " + status);
                }

                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                long totalBytes = resuming
                        ? existingBytes + contentLength
                        : contentLength;

                downloadWithProgress(response.body(), target, existingBytes, totalBytes, label, resuming);
                return; // success

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted: " + url, e);
            } catch (IOException e) {
                // Keep partial file for resume on next attempt
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
     * Download an InputStream to a file with a progress bar.
     * Supports appending for resume.
     */
    private void downloadWithProgress(InputStream in, Path target, long alreadyDownloaded,
                                       long totalBytes, String label, boolean append) throws IOException {
        byte[] buffer = new byte[65536];
        long downloaded = alreadyDownloaded;
        int barWidth = 30;
        long lastPrint = 0;

        var openOptions = append
                ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND}
                : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING};

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
            String bar = "=".repeat(filled) + " ".repeat(barWidth - filled);
            String totalStr = formatSize(totalBytes);
            System.out.printf("\r  %-20s %8s / %8s  [%s]  %3.0f%%",
                    label, dlStr, totalStr, bar, pct * 100);
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

    private boolean hasFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return stream.iterator().hasNext();
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        }
    }

    /**
     * List files matching a glob pattern in a directory (non-recursive).
     */
    public List<Path> listFiles(Path dir, String glob) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    /**
     * Recursively list files matching a glob pattern.
     */
    public List<Path> listFilesRecursive(Path dir, String glob) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return matchesGlob(name, glob);
                    })
                    .forEach(result::add);
        }
        return result;
    }

    private boolean matchesGlob(String name, String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*");
        return name.matches(regex);
    }
}
