package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Clones or pulls Git repositories for document indexing.
 * Uses native git via ProcessBuilder — JGit's shallow clone is
 * 10-30x slower on large repos like apache/camel.
 */
public class GitRepoFetcher {

    private final Path baseDir;

    public GitRepoFetcher(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Clone a repo at a specific branch/tag, or pull if already cloned.
     *
     * @param repoUrl   Git remote URL (HTTPS)
     * @param refName   Branch name (e.g., "main", "camel-4.14.x") or tag (e.g., "camel-4.19.0")
     * @param localName Local directory name under baseDir
     * @return Path to the cloned repo working directory
     */
    public Path fetchRepo(String repoUrl, String refName, String localName) throws IOException {
        Path repoDir = baseDir.resolve(localName);

        if (Files.isDirectory(repoDir.resolve(".git"))) {
            // Already cloned — pull latest
            System.out.printf("  Pulling %s (%s)...%n", localName, refName);
            try {
                runGit(repoDir, "git", "pull", "--ff-only");
            } catch (IOException e) {
                System.out.printf("  WARN: Failed to pull %s: %s (using cached)%n",
                        localName, e.getMessage());
            }
        } else {
            // Fresh clone — shallow, single branch
            System.out.printf("  Cloning %s (%s)...%n", repoUrl, refName);
            Files.createDirectories(repoDir.getParent());
            runGit(baseDir, "git", "clone",
                    "--depth", "1",
                    "--single-branch",
                    "--branch", refName,
                    repoUrl,
                    localName);
        }

        return repoDir;
    }

    private void runGit(Path workDir, String... command) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("git command failed (exit " + exitCode + "): " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted", e);
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
