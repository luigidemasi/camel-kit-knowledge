package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts AsciiDoc files to HTML using AsciidoctorJ.
 * Pure Java — no Docker or external process needed.
 * Thread-safe: Asciidoctor instance is created once and reused.
 */
public class AsciidocConverter {

    private final Asciidoctor asciidoctor;
    private final Options options;

    public AsciidocConverter() {
        this.asciidoctor = Asciidoctor.Factory.create();
        this.options = Options.builder()
                .safe(SafeMode.UNSAFE)
                .backend("html5")
                .headerFooter(false)  // No <html>/<body> wrapper — just content
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
        String content = Files.readString(adocFile);
        return toHtml(content);
    }
}
