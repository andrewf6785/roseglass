package dev.roseglass;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;

/** Common render-only view for detached snapshots and temporary live neighbor access. */
abstract class ColumnData {
    final int xPosition, zPosition;
    ColumnData(int x, int z) { xPosition = x; zPosition = z; }
    abstract IBlockState getBlockState(BlockPos pos);
    abstract int getLightFor(EnumSkyBlock type, BlockPos pos);
    abstract byte[] getBiomeArray();

    // The wrapper exists only during synchronous tessellation; never stored in the terrain cache.
    static ColumnData live(final Chunk chunk) {
        return new ColumnData(chunk.xPosition, chunk.zPosition) {
            @Override IBlockState getBlockState(BlockPos pos) { return chunk.getBlockState(pos); }
            @Override int getLightFor(EnumSkyBlock type, BlockPos pos) { return chunk.getLightFor(type, pos); }
            @Override byte[] getBiomeArray() { return chunk.getBiomeArray(); }
        };
    }
}
