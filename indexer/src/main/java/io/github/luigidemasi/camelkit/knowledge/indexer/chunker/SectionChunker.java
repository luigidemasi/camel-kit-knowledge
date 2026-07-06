package io.github.luigidemasi.camelkit.knowledge.indexer.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits Markdown text into sections at heading boundaries (##, ###, ####). Each section becomes a self-contained chunk
 * whose title is the full heading breadcrumb (e.g. {@code "Kafka Component > Endpoint Options"}) — bare titles like
 * "Options" are indistinguishable across hundreds of documents for both BM25 and embeddings.
 *
 * <p>
 * Heading markers inside fenced code blocks (``` ... ```) are ignored — shell/properties/YAML comments starting with
 * {@code #} must not split a code example in half.
 *
 * <p>
 * Oversized sections are split at paragraph boundaries into parts of at most {@link #MAX_CHUNK_CHARS} characters, so
 * every chunk fits the embedding window and no tail content is invisible to vector search.
 */
public class SectionChunker {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{2,4})\\s+(.+)$");

    /**
     * ~1,500 tokens at ~4 chars/token — comfortably inside the embedding provider's window (default 2,048 tokens).
     */
    static final int MAX_CHUNK_CHARS = 6000;

    /**
     * A single section extracted from the document.
     */
    public record Section(String title, String content, int level) {
    }

    /**
     * Split markdown text into sections at heading boundaries. Content before the first heading is included as a
     * section with title "Introduction".
     *
     * @param  markdown the full markdown text
     * @return          list of sections, each with a breadcrumb title and content
     */
    public List<Section> chunk(String markdown) {
        List<Section> sections = new ArrayList<>();

        String[] crumbs = new String[5]; // heading text by level (2..4)
        String currentTitle = "Introduction";
        int currentLevel = 1;
        StringBuilder content = new StringBuilder();
        boolean inFence = false;

        for (String line : markdown.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                inFence = !inFence;
                content.append(line).append('\n');
                continue;
            }

            Matcher m = inFence ? null : HEADING_PATTERN.matcher(line);
            if (m != null && m.matches()) {
                addSection(sections, currentTitle, content.toString(), currentLevel);
                content.setLength(0);

                currentLevel = m.group(1).length();
                crumbs[currentLevel] = m.group(2).trim();
                for (int l = currentLevel + 1; l < crumbs.length; l++) {
                    crumbs[l] = null;
                }
                currentTitle = breadcrumb(crumbs, currentLevel);
            } else {
                content.append(line).append('\n');
            }
        }
        addSection(sections, currentTitle, content.toString(), currentLevel);

        return sections;
    }

    private static String breadcrumb(String[] crumbs, int level) {
        StringBuilder sb = new StringBuilder();
        for (int l = 2; l <= level; l++) {
            if (crumbs[l] != null) {
                if (sb.length() > 0) {
                    sb.append(" > ");
                }
                sb.append(crumbs[l]);
            }
        }
        return sb.toString();
    }

    /** Adds the section, splitting at paragraph boundaries when it exceeds {@link #MAX_CHUNK_CHARS}. */
    private static void addSection(List<Section> sections, String title, String content, int level) {
        content = content.trim();
        if (content.isEmpty()) {
            return;
        }
        if (content.length() <= MAX_CHUNK_CHARS) {
            sections.add(new Section(title, content, level));
            return;
        }

        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String paragraph : content.split("\n\n")) {
            if (part.length() > 0 && part.length() + paragraph.length() + 2 > MAX_CHUNK_CHARS) {
                parts.add(part.toString().trim());
                part.setLength(0);
            }
            part.append(paragraph).append("\n\n");
        }
        if (part.length() > 0) {
            parts.add(part.toString().trim());
        }

        for (int i = 0; i < parts.size(); i++) {
            String partTitle = parts.size() > 1 ? title + " (part " + (i + 1) + ")" : title;
            sections.add(new Section(partTitle, parts.get(i), level));
        }
    }
}
