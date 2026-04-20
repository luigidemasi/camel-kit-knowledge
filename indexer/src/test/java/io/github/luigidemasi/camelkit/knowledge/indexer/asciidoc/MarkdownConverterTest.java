package io.github.luigidemasi.camelkit.knowledge.indexer.asciidoc;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownConverterTest {

    private static Asciidoctor asciidoctor;

    @BeforeAll
    static void setup() {
        asciidoctor = Asciidoctor.Factory.create();
        asciidoctor.javaConverterRegistry().register(MarkdownConverter.class);
    }

    private String convert(String adoc) {
        return asciidoctor.convert(adoc, Options.builder()
                .backend("markdown")
                .headerFooter(false)
                .build());
    }

    @Test
    void convertsHeadings() {
        String md = convert("== Title\n\nSome text.\n\n=== Subtitle\n\nMore text.");
        assertTrue(md.contains("## Title"), "Got: " + md);
        assertTrue(md.contains("### Subtitle"), "Got: " + md);
        assertTrue(md.contains("Some text."), "Got: " + md);
    }

    @Test
    void convertsCodeBlock() {
        String md = convert("[source,java]\n----\nSystem.out.println(\"hello\");\n----");
        assertTrue(md.contains("```java"), "Got: " + md);
        assertTrue(md.contains("System.out.println"), "Got: " + md);
        assertTrue(md.contains("```"), "Should close code block. Got: " + md);
    }

    @Test
    void convertsUnorderedList() {
        String md = convert("* item one\n* item two\n* item three");
        assertTrue(md.contains("- item one"), "Got: " + md);
        assertTrue(md.contains("- item two"), "Got: " + md);
    }

    @Test
    void convertsOrderedList() {
        String md = convert(". first\n. second\n. third");
        assertTrue(md.contains("1."), "Got: " + md);
        assertTrue(md.contains("first"), "Got: " + md);
    }

    @Test
    void convertsTable() {
        String md = convert("[cols=\"1,1\"]\n|===\n| Name | Value\n\n| foo | bar\n| baz | qux\n|===");
        assertTrue(md.contains("Name"), "Got: " + md);
        assertTrue(md.contains("foo"), "Got: " + md);
        assertTrue(md.contains("|"), "Should have pipe chars. Got: " + md);
    }

    @Test
    void convertsAdmonition() {
        String md = convert("NOTE: This is important.");
        assertTrue(md.contains("NOTE"), "Got: " + md);
        assertTrue(md.contains("important"), "Got: " + md);
    }

    @Test
    void convertsParagraph() {
        String md = convert("This is a paragraph.\n\nThis is another.");
        assertTrue(md.contains("This is a paragraph."), "Got: " + md);
        assertTrue(md.contains("This is another."), "Got: " + md);
    }

    @Test
    void preservesAttributes() {
        String md = convert(":myattr: hello\n\n== Section\n\nThe value is {myattr}.");
        assertTrue(md.contains("hello"), "Attribute should be resolved. Got: " + md);
    }
}
