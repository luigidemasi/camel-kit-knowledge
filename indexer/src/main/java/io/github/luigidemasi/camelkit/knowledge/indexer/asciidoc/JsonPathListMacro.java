package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.util.Map;

import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.BlockMacroProcessor;
import org.asciidoctor.extension.Name;

/**
 * No-op block macro processor for {@code jsonpathList::} directives.
 *
 * <p>
 * Camel docs use {@code jsonpathList::} for API-based components to render lists of available API methods. For indexing
 * purposes, these lists are skipped — the component's main description and option tables provide sufficient coverage.
 */
@Name("jsonpathList")
public class JsonPathListMacro extends BlockMacroProcessor {

    @Override
    public Object process(StructuralNode parent, String target, Map<String, Object> attributes) {
        return createBlock(parent, "pass", "", attributes);
    }
}
