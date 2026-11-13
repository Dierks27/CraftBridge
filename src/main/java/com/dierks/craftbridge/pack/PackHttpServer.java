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
 * The built-in web server ({@code resource-pack.host}): serves the pack at
 * {@code /pack/<sha1>.zip} (the address players are sent, so no cache ever hands out an old
 * pack) and at {@link #PATH} (the address older CraftBridge versions used), and answers 404 to
 * everything else. It holds the zip in memory, so a download never touches the disk and cannot
 * be pointed at any other file. {@link #update} swaps in a rebuilt pack; the one before stays
 * downloadable at its own hash, for a client that was sent it a moment earlier.
 */
public final class PackHttpServer {

    public static final String PATH = "/craftbridge-java.zip";
    /** {@code /pack/<sha1>.zip}. */
    public static final String HASHED_PREFIX = "/pack/";

    /** The current pack and the one before it. */
    private record Content(String sha1, byte[] zip, String previousSha1, byte[] previousZip) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final java.util.concurrent.atomic.AtomicReference<Content> content;

    private PackHttpServer(HttpServer server, ExecutorService executor,
                           java.util.concurrent.atomic.AtomicReference<Content> content) {
        this.server = server;
        this.executor = executor;
        this.content = content;
    }

    /** Serve this pack from now on (its SHA-1 is worked out here). */
    public void update(byte[] zip) {
        byte[] copy = zip.clone();
        String sha1 = PackFiles.sha1(copy);
        content.updateAndGet(old -> old == null || old.sha1().equals(sha1) ? new Content(sha1, copy,
                old == null ? null : old.previousSha1(), old == null ? null : old.previousZip())
                : new Content(sha1, copy, old.sha1(), old.zip()));
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
        java.util.concurrent.atomic.AtomicReference<Content> content = new java.util.concurrent.atomic.AtomicReference<>(
                new Content(PackFiles.sha1(body), body, null, null));
        server.createContext("/", exchange -> {
            try (exchange) {
                handle(exchange, content.get());
            }
        });
        server.setExecutor(executor);
        server.start();
        return new PackHttpServer(server, executor, content);
    }

    private static void handle(HttpExchange exchange, Content content) throws IOException {
        String method = exchange.getRequestMethod();
        boolean head = method.equalsIgnoreCase("HEAD");
        String path = exchange.getRequestURI().getPath();
        byte[] body = null;
        if (path.equals(PATH)) {
            body = content.zip();
        } else if (path.equals(HASHED_PREFIX + content.sha1() + ".zip")) {
            body = content.zip();
        } else if (content.previousSha1() != null && path.equals(HASHED_PREFIX + content.previousSha1() + ".zip")) {
            body = content.previousZip();
        }
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        if (!head && !method.equalsIgnoreCase("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        // A hashed URL never changes content, so caching it is safe; the fixed old path must be re-checked.
        exchange.getResponseHeaders().set("Cache-Control", path.equals(PATH) ? "no-cache" : "public, max-age=86400");
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
     * The URL players download from, with the hash in the path so a changed pack is never
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
        return "http://" + authority + HASHED_PREFIX + sha1 + ".zip";
    }

    /**
     * The URL players download from when {@code resource-pack.host.public-url} gives the whole
     * address the port is reached at ({@code http://mc.lilahcraft.com:8765}, or an https proxy
     * in front of it): that, then {@code /pack/<sha1>.zip}.
     */
    public static String urlUnder(String publicUrl, String sha1) {
        String base = publicUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + HASHED_PREFIX + sha1 + ".zip";
    }
}
