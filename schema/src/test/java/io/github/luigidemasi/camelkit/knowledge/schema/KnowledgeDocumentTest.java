package io.github.luigidemasi.camelkit.knowledge.schema;

import org.apache.lucene.document.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeDocumentTest {

    @Test
    void buildMigrationDocument() {
        Document doc = new KnowledgeDocument("test-id", "apache_camel")
                .source("apache-camel")
                .docType("guide")
                .sourceVersion("4.14")
                .component("camel-kafka")
                .sectionTitle("Camel Kafka configuration")
                .content("The camel-kafka component requires a broker URL.")
                .build();

        assertEquals("test-id", doc.get(KnowledgeFields.ID));
        assertEquals("apache_camel", doc.get(KnowledgeFields.DOMAIN));
        assertEquals("apache-camel", doc.get(KnowledgeFields.SOURCE));
        assertEquals("guide", doc.get(KnowledgeFields.DOC_TYPE));
        assertEquals("4.14", doc.get(KnowledgeFields.SOURCE_VERSION));
        assertNull(doc.get(KnowledgeFields.TARGET_VERSION));
        assertEquals("camel-kafka", doc.get(KnowledgeFields.COMPONENT));
        assertEquals("Camel Kafka configuration", doc.get(KnowledgeFields.SECTION_TITLE));
        assertEquals("The camel-kafka component requires a broker URL.", doc.get(KnowledgeFields.CONTENT));
    }

    @Test
    void domainMetadataMigrationFactory() {
        DomainMetadata meta = DomainMetadata.migration(
                "apache_camel_migration",
                "camel_migration",
                "Search Apache Camel migration docs");

        assertEquals("apache_camel_migration", meta.domainId());
        assertEquals("camel_migration", meta.toolName());
        assertTrue(meta.hasComponentField());
        assertTrue(meta.hasVersionFields());
    }

    @Test
    void domainMetadataGeneralFactory() {
        DomainMetadata meta = DomainMetadata.general(
                "best_practices",
                "camel_best_practices",
                "Search Camel best practices");

        assertFalse(meta.hasComponentField());
        assertFalse(meta.hasVersionFields());
    }
}
