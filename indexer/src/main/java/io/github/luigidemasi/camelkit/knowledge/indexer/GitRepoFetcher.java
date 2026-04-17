package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Downloads GitHub repository snapshots as ZIP archives for document indexing.
 * Uses curl for reliable large downloads (Java's HttpClient fails on large
 * GitHub ZIPs with stream resets and EOF errors).
 *
 * GitHub ZIP URLs:
 *   Branch: https://github.com/{org}/{repo}/archive/refs/heads/{branch}.zip
 *   Tag:    https://github.com/{org}/{repo}/archive/refs/tags/{tag}.zip
 */
public class GitRepoFetcher {

    private final Path baseDir;

    public GitRepoFetcher(Path baseDir) {
        this.baseDir = baseDir;
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
            zipUrl = baseUrl + "/archive/refs/tags/" + refName + ".zip";
        } else {
            zipUrl = baseUrl + "/archive/refs/heads/" + refName + ".zip";
        }

        Path zipFile = baseDir.resolve(localName + ".zip");
        long start = System.currentTimeMillis();

        // Download with curl — reliable for large files, built-in progress bar and resume
        System.out.printf("  Downloading %s%n", zipUrl);
        System.out.flush();

        int exitCode = runCurl(zipUrl, zipFile);

        if (exitCode != 0 && zipUrl.contains("/refs/tags/")) {
            // Fallback: try as branch
            zipUrl = baseUrl + "/archive/refs/heads/" + refName + ".zip";
            System.out.printf("  Tag not found, trying branch: %s%n", zipUrl);
            System.out.flush();
            Files.deleteIfExists(zipFile);
            exitCode = runCurl(zipUrl, zipFile);
        }

        if (exitCode != 0) {
            Files.deleteIfExists(zipFile);
            throw new IOException("Failed to download " + zipUrl + " (curl exit " + exitCode + ")");
        }

        // Extract ZIP — GitHub ZIPs have a top-level directory like "camel-camel-4.14.x/"
        System.out.printf("  Extracting %s ...%n", localName);
        System.out.flush();
        Files.createDirectories(repoDir);
        int fileCount = extractZip(zipFile, repoDir);

        Files.deleteIfExists(zipFile);

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        System.out.printf("  Extracted %s: %d files (%ds)%n", localName, fileCount, elapsed);
        System.out.flush();

        return repoDir;
    }

    /**
     * Download a file using curl with progress bar and resume support.
     * curl handles: redirects, chunked encoding, large files, resume (-C -).
     */
    private int runCurl(String url, Path output) throws IOException {
        // -L: follow redirects
        // -C -: resume from where it left off (if partial file exists)
        // --retry 3: retry on transient failures
        // --retry-delay 2: wait 2s between retries
        // -f: fail on HTTP errors (4xx/5xx)
        // --progress-bar: show progress
        List<String> cmd = List.of(
                "curl", "-L", "-C", "-",
                "--retry", "3",
                "--retry-delay", "2",
                "-f",
                "--progress-bar",
                "-o", output.toString(),
                url
        );

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(baseDir.toFile())
                    .inheritIO();  // stream progress bar directly to terminal
            Process process = pb.start();
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    /**
     * Extract ZIP, stripping the top-level directory that GitHub adds.
     */
    private int extractZip(Path zipFile, Path targetDir) throws IOException {
        int fileCount = 0;
        try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            String stripPrefix = null;

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (stripPrefix == null) {
                    int slash = name.indexOf('/');
                    if (slash > 0) {
                        stripPrefix = name.substring(0, slash + 1);
                    } else {
                        stripPrefix = "";
                    }
                }

                String relativePath = name.startsWith(stripPrefix)
                        ? name.substring(stripPrefix.length())
                        : name;

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
