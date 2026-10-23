package dev.roseglass;

import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraftforge.client.ForgeHooksClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Separate final terrain pass. Section replacements preserve old geometry until compilation succeeds. */
final class DebugMesh {
    boolean borderOverlap;
    long visibleBits = -1L;
    private final int[] lists = new int[16];
    private int completed;
    private boolean initialized;
    private long emittedBits;
    private int originX, originZ;
    private static WorldRenderer buildBuffer;

    void delete() {
        if (!initialized) return;
        for (int s = 0; s < 16; s++) { if (lists[s] != 0) GL11.glDeleteLists(lists[s], 4); lists[s] = 0; }
        completed = 0; emittedBits = 0; initialized = false;
    }
    boolean ready() { return initialized; }
    int completedSections() { return completed; }
    void markEmpty() { initialized = true; }
    long sectionBits() { return emittedBits; }

    static void borderDepth(boolean overlap) {
        if (overlap) { GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL); GL11.glPolygonOffset(1.0F, 4.0F); }
        else GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    void replaceSection(int section, int replacement, long bits) {
        int previous = lists[section];
        lists[section] = replacement;
        emittedBits = (emittedBits & ~(15L << (section * 4))) | (bits & (15L << (section * 4)));
        completed |= 1 << section; initialized = true;
        if (previous != 0) GL11.glDeleteLists(previous, 4);
    }

    void buildSection(VisualChunk chunk, RetainedAccess access, int section) {
        if (section < (VisualChunk.MIN_Y >> 4) || section > 15) throw new IllegalArgumentException("Section outside retained terrain");
        if (!chunk.hasSection(section)) { replaceSection(section, 0, 0); return; }
        if (buildBuffer == null) buildBuffer = new WorldRenderer(524288);
        WorldRenderer buffer = buildBuffer;
        WorldVertexBufferUploader uploader = new WorldVertexBufferUploader();
        int replacement = GL11.glGenLists(4);
        if (replacement == 0) throw new IllegalStateException("Could not allocate terrain section display lists");
        long bits = 0;
        boolean drawing = false;
        int x0 = chunk.xPosition << 4, z0 = chunk.zPosition << 4;
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            for (EnumWorldBlockLayer layer : EnumWorldBlockLayer.values()) {
                ForgeHooksClient.setRenderLayer(layer);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                drawing = true;
                buffer.setTranslation(-x0, 0, -z0);
                for (int y = Math.max(VisualChunk.MIN_Y, section * 16); y < (section + 1) * 16; y++)
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        BlockPos pos = new BlockPos(x0 + x, y, z0 + z);
                        IBlockState state = access.getBlockState(pos);
                        if (state.getBlock().getRenderType() != -1 && state.getBlock().canRenderInLayer(layer))
                            Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlock(state, pos, access, buffer);
                    }
                if (buffer.getVertexCount() != 0) bits |= 1L << (section * 4 + layer.ordinal());
                buffer.finishDrawing();
                drawing = false;
                GL11.glNewList(replacement + layer.ordinal(), GL11.GL_COMPILE);
                try { uploader.draw(buffer); } finally { GL11.glEndList(); }
            }
            // Commit only after every layer is ready. Unchanged sections keep their original GL IDs.
            originX = x0; originZ = z0;
            replaceSection(section, replacement, bits); replacement = 0;
        } finally {
            if (drawing) { buffer.finishDrawing(); buffer.reset(); }
            if (replacement != 0) GL11.glDeleteLists(replacement, 4);
            GL11.glPopClientAttrib(); ForgeHooksClient.setRenderLayer(null);
            buffer.setTranslation(0, 0, 0); GlStateManager.resetColor();
        }
    }

    void drawLayer(EnumWorldBlockLayer layer) {
        for (int section = 0; section < 16; section++)
            if (lists[section] != 0 && (visibleBits & (1L << (section * 4 + layer.ordinal()))) != 0)
                GL11.glCallList(lists[section] + layer.ordinal());
    }

    long distance(int cameraX, int cameraZ) {
        return Visibility.distance(cameraX, cameraZ, originX >> 4, originZ >> 4);
    }

    static void drawAll(List<DebugMesh> meshes, Entity camera, float partialTicks, int visualRadius) {
        if (meshes.isEmpty()) return;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        // Raw GL changes are restored with attrib/matrix stacks; do not update GlStateManager's cache here.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        // Clouds can restore vanilla's short projection late in the frame. Reapply the same terrain
        // projection used by the pre-terrain hook, so cached and live depth values agree.
        SharedView.extendProjection(visualRadius);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            // Vanilla has already bound and configured this frame's lightmap during terrain rendering.
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getMinecraft().getTextureManager()
                    .getTexture(TextureMap.locationBlocksTexture).getGlTextureId());
            GL11.glDisable(GL11.GL_LIGHTING);
            // Vanilla setupFog left the correct color/mode/range configured, including special fog.
            GL11.glEnable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glDisable(GL11.GL_BLEND);
            for (EnumWorldBlockLayer layer : EnumWorldBlockLayer.values()) {
                if (layer == EnumWorldBlockLayer.TRANSLUCENT) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL11.glDepthMask(false);
                }
                // Nearest-first input; reverse for approximate column-level translucent sorting.
                for (int i = 0; i < meshes.size(); i++) {
                    DebugMesh mesh = meshes.get(layer == EnumWorldBlockLayer.TRANSLUCENT
                            ? meshes.size() - 1 - i : i);
                    // Positive offset puts overlapping fallback faces behind native opaque terrain.
                    // Native translucent geometry does not reliably write depth, so blending can overlap.
                    borderDepth(mesh.borderOverlap);
                    GL11.glPushMatrix();
                    GL11.glTranslated(mesh.originX - x, -y, mesh.originZ - z);
                    mesh.drawLayer(layer);
                    GL11.glPopMatrix();
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopAttrib();
            OpenGlHelper.setActiveTexture(activeTexture);
            GlStateManager.resetColor();
        }
    }
}
