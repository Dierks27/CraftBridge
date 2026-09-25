package com.dierks.craftbridge.pack;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads the pack from {@code resource-pack.url} once at startup and compares it with the
 * pack this jar built, so an upload left over from an older CraftBridge is caught in the
 * console instead of by every player's client (which rejects it on the hash).
 */
public final class PackUrlCheck {

    /** Far above any real pack; a URL answering with more is not our pack. */
    static final int MAX_BYTES = 64 * 1024 * 1024;

    /** What the check found. {@code sha1} is null when nothing could be downloaded. */
    public record Result(boolean matches, String sha1, String problem) {
    }

    private PackUrlCheck() {
    }

    /** Blocking: call it off the main thread. Never throws. */
    public static Result check(String url, String expectedSha1, Duration timeout) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return new Result(false, null, "it is not a valid URL");
        }
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return new Result(false, null, "the server answered HTTP " + response.statusCode());
                }
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    return new Result(false, null, "the file there is larger than " + (MAX_BYTES >> 20) + " MB");
                }
                String sha1 = PackFiles.sha1(bytes);
                return new Result(sha1.equalsIgnoreCase(expectedSha1), sha1, null);
            }
        } catch (IOException ex) {
            return new Result(false, null, ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "the check was interrupted");
        } catch (IllegalArgumentException ex) {
            return new Result(false, null, "it is not a URL a client can download (" + ex.getMessage() + ")");
        } finally {
            client.shutdownNow();
        }
    }
}
