package com.campusshare.clientagent;

import com.campusshare.config.Config;
import com.campusshare.concurrency.TransferTracker;
import com.campusshare.discovery.Discovery;
import com.campusshare.security.Tls;
import com.campusshare.storage.FileStore;
import com.campusshare.transfer.TransferClient;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.*;
import static com.campusshare.transfer.Protocol.JSON;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

public final class LocalHttp implements AutoCloseable {
    private final TransferTracker transfers = new TransferTracker();
    private final AgentConnection agent;
    private final HttpServer http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore requests = new Semaphore(32);
    private final Semaphore streams = new Semaphore(6);
    private final String origin = Config.value("FRONTEND_ORIGIN", "http://localhost:5173");

    record Connect(String displayName, String host, int port) {}
    record State(boolean connected, boolean tlsVerified, String displayName, String host, int port,
                 boolean eventsConnected, int onlineCount, int activeWorkers, int queuedRequests) {}

    public LocalHttp(int port) throws Exception {
        this(port, Tls.clientContext());
    }

    public LocalHttp(int port, SSLContext trust) throws IOException {
        agent = new AgentConnection(trust, transfers::reset);
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        } catch (IOException error) {
            agent.close();
            throw error;
        }
        http.setExecutor(executor);
        http.createContext("/api/", this::handle);
    }

    public void start() { http.start(); }
    public int port() { return http.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getRequestHeaders();
            String requestOrigin = headers.getFirst("Origin");
            int port = port();
            String host = headers.getFirst("Host");
            if (!Set.of("127.0.0.1:" + port, "localhost:" + port).contains(Objects.toString(host, ""))
                    || (requestOrigin != null && !origin.equals(requestOrigin))
                    || (requestOrigin == null && "cross-site".equals(headers.getFirst("Sec-Fetch-Site")))) {
                error(exchange, 403, "Unexpected local API origin");
                return;
            }
            if (exchange.getRequestMethod().equals("POST")
                    && !"1".equals(headers.getFirst("X-CampusShare-Request"))) {
                error(exchange, 403, "Missing CampusShare request header");
                return;
            }
            var response = exchange.getResponseHeaders();
            response.set("Cache-Control", "no-store");
            response.set("X-Content-Type-Options", "nosniff");
            response.set("Vary", "Origin");
            if (origin.equals(requestOrigin)) response.set("Access-Control-Allow-Origin", origin);
            if (exchange.getRequestMethod().equals("OPTIONS")) {
                response.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                response.set("Access-Control-Allow-Headers", "Content-Type, X-Filename, X-Transfer-Id, X-CampusShare-Request");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!requests.tryAcquire()) {
                error(exchange, 503, "Local agent is busy");
                return;
            }
            try {
                route(exchange);
            } catch (HttpError failure) {
                error(exchange, failure.status, failure.getMessage());
            } catch (JsonParseException | IllegalArgumentException failure) {
                error(exchange, 400, failure.getMessage());
            } catch (SSLHandshakeException failure) {
                error(exchange, 502, "TLS handshake failed. Check the server certificate, address and truststore.");
            } catch (TransferClient.Rejected failure) {
                error(exchange, 422, failure.getMessage());
            } catch (IOException failure) {
                if (exchange.getResponseCode() == -1) error(exchange, 502, "Network or transfer operation failed");
            } finally {
                requests.release();
            }
        }
    }

    private void route(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        switch (x.getRequestMethod() + " " + path) {
            case "GET /api/health" -> json(x, 200, Map.of("ready", true));
            case "GET /api/status" -> json(x, 200, state(agent.current()));
            case "GET /api/discovery" -> json(x, 200, Discovery.search(Config.discoveryPort()));
            case "POST /api/connect" -> connect(x);
            case "POST /api/disconnect" -> {
                agent.disconnect();
                json(x, 200, state(null));
            }
            case "GET /api/files" -> json(x, 200, requireConnection().client.list());
            case "POST /api/files/upload" -> upload(x);
            case "GET /api/transfers" -> json(x, 200, transfers.list());
            case "GET /api/events" -> events(x);
            default -> {
                if (x.getRequestMethod().equals("GET") && path.matches("/api/files/[0-9a-f-]{36}/download")) {
                    download(x, path.split("/")[3]);
                } else if (x.getRequestMethod().equals("POST") && path.matches("/api/transfers/[0-9a-f-]{36}/cancel")) {
                    if (!transfers.cancel(path.split("/")[3])) throw new HttpError(404, "No active transfer with this ID");
                    json(x, 200, Map.of("cancelled", true));
                } else {
                    error(x, 404, "Endpoint unavailable");
                }
            }
        }
    }

    private void connect(HttpExchange x) throws IOException {
        byte[] bytes = x.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) throw new HttpError(413, "Connection request is too large");
        var input = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Connect.class);
        if (input == null || input.displayName() == null || input.host() == null) {
            throw new HttpError(400, "Display name and server address are required");
        }
        String name = input.displayName().strip();
        String host = input.host().strip();
        if (name.isBlank() || name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl)
                || !host.matches("[a-zA-Z0-9.:%-]{1,253}") || input.port() < 1 || input.port() > 65535) {
            throw new HttpError(400, "Use a display name of 1–40 characters, a server host and a port from 1–65535");
        }
        json(x, 200, state(agent.connect(name, host, input.port())));
    }

    private static State state(AgentConnection.Connection connection) {
        if (connection == null) return new State(false, false, null, null, 0, false, 0, 0, 0);
        var status = connection.status;
        return new State(true, connection.tlsVerified, connection.displayName, connection.host, connection.port,
                connection.eventsConnected, status.onlineCount(), status.activeWorkers(), status.queuedRequests());
    }

    private AgentConnection.Connection requireConnection() throws HttpError {
        var connection = agent.current();
        if (connection == null) throw new HttpError(409, "Connect to a CampusShare server first");
        return connection;
    }

    private void upload(HttpExchange x) throws IOException {
        var headers = x.getRequestHeaders();
        String filename = URLDecoder.decode(Objects.toString(headers.getFirst("X-Filename"), ""), StandardCharsets.UTF_8);
        if (!FileStore.validName(filename)) throw new HttpError(400, "Invalid filename");
        String length = headers.getFirst("Content-Length");
        if (length == null) throw new HttpError(411, "File length is required");
        long size = Long.parseLong(length);
        var connection = requireConnection();
        if (size < 0 || size > Math.min(connection.status.maxFileBytes(), Config.maxFile())) {
            throw new HttpError(413, "File exceeds size limit");
        }
        var transfer = begin(connection, transferId(headers.getFirst("X-Transfer-Id")), "Upload", filename, size);
        try {
            var file = connection.client.upload(filename, size, x.getRequestBody(), transfer::progress, transfer::attach);
            transfer.finish("Verified");
            json(x, 201, file);
        } finally {
            transfer.finish("Failed");
        }
    }

    private void download(HttpExchange x, String id) throws IOException {
        var connection = requireConnection();
        String query = x.getRequestURI().getRawQuery();
        var transfer = begin(connection, transferId(query != null && query.startsWith("transferId=")
                ? query.substring("transferId=".length()) : null), "Download", id, 0);
        Path staged = null;
        try {
            staged = Files.createTempFile("campusshare-", ".part");
            var file = connection.client.download(id, staged,
                    metadata -> transfer.metadata(metadata.filename(), metadata.size()), transfer::progress, transfer::attach);
            var response = x.getResponseHeaders();
            response.set("Content-Type", "application/octet-stream");
            String filename = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
            response.set("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
            x.sendResponseHeaders(200, file.size() == 0 ? -1 : file.size());
            if (file.size() > 0) Files.copy(staged, x.getResponseBody());
            transfer.finish("Verified");
        } finally {
            transfer.finish("Failed");
            if (staged != null) Files.deleteIfExists(staged);
        }
    }

    private TransferTracker.Transfer begin(AgentConnection.Connection connection, String id,
                                           String direction, String filename, long size) throws HttpError {
        synchronized (agent) {
            if (agent.current() != connection) throw new HttpError(409, "Server connection changed");
            return transfers.begin(id, direction, filename, size);
        }
    }

    private static String transferId(String value) {
        return value == null ? UUID.randomUUID().toString() : UUID.fromString(value).toString();
    }

    private void events(HttpExchange x) throws IOException {
        var connection = requireConnection();
        if (!streams.tryAcquire()) throw new HttpError(503, "Too many event streams");
        var events = new ArrayBlockingQueue<com.campusshare.collaboration.Notifications.Event>(16);
        connection.subscribers.add(events);
        try {
            x.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            x.sendResponseHeaders(200, 0);
            var output = x.getResponseBody();
            while (agent.current() == connection) {
                var event = events.poll(2, TimeUnit.SECONDS);
                String frame = event == null ? ": keepalive\n\n" : "data: " + event.name() + "\n\n";
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            connection.subscribers.remove(events);
            streams.release();
        }
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", Objects.toString(message, "Invalid request")));
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] data = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }

    private static final class HttpError extends IOException {
        final int status;
        HttpError(int status, String message) { super(message); this.status = status; }
    }

    @Override public void close() {
        agent.close();
        http.stop(0);
        executor.shutdownNow();
    }
}
