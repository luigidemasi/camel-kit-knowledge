package io.github.luigidemasi.camelkit.knowledge.mcp;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for knowledge index resolution. The index is distributed as a GitHub Release asset described by a small
 * JSON manifest; the MCP server downloads it once into a local cache and opens it in place.
 *
 * Resolution order: {@code path} (local dir, no network) → {@code url} manifest + local cache → classpath fallback.
 */
@ConfigMapping(prefix = "knowledge.index")
public interface IndexResolverConfig {

    /** Direct path to a Lucene index directory — used as-is, no download. Intended for dev, tests, air-gapped use. */
    Optional<String> path();

    /** Manifest URL (https:// or file://). Defaults to the latest GitHub release of camel-kit-knowledge. */
    @WithDefault("https://github.com/luigidemasi/camel-kit-knowledge/releases/latest/download/index.json")
    String url();

    /** Local cache directory holding downloaded index versions. */
    @WithDefault("${user.home}/.camel-kit/knowledge-index")
    String cacheDir();
}
