package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathTableMacroTest {

    private static final String TIMER_JSON = """
            {
                "component": {
                    "name": "timer",
                    "scheme": "timer",
                    "syntax": "timer:timerName",
                    "title": "Timer",
                    "description": "Generate messages in specified intervals using java.util.Timer."
                },
                "componentProperties": {
                    "bridgeErrorHandler": {
                        "displayName": "Bridge Error Handler",
                        "description": "Allows for bridging the consumer to the Camel routing Error Handler.",
                        "defaultValue": false,
                        "javaType": "boolean",
                        "required": false
                    },
                    "lazyStartProducer": {
                        "displayName": "Lazy Start Producer",
                        "description": "Whether the producer should be started lazy.",
                        "defaultValue": false,
                        "javaType": "boolean",
                        "required": false
                    }
                },
                "properties": {
                    "timerName": {
                        "displayName": "Timer Name",
                        "description": "The name of the timer.",
                        "kind": "path",
                        "javaType": "java.lang.String",
                        "required": true
                    },
                    "delay": {
                        "displayName": "Delay",
                        "description": "Delay before first event is triggered.",
                        "kind": "parameter",
                        "defaultValue": "1s",
                        "javaType": "long",
                        "required": false
                    },
                    "period": {
                        "displayName": "Period",
                        "description": "If greater than 0, generate periodic events every period.",
                        "kind": "parameter",
                        "defaultValue": "1s",
                        "javaType": "long",
                        "required": false
                    },
                    "repeatCount": {
                        "displayName": "Repeat Count",
                        "description": "Specifies a maximum limit of number of fires.",
                        "kind": "parameter",
                        "defaultValue": 0,
                        "javaType": "long",
                        "required": false
                    }
                },
                "headers": {}
            }
            """;

    @TempDir
    Path examplesDir;

    private Asciidoctor asciidoctor;

    @BeforeEach
    void setUp() throws IOException {
        // Write the JSON file into the temp examples dir
        Path jsonDir = examplesDir.resolve("json");
        Files.createDirectories(jsonDir);
        Files.writeString(jsonDir.resolve("timer.json"), TIMER_JSON);

        asciidoctor = Asciidoctor.Factory.create();

        // Register all three macros
        asciidoctor.javaExtensionRegistry()
                .blockMacro(new JsonPathTableMacro(examplesDir));
        asciidoctor.javaExtensionRegistry()
                .blockMacro(new JsonPathBlockMacro());
        asciidoctor.javaExtensionRegistry()
                .blockMacro(new JsonPathListMacro());
    }

    @Test
    void rendersComponentOptionsTable() {
        String adoc = """
                = Timer Component

                == Component Options

                jsonpathTable::example$json/timer.json[$.componentProperties.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        assertTrue(html.contains("Bridge Error Handler"),
                "Should contain 'Bridge Error Handler' display name");
        assertTrue(html.contains("Lazy Start Producer"),
                "Should contain 'Lazy Start Producer' display name");
        assertTrue(html.contains("bridging the consumer"),
                "Should contain description text");
    }

    @Test
    void rendersEndpointOptionsTable() {
        String adoc = """
                = Timer Component

                == Endpoint Options

                jsonpathTable::example$json/timer.json[$.properties.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        assertTrue(html.contains("Timer Name"),
                "Should contain 'Timer Name' display name");
        assertTrue(html.contains("*Required*"),
                "Required property should be marked");
        assertTrue(html.contains("Delay"),
                "Should contain 'Delay' display name");
        assertTrue(html.contains("Period"),
                "Should contain 'Period' display name");
        assertTrue(html.contains("Repeat Count"),
                "Should contain 'Repeat Count' display name");
    }

    @Test
    void simplifiesJavaType() {
        String adoc = """
                jsonpathTable::example$json/timer.json[$.properties.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        // java.lang.String should be simplified to String
        assertTrue(html.contains("String"),
                "Should simplify java.lang.String to String");
        assertFalse(html.contains("java.lang.String"),
                "Should NOT contain fully qualified java.lang.String");
    }

    @Test
    void handlesFilterExpression() {
        String adoc = """
                jsonpathTable::example$json/timer.json['$.properties[?(@.kind=="path")]']
                """;

        String html = asciidoctor.convert(adoc, options());

        assertTrue(html.contains("Timer Name"),
                "Should contain path parameter 'Timer Name'");
        assertFalse(html.contains("Delay"),
                "Should NOT contain query parameter 'Delay'");
    }

    @Test
    void handlesNodesPrefix() {
        // Camel docs use "nodes$.componentProperties.*" as the query
        String adoc = """
                jsonpathTable::example$json/timer.json[nodes$.componentProperties.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        assertTrue(html.contains("Bridge Error Handler"),
                "Should handle nodes$ prefix and render results");
    }

    @Test
    void handlesMissingJsonFile() {
        String adoc = """
                jsonpathTable::example$json/nonexistent.json[$.componentProperties.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        assertTrue(html.contains("options table not available"),
                "Should show fallback message for missing JSON file");
    }

    @Test
    void handlesEmptyQueryResult() {
        String adoc = """
                jsonpathTable::example$json/timer.json[$.headers.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        // Empty headers object should produce no table rows
        assertFalse(html.contains("| "),
                "Should produce no table rows for empty query result");
    }

    @Test
    void jsonpathBlockProducesNoOutput() {
        String adoc = """
                = API Component

                jsonpathBlock::example$json/timer.json[$.apis]
                """;

        String html = asciidoctor.convert(adoc, options());

        // jsonpathBlock is a no-op, should not fail
        assertNotNull(html);
    }

    @Test
    void jsonpathListProducesNoOutput() {
        String adoc = """
                = API Component

                jsonpathList::example$json/timer.json[$.apis]
                """;

        String html = asciidoctor.convert(adoc, options());

        // jsonpathList is a no-op, should not fail
        assertNotNull(html);
    }

    @Test
    void rendersDefaultValues() {
        String adoc = """
                jsonpathTable::example$json/timer.json[$.properties.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        assertTrue(html.contains("1s"),
                "Should contain default value '1s'");
        assertTrue(html.contains("0"),
                "Should contain default value '0'");
    }

    @Test
    void stripsExampleDollarPrefix() {
        // Target with example$ prefix should resolve correctly
        String adoc = """
                jsonpathTable::example$json/timer.json[$.componentProperties.*]
                """;

        String html = asciidoctor.convert(adoc, options());

        assertTrue(html.contains("Bridge Error Handler"),
                "Should strip example$ prefix and find the JSON file");
    }

    private Options options() {
        return Options.builder()
                .safe(SafeMode.UNSAFE)
                .backend("html5")
                .headerFooter(false)
                .build();
    }
}
