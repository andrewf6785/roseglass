package dev.roseglass;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IWorldAccess;

/** Read-only client-world observer. Uses the same block/light/render notifications as native terrain. */
final class TerrainUpdates implements IWorldAccess {
    interface Listener { void changed(int minX, int minZ, int maxX, int maxZ, int sections, boolean force); }
    private final Listener listener;
    TerrainUpdates(Listener listener) { this.listener = listener; }

    private static int chunk(long block) {
        return (int) Math.max(Integer.MIN_VALUE >> 4, Math.min(Integer.MAX_VALUE >> 4, block >> 4));
    }
    static int sections(int y1, int y2) {
        int min = (int) Math.max(VisualChunk.MIN_Y, (long) Math.min(y1, y2) - 1);
        int max = (int) Math.min(255, (long) Math.max(y1, y2) + 1);
        if (min > max) return 0;
        int mask = 0;
        for (int s = min >> 4; s <= (max >> 4); s++) mask |= 1 << s;
        return mask & SectionWork.ALL;
    }
    private void changed(int x1, int y1, int z1, int x2, int y2, int z2, boolean force) {
        int mask = sections(y1, y2);
        if (mask != 0) listener.changed(chunk((long) Math.min(x1, x2) - 1), chunk((long) Math.min(z1, z2) - 1),
                chunk((long) Math.max(x1, x2) + 1), chunk((long) Math.max(z1, z2) + 1), mask, force);
    }
    @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        changed(x1, y1, z1, x2, y2, z2, false);
    }
    @Override public void markBlockForUpdate(BlockPos p) {
        changed(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ(), true);
    }
    @Override public void notifyLightSet(BlockPos p) { markBlockForUpdate(p); }
    @Override public void playSound(String name, double x, double y, double z, float volume, float pitch) { }
    @Override public void playSoundToNearExcept(EntityPlayer player, String name, double x, double y, double z, float volume, float pitch) { }
    @Override public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double vx, double vy, double vz, int... args) { }
    @Override public void onEntityAdded(Entity entity) { }
    @Override public void onEntityRemoved(Entity entity) { }
    @Override public void playRecord(String name, BlockPos pos) { }
    @Override public void broadcastSound(int id, BlockPos pos, int data) { }
    @Override public void playAuxSFX(EntityPlayer player, int id, BlockPos pos, int data) { }
    @Override public void sendBlockBreakProgress(int id, BlockPos pos, int progress) { }
}
