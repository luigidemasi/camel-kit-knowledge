package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxEmbeddingProvider;
import io.github.luigidemasi.camelkit.knowledge.schema.KnowledgeFields;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * One-shot diagnostic for the in-repo index's vector field. Run with -Dvector.diag=true.
 */
@EnabledIfSystemProperty(named = "vector.diag", matches = "true")
class VectorDiagnosticTest {

    @Test
    void inspectVectors() throws Exception {
        Path indexDir = Paths.get("../index/src/main/resources/knowledge-index").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(indexDir),
                "In-repo index not found at " + indexDir + " — adjust the path if the layout changed");
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider();

        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(indexDir))) {
            int totalVectors = 0;
            for (LeafReaderContext leaf : reader.leaves()) {
                FloatVectorValues values = leaf.reader().getFloatVectorValues(KnowledgeFields.EMBEDDING);
                int size = values != null ? values.size() : 0;
                totalVectors += size;
                if (values != null && size > 0) {
                    values.nextDoc();
                    float[] first = values.vectorValue();
                    double norm = 0;
                    for (float v : first) {
                        norm += v * v;
                    }
                    System.out.printf("leaf docs=%d vectors=%d dim=%d firstNorm=%.4f%n",
                            leaf.reader().maxDoc(), size, values.dimension(), Math.sqrt(norm));
                }
            }
            System.out.printf("TOTAL docs=%d vectors=%d%n", reader.maxDoc(), totalVectors);

            IndexSearcher searcher = new IndexSearcher(reader);
            float[] query = provider.embed("how to configure the Kafka component");
            TopDocs top = searcher.search(
                    new KnnFloatVectorQuery(KnowledgeFields.EMBEDDING, query, 10), 10);
            System.out.println("KNN top10 (no filter):");
            for (ScoreDoc sd : top.scoreDocs) {
                System.out.printf("  [%.4f] %s%n", sd.score,
                        searcher.doc(sd.doc).get(KnowledgeFields.ID));
            }

            // Bulk check: same-text cosine across many chunks, split by text length
            {
                int checked = 0;
                double sumShort = 0, sumLong = 0;
                int nShort = 0, nLong = 0;
                for (LeafReaderContext leaf : reader.leaves()) {
                    FloatVectorValues values = leaf.reader().getFloatVectorValues(KnowledgeFields.EMBEDDING);
                    if (values == null) {
                        continue;
                    }
                    while (values.nextDoc() != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
                            && checked < 40) {
                        var d = leaf.reader().document(values.docID());
                        String content = d.get(KnowledgeFields.CONTENT);
                        String title = d.get(KnowledgeFields.SECTION_TITLE);
                        if (content == null) {
                            continue;
                        }
                        String text = title + " " + content;
                        float[] fresh = provider.embed(text);
                        float[] stored = values.vectorValue();
                        double dot = 0;
                        for (int i = 0; i < stored.length; i++) {
                            dot += stored[i] * fresh[i];
                        }
                        boolean shortText = text.length() < 1500; // well under 512 tokens
                        if (shortText) {
                            sumShort += dot;
                            nShort++;
                        } else {
                            sumLong += dot;
                            nLong++;
                        }
                        checked++;
                    }
                    if (checked >= 40) {
                        break;
                    }
                }
                System.out.printf("SAME-TEXT cosine: short(<1500ch) mean=%.4f (n=%d), long mean=%.4f (n=%d)%n",
                        nShort > 0 ? sumShort / nShort : -1, nShort,
                        nLong > 0 ? sumLong / nLong : -1, nLong);
            }

            // Decisive check: does the stored vector of a chunk match a fresh embedding of its own text?
            var td = searcher.search(new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term(
                            KnowledgeFields.ID,
                            "apache-camel-4.18-component-file-component-introduction")),
                    1);
            if (td.scoreDocs.length > 0) {
                var doc = searcher.doc(td.scoreDocs[0].doc);
                String text = doc.get(KnowledgeFields.SECTION_TITLE) + " " + doc.get(KnowledgeFields.CONTENT);
                float[] fresh = provider.embed(text);

                // find the stored vector by walking the leaf that contains this doc
                int docId = td.scoreDocs[0].doc;
                for (LeafReaderContext leaf : reader.leaves()) {
                    if (docId >= leaf.docBase && docId < leaf.docBase + leaf.reader().maxDoc()) {
                        FloatVectorValues values = leaf.reader().getFloatVectorValues(KnowledgeFields.EMBEDDING);
                        int target = docId - leaf.docBase;
                        while (values.nextDoc() != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS) {
                            if (values.docID() == target) {
                                float[] stored = values.vectorValue();
                                double dot = 0;
                                for (int i = 0; i < stored.length; i++) {
                                    dot += stored[i] * fresh[i];
                                }
                                System.out.printf("STORED-vs-FRESH cosine for same text: %.4f%n", dot);
                                break;
                            }
                        }
                        break;
                    }
                }
            }
        }
    }
}
