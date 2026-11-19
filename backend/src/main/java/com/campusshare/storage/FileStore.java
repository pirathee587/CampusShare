package com.campusshare.storage;

import com.campusshare.security.Integrity;
import com.campusshare.transfer.Protocol.SharedFile;
import static com.campusshare.transfer.Protocol.JSON;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FileStore {
    private final Path root;
    private final Map<String, SharedFile> files = new ConcurrentHashMap<>();

    public FileStore(Path root) throws IOException {
        this.root = Files.createDirectories(root);
        try (var entries = Files.newDirectoryStream(root, "*.json")) {
            for (Path metadata : entries) {
                if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var reader = Files.newBufferedReader(metadata)) {
                    var file = JSON.fromJson(reader, SharedFile.class);
                    if (file != null && UUID.fromString(file.id()).toString().equals(file.id())
                            && metadata.getFileName().toString().equals(file.id() + ".json")
                            && validName(file.filename()) && file.size() >= 0
                            && file.sha256() != null && file.sha256().matches("[0-9a-f]{64}")
                            && Files.isRegularFile(path(file), LinkOption.NOFOLLOW_LINKS)) files.put(file.id(), file);
                } catch (RuntimeException | IOException error) {
                    System.err.println("Ignoring invalid file metadata: " + metadata.getFileName());
                }
            }
        }
    }

    public List<SharedFile> list() {
        return files.values().stream().sorted(Comparator.comparing(SharedFile::filename)
                .thenComparing(SharedFile::id)).toList();
    }

    public SharedFile get(String id) { return id == null ? null : files.get(id); }
    public Path path(SharedFile file) { return root.resolve(file.id() + ".bin"); }

    public SharedFile save(String name, long size, DataInputStream input) throws IOException {
        String id = UUID.randomUUID().toString();
        Path staged = Files.createTempFile(root, "upload-", ".part");
        Path metadata = null;
        boolean moved = false;
        try {
            byte[] actual;
            try (var output = Files.newOutputStream(staged)) {
                actual = Integrity.copy(input, output, size, ignored -> {});
            }
            byte[] expected = new byte[Integrity.DIGEST_BYTES];
            input.readFully(expected);
            Integrity.verify(actual, expected);
            var file = new SharedFile(id, name, size, HexFormat.of().formatHex(actual));
            metadata = Files.createTempFile(root, "metadata-", ".part");
            Files.writeString(metadata, JSON.toJson(file));
            Files.move(staged, path(file));
            moved = true;
            Files.move(metadata, root.resolve(id + ".json"));
            files.put(id, file);
            return file;
        } finally {
            Files.deleteIfExists(staged);
            if (metadata != null) Files.deleteIfExists(metadata);
            if (moved && !files.containsKey(id)) Files.deleteIfExists(root.resolve(id + ".bin"));
        }
    }

    public static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= 180 && !name.equals(".") && !name.equals("..")
                && name.chars().noneMatch(c -> Character.isISOControl(c) || "\\/:*?\"<>|".indexOf(c) >= 0);
    }
}
