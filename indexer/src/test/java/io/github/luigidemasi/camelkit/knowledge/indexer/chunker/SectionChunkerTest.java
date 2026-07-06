package io.github.luigidemasi.camelkit.knowledge.indexer.chunker;

import java.util.List;

import io.github.luigidemasi.camelkit.knowledge.indexer.chunker.SectionChunker.Section;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionChunkerTest {

    private final SectionChunker chunker = new SectionChunker();

    @Test
    void chunkByHeadings() {
        String markdown = """
                ## Component Changes

                The following components have been renamed.

                ### camel-http4

                The http4 component has been renamed to http.
                The old http component was removed.

                ### camel-netty4

                The netty4 component has been renamed to netty.
                """;

        List<Section> sections = chunker.chunk(markdown);

        assertEquals(3, sections.size());

        assertEquals("Component Changes", sections.get(0).title());
        assertTrue(sections.get(0).content().contains("following components"));
        assertEquals(2, sections.get(0).level());

        // Subsection titles carry the parent-heading breadcrumb so they stay
        // distinguishable for BM25 and embeddings ("Options" alone is ambiguous)
        assertEquals("Component Changes > camel-http4", sections.get(1).title());
        assertTrue(sections.get(1).content().contains("renamed to http"));
        assertEquals(3, sections.get(1).level());

        assertEquals("Component Changes > camel-netty4", sections.get(2).title());
        assertTrue(sections.get(2).content().contains("renamed to netty"));
        assertEquals(3, sections.get(2).level());
    }

    @Test
    void headingsInsideCodeFencesDoNotSplit() {
        String markdown = """
                ## Configuration

                Example properties file:

                ```properties
                ## this is a comment, not a heading
                camel.component.kafka.brokers=localhost:9092
                ```

                More text after the fence.
                """;

        List<Section> sections = chunker.chunk(markdown);

        assertEquals(1, sections.size());
        assertEquals("Configuration", sections.get(0).title());
        assertTrue(sections.get(0).content().contains("this is a comment"));
        assertTrue(sections.get(0).content().contains("More text after the fence"));
    }

    @Test
    void oversizedSingleParagraphIsHardSplit() {
        // One giant code block: no blank lines, so no paragraph boundaries to split at
        StringBuilder md = new StringBuilder("## Giant Table\n\n");
        for (int i = 0; i < 400; i++) {
            md.append("| option-").append(i).append(" | ").append("x".repeat(60)).append(" |\n");
        }

        List<Section> sections = chunker.chunk(md.toString());

        assertTrue(sections.size() > 1, "Oversized single paragraph must be hard-split");
        for (Section s : sections) {
            assertTrue(s.content().length() <= SectionChunker.MAX_CHUNK_CHARS,
                    "Every part must fit the cap, got " + s.content().length());
        }
    }

    @Test
    void pathologicalSingleLineIsSliced() {
        String md = "## Blob\n\n" + "y".repeat(3 * SectionChunker.MAX_CHUNK_CHARS);

        List<Section> sections = chunker.chunk(md);

        assertTrue(sections.size() >= 3);
        for (Section s : sections) {
            assertTrue(s.content().length() <= SectionChunker.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void oversizedSectionsAreSplitAtParagraphs() {
        StringBuilder md = new StringBuilder("## Big Section\n\n");
        for (int i = 0; i < 40; i++) {
            md.append("Paragraph ").append(i).append(". ").append("x".repeat(400)).append("\n\n");
        }

        List<Section> sections = chunker.chunk(md.toString());

        assertTrue(sections.size() > 1, "Oversized section must be split");
        for (Section s : sections) {
            assertTrue(s.content().length() <= SectionChunker.MAX_CHUNK_CHARS,
                    "Every part must fit the cap, got " + s.content().length());
            assertTrue(s.title().startsWith("Big Section (part "));
        }
    }

    @Test
    void contentBeforeFirstHeading() {
        String markdown = """
                This is an introduction paragraph.

                ## First Section

                Section content here.
                """;

        List<Section> sections = chunker.chunk(markdown);

        assertEquals(2, sections.size());
        assertEquals("Introduction", sections.get(0).title());
        assertTrue(sections.get(0).content().contains("introduction paragraph"));
    }

    @Test
    void emptyDocument() {
        List<Section> sections = chunker.chunk("");
        assertTrue(sections.isEmpty());
    }

    @Test
    void noHeadings() {
        String markdown = "Just plain text without any headings.";
        List<Section> sections = chunker.chunk(markdown);

        assertEquals(1, sections.size());
        assertEquals("Introduction", sections.get(0).title());
    }
}
