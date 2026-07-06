package io.github.luigidemasi.camelkit.knowledge.mcp;

import io.github.luigidemasi.camelkit.knowledge.embedding.OnnxEmbeddingProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards embedding quality for long inputs. Discovered 2026-07-06: raising the embedding window past the ONNX export's
 * supported positions can silently corrupt document embeddings while short query embeddings stay healthy — vector
 * search then returns noise even though the model stamp matches. This test embeds a short query against long relevant
 * and long unrelated documents at the configured window and fails if ranking degenerates or vectors go non-finite.
 */
@EnabledIf("modelExists")
class EmbeddingLongInputTest {

    static boolean modelExists() {
        return EmbeddingLongInputTest.class.getClassLoader()
                .getResource("models/model_quantized.onnx")
               != null;
    }

    @Test
    void longDocumentEmbeddingsStayDiscriminative() {
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider();

        String query = "how to configure the Kafka component brokers";

        String kafkaFiller = "The Kafka component supports consuming and producing messages with Apache Kafka "
                             + "brokers. Configure the brokers option with a comma separated list of host and port. ";
        String weatherFiller = "The weather today is sunny with a light breeze and mild temperatures across the "
                               + "region. Tomorrow brings scattered clouds and a chance of light rain in the hills. ";

        // ~2600 words ≈ well past 512 tokens, inside the 2048 default window
        String longKafkaDoc = ("Kafka Component > Configuration. " + kafkaFiller.repeat(100));
        String longWeatherDoc = ("Weather Report > Forecast. " + weatherFiller.repeat(100));
        String shortKafkaDoc = "Kafka Component > Configuration. " + kafkaFiller;

        float[] q = provider.embed(query);
        float[] longKafka = provider.embed(longKafkaDoc);
        float[] longWeather = provider.embed(longWeatherDoc);
        float[] shortKafka = provider.embed(shortKafkaDoc);

        assertAllFinite(q);
        assertAllFinite(longKafka);
        assertAllFinite(longWeather);

        double simLongKafka = cosine(q, longKafka);
        double simLongWeather = cosine(q, longWeather);
        double simShortKafka = cosine(q, shortKafka);

        assertTrue(simShortKafka > 0.5,
                "Short relevant doc must be similar to the query, got " + simShortKafka);
        assertTrue(simLongKafka > simLongWeather + 0.1,
                "Long relevant doc must clearly outscore long unrelated doc: kafka=" + simLongKafka
                                                        + " weather=" + simLongWeather);
        assertTrue(simLongKafka > 0.4,
                "Long relevant doc similarity collapsed (broken long-input embeddings?): " + simLongKafka);
    }

    private static void assertAllFinite(float[] v) {
        for (float x : v) {
            assertTrue(Float.isFinite(x), "Embedding contains non-finite values");
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
