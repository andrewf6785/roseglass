package dev.roseglass;

import java.util.Arrays;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Detached visual snapshot. No Chunk, World, entity, tile entity or storage-array references. */
final class VisualChunk extends ColumnData {
    static final int MIN_Y = 50;
    final WorldType worldType;
    final boolean noSky;
    private final char[][] states = new char[16][];
    private final byte[][] lights = new byte[16][];
    private final byte[] biomes;
    private final short[] heights = new short[256];

    static VisualChunk capture(Chunk chunk) {
        return new VisualChunk(chunk, chunk.getWorld().getWorldType(), chunk.getWorld().provider.getHasNoSky());
    }

    VisualChunk(Chunk chunk, WorldType type, boolean noSky) {
        super(chunk.xPosition, chunk.zPosition);
        this.worldType = type;
        this.noSky = noSky;
        biomes = chunk.getBiomeArray().clone();
        for (int i = 0; i < 256; i++) heights[i] = (short) Math.max(MIN_Y, chunk.getHeightMap()[i]);
        for (int section = MIN_Y >> 4; section < 16; section++) {
            ExtendedBlockStorage source = chunk.getBlockStorageArray()[section];
            if (source == null) continue;
            int first = Math.max(0, MIN_Y - section * 16) * 256;
            char[] copy = Arrays.copyOfRange(source.getData(), first, 4096);
            boolean anyBlock = false;
            for (char id : copy) if (id != 0) { anyBlock = true; break; }
            if (anyBlock) states[section] = copy;
            // One packed byte per block: sky in high nibble, block light in low nibble.
            byte[] light = new byte[4096 - first];
            for (int i = first; i < 4096; i++) {
                int sky = noSky || source.getSkylightArray() == null ? 0 : source.getSkylightArray().getFromIndex(i);
                light[i - first] = (byte) ((sky << 4) | source.getBlocklightArray().getFromIndex(i));
            }
            lights[section] = light;
        }
    }

    private VisualChunk(int x, int z, WorldType type, boolean noSky, byte[] biomes) {
        super(x, z);
        this.worldType = type;
        this.noSky = noSky;
        this.biomes = biomes;
    }

    // Files use registry names/properties, never launch-specific numeric state IDs.
    void write(DataOutput out) throws IOException {
        out.writeInt(0x52475343); out.writeInt(1); out.writeInt(MIN_Y);
        out.writeInt(xPosition); out.writeInt(zPosition);
        out.writeUTF(worldType.getWorldTypeName()); out.writeBoolean(noSky);
        out.write(biomes);
        for (short height : heights) out.writeShort(height);
        Map<Character, Integer> palette = new LinkedHashMap<>();
        for (char[] section : states) if (section != null) {
            for (char id : section) if (!palette.containsKey(id)) palette.put(id, palette.size());
        }
        out.writeInt(palette.size());
        for (char id : palette.keySet()) SnapshotStates.write(out, Block.BLOCK_STATE_IDS.getByValue(id));
        for (int s = 0; s < 16; s++) {
            out.writeInt(states[s] == null ? -1 : states[s].length);
            if (states[s] != null) for (char id : states[s]) out.writeShort(palette.get(id));
            out.writeInt(lights[s] == null ? -1 : lights[s].length);
            if (lights[s] != null) out.write(lights[s]);
        }
    }

    static VisualChunk read(DataInput in) throws IOException {
        if (in.readInt() != 0x52475343 || in.readInt() != 1 || in.readInt() != MIN_Y)
            throw new IOException("Unsupported roseglass snapshot header/cutoff");
        int x = in.readInt(), z = in.readInt();
        WorldType type = WorldType.parseWorldType(in.readUTF());
        if (type == null) throw new IOException("Unknown world type");
        boolean noSky = in.readBoolean();
        byte[] biomes = new byte[256]; in.readFully(biomes);
        VisualChunk chunk = new VisualChunk(x, z, type, noSky, biomes);
        for (int i = 0; i < 256; i++) {
            int height = in.readUnsignedShort();
            if (height < MIN_Y || height > 256) throw new IOException("Invalid height summary");
            chunk.heights[i] = (short) height;
        }
        int count = in.readInt();
        if (count < 0 || count > 65536) throw new IOException("Invalid palette size");
        char[] palette = new char[count];
        for (int i = 0; i < count; i++) palette[i] = SnapshotStates.read(in);
        for (int s = 0; s < 16; s++) {
            int expected = s < (MIN_Y >> 4) ? 0 : (16 - Math.max(0, MIN_Y - s * 16)) * 256;
            int length = in.readInt();
            if (length != -1) {
                if (length != expected || expected == 0) throw new IOException("Invalid state section length");
                chunk.states[s] = new char[length];
                for (int i = 0; i < length; i++) {
                    int id = in.readUnsignedShort();
                    if (id >= count) throw new IOException("Invalid palette index");
                    chunk.states[s][i] = palette[id];
                }
            }
            length = in.readInt();
            if (length != -1) {
                if (length != expected || expected == 0) throw new IOException("Invalid light section length");
                chunk.lights[s] = new byte[length]; in.readFully(chunk.lights[s]);
            } else if (chunk.states[s] != null) throw new IOException("Missing section lighting");
        }
        return chunk;
    }

    private int index(BlockPos pos) {
        int firstY = Math.max(MIN_Y, (pos.getY() >> 4) * 16);
        return ((pos.getY() - firstY) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
    }

    int sectionMask() {
        int mask = 0;
        for (int s = MIN_Y >> 4; s < 16; s++) if (states[s] != null) mask |= 1 << s;
        return mask;
    }

    boolean sameSection(VisualChunk other, int section) {
        return other != null && noSky == other.noSky && worldType == other.worldType
                && Arrays.equals(states[section], other.states[section])
                && Arrays.equals(lights[section], other.lights[section])
                && Arrays.equals(biomes, other.biomes) && Arrays.equals(heights, other.heights);
    }

    boolean hasSection(int section) { return states[section] != null; }

    @Override IBlockState getBlockState(BlockPos pos) {
        int y = pos.getY();
        if (y < MIN_Y || y >= 256 || states[y >> 4] == null) return Blocks.air.getDefaultState();
        IBlockState state = Block.BLOCK_STATE_IDS.getByValue(states[y >> 4][index(pos)]);
        return state == null ? Blocks.air.getDefaultState() : state;
    }

    @Override int getLightFor(EnumSkyBlock type, BlockPos pos) {
        int y = pos.getY();
        if (type == EnumSkyBlock.SKY && noSky) return 0;
        if (y < MIN_Y || y >= 256) return type.defaultLightValue;
        byte[] light = lights[y >> 4];
        if (light == null) return type == EnumSkyBlock.SKY
                && y >= heights[((pos.getZ() & 15) << 4) | (pos.getX() & 15)] ? 15 : 0;
        int packed = light[index(pos)] & 255;
        return type == EnumSkyBlock.SKY ? packed >> 4 : packed & 15;
    }

    @Override byte[] getBiomeArray() { return biomes; }

    long payloadBytes() {
        long bytes = biomes.length + heights.length * 2L;
        for (int i = 0; i < 16; i++) {
            if (states[i] != null) bytes += states[i].length * 2L;
            if (lights[i] != null) bytes += lights[i].length;
        }
        return bytes;
    }
}
