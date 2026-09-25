package com.dierks.craftbridge.pack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in web server serves the pack and nothing else; the URL check tells a stale upload apart. */
class PackHttpServerTest {

    private static final byte[] PACK = "the pack".getBytes(StandardCharsets.UTF_8);

    private PackHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private String base() throws IOException {
        server = PackHttpServer.start("127.0.0.1", 0, PACK);
        return "http://127.0.0.1:" + server.port();
    }

    private static HttpResponse<byte[]> send(String url, String method) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    @Test
    void servesThePackAtItsPathWithAnyQuery() throws Exception {
        String base = base();
        HttpResponse<byte[]> response = send(base + PackHttpServer.PATH + "?v=abc", "GET");
        assertEquals(200, response.statusCode());
        assertArrayEquals(PACK, response.body());
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void everythingElseIsNotFoundOrNotAllowed() throws Exception {
        String base = base();
        assertEquals(404, send(base + "/", "GET").statusCode());
        assertEquals(404, send(base + "/../config.yml", "GET").statusCode());
        assertEquals(404, send(base + PackHttpServer.PATH + "/x", "GET").statusCode());
        assertEquals(405, send(base + PackHttpServer.PATH, "POST").statusCode());
        HttpResponse<byte[]> head = send(base + PackHttpServer.PATH, "HEAD");
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
    }

    @Test
    void stopFreesThePort() throws Exception {
        base();
        int port = server.port();
        server.stop();
        server = PackHttpServer.start("127.0.0.1", port, PACK);
        assertEquals(port, server.port());
    }

    @Test
    void theUrlCarriesTheHashAndAPortUnlessTheAddressNamesOne() {
        assertEquals("http://play.example.com:8765/craftbridge-java.zip?v=ab12",
                PackHttpServer.url("play.example.com", 8765, "ab12"));
        assertEquals("http://play.example.com:25580/craftbridge-java.zip?v=ab12",
                PackHttpServer.url(" play.example.com:25580 ", 8765, "ab12"));
        assertEquals("http://203.0.113.7:8765/craftbridge-java.zip?v=ab12", PackHttpServer.url("203.0.113.7", 8765, "ab12"));
        assertEquals("http://[2001:db8::1]:8765/craftbridge-java.zip?v=ab12", PackHttpServer.url("2001:db8::1", 8765, "ab12"));
        assertEquals("http://[2001:db8::1]:9000/craftbridge-java.zip?v=ab12", PackHttpServer.url("[2001:db8::1]:9000", 8765, "ab12"));
    }

    @Test
    void theUrlCheckMatchesTheSamePack() throws Exception {
        String url = base() + PackHttpServer.PATH;
        PackUrlCheck.Result result = PackUrlCheck.check(url, PackFiles.sha1(PACK), Duration.ofSeconds(5));
        assertTrue(result.matches(), String.valueOf(result));
        assertNull(result.problem());
    }

    @Test
    void theUrlCheckCatchesAStaleUpload() throws Exception {
        String url = base() + PackHttpServer.PATH;
        PackUrlCheck.Result result = PackUrlCheck.check(url, PackFiles.sha1("a newer pack".getBytes(StandardCharsets.UTF_8)),
                Duration.ofSeconds(5));
        assertFalse(result.matches());
        assertEquals(PackFiles.sha1(PACK), result.sha1(), "reports the hash it found");
    }

    @Test
    void theUrlCheckReportsWhatWentWrong() throws Exception {
        String base = base();
        PackUrlCheck.Result missing = PackUrlCheck.check(base + "/nothing-here.zip", "00", Duration.ofSeconds(5));
        assertFalse(missing.matches());
        assertNull(missing.sha1());
        assertTrue(missing.problem().contains("404"), missing.problem());
        PackUrlCheck.Result bad = PackUrlCheck.check("not a url", "00", Duration.ofSeconds(5));
        assertNotNull(bad.problem());
        server.stop();
        PackUrlCheck.Result down = PackUrlCheck.check(base + PackHttpServer.PATH, "00", Duration.ofSeconds(5));
        assertNotNull(down.problem(), "a server that is gone is a problem, not an exception");
        server = null;
    }
}
