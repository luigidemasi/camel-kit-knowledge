package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.util.Map;

import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.BlockMacroProcessor;
import org.asciidoctor.extension.Name;

/**
 * No-op block macro processor for {@code jsonpathBlock::} directives.
 *
 * <p>
 * Camel docs use {@code jsonpathBlock::} for API-based components to render complex per-method documentation. For
 * indexing purposes, these blocks are skipped — the component's main description and option tables provide sufficient
 * coverage.
 */
@Name("jsonpathBlock")
public class JsonPathBlockMacro extends BlockMacroProcessor {

    @Override
    public Object process(StructuralNode parent, String target, Map<String, Object> attributes) {
        return createBlock(parent, "pass", "", attributes);
    }
}
