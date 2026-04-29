package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import io.github.luigidemasi.camelkit.knowledge.indexer.VersionResolver.CamelRelease;
import io.github.luigidemasi.camelkit.knowledge.indexer.VersionResolver.QuarkusMapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class VersionResolverTest {

    @TempDir
    Path tempDir;

    // ── 1. parsesLtsReleasesWithEol ─────────────────────────────────────

    @Test
    void parsesLtsReleasesWithEol() throws Exception {
        Path releasesDir = tempDir.resolve("content/releases");
        Files.createDirectories(releasesDir);

        Files.writeString(releasesDir.resolve("release-4.14.0.md"), """
                ---
                date: 2026-01-15
                draft: false
                title: "Camel 4.14.0 (LTS) Release"
                summary: "Long term support release"
                kind: lts
                eol: 2027-06-30
                category: camel
                ---
                Body text here.
                """);

        Files.writeString(releasesDir.resolve("release-4.18.0.md"), """
                ---
                date: 2026-03-01
                draft: false
                title: "Camel 4.18.0 (LTS) Release"
                summary: "Long term support release"
                kind: lts
                eol: 2027-12-31
                category: camel
                ---
                Body text here.
                """);

        List<CamelRelease> releases = VersionResolver.parseReleases(tempDir);

        assertEquals(2, releases.size());

        CamelRelease r414 = releases.stream()
                .filter(r -> r.minor().equals("4.14")).findFirst().orElseThrow();
        assertTrue(r414.lts());
        assertEquals(LocalDate.of(2027, 6, 30), r414.eol());

        CamelRelease r418 = releases.stream()
                .filter(r -> r.minor().equals("4.18")).findFirst().orElseThrow();
        assertTrue(r418.lts());
        assertEquals(LocalDate.of(2027, 12, 31), r418.eol());
    }

    // ── 2. excludesExpiredLts ───────────────────────────────────────────

    @Test
    void excludesExpiredLts() throws Exception {
        Path releasesDir = tempDir.resolve("content/releases");
        Files.createDirectories(releasesDir);

        Files.writeString(releasesDir.resolve("release-4.10.0.md"), """
                ---
                date: 2025-06-01
                draft: false
                title: "Camel 4.10.0 (LTS) Release"
                summary: "Long term support release"
                kind: lts
                eol: 2026-02-10
                category: camel
                ---
                Body text here.
                """);

        Files.writeString(releasesDir.resolve("release-4.14.0.md"), """
                ---
                date: 2026-01-15
                draft: false
                title: "Camel 4.14.0 (LTS) Release"
                summary: "Long term support release"
                kind: lts
                eol: 2027-06-30
                category: camel
                ---
                Body text here.
                """);

        LocalDate today = LocalDate.of(2026, 4, 20);
        List<CamelRelease> active = VersionResolver.activeVersions(tempDir, today);

        // 4.10 is expired (eol 2026-02-10 < today 2026-04-20), should be excluded
        assertTrue(active.stream().noneMatch(r -> r.minor().equals("4.10")),
                "Expired LTS 4.10 should be excluded");
        assertTrue(active.stream().anyMatch(r -> r.minor().equals("4.14")),
                "Active LTS 4.14 should be included");
    }

    // ── 3. includesLatestNonLts ─────────────────────────────────────────

    @Test
    void includesLatestNonLts() throws Exception {
        Path releasesDir = tempDir.resolve("content/releases");
        Files.createDirectories(releasesDir);

        Files.writeString(releasesDir.resolve("release-4.18.0.md"), """
                ---
                date: 2026-03-01
                draft: false
                title: "Camel 4.18.0 (LTS) Release"
                summary: "Long term support release"
                kind: lts
                eol: 2027-12-31
                category: camel
                ---
                Body.
                """);

        Files.writeString(releasesDir.resolve("release-4.19.0.md"), """
                ---
                date: 2026-04-01
                draft: false
                title: "Camel 4.19.0 Release"
                summary: "Latest non-LTS release"
                category: camel
                ---
                Body.
                """);

        Files.writeString(releasesDir.resolve("release-4.17.0.md"), """
                ---
                date: 2026-02-01
                draft: false
                title: "Camel 4.17.0 Release"
                summary: "Older non-LTS release"
                category: camel
                ---
                Body.
                """);

        LocalDate today = LocalDate.of(2026, 4, 20);
        List<CamelRelease> active = VersionResolver.activeVersions(tempDir, today);

        // Should include LTS 4.18 and latest non-LTS 4.19, but not older non-LTS 4.17
        assertTrue(active.stream().anyMatch(r -> r.minor().equals("4.18")),
                "Active LTS 4.18 should be included");
        assertTrue(active.stream().anyMatch(r -> r.minor().equals("4.19")),
                "Latest non-LTS 4.19 should be included");
        assertTrue(active.stream().noneMatch(r -> r.minor().equals("4.17")),
                "Older non-LTS 4.17 should be excluded");
    }

    // ── 4. ignoresNonCamelReleases ──────────────────────────────────────

    @Test
    void ignoresNonCamelReleases() throws Exception {
        Path releasesDir = tempDir.resolve("content/releases");
        Files.createDirectories(releasesDir);

        // A normal camel release at the top level
        Files.writeString(releasesDir.resolve("release-4.14.0.md"), """
                ---
                date: 2026-01-15
                draft: false
                title: "Camel 4.14.0 (LTS) Release"
                summary: "Long term support release"
                kind: lts
                eol: 2027-06-30
                category: camel
                ---
                Body.
                """);

        // A quarkus release in a subdirectory
        Path quarkusDir = releasesDir.resolve("q");
        Files.createDirectories(quarkusDir);
        Files.writeString(quarkusDir.resolve("release-3.27.0.md"), """
                ---
                date: 2026-01-20
                draft: false
                title: "Camel Quarkus 3.27.0 Release"
                summary: "Quarkus extension release"
                category: camel-quarkus
                ---
                Body.
                """);

        List<CamelRelease> releases = VersionResolver.parseReleases(tempDir);

        // Should only include the camel release, not the quarkus one
        assertEquals(1, releases.size());
        assertEquals("4.14", releases.get(0).minor());
    }

    // ── 5. findsLatestTagForMinor ───────────────────────────────────────

    @Test
    void findsLatestTagForMinor() {
        List<String> tags = List.of(
                "camel-4.14.0", "camel-4.14.1", "camel-4.14.2", "camel-4.14.3",
                "camel-4.10.0", "camel-4.10.1",
                "camel-4.18.0");

        String latest = VersionResolver.findLatestTag(tags, "camel-", "4.14");
        assertEquals("camel-4.14.3", latest);

        String latest410 = VersionResolver.findLatestTag(tags, "camel-", "4.10");
        assertEquals("camel-4.10.1", latest410);

        String latest418 = VersionResolver.findLatestTag(tags, "camel-", "4.18");
        assertEquals("camel-4.18.0", latest418);
    }

    // ── 6. findsLatestTagWithDifferentPrefixes ──────────────────────────

    @Test
    void findsLatestTagWithDifferentPrefixes() {
        // Spring Boot tags use "camel-spring-boot-" prefix
        List<String> springTags = List.of(
                "camel-spring-boot-4.14.0", "camel-spring-boot-4.14.1",
                "camel-spring-boot-4.14.2",
                "camel-spring-boot-4.10.0");

        String latestSpring = VersionResolver.findLatestTag(springTags, "camel-spring-boot-", "4.14");
        assertEquals("camel-spring-boot-4.14.2", latestSpring);

        // Quarkus tags have no prefix (just version numbers)
        List<String> quarkusTags = List.of(
                "3.27.0", "3.27.1", "3.27.2",
                "3.33.0", "3.33.1");

        String latestQuarkus = VersionResolver.findLatestTag(quarkusTags, "", "3.27");
        assertEquals("3.27.2", latestQuarkus);

        String latestQuarkus33 = VersionResolver.findLatestTag(quarkusTags, "", "3.33");
        assertEquals("3.33.1", latestQuarkus33);
    }

    // ── 7. parsesCamelVersionFromQuarkusPom ─────────────────────────────

    @Test
    void parsesCamelVersionFromQuarkusPom() {
        String pomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.apache.camel.quarkus</groupId>
                    <artifactId>camel-quarkus</artifactId>
                    <version>3.27.0</version>
                    <properties>
                        <camel.major.minor>4.14</camel.major.minor>
                        <camel.version>${camel.major.minor}.0</camel.version>
                    </properties>
                </project>
                """;

        QuarkusMapping mapping = VersionResolver.parseQuarkusPom(pomXml);

        assertNotNull(mapping);
        assertEquals("4.14", mapping.camelMinor());
        assertEquals("4.14.0", mapping.camelVersion());
    }

    // ── 8. parsesCamelVersionWithPatchBump ──────────────────────────────

    @Test
    void parsesCamelVersionWithPatchBump() {
        String pomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.apache.camel.quarkus</groupId>
                    <artifactId>camel-quarkus</artifactId>
                    <version>3.33.1</version>
                    <properties>
                        <camel.major.minor>4.18</camel.major.minor>
                        <camel.version>${camel.major.minor}.4</camel.version>
                    </properties>
                </project>
                """;

        QuarkusMapping mapping = VersionResolver.parseQuarkusPom(pomXml);

        assertNotNull(mapping);
        assertEquals("4.18", mapping.camelMinor());
        assertEquals("4.18.4", mapping.camelVersion());
    }

    // ── 9. parsesCamelVersionWithoutInterpolation ───────────────────────

    @Test
    void parsesCamelVersionWithoutInterpolation() {
        String pomXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.apache.camel.quarkus</groupId>
                    <artifactId>camel-quarkus</artifactId>
                    <version>3.27.2</version>
                    <properties>
                        <camel.major.minor>4.14</camel.major.minor>
                        <camel.version>4.14.2</camel.version>
                    </properties>
                </project>
                """;

        QuarkusMapping mapping = VersionResolver.parseQuarkusPom(pomXml);

        assertNotNull(mapping);
        assertEquals("4.14", mapping.camelMinor());
        assertEquals("4.14.2", mapping.camelVersion());
    }
}
