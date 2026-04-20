package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathBlockMacro;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathIncludeProcessor;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathListMacro;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.JsonPathTableMacro;
import io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc.MarkdownConverter;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts AsciiDoc files to HTML using AsciidoctorJ.
 * Pure Java — no Docker or external process needed.
 * Thread-safe: Asciidoctor instance is created once and reused.
 */
public class AsciidocConverter {

    private final Asciidoctor asciidoctor;
    private final Options options;

    /** Dedicated Asciidoctor instance for Markdown conversion (separate from HTML). */
    private final Asciidoctor mdAsciidoctor;
    private final Options mdOptions;

    /**
     * Tracks the examplesDir currently registered with mdAsciidoctor extensions.
     * When the caller passes a different examplesDir, extensions are re-registered.
     * {@code null} means no extensions have been registered yet.
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
     * Convert AsciiDoc file to HTML, resolving Antora partial$ includes
     * from the given partials directory.
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
     * Convert AsciiDoc file directly to Markdown using the custom
     * {@link MarkdownConverter} backend, with jsonpath extension support.
     *
     * <p>Uses a dedicated Asciidoctor instance (separate from the HTML one)
     * to avoid interfering with HTML-mode extensions. The jsonpath extensions
     * are re-registered whenever {@code examplesDir} changes, since their
     * constructors take a fixed {@link Path}.
     *
     * @param adocFile    the AsciiDoc source file
     * @param partialsDir Antora partials directory (sibling of pages), or null
     * @param examplesDir Antora examples directory (sibling of pages), or null
     * @return Markdown string
     */
    public String toMarkdown(Path adocFile, Path partialsDir, Path examplesDir) throws IOException {
        String content = Files.readString(adocFile);

        // Resolve Antora partial$ includes
        if (partialsDir != null && Files.isDirectory(partialsDir)) {
            String absPartials = partialsDir.toAbsolutePath().toString();
            content = content.replace("include::partial$", "include::" + absPartials + "/");
        }

        // Drop cross-module Antora references
        content = content.replaceAll("include::[a-zA-Z0-9_-]+:partial\\$[^\\[]*\\[[^\\]]*\\]", "");

        // Re-register jsonpath extensions if examplesDir changed
        Path resolvedDir = (examplesDir != null && Files.isDirectory(examplesDir))
                ? examplesDir.toAbsolutePath()
                : null;

        if (resolvedDir != null && !resolvedDir.equals(currentExamplesDir)) {
            mdAsciidoctor.unregisterAllExtensions();
            mdAsciidoctor.javaExtensionRegistry()
                    .includeProcessor(new JsonPathIncludeProcessor(resolvedDir))
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
}
