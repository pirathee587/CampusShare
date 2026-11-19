package com.campusshare.collaboration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;

public final class Notifications implements AutoCloseable {
    public enum Event { FILE_LIST_CHANGED, TRANSFER_COMPLETED, SERVER_STATUS_CHANGED, ONLINE_COUNT_CHANGED }

    private final Selector selector;
    private final ServerSocketChannel listener;
    private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(64);
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
    private volatile int clients;
    private Thread worker;

    public Notifications(int port) throws IOException {
        selector = Selector.open();
        listener = ServerSocketChannel.open();
        try {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(port));
            listener.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException error) {
            listener.close();
            selector.close();
            throw error;
        }
    }

    public int port() throws IOException { return ((InetSocketAddress) listener.getLocalAddress()).getPort(); }
    public int clients() { return clients; }

    public void publish(Event event) {
        events.offer(event);
        selector.wakeup();
    }

    public void start() {
        worker = Thread.ofPlatform().start(() -> {
            try {
                while (running.get()) {
                    selector.select();
                    var ready = selector.selectedKeys().iterator();
                    while (ready.hasNext()) {
                        var key = ready.next();
                        ready.remove();
                        try {
                            if (key.isAcceptable()) accept();
                            if (key.isValid() && key.isReadable()) {
                                var channel = (SocketChannel) key.channel();
                                // The protocol is server-to-client only. Any data or EOF closes the channel.
                                if (channel.read(ByteBuffer.allocate(1)) != 0) drop(key);
                            }
                            if (key.isValid() && key.isWritable()) write(key);
                        } catch (IOException error) {
                            if (key.channel() == listener) throw error;
                            drop(key);
                        }
                    }
                    for (Event event; (event = events.poll()) != null;) broadcast(event);
                }
                broadcast(Event.SERVER_STATUS_CHANGED);
                for (var key : selector.keys()) {
                    if (key.isValid() && key.channel() instanceof SocketChannel) {
                        try { write(key); } catch (IOException ignored) {}
                    }
                }
            } catch (IOException error) {
                if (running.get()) System.err.println("NIO notifications: " + error.getMessage());
            } finally {
                closeChannels();
                try (selector; listener) {
                    clients = 0;
                } catch (IOException error) {
                    System.err.println("NIO cleanup: " + error.getMessage());
                }
            }
        });
    }


    private void closeChannels() {
        for (var key : selector.keys()) {
            if (key.channel() instanceof SocketChannel) drop(key);
        }
    }

    private void accept() throws IOException {
        SocketChannel channel = listener.accept();
        if (channel == null) return;
        if (clients >= 64) {
            channel.close();
            return;
        }
        try {
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ, new ArrayDeque<ByteBuffer>());
            clients++;
            publish(Event.ONLINE_COUNT_CHANGED);
        } catch (IOException error) {
            channel.close();
        }
    }

    private void broadcast(Event event) {
        byte[] frame = (event.name() + "\n").getBytes(StandardCharsets.US_ASCII);
        for (var key : selector.keys()) {
            if (!key.isValid() || key.channel() == listener) continue;
            var queue = queue(key);
            if (queue.size() >= 16) {
                drop(key);
                continue;
            }
            queue.add(ByteBuffer.wrap(frame));
            key.interestOpsOr(SelectionKey.OP_WRITE);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<ByteBuffer> queue(SelectionKey key) {
        return (ArrayDeque<ByteBuffer>) key.attachment();
    }

    private static void write(SelectionKey key) throws IOException {
        var channel = (SocketChannel) key.channel();
        var queue = queue(key);
        while (!queue.isEmpty()) {
            var frame = queue.element();
            channel.write(frame);
            if (frame.hasRemaining()) return;
            queue.remove();
        }
        key.interestOps(SelectionKey.OP_READ);
    }

    private void drop(SelectionKey key) {
        if (!key.isValid()) return;
        key.cancel();
        try { key.channel().close(); } catch (IOException ignored) {}
        clients--;
        publish(Event.ONLINE_COUNT_CHANGED);
    }

    @Override public void close() throws IOException {
        if (!running.getAndSet(false)) return;
        selector.wakeup();
        try {
            if (worker == null) {
                listener.close();
                selector.close();
            } else {
                worker.join();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
