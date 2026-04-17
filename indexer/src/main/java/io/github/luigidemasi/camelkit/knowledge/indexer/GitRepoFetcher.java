package io.github.luigidemasi.camelkit.knowledge.indexer;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Clones Git repositories for document indexing using JGit.
 * Shallow clones (depth=1) to minimize download size.
 *
 * Cached repos are reused across runs — delete the repos directory to force refresh.
 * No git-pull on shallow clones (JGit handles that poorly).
 */
public class GitRepoFetcher {

    private final Path baseDir;

    public GitRepoFetcher(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Clone a repo at a specific branch/tag.
     * Returns cached directory if already cloned.
     */
    public Path fetchRepo(String repoUrl, String refName, String localName) throws IOException {
        Path repoDir = baseDir.resolve(localName);

        if (Files.isDirectory(repoDir) && hasFiles(repoDir)) {
            System.out.printf("  Cache hit: %s%n", localName);
            System.out.flush();
            return repoDir;
        }

        // Delete any partial/corrupted clone
        if (Files.exists(repoDir)) {
            deleteDirectory(repoDir);
        }

        System.out.printf("  Cloning %s (%s) ...%n", repoUrl, refName);
        System.out.flush();

        long start = System.currentTimeMillis();
        Files.createDirectories(repoDir.getParent());

        try {
            CloneCommand clone = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(repoDir.toFile())
                    .setBranch(refName)
                    .setDepth(1)
                    .setCloneAllBranches(false)
                    .setProgressMonitor(new ConsoleProgressMonitor(localName));

            try (Git git = clone.call()) {
                // clone complete
            }

            long elapsed = (System.currentTimeMillis() - start) / 1000;
            long fileCount = countFiles(repoDir);
            System.out.printf("  Done: %s — %d files (%ds)%n", localName, fileCount, elapsed);
            System.out.flush();

        } catch (GitAPIException e) {
            // Clean up failed clone
            if (Files.exists(repoDir)) {
                deleteDirectory(repoDir);
            }
            throw new IOException("Failed to clone " + repoUrl + " at " + refName + ": " + e.getMessage(), e);
        }

        return repoDir;
    }

    /**
     * JGit progress monitor that prints clone progress to console.
     */
    private static class ConsoleProgressMonitor implements ProgressMonitor {
        private final String label;
        private String currentTask;
        private int totalWork;
        private int completed;

        ConsoleProgressMonitor(String label) {
            this.label = label;
        }

        @Override
        public void start(int totalTasks) {}

        @Override
        public void beginTask(String title, int total) {
            this.currentTask = title;
            this.totalWork = total;
            this.completed = 0;
            if (total > 0) {
                System.out.printf("    %s: %s (0/%d)%n", label, title, total);
            } else {
                System.out.printf("    %s: %s%n", label, title);
            }
            System.out.flush();
        }

        @Override
        public void update(int work) {
            completed += work;
            if (totalWork > 0 && completed % Math.max(totalWork / 10, 1) == 0) {
                int pct = (int) ((100.0 * completed) / totalWork);
                System.out.printf("    %s: %s (%d/%d) %d%%%n",
                        label, currentTask, completed, totalWork, pct);
                System.out.flush();
            }
        }

        @Override
        public void endTask() {}

        @Override
        public boolean isCancelled() { return false; }

        @Override
        public void showDuration(boolean enabled) {}
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

    private long countFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
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
