package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathBlockMacro;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathIncludeProcessor;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathListMacro;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathTableMacro;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathUtil;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.MarkdownConverter;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;
import org.json.JSONObject;

/**
 * Converts AsciiDoc files to HTML using AsciidoctorJ. Pure Java — no Docker or external process needed. Thread-safe:
 * Asciidoctor instance is created once and reused.
 */
public class AsciidocConverter {

    private final Asciidoctor asciidoctor;
    private final Options options;

    /** Dedicated Asciidoctor instance for Markdown conversion (separate from HTML). */
    private final Asciidoctor mdAsciidoctor;
    private final Options mdOptions;

    /**
     * Tracks the examplesDir currently registered with mdAsciidoctor extensions. When the caller passes a different
     * examplesDir, extensions are re-registered. {@code null} means no extensions have been registered yet.
     */
    private Path currentExamplesDir;

    public AsciidocConverter() {
        // Suppress AsciidoctorJ logs for missing includes —
        // Camel docs reference generated JSON data files (option tables)
        // that only exist after a full Maven build, not in a plain checkout.
        // SEVERE > WARNING in JUL hierarchy, so OFF is needed to suppress all.
        Logger.getLogger("").setLevel(Level.OFF);

        this.asciidoctor = Asciidoctor.Factory.create();
        this.options = Options.builder()
                .safe(SafeMode.UNSAFE)
                .backend("html5")
                .headerFooter(false)  // No <html>/<body> wrapper — just content
                .build();

        this.mdAsciidoctor = Asciidoctor.Factory.create();
        mdAsciidoctor.javaConverterRegistry().register(MarkdownConverter.class);
        this.mdOptions = Options.builder()
                .safe(SafeMode.UNSAFE)
                .backend("markdown")
                .headerFooter(false)
                .build();
    }

    /**
     * Convert AsciiDoc string to HTML.
     */
    public String toHtml(String asciidoc) {
        return asciidoctor.convert(asciidoc, options);
    }

    /**
     * Convert AsciiDoc file to HTML string.
     */
    public String toHtml(Path adocFile) throws IOException {
        return toHtml(adocFile, null);
    }

    /**
     * Convert AsciiDoc file to HTML, resolving Antora partial$ includes from the given partials directory.
     */
    public String toHtml(Path adocFile, Path partialsDir) throws IOException {
        String content = Files.readString(adocFile);

        if (partialsDir != null && Files.isDirectory(partialsDir)) {
            String absPartials = partialsDir.toAbsolutePath().toString();
            content = content.replace("include::partial$", "include::" + absPartials + "/");
        }

        // Drop cross-module Antora references (e.g., spring-boot:partial$starter.adoc)
        // — they can't be resolved outside the full Antora build
        content = content.replaceAll("include::[a-zA-Z0-9_-]+:partial\\$[^\\[]*\\[[^\\]]*\\]", "");

        return toHtml(content);
    }

    /**
     * Convert AsciiDoc file directly to Markdown using the custom {@link MarkdownConverter} backend, with jsonpath
     * extension support.
     *
     * <p>
     * Uses a dedicated Asciidoctor instance (separate from the HTML one) to avoid interfering with HTML-mode
     * extensions. The jsonpath extensions are re-registered whenever {@code examplesDir} changes, since their
     * constructors take a fixed {@link Path}.
     *
     * @param  adocFile    the AsciiDoc source file
     * @param  partialsDir Antora partials directory (sibling of pages), or null
     * @param  examplesDir Antora examples directory (sibling of pages), or null
     * @return             Markdown string
     */
    public String toMarkdown(Path adocFile, Path partialsDir, Path examplesDir) throws IOException {
        String content = Files.readString(adocFile);

        // Drop cross-module Antora references
        content = content.replaceAll("include::[a-zA-Z0-9_-]+:partial\\$[^\\[]*\\[[^\\]]*\\]", "");

        // Inline Antora partial$ includes: read each partial file, pre-process its
        // jsonpath$/jsonpathcount$ includes, and inline the content. This ensures
        // attribute definitions (like :propertycount:) appear before references ({propertycount}).
        if (partialsDir != null && Files.isDirectory(partialsDir)) {
            content = inlinePartials(content, partialsDir, examplesDir);
        }

        // Also pre-resolve any jsonpath includes in the top-level document
        if (examplesDir != null && Files.isDirectory(examplesDir)) {
            content = resolveJsonPathIncludes(content, examplesDir);
        }

        // Re-register block macros (jsonpathTable, jsonpathBlock, jsonpathList) if examplesDir changed
        Path resolvedDir = (examplesDir != null && Files.isDirectory(examplesDir))
                ? examplesDir.toAbsolutePath()
                : null;

        if (resolvedDir != null && !resolvedDir.equals(currentExamplesDir)) {
            mdAsciidoctor.unregisterAllExtensions();
            mdAsciidoctor.javaExtensionRegistry()
                    .blockMacro(new JsonPathTableMacro(resolvedDir))
                    .blockMacro(new JsonPathBlockMacro())
                    .blockMacro(new JsonPathListMacro());
            currentExamplesDir = resolvedDir;
        } else if (resolvedDir == null && currentExamplesDir != null) {
            mdAsciidoctor.unregisterAllExtensions();
            currentExamplesDir = null;
        }

        return mdAsciidoctor.convert(content, mdOptions);
    }

    private static final Pattern PARTIAL_INCLUDE_PATTERN = Pattern.compile(
            "include::partial\\$([^\\[]+)\\[([^\\]]*)\\]");

    private static final Pattern ATTR_DEF_PATTERN = Pattern.compile(
            "^:(\\w[\\w-]*):\\s*(.*)$", java.util.regex.Pattern.MULTILINE);

    private String inlinePartials(String content, Path partialsDir, Path examplesDir) {
        // Extract document attributes (e.g., :shortname: kafka) for substitution in partials
        Map<String, String> docAttrs = new LinkedHashMap<>();
        Matcher attrMatcher = ATTR_DEF_PATTERN.matcher(content);
        while (attrMatcher.find()) {
            docAttrs.put(attrMatcher.group(1), attrMatcher.group(2).trim());
        }

        Matcher m = PARTIAL_INCLUDE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();

        while (m.find()) {
            String partialFile = m.group(1);
            Path partialPath = partialsDir.resolve(partialFile);

            String replacement = "";
            if (Files.exists(partialPath)) {
                try {
                    String partialContent = Files.readString(partialPath);
                    // Substitute document attributes in partial content
                    for (var entry : docAttrs.entrySet()) {
                        partialContent = partialContent.replace(
                                "{" + entry.getKey() + "}", entry.getValue());
                    }
                    // Pre-resolve jsonpath includes — collects resolved attributes
                    Map<String, String> resolvedAttrs = new LinkedHashMap<>();
                    if (examplesDir != null && Files.isDirectory(examplesDir)) {
                        partialContent = resolveJsonPathIncludes(partialContent, examplesDir, resolvedAttrs);
                    }
                    // Substitute the resolved attributes in the partial content
                    for (var entry : resolvedAttrs.entrySet()) {
                        partialContent = partialContent.replace(
                                "{" + entry.getKey() + "}", entry.getValue());
                    }
                    replacement = partialContent;
                } catch (IOException e) {
                    // Skip silently
                }
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Match include::jsonpath$example$path[...] — use greedy match up to the last ] on the line
    private static final Pattern JSONPATH_INCLUDE_PATTERN = Pattern.compile(
            "include::(jsonpath|jsonpathcount)\\$example\\$([^\\[]+)\\[(.+)\\]$",
            Pattern.MULTILINE);

    private String resolveJsonPathIncludes(String content, Path examplesDir) {
        return resolveJsonPathIncludes(content, examplesDir, null);
    }

    private String resolveJsonPathIncludes(String content, Path examplesDir, Map<String, String> collectedAttrs) {
        Matcher m = JSONPATH_INCLUDE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();

        while (m.find()) {
            String type = m.group(1);
            String jsonRelPath = m.group(2);
            String attrsStr = m.group(3);

            StringBuilder replacement = new StringBuilder();

            try {
                Path jsonFile = examplesDir.resolve(jsonRelPath);
                if (!Files.exists(jsonFile)) {
                    m.appendReplacement(sb, "");
                    continue;
                }

                JSONObject root = new JSONObject(Files.readString(jsonFile));
                Map<String, String> attrs = parseIncludeAttributes(attrsStr);

                Map<String, String> localAttrs = new LinkedHashMap<>();
                if ("jsonpath".equals(type)) {
                    resolveQueryAttrs(root, attrs, replacement, localAttrs);
                } else {
                    resolveCountAttrs(root, attrs, replacement, localAttrs);
                }
                if (collectedAttrs != null) {
                    collectedAttrs.putAll(localAttrs);
                }
            } catch (Exception e) {
                // Skip silently
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void resolveQueryAttrs(
            JSONObject root, Map<String, String> attrs, StringBuilder out, Map<String, String> collected) {
        String query = stripQuotes(attrs.get("query"));
        String formats = stripQuotes(attrs.get("formats"));
        if (query == null || formats == null)
            return;

        Object result = JsonPathUtil.query(root, query);
        if (!(result instanceof JSONObject jsonResult))
            return;

        for (String format : formats.split(",")) {
            format = format.trim();
            if (format.isEmpty())
                continue;

            String attrName, fieldName;
            if (format.contains("=")) {
                String[] parts = format.split("=", 2);
                attrName = parts[0].trim();
                String expr = parts[1].trim();
                int paren = expr.indexOf('(');
                fieldName = (paren >= 0 && expr.indexOf(')') > paren)
                        ? expr.substring(paren + 1, expr.indexOf(')')).trim()
                        : attrName;
            } else {
                attrName = format;
                fieldName = format;
            }

            String value = JsonPathUtil.extractString(jsonResult, fieldName);
            out.append(":").append(attrName).append(": ").append(value).append("\n");
            collected.put(attrName, value);
        }
    }

    private void resolveCountAttrs(
            JSONObject root, Map<String, String> attrs, StringBuilder out, Map<String, String> collected) {
        String queries = stripQuotes(attrs.get("queries"));
        if (queries == null)
            return;

        for (String entry : JsonPathIncludeProcessor.splitQueries(queries)) {
            entry = entry.trim();
            int eq = entry.indexOf('=');
            if (eq < 0)
                continue;

            String attrName = entry.substring(0, eq).trim();
            String path = entry.substring(eq + 1).trim();
            if (path.startsWith("nodes"))
                path = path.substring("nodes".length());

            int count = JsonPathUtil.countNodes(root, path);
            out.append(":").append(attrName).append(": ").append(count).append("\n");
            collected.put(attrName, String.valueOf(count));
        }
    }

    private Map<String, String> parseIncludeAttributes(String attrsStr) {
        Map<String, String> attrs = new LinkedHashMap<>();
        // Parse key='value' or key="value" pairs
        Pattern p = Pattern.compile("(\\w+)\\s*=\\s*(['\"])(.*?)\\2");
        Matcher m = p.matcher(attrsStr);
        while (m.find()) {
            attrs.put(m.group(1), m.group(3));
        }
        return attrs;
    }

    private static String stripQuotes(String s) {
        if (s == null)
            return null;
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) ||
                (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
