package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathIncludeProcessorTest {

    private static final String KAFKA_JSON = """
            {
                "component": {
                    "name": "kafka",
                    "scheme": "kafka",
                    "syntax": "kafka:topic",
                    "apiSyntax": "kafka:topic"
                },
                "componentProperties": {
                    "brokers": {"displayName": "Brokers", "type": "string"},
                    "groupId": {"displayName": "Group ID", "type": "string"},
                    "sslKeyPassword": {"displayName": "SSL Key Password", "type": "string"}
                },
                "properties": {
                    "topic": {"displayName": "Topic", "kind": "path", "type": "string"},
                    "brokers": {"displayName": "Brokers", "kind": "parameter", "type": "string"},
                    "groupId": {"displayName": "Group ID", "kind": "parameter", "type": "string"}
                },
                "apis": {}
            }
            """;

    private static Asciidoctor asciidoctor;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setup() {
        asciidoctor = Asciidoctor.Factory.create();
    }

    private Path writeJsonFile(String relativePath, String content) throws IOException {
        Path jsonFile = tempDir.resolve(relativePath);
        Files.createDirectories(jsonFile.getParent());
        Files.writeString(jsonFile, content);
        return jsonFile;
    }

    private String render(String adoc, Path examplesDir) {
        // Create a fresh Asciidoctor for each test to avoid stale registrations
        Asciidoctor ad = Asciidoctor.Factory.create();
        ad.javaExtensionRegistry().includeProcessor(new JsonPathIncludeProcessor(examplesDir));
        return ad.convert(adoc, Options.builder()
                .headerFooter(false)
                .safe(org.asciidoctor.SafeMode.UNSAFE)
                .build());
    }

    @Test
    void setsComponentAttributesFromJsonPath() throws IOException {
        writeJsonFile("json/kafka.json", KAFKA_JSON);

        // The include sets document attributes, then we use them in the document text
        String adoc = """
                :shortname: kafka
                include::jsonpath$example$json/kafka.json[query='$.component',formats='name,scheme,syntax']

                The component name is {name} with scheme {scheme} and syntax {syntax}.
                """;

        String output = render(adoc, tempDir);

        assertTrue(output.contains("kafka"), "Should contain resolved name. Got: " + output);
        assertTrue(output.contains("kafka:topic"), "Should contain resolved syntax. Got: " + output);
    }

    @Test
    void setsRenamedAttributeFromExpression() throws IOException {
        writeJsonFile("json/kafka.json", KAFKA_JSON);

        String adoc
                = """
                        :shortname: kafka
                        include::jsonpath$example$json/kafka.json[query='$.component',formats='name,scheme,pascalcasescheme=util.pascalCase(scheme),syntax']

                        Header prefix is Camel{pascalcasescheme}.
                        """;

        String output = render(adoc, tempDir);

        // pascalcasescheme should be set to the value of "scheme" field (kafka)
        assertTrue(output.contains("Camelkafka"), "Should contain resolved pascalcasescheme. Got: " + output);
    }

    @Test
    void setsCountAttributes() throws IOException {
        writeJsonFile("json/kafka.json", KAFKA_JSON);

        String adoc
                = """
                        :shortname: kafka
                        include::jsonpathcount$example$json/kafka.json[queries='propertycount=nodes$.componentProperties.*,pathparametercount=nodes$.properties[?(@.kind=="path")],queryparametercount=nodes$.properties[?(@.kind=="parameter")]']

                        Component has {propertycount} options, {pathparametercount} path params, {queryparametercount} query params.
                        """;

        String output = render(adoc, tempDir);

        assertTrue(output.contains("3 options"), "Should have 3 component properties. Got: " + output);
        assertTrue(output.contains("1 path params"), "Should have 1 path param. Got: " + output);
        assertTrue(output.contains("2 query params"), "Should have 2 query params. Got: " + output);
    }

    @Test
    void handlesNonExistentJsonFile() {
        // No JSON file written — tempDir/json/missing.json does not exist
        String adoc = """
                include::jsonpath$example$json/missing.json[query='$.component',formats='name']

                Name is {name}.
                """;

        // Should not throw — gracefully handles missing file
        String output = render(adoc, tempDir);
        assertNotNull(output, "Should produce output even when JSON file is missing");
    }

    @Test
    void handlesCountWithEmptyApis() throws IOException {
        writeJsonFile("json/kafka.json", KAFKA_JSON);

        String adoc = """
                :shortname: kafka
                include::jsonpathcount$example$json/kafka.json[queries='apicount=nodes$.apis.*']

                APIs: {apicount}.
                """;

        String output = render(adoc, tempDir);

        assertTrue(output.contains("0"), "Should have 0 APIs for empty apis object. Got: " + output);
    }

    @Test
    void handlesMethodDoesNotMatchReturnsTrue() {
        // The processor should only handle jsonpath$ and jsonpathcount$ prefixes
        JsonPathIncludeProcessor processor = new JsonPathIncludeProcessor(tempDir);

        assertTrue(processor.handles("jsonpath$example$json/test.json"));
        assertTrue(processor.handles("jsonpathcount$example$json/test.json"));
        assertFalse(processor.handles("example$json/test.json"));
        assertFalse(processor.handles("some/other/path.adoc"));
    }
}
