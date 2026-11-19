package dev.roseglass;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.logging.log4j.LogManager;

/** Multiplayer-only metadata, recognition, journaled merging and pruning. All storage work is serialized. */
final class MultiplayerCache {
    static final long DAY = 86400000L;
    private static final int MAGIC = 0x52474d31;
    private final Path root;
    private final Map<Path, Scope> scopes = new HashMap<>();
    private final Queue<Activation> activations = new ConcurrentLinkedQueue<>();
    private final Queue<String> notices = new ConcurrentLinkedQueue<>();
    private volatile Session active;
    private volatile int interval = 15, unusedDays = 15, maxChunks = 256;
    private boolean cleanupQueued;
    private long lastCheck;
    private long lastFlush;
    private Long cleanupAt;

    static final class Session {
        final Path scope;
        Record record;
        boolean stopped;
        long lastMatch;
        boolean evidenceDirty, recognized;
        volatile DiskCache reader;
        final Map<Long, String> observed = new HashMap<>();
        Session(Path scope) { this.scope = scope; }
    }
    static final class Activation {
        final Session session;
        final Path path;
        Activation(Session session, Path path) { this.session = session; this.path = path; }
    }
    static final class Entry {
        final String hash;
        final long age;
        Entry(String hash, long age) { this.hash = hash; this.age = age; }
    }
    static final class Record {
        final Path dir;
        final Map<Long, Entry> chunks = new HashMap<>();
        long used, created, bytes;
        String merging = "";
        boolean dirty, copying, valid = true;
        Record(Path dir) { this.dir = dir; }
    }
    static final class Scope {
        final Map<String, Record> records = new HashMap<>();
        final Map<Long, Map<Record, String>> index = new HashMap<>();
        void index(Record record, long key, String hash) {
            index.computeIfAbsent(key, k -> new HashMap<>()).put(record, hash);
        }
    }
    private static final class Score {
        int matches, mismatches;
        final Set<String> patterns = new HashSet<>();
        boolean qualifies() { return MultiplayerCache.qualifies(matches, mismatches) && patterns.size() > 1; }
    }

    MultiplayerCache(Path root) {
        this.root = root.toAbsolutePath().normalize();
        DiskCache.onShutdown(() -> {
            for (Scope scope : scopes.values()) for (Record record : scope.records.values())
                try { if (record.dirty) persist(record); } catch (IOException e) { fail(e); }
        });
    }

    void settings(int interval, int unusedDays, int maxChunks) {
        this.interval = interval; this.unusedDays = unusedDays; this.maxChunks = maxChunks;
    }

    Session start(String server, int dimension) {
        Session session = new Session(DiskCache.scope(root, "server", server, dimension));
        active = session;
        return session;
    }

    void stop(Session session) {
        if (active == session) active = null;
        DiskCache.execute(() -> {
            session.stopped = true;
            try { if (session.record != null) persist(session.record); }
            catch (IOException e) { fail(e); }
        });
    }

    void observe(Session session, VisualChunk snapshot, MapFingerprint fingerprint) {
        DiskCache.execute(() -> {
            try {
                Scope scope = scope(session.scope);
                if (session.record == null) {
                    Record record = new Record(session.scope.resolve("instance_" + UUID.randomUUID().toString()));
                    record.created = record.used = System.currentTimeMillis();
                    session.record = record; scope.records.put(record.dir.getFileName().toString(), record);
                    activations.add(new Activation(session, record.dir));
                }
                String hash = fingerprint.hash();
                long key = Visibility.key(snapshot.xPosition, snapshot.zPosition);
                session.observed.put(key, hash);
                session.evidenceDirty = true;
                write(scope, session.record, key, snapshot, hash);
                DiskCache reader = session.reader;
                if (reader != null && reader.directory().equals(session.record.dir)) reader.saved(snapshot);
            } catch (IOException | RuntimeException e) { fail(e); }
        });
    }

    void save(Session session, VisualChunk snapshot, DiskCache reader) {
        DiskCache.execute(() -> {
            if (session.record == null) return;
            try {
                long key = Visibility.key(snapshot.xPosition, snapshot.zPosition);
                Entry previous = session.record.chunks.get(key);
                write(scope(session.scope), session.record, key, snapshot, previous == null ? "" : previous.hash);
                if (reader != null && reader.directory().equals(session.record.dir)) reader.saved(snapshot);
            } catch (IOException | RuntimeException e) { fail(e); }
        });
    }

    private void write(Scope scope, Record record, long key, VisualChunk snapshot, String hash) throws IOException {
        Path file = chunkFile(record, key);
        long before = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0;
        DiskCache.writeFile(file, snapshot);
        record.bytes += Files.size(file) - before;
        record.used = System.currentTimeMillis();
        record.chunks.put(key, new Entry(hash, record.used)); record.dirty = true;
        scope.index(record, key, hash);
    }

    void used(Session session) {
        DiskCache.execute(() -> {
            if (session.record != null) { session.record.used = System.currentTimeMillis(); session.record.dirty = true; }
        });
    }

    Activation poll() { return activations.poll(); }
    String notice() { return notices.poll(); }

    void tick(long now) {
        if (now - lastCheck < 1000) return;
        lastCheck = now;
        DiskCache.execute(() -> {
            try {
                Session session = active;
                if (session != null && session.record != null && !session.stopped) match(session, now);
                if (now - lastFlush >= 10000) {
                    for (Scope scope : scopes.values()) for (Record record : scope.records.values())
                        if (record.dirty) persist(record);
                    lastFlush = now;
                }
                automatic(now);
            } catch (IOException | RuntimeException e) { fail(e); }
        });
    }

    static boolean qualifies(int matches, int mismatches) { return (long) matches - 2L * mismatches > 10; }

    private void match(Session session, long now) throws IOException {
        if (!session.evidenceDirty || session.recognized || now - session.lastMatch < 1000
                || !session.record.merging.isEmpty() || session.record.copying) return;
        session.lastMatch = now;
        session.evidenceDirty = false;
        Scope scope = scope(session.scope);
        Map<Record, Score> scores = new HashMap<>();
        // Coordinate-indexed metadata only: no candidate terrain files are opened for recognition.
        for (Map.Entry<Long, String> observation : session.observed.entrySet()) {
            Map<Record, String> candidates = scope.index.get(observation.getKey());
            if (candidates == null) continue;
            for (Map.Entry<Record, String> candidate : candidates.entrySet()) {
                Record record = candidate.getKey();
                if (record == session.record || !record.valid || record.copying || !record.merging.isEmpty()) continue;
                String a = observation.getValue(), b = candidate.getValue();
                if (a.isEmpty() || b.isEmpty()) continue;
                Score score = scores.computeIfAbsent(record, r -> new Score());
                if (a.equals(b)) { score.matches++; score.patterns.add(a); }
                else score.mismatches++;
            }
        }
        Record target = null;
        for (Map.Entry<Record, Score> score : scores.entrySet()) {
            if (!score.getValue().qualifies()) continue;
            if (target != null) return; // Ambiguous: do not choose by iteration order.
            target = score.getKey();
        }
        if (target == null || active != session) return;
        Record source = session.record;
        source.merging = target.dir.getFileName().toString();
        persist(source); // Durable intent before changing ownership or moving data.
        session.record = target;
        session.recognized = true;
        merge(scope, source, target, session);
    }

    private void merge(Scope scope, Record source, Record target, Session session) {
        if (source.copying) return;
        source.copying = true; target.copying = true;
        DiskCache.execute(() -> mergeBatch(scope, source, target, session));
    }

    private void mergeBatch(Scope scope, Record source, Record target, Session session) {
        try {
            int copied = 0;
            List<Long> committed = new ArrayList<>();
            Iterator<Map.Entry<Long, Entry>> entries = source.chunks.entrySet().iterator();
            while (entries.hasNext() && copied++ < 8) {
                Map.Entry<Long, Entry> entry = entries.next();
                long key = entry.getKey(); Entry old = target.chunks.get(key);
                Path from = chunkFile(source, key), to = chunkFile(target, key);
                if (Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)
                        && (old == null || !Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS) || entry.getValue().age > old.age)) {
                    VisualChunk data = DiskCache.readFile(from);
                    if (Visibility.key(data.xPosition, data.zPosition) != key) throw new IOException("Merge coordinate mismatch");
                    long before = Files.exists(to) ? Files.size(to) : 0;
                    DiskCache.writeFile(to, data); target.bytes += Files.size(to) - before;
                    target.chunks.put(key, entry.getValue()); scope.index(target, key, entry.getValue().hash);
                }
                committed.add(key);
            }
            // Commit the whole bounded batch before retiring any source metadata or files.
            target.used = Math.max(target.used, source.used); persist(target);
            for (long key : committed) source.chunks.remove(key);
            persist(source);
            for (long key : committed) {
                Files.deleteIfExists(chunkFile(source, key));
                Map<Record, String> indexed = scope.index.get(key);
                if (indexed != null) indexed.remove(source);
            }
            if (!source.chunks.isEmpty()) {
                DiskCache.execute(() -> mergeBatch(scope, source, target, session)); return;
            }
            // Delete only recognized files; unexpected content prevents removing the directory.
            deleteRecord(source);
            scope.records.remove(source.dir.getFileName().toString());
            source.copying = false; target.copying = false;
            if (session != null && !session.stopped && active == session)
                activations.add(new Activation(session, target.dir));
        } catch (IOException | RuntimeException e) {
            // Leave the durable journal intact. A restart resumes the merge; protect both directories.
            fail(e);
        }
    }

    private Scope scope(Path directory) throws IOException {
        Scope existing = scopes.get(directory);
        if (existing != null) return existing;
        Files.createDirectories(directory);
        Scope scope = new Scope(); scopes.put(directory, scope);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(directory, "instance_*")) {
            for (Path dir : dirs) if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir)) {
                Record record = read(dir); scope.records.put(dir.getFileName().toString(), record);
                for (Map.Entry<Long, Entry> e : record.chunks.entrySet()) scope.index(record, e.getKey(), e.getValue().hash);
            }
        }
        for (Record record : new ArrayList<>(scope.records.values())) if (!record.merging.isEmpty()) {
            Record target = scope.records.get(record.merging);
            if (record.valid && target != null && target.valid && target != record && target.merging.isEmpty())
                merge(scope, record, target, null);
            else record.valid = false; // Do not guess how to recover an invalid journal.
        }
        return scope;
    }

    private static Path chunkFile(Record record, long key) {
        return record.dir.resolve("c_" + (int) (key >> 32) + "_" + (int) key + ".rgc.gz");
    }

    private Record read(Path dir) throws IOException {
        Record record = new Record(dir);
        Path meta = dir.resolve("map.meta");
        if (Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(meta)))) {
                if (in.readInt() != MAGIC || in.readInt() != MapFingerprint.VERSION) throw new IOException("Incompatible map metadata");
                record.created = in.readLong(); record.used = in.readLong(); record.bytes = in.readLong();
                record.merging = in.readUTF();
                if (!record.merging.isEmpty() && !record.merging.matches("instance_[a-zA-Z0-9-]+")) throw new IOException("Invalid merge identity");
                int count = in.readInt();
                if (count < 0 || count > 10000000) throw new IOException("Invalid map count");
                for (int i = 0; i < count; i++) {
                    long key = in.readLong(), age = in.readLong(); String hash = in.readUTF();
                    if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid fingerprint");
                    record.chunks.put(key, new Entry(hash, age));
                }
                if (in.read() != -1) throw new IOException("Trailing map metadata");
            } catch (IOException | RuntimeException e) {
                record.valid = false; record.chunks.clear(); fail(e); return record;
            }
        }
        // Reconcile successful chunk writes not yet checkpointed; unknown fingerprints never match.
        Set<Long> present = new HashSet<>(); long bytes = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "c_*_*.rgc.gz")) {
            for (Path file : files) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    String[] parts = file.getFileName().toString().replace(".rgc.gz", "").split("_");
                    long key = Visibility.key(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                    present.add(key); bytes += Files.size(file);
                    if (!record.chunks.containsKey(key)) record.chunks.put(key, new Entry("", 0));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) { }
            }
        }
        record.chunks.keySet().retainAll(present); record.bytes = bytes;
        if (record.created == 0) record.created = record.used = System.currentTimeMillis();
        return record;
    }

    private static void persist(Record record) throws IOException {
        if (!record.valid) return;
        Files.createDirectories(record.dir);
        Path temp = Files.createTempFile(record.dir, ".map-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC); out.writeInt(MapFingerprint.VERSION);
                out.writeLong(record.created); out.writeLong(record.used); out.writeLong(record.bytes);
                out.writeUTF(record.merging); out.writeInt(record.chunks.size());
                for (Map.Entry<Long, Entry> e : record.chunks.entrySet()) {
                    out.writeLong(e.getKey()); out.writeLong(e.getValue().age); out.writeUTF(e.getValue().hash);
                }
            }
            replace(temp, record.dir.resolve("map.meta")); record.dirty = false;
        } finally { Files.deleteIfExists(temp); }
    }

    private static void replace(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    void cleanup() {
        DiskCache.execute(() -> {
            try { prune(System.currentTimeMillis(), true); } catch (IOException | RuntimeException e) { fail(e); }
        });
    }

    private void automatic(long now) throws IOException {
        if (interval < 1 || cleanupQueued) return;
        Files.createDirectories(root);
        Path stamp = root.resolve("cleanup.time");
        if (cleanupAt == null) {
            if (!Files.exists(stamp)) { checkpoint(now); return; }
            try (DataInputStream in = new DataInputStream(Files.newInputStream(stamp))) { cleanupAt = in.readLong(); }
            catch (IOException e) { checkpoint(now); fail(e); return; }
            if (cleanupAt < 0 || cleanupAt > now) { checkpoint(now); return; }
        }
        if (now - cleanupAt >= interval * DAY) prune(now, false);
    }

    private void checkpoint(long now) throws IOException {
        Files.createDirectories(root);
        Path temp = Files.createTempFile(root, ".cleanup-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) { out.writeLong(now); }
            replace(temp, root.resolve("cleanup.time"));
            cleanupAt = now;
        } finally { Files.deleteIfExists(temp); }
    }

    private void prune(long now, boolean manual) throws IOException {
        if (cleanupQueued) return;
        cleanupQueued = true;
        int removed = 0;
        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
                throw new IOException("Unsafe multiplayer root");
            // Enumerate only our server/dimension directories; never recurse through links.
            try (DirectoryStream<Path> servers = Files.newDirectoryStream(root, "server-*")) {
                for (Path server : servers) if (safeDirectory(server)) {
                    List<Scope> serverScopes = new ArrayList<>();
                    long newestFile = 0;
                    try (DirectoryStream<Path> dimensions = Files.newDirectoryStream(server, "dim_*")) {
                        for (Path dim : dimensions) if (safeDirectory(dim)) {
                            Scope scope = scope(dim);
                            serverScopes.add(scope);
                            for (Record record : scope.records.values()) if (record.valid)
                                newestFile = Math.max(newestFile, newestFile(record));
                        }
                    }
                    // No recent terrain means this server may simply not have been played recently.
                    // Ignore metadata/checkpoint timestamps and newly created empty directories.
                    if (newestFile == 0 || newestFile > now || now - newestFile >= unusedDays * DAY) continue;
                    for (Scope scope : serverScopes) {
                            List<Record> candidates = new ArrayList<>(scope.records.values());
                            candidates.sort(Comparator.comparingInt(r -> r.chunks.size()));
                            for (Record record : candidates) {
                                Session current = active;
                                if (!record.valid || record.copying || !record.merging.isEmpty()
                                        || current != null && current.record == record
                                        || scope.records.values().stream().anyMatch(r -> r.merging.equals(record.dir.getFileName().toString()))
                                        || record.chunks.size() >= maxChunks
                                        || newestFile - Math.max(record.used, newestFile(record)) < unusedDays * DAY) continue;
                                deleteRecord(record);
                                scope.records.remove(record.dir.getFileName().toString());
                                for (Map<Record, String> values : scope.index.values()) values.remove(record);
                                removed++;
                            }
                    }
                }
            }
            checkpoint(now);
            if (manual) notices.add("Cleanup complete: removed " + removed + " old, small multiplayer caches.");
        } finally { cleanupQueued = false; }
    }

    private static long newestFile(Record record) {
        long newest = 0;
        for (Entry entry : record.chunks.values()) newest = Math.max(newest, entry.age);
        return newest;
    }

    private boolean safeDirectory(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) return false;
        for (Path path = absolute; path != null && path.startsWith(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) return false;
        return Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS);
    }

    private void deleteRecord(Record record) throws IOException {
        if (!safeDirectory(record.dir)) throw new IOException("Unsafe cache directory");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(record.dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                        || !(name.equals("map.meta") || name.matches("c_-?[0-9]+_-?[0-9]+\\.rgc\\.gz")
                        || name.matches("\\.(map|rgc)-.*\\.tmp"))) throw new IOException("Unexpected cache content; preserving directory");
            }
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(record.dir)) {
            for (Path file : files) Files.delete(file);
        }
        Files.delete(record.dir);
    }

    private void fail(Exception e) {
        LogManager.getLogger("roseglass").warn("Multiplayer cache operation failed", e);
        notices.add("Multiplayer cache operation failed; see game log.");
    }
}
