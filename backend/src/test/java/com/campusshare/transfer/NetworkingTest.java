package com.campusshare.transfer;

import com.campusshare.collaboration.Notifications;
import com.campusshare.clientagent.LocalHttp;
import com.campusshare.config.Config;
import com.campusshare.security.Tls;
import com.campusshare.storage.FileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.SSLHandshakeException;
import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.HexFormat;
import java.util.concurrent.*;
import static com.campusshare.transfer.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkingTest {
    @TempDir Path dir;

    @Test void frameSurvivesFragmentedReadsAndRejectsAnOversizedRequest() throws Exception {
        var buffer = new ByteArrayOutputStream();
        var request = new Request("LIST", "test", null, null, 0);
        write(new DataOutputStream(buffer), request);
        var fragmented = new ByteArrayInputStream(buffer.toByteArray()) {
            @Override public synchronized int read(byte[] data, int offset, int length) {
                return super.read(data, offset, Math.min(1, length));
            }
        };
        assertEquals(request, read(new DataInputStream(fragmented), Request.class));
        assertThrows(IOException.class, () -> read(new DataInputStream(
                new ByteArrayInputStream(buffer.toByteArray())), Request.class, 8));
    }

    @Test void checksumAndInterruptKeepPartialFilesOutOfStorage() throws Exception {
        var storage = new FileStore(dir);
        byte[] payload = HexFormat.of().parseHex("616263");
        assertThrows(EOFException.class, () -> storage.save("sample.txt", 10,
                new DataInputStream(new ByteArrayInputStream(payload))));
        var incorrect = new byte[3 + 32];
        assertThrows(IOException.class, () -> storage.save("sample.txt", 3,
                new DataInputStream(new ByteArrayInputStream(incorrect))));
        assertTrue(storage.list().isEmpty());
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test void trustedTlsConcurrentTransfersAndSelectorEventsWorkTogether() throws Exception {
        Path key = dir.resolve("key.p12");
        Path trusted = dir.resolve("trust.p12");
        Path otherKey = dir.resolve("other.p12");
        Path untrusted = dir.resolve("other-trust.p12");
        generate(key, trusted);
        generate(otherKey, untrusted);
        char[] password = "test-fixture".toCharArray();
        Path storagePath = dir.resolve("shared");
        try (var events = new Notifications(0);
             var listener = Tls.listen(0, key, password);
             var server = new TransferServer(listener, new FileStore(storagePath), events, events.port());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            events.start();
            var serverTask = executor.submit(() -> { server.run(); return null; });
            var trust = Tls.clientContext(trusted, password);
            var wrongTrust = Tls.clientContext(untrusted, password);
            var client = new TransferClient(trust, "127.0.0.1", listener.getLocalPort());
            assertThrows(TransferClient.Rejected.class, () -> client.upload("../outside.txt", 0,
                    InputStream.nullInputStream(), n -> {}, socket -> {}));
            assertThrows(TransferClient.Rejected.class, () -> client.upload("too-large.txt", Config.maxFile() + 1,
                    InputStream.nullInputStream(), n -> {}, socket -> {}));
            assertThrows(SSLHandshakeException.class, () -> Tls.connect(wrongTrust, "127.0.0.1", listener.getLocalPort()));
            assertThrows(SSLHandshakeException.class, () -> Tls.connect(trust, "localhost", listener.getLocalPort()));

            try (var first = new Socket("127.0.0.1", events.port());
                 var second = new Socket("127.0.0.1", events.port())) {
                first.setSoTimeout(5000);
                second.setSoTimeout(5000);
                var firstEvents = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.US_ASCII));
                var secondEvents = new BufferedReader(new InputStreamReader(second.getInputStream(), StandardCharsets.US_ASCII));
                awaitEvent(firstEvents, "ONLINE_COUNT_CHANGED");
                awaitEvent(secondEvents, "ONLINE_COUNT_CHANGED");

                int count = Math.min(5, Config.workers());
                var entered = new CountDownLatch(count);
                var release = new CountDownLatch(1);
                var uploads = new java.util.ArrayList<Future<SharedFile>>();
                byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
                try {
                    for (int index = 0; index < count; index++) {
                        int fileNumber = index;
                        uploads.add(executor.submit(() -> {
                            var source = new ByteArrayInputStream(payload) {
                                @Override public synchronized int read(byte[] data, int offset, int length) {
                                    entered.countDown();
                                    try {
                                        if (!release.await(10, TimeUnit.SECONDS)) return -1;
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        return -1;
                                    }
                                    return super.read(data, offset, length);
                                }
                            };
                            return client.upload("file-" + fileNumber + ".txt", payload.length, source, n -> {}, s -> {});
                        }));
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    var status = client.status();
                    assertEquals(2, status.onlineCount());
                    assertEquals(count, status.activeWorkers());
                } finally {
                    release.countDown();
                }
                for (var upload : uploads) assertTrue(upload.get(5, TimeUnit.SECONDS).sha256().startsWith("ba7816bf"));
                awaitEvent(firstEvents, "FILE_LIST_CHANGED");
                awaitEvent(secondEvents, "FILE_LIST_CHANGED");
                assertEquals(count, client.list().length);
                assertEquals(count, new FileStore(storagePath).list().size());

                var file = client.list()[0];
                Path download = dir.resolve("download.txt");
                assertEquals(file, client.download(file.id(), download, f -> {}, n -> {}, s -> {}));
                assertArrayEquals(payload, Files.readAllBytes(download));
                Files.writeString(storagePath.resolve(file.id() + ".bin"), "xyz");
                assertThrows(IOException.class, () -> client.download(file.id(), download, f -> {}, n -> {}, s -> {}));
            } finally {
                server.close();
                serverTask.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void agentConnectsWithTemporaryNameAndStreamsVerifiedFilesAndEvents() throws Exception {
        Path key = dir.resolve("key.p12");
        Path trust = dir.resolve("trust.p12");
        generate(key, trust);
        char[] password = "test-fixture".toCharArray();
        try (var events = new Notifications(0);
             var listener = Tls.listen(0, key, password);
             var server = new TransferServer(listener, new FileStore(dir.resolve("shared")), events, events.port());
             var agent = new LocalHttp(0, Tls.clientContext(trust, password));
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            events.start();
            var serverTask = executor.submit(() -> { server.run(); return null; });
            agent.start();
            try {
                var invalid = request(agent, "connect").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"   \"}")).build();
                assertEquals(400, http.send(invalid, HttpResponse.BodyHandlers.discarding()).statusCode());
                var missingHeader = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/disconnect"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(403, http.send(missingHeader, HttpResponse.BodyHandlers.discarding()).statusCode());
                var crossOrigin = request(agent, "connect").header("Origin", "http://unrelated.invalid")
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(403, http.send(crossOrigin, HttpResponse.BodyHandlers.discarding()).statusCode());

                var connect = request(agent, "connect").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(java.util.Map.of(
                                "displayName", " Sam ", "host", "127.0.0.1", "port", listener.getLocalPort())))).build();
                var connection = http.send(connect, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, connection.statusCode());
                var state = com.google.gson.JsonParser.parseString(connection.body()).getAsJsonObject();
                assertTrue(state.get("tlsVerified").getAsBoolean());
                assertEquals("Sam", state.get("displayName").getAsString());

                boolean subscribed = false;
                for (int attempt = 0; attempt < 50 && !subscribed; attempt++) {
                    var status = http.send(request(agent, "status").GET().build(), HttpResponse.BodyHandlers.ofString());
                    subscribed = com.google.gson.JsonParser.parseString(status.body()).getAsJsonObject()
                            .get("eventsConnected").getAsBoolean();
                    if (!subscribed) Thread.sleep(20);
                }
                assertTrue(subscribed, "Agent did not subscribe to NIO notifications");
                var stream = http.send(request(agent, "events").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, stream.statusCode());
                try (var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                    byte[] file = "CampusShare integration".getBytes(StandardCharsets.UTF_8);
                    var upload = request(agent, "files/upload").header("X-Filename", "sample.txt")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(file)).build();
                    var uploaded = http.send(upload, HttpResponse.BodyHandlers.ofString());
                    assertEquals(201, uploaded.statusCode(), uploaded.body());
                    var metadata = JSON.fromJson(uploaded.body(), SharedFile.class);

                    var downloaded = http.send(request(agent, "files/" + metadata.id() + "/download").GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200, downloaded.statusCode());
                    assertArrayEquals(file, downloaded.body());

                    var event = executor.submit(() -> { awaitEvent(reader, "data: FILE_LIST_CHANGED"); return null; });
                    event.get(5, TimeUnit.SECONDS);
                    verifyInterruptedUpload(http, agent, false);
                    verifyInterruptedUpload(http, agent, true);
                    var disconnect = request(agent, "disconnect").POST(HttpRequest.BodyPublishers.noBody()).build();
                    var disconnected = http.send(disconnect, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, disconnected.statusCode());
                    var cleared = com.google.gson.JsonParser.parseString(disconnected.body()).getAsJsonObject();
                    assertFalse(cleared.get("connected").getAsBoolean());
                    assertFalse(cleared.has("displayName"));
                    assertEquals(409, http.send(request(agent, "files").GET().build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode());
                }
            } finally {
                server.close();
                serverTask.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void verifyInterruptedUpload(HttpClient http, LocalHttp agent, boolean cancel) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        String filename = (cancel ? "cancelled-" : "interrupted-") + id + ".bin";
        try (var socket = new Socket("127.0.0.1", agent.port())) {
            var out = socket.getOutputStream();
            String headers = "POST /api/files/upload HTTP/1.1\r\nHost: 127.0.0.1:" + agent.port()
                    + "\r\nX-CampusShare-Request: 1\r\nX-Filename: " + filename
                    + "\r\nX-Transfer-Id: " + id + "\r\nContent-Length: 524288\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[131072]);
            out.flush();
            awaitTransfer(http, agent, id, "Transferring");
            if (cancel) {
                var request = request(agent, "transfers/" + id + "/cancel")
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                assertEquals(200, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
        awaitTransfer(http, agent, id, cancel ? "Cancelled" : "Failed");
        var files = http.send(request(agent, "files").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(files.body().contains(filename));
    }

    private static void awaitTransfer(HttpClient http, LocalHttp agent, String id, String state) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            var response = http.send(request(agent, "transfers").GET().build(), HttpResponse.BodyHandlers.ofString());
            var records = com.google.gson.JsonParser.parseString(response.body()).getAsJsonArray();
            for (var item : records) {
                var record = item.getAsJsonObject();
                if (id.equals(record.get("id").getAsString()) && state.equals(record.get("state").getAsString())) {
                    assertTrue(record.get("bytes").getAsLong() > 0);
                    assertTrue(record.get("bytes").getAsLong() < record.get("size").getAsLong());
                    return;
                }
            }
            Thread.sleep(50);
        }
        fail("No transfer reached " + state);
    }

    private static HttpRequest.Builder request(LocalHttp agent, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + agent.port() + "/api/" + path))
                .timeout(Duration.ofSeconds(8)).header("X-CampusShare-Request", "1");
    }

    private static void awaitEvent(BufferedReader reader, String expected) throws IOException {
        for (int i = 0; i < 30; i++) if (expected.equals(reader.readLine())) return;
        fail("Expected event: " + expected);
    }

    @Test void rejectsExpiredDirectlyTrustedCertificate() throws Exception {
        Path key = dir.resolve("expired.p12");
        Path trust = dir.resolve("expired-trust.p12");
        generate(key, trust, "-10d");
        char[] password = "test-fixture".toCharArray();
        try (var events = new Notifications(0);
             var listener = Tls.listen(0, key, password);
             var server = new TransferServer(listener, new FileStore(dir.resolve("shared")), events, events.port());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> { server.run(); return null; });
            try {
                assertThrows(SSLHandshakeException.class, () -> Tls.connect(Tls.clientContext(trust, password),
                        "127.0.0.1", listener.getLocalPort()));
            } finally {
                server.close();
                running.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void generate(Path key, Path trust) throws Exception {
        generate(key, trust, "+0d");
    }

    private static void generate(Path key, Path trust, String startDate) throws Exception {
        String tool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process = new ProcessBuilder(tool, "-genkeypair", "-alias", "server", "-keyalg", "EC",
                "-dname", "CN=CampusShare test", "-ext", "SAN=IP:127.0.0.1", "-startdate", startDate, "-validity", "2",
                "-storetype", "PKCS12", "-keystore", key.toString(), "-storepass", "test-fixture")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        char[] password = "test-fixture".toCharArray();
        var keys = KeyStore.getInstance(key.toFile(), password);
        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, password);
        trusted.setCertificateEntry("server", keys.getCertificate("server"));
        try (var stream = Files.newOutputStream(trust)) { trusted.store(stream, password); }
    }
}
