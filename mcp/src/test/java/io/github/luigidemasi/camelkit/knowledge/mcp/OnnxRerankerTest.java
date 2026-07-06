package io.github.luigidemasi.camelkit.knowledge.mcp;

import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxReranker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the cross-encoder reranker. Requires reranker_quantized.onnx on the classpath — skipped if not downloaded.
 */
@EnabledIf("modelExists")
class OnnxRerankerTest {

    private static OnnxReranker reranker;

    static boolean modelExists() {
        return OnnxRerankerTest.class.getClassLoader()
                .getResource("models/reranker_quantized.onnx")
               != null;
    }

    @BeforeAll
    static void setUp() {
        reranker = new OnnxReranker();
    }

    @Test
    void scoreIsInUnitInterval() {
        float score = reranker.score("kafka consumer configuration",
                "The Kafka component allows messages to be consumed from Apache Kafka brokers.");
        assertTrue(score > 0f && score < 1f, "Sigmoid score must be in (0,1), got: " + score);
    }

    @Test
    void relevantPassageScoresHigherThanIrrelevant() {
        String query = "how to configure SSL for the HTTP component";
        float relevant = reranker.score(query,
                "HTTP Component Security. To configure SSL, set the sslContextParameters option "
                                               + "on the http endpoint with your keystore and truststore.");
        float irrelevant = reranker.score(query,
                "The timer component is used to generate message exchanges when a timer fires.");
        assertTrue(relevant > irrelevant,
                "Relevant passage should outscore irrelevant one: " + relevant + " vs " + irrelevant);
    }

    @Test
    void scoreIsIdempotent() {
        float a = reranker.score("aggregate messages", "The Aggregator EIP combines multiple messages into one.");
        float b = reranker.score("aggregate messages", "The Aggregator EIP combines multiple messages into one.");
        assertEquals(a, b, 0.0f, "Same input should produce same score");
    }
}
