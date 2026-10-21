package dev.roseglass;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;

/** Stable on-disk state identity; reject incompatible blocks rather than silently substituting terrain. */
final class SnapshotStates {
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void write(DataOutput out, IBlockState state) throws IOException {
        if (state == null) throw new IOException("Unregistered state");
        ResourceLocation name = Block.blockRegistry.getNameForObject(state.getBlock());
        if (name == null) throw new IOException("Unregistered block");
        out.writeUTF(name.toString());
        out.writeInt(state.getProperties().size());
        for (Map.Entry<IProperty, Comparable> entry : state.getProperties().entrySet()) {
            out.writeUTF(entry.getKey().getName());
            out.writeUTF(entry.getKey().getName(entry.getValue()));
        }
    }

    static char read(DataInput in) throws IOException {
        ResourceLocation name = new ResourceLocation(in.readUTF());
        if (!Block.blockRegistry.containsKey(name)) throw new IOException("Missing block " + name);
        IBlockState state = Block.blockRegistry.getObject(name).getDefaultState();
        int count = in.readInt();
        if (count < 0 || count > 32 || count != state.getProperties().size()) throw new IOException("Incompatible property count");
        for (int i = 0; i < count; i++) {
            String propertyName = in.readUTF(), value = in.readUTF();
            IProperty<?> property = null;
            for (IProperty<?> candidate : state.getPropertyNames())
                if (candidate.getName().equals(propertyName)) property = candidate;
            if (property == null) throw new IOException("Missing state property " + propertyName);
            state = set(state, property, value);
        }
        int id = Block.BLOCK_STATE_IDS.get(state);
        if (id < 0 || id > 65535) throw new IOException("Unregistered restored state");
        return (char) id;
    }

    private static <T extends Comparable<T>> IBlockState set(IBlockState state, IProperty<T> property, String name)
            throws IOException {
        for (T value : property.getAllowedValues()) if (property.getName(value).equals(name))
            return state.withProperty(property, value);
        throw new IOException("Unsupported property value " + name);
    }
}
