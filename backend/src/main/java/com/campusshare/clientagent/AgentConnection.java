package com.campusshare.clientagent;

import com.campusshare.collaboration.Notifications.Event;
import com.campusshare.transfer.Protocol.Status;
import com.campusshare.transfer.TransferClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;

final class AgentConnection implements AutoCloseable {
    private final SSLContext trust;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final Runnable onLost;
    private volatile Connection current;

    static final class Connection {
        final String displayName;
        final String host;
        final int port;
        final TransferClient client;
        final CopyOnWriteArrayList<BlockingQueue<Event>> subscribers = new CopyOnWriteArrayList<>();
        volatile Socket notifications;
        volatile Status status;
        volatile boolean eventsConnected;
        volatile boolean tlsVerified = true;
        int failures;

        Connection(String displayName, String host, int port, TransferClient client, Status status) {
            this.displayName = displayName;
            this.host = host;
            this.port = port;
            this.client = client;
            this.status = status;
        }
    }

    AgentConnection(SSLContext trust, Runnable onLost) {
        this.trust = trust;
        this.onLost = onLost;
        monitor.scheduleWithFixedDelay(this::refresh, 2, 2, TimeUnit.SECONDS);
    }

    Connection current() { return current; }

    synchronized Connection connect(String name, String host, int port) throws IOException {
        if (current != null) throw new IllegalArgumentException("Disconnect before changing the server or display name");
        var client = new TransferClient(trust, host, port);
        var connection = new Connection(name, host, port, client, client.status());
        current = connection;
        Thread.startVirtualThread(() -> receiveEvents(connection));
        return connection;
    }

    synchronized void disconnect() {
        Connection previous = current;
        current = null;
        if (previous != null) {
            closeSocket(previous.notifications);
            onLost.run();
        }
    }

    private void refresh() {
        Connection connection = current;
        if (connection == null) return;
        try {
            connection.status = connection.client.status();
            connection.tlsVerified = true;
            connection.failures = 0;
        } catch (IOException error) {
            connection.tlsVerified = false;
            synchronized (this) {
                if (current == connection && ++connection.failures >= 3) disconnect();
            }
        }
    }

    private void receiveEvents(Connection connection) {
        while (current == connection) {
            try (var socket = new Socket()) {
                connection.notifications = socket;
                if (current != connection) return;
                socket.connect(new InetSocketAddress(connection.host, connection.status.eventPort()), 2000);
                connection.eventsConnected = true;
                var input = socket.getInputStream();
                while (current == connection) {
                    String line = readEvent(input);
                    if (line == null) break;
                    try { Event event = Event.valueOf(line); connection.subscribers.forEach(queue -> queue.offer(event)); }
                    catch (IllegalArgumentException ignored) {}
                }
            } catch (IOException ignored) {
                // The TLS channel stays available during an event service interruption.
            } finally {
                connection.eventsConnected = false;
                connection.notifications = null;
            }
            try { Thread.sleep(1000); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static String readEvent(InputStream input) throws IOException {
        var buffer = new ByteArrayOutputStream(32);
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return buffer.toString(StandardCharsets.US_ASCII);
            if (buffer.size() == 32) throw new IOException("Event frame too long");
            buffer.write(value);
        }
        return null;
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override public void close() {
        monitor.shutdownNow();
        disconnect();
    }
}
