package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.asciidoctor.ast.Document;
import org.asciidoctor.extension.IncludeProcessor;
import org.asciidoctor.extension.PreprocessorReader;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AsciidoctorJ IncludeProcessor that resolves {@code jsonpath$} and {@code jsonpathcount$} include directives used in
 * Apache Camel's Antora-based documentation.
 *
 * <p>
 * These custom includes load a JSON file from the Antora examples directory, evaluate JSONPath-like expressions against
 * it, and set AsciiDoc document attributes with the results. They produce no inline content — their purpose is purely
 * to populate attributes used later in the document.
 *
 * <h3>Pattern 1: {@code jsonpath$}</h3>
 *
 * <pre>
 * include::jsonpath$example$json/kafka.json[query='$.component',formats='name,scheme,syntax']
 * </pre>
 *
 * Evaluates the {@code query} JSONPath, then for each field listed in {@code formats}, sets a document attribute with
 * that field's value from the query result.
 *
 * <h3>Pattern 2: {@code jsonpathcount$}</h3>
 *
 * <pre>
 * include::jsonpathcount$example$json/kafka.json[queries='propertycount=nodes$.componentProperties.*']
 * </pre>
 *
 * For each {@code attrName=nodes$jsonpath} pair in {@code queries}, counts the matching nodes and sets the attribute to
 * the count.
 */
public class JsonPathIncludeProcessor extends IncludeProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(JsonPathIncludeProcessor.class);

    private final Path examplesDir;

    public JsonPathIncludeProcessor(Path examplesDir) {
        this.examplesDir = examplesDir;
    }

    @Override
    public boolean handles(String target) {
        return target.startsWith("jsonpath$") || target.startsWith("jsonpathcount$");
    }

    @Override
    public void process(Document document, PreprocessorReader reader, String target, Map<String, Object> attributes) {
        StringBuilder attrDefs = new StringBuilder();

        try {
            String jsonRelPath;
            boolean isCount;
            if (target.startsWith("jsonpathcount$example$")) {
                jsonRelPath = target.substring("jsonpathcount$example$".length());
                isCount = true;
            } else if (target.startsWith("jsonpath$example$")) {
                jsonRelPath = target.substring("jsonpath$example$".length());
                isCount = false;
            } else {
                reader.pushInclude("", target, target, 1, attributes);
                return;
            }

            Path jsonFile = examplesDir.resolve(jsonRelPath);
            if (!Files.exists(jsonFile)) {
                reader.pushInclude("", target, target, 1, attributes);
                return;
            }

            String jsonContent = Files.readString(jsonFile);
            JSONObject root = new JSONObject(jsonContent);

            Map<String, String> resolvedAttrs = new java.util.LinkedHashMap<>();
            if (isCount) {
                collectCountAttrs(root, attributes, resolvedAttrs);
            } else {
                collectQueryAttrs(root, attributes, resolvedAttrs);
            }

            for (var entry : resolvedAttrs.entrySet()) {
                attrDefs.append(":").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }

        } catch (Exception e) {
            LOG.warn("  JsonPath include failed for {}: {}", target, e.getMessage());
        }

        reader.pushInclude(attrDefs.toString(), target, target, 1, attributes);
    }

    private void collectQueryAttrs(JSONObject root, Map<String, Object> attributes, Map<String, String> out) {
        String query = (String) attributes.get("query");
        String formats = (String) attributes.get("formats");
        if (query == null || formats == null)
            return;

        query = stripQuotes(query);

        Object result = JsonPathUtil.query(root, query);
        if (!(result instanceof JSONObject jsonResult))
            return;

        for (String format : formats.split(",")) {
            format = format.trim();
            if (format.isEmpty())
                continue;

            String attrName;
            String fieldName;

            if (format.contains("=")) {
                String[] parts = format.split("=", 2);
                attrName = parts[0].trim();
                String expr = parts[1].trim();
                fieldName = extractFieldFromExpression(expr);
                if (fieldName == null)
                    fieldName = attrName;
            } else {
                attrName = format;
                fieldName = format;
            }

            out.put(attrName, JsonPathUtil.extractString(jsonResult, fieldName));
        }
    }

    private void collectCountAttrs(JSONObject root, Map<String, Object> attributes, Map<String, String> out) {
        String queries = (String) attributes.get("queries");
        if (queries == null)
            return;

        for (String entry : splitQueries(queries)) {
            entry = entry.trim();
            if (entry.isEmpty())
                continue;

            int eq = entry.indexOf('=');
            if (eq < 0)
                continue;

            String attrName = entry.substring(0, eq).trim();
            String path = entry.substring(eq + 1).trim();

            if (path.startsWith("nodes")) {
                path = path.substring("nodes".length());
            }

            int count = JsonPathUtil.countNodes(root, path);
            out.put(attrName, String.valueOf(count));
        }
    }

    /**
     * Split the queries string on commas, but respect bracket depth so that filter expressions like
     * {@code [?(@.kind=="path")]} are not split.
     *
     * <p>
     * Example input:
     * {@code propertycount=nodes$.componentProperties.*,pathparametercount=nodes$.properties[?(@.kind=="path")]}
     *
     * @param  queries the raw queries attribute value
     * @return         list of individual query entries
     */
    public static List<String> splitQueries(String queries) {
        List<String> result = new ArrayList<>();
        int bracketDepth = 0;
        int start = 0;

        for (int i = 0; i < queries.length(); i++) {
            char c = queries.charAt(i);
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth--;
            } else if (c == ',' && bracketDepth == 0) {
                result.add(queries.substring(start, i));
                start = i + 1;
            }
        }

        // Add the last segment
        if (start < queries.length()) {
            result.add(queries.substring(start));
        }

        return result;
    }

    /**
     * Extract the field name from a format expression. For example, {@code util.pascalCase(scheme)} yields
     * {@code scheme}. A plain field name like {@code scheme} is returned as-is.
     */
    static String extractFieldFromExpression(String expr) {
        int paren = expr.indexOf('(');
        if (paren >= 0) {
            int end = expr.indexOf(')', paren);
            if (end > paren) {
                return expr.substring(paren + 1, end).trim();
            }
        }
        return expr;
    }

    /**
     * Strip surrounding single or double quotes from a string.
     */
    private static String stripQuotes(String s) {
        if (s != null && s.length() >= 2) {
            if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
