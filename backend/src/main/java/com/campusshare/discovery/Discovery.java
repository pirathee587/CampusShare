package com.campusshare.discovery;

import com.campusshare.config.Config;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Discovery implements AutoCloseable {
    private static final String QUERY = "CAMPUSSHARE_DISCOVER_V1";
    private static final String REPLY = "CAMPUSSHARE_SERVER_V1";
    private final DatagramSocket socket;

    public record Server(String host, int port) {}

    public Discovery(int port) throws SocketException {
        socket = new DatagramSocket(port);
    }

    public int port() { return socket.getLocalPort(); }

    public void start(int tlsPort) {
        Thread.startVirtualThread(() -> {
            while (!socket.isClosed()) {
                try {
                    var packet = new DatagramPacket(new byte[128], 128);
                    socket.receive(packet);
                    if (QUERY.equals(decode(packet))) {
                        send(socket, REPLY + " " + tlsPort, packet.getSocketAddress());
                    }
                } catch (IOException error) {
                    if (!socket.isClosed()) System.err.println("UDP discovery: " + error.getMessage());
                }
            }
        });
    }

    public static Collection<Server> search(int port) throws IOException {
        return search(port, Config.value("DISCOVERY_ADDRESS", "255.255.255.255"));
    }

    static Collection<Server> search(int port, String address) throws IOException {
        Map<String, Server> results = new LinkedHashMap<>();
        try (var socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            send(socket, QUERY, new InetSocketAddress(address, port));
            long deadline = System.nanoTime() + 1_200_000_000L;
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout((int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
                var packet = new DatagramPacket(new byte[128], 128);
                try {
                    socket.receive(packet);
                    Server server = parse(packet, port);
                    if (server != null) results.putIfAbsent(server.host() + ":" + server.port(), server);
                } catch (SocketTimeoutException done) {
                    break;
                }
            }
        }
        return List.copyOf(results.values());
    }

    static Server parse(DatagramPacket packet, int expectedPort) {
        String[] fields = decode(packet).split(" ", -1);
        if (packet.getPort() != expectedPort || fields.length != 2 || !REPLY.equals(fields[0])) return null;
        try {
            int port = Integer.parseInt(fields[1]);
            return port > 0 && port <= 65535 ? new Server(packet.getAddress().getHostAddress(), port) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static void send(DatagramSocket socket, String text, SocketAddress target) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        socket.send(new DatagramPacket(bytes, bytes.length, target));
    }

    private static String decode(DatagramPacket packet) {
        return new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
    }

    @Override public void close() { socket.close(); }
}
