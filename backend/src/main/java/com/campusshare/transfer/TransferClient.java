package com.campusshare.transfer;

import com.campusshare.config.Config;
import com.campusshare.security.Integrity;
import com.campusshare.security.Tls;
import static com.campusshare.transfer.Protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import javax.net.ssl.*;

public final class TransferClient {
    private final SSLContext context;
    private final String host;
    private final int port;

    public TransferClient(SSLContext context, String host, int port) {
        this.context = context;
        this.host = host;
        this.port = port;
    }

    public Status status() throws IOException {
        try (var exchange = new Exchange("STATUS", null, null, 0, ignored -> {})) {
            return convert(exchange.response(), Status.class);
        }
    }

    public SharedFile[] list() throws IOException {
        try (var exchange = new Exchange("LIST", null, null, 0, ignored -> {})) {
            return convert(exchange.response(), SharedFile[].class);
        }
    }

    public SharedFile upload(String name, long size, InputStream source, LongConsumer progress,
                             Consumer<SSLSocket> socketReady) throws IOException {
        try (var exchange = new Exchange("UPLOAD", null, name, size, socketReady)) {
            exchange.response();
            byte[] digest = Integrity.copy(source, exchange.output, size, progress);
            exchange.output.write(digest);
            exchange.output.flush();
            return convert(exchange.response(), SharedFile.class);
        }
    }

    public SharedFile download(String id, Path target, Consumer<SharedFile> metadata, LongConsumer progress,
                               Consumer<SSLSocket> socketReady) throws IOException {
        try (var exchange = new Exchange("DOWNLOAD", id, null, 0, socketReady);
             var output = Files.newOutputStream(target)) {
            var file = convert(exchange.response(), SharedFile.class);
            if (file.size() < 0 || file.size() > Config.maxFile()) {
                throw new IOException("File exceeds local size limit");
            }
            metadata.accept(file);
            byte[] digest = Integrity.copy(exchange.input, output, file.size(), progress);
            Integrity.verify(digest, HexFormat.of().parseHex(file.sha256()));
            return file;
        }
    }

    private static <T> T convert(Object value, Class<T> type) {
        return JSON.fromJson(JSON.toJsonTree(value), type);
    }

    public static final class Rejected extends IOException {
        public Rejected(String message) { super(message); }
    }

    private final class Exchange implements AutoCloseable {
        private final SSLSocket socket;
        private final DataOutputStream output;
        private final DataInputStream input;
        private final String id = UUID.randomUUID().toString();

        private Exchange(String action, String fileId, String name, long size,
                         Consumer<SSLSocket> socketReady) throws IOException {
            socket = Tls.connect(context, host, port);
            try {
                socketReady.accept(socket);
                output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                write(output, new Request(action, id, fileId, name, size));
            } catch (IOException | RuntimeException error) {
                socket.close();
                throw error;
            }
        }

        private Object response() throws IOException {
            var response = read(input, Response.class);
            if (!id.equals(response.requestId())) throw new IOException("Response ID mismatch");
            if (!response.ok()) throw new Rejected(response.error());
            return response.data();
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
