package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.asciidoctor.ast.*;
import org.asciidoctor.converter.ConverterFor;
import org.asciidoctor.converter.StringConverter;

/**
 * Custom AsciidoctorJ converter that outputs Markdown instead of HTML.
 *
 * <p>
 * Registered for the {@code "markdown"} backend. Usage:
 *
 * <pre>{@code
 * Asciidoctor asciidoctor = Asciidoctor.Factory.create();
 * asciidoctor.javaConverterRegistry().register(MarkdownConverter.class);
 * String md = asciidoctor.convert(adoc, Options.builder()
 *         .backend("markdown")
 *         .headerFooter(false)
 *         .build());
 * }</pre>
 */
@ConverterFor("markdown")
public class MarkdownConverter extends StringConverter {

    private static final Pattern STRONG_PATTERN = Pattern.compile("<strong>(.*?)</strong>");
    private static final Pattern EM_PATTERN = Pattern.compile("<em>(.*?)</em>");
    private static final Pattern CODE_PATTERN = Pattern.compile("<code>(.*?)</code>");
    private static final Pattern LINK_PATTERN = Pattern.compile("<a href=\"([^\"]*)\">([^<]*)</a>");

    public MarkdownConverter(String backend, Map<String, Object> opts) {
        super(backend, opts);
    }

    @Override
    public String convert(ContentNode node, String transform, Map<Object, Object> opts) {
        if (node instanceof Document document) {
            return convertDocument(document);
        }

        if (transform == null && node instanceof StructuralNode) {
            transform = ((StructuralNode) node).getContext();
        }

        if ("embedded".equals(transform)) {
            return convertDocument((StructuralNode) node);
        }

        if (node instanceof Section section) {
            return convertSection(section);
        }

        if (node instanceof Table table) {
            return convertTable(table);
        }

        if (node instanceof DescriptionList dlist) {
            return convertDescriptionList(dlist);
        }

        if (node instanceof org.asciidoctor.ast.List list) {
            return convertList(list);
        }

        if (node instanceof ListItem listItem) {
            return convertListItem(listItem);
        }

        if (node instanceof Block block) {
            return convertBlock(block);
        }

        // Fallback: try getContent() on StructuralNode
        if (node instanceof StructuralNode sn) {
            Object content = sn.getContent();
            return content != null ? content.toString() : "";
        }

        return "";
    }

    private String convertDocument(StructuralNode node) {
        StringBuilder sb = new StringBuilder();
        for (StructuralNode child : node.getBlocks()) {
            String converted = convert(child, child.getContext(), null);
            if (converted != null && !converted.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(converted);
            }
        }
        return sb.toString();
    }

    private String convertSection(Section section) {
        StringBuilder sb = new StringBuilder();
        String heading = "#".repeat(section.getLevel() + 1) + " " + section.getTitle();
        sb.append(heading);

        for (StructuralNode child : section.getBlocks()) {
            String converted = convert(child, child.getContext(), null);
            if (converted != null && !converted.isEmpty()) {
                sb.append("\n\n");
                sb.append(converted);
            }
        }

        return sb.toString();
    }

    private String convertBlock(Block block) {
        String context = block.getContext();

        return switch (context) {
            case "paragraph" -> cleanInlineHtml(safeGetContent(block));
            case "listing" -> convertListing(block);
            case "literal" -> convertLiteral(block);
            case "pass" -> safeGetContent(block);
            case "admonition" -> convertAdmonition(block);
            case "quote", "verse" -> convertQuote(block);
            case "image" -> convertImage(block);
            case "open", "sidebar" -> convertChildren(block);
            case "preamble" -> convertChildren(block);
            case "toc" -> "";
            default -> {
                // Try to get content for unknown block types
                String content = safeGetContent(block);
                yield content.isEmpty() ? convertChildren(block) : cleanInlineHtml(content);
            }
        };
    }

    private String convertListing(Block block) {
        String language = "";
        Object langAttr = block.getAttribute("language");
        if (langAttr != null) {
            language = langAttr.toString();
        }
        return "```" + language + "\n" + block.getSource() + "\n```";
    }

    private String convertLiteral(Block block) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.getSource().split("\n")) {
            sb.append("    ").append(line).append("\n");
        }
        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String convertAdmonition(Block block) {
        String style = "NOTE";
        Object styleAttr = block.getAttribute("style");
        if (styleAttr != null) {
            style = styleAttr.toString().toUpperCase(Locale.ROOT);
        }
        String content = cleanInlineHtml(safeGetContent(block));
        return "> **" + style + ":** " + content;
    }

    private String convertQuote(Block block) {
        String content = cleanInlineHtml(safeGetContent(block));
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            sb.append("> ").append(line).append("\n");
        }
        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String convertImage(Block block) {
        String target = "";
        Object targetAttr = block.getAttribute("target");
        if (targetAttr != null) {
            target = targetAttr.toString();
        }
        String alt = block.getAttribute("alt", "").toString();
        return "![" + alt + "](" + target + ")";
    }

    private String convertList(org.asciidoctor.ast.List list) {
        String context = list.getContext();
        StringBuilder sb = new StringBuilder();
        int index = 1;

        for (StructuralNode item : list.getItems()) {
            if (item instanceof ListItem listItem) {
                String text = cleanInlineHtml(listItem.getText() != null ? listItem.getText() : "");

                if ("olist".equals(context) || "colist".equals(context)) {
                    sb.append(index).append(". ").append(text).append("\n");
                    index++;
                } else {
                    sb.append("- ").append(text).append("\n");
                }

                // Convert any child blocks of the list item
                if (listItem.getBlocks() != null && !listItem.getBlocks().isEmpty()) {
                    for (StructuralNode child : listItem.getBlocks()) {
                        String converted = convert(child, child.getContext(), null);
                        if (converted != null && !converted.isEmpty()) {
                            // Indent child content under the list item
                            for (String line : converted.split("\n")) {
                                sb.append("  ").append(line).append("\n");
                            }
                        }
                    }
                }
            }
        }

        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String convertListItem(ListItem listItem) {
        return cleanInlineHtml(listItem.getText() != null ? listItem.getText() : "");
    }

    private String convertDescriptionList(DescriptionList dlist) {
        StringBuilder sb = new StringBuilder();
        for (DescriptionListEntry entry : dlist.getItems()) {
            String term = "";
            if (entry.getTerms() != null && !entry.getTerms().isEmpty()) {
                term = cleanInlineHtml(entry.getTerms().get(0).getText());
            }
            String description = "";
            if (entry.getDescription() != null) {
                description = cleanInlineHtml(
                        entry.getDescription().getText() != null ? entry.getDescription().getText() : "");
            }
            sb.append("**").append(term).append("**: ").append(description).append("\n");
        }
        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String convertTable(Table table) {
        StringBuilder sb = new StringBuilder();
        // Header row
        if (table.getHeader() != null && !table.getHeader().isEmpty()) {
            Row headerRow = table.getHeader().get(0);
            sb.append("|");
            for (Cell cell : headerRow.getCells()) {
                sb.append(" ").append(cell.getText()).append(" |");
            }
            sb.append("\n|");
            for (int i = 0; i < headerRow.getCells().size(); i++) {
                sb.append(" --- |");
            }
            sb.append("\n");
        }
        // Body rows
        for (Row row : table.getBody()) {
            sb.append("|");
            for (Cell cell : row.getCells()) {
                sb.append(" ").append(cell.getText().replace("\n", " ")).append(" |");
            }
            sb.append("\n");
        }
        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private String convertChildren(StructuralNode node) {
        StringBuilder sb = new StringBuilder();
        for (StructuralNode child : node.getBlocks()) {
            String converted = convert(child, child.getContext(), null);
            if (converted != null && !converted.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(converted);
            }
        }
        return sb.toString();
    }

    /**
     * Get content from a block safely, returning empty string on failure.
     */
    private String safeGetContent(Block block) {
        try {
            Object content = block.getContent();
            return content != null ? content.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Clean inline HTML tags that AsciidoctorJ produces and convert them to their Markdown equivalents.
     */
    static String cleanInlineHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // Convert <a href="url">text</a> to [text](url)
        Matcher linkMatcher = LINK_PATTERN.matcher(text);
        text = linkMatcher.replaceAll("[$2]($1)");

        // Convert <strong>text</strong> to **text**
        Matcher strongMatcher = STRONG_PATTERN.matcher(text);
        text = strongMatcher.replaceAll("**$1**");

        // Convert <em>text</em> to *text*
        Matcher emMatcher = EM_PATTERN.matcher(text);
        text = emMatcher.replaceAll("*$1*");

        // Convert <code>text</code> to `text`
        Matcher codeMatcher = CODE_PATTERN.matcher(text);
        text = codeMatcher.replaceAll("`$1`");

        return text;
    }
}
