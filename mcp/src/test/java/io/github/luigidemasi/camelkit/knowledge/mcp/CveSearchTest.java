package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.util.List;

import io.github.luigidemasi.camelkit.knowledge.schema.KnowledgeDocument;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the CVE search path against indexer/searcher doc_type drift: the documents indexed here replicate the exact
 * shape {@code ApacheCamelDomain.buildCveChunks()} produces ({@code docType="cve"}), plus a legacy
 * {@code docType="errata"} document — both eras must be found. This test exists because the searcher once demanded
 * {@code doc_type:"errata"} while the indexer wrote {@code "cve"}, making every CVE search silently return zero
 * results.
 */
class CveSearchTest {

    private static IndexSearcher searcher;

    @BeforeAll
    static void setUp() throws Exception {
        Directory dir = new ByteBuffersDirectory();
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            // Shape produced by ApacheCamelDomain.buildCveChunks()
            KnowledgeDocument cveDoc = new KnowledgeDocument("apache-camel-cve-cve-2025-29891", "apache_camel")
                    .source("apache-camel")
                    .docType("cve")
                    .sectionTitle("CVE-2025-29891: Camel Message Header Injection")
                    .content("CVE-2025-29891 (HIGH): header injection through request parameters. "
                             + "Fixed in: 3.22.4, 4.8.5, 4.10.2")
                    .severity("HIGH")
                    .cveId("CVE-2025-29891")
                    .fixedInVersion("4.10.2")
                    .fixedInVersion("4.8.5");
            writer.addDocument(cveDoc.build());

            // Legacy Red Hat erratum shape
            KnowledgeDocument errataDoc
                    = new KnowledgeDocument("rh-build-camel-errata-RHSA-2025-1234", "rh_build_camel")
                            .source("rh-build-camel")
                            .docType("errata")
                            .sectionTitle("RHSA-2025:1234 Security Advisory")
                            .content("Security update fixing CVE-2025-29891 in Red Hat build of Apache Camel")
                            .erratumId("RHSA-2025:1234")
                            .advisoryType("Security Advisory")
                            .severity("Important")
                            .cveId("CVE-2025-29891")
                            .fixedInVersion("4.8.5");
            writer.addDocument(errataDoc.build());

            writer.commit();
        }
        searcher = new IndexSearcher(DirectoryReader.open(dir));
    }

    @Test
    void searchByCveFindsIndexerShapedCveDocuments() throws Exception {
        List<LuceneSearchService.ErrataSearchResult> results
                = LuceneSearchService.searchByCve(searcher, "CVE-2025-29891", 20);

        assertEquals(2, results.size(), "Both the Apache cve doc and the legacy erratum must match");
        assertTrue(results.stream().anyMatch(r -> "apache-camel-cve-cve-2025-29891".equals(r.id())),
                "The ApacheCamelDomain-shaped cve document must be found");
        assertTrue(results.stream().anyMatch(r -> "RHSA-2025:1234".equals(r.erratumId())),
                "The legacy erratum must be found");
    }

    @Test
    void searchByCveReturnsEmptyForUnknownCve() throws Exception {
        assertTrue(LuceneSearchService.searchByCve(searcher, "CVE-1999-0001", 20).isEmpty());
    }
}
