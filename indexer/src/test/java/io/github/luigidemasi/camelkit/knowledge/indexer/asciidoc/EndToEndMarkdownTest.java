package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.nio.file.Files;
import java.nio.file.Path;

import io.github.luigidemasi.camelkit.knowledge.indexer.parser.AsciidocConverter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.*;

class EndToEndMarkdownTest {

    private static final Path REPO_ROOT = Path.of(
            "src/main/resources/apache-camel/repos/camel-4.18");

    static boolean repoExists() {
        return Files.isDirectory(REPO_ROOT.resolve("docs/components/modules/ROOT/partials"));
    }

    @Test
    @EnabledIf("repoExists")
    void kafkaComponentRendersWithOptionTables() throws Exception {
        AsciidocConverter converter = new AsciidocConverter();

        Path adocFile = REPO_ROOT.resolve(
                "components/camel-kafka/src/main/docs/kafka-component.adoc");
        Path partialsDir = REPO_ROOT.resolve("docs/components/modules/ROOT/partials");
        Path examplesDir = REPO_ROOT.resolve("docs/components/modules/ROOT/examples");

        assertTrue(Files.exists(adocFile), "kafka-component.adoc should exist");
        assertTrue(Files.isDirectory(partialsDir), "partials dir should exist");
        assertTrue(Files.isDirectory(examplesDir), "examples dir should exist");

        String markdown = converter.toMarkdown(adocFile, partialsDir, examplesDir);

        assertNotNull(markdown);
        assertFalse(markdown.isEmpty(), "Markdown output should not be empty");

        // Verify headings
        assertTrue(markdown.contains("Component Options"),
                "Should contain Component Options heading");
        assertTrue(markdown.contains("Endpoint Options"),
                "Should contain Endpoint Options heading");

        // Verify option counts are resolved (not literal {propertycount})
        assertFalse(markdown.contains("{propertycount}"),
                "Attribute {propertycount} should be resolved");
        assertFalse(markdown.contains("{pathparametercount}"),
                "Attribute {pathparametercount} should be resolved");

        // Verify option table data is present
        assertTrue(markdown.contains("Brokers"),
                "Should contain Brokers property");
        assertTrue(markdown.contains("Topic"),
                "Should contain Topic property");

        // Verify it's Markdown not HTML
        assertFalse(markdown.contains("<h2>"),
                "Should not contain HTML heading tags");
        assertFalse(markdown.contains("<table>"),
                "Should not contain HTML table tags");

        // Print summary for manual review
        String[] lines = markdown.split("\n");
        System.out.println("=== Kafka component Markdown: " + lines.length + " lines ===");
        for (int i = 0; i < Math.min(lines.length, 80); i++) {
            System.out.println(lines[i]);
        }
        System.out.println("...");
    }

    @Test
    @EnabledIf("repoExists")
    void choiceEipRendersWithOptions() throws Exception {
        AsciidocConverter converter = new AsciidocConverter();

        Path adocFile = REPO_ROOT.resolve(
                "core/camel-core-engine/src/main/docs/modules/eips/pages/choice-eip.adoc");
        Path partialsDir = REPO_ROOT.resolve(
                "core/camel-core-engine/src/main/docs/modules/eips/partials");
        Path examplesDir = REPO_ROOT.resolve(
                "core/camel-core-engine/src/main/docs/modules/eips/examples");

        if (!Files.exists(adocFile)) {
            System.out.println("Skipping: " + adocFile + " not found");
            return;
        }

        String markdown = converter.toMarkdown(adocFile, partialsDir, examplesDir);

        assertNotNull(markdown);
        assertFalse(markdown.isEmpty());
        assertFalse(markdown.contains("{optioncount}"),
                "Attribute {optioncount} should be resolved");

        String[] lines = markdown.split("\n");
        System.out.println("=== Choice EIP Markdown: " + lines.length + " lines ===");
        for (int i = 0; i < Math.min(lines.length, 40); i++) {
            System.out.println(lines[i]);
        }
    }
}
