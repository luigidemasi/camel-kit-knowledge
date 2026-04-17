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
     * Clone a repo at a specific branch/tag, or update if already cloned.
     *
     * <ul>
     *   <li>If repo exists with .git → fetch + hard reset to latest (fast, only downloads delta)</li>
     *   <li>If repo exists without .git (corrupted) → delete and re-clone</li>
     *   <li>If repo doesn't exist → fresh shallow clone</li>
     * </ul>
     */
    public Path fetchRepo(String repoUrl, String refName, String localName) throws IOException {
        Path repoDir = baseDir.resolve(localName);

        if (Files.isDirectory(repoDir.resolve(".git"))) {
            // Existing clone — fetch latest and reset working tree
            return updateRepo(repoDir, refName, localName);
        }

        // Delete any partial/corrupted directory (no .git = not a valid repo)
        if (Files.exists(repoDir)) {
            deleteDirectory(repoDir);
        }

        return cloneRepo(repoUrl, refName, localName, repoDir);
    }

    /**
     * Update an existing repo by fetching latest changes and resetting the working tree.
     * Much faster than a full clone — only downloads the delta.
     */
    private Path updateRepo(Path repoDir, String refName, String localName) throws IOException {
        System.out.printf("  Updating %s (%s) ...%n", localName, refName);
        System.out.flush();

        long start = System.currentTimeMillis();

        try (Git git = Git.open(repoDir.toFile())) {
            // Fetch latest from remote (works on shallow clones)
            git.fetch()
                    .setRemote("origin")
                    .setProgressMonitor(new ConsoleProgressMonitor(localName))
                    .call();

            // Reset working tree to the fetched branch/tag head
            git.reset()
                    .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef("origin/" + refName)
                    .call();

            long elapsed = (System.currentTimeMillis() - start) / 1000;
            System.out.printf("  Updated: %s (%ds)%n", localName, elapsed);
            System.out.flush();

        } catch (GitAPIException e) {
            // Update failed — fall back to fresh clone
            System.out.printf("  WARN: Update failed for %s: %s — re-cloning%n",
                    localName, e.getMessage());
            deleteDirectory(repoDir);
            return cloneRepo(null, refName, localName, repoDir);
        }

        return repoDir;
    }

    /**
     * Fresh shallow clone of a repo.
     */
    private Path cloneRepo(String repoUrl, String refName, String localName, Path repoDir) throws IOException {
        // If repoUrl is null (fallback from failed update), reconstruct from remote
        if (repoUrl == null) {
            throw new IOException("Cannot re-clone " + localName + " — original URL unknown");
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
            if (Files.exists(repoDir)) deleteDirectory(repoDir);
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
