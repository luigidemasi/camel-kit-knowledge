package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import io.github.luigidemasi.camelkit.knowledge.embedding.EmbeddingProvider;
import io.github.luigidemasi.camelkit.knowledge.indexer.domain.DocumentChunk;
import io.github.luigidemasi.camelkit.knowledge.indexer.domain.DocumentDomain;
import io.github.luigidemasi.camelkit.knowledge.schema.DomainMetadata;
import io.github.luigidemasi.camelkit.knowledge.schema.KnowledgeDocument;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a Lucene index from a list of document domains. Each domain contributes its chunks and metadata to a single
 * shared index.
 */
public class IndexBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(IndexBuilder.class);

    private final EmbeddingProvider embeddingProvider;

    /** Creates an IndexBuilder without embedding support. */
    public IndexBuilder() {
        this(null);
    }

    /** Creates an IndexBuilder with optional embedding support. */
    public IndexBuilder(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Build the Lucene index at the given path from the provided domains.
     *
     * @param  indexPath directory where the Lucene index will be written
     * @param  domains   list of domains to index
     * @return           total number of documents indexed
     */
    public int build(Path indexPath, List<DocumentDomain> domains) throws IOException, InterruptedException {
        int totalDocs = 0;

        // CREATE (not the default CREATE_OR_APPEND): rebuilding into an existing directory must
        // replace the index, not silently append duplicate documents
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        try (Directory dir = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(dir, config)) {

            // Stamp the embedding model so the MCP server can detect incompatible query-time models
            if (embeddingProvider != null) {
                writer.addDocument(KnowledgeDocument.indexMeta(embeddingProvider.modelId()));
            }

            for (DocumentDomain domain : domains) {
                DomainMetadata meta = domain.metadata();
                LOG.info("Building chunks for domain: {}...", meta.domainId());
                String metaJson = serializeMeta(meta);
                List<DocumentChunk> chunks = domain.buildChunks();
                LOG.info("{}: {} chunks ready, indexing...", meta.domainId(), chunks.size());

                int embeddedCount = 0;
                int chunkCount = 0;
                int totalChunks = chunks.size();
                long startTime = System.currentTimeMillis();
                boolean metaWritten = false;
                for (DocumentChunk chunk : chunks) {
                    KnowledgeDocument doc = new KnowledgeDocument(chunk.id(), meta.domainId())
                            .source(chunk.source())
                            .docType(chunk.docType())
                            .sectionTitle(chunk.sectionTitle())
                            .content(chunk.content());

                    if (chunk.sourceVersion() != null) {
                        doc.sourceVersion(chunk.sourceVersion());
                    }
                    if (chunk.targetVersion() != null) {
                        doc.targetVersion(chunk.targetVersion());
                    }
                    if (chunk.component() != null) {
                        doc.component(chunk.component());
                    }

                    // Runtime (multi-valued)
                    if (chunk.runtimes() != null) {
                        for (String runtime : chunk.runtimes()) {
                            doc.runtime(runtime);
                        }
                    }

                    // JIRA IDs (multi-valued)
                    if (chunk.jiraIds() != null) {
                        for (String jiraId : chunk.jiraIds()) {
                            doc.jiraId(jiraId);
                        }
                    }

                    // Errata-specific structured fields
                    if (chunk.erratumId() != null) {
                        doc.erratumId(chunk.erratumId());
                    }
                    if (chunk.advisoryType() != null) {
                        doc.advisoryType(chunk.advisoryType());
                    }
                    if (chunk.severity() != null) {
                        doc.severity(chunk.severity());
                    }
                    if (chunk.cveIds() != null) {
                        for (String cveId : chunk.cveIds()) {
                            doc.cveId(cveId);
                        }
                    }
                    if (chunk.fixedInVersions() != null) {
                        for (String ver : chunk.fixedInVersions()) {
                            doc.fixedInVersion(ver);
                        }
                    }

                    // Generate embedding if provider is available
                    if (embeddingProvider != null) {
                        String textToEmbed = chunk.sectionTitle() + " " + chunk.content();
                        float[] vector = embeddingProvider.embed(textToEmbed);
                        doc.embedding(vector);
                        embeddedCount++;
                    }

                    // Store domain metadata on the first document only
                    if (!metaWritten) {
                        doc.domainMeta(metaJson);
                        metaWritten = true;
                    }

                    writer.addDocument(doc.build());
                    totalDocs++;
                    chunkCount++;
                    if (chunkCount % 500 == 0 || chunkCount == totalChunks) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        LOG.info("  Indexing progress: {}/{} chunks ({} embedded, {}s elapsed)",
                                chunkCount, totalChunks, embeddedCount, elapsed);
                    }
                }

                LOG.info("  Domain '{}': {} chunks indexed, {} embedded",
                        meta.domainId(), chunks.size(), embeddedCount);
            }

            writer.commit();
        }

        LOG.info("Index built: {} total documents", totalDocs);
        return totalDocs;
    }

    private String serializeMeta(DomainMetadata meta) {
        // Simple JSON serialization without Jackson dependency
        return String.format(Locale.ROOT,
                "{\"domainId\":\"%s\",\"toolName\":\"%s\",\"description\":\"%s\",\"hasComponentField\":%s,\"hasVersionFields\":%s}",
                escape(meta.domainId()),
                escape(meta.toolName()),
                escape(meta.description()),
                meta.hasComponentField(),
                meta.hasVersionFields());
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
