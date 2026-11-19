package com.campusshare.transfer;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class Protocol {
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    public static final Gson JSON = new Gson();

    public record Request(String action, String requestId, String fileId, String filename, long size) {}
    public record Response(boolean ok, String requestId, String error, Object data) {}
    public record Status(int onlineCount, int eventPort, int activeWorkers, int queuedRequests, long maxFileBytes) {}
    public record SharedFile(String id, String filename, long size, String sha256) {}

    private Protocol() {}

    public static void write(DataOutputStream out, Object value) throws IOException {
        byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME_BYTES) throw new IOException("Frame too large");
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }

    public static <T> T read(DataInputStream in, Class<T> type) throws IOException {
        return read(in, type, MAX_FRAME_BYTES);
    }

    public static <T> T read(DataInputStream in, Class<T> type, int limit) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > limit) throw new IOException("Invalid frame length");
        byte[] body = new byte[length];
        in.readFully(body);
        try {
            T value = JSON.fromJson(new String(body, StandardCharsets.UTF_8), type);
            if (value == null) throw new IOException("Empty frame");
            return value;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid JSON frame", invalid);
        }
    }
}
