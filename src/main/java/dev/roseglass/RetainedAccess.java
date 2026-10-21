package dev.roseglass;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;

/** Render-only 3x3 context. Neighbors were resolved without loading chunks before tessellation. */
final class RetainedAccess implements IBlockAccess {
    private final ColumnData center;
    private final ColumnData[] neighbors;
    private final WorldType worldType;
    private final boolean noSky;

    RetainedAccess(VisualChunk center, ColumnData[] neighbors) {
        this(center, neighbors, center.worldType, center.noSky);
    }

    RetainedAccess(ColumnData center, ColumnData[] neighbors, WorldType type, boolean noSky) {
        this.center = center;
        this.neighbors = neighbors;
        this.worldType = type;
        this.noSky = noSky;
    }

    private ColumnData at(BlockPos p) {
        int dx = (p.getX() >> 4) - center.xPosition;
        int dz = (p.getZ() >> 4) - center.zPosition;
        return dx >= -1 && dx <= 1 && dz >= -1 && dz <= 1 ? neighbors[(dz + 1) * 3 + dx + 1] : null;
    }

    private BlockPos edge(BlockPos p) {
        int x = center.xPosition << 4, z = center.zPosition << 4;
        return new BlockPos(Math.max(x, Math.min(x + 15, p.getX())), Math.max(VisualChunk.MIN_Y, p.getY()),
                Math.max(z, Math.min(z + 15, p.getZ())));
    }

    @Override public IBlockState getBlockState(BlockPos p) {
        if (p.getY() < 0 || p.getY() >= 256) return Blocks.air.getDefaultState();
        // Repeat the cutoff layer only as occlusion context, so no artificial underside is emitted.
        if (p.getY() < VisualChunk.MIN_Y) p = new BlockPos(p.getX(), VisualChunk.MIN_Y, p.getZ());
        ColumnData chunk = at(p);
        // At an unexplored frontier, extend the nearest edge only for neighbor queries. This suppresses
        // the artificial vertical cutaway; no fallback blocks are emitted or inserted into any world.
        return chunk == null ? center.getBlockState(edge(p)) : chunk.getBlockState(p);
    }

    @Override public TileEntity getTileEntity(BlockPos p) { return null; }

    private int light(EnumSkyBlock type, BlockPos p) {
        if (type == EnumSkyBlock.SKY && noSky) return 0;
        if (p.getY() < 0 || p.getY() >= 256) return type.defaultLightValue;
        if (p.getY() < VisualChunk.MIN_Y) p = new BlockPos(p.getX(), VisualChunk.MIN_Y, p.getZ());
        ColumnData chunk = at(p);
        return chunk == null ? center.getLightFor(type, edge(p)) : chunk.getLightFor(type, p);
    }

    private int lightWithNeighbors(EnumSkyBlock type, BlockPos p) {
        if (!getBlockState(p).getBlock().getUseNeighborBrightness()) return light(type, p);
        int maximum = 0;
        for (EnumFacing facing : EnumFacing.values()) maximum = Math.max(maximum, light(type, p.offset(facing)));
        return maximum;
    }

    @Override public int getCombinedLight(BlockPos p, int minimum) {
        return (lightWithNeighbors(EnumSkyBlock.SKY, p) << 20)
                | (Math.max(minimum, lightWithNeighbors(EnumSkyBlock.BLOCK, p)) << 4);
    }
    @Override public boolean isAirBlock(BlockPos p) { return getBlockState(p).getBlock().isAir(this, p); }
    @Override public BiomeGenBase getBiomeGenForCoords(BlockPos p) {
        ColumnData chunk = at(p);
        if (chunk == null) { chunk = center; p = edge(p); }
        BiomeGenBase biome = BiomeGenBase.getBiome(chunk.getBiomeArray()[((p.getZ() & 15) << 4) | (p.getX() & 15)] & 255);
        return biome == null ? BiomeGenBase.plains : biome;
    }
    @Override public boolean extendedLevelsInChunkCache() { return false; }
    @Override public int getStrongPower(BlockPos p, EnumFacing side) { return 0; }
    @Override public WorldType getWorldType() { return worldType; }
    @Override public boolean isSideSolid(BlockPos p, EnumFacing side, boolean fallback) {
        return p.getY() < 0 || p.getY() >= 256 ? fallback : getBlockState(p).getBlock().isSideSolid(this, p, side);
    }
}
