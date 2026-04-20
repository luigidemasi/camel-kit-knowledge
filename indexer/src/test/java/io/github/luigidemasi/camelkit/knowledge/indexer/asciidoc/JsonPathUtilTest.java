package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsonPathUtilTest {

    private static final String COMPONENT_JSON = """
            {
                "component": {"name": "kafka", "scheme": "kafka", "syntax": "kafka:topic"},
                "componentProperties": {
                    "brokers": {"displayName": "Brokers", "type": "string", "description": "URL of brokers"},
                    "groupId": {"displayName": "Group ID", "type": "string", "description": "Consumer group"}
                },
                "properties": {
                    "topic": {"displayName": "Topic", "kind": "path", "type": "string", "required": true, "description": "The topic name"},
                    "brokers": {"displayName": "Brokers", "kind": "parameter", "type": "string", "required": false, "description": "Broker URLs"},
                    "groupId": {"displayName": "Group ID", "kind": "parameter", "type": "string", "required": false, "description": "Group ID"}
                },
                "headers": {
                    "kafka.PARTITION_KEY": {"displayName": "Partition", "description": "The partition", "javaType": "Integer", "constantName": "PARTITION_KEY"}
                },
                "apis": {}
            }
            """;

    private static final String EIP_JSON = """
            {
                "model": {"name": "choice", "title": "Choice", "description": "Routes messages based on conditions"},
                "properties": {
                    "id": {"displayName": "Id", "description": "Node ID", "type": "string"},
                    "description": {"displayName": "Description", "description": "Description", "type": "string"},
                    "when": {"displayName": "When", "description": "Conditional branch", "type": "object"},
                    "otherwise": {"displayName": "Otherwise", "description": "Default branch", "type": "object"},
                    "precondition": {"displayName": "Precondition", "description": "Startup eval", "type": "boolean"}
                },
                "exchangeProperties": {
                    "CamelSlipEndpoint": {"displayName": "Slip Endpoint", "description": "The endpoint", "type": "string"}
                }
            }
            """;

    @Test
    void queryObjectAccess() {
        JSONObject root = new JSONObject(COMPONENT_JSON);
        Object result = JsonPathUtil.query(root, "$.component");
        assertInstanceOf(JSONObject.class, result);
        assertEquals("kafka", ((JSONObject) result).getString("name"));
    }

    @Test
    void queryNestedAccess() {
        JSONObject root = new JSONObject(COMPONENT_JSON);
        Object result = JsonPathUtil.query(root, "$.component.name");
        assertEquals("kafka", result);
    }

    @Test
    void queryWildcard() {
        JSONObject root = new JSONObject(COMPONENT_JSON);
        Object result = JsonPathUtil.query(root, "$.componentProperties.*");
        assertInstanceOf(List.class, result);
        assertEquals(2, ((List<?>) result).size());
    }

    @Test
    void countNodesWildcard() {
        JSONObject root = new JSONObject(COMPONENT_JSON);
        assertEquals(2, JsonPathUtil.countNodes(root, "$.componentProperties.*"));
        assertEquals(3, JsonPathUtil.countNodes(root, "$.properties.*"));
        assertEquals(1, JsonPathUtil.countNodes(root, "$.headers.*"));
    }

    @Test
    void queryWithEqualityFilter() {
        JSONObject root = new JSONObject(COMPONENT_JSON);
        int pathParams = JsonPathUtil.countNodes(root, "$.properties[?(@.kind==\"path\")]");
        assertEquals(1, pathParams);
        int queryParams = JsonPathUtil.countNodes(root, "$.properties[?(@.kind==\"parameter\")]");
        assertEquals(2, queryParams);
    }

    @Test
    void queryWithNegationFilter() {
        JSONObject root = new JSONObject(EIP_JSON);
        // Exclude Id and Description
        int count = JsonPathUtil.countNodes(root, "$.properties[?(@.displayName!=\"Id\" && @.displayName!=\"Description\")]");
        assertEquals(3, count); // when, otherwise, precondition
    }

    @Test
    void countNonExistentPath() {
        JSONObject root = new JSONObject(COMPONENT_JSON);
        assertEquals(0, JsonPathUtil.countNodes(root, "$.apis.*"));
        assertEquals(0, JsonPathUtil.countNodes(root, "$.nonexistent.*"));
    }

    @Test
    void extractStringHandlesNull() {
        JSONObject obj = new JSONObject("{}");
        assertEquals("", JsonPathUtil.extractString(obj, "missing"));
    }

    @Test
    void exchangePropertiesAccess() {
        JSONObject root = new JSONObject(EIP_JSON);
        assertEquals(1, JsonPathUtil.countNodes(root, "$.exchangeProperties.*"));
    }
}
