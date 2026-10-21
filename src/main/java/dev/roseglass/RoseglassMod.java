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

@Mod(modid = "roseglass", name = "roseglass", version = "0.7.3",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*")
public final class RoseglassMod {
    private static final Logger LOG = LogManager.getLogger("roseglass");
    private final Minecraft mc = Minecraft.getMinecraft();
    private final Map<Long, CachedChunk> cache = new HashMap<>();
    // Live tracking supports persistence; border rendering uses non-generating provider lookups.
    private final Map<Long, Chunk> liveChunks = new HashMap<>();
    private final Map<Long, BorderColumn> border = new HashMap<>();
    private final List<DebugMesh> garbage = new ArrayList<>();
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

    private static final class CachedChunk {
        final VisualChunk chunk;
        final DebugMesh mesh = new DebugMesh();
        boolean dirty = true;
        CachedChunk(Chunk chunk) { this.chunk = VisualChunk.capture(chunk); }
        CachedChunk(VisualChunk chunk) { this.chunk = chunk; }
    }

    private static final class BorderColumn {
        final int x, z;
        final DebugMesh mesh = new DebugMesh();
        long refreshed = Long.MIN_VALUE;
        BorderColumn(int x, int z) { this.x = x; this.z = z; mesh.borderOverlap = true; }
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
                "Visual fallback for the native border and one ring on each side. /roseglass border on|off");
        if (config.hasChanged()) config.save();
        MinecraftForge.EVENT_BUS.register(this);
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
        borderDrawn = borderPending = 0;
    }

    private void syncWorld() {
        if (world != mc.theWorld) {
            saveLiveForExit();
            clear();
            world = mc.theWorld;
            openDisk();
        }
    }

    private void openDisk() {
        disk = null;
        diskReadsPaused = false;
        if (!diskEnabled || world == null) return;
        String kind, identity;
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null) {
            kind = "world"; identity = mc.getIntegratedServer().getFolderName()
                    + "|seed=" + mc.getIntegratedServer().worldServerForDimension(0).getSeed();
        } else if (mc.getCurrentServerData() != null) {
            kind = "server"; identity = mc.getCurrentServerData().serverIP.trim().toLowerCase(java.util.Locale.ROOT);
        } else {
            LOG.warn("No reliable world/server identity; disk cache disabled for this session"); return;
        }
        disk = new DiskCache(DiskCache.scope(mc.mcDataDir.toPath().resolve(".roseglass/cached_chunk_data"),
                kind, identity, world.provider.getDimensionId()));
        LOG.info("Visual disk cache: {}", disk.directory());
    }

    private void saveLiveForExit() {
        if (disk == null) return;
        for (Chunk chunk : liveChunks.values()) if (chunk.isLoaded() && chunk.getWorld() == world)
            disk.save(VisualChunk.capture(chunk));
        disk = null;
    }

    private void pollDisk() {
        if (disk == null) return;
        DiskCache.Result result;
        while ((result = disk.poll()) != null) {
            if (diskReadsPaused || result.chunk == null || cache.containsKey(result.key) || liveChunks.containsKey(result.key)) continue;
            cache.put(result.key, new CachedChunk(result.chunk));
            dirtyNeighbors(result.chunk.xPosition, result.chunk.zPosition);
        }
        Entity camera = mc.getRenderViewEntity();
        if (camera != null && !diskReadsPaused) {
            Set<Long> present = new HashSet<>(cache.keySet()); present.addAll(liveChunks.keySet());
            disk.request(Visibility.chunk(camera.posX), Visibility.chunk(camera.posZ), visualRadius, present);
        }
    }

    @SubscribeEvent public void unloaded(ChunkEvent.Unload event) {
        if (!event.world.isRemote || event.world != mc.theWorld) return;
        Chunk chunk = event.getChunk();
        if (chunk instanceof EmptyChunk) return;
        syncWorld();
        long key = Visibility.key(chunk.xPosition, chunk.zPosition);
        liveChunks.remove(key);
        CachedChunk previous = cache.put(Visibility.key(chunk.xPosition, chunk.zPosition), new CachedChunk(chunk));
        if (previous != null) discard(previous);
        if (disk != null) disk.save(cache.get(key).chunk);
        dirtyNeighbors(chunk.xPosition, chunk.zPosition);
        // Capture every column, regardless of distance; never request chunks or mutate the provider.
    }

    private void dirtyNeighbors(int x, int z) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            CachedChunk entry = cache.get(Visibility.key(x + dx, z + dz));
            if (entry != null) entry.dirty = true;
            BorderColumn edge = border.get(Visibility.key(x + dx, z + dz));
            if (edge != null) edge.refreshed = Long.MIN_VALUE;
        }
    }

    @SubscribeEvent public void loaded(ChunkEvent.Load event) {
        if (event.world.isRemote && event.world == mc.theWorld) {
            syncWorld();
            liveChunks.put(Visibility.key(event.getChunk().xPosition, event.getChunk().zPosition), event.getChunk());
            dirtyNeighbors(event.getChunk().xPosition, event.getChunk().zPosition);
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
                    ? ColumnData.live(live) : cached == null ? null : cached.chunk;
        }
        neighbors[4] = center;
        return new RetainedAccess(center, neighbors);
    }

    @SubscribeEvent public void worldUnloaded(WorldEvent.Unload event) {
        if (event.world.isRemote && event.world == world) {
            saveLiveForExit();
            clear();
            world = null;
        }
    }

    // ChunkProviderClient.chunkExists() is always true. provideChunk() is a non-generating lookup here.
    private boolean live(VisualChunk cached) {
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
            for (CachedChunk entry : cache.values()) { entry.mesh.delete(); entry.dirty = true; }
            for (BorderColumn entry : border.values()) { entry.mesh.delete(); entry.refreshed = Long.MIN_VALUE; }
            invalidateMeshes = false;
            drawnCount = 0;
        }
    }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        clientTicks++;
        syncWorld();
        collectGarbage();
        pollDisk();
    }

    private Chunk loadedAt(int x, int z) {
        // Exact 1.8.9 ChunkProviderClient lookup: missing columns return EmptyChunk, never generate.
        Chunk chunk = world.getChunkProvider().provideChunk(x, z);
        return chunk instanceof EmptyChunk || !chunk.isLoaded() ? null : chunk;
    }

    private void queueBorder(int x, int z) {
        if (loadedAt(x, z) != null) border.computeIfAbsent(Visibility.key(x, z), key -> new BorderColumn(x, z));
    }

    private void syncBorder(int x, int z, int radius) {
        Iterator<BorderColumn> it = border.values().iterator();
        while (it.hasNext()) {
            BorderColumn entry = it.next();
            if (!Visibility.border(Visibility.distance(x, z, entry.x, entry.z), borderEnabled, radius, visualRadius)
                    || loadedAt(entry.x, entry.z) == null) {
                entry.mesh.delete(); it.remove();
            }
        }
        if (!Visibility.border(radius, borderEnabled, radius, visualRadius)) return;
        // Three rings: one inside the native border, the border itself, and one outside.
        for (int offset = -Visibility.BORDER_INSET; offset <= Visibility.BORDER_OUTSET; offset++) {
            int ring = radius + offset;
            if (!Visibility.border(ring, borderEnabled, radius, visualRadius)) continue;
            if (ring == 0) { queueBorder(x, z); continue; }
            for (int dx = -ring; dx <= ring; dx++) {
                queueBorder(x + dx, z - ring);
                queueBorder(x + dx, z + ring);
            }
            for (int dz = -ring + 1; dz < ring; dz++) {
                queueBorder(x - ring, z + dz);
                queueBorder(x + ring, z + dz);
            }
        }
    }

    private void buildBorder(BorderColumn entry) {
        Chunk live = loadedAt(entry.x, entry.z);
        if (live == null) return;
        // Capture only at compilation time, never build from historical disk data over a live column.
        VisualChunk snapshot = VisualChunk.capture(live);
        entry.mesh.build(snapshot, accessFor(snapshot));
        entry.refreshed = clientTicks;
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
            if (live(entry.chunk)) {
                // New live data wins; a later unload captures its updated terrain.
                entry.mesh.delete();
                dirtyNeighbors(entry.chunk.xPosition, entry.chunk.zPosition);
                iterator.remove();
                continue;
            }
            long d = distance(entry.chunk, cameraX, cameraZ);
            if (Visibility.show(d, true, false, vanillaRadius, visualRadius)
                    || Visibility.border(d, borderEnabled, vanillaRadius, visualRadius)) {
                visible.add(entry);
            } else {
                // Keep CPU terrain at every distance, but release GPU meshes outside the active annulus.
                entry.mesh.delete();
            }
        }
        visible.sort(Comparator.comparingLong(entry -> distance(entry.chunk, cameraX, cameraZ)));
        eligibleCount = visible.size();
        boolean built = false;
        pendingCount = 0;
        // Newly exposed border holes have priority, sharing the existing ONE-column/frame budget.
        BorderColumn missing = null;
        for (BorderColumn entry : border.values()) if (!entry.mesh.ready()) { missing = entry; break; }
        if (missing != null) { buildBorder(missing); built = true; }
        List<DebugMesh> draw = new ArrayList<>();
        for (CachedChunk entry : visible) {
            // One synchronous column compilation per frame, closest pending column first.
            if ((!entry.mesh.ready() || entry.dirty) && !built) {
                entry.mesh.build(entry.chunk, accessFor(entry.chunk));
                entry.dirty = false;
                built = true;
            }
            if (entry.mesh.ready()) draw.add(entry.mesh);
            if (entry.dirty || !entry.mesh.ready()) pendingCount++;
        }
        // Refresh the oldest live border mesh when no new cached/border mesh needs compilation.
        BorderColumn oldest = null;
        for (BorderColumn entry : border.values())
            if (oldest == null || entry.refreshed < oldest.refreshed) oldest = entry;
        if (!built && oldest != null && (oldest.refreshed == Long.MIN_VALUE || clientTicks - oldest.refreshed >= 100))
            buildBorder(oldest);
        List<DebugMesh> edges = new ArrayList<>();
        borderPending = 0;
        for (BorderColumn entry : border.values()) {
            if (entry.mesh.ready()) edges.add(entry.mesh); else borderPending++;
        }
        borderDrawn = edges.size();
        draw.addAll(0, edges); // Border band precedes the outer cached columns (overlapping outer ring is the same radius).
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
                + " disk=" + (diskEnabled ? "on" : "off")
                + " edgeDrawn=" + borderDrawn + " edgeQueued=" + borderPending;
    }

    private final class Control extends CommandBase {
        @Override public String getCommandName() { return "roseglass"; }
        @Override public String getCommandUsage(ICommandSender sender) {
            return "/roseglass distance <chunks> | debug [on|off] | disk [on|off] | border [on|off] | status | clear";
        }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
        @Override public void processCommand(ICommandSender sender, String[] args)
                throws net.minecraft.command.CommandException {
            if (args.length == 2 && "distance".equals(args[0])) {
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
                if (disk != null) for (CachedChunk entry : cache.values()) disk.save(entry.chunk);
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
            if (args.length == 1) return getListOfStringsMatchingLastWord(args, "distance", "debug", "disk", "border", "status", "clear");
            if (args.length == 2 && ("debug".equals(args[0]) || "disk".equals(args[0]) || "border".equals(args[0])))
                return getListOfStringsMatchingLastWord(args, "on", "off");
            if (args.length == 2 && "distance".equals(args[0])) return getListOfStringsMatchingLastWord(args, "16", "32", "64", "100");
            return null;
        }
    }
}
