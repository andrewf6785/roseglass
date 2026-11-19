package dev.roseglass;

import java.io.*;
import java.security.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Canonical full-height block identities, independent of render cutoff and runtime state IDs. */
final class MapFingerprint {
    static final int VERSION = 1;
    private static final Map<IBlockState, byte[]> identities = new HashMap<>();
    final char[][] sections;
    final Map<Character, byte[]> palette;

    private MapFingerprint(char[][] sections, Map<Character, byte[]> palette) {
        this.sections = sections; this.palette = palette;
    }

    // Called on the client thread. Detached IDs plus canonical palette bytes cross the worker boundary.
    static MapFingerprint capture(Chunk chunk) {
        char[][] sections = new char[16][];
        Map<Character, byte[]> palette = new HashMap<>();
        ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        try {
            char air = (char) Block.BLOCK_STATE_IDS.get(Blocks.air.getDefaultState());
            palette.put(air, identity(Blocks.air.getDefaultState()));
            for (int s = 0; s < 16; s++) {
                if (storage[s] == null) {
                    sections[s] = new char[4096]; Arrays.fill(sections[s], air);
                    continue;
                } else sections[s] = storage[s].getData().clone();
                for (char id : sections[s]) if (!palette.containsKey(id)) {
                    IBlockState state = Block.BLOCK_STATE_IDS.getByValue(id);
                    if (state == null) throw new IllegalArgumentException("Unknown block state");
                    palette.put(id, identity(state));
                }
            }
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return new MapFingerprint(sections, palette);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] identity(IBlockState state) throws IOException {
        byte[] existing = identities.get(state);
        if (existing != null) return existing;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(Block.blockRegistry.getNameForObject(state.getBlock()).toString());
        List<IProperty> props = new ArrayList<>(state.getProperties().keySet());
        props.sort(Comparator.comparing(IProperty::getName)); out.writeInt(props.size());
        for (IProperty property : props) {
            out.writeUTF(property.getName()); out.writeUTF(property.getName(state.getValue(property)));
        }
        byte[] result = bytes.toByteArray(); identities.put(state, result); return result;
    }

    String hash() {
        boolean informative = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (char[] section : sections) {
                for (int y = 0; y < 16; y++) {
                    char first = section[y * 256];
                    for (int i = y * 256; i < (y + 1) * 256; i++) {
                        informative |= section[i] != first;
                        digest.update(palette.get(section[i]));
                    }
                }
            }
            if (!informative) return "";
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
