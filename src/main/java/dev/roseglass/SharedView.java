package dev.roseglass;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Render-only camera adjustment, shared by vanilla terrain and the cached terrain pass. */
final class SharedView {
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);

    static void extendProjection(int visualRadius) {
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        MATRIX.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MATRIX);
        // Only perspective matrices; retain the FOV, aspect, zoom and stereo offsets.
        if (MATRIX.get(11) == -1.0F && MATRIX.get(15) == 0.0F) {
            float near = MATRIX.get(14) / (MATRIX.get(10) - 1.0F);
            float far = Visibility.farPlane(visualRadius);
            MATRIX.put(10, -(far + near) / (far - near));
            MATRIX.put(14, -(2.0F * far * near) / (far - near));
            GL11.glLoadMatrix(MATRIX);
        }
        GL11.glMatrixMode(mode);
    }
}
