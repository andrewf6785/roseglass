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

/** DEBUG ONLY: vanilla block tessellation, separate display lists and final world pass. */
final class DebugMesh {
    boolean borderOverlap;
    private int lists;
    private int originX, originZ;
    private static WorldRenderer buildBuffer;

    void delete() {
        if (lists != 0) GL11.glDeleteLists(lists, 4);
        lists = 0;
    }

    boolean ready() { return lists != 0; }

    static void borderDepth(boolean overlap) {
        if (overlap) {
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(1.0F, 4.0F);
        } else GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    void build(VisualChunk chunk, RetainedAccess access) {
        delete();
        originX = chunk.xPosition << 4;
        originZ = chunk.zPosition << 4;
        if (buildBuffer == null) buildBuffer = new WorldRenderer(524288);
        WorldRenderer buffer = buildBuffer;
        WorldVertexBufferUploader uploader = new WorldVertexBufferUploader();
        lists = GL11.glGenLists(4);
        if (lists == 0) throw new IllegalStateException("Could not allocate debug terrain display lists");
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            for (EnumWorldBlockLayer layer : EnumWorldBlockLayer.values()) {
                ForgeHooksClient.setRenderLayer(layer);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                buffer.setTranslation(-originX, 0, -originZ);
                for (int y = VisualChunk.MIN_Y; y < 256; y++) {
                    if (!chunk.hasSection(y >> 4)) continue;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockPos pos = new BlockPos(originX + x, y, originZ + z);
                            IBlockState state = access.getBlockState(pos);
                            if (state.getBlock().getRenderType() != -1 && state.getBlock().canRenderInLayer(layer)) {
                                Minecraft.getMinecraft().getBlockRendererDispatcher()
                                        .renderBlock(state, pos, access, buffer);
                            }
                        }
                    }
                }
                buffer.finishDrawing();
                GL11.glNewList(lists + layer.ordinal(), GL11.GL_COMPILE);
                try { uploader.draw(buffer); } finally { GL11.glEndList(); }
            }
        } catch (RuntimeException | Error e) {
            delete();
            throw e;
        } finally {
            GL11.glPopClientAttrib();
            ForgeHooksClient.setRenderLayer(null);
            buffer.setTranslation(0, 0, 0);
            GlStateManager.resetColor();
        }
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
                    GL11.glCallList(mesh.lists + layer.ordinal());
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
