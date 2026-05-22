package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL40C.glBindBufferBase;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

/**
 * Renders unscanned entities as a glitchy spinning magenta cube so players
 * know they need to run a scan to include that entity type.
 *
 * Shares the main entity SSBO (binding 12) so instance positions are already
 * uploaded by EntityBatchRenderer before we draw.
 */
public class EntityErrorRenderer {

    private static final int MAX_ERROR_INSTANCES = 512;
    // SSBO stride must match EntityInstance exactly — we only write posX/Y/Z
    private static final int INSTANCE_STRIDE = EntityInstance.STRIDE;

    // 8 vertices × 3 floats. Indexed as 6 faces × 2 triangles.
    private static final float[] CUBE_VERTS = {
        -0.5f,-0.5f,-0.5f,  0.5f,-0.5f,-0.5f,  0.5f, 0.5f,-0.5f, -0.5f, 0.5f,-0.5f, // front
        -0.5f,-0.5f, 0.5f,  0.5f,-0.5f, 0.5f,  0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, // back
    };
    private static final int[] CUBE_INDICES = {
        0,1,2, 2,3,0,  // front
        4,5,6, 6,7,4,  // back
        0,4,7, 7,3,0,  // left
        1,5,6, 6,2,1,  // right
        3,7,6, 6,2,3,  // top
        0,4,5, 5,1,0   // bottom
    };

    private int vaoId, vboId, eboId;
    private int errorShaderProgram = 0;
    private int uViewProjection, uGameTime, uBaseInstance;

    // Per-frame error instance SSBO (separate from the main one)
    private int errorSsboId;
    private long errorSsboAddr;

    // Queue for error instances this frame — filled by EntityBatchRenderer
    final int[] errorInstanceBuffer = new int[MAX_ERROR_INSTANCES]; // stores index into main SSBO
    final AtomicInteger errorCount = new AtomicInteger(0);

    public EntityErrorRenderer() {
        buildGeometry();
        buildSsbo();
        buildShader();
    }

    private void buildGeometry() {
        vaoId = glCreateVertexArrays();
        vboId = glCreateBuffers();
        eboId = glCreateBuffers();

        glNamedBufferData(vboId, toFloatBuffer(CUBE_VERTS), GL_STATIC_DRAW);
        glNamedBufferData(eboId, toIntBuffer(CUBE_INDICES),  GL_STATIC_DRAW);

        glVertexArrayVertexBuffer(vaoId, 0, vboId, 0, 3 * 4);
        glVertexArrayElementBuffer(vaoId, eboId);
        glEnableVertexArrayAttrib(vaoId, 0);
        glVertexArrayAttribFormat(vaoId, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vaoId, 0, 0);
    }

    private void buildSsbo() {
        // Persistent mapped SSBO for error instance data (just posX/Y/Z per entry)
        errorSsboId = glCreateBuffers();
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        long size  = (long) MAX_ERROR_INSTANCES * INSTANCE_STRIDE;
        glNamedBufferStorage(errorSsboId, size, flags);
        errorSsboAddr = nglMapNamedBufferRange(errorSsboId, 0, size, flags);
    }

    private void buildShader() {
        try {
            String vertSrc = loadShaderSource("entity/entity_error_vert.glsl");
            String fragSrc = loadShaderSource("entity/entity_error_frag.glsl");
            if (vertSrc == null || fragSrc == null) {
                Rentities.LOGGER.error("[ErrorRenderer] Could not load error shaders");
                return;
            }
            int vert = compileShader(GL_VERTEX_SHADER,   vertSrc);
            int frag = compileShader(GL_FRAGMENT_SHADER, fragSrc);
            errorShaderProgram = glCreateProgram();
            glAttachShader(errorShaderProgram, vert);
            glAttachShader(errorShaderProgram, frag);
            glLinkProgram(errorShaderProgram);
            glDeleteShader(vert);
            glDeleteShader(frag);

            if (glGetProgrami(errorShaderProgram, GL_LINK_STATUS) == GL_FALSE) {
                Rentities.LOGGER.error("[ErrorRenderer] Shader link failed: {}",
                    glGetProgramInfoLog(errorShaderProgram));
                glDeleteProgram(errorShaderProgram);
                errorShaderProgram = 0;
                return;
            }

            uViewProjection = glGetUniformLocation(errorShaderProgram, "uViewProjection");
            uGameTime       = glGetUniformLocation(errorShaderProgram, "uGameTime");
            uBaseInstance   = glGetUniformLocation(errorShaderProgram, "uBaseInstance");
        } catch (Exception e) {
            Rentities.LOGGER.error("[ErrorRenderer] Shader build exception: {}", e.getMessage());
        }
    }

    /**
     * Queues an error instance at the given world position.
     * Called from EntityBatchRenderer when a type has no mesh.
     */
    public void queueError(double x, double y, double z) {
        int idx = errorCount.getAndIncrement();
        if (idx >= MAX_ERROR_INSTANCES) return;

        long ptr = errorSsboAddr + (long) idx * INSTANCE_STRIDE;
        // Write posX, posY, posZ — rest of the EntityInstance struct is zeroed (already mapped)
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_X, (float) x);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_Y, (float) y);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_Z, (float) z);
    }

    /**
     * Draws all queued error instances. Call after EntityBatchRenderer.doFlush().
     */
    public void flush(float[] vpMatrix, float gameTime) {
        int count = errorCount.getAndSet(0);
        if (count == 0 || errorShaderProgram == 0) return;

        // Flush mapped range
        glFlushMappedNamedBufferRange(errorSsboId, 0, (long) count * INSTANCE_STRIDE);
        glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);

        // Save GL state
        int prevVAO  = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevProg = glGetInteger(GL_CURRENT_PROGRAM);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend     = glIsEnabled(GL_BLEND);

        // Draw setup
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);

        glUseProgram(errorShaderProgram);
        glUniformMatrix4fv(uViewProjection, false, vpMatrix);
        glUniform1f(uGameTime, gameTime);
        glUniform1i(uBaseInstance, 0);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 12, errorSsboId);
        glBindVertexArray(vaoId);
        glDrawElementsInstanced(GL_TRIANGLES, CUBE_INDICES.length, GL_UNSIGNED_INT, 0, count);

        // Restore
        glBindVertexArray(prevVAO);
        glUseProgram(prevProg);
        if (!depthTest) glDisable(GL_DEPTH_TEST);
        if (!blend)     glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    public void delete() {
        glDeleteVertexArrays(vaoId);
        glDeleteBuffers(vboId);
        glDeleteBuffers(eboId);
        glUnmapNamedBuffer(errorSsboId);
        glDeleteBuffers(errorSsboId);
        if (errorShaderProgram != 0) glDeleteProgram(errorShaderProgram);
    }

    private static String loadShaderSource(String path) {
        try (var stream = EntityErrorRenderer.class.getClassLoader()
                .getResourceAsStream("assets/rentities/shaders/" + path)) {
            if (stream == null) return null;
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    private static int compileShader(int type, String src) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE)
            Rentities.LOGGER.error("[ErrorRenderer] Shader compile error: {}", glGetShaderInfoLog(id));
        return id;
    }

    private static java.nio.FloatBuffer toFloatBuffer(float[] data) {
        java.nio.FloatBuffer b = MemoryUtil.memAllocFloat(data.length);
        b.put(data).flip();
        return b;
    }

    private static java.nio.IntBuffer toIntBuffer(int[] data) {
        java.nio.IntBuffer b = MemoryUtil.memAllocInt(data.length);
        b.put(data).flip();
        return b;
    }
}
