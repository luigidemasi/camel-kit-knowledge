package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingCacheTest {

    @TempDir
    Path dir;

    @Test
    void missThenHitRoundtrip() throws Exception {
        EmbeddingCache cache = new EmbeddingCache(dir, "model-a");
        assertNull(cache.get("some chunk text"));

        float[] vector = {0.1f, -0.5f, 3.25f};
        cache.put("some chunk text", vector);
        assertArrayEquals(vector, cache.get("some chunk text"));
    }

    @Test
    void keyVariesWithModelAndText() throws Exception {
        EmbeddingCache cacheA = new EmbeddingCache(dir, "model-a");
        EmbeddingCache cacheB = new EmbeddingCache(dir, "model-b");

        cacheA.put("text", new float[]{1f});
        assertNull(cacheB.get("text"), "Different model must not hit the same entry");
        assertNull(cacheA.get("other text"), "Different text must not hit the same entry");
    }

    @Test
    void corruptedEntryIsAMiss() throws Exception {
        EmbeddingCache cache = new EmbeddingCache(dir, "model-a");
        cache.put("text", new float[]{1f, 2f});

        // Truncate the entry to a non-float-aligned size
        Path entry;
        try (var files = Files.list(dir)) {
            entry = files.findFirst().orElseThrow();
        }
        Files.write(entry, new byte[]{1, 2, 3});

        assertNull(cache.get("text"));
    }

    @Test
    void changingMaxLengthInvalidatesEntries() throws Exception {
        String original = System.getProperty("embedding.maxLength");
        try {
            System.setProperty("embedding.maxLength", "512");
            EmbeddingCache cache512 = new EmbeddingCache(dir, "model-a");
            cache512.put("text", new float[]{1f, 2f});
            assertArrayEquals(new float[]{1f, 2f}, cache512.get("text"));

            // A cache built under a different truncation window must not serve the old vectors —
            // the embedding of the same text differs when the window changes
            System.setProperty("embedding.maxLength", "2048");
            EmbeddingCache cache2048 = new EmbeddingCache(dir, "model-a");
            assertNull(cache2048.get("text"));
        } finally {
            if (original != null) {
                System.setProperty("embedding.maxLength", original);
            } else {
                System.clearProperty("embedding.maxLength");
            }
        }
    }
}
