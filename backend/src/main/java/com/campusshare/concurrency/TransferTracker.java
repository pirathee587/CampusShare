package com.campusshare.concurrency;

import java.io.IOException;
import java.net.Socket;
import java.util.*;

public final class TransferTracker {
    public record Snapshot(String id, String direction, String filename, long size, long bytes, String state) {}

    private final Map<String, Transfer> transfers = new LinkedHashMap<>();

    public synchronized Transfer begin(String id, String direction, String name, long size) {
        if (transfers.containsKey(id)) throw new IllegalArgumentException("Transfer ID already used");
        if (transfers.values().stream().filter(Transfer::running).count() >= 12) {
            throw new IllegalArgumentException("Local transfer limit reached");
        }
        if (transfers.size() >= 84) {
            transfers.values().removeIf(transfer -> !transfer.running());
        }
        var transfer = new Transfer(id, direction, name, size);
        transfers.put(id, transfer);
        return transfer;
    }

    public synchronized List<Snapshot> list() {
        return transfers.values().stream().map(Transfer::snapshot).toList().reversed();
    }

    public synchronized boolean cancel(String id) {
        var transfer = transfers.get(id);
        return transfer != null && transfer.cancel();
    }

    public synchronized void reset() {
        transfers.values().forEach(Transfer::cancel);
        transfers.clear();
    }

    public static final class Transfer {
        private final String id;
        private final String direction;
        private String name;
        private long size;
        private long bytes;
        private String state = "Waiting for server";
        private Socket socket;

        private Transfer(String id, String direction, String name, long size) {
            this.id = id;
            this.direction = direction;
            this.name = name;
            this.size = size;
        }

        public synchronized void attach(Socket active) {
            if (running()) socket = active;
            else close(active);
        }

        public synchronized void metadata(String filename, long value) {
            name = filename;
            size = value;
            if (running()) state = "Transferring";
        }

        public synchronized void progress(long value) {
            bytes = value;
            if (running()) state = "Transferring";
        }

        public synchronized void finish(String result) {
            if (running()) state = result;
            socket = null;
        }

        private synchronized boolean running() {
            return state.equals("Waiting for server") || state.equals("Transferring");
        }

        private synchronized boolean cancel() {
            if (!running()) return false;
            state = "Cancelled";
            if (socket != null) close(socket);
            return true;
        }

        private synchronized Snapshot snapshot() { return new Snapshot(id, direction, name, size, bytes, state); }

        private static void close(Socket socket) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
