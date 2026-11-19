package com.campusshare.transfer;

import com.campusshare.collaboration.Notifications;
import com.campusshare.collaboration.Notifications.Event;
import com.campusshare.config.Config;
import com.campusshare.security.Integrity;
import com.campusshare.storage.FileStore;
import static com.campusshare.transfer.Protocol.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Set;
import java.util.concurrent.*;
import javax.net.ssl.*;

public final class TransferServer implements AutoCloseable {
    private final SSLServerSocket listener;
    private final FileStore storage;
    private final Notifications notifications;
    private final ThreadPoolExecutor pool;
    private final Set<SSLSocket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService intake = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore intakeSlots = new Semaphore(32);
    private final ConcurrentMap<SSLSocket, Request> active = new ConcurrentHashMap<>();
    private final int eventPort;
    private final long maxFile;

    public TransferServer(SSLServerSocket listener, FileStore storage, Notifications notifications, int eventPort) {
        this.listener = listener;
        this.storage = storage;
        this.notifications = notifications;
        this.eventPort = eventPort;
        this.maxFile = Config.maxFile();
        int workers = Config.workers();
        pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Config.queue()));
    }

    public void run() throws IOException {
        while (!listener.isClosed()) {
            SSLSocket socket;
            try {
                socket = (SSLSocket) listener.accept();
            } catch (IOException error) {
                if (listener.isClosed()) return;
                throw error;
            }
            sockets.add(socket);
            if (!intakeSlots.tryAcquire()) {
                closeSocket(socket);
                continue;
            }
            try {
                intake.execute(() -> route(socket));
            } catch (RejectedExecutionException stopped) {
                intakeSlots.release();
                closeSocket(socket);
            }
        }
    }

    private void route(SSLSocket socket) {
        boolean handedOff = false;
        try {
            socket.setSoTimeout(15000);
            var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            var request = read(input, Request.class, 4096);
            if (request.action() == null) throw new IOException("Missing action");
            switch (request.action()) {
                case "STATUS" -> reply(output, request, new Status(notifications.clients(), eventPort,
                        active.size(), pool.getQueue().size(), maxFile));
                case "LIST" -> reply(output, request, storage.list());
                case "UPLOAD", "DOWNLOAD" -> {
                    try {
                        pool.execute(() -> transfer(socket, input, output, request));
                        handedOff = true;
                    } catch (RejectedExecutionException busy) {
                        fail(output, request, "Server transfer queue is full");
                    }
                }
                default -> fail(output, request, "Unsupported action");
            }
        } catch (IOException ignored) {
            // A closed peer or rejected TLS handshake ends this request.
        } finally {
            intakeSlots.release();
            if (!handedOff) closeSocket(socket);
        }
    }

    private void transfer(SSLSocket socket, DataInputStream input, DataOutputStream output, Request request) {
        active.put(socket, request);
        try {
            if (request.action().equals("UPLOAD")) upload(input, output, request);
            else download(output, request);
        } catch (IOException ignored) {
            // The client detects a missing final response or an incomplete byte stream.
        } finally {
            active.remove(socket);
            closeSocket(socket);
        }
    }

    private void upload(DataInputStream in, DataOutputStream out, Request request) throws IOException {
        if (!FileStore.validName(request.filename()) || request.size() < 0 || request.size() > maxFile) {
            fail(out, request, "Invalid filename or file size");
            return;
        }
        reply(out, request, null);
        SharedFile file;
        try {
            file = storage.save(request.filename(), request.size(), in);
        } catch (IOException failed) {
            fail(out, request, "Upload did not pass storage or integrity checks");
            return;
        }
        notifications.publish(Event.FILE_LIST_CHANGED);
        notifications.publish(Event.TRANSFER_COMPLETED);
        reply(out, request, file);
    }

    private void download(DataOutputStream out, Request request) throws IOException {
        var file = storage.get(request.fileId());
        if (file == null) {
            fail(out, request, "File unavailable");
            return;
        }
        try (var input = Files.newInputStream(storage.path(file), LinkOption.NOFOLLOW_LINKS)) {
            reply(out, request, file);
            Integrity.copy(input, out, file.size(), ignored -> {});
            out.flush();
        }
        notifications.publish(Event.TRANSFER_COMPLETED);
    }

    private static void reply(DataOutputStream out, Request request, Object data) throws IOException {
        write(out, new Response(true, request.requestId(), null, data));
    }

    private static void fail(DataOutputStream out, Request request, String error) throws IOException {
        write(out, new Response(false, request.requestId(), error, null));
    }

    private void closeSocket(SSLSocket socket) {
        sockets.remove(socket);
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override public void close() throws IOException {
        listener.close();
        intake.shutdownNow();
        pool.shutdownNow();
        sockets.forEach(this::closeSocket);
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
