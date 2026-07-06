package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxEmbeddingProvider;
import io.github.luigidemasi.camelkit.knowledge.indexer.domain.ApacheCamelDomain;
import io.github.luigidemasi.camelkit.knowledge.indexer.domain.DocumentDomain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI entry point for building the Lucene knowledge index. Runs during CI/CD to produce the pre-built index artifact.
 *
 * Usage: java -cp indexer.jar io.github.luigidemasi.camelkit.knowledge.indexer.IndexerMain [output-dir]
 *
 * System properties (override automatic path resolution): -Dindex.output=path Output directory for the Lucene index
 * -Dindex.resources=path Indexer resources directory (git repo clones, JIRA/CVE caches) -Dindex.cache=path document
 * cache directory
 *
 */
public class IndexerMain {

    private static final Logger LOG = LoggerFactory.getLogger(IndexerMain.class);

    public static void main(String[] args) throws Exception {
        // Resolve base directories. System properties override automatic resolution.
        Path classesDir = Path.of(
                IndexerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path targetDir = classesDir.getParent();
        Path moduleDir = targetDir.getParent();

        Path outputDir = resolvePath("index.output",
                args.length > 0 ? args[0] : null,
                targetDir.resolve("knowledge-index").toString());

        Path cacheDir = resolvePath("index.cache",
                null,
                targetDir.resolve("doc-cache").toString());

        Path resourcesDir = resolvePath("index.resources",
                null,
                moduleDir.resolve("src/main/resources").toString());

        LOG.info("Building camel-kit knowledge index...");
        LOG.info("Output: {}", outputDir);
        LOG.info("Resources: {}", resourcesDir);
        LOG.info("Cache: {}", cacheDir);

        Files.createDirectories(outputDir);

        List<DocumentDomain> domains = buildDomains(cacheDir, resourcesDir);

        LOG.info("Loading embedding model...");
        OnnxEmbeddingProvider embeddingProvider = new OnnxEmbeddingProvider();
        IndexBuilder builder = new IndexBuilder(embeddingProvider);
        int total = builder.build(outputDir, domains);

        // Write index file manifest for MCP server classpath extraction
        Path manifestPath = outputDir.resolve("INDEX_FILES");
        try (var stream = Files.list(outputDir)) {
            List<String> fileNames = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("INDEX_FILES"))
                    .sorted()
                    .toList();
            Files.write(manifestPath, fileNames);
        }

        LOG.info("Index built successfully: {} documents in {} domains",
                total, domains.size());
    }

    private static Path resolvePath(String sysProp, String argValue, String defaultValue) {
        String value = System.getProperty(sysProp);
        if (value != null && !value.isBlank())
            return Path.of(value).toAbsolutePath();
        if (argValue != null && !argValue.isBlank())
            return Path.of(argValue).toAbsolutePath();
        return Path.of(defaultValue).toAbsolutePath();
    }

    private static List<DocumentDomain> buildDomains(Path cacheDir, Path resourcesDir) throws IOException {
        List<DocumentDomain> domains = new ArrayList<>();

        // Add all registered domains here
        domains.add(new ApacheCamelDomain(cacheDir, resourcesDir));

        return domains;
    }
}
