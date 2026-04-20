package io.github.luigidemasi.camelkit.knowledge.indexer;

import io.github.luigidemasi.camelkit.knowledge.indexer.domain.DocumentChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CamelCatalogIndexerTest {

    @Test
    void formatsComponentProperties() {
        String json = """
                {
                    "component": {"name": "timer", "title": "Timer", "description": "Generate messages in specified intervals"},
                    "componentProperties": {},
                    "headers": {"CamelTimerFiredTime": {"constantName": "TIMER_FIRED_TIME", "description": "The fired time", "javaType": "java.util.Date"}},
                    "properties": {
                        "timerName": {"displayName": "Timer Name", "description": "The name of the timer", "type": "string", "required": true, "kind": "path"},
                        "delay": {"displayName": "Delay", "description": "Initial delay before first event", "type": "duration", "defaultValue": "1000", "required": false, "kind": "parameter"}
                    }
                }
                """;

        DocumentChunk chunk = CamelCatalogIndexer.buildComponentChunk(json, "4.18", "4.18.2");

        assertEquals("apache-camel-4.18-catalog-component-timer", chunk.id());
        assertEquals("catalog-component", chunk.docType());
        assertEquals("timer", chunk.component());
        assertTrue(chunk.content().contains("Timer Name"));
        assertTrue(chunk.content().contains("The name of the timer"));
        assertTrue(chunk.content().contains("CamelTimerFiredTime"));
    }

    @Test
    void formatsEipProperties() {
        String json = """
                {
                    "model": {"name": "choice", "title": "Choice", "description": "Routes messages based on conditions"},
                    "properties": {
                        "when": {"displayName": "When", "description": "Conditional branch", "type": "object", "required": false},
                        "otherwise": {"displayName": "Otherwise", "description": "Default branch", "type": "object", "required": false}
                    }
                }
                """;

        DocumentChunk chunk = CamelCatalogIndexer.buildEipChunk(json, "4.18", "4.18.2");

        assertEquals("apache-camel-4.18-catalog-eip-choice", chunk.id());
        assertEquals("catalog-eip", chunk.docType());
        assertTrue(chunk.content().contains("Conditional branch"));
    }
}
