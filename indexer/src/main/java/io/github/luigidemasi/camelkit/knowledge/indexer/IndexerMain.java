package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxEmbeddingProvider;
import io.github.luigidemasi.camelkit.knowledge.indexer.domain.ApacheCamelDomain;
import io.github.luigidemasi.camelkit.knowledge.indexer.domain.DocumentDomain;
import io.github.luigidemasi.camelkit.knowledge.schema.KnowledgeFields;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.FSDirectory;
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
        EmbeddingCache embeddingCache = new EmbeddingCache(
                resourcesDir.resolve("apache-camel/embedding-cache"), embeddingProvider.modelId());
        IndexBuilder builder = new IndexBuilder(embeddingProvider, embeddingCache);
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

        // Manifest skeleton for index distribution — the release workflow adds version/sha256/size
        writeManifestSkeleton(outputDir, embeddingProvider.modelId(), total);

        LOG.info("Index built successfully: {} documents in {} domains",
                total, domains.size());
    }

    /**
     * Writes {@code index.json} next to the index with the fields only the build knows (embedding model, doc count,
     * covered versions, build time). The release workflow enriches it with version, sha256, size, and asset name.
     */
    private static void writeManifestSkeleton(Path outputDir, String embeddingModel, int docCount)
            throws IOException {
        TreeSet<String> versions = new TreeSet<>();
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(outputDir))) {
            Terms terms = MultiTerms.getTerms(reader, KnowledgeFields.SOURCE_VERSION);
            if (terms != null) {
                TermsEnum te = terms.iterator();
                while (te.next() != null) {
                    versions.add(te.term().utf8ToString());
                }
            }
        }

        StringBuilder versionArray = new StringBuilder();
        for (String v : versions) {
            if (versionArray.length() > 0) {
                versionArray.append(',');
            }
            versionArray.append('"').append(v).append('"');
        }

        String json = String.format(Locale.ROOT,
                "{%n  \"embeddingModel\": \"%s\",%n  \"docCount\": %d,%n  \"camelVersions\": [%s],%n"
                                                 + "  \"builtAt\": \"%s\"%n}%n",
                embeddingModel, docCount, versionArray, Instant.now());
        Files.writeString(outputDir.getParent().resolve("index.json"), json);
        LOG.info("Manifest skeleton written: {}", outputDir.getParent().resolve("index.json"));
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
