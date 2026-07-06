package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disk cache for chunk embeddings, keyed by sha256(modelId + maxLength + text). Re-embedding all ~26k chunks takes
 * hours; with this cache an incremental rebuild only embeds new or changed chunks. Values are raw little-endian float32
 * vectors — corrupted or truncated entries are treated as misses and overwritten.
 *
 * <p>
 * The key includes the model ID and the effective {@code embedding.maxLength} because both change the resulting vector:
 * switching model or truncation window naturally invalidates the whole cache.
 */
public class EmbeddingCache {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingCache.class);

    private final Path dir;
    private final String keyPrefix;

    public EmbeddingCache(Path dir, String modelId) throws IOException {
        this.dir = dir;
        this.keyPrefix = modelId + '\0' + Integer.getInteger("embedding.maxLength", 2048) + '\0';
        Files.createDirectories(dir);
    }

    /** Cached vector for the text, or null on miss/corruption. */
    public float[] get(String text) {
        Path file = dir.resolve(key(text) + ".vec");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0 || bytes.length % Float.BYTES != 0) {
                return null; // corrupted — treat as miss
            }
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[bytes.length / Float.BYTES];
            buf.asFloatBuffer().get(vector);
            return vector;
        } catch (IOException e) {
            return null;
        }
    }

    public void put(String text, float[] vector) {
        Path file = dir.resolve(key(text) + ".vec");
        ByteBuffer buf = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(vector);
        try {
            Files.write(file, buf.array());
        } catch (IOException e) {
            LOG.warn("Failed to write embedding cache entry: {}", e.getMessage());
        }
    }

    private String key(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((keyPrefix + text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
