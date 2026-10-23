package dev.roseglass;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.concurrent.atomic.AtomicReference;
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
    private final RegionIndex index = new RegionIndex();
    private volatile View view;
    private final AtomicBoolean selecting = new AtomicBoolean();
    private final AtomicReference<Selection> selected = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Addition> additions = new ConcurrentLinkedQueue<>();
    // Candidates and queued keys belong to the client thread; worker callbacks never inspect game maps.
    private final PriorityQueue<RegionIndex.Column> candidates = new PriorityQueue<>((a, b) -> {
        View current = view;
        int order = Long.compare(Visibility.distance(current.x, current.z, a.x, a.z),
                Visibility.distance(current.x, current.z, b.x, b.z));
        return order == 0 ? Long.compare(a.key, b.key) : order;
    });
    private final Set<Long> queued = new HashSet<>();
    private Selection installing;
    private int installCursor;
    private static final class View {
        final int x, z, radius;
        View(int x, int z, int radius) { this.x = x; this.z = z; this.radius = radius; }
        boolean contains(int x, int z) { return Visibility.distance(this.x, this.z, x, z) <= radius; }
    }
    private static final class Selection {
        final View view;
        final List<RegionIndex.Column> columns;
        Selection(View view, List<RegionIndex.Column> columns) { this.view = view; this.columns = columns; }
    }
    private static final class Addition {
        final View view;
        final RegionIndex.Column column;
        Addition(View view, RegionIndex.Column column) { this.view = view; this.column = column; }
    }
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
        final RegionIndex.Column retry;
        Result(long key, VisualChunk chunk) { this(key, chunk, null); }
        Result(long key, VisualChunk chunk, RegionIndex.Column retry) { this.key = key; this.chunk = chunk; this.retry = retry; }
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
                            if (parts.length == 3) {
                                int x = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
                                files.put(Visibility.key(x, z), path); index.put(x, z, path);
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } catch (IOException e) { fail("Cannot open disk cache", e); }
            selectNearby();
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
                        try {
                            writeFile(save.path, save.chunk); files.put(entry.getKey(), save.path);
                            int x = save.chunk.xPosition, z = save.chunk.zPosition;
                            index.put(x, z, save.path);
                            View current = view;
                            if (current != null && current.contains(x, z))
                                additions.add(new Addition(current, new RegionIndex.Column(x, z, save.path)));
                        }
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

    private void selectNearby() {
        if (!enabled || view == null || !selecting.compareAndSet(false, true)) return;
        IO.execute(() -> {
            View target = null;
            try {
                if (enabled) {
                    target = view;
                    List<RegionIndex.Column> columns = index.nearby(target.x, target.z, target.radius);
                    if (target == view) selected.set(new Selection(target, columns));
                }
            } finally {
                selecting.set(false);
                if (enabled && target != view) selectNearby();
            }
        });
    }

    private void offer(RegionIndex.Column column) {
        if (view != null && view.contains(column.x, column.z) && queued.add(column.key)) candidates.add(column);
    }

    void request(int cameraX, int cameraZ, int radius, LongPredicate present) {
        if (!enabled) return;
        if (view == null || view.x != cameraX || view.z != cameraZ || view.radius != radius) {
            view = new View(cameraX, cameraZ, radius);
            candidates.clear(); queued.clear();
            installing = null; installCursor = 0;
            selectNearby();
        }
        Selection selection = selected.getAndSet(null);
        if (selection != null && selection.view == view) { installing = selection; installCursor = 0; }
        int installed = 0;
        while (installing != null && installed++ < 64 && installCursor < installing.columns.size())
            offer(installing.columns.get(installCursor++));
        if (installing != null && installCursor == installing.columns.size()) installing = null;
        Addition addition;
        int inspected = 0;
        while (inspected++ < 64 && (addition = additions.poll()) != null)
            if (addition.view == view) offer(addition.column);
        // Bounded client work and at most eight queued/completed-but-uncollected disk reads.
        inspected = 0;
        while (loading.size() < 8 && inspected++ < 64 && !candidates.isEmpty()) {
            RegionIndex.Column column = candidates.remove(); queued.remove(column.key);
            if (!view.contains(column.x, column.z) || present.test(column.key) || !loading.add(column.key)) continue;
            IO.execute(() -> {
                VisualChunk chunk = null;
                View current = view;
                boolean obsolete = !enabled || current == null || !current.contains(column.x, column.z);
                if (!obsolete) {
                    try {
                        chunk = readFile(column.path);
                        if (Visibility.key(chunk.xPosition, chunk.zPosition) != column.key) throw new IOException("Coordinate mismatch");
                    } catch (IOException | RuntimeException e) {
                        files.remove(column.key); index.remove(column.x, column.z);
                        fail("Ignoring incompatible/corrupt " + column.path.getFileName(), e); chunk = null;
                    }
                }
                ready.add(new Result(column.key, chunk, obsolete ? column : null));
            });
        }
    }

    Result poll() {
        Result result = ready.poll();
        if (result != null) {
            loading.remove(result.key);
            if (enabled && result.retry != null) offer(result.retry);
        }
        return result;
    }

    void disable() {
        enabled = false;
        for (Save save : writes.values()) LATEST.remove(save.path, save.revision);
        writes.clear();
        candidates.clear(); queued.clear(); selected.set(null); additions.clear(); installing = null;
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
