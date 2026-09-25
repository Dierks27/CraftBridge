package com.dierks.craftbridge.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The built-in web server ({@code resource-pack.host}): serves one file, the Java pack, at
 * {@link #PATH}, and answers 404 to everything else. It holds the zip in memory, so a
 * download never touches the disk and cannot be pointed at any other file.
 */
public final class PackHttpServer {

    public static final String PATH = "/craftbridge-java.zip";

    private final HttpServer server;
    private final ExecutorService executor;

    private PackHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Start serving {@code zip} on {@code bindAddress:port} (port 0 picks a free one).
     *
     * @throws IOException when the port is taken or cannot be bound
     */
    public static PackHttpServer start(String bindAddress, int port, byte[] zip) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 16);
        ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "CraftBridge pack server");
            thread.setDaemon(true);
            return thread;
        });
        byte[] body = zip.clone();
        server.createContext("/", exchange -> {
            try (exchange) {
                handle(exchange, body);
            }
        });
        server.setExecutor(executor);
        server.start();
        return new PackHttpServer(server, executor);
    }

    private static void handle(HttpExchange exchange, byte[] body) throws IOException {
        String method = exchange.getRequestMethod();
        boolean head = method.equalsIgnoreCase("HEAD");
        if (!exchange.getRequestURI().getPath().equals(PATH)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        if (!head && !method.equalsIgnoreCase("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        // The URL carries the pack's hash, so a changed pack is a new URL: caching is safe.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        if (head) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** The port actually bound (useful when started on port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Stop accepting downloads and free the port; running downloads get a second to finish. */
    public void stop() {
        server.stop(1);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The URL players download from, with the hash as a query so a changed pack is never
     * served from a cache. {@code publicAddress} is a host name or IP; one that already names
     * a port ({@code play.example.com:25580}, for a port relayed by a proxy) keeps it.
     */
    public static String url(String publicAddress, int port, String sha1) {
        String address = publicAddress.trim();
        long colons = address.chars().filter(c -> c == ':').count();
        String authority;
        if (address.startsWith("[")) {
            authority = address.contains("]:") ? address : address + ":" + port; // [IPv6] or [IPv6]:port
        } else if (colons > 1) {
            authority = "[" + address + "]:" + port; // a bare IPv6 address
        } else if (colons == 1) {
            authority = address; // host:port
        } else {
            authority = address + ":" + port;
        }
        return "http://" + authority + PATH + "?v=" + sha1;
    }
}
