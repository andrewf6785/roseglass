package dev.roseglass;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = "roseglass", name = "roseglass", version = "0.9.2",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*")
public final class RoseglassMod {
    private static final Logger LOG = LogManager.getLogger("roseglass");
    private final Minecraft mc = Minecraft.getMinecraft();
    private final Map<Long, CachedChunk> cache = new HashMap<>();
    // Live tracking supports persistence; border rendering uses non-generating provider lookups.
    private final Map<Long, Chunk> liveChunks = new HashMap<>();
    private final Map<Long, BorderColumn> border = new HashMap<>();
    private final List<DebugMesh> garbage = new ArrayList<>();
    private final TerrainUpdates terrainUpdates = new TerrainUpdates(this::terrainChanged);
    private final WorkingBand workingBand = new WorkingBand();
    private final Set<Long> pendingLive = new HashSet<>();
    private WorldClient world;
    private Configuration config;
    private int visualRadius = 32;
    private boolean debugOverlay;
    private boolean diskEnabled = true;
    private boolean borderEnabled = true;
    private long clientTicks;
    private int borderDrawn, borderPending;
    private boolean diskReadsPaused;
    private DiskCache disk;
    private boolean invalidateMeshes;
    private int eligibleCount, drawnCount, pendingCount;
    private boolean multiplayer;
    private NetworkManager connection;
    private final Set<Long> freshMultiplayer = new HashSet<>();
    private final Set<Long> fingerprintQueue = new LinkedHashSet<>();
    private MultiplayerCache multiplayerCache;
    private MultiplayerCache.Session multiplayerSession;
    private int cleanupInterval = 15, cleanupAge = 15, cleanupChunks = 256;

    private final MultiplayerPackets packets = new MultiplayerPackets(new MultiplayerPackets.Listener() {
        @Override public void connected(NetworkManager manager) {
            // The Forge connection event can precede Join Game and creation of the local player.
            // Minecraft.getNetHandler() reads the player's sendQueue and is null at this point.
            if (!manager.isChannelOpen() || manager.isLocalChannel() || mc.isSingleplayer()) return;
            if (connection != null && connection != manager) resetMultiplayer();
            connection = manager; multiplayer = !mc.isSingleplayer();
            LOG.info("Multiplayer cache connection observer active");
        }
        @Override public void disconnected(NetworkManager manager) {
            if (connection != manager) return;
            resetMultiplayer(); connection = null; multiplayer = false;
        }
        @Override public void transition(NetworkManager manager) {
            if (connection == manager && multiplayer) resetMultiplayer();
        }
        @Override public void fullChunk(NetworkManager manager, int x, int z) {
            if (connection != manager || !multiplayer || mc.theWorld == null) return;
            syncWorld();
            Chunk chunk = world.getChunkProvider().provideChunk(x, z);
            if (chunk instanceof EmptyChunk || !chunk.isLoaded()) return;
            long key = Visibility.key(x, z);
            freshMultiplayer.add(key); liveChunks.put(key, chunk); pendingLive.add(key);
            if (diskEnabled) {
                ensureMultiplayerSession();
                if (multiplayerSession != null) fingerprintQueue.add(key);
            }
        }
    });

    private void ensureMultiplayerSession() {
        if (multiplayerSession != null || !multiplayer || !diskEnabled || world == null) return;
        String identity = mc.getCurrentServerData() == null ? connection.getRemoteAddress().toString()
                : mc.getCurrentServerData().serverIP.trim().toLowerCase(java.util.Locale.ROOT);
        multiplayerSession = multiplayerCache.start(identity, world.provider.getDimensionId());
    }

    private void resetMultiplayer() {
        DiskCache previous = disk;
        saveLiveForExit();
        if (previous != null) previous.disable();
        disk = null;
        if (multiplayerSession != null) multiplayerCache.stop(multiplayerSession);
        multiplayerSession = null;
        freshMultiplayer.clear(); fingerprintQueue.clear(); clear();
    }

    private void saveSnapshot(VisualChunk snapshot) {
        if (multiplayer) {
            if (diskEnabled && multiplayerSession != null) multiplayerCache.save(multiplayerSession, snapshot, disk);
        } else if (disk != null) disk.save(snapshot);
    }

    private static final class CachedChunk {
        final VisualChunk chunk;
        final DebugMesh mesh;
        final SectionWork work = new SectionWork();
        CachedChunk(VisualChunk chunk) { this(chunk, new DebugMesh()); }
        CachedChunk(VisualChunk chunk, DebugMesh mesh) {
            this.chunk = chunk; this.mesh = mesh;
            work.queue(chunk.sectionMask() & ~mesh.completedSections());
            if (chunk.sectionMask() == 0 && !mesh.ready()) mesh.markEmpty();
        }
    }

    private static final class BorderColumn {
        final int x, z;
        final DebugMesh mesh;
        final SectionWork work = new SectionWork();
        VisualChunk snapshot;
        long refreshed;
        int deferred;
        BorderColumn(int x, int z) { this(x, z, new DebugMesh(), null); }
        BorderColumn(int x, int z, DebugMesh mesh, VisualChunk snapshot) {
            this.x = x; this.z = z; this.mesh = mesh; this.snapshot = snapshot;
            mesh.borderOverlap = true;
            work.queue(snapshot == null ? SectionWork.ALL : snapshot.sectionMask() & ~mesh.completedSections());
        }
    }

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        File configFile = new File(mc.mcDataDir, "config/roseglass.cfg");
        int previousRadius = 32;
        // Import a legacy configuration only when the current configuration is absent.
        File previousFile = new File(mc.mcDataDir, "config/visualchunkcache.cfg");
        if (!configFile.exists() && previousFile.isFile()) {
            Configuration previous = new Configuration(previousFile);
            previous.load();
            previousRadius = previous.getInt("visualDistance", "rendering", 32, 1, Integer.MAX_VALUE, "");
        }
        config = new Configuration(configFile);
        config.load();
        visualRadius = config.getInt("visualDistance", "rendering", previousRadius, 1, Integer.MAX_VALUE,
                "Cached visual radius in chunks; independent of vanilla render distance.");
        debugOverlay = config.getBoolean("debugOverlay", "rendering", false,
                "Show the roseglass debug overlay in the top left. Toggle with /roseglass debug.");
        diskEnabled = config.getBoolean("diskCache", "storage", true, "Save/load world-specific visual snapshots on disk.");
        borderEnabled = config.getBoolean("borderQueue", "rendering", true,
                "Force draw the three outer rings inside vanilla render distance. /roseglass border on|off");
        cleanupInterval = config.getInt("cleanupIntervalDays", "multiplayer", 15, -1, Integer.MAX_VALUE,
                "Automatic cleanup interval in days; -1 disables automatic cleanup. /roseglass cleanup [days]");
        if (cleanupInterval == 0) { cleanupInterval = 15; config.get("multiplayer", "cleanupIntervalDays", 15).set(15); }
        cleanupAge = config.getInt("cleanupUnusedDays", "multiplayer", 15, 1, Integer.MAX_VALUE,
                "Minimum days since successful use before a multiplayer map cache can be deleted.");
        cleanupChunks = config.getInt("cleanupChunkLimit", "multiplayer", 256, 1, Integer.MAX_VALUE,
                "Only multiplayer caches with fewer than this many chunks can be deleted.");
        multiplayerCache = new MultiplayerCache(mc.mcDataDir.toPath().resolve(".roseglass/multiplayer_cached_chunk_data"));
        multiplayerCache.settings(cleanupInterval, cleanupAge, cleanupChunks);
        if (config.hasChanged()) config.save();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(packets);
        FMLCommonHandler.instance().bus().register(this);
        ClientCommandHandler.instance.registerCommand(new Control());
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(manager -> invalidateMeshes = true);
        LOG.info("DEBUG terrain cache ready; all unloaded client chunks; visual={}; /roseglass distance <chunks>",
                visualRadius);
    }

    private void discard(CachedChunk entry) { garbage.add(entry.mesh); }

    private void clear() {
        for (CachedChunk entry : cache.values()) discard(entry);
        cache.clear();
        clearBorder();
        liveChunks.clear();
        eligibleCount = drawnCount = pendingCount = 0;
    }

    private void clearBorder() {
        for (BorderColumn entry : border.values()) garbage.add(entry.mesh);
        border.clear();
        workingBand.clear(); pendingLive.clear();
        borderDrawn = borderPending = 0;
    }

    private void syncWorld() {
        if (world != mc.theWorld) {
            saveLiveForExit();
            if (world != null) world.removeWorldAccess(terrainUpdates);
            clear();
            world = mc.theWorld;
            if (multiplayer) { freshMultiplayer.clear(); fingerprintQueue.clear(); }
            if (world != null) world.addWorldAccess(terrainUpdates);
            openDisk();
        }
    }

    private void openDisk() {
        if (multiplayerSession != null) multiplayerCache.stop(multiplayerSession);
        multiplayerSession = null;
        if (multiplayer && disk != null) disk.disable();
        disk = null;
        diskReadsPaused = false;
        if (!diskEnabled || world == null) return;
        if (multiplayer) return; // Only fresh full-chunk packets may initialize a new multiplayer map.
        String kind, identity;
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null) {
            kind = "world"; identity = mc.getIntegratedServer().getFolderName()
                    + "|seed=" + mc.getIntegratedServer().worldServerForDimension(0).getSeed();
        } else {
            LOG.warn("No reliable world/server identity; disk cache disabled for this session"); return;
        }
        disk = new DiskCache(DiskCache.scope(mc.mcDataDir.toPath().resolve(".roseglass/cached_chunk_data"),
                kind, identity, world.provider.getDimensionId()));
        LOG.info("Visual disk cache: {}", disk.directory());
    }

    private void saveLiveForExit() {
        if (disk == null && multiplayerSession == null) return;
        for (Chunk chunk : liveChunks.values()) if (chunk.isLoaded() && chunk.getWorld() == world) {
            if (multiplayer) {
                if (diskEnabled && multiplayerSession != null
                        && freshMultiplayer.contains(Visibility.key(chunk.xPosition, chunk.zPosition)))
                    multiplayerCache.observe(multiplayerSession, VisualChunk.capture(chunk), MapFingerprint.capture(chunk));
            } else saveSnapshot(VisualChunk.capture(chunk));
        }
        if (multiplayer && disk != null) disk.disable();
        disk = null;
    }

    private void pollDisk() {
        if (disk == null) return;
        DiskCache.Result result;
        while ((result = disk.poll()) != null) {
            if (diskReadsPaused || result.chunk == null || cache.containsKey(result.key) || liveChunks.containsKey(result.key)) continue;
            Entity current = mc.getRenderViewEntity();
            if (current == null || Visibility.distance(Visibility.chunk(current.posX), Visibility.chunk(current.posZ),
                    result.chunk.xPosition, result.chunk.zPosition) > visualRadius) continue;
            cache.put(result.key, new CachedChunk(result.chunk));
            if (multiplayer && multiplayerSession != null) multiplayerCache.used(multiplayerSession);
            dirtyNeighbors(result.chunk.xPosition, result.chunk.zPosition);
        }
        Entity camera = mc.getRenderViewEntity();
        if (camera != null && !diskReadsPaused) {
            disk.request(Visibility.chunk(camera.posX), Visibility.chunk(camera.posZ), visualRadius,
                    key -> cache.containsKey(key) || liveChunks.containsKey(key));
        }
    }

    @SubscribeEvent public void unloaded(ChunkEvent.Unload event) {
        if (!event.world.isRemote || event.world != mc.theWorld) return;
        Chunk chunk = event.getChunk();
        if (chunk instanceof EmptyChunk) return;
        syncWorld();
        long key = Visibility.key(chunk.xPosition, chunk.zPosition);
        if (multiplayer && !freshMultiplayer.remove(key)) return;
        fingerprintQueue.remove(key);
        liveChunks.remove(key);
        VisualChunk snapshot = VisualChunk.capture(chunk);
        BorderColumn prepared = border.remove(key);
        CachedChunk previous = cache.remove(key);
        DebugMesh drawable = prepared != null ? prepared.mesh : previous != null ? previous.mesh : new DebugMesh();
        CachedChunk retained = new CachedChunk(snapshot, drawable);
        cache.put(key, retained);
        if (previous != null && previous.mesh != retained.mesh) discard(previous);
        if (prepared != null) {
            retained.work.queue(transferWork(prepared, snapshot));
            retained.work.pending &= snapshot.sectionMask() | retained.mesh.completedSections();
        } else if (previous != null) {
            retained.work.queue(previous.work.pending | previous.work.forced()
                    | differences(previous.chunk, snapshot, SectionWork.ALL));
            retained.work.pending &= snapshot.sectionMask() | retained.mesh.completedSections();
        }
        retained.mesh.visibleBits = -1L;
        retained.mesh.borderOverlap = false;
        pendingLive.remove(key);
        // No synchronous mesh build here. Existing section lists transfer intact; missing work waits.
        if (multiplayer && diskEnabled && multiplayerSession != null)
            multiplayerCache.observe(multiplayerSession, snapshot, MapFingerprint.capture(chunk));
        else saveSnapshot(snapshot);
        // Provider removal changes ownership, not terrain: retain meshes and do not dirty neighbors.
    }

    private void terrainChanged(int minX, int minZ, int maxX, int maxZ, int sections, boolean force) {
        long columns = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (columns <= 4096) {
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                BorderColumn entry = border.get(Visibility.key(x, z));
                if (entry != null) entry.work.invalidate(sections, force, clientTicks);
            }
        } else for (BorderColumn entry : border.values())
            if (entry.x >= minX && entry.x <= maxX && entry.z >= minZ && entry.z <= maxZ)
                entry.work.invalidate(sections, force, clientTicks);
        // Native unload/render invalidations do not touch detached historical geometry.
    }

    private static int differences(VisualChunk before, VisualChunk after, int mask) {
        int changed = 0;
        for (int section = VisualChunk.MIN_Y >> 4; section < 16; section++)
            if ((mask & (1 << section)) != 0 && !after.sameSection(before, section)) changed |= 1 << section;
        return changed;
    }

    private static int transferWork(BorderColumn prepared, VisualChunk fresh) {
        // Mere render-range checks are resolved by comparing the final snapshot, not converted into
        // forced rebuilds during ownership transfer. Preserve actual queued/context changes.
        return prepared.work.pending | prepared.work.forced() | prepared.deferred
                | differences(prepared.snapshot, fresh, SectionWork.ALL);
    }

    private void dirtyNeighbors(int x, int z) { dirtyNeighbors(x, z, SectionWork.ALL); }
    private void dirtyNeighbors(int x, int z, int sections) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dz == 0) continue;
            long key = Visibility.key(x + dx, z + dz);
            CachedChunk cached = cache.get(key);
            if (cached != null) cached.work.invalidate(sections, true, clientTicks);
            BorderColumn live = border.get(key);
            if (live != null) live.work.invalidate(sections, true, clientTicks);
        }
    }

    @SubscribeEvent public void loaded(ChunkEvent.Load event) {
        if (event.world.isRemote && event.world == mc.theWorld) {
            syncWorld();
            long key = Visibility.key(event.getChunk().xPosition, event.getChunk().zPosition);
            liveChunks.put(key, event.getChunk()); pendingLive.add(key);
            if (!cache.containsKey(key)) dirtyNeighbors(event.getChunk().xPosition, event.getChunk().zPosition);
        }
    }

    @SubscribeEvent public void cameraFog(EntityViewRenderEvent.FogDensity event) {
        if (mc.theWorld == null || visualRadius <= mc.gameSettings.renderDistanceChunks) return;
        // This event runs inside setupFog before terrain, for normal AND water/lava/blindness fog.
        // Do not cancel it or change density. Change only render projection, never gameplay distance.
        SharedView.extendProjection(visualRadius);
        ClippingHelperImpl.getInstance();
    }

    @SubscribeEvent public void distanceFog(EntityViewRenderEvent.RenderFogEvent event) {
        if (mc.theWorld == null || visualRadius <= mc.gameSettings.renderDistanceChunks) return;
        // Forge invokes this only in the normal distance-fog branch. Preserve special dimension fog.
        if (mc.theWorld.provider.doesXZShowFog((int) event.entity.posX, (int) event.entity.posZ)) return;
        GlStateManager.setFogStart(event.fogMode < 0 ? 0 : Visibility.fogStart(visualRadius));
        GlStateManager.setFogEnd(Visibility.fogEnd(visualRadius));
    }

    private RetainedAccess accessFor(VisualChunk center) {
        ColumnData[] neighbors = new ColumnData[9];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int x = center.xPosition + dx, z = center.zPosition + dz;
            Chunk live = world.getChunkProvider().provideChunk(x, z);
            CachedChunk cached = cache.get(Visibility.key(x, z));
            neighbors[(dz + 1) * 3 + dx + 1] = !(live instanceof EmptyChunk) && live.isLoaded()
                    && (!multiplayer || freshMultiplayer.contains(Visibility.key(x, z)))
                    ? ColumnData.live(live) : cached == null ? null : cached.chunk;
        }
        neighbors[4] = center;
        return new RetainedAccess(center, neighbors);
    }

    @SubscribeEvent public void worldUnloaded(WorldEvent.Unload event) {
        if (event.world.isRemote && event.world == world) {
            saveLiveForExit();
            if (multiplayerSession != null) multiplayerCache.stop(multiplayerSession);
            multiplayerSession = null;
            if (multiplayer) freshMultiplayer.clear();
            fingerprintQueue.clear();
            world.removeWorldAccess(terrainUpdates);
            clear();
            world = null;
        }
    }

    // ChunkProviderClient.chunkExists() is always true. provideChunk() is a non-generating lookup here.
    private boolean live(VisualChunk cached) {
        if (multiplayer && !freshMultiplayer.contains(Visibility.key(cached.xPosition, cached.zPosition))) return false;
        Chunk chunk = mc.theWorld.getChunkProvider().provideChunk(cached.xPosition, cached.zPosition);
        return !(chunk instanceof EmptyChunk) && chunk.isLoaded();
    }

    private long distance(VisualChunk chunk, int cameraX, int cameraZ) {
        return Visibility.distance(cameraX, cameraZ, chunk.xPosition, chunk.zPosition);
    }

    private void collectGarbage() {
        for (DebugMesh mesh : garbage) mesh.delete();
        garbage.clear();
        if (invalidateMeshes) {
            for (CachedChunk entry : cache.values()) { entry.mesh.delete(); entry.work.queue(entry.chunk.sectionMask()); }
            for (BorderColumn entry : border.values()) { entry.mesh.delete(); entry.snapshot = null; entry.work.queue(SectionWork.ALL); }
            invalidateMeshes = false;
            drawnCount = 0;
        }
    }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        clientTicks++;
        syncWorld();
        collectGarbage();
        // Full packets can arrive in large bursts. Copy at most two live fingerprint inputs per tick.
        if (multiplayer && diskEnabled && multiplayerSession != null) {
            Iterator<Long> fingerprints = fingerprintQueue.iterator();
            for (int i = 0; i < 2 && fingerprints.hasNext(); i++) {
                long key = fingerprints.next(); fingerprints.remove();
                Chunk chunk = loadedAt((int) (key >> 32), (int) key);
                if (chunk != null) multiplayerCache.observe(multiplayerSession,
                        VisualChunk.capture(chunk), MapFingerprint.capture(chunk));
            }
        }
        multiplayerCache.tick(System.currentTimeMillis());
        MultiplayerCache.Activation activation;
        while ((activation = multiplayerCache.poll()) != null) {
            if (!diskEnabled || activation.session != multiplayerSession) continue;
            if (disk != null) disk.disable();
            disk = new DiskCache(activation.path);
            multiplayerSession.reader = disk;
            LOG.info("Multiplayer visual disk cache: {}", activation.path);
        }
        String notice;
        while ((notice = multiplayerCache.notice()) != null)
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[roseglass] " + notice));
        pollDisk();
    }

    private Chunk loadedAt(int x, int z) {
        if (multiplayer && !freshMultiplayer.contains(Visibility.key(x, z))) return null;
        // Exact 1.8.9 ChunkProviderClient lookup: missing columns return EmptyChunk, never generate.
        Chunk chunk = world.getChunkProvider().provideChunk(x, z);
        return chunk instanceof EmptyChunk || !chunk.isLoaded() ? null : chunk;
    }

    private BorderColumn adoptCached(int x, int z, CachedChunk retained) {
        BorderColumn entry = new BorderColumn(x, z, retained.mesh, retained.chunk);
        entry.work.queue(retained.work.outstanding());
        entry.work.invalidate(SectionWork.ALL, false, clientTicks - 2);
        entry.mesh.visibleBits = -1L;
        return entry;
    }

    private void queueBorder(long key) {
        int x = (int) (key >> 32), z = (int) key;
        if (loadedAt(x, z) == null) return;
        BorderColumn entry = border.get(key);
        if (entry == null) {
            CachedChunk retained = cache.remove(key);
            entry = retained == null ? new BorderColumn(x, z) : adoptCached(x, z, retained);
            border.put(key, entry);
        }
        else if (entry.mesh.visibleBits == 0) {
            entry.work.queue(entry.deferred); entry.deferred = 0;
            entry.work.invalidate(SectionWork.ALL, false, clientTicks - 2);
        }
    }

    private void syncBorder(int x, int z, int radius) {
        WorkingBand.Delta delta = workingBand.update(x, z, radius, visualRadius, borderEnabled);
        if (delta.changed) {
            Iterator<Map.Entry<Long, BorderColumn>> it = border.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, BorderColumn> pair = it.next();
                BorderColumn entry = pair.getValue();
                if (workingBand.contains(pair.getKey())) continue;
                long d = Visibility.distance(x, z, entry.x, entry.z);
                // Leaving the force band is not proof that native terrain replaced this geometry.
                // Keep already prepared sections drawable, both inward and outward, until out of range.
                if (!borderEnabled || !Visibility.keepMesh(d, entry.mesh.ready(), visualRadius)) {
                    entry.mesh.delete(); it.remove();
                }
            }
            for (long key : delta.entered) queueBorder(key);
        }
        Iterator<Long> retry = pendingLive.iterator();
        while (retry.hasNext()) {
            long key = retry.next();
            if (!workingBand.contains(key)) { retry.remove(); continue; }
            if (loadedAt((int) (key >> 32), (int) key) != null) { queueBorder(key); retry.remove(); }
        }
        for (Map.Entry<Long, BorderColumn> pair : border.entrySet()) {
            BorderColumn entry = pair.getValue();
            long d = Visibility.distance(x, z, entry.x, entry.z);
            entry.mesh.visibleBits = workingBand.contains(pair.getKey())
                    || (borderEnabled && Visibility.keepMesh(d, entry.mesh.ready(), visualRadius)) ? -1L : 0;
            if (entry.mesh.visibleBits != 0 && entry.snapshot != null && !entry.work.checking()
                    && entry.work.pending == 0 && clientTicks - entry.refreshed >= 100)
                entry.work.invalidate(SectionWork.ALL, false, clientTicks);
        }
    }

    private void prepare(BorderColumn entry) {
        if (entry.snapshot == null || entry.work.due(clientTicks)) {
            Chunk live = loadedAt(entry.x, entry.z);
            if (live == null) return;
            VisualChunk fresh = VisualChunk.capture(live);
            int relevant = fresh.sectionMask() | entry.mesh.completedSections();
            if (entry.snapshot == null) {
                entry.work.queue(relevant);
            } else {
                int changed = differences(entry.snapshot, fresh, entry.work.requested());
                entry.work.queue((changed | entry.work.forced()) & relevant);
                if (changed != 0) dirtyNeighbors(entry.x, entry.z, changed);
            }
            entry.work.pending &= relevant;
            entry.snapshot = fresh; entry.refreshed = clientTicks; entry.work.checked();
            if (relevant == 0) entry.mesh.markEmpty();
        }
    }

    private void prepare(CachedChunk entry) {
        if (entry.work.due(clientTicks)) {
            entry.work.queue(entry.work.requested()); entry.work.checked();
        }
        entry.work.pending &= entry.chunk.sectionMask() | entry.mesh.completedSections();
        if (entry.chunk.sectionMask() == 0 && entry.mesh.completedSections() == 0) entry.mesh.markEmpty();
    }

    private int priority(DebugMesh mesh, SectionWork work, boolean nearby) {
        if (!nearby) return 2;
        return !mesh.ready() || (work.pending & ~mesh.completedSections()) != 0 ? 0 : 1;
    }

    private void buildNext(List<CachedChunk> visible, int x, int z, int radius) {
        // A count limit, not a time budget: one vertical section (four layers) per frame.
        // A few no-op preparations may be consumed to discard unchanged render notifications.
        for (int attempt = 0; attempt < 4; attempt++) {
            BorderColumn liveJob = null; CachedChunk cachedJob = null;
            int bestPriority = Integer.MAX_VALUE; long bestDistance = Long.MAX_VALUE;
            for (BorderColumn entry : border.values()) {
                if (entry.mesh.visibleBits == 0 || (entry.work.pending == 0 && !entry.work.due(clientTicks))) continue;
                int p = priority(entry.mesh, entry.work, true);
                long d = Visibility.distance(x, z, entry.x, entry.z);
                if (p < bestPriority || (p == bestPriority && d < bestDistance)) {
                    liveJob = entry; cachedJob = null; bestPriority = p; bestDistance = d;
                }
            }
            for (CachedChunk entry : visible) {
                if (entry.work.pending == 0 && !entry.work.due(clientTicks)) continue;
                long d = distance(entry.chunk, x, z);
                int p = priority(entry.mesh, entry.work, d <= radius);
                if (p < bestPriority || (p == bestPriority && d < bestDistance)) {
                    cachedJob = entry; liveJob = null; bestPriority = p; bestDistance = d;
                }
            }
            if (liveJob == null && cachedJob == null) return;
            DebugMesh mesh; SectionWork work; VisualChunk snapshot;
            if (liveJob != null) {
                prepare(liveJob); mesh = liveJob.mesh; work = liveJob.work; snapshot = liveJob.snapshot;
                if (snapshot == null) { work.cancel(); return; }
            } else {
                prepare(cachedJob); mesh = cachedJob.mesh; work = cachedJob.work; snapshot = cachedJob.chunk;
            }
            int section = work.next();
            if (section < 0) continue;
            mesh.buildSection(snapshot, accessFor(snapshot), section); work.complete(section);
            return;
        }
    }

    @SubscribeEvent public void render(RenderWorldLastEvent event) {
        syncWorld();
        collectGarbage();
        Entity camera = mc.getRenderViewEntity();
        if (world == null || camera == null) return;
        int cameraX = Visibility.chunk(camera.posX), cameraZ = Visibility.chunk(camera.posZ);
        int vanillaRadius = mc.gameSettings.renderDistanceChunks;
        syncBorder(cameraX, cameraZ, vanillaRadius);
        List<CachedChunk> visible = new ArrayList<>();
        Iterator<CachedChunk> iterator = cache.values().iterator();
        while (iterator.hasNext()) {
            CachedChunk entry = iterator.next();
            long d = distance(entry.chunk, cameraX, cameraZ);
            if (live(entry.chunk)) {
                if (borderEnabled && Visibility.keepMesh(d, entry.mesh.ready(), visualRadius)) {
                    long key = Visibility.key(entry.chunk.xPosition, entry.chunk.zPosition);
                    BorderColumn current = border.get(key);
                    if (current == null || !current.mesh.ready()) {
                        if (current != null && current.mesh != entry.mesh) garbage.add(current.mesh);
                        border.put(key, adoptCached(entry.chunk.xPosition, entry.chunk.zPosition, entry));
                    } else if (current.mesh != entry.mesh) discard(entry);
                    iterator.remove();
                    continue;
                }
                // Revisited known terrain may be identical. Check before invalidating neighboring meshes.
                Chunk current = loadedAt(entry.chunk.xPosition, entry.chunk.zPosition);
                int changed = differences(entry.chunk, VisualChunk.capture(current), SectionWork.ALL);
                entry.mesh.delete();
                if (changed != 0) dirtyNeighbors(entry.chunk.xPosition, entry.chunk.zPosition, changed);
                iterator.remove();
                continue;
            }
            if (Visibility.show(d, true, false, vanillaRadius, visualRadius)
                    || Visibility.border(d, borderEnabled, vanillaRadius, visualRadius)
                    || (borderEnabled && Visibility.keepMesh(d, entry.mesh.ready(), visualRadius))) {
                entry.mesh.visibleBits = -1L;
                entry.mesh.borderOverlap = Visibility.insideNative(d, vanillaRadius);
                if (!entry.mesh.ready()) {
                    entry.work.queue(entry.chunk.sectionMask());
                    if (entry.chunk.sectionMask() == 0) entry.mesh.markEmpty();
                }
                visible.add(entry);
            } else {
                // Keep CPU terrain at every distance, but release GPU meshes outside the active annulus.
                entry.mesh.delete();
                entry.work.cancel();
            }
        }
        eligibleCount = visible.size();
        buildNext(visible, cameraX, cameraZ, vanillaRadius);
        pendingCount = 0;
        List<DebugMesh> draw = new ArrayList<>();
        for (CachedChunk entry : visible) {
            if (entry.mesh.ready()) draw.add(entry.mesh);
            if (entry.work.outstanding() != 0) pendingCount++;
        }
        List<DebugMesh> edges = new ArrayList<>();
        borderPending = 0;
        for (BorderColumn entry : border.values()) {
            if (entry.mesh.visibleBits == 0) continue;
            if (entry.mesh.ready() && entry.mesh.sectionBits() != 0) edges.add(entry.mesh);
            if (entry.work.outstanding() != 0) borderPending++;
        }
        borderDrawn = edges.size();
        draw.addAll(edges);
        draw.sort(Comparator.comparingLong(mesh -> mesh.distance(cameraX, cameraZ)));
        drawnCount = draw.size();
        DebugMesh.drawAll(draw, camera, event.partialTicks, visualRadius);
    }

    @SubscribeEvent public void overlay(RenderGameOverlayEvent.Text event) {
        if (!debugOverlay) return;
        event.left.add("roseglass: DEBUG TERRAIN PASS");
        event.left.add(status());
    }

    private String status() {
        return "cached=" + cache.size() + " eligible=" + eligibleCount + " drawn=" + drawnCount
                + " queued=" + pendingCount
                + " vanilla=" + mc.gameSettings.renderDistanceChunks + " visual=" + visualRadius
                + " minY=" + VisualChunk.MIN_Y + " debug=" + (debugOverlay ? "on" : "off")
                + " disk=" + (diskEnabled ? "on" : "off");
    }

    private final class Control extends CommandBase {
        @Override public String getCommandName() { return "roseglass"; }
        @Override public String getCommandUsage(ICommandSender sender) {
            return "/roseglass distance <chunks> | debug [on|off] | disk [on|off] | border [on|off] | cleanup [days|-1] | status | clear";
        }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
        @Override public void processCommand(ICommandSender sender, String[] args)
                throws net.minecraft.command.CommandException {
            if ((args.length == 1 || args.length == 2) && "cleanup".equals(args[0])) {
                if (args.length == 1) {
                    multiplayerCache.cleanup();
                    sender.addChatMessage(new ChatComponentText("[roseglass] Multiplayer cleanup queued."));
                } else {
                    int days = parseInt(args[1], -1, Integer.MAX_VALUE);
                    if (days == 0) throw new net.minecraft.command.WrongUsageException("Use positive days or -1 to disable automatic cleanup.");
                    cleanupInterval = days;
                    multiplayerCache.settings(days, cleanupAge, cleanupChunks);
                    config.get("multiplayer", "cleanupIntervalDays", 15).set(days); config.save();
                    sender.addChatMessage(new ChatComponentText(days == -1 ? "[roseglass] Automatic cleanup disabled."
                            : "[roseglass] Automatic cleanup interval set to " + days + " days."));
                }
                return;
            } else if (args.length == 2 && "distance".equals(args[0])) {
                visualRadius = parseInt(args[1], 1, Integer.MAX_VALUE);
                config.get("rendering", "visualDistance", 32).set(visualRadius);
                config.save();
                sender.addChatMessage(new ChatComponentText("[roseglass] Visual distance set to "
                        + visualRadius + " chunks. Vanilla distance remains " + mc.gameSettings.renderDistanceChunks + "."));
                return;
            } else if ((args.length == 1 || args.length == 2) && "debug".equals(args[0])) {
                boolean enabled;
                if (args.length == 1) enabled = !debugOverlay;
                else if ("on".equalsIgnoreCase(args[1])) enabled = true;
                else if ("off".equalsIgnoreCase(args[1])) enabled = false;
                else throw new net.minecraft.command.WrongUsageException(getCommandUsage(sender));
                debugOverlay = enabled;
                config.get("rendering", "debugOverlay", false).set(debugOverlay);
                config.save();
                sender.addChatMessage(new ChatComponentText("[roseglass] Debug overlay "
                        + (debugOverlay ? "on" : "off") + "."));
                return;
            } else if ((args.length == 1 || args.length == 2) && "border".equals(args[0])) {
                if (args.length == 1) borderEnabled = !borderEnabled;
                else if ("on".equalsIgnoreCase(args[1])) borderEnabled = true;
                else if ("off".equalsIgnoreCase(args[1])) borderEnabled = false;
                else throw new net.minecraft.command.WrongUsageException(getCommandUsage(sender));
                if (!borderEnabled) clearBorder();
                config.get("rendering", "borderQueue", true).set(borderEnabled);
                config.save();
                sender.addChatMessage(new ChatComponentText("[roseglass] Border queue " + (borderEnabled ? "on" : "off") + "."));
                return;
            } else if ((args.length == 1 || args.length == 2) && "disk".equals(args[0])) {
                boolean enabled;
                if (args.length == 1) enabled = !diskEnabled;
                else if ("on".equalsIgnoreCase(args[1])) enabled = true;
                else if ("off".equalsIgnoreCase(args[1])) enabled = false;
                else throw new net.minecraft.command.WrongUsageException(getCommandUsage(sender));
                diskEnabled = enabled;
                if (disk != null) disk.disable();
                openDisk();
                if (multiplayer && diskEnabled) {
                    ensureMultiplayerSession();
                    if (multiplayerSession != null) for (Chunk chunk : liveChunks.values())
                        if (chunk.isLoaded() && freshMultiplayer.contains(Visibility.key(chunk.xPosition, chunk.zPosition)))
                            fingerprintQueue.add(Visibility.key(chunk.xPosition, chunk.zPosition));
                }
                for (CachedChunk entry : cache.values()) saveSnapshot(entry.chunk);
                config.get("storage", "diskCache", true).set(diskEnabled);
                config.save();
                sender.addChatMessage(new ChatComponentText("[roseglass] disk " + (diskEnabled ? "on" : "off") + "."));
                return;
            } else if (args.length == 1 && "clear".equals(args[0])) {
                for (CachedChunk entry : cache.values()) discard(entry);
                cache.clear();
                clearBorder();
                eligibleCount = drawnCount = pendingCount = 0;
                diskReadsPaused = true;
            } else if (args.length > 1 || (args.length == 1 && !"status".equals(args[0]))) {
                throw new net.minecraft.command.WrongUsageException(getCommandUsage(sender));
            }
            sender.addChatMessage(new ChatComponentText("[roseglass] " + status()));
            if (disk != null) sender.addChatMessage(new ChatComponentText("[roseglass] " + disk.directory() + " (" + disk.status() + ")"));
        }
        @Override public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
            if (args.length == 1) return getListOfStringsMatchingLastWord(args, "distance", "debug", "disk", "border", "cleanup", "status", "clear");
            if (args.length == 2 && "cleanup".equals(args[0])) return getListOfStringsMatchingLastWord(args, "15", "-1");
            if (args.length == 2 && ("debug".equals(args[0]) || "disk".equals(args[0]) || "border".equals(args[0])))
                return getListOfStringsMatchingLastWord(args, "on", "off");
            if (args.length == 2 && "distance".equals(args[0])) return getListOfStringsMatchingLastWord(args, "16", "32", "64", "100");
            return null;
        }
    }
}
