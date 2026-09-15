package io.github.luigidemasi.camelkit.knowledge.indexer.parser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CveEnrichmentTest {
    private static final String CVE_ID = "CVE-2024-22369";
    // Same NVD record shape returned by CIRCL's /api/vulnerability/fkie_cve-2024-22369 endpoint.
    private static final String RECORD
            = """
                    {"id":"CVE-2024-22369","descriptions":[{"value":"CWE-999 is not a classification"}],
                     "metrics":{
                       "cvssMetricV40":[{"cvssData":{"baseScore":9.3,"vectorString":"CVSS:4.0/example"}}],
                       "cvssMetricV31":[{"cvssData":{"baseScore":7.8,"vectorString":"CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:H"}}]},
                     "weaknesses":[{"description":[{"lang":"en","value":"CWE-502"}]}]}
                    """;
    private static final String NVD_RESPONSE = "{\"vulnerabilities\":[{\"cve\":" + RECORD + "}]}";

    @TempDir
    Path cacheDir;
    private HttpServer server;
    private ExecutorService executor;
    private CveParser.EnrichmentClient client;
    private CveParser.CveAdvisory advisory;
    private final AtomicInteger nvdRequests = new AtomicInteger();
    private final AtomicInteger circlRequests = new AtomicInteger();
    private final AtomicLong nvdRequestNanos = new AtomicLong();
    private final AtomicLong circlRequestNanos = new AtomicLong();
    private int nvdStatus = 200;
    private String nvdBody = NVD_RESPONSE;
    private int circlStatus = 200;
    private String circlBody = RECORD;
    private String retryAfter = "60";

    @BeforeEach
    void start() throws Exception {
        advisory = CveParser.parse(Files.readString(Path.of("src/test/resources/test-cve.md")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/nvd", exchange -> {
            nvdRequests.incrementAndGet();
            nvdRequestNanos.set(System.nanoTime());
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
            respond(exchange, nvdStatus, nvdBody);
        });
        server.createContext("/circl/fkie_cve-2024-22369", exchange -> {
            circlRequests.incrementAndGet();
            circlRequestNanos.set(System.nanoTime());
            respond(exchange, circlStatus, circlBody);
        });
        server.start();
        client = client(Duration.ofSeconds(2), Duration.ZERO);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void prefersNvdAndReusesLegacyCache() throws Exception {
        Files.writeString(cacheFile(), NVD_RESPONSE);
        assertEnriched(enrich());
        assertEquals(0, nvdRequests.get());
        Files.delete(cacheFile());
        assertEnriched(enrich());
        assertEquals("NVD", new JSONObject(Files.readString(cacheFile())).getString("enrichmentSource"));
        assertEquals(1, nvdRequests.get());
        assertEquals(0, circlRequests.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 429, 500, 503})
    void fallsBackAndCachesMirrorOnNvdHttpFailure(int status) throws Exception {
        nvdStatus = status;
        assertEnriched(enrich());
        JSONObject cached = new JSONObject(Files.readString(cacheFile()));
        assertEquals("CIRCL/FKIE NVD", cached.getString("enrichmentSource"));
        assertTrue(cached.getString("enrichmentUrl").endsWith("/circl/fkie_cve-2024-22369"));
        assertNotNull(cached.getString("enrichmentFetchedAt"));
        assertEnriched(enrich());
        assertEquals(1, nvdRequests.get());
        assertEquals(1, circlRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<html>unavailable</html>", "{}", "{\"vulnerabilities\":[]}",
            "{\"vulnerabilities\":[{\"cve\":{\"id\":\"CVE-2024-99999\"}}]}"})
    void ignoresInvalidCacheAndFallsBackFromInvalidNvdResponses(String invalid) throws Exception {
        Files.writeString(cacheFile(), invalid);
        nvdBody = invalid;
        assertEnriched(enrich());
        assertEquals(1, nvdRequests.get());
        assertEquals(1, circlRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not json", "{\"id\":\"CVE-2024-99999\"}"})
    void rejectsInvalidMirrorDataWithoutCaching(String invalid) {
        nvdStatus = 503;
        circlBody = invalid;
        assertSame(advisory, enrich());
        assertFalse(Files.exists(cacheFile()));
    }

    @Test
    void retainsAdvisoryWhenBothServicesFailAndRetriesNextBuild() {
        nvdStatus = 500;
        circlStatus = 429;
        assertSame(advisory, enrich());
        assertFalse(Files.exists(cacheFile()));
        client = client(Duration.ofSeconds(2), Duration.ZERO);
        circlStatus = 200;
        assertEnriched(enrich());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void respectsNvdRetryAfterAcrossLookups(boolean httpDate) throws Exception {
        retryAfter = httpDate ? ZonedDateTime.now().plusMinutes(2).format(DateTimeFormatter.RFC_1123_DATE_TIME) : "120";
        nvdStatus = 429;
        assertEnriched(enrich());
        Files.delete(cacheFile());
        assertEnriched(enrich());
        assertEquals(1, nvdRequests.get());
        assertEquals(2, circlRequests.get());
    }

    @Test
    void boundsStalledNvdResponseBodyAndFallsBack() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.removeContext("/nvd");
        server.createContext("/nvd", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                releaseBody.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        client = client(Duration.ofSeconds(1), Duration.ZERO);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertEnriched(enrich()));
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void fallsBackOnConnectionFailure() {
        server.removeContext("/nvd");
        server.createContext("/nvd", HttpExchange::close);
        assertEnriched(enrich());
        assertEquals(1, circlRequests.get());
    }

    @Test
    void spacesUncachedRequests() {
        nvdStatus = 500;
        client = client(Duration.ofSeconds(2), Duration.ofMillis(150));
        assertEnriched(enrich());
        assertTrue(circlRequestNanos.get() - nvdRequestNanos.get() >= Duration.ofMillis(100).toNanos());
    }

    @Test
    void preservesInterruptWithoutContactingEitherService() {
        Thread.currentThread().interrupt();
        try {
            assertSame(advisory, enrich());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, nvdRequests.get());
            assertEquals(0, circlRequests.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void keepsDownloadedEnrichmentIfCacheCannotBeWritten() throws Exception {
        Path file = cacheDir.resolve("not-a-directory");
        Files.writeString(file, "occupied");
        assertEnriched(CveParser.enrichWithNvd(advisory, file, client));
    }

    @Test
    void ignoresMalformedOptionalWeaknessData() {
        JSONObject record = new JSONObject(RECORD).put("weaknesses",
                new org.json.JSONArray("[null,{}, {\"description\":[null,{}]}]"));
        nvdBody = "{\"vulnerabilities\":[{\"cve\":" + record + "}]}";
        CveParser.CveAdvisory actual = enrich();
        assertEquals(advisory.description(), actual.description());
        assertEquals("7.8", actual.cvssScore());
        assertNull(actual.cweId());
    }

    private CveParser.EnrichmentClient client(Duration timeout, Duration interval) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CveParser.EnrichmentClient(base + "/nvd?cveId=", base + "/circl/fkie_", timeout, interval);
    }

    private CveParser.CveAdvisory enrich() {
        return CveParser.enrichWithNvd(advisory, cacheDir, client);
    }

    private Path cacheFile() {
        return cacheDir.resolve(CVE_ID + ".json");
    }

    private void assertEnriched(CveParser.CveAdvisory actual) {
        assertEquals("7.8", actual.cvssScore());
        assertEquals("CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:H", actual.cvssVector());
        assertEquals("CWE-502", actual.cweId());
        assertEquals(advisory.description(), actual.description());
        assertEquals(advisory.fixedVersions(), actual.fixedVersions());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
