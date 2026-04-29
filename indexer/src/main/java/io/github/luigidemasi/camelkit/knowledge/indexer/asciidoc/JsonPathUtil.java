package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal JSONPath evaluation utility supporting the patterns used by Apache Camel's Antora documentation (component,
 * EIP, and API JSON schemas).
 *
 * <p>
 * Supported patterns:
 * <ul>
 * <li>{@code $.component} - object access</li>
 * <li>{@code $.component.name} - nested access</li>
 * <li>{@code $.componentProperties.*} - wildcard (all values)</li>
 * <li>{@code $.properties[?(@.kind=="path")]} - equality filter</li>
 * <li>{@code $.properties[?(@.displayName!="Id")]} - negation filter</li>
 * <li>{@code $.properties[?(@.a!="X" && @.b!="Y")]} - compound AND filters</li>
 * <li>{@code $.apis["apiName"].methods.*} - quoted key access</li>
 * </ul>
 */
public final class JsonPathUtil {

    /** Pattern for filter expressions: [?(@.field=="value")] or [?(@.field!="value")] with optional && chains. */
    private static final Pattern FILTER_PATTERN = Pattern.compile(
            "\\[\\?\\((.+)\\)\\]");

    /** Pattern for a single condition inside a filter: @.field=="value" or @.field!="value". */
    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "@\\.(\\w+)\\s*(==|!=)\\s*\"([^\"]*)\"");

    /** Pattern for quoted key access: ["key"]. */
    private static final Pattern QUOTED_KEY_PATTERN = Pattern.compile(
            "\\[\"([^\"]+)\"\\]");

    private JsonPathUtil() {
    }

    /**
     * Execute a JSONPath query on a JSON root object.
     *
     * @param  root     the root JSON object
     * @param  jsonPath the JSONPath expression (must start with {@code $})
     * @return          a {@link JSONObject} for single object access, a {@link List} of objects for wildcard/filter
     *                  results, a primitive value for leaf access, or {@code null} if the path does not exist
     */
    public static Object query(JSONObject root, String jsonPath) {
        if (root == null || jsonPath == null || !jsonPath.startsWith("$")) {
            return null;
        }

        // Strip the leading "$" and optional leading "."
        String path = jsonPath.substring(1);
        if (path.startsWith(".")) {
            path = path.substring(1);
        }

        List<String> segments = tokenize(path);
        Object current = root;

        for (String segment : segments) {
            if (current == null) {
                return null;
            }

            Matcher filterMatcher = FILTER_PATTERN.matcher(segment);

            if (segment.equals("*")) {
                // Wildcard: collect all values of the current JSONObject
                if (current instanceof JSONObject obj) {
                    List<Object> values = new ArrayList<>();
                    for (String key : obj.keySet()) {
                        values.add(obj.get(key));
                    }
                    current = values.isEmpty() ? null : values;
                } else {
                    return null;
                }
            } else if (filterMatcher.matches()) {
                // Filter expression: [?(@.field=="value")] or compound with &&
                if (current instanceof JSONObject obj) {
                    current = applyFilter(obj, filterMatcher.group(1));
                } else {
                    return null;
                }
            } else {
                // Regular key access (plain key or quoted ["key"])
                String key = extractKey(segment);
                if (current instanceof JSONObject obj) {
                    if (!obj.has(key)) {
                        return null;
                    }
                    current = obj.get(key);
                } else {
                    return null;
                }
            }
        }

        return current;
    }

    /**
     * Count nodes matching a JSONPath query. For wildcards/filters, counts matching entries. For a JSONObject result,
     * returns its key count.
     *
     * @param  root     the root JSON object
     * @param  jsonPath the JSONPath expression
     * @return          the count of matching nodes, or 0 if the path does not exist
     */
    public static int countNodes(JSONObject root, String jsonPath) {
        Object result = query(root, jsonPath);
        if (result == null) {
            return 0;
        }
        if (result instanceof List<?> list) {
            return list.size();
        }
        if (result instanceof JSONArray arr) {
            return arr.length();
        }
        if (result instanceof JSONObject obj) {
            return obj.length();
        }
        // Single scalar value
        return 1;
    }

    /**
     * Safely extract a string field from a JSONObject.
     *
     * @param  obj   the JSON object
     * @param  field the field name
     * @return       the string value, or empty string if the field doesn't exist or is null
     */
    public static String extractString(JSONObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.isNull(field)) {
            return "";
        }
        return valueAsString(obj.get(field));
    }

    /**
     * Safely convert a value to its string representation. Handles strings, numbers, booleans, and null.
     *
     * @param  value the value to convert
     * @return       the string representation, or empty string if null
     */
    public static String valueAsString(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return "";
        }
        return value.toString();
    }

    /**
     * Tokenize a JSONPath expression (after stripping "$." prefix) into segments. Handles dot-separated keys, quoted
     * bracket keys, filter expressions, and wildcards.
     *
     * <p>
     * Examples:
     * <ul>
     * <li>{@code component.name} -> {@code ["component", "name"]}</li>
     * <li>{@code apis["apiName"].methods.*} -> {@code ["apis", "apiName", "methods", "*"]}</li>
     * <li>{@code properties[?(@.kind=="path")]} -> {@code ["properties", "[?(@.kind==\"path\")]"]}</li>
     * </ul>
     */
    private static List<String> tokenize(String path) {
        List<String> segments = new ArrayList<>();
        int i = 0;
        int len = path.length();

        while (i < len) {
            if (path.charAt(i) == '.') {
                // Skip dot separators
                i++;
            } else if (path.charAt(i) == '[') {
                // Find the matching closing bracket
                int bracketDepth = 0;
                int start = i;
                while (i < len) {
                    if (path.charAt(i) == '[') {
                        bracketDepth++;
                    } else if (path.charAt(i) == ']') {
                        bracketDepth--;
                        if (bracketDepth == 0) {
                            i++;
                            break;
                        }
                    }
                    i++;
                }
                String bracketExpr = path.substring(start, i);

                // Check if it's a quoted key ["key"] or a filter [?(...)]
                Matcher quotedMatcher = QUOTED_KEY_PATTERN.matcher(bracketExpr);
                if (quotedMatcher.matches()) {
                    segments.add(quotedMatcher.group(1));
                } else {
                    // Filter expression — add as-is
                    segments.add(bracketExpr);
                }
            } else {
                // Regular key: read until dot, bracket, or end
                int start = i;
                while (i < len && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    i++;
                }
                segments.add(path.substring(start, i));
            }
        }

        return segments;
    }

    /**
     * Extract the key from a segment. If the segment is a quoted bracket key like {@code ["apiName"]}, extracts
     * {@code apiName}. Otherwise returns the segment as-is.
     */
    private static String extractKey(String segment) {
        Matcher m = QUOTED_KEY_PATTERN.matcher(segment);
        if (m.matches()) {
            return m.group(1);
        }
        return segment;
    }

    /**
     * Apply a filter expression to a JSONObject whose values are themselves JSONObjects. The filter string is the
     * content inside {@code [?( ... )]}, e.g. {@code @.kind=="path"} or
     * {@code @.displayName!="Id" && @.displayName!="Description"}.
     *
     * @param  obj        the JSONObject whose values to filter
     * @param  filterExpr the raw filter expression (between parentheses)
     * @return            a list of matching values, or null if none match
     */
    private static List<Object> applyFilter(JSONObject obj, String filterExpr) {
        // Split on && for compound conditions
        String[] conditions = filterExpr.split("\\s*&&\\s*");
        List<Object> results = new ArrayList<>();

        for (String key : obj.keySet()) {
            Object value = obj.get(key);
            if (!(value instanceof JSONObject entry)) {
                continue;
            }

            boolean allMatch = true;
            for (String condition : conditions) {
                Matcher m = CONDITION_PATTERN.matcher(condition.trim());
                if (!m.find()) {
                    allMatch = false;
                    break;
                }
                String field = m.group(1);
                String operator = m.group(2);
                String expected = m.group(3);

                String actual = entry.has(field) ? valueAsString(entry.get(field)) : "";

                if ("==".equals(operator) && !expected.equals(actual)) {
                    allMatch = false;
                    break;
                }
                if ("!=".equals(operator) && expected.equals(actual)) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                results.add(value);
            }
        }

        return results.isEmpty() ? null : results;
    }
}
