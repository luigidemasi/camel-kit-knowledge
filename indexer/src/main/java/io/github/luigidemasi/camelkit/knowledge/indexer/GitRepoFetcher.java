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

    public GitRepoFetcher(Path baseDir) {
        this.baseDir = baseDir;
        this.httpClient = HttpClient.newBuilder()
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

        System.out.printf("  Downloading %s ...%n", zipUrl);
        System.out.flush();

        long start = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(zipUrl))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + zipUrl, e);
        }

        if (response.statusCode() == 404 && zipUrl.contains("/refs/tags/")) {
            // Fallback: try as branch
            zipUrl = baseUrl + "/archive/refs/heads/" + refName + ".zip";
            System.out.printf("  Tag not found, trying branch: %s ...%n", zipUrl);
            System.out.flush();
            request = HttpRequest.newBuilder().uri(URI.create(zipUrl)).GET().build();
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted: " + zipUrl, e);
            }
        }

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download " + zipUrl + " (HTTP " + response.statusCode() + ")");
        }

        // Download with progress bar, then extract
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        Path zipFile = baseDir.resolve(localName + ".zip");
        downloadWithProgress(response.body(), zipFile, contentLength, localName);

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
     * Download an InputStream to a file with a progress bar.
     * Shows: "  camel-4.14:  45.2 MB / 120.3 MB  [===========          ]  37%"
     * If content length is unknown, shows bytes downloaded only.
     */
    private void downloadWithProgress(InputStream in, Path target, long totalBytes, String label) throws IOException {
        byte[] buffer = new byte[65536];
        long downloaded = 0;
        int barWidth = 30;
        long lastPrint = 0;

        try (OutputStream out = Files.newOutputStream(target)) {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                // Update progress at most every 200ms
                long now = System.currentTimeMillis();
                if (now - lastPrint >= 200) {
                    lastPrint = now;
                    printProgress(label, downloaded, totalBytes, barWidth);
                }
            }
        }

        // Final progress line
        printProgress(label, downloaded, totalBytes, barWidth);
        System.out.println(); // newline after progress bar
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
