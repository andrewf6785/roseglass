package dev.roseglass;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Immutable snapshots cross the worker boundary; no World/Chunk/GL access on this thread. */
final class DiskCache {
    private static final Logger LOG = LogManager.getLogger("roseglass");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "roseglass-cache-io"); thread.setDaemon(true); return thread;
    });
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            IO.shutdown();
            try { IO.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "roseglass-cache-flush"));
    }
    private final Path directory;
    private static final AtomicLong REVISIONS = new AtomicLong();
    private static final ConcurrentMap<Path, Long> LATEST = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Path> files = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Save> writes = new ConcurrentHashMap<>();
    private final java.util.Set<Long> loading = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Result> ready = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile boolean enabled = true;
    private volatile String error = "";

    private static final class Save {
        final VisualChunk chunk;
        final Path path;
        final long revision;
        Save(VisualChunk chunk, Path path) { this.chunk = chunk; this.path = path; revision = REVISIONS.incrementAndGet(); }
    }

    static final class Result {
        final long key;
        final VisualChunk chunk;
        Result(long key, VisualChunk chunk) { this.key = key; this.chunk = chunk; }
    }

    DiskCache(Path directory) {
        this.directory = directory;
        IO.execute(() -> {
            try {
                Files.createDirectories(directory);
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "c_*_*.rgc.gz")) {
                    for (Path path : entries) {
                        try {
                            String[] parts = path.getFileName().toString().replace(".rgc.gz", "").split("_");
                            if (parts.length == 3) files.put(Visibility.key(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])), path);
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (IOException e) { fail("Cannot open disk cache", e); }
        });
    }

    static Path scope(Path root, String kind, String identity, int dimension) {
        if (!kind.equals("world") && !kind.equals("server")) throw new IllegalArgumentException("Cache kind");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            String slug = identity.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (slug.length() > 32) slug = slug.substring(0, 32);
            return root.resolve(kind + "-" + slug + "-" + hex).resolve("dim_" + dimension);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    void save(VisualChunk chunk) {
        if (!enabled) return;
        Save save = new Save(chunk, directory.resolve("c_" + chunk.xPosition + "_" + chunk.zPosition + ".rgc.gz"));
        LATEST.put(save.path, save.revision);
        writes.put(Visibility.key(chunk.xPosition, chunk.zPosition), save);
        startDrain();
    }

    private void startDrain() {
        if (!draining.compareAndSet(false, true)) return;
        IO.execute(() -> {
            try {
                while (enabled && !writes.isEmpty()) {
                    for (Map.Entry<Long, Save> entry : writes.entrySet()) {
                        if (!enabled) break;
                        if (!writes.remove(entry.getKey(), entry.getValue())) continue;
                        Save save = entry.getValue();
                        // A previous session may still have pending writes when the same scope reopens.
                        // Only the newest scheduled revision for a file may replace it.
                        if (!Long.valueOf(save.revision).equals(LATEST.get(save.path))) continue;
                        try { writeFile(save.path, save.chunk); files.put(entry.getKey(), save.path); }
                        catch (IOException | RuntimeException e) { fail("Cannot save " + save.path.getFileName(), e); }
                        finally { LATEST.remove(save.path, save.revision); }
                    }
                }
            } finally {
                draining.set(false);
                if (enabled && !writes.isEmpty() && !IO.isShutdown()) startDrain();
            }
        });
    }

    void request(int cameraX, int cameraZ, int radius, java.util.Set<Long> present) {
        if (!enabled || loading.size() >= 8) return;
        for (Map.Entry<Long, Path> entry : files.entrySet()) {
            long key = entry.getKey();
            int x = (int) (key >> 32), z = (int) key;
            if (present.contains(key) || Visibility.distance(cameraX, cameraZ, x, z) > radius || !loading.add(key)) continue;
            IO.execute(() -> {
                VisualChunk chunk = null;
                if (enabled) {
                    try {
                        chunk = readFile(entry.getValue());
                        if (Visibility.key(chunk.xPosition, chunk.zPosition) != key) throw new IOException("Coordinate mismatch");
                    } catch (IOException | RuntimeException e) {
                        files.remove(key); fail("Ignoring incompatible/corrupt " + entry.getValue().getFileName(), e);
                        chunk = null;
                    }
                }
                ready.add(new Result(key, chunk));
            });
            if (loading.size() >= 8) break;
        }
    }

    Result poll() {
        Result result = ready.poll();
        if (result != null) loading.remove(result.key);
        return result;
    }

    void disable() {
        enabled = false;
        for (Save save : writes.values()) LATEST.remove(save.path, save.revision);
        writes.clear();
    }
    String status() { return error.isEmpty() ? "files=" + files.size() + " writes=" + writes.size() : "error=" + error; }
    Path directory() { return directory; }
    private void fail(String message, Exception e) { error = message; LOG.warn(message, e); }

    static void writeFile(Path path, VisualChunk chunk) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".rgc-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary))))) {
                chunk.write(out);
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    static VisualChunk readFile(Path path) throws IOException {
        // Bound decompression before accepting array lengths, palettes or registry properties.
        try (InputStream compressed = new GZIPInputStream(Files.newInputStream(path));
             DataInputStream in = new DataInputStream(new FilterInputStream(compressed) {
                 int remaining = 16 * 1024 * 1024;
                 @Override public int read() throws IOException {
                     if (--remaining < 0) throw new IOException("Snapshot too large");
                     return super.read();
                 }
                 @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                     if (remaining <= 0) throw new IOException("Snapshot too large");
                     int read = this.in.read(bytes, offset, Math.min(length, remaining));
                     if (read > 0) remaining -= read;
                     return read;
                 }
             })) {
            VisualChunk chunk = VisualChunk.read(in);
            if (in.read() != -1) throw new IOException("Trailing snapshot data");
            return chunk;
        }
    }

}
