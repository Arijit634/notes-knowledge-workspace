package org.notesknowledge;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Disk-backed controlled local transport; not an OCI adapter or production storage option. */
public final class LocalhostObjectDirectory {
    private final Path directory;
    private final String prefix;
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    public LocalhostObjectDirectory(LocalhostMode mode, String category) {
        directory = mode.directory(category); prefix = category + "/";
    }

    private Path path(String reference) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || reference == null
                || !reference.matches(java.util.regex.Pattern.quote(prefix) + "[0-9a-f]{64}")) throw unavailable();
        try { if (!directory.equals(directory.toRealPath())) throw unavailable(); }
        catch (java.io.IOException invalid) { throw unavailable(); }
        return directory.resolve(reference.substring(prefix.length()));
    }

    public void write(String reference, InputStream source, long size) {
        Path file = path(reference);
        if (source == null || size < 1 || size > MAX_BYTES) throw unavailable();
        boolean created = false;
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            created = true;
            byte[] bytes = new byte[16 * 1024];
            try {
                long remaining = size;
                while (remaining > 0) {
                    int count = source.read(bytes, 0, (int)Math.min(bytes.length, remaining));
                    if (count <= 0) throw unavailable();
                    var buffer = ByteBuffer.wrap(bytes, 0, count);
                    while (buffer.hasRemaining()) output.write(buffer);
                    remaining -= count;
                }
                if (source.read() != -1) throw unavailable();
                output.force(true);
            } finally { java.util.Arrays.fill(bytes, (byte)0); }
        } catch (Exception failure) {
            if (created) try { Files.delete(file); } catch (java.io.IOException ignored) { /* Unreachable orphan; no committed metadata. */ }
            throw unavailable();
        }
    }

    public InputStream open(String reference, long offset, long length) {
        Path file = path(reference);
        if (offset < 0 || length < 1 || length > MAX_BYTES || offset > MAX_BYTES - length) throw unavailable();
        FileChannel channel = null;
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            if (channel.size() < offset + length || channel.size() > MAX_BYTES) throw unavailable();
            channel.position(offset);
            InputStream input = Channels.newInputStream(channel);
            return new java.io.FilterInputStream(input) {
                long remaining = length;
                @Override public int read() throws java.io.IOException {
                    if (remaining == 0) return -1;
                    int value = super.read(); if (value < 0) throw new java.io.IOException("Local bytes unavailable");
                    remaining--; return value;
                }
                @Override public int read(byte[] bytes, int start, int count) throws java.io.IOException {
                    java.util.Objects.checkFromIndexSize(start, count, bytes.length);
                    if (count == 0) return 0;
                    if (remaining == 0) return -1;
                    int read = in.read(bytes, start, (int)Math.min(count, remaining));
                    if (read < 0) throw new java.io.IOException("Local bytes unavailable");
                    remaining -= read; return read;
                }
                @Override public long skip(long count) throws java.io.IOException {
                    long skipped = in.skip(Math.min(Math.max(0, count), remaining)); remaining -= skipped; return skipped;
                }
            };
        } catch (Exception failure) {
            if (channel != null) try { channel.close(); } catch (java.io.IOException ignored) { }
            throw unavailable();
        }
    }

    public void delete(String reference) {
        try { Files.deleteIfExists(path(reference)); } catch (Exception failure) { throw unavailable(); }
    }

    public List<ObjectEntry> inventory(Instant cutoff, String after, int limit) {
        path(prefix + "0".repeat(64));
        if (cutoff == null || limit < 1 || limit > 1000) throw unavailable();
        if (after != null) path(after);
        var entries = new ArrayList<ObjectEntry>();
        try (var files = Files.newDirectoryStream(directory)) {
            int scanned = 0;
            for (Path file : files) {
                if (++scanned > 10_000) throw unavailable(); // Deliberately bounded local manual-test storage.
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f]{64}") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
                String reference = prefix + name;
                Instant created = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
                if ((after == null || reference.compareTo(after) > 0) && created.isBefore(cutoff)) entries.add(new ObjectEntry(reference, created));
            }
        } catch (Exception failure) { throw unavailable(); }
        return entries.stream().sorted(Comparator.comparing(ObjectEntry::reference)).limit(limit).toList();
    }

    public record ObjectEntry(String reference, Instant createdAt) {
        @Override public String toString() { return "LocalObject[REDACTED]"; }
    }
    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
}
