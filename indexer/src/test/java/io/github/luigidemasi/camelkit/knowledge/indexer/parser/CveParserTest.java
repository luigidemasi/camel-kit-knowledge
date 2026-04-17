package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CveParserTest {

    @Test
    void parsesFrontmatter() throws Exception {
        String content = new String(
                getClass().getClassLoader().getResourceAsStream("test-cve.md").readAllBytes(),
                StandardCharsets.UTF_8
        );

        CveParser.CveAdvisory cve = CveParser.parse(content);

        assertEquals("CVE-2024-22369", cve.cveId());
        assertEquals("HIGH", cve.severity());
        assertNotNull(cve.summary());
        assertTrue(cve.summary().contains("Unsafe Deserialization"));
        assertNotNull(cve.description());
        assertNotNull(cve.affected());
        assertEquals(4, cve.fixedVersions().size());
        assertTrue(cve.fixedVersions().contains("4.0.4"));
        assertTrue(cve.fixedVersions().contains("3.21.4"));
        assertTrue(cve.jiraIds().contains("CAMEL-20303"));
        assertEquals("2024-02-19", cve.publishedDate());
    }

    @Test
    void extractsComponentFromDescription() {
        String desc = "Unsafe Deserialization from JDBCAggregationRepository in camel-sql";
        String component = CveParser.extractComponent(desc);
        assertEquals("sql", component);
    }

    @Test
    void extractsComponentFromSummary() {
        String desc = "The Camel CXF component allows XXE attacks";
        String component = CveParser.extractComponent(desc);
        assertEquals("cxf", component);
    }

    @Test
    void parsesAffectedVersionRanges() {
        String affected = "From 3.0.0 before 3.21.4, from 4.0.0 before 4.0.4";
        var ranges = CveParser.parseAffectedVersions(affected);
        assertEquals(2, ranges.size());
        assertTrue(ranges.contains("3.0.0-3.21.4"));
        assertTrue(ranges.contains("4.0.0-4.0.4"));
    }
}
