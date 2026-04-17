package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsciidocConverterTest {

    @Test
    void convertsSimpleAsciidoc() {
        AsciidocConverter converter = new AsciidocConverter();
        String adoc = """
                = My Title

                == Section One

                Some content here.

                == Section Two

                More content.
                """;

        String html = converter.toHtml(adoc);

        // With headerFooter(false), the document title is not included in body content
        // Only section headings and content are rendered
        assertTrue(html.contains("Section One"), "Should contain section heading");
        assertTrue(html.contains("Some content here"), "Should contain body text");
        assertTrue(html.contains("Section Two"), "Should contain second section");
        assertTrue(html.contains("More content"), "Should contain second section content");
    }

    @Test
    void handlesComponentDocFormat() {
        AsciidocConverter converter = new AsciidocConverter();
        String adoc = """
                = Kafka
                :doctitle: Kafka
                :shortname: kafka
                :artifactid: camel-kafka

                *Since Camel 2.13*

                The Kafka component is used for communicating with Apache Kafka message broker.

                == URI Format

                ----
                kafka:topic[?options]
                ----
                """;

        String html = converter.toHtml(adoc);

        assertTrue(html.contains("Since Camel 2.13"), "Should contain version info");
        assertTrue(html.contains("Apache Kafka message broker"), "Should contain description");
        assertTrue(html.contains("URI Format"), "Should contain section heading");
        assertTrue(html.contains("kafka:topic"), "Should contain code block content");
    }
}
