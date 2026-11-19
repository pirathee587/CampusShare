package com.campusshare.security;

import java.io.*;
import java.security.*;
import java.util.function.LongConsumer;

public final class Integrity {
    public static final int DIGEST_BYTES = 32;
    private Integrity() {}

    public static byte[] copy(InputStream input, OutputStream output, long length, LongConsumer progress)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        var buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (count == -1) throw new EOFException("Incomplete file");
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            remaining -= count;
            progress.accept(length - remaining);
        }
        return digest.digest();
    }

    public static void verify(byte[] actual, byte[] expected) throws IOException {
        if (!MessageDigest.isEqual(actual, expected)) throw new IOException("SHA-256 verification failed");
    }
}
