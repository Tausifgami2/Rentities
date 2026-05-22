package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.gl.GlShader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30C.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL40C.glBindBufferBase;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL44C.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL44C.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glFlushMappedNamedBufferRange;
import static org.lwjgl.opengl.GL45C.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45C.glUnmapNamedBuffer;
import static org.lwjgl.opengl.GL45C.nglMapNamedBufferRange;
import static org.lwjgl.opengl.GL31C.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42C.glDrawElementsInstancedBaseInstance;

public class EntityBatchRenderer {

    public static EntityBatchRenderer INSTANCE;

    private static final int MAX_QUEUE = EntityInstance.MAX_INSTANCES;
    private static final EntityType<?>[] queuedTypes = new EntityType[MAX_QUEUE];
    private static final EntityType<?>[] extractionTypes = new EntityType[MAX_QUEUE];
    private static final int[] queuedOriginalIndices = new int[MAX_QUEUE];
    private static final AtomicInteger queueSize = new AtomicInteger(0);
    private static long extractionBuffer;

    // Stored VP matrix from terrain render pass
    public static org.joml.Matrix4f storedViewProjection;

    private static final int SSBO_BINDING       = 12;
    private static final int PIVOT_SSBO_BINDING = 13;
    private int pivotSSBOId = 0;
    private boolean textureCacheLoaded = false;
    private static final int NUM_BUFFERS = 3;
    // Pre-allocated to avoid per-frame heap allocation
    private final float[] vpFloats = new float[16]; // Triple buffering to prevent stalls
    private final int[] ssboIds = new int[NUM_BUFFERS];
    private final long[] ssboAddrs = new long[NUM_BUFFERS];
    private int currentBufferIdx = 0;
    
    private final long[] fences = new long[NUM_BUFFERS];
    private static final long FENCE_TIMEOUT_NS = 50_000_000L; // 50ms max wait

    private GlShader entityShader;
    private int uViewProjection = -1;
    private int uGameTime = -1;
    private int uEntityTextures = -1; // sampler2D uEntityTexture — bound per draw call
    private int uBaseInstance  = -1;

    private final EntityMeshBaker meshBaker;
    private final EntityErrorRenderer errorRenderer;
    private final EntityTextureAtlas textureAtlas;
    private final EntitySkinCache skinCache;

    private static final Map<Class<?>, Map<String, Field>> fieldCache = new ConcurrentHashMap<>();

    public EntityBatchRenderer() {
        INSTANCE = this;
        this.meshBaker = new EntityMeshBaker();
        this.errorRenderer = new EntityErrorRenderer();
        this.textureAtlas = new EntityTextureAtlas();
        this.skinCache = new EntitySkinCache();

        // Allocate SSBOs (persistently mapped, triple buffered)
        for (int i = 0; i < NUM_BUFFERS; i++) {
            ssboIds[i] = glCreateBuffers();
            // No GL_CLIENT_STORAGE_BIT — that pins RAM and causes system memory pressure
            glNamedBufferStorage(ssboIds[i], EntityInstance.SSBO_SIZE,
                    GL_MAP_PERSISTENT_BIT | GL_MAP_WRITE_BIT);
            ssboAddrs[i] = nglMapNamedBufferRange(ssboIds[i], 0, EntityInstance.SSBO_SIZE,
                    GL_MAP_PERSISTENT_BIT  // 0x0040
                    | 0x0020              // GL_MAP_UNSYNCHRONIZED_BIT
                    | 0x0010              // GL_MAP_FLUSH_EXPLICIT_BIT
                    | GL_MAP_WRITE_BIT);  // 0x0002
        }

        extractionBuffer = MemoryUtil.nmemAlloc(EntityInstance.SSBO_SIZE);
        compileShader();
    }

    public static void queueEntityStateDirect(Object state, double x, double y, double z,
                                              EntityType<?> type) {
        if (INSTANCE == null) return;
        int idx = queueSize.getAndIncrement();
        if (idx < MAX_QUEUE) {
            queuedTypes[idx] = type;
            extractionTypes[idx] = type;
            queuedOriginalIndices[idx] = idx;
            INSTANCE.writeEntityInstance(
                extractionBuffer + (long)idx * EntityInstance.STRIDE, state, x, y, z);
        }
    }

    public static void queueEntityState(Object state, double x, double y, double z) {
        if (INSTANCE == null) return;
        int idx = queueSize.getAndIncrement();
        if (idx < MAX_QUEUE) {
            EntityType<?> type = getEntityType(state);
            queuedTypes[idx] = type;
            extractionTypes[idx] = type;
            queuedOriginalIndices[idx] = idx;
            INSTANCE.writeEntityInstance(extractionBuffer + (long)idx * EntityInstance.STRIDE, state, x, y, z);
        }
    }

    public static void flushBatch() {
        if (INSTANCE == null) return;
        // Always trigger bake unconditionally — bake() is idempotent (noop after first run).
        // Must happen here BEFORE doFlush() so it runs even when no entities are queued.
        // This breaks the chicken-and-egg: bake needs to run to populate meshInfoMap,
        // but meshInfoMap must exist before entities can be queued.
        if (!INSTANCE.meshBaker.isBaked()) INSTANCE.meshBaker.bake();
        // Upload pivot SSBO once immediately after bake completes
        if (INSTANCE.pivotSSBOId == 0 && INSTANCE.meshBaker.isBaked())
            INSTANCE.pivotSSBOId = INSTANCE.meshBaker.uploadPivotSSBO();
        INSTANCE.doFlush();
    }

    private void doFlush() {
        int count = Math.min(queueSize.getAndSet(0), MAX_QUEUE);
        if (count == 0) return;

        // Sort by entity type for contiguous SSBO blocks
        sortByEntityType(count);

        // Advance ring buffer index
        currentBufferIdx = (currentBufferIdx + 1) % NUM_BUFFERS;
        int bufIdx = currentBufferIdx;

        // Try all 3 slots — find one the GPU has already finished with.
        // Only hard-stall on the oldest slot as absolute last resort.
        // This prevents the render thread from blocking on GPU work.
        if (fences[bufIdx] != 0) {
            int result = org.lwjgl.opengl.GL32C.glClientWaitSync(
                fences[bufIdx], 0, 0L); // timeout=0 → non-blocking
            if (result == org.lwjgl.opengl.GL32C.GL_TIMEOUT_EXPIRED
                    || result == org.lwjgl.opengl.GL32C.GL_WAIT_FAILED) {
                // Slot still in use — try next slot
                int fallback = (bufIdx + 1) % NUM_BUFFERS;
                if (fences[fallback] != 0) {
                    result = org.lwjgl.opengl.GL32C.glClientWaitSync(
                        fences[fallback], 0, 0L);
                    if (result == org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED
                            || result == org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED) {
                        org.lwjgl.opengl.GL32C.glDeleteSync(fences[fallback]);
                        fences[fallback] = 0;
                        bufIdx = fallback;
                        currentBufferIdx = bufIdx;
                    } else {
                        // Both slots busy — hard wait on oldest slot, max 2ms not 50ms
                        // 2ms is one frame at 500fps; if we're here the GPU is genuinely behind
                        result = org.lwjgl.opengl.GL32C.glClientWaitSync(
                            fences[bufIdx],
                            org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT,
                            2_000_000L); // 2ms max
                        org.lwjgl.opengl.GL32C.glDeleteSync(fences[bufIdx]);
                        fences[bufIdx] = 0;
                        if (result == org.lwjgl.opengl.GL32C.GL_TIMEOUT_EXPIRED
                                && Rentities.IS_DEBUG) {
                            Rentities.LOGGER.warn("[Entity] GPU fence timeout — all 3 SSBO slots busy");
                        }
                    }
                } else {
                    bufIdx = fallback;
                    currentBufferIdx = bufIdx;
                }
            } else {
                // Already done — free it
                org.lwjgl.opengl.GL32C.glDeleteSync(fences[bufIdx]);
                fences[bufIdx] = 0;
            }
        }
        textureAtlas.processUploads();
        skinCache.processUploads();

        if (meshBaker.pendingTextureSave) {
            meshBaker.pendingTextureSave = false;
            textureAtlas.saveTextureCache();
        }

        if (!textureCacheLoaded) {
            textureCacheLoaded = true;
            if (!Rentities.config.entity_scan_mode) {
                textureAtlas.loadTextureCache();
            }
        }

        if (storedViewProjection == null || entityShader == null) return;

        // Copy extracted data to staging buffer in sorted order, write directly to mapped SSBO
        for (int i = 0; i < count; i++) {
            int originalIdx = queuedOriginalIndices[i];
            MemoryUtil.memCopy(extractionBuffer + (long)originalIdx * EntityInstance.STRIDE,
                               ssboAddrs[bufIdx] + (long)i * EntityInstance.STRIDE,
                               EntityInstance.STRIDE);
        }

        // Flush only the range we actually wrote.
        // glFlushMappedNamedBufferRange is sufficient for GL_MAP_COHERENT_BIT buffers —
        // no glMemoryBarrier needed, which would stall the entire pipeline.
        glFlushMappedNamedBufferRange(ssboIds[bufIdx], 0, (long) count * EntityInstance.STRIDE);

        // Save only what we change — 4 queries instead of 10
        int prevVAO         = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevProgram     = glGetInteger(GL_CURRENT_PROGRAM);
        int prevDepthFunc   = glGetInteger(GL_DEPTH_FUNC);
        boolean prevCull    = glIsEnabled(GL_CULL_FACE);
        int prevActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int prevTex2DBinding  = glGetInteger(GL_TEXTURE_BINDING_2D);

        // Bind shader and upload uniforms
        entityShader.bind();
        float[] vp = vpFloats;
        if (storedViewProjection != null) storedViewProjection.get(vp);
        glUniformMatrix4fv(uViewProjection, false, vp);
        // uGameTime = ticks + partialTick — gives smooth 60fps+ animation in the shader
        Minecraft mc = Minecraft.getInstance();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float gameTime = mc.level != null
                ? (float)(mc.level.getGameTime() % 100000L) + partialTick
                : partialTick;
        glUniform1f(uGameTime, gameTime);

        // Bind SSBOs — texture binding happens per draw call in renderBySortedEntityType
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, SSBO_BINDING, ssboIds[bufIdx]);
        if (pivotSSBOId != 0)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, PIVOT_SSBO_BINDING, pivotSSBOId);

        if (Rentities.config.entity_batching_debug && count > 0 && (System.currentTimeMillis() % 2000 < 50)) {
             float px = MemoryUtil.memGetFloat(ssboAddrs[bufIdx] + EntityInstance.OFFSET_POSITION_X);
             float py = MemoryUtil.memGetFloat(ssboAddrs[bufIdx] + EntityInstance.OFFSET_POSITION_Y);
             float pz = MemoryUtil.memGetFloat(ssboAddrs[bufIdx] + EntityInstance.OFFSET_POSITION_Z);
             
             // Calculate expected W for the first entity using standard matrix multiplication
             float w = vp[3]*px + vp[7]*py + vp[11]*pz + vp[15];
             // Standard NDC Z calculation
             float z = vp[2]*px + vp[6]*py + vp[10]*pz + vp[14];
             
             Rentities.LOGGER.info("FLUSH: count={}, pos=({},{},{}), w_calc={}, z_calc={}, matrix=[{},{},{},{}; {},{},{},{}; {},{},{},{}; {},{},{},{}], VAO={}", 
                 count, px, py, pz, w, z,
                 vp[0], vp[1], vp[2], vp[3],
                 vp[4], vp[5], vp[6], vp[7],
                 vp[8], vp[9], vp[10], vp[11],
                 vp[12], vp[13], vp[14], vp[15],
                 meshBaker.getVaoId());
        }

        // Bind VAO and draw
        int vaoId = meshBaker.getVaoId();
        if (vaoId != 0) {
            glBindVertexArray(vaoId);
            
            // Ensure correct GL state for entity rendering
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL); // Standard Z (Sodium does NOT use reverse-Z)
            glDepthMask(true);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            
            // Apply debug solid override
            if (Rentities.config.entity_batching_debug_solid) {
                glDisable(GL_BLEND);
            }

            glColorMask(true, true, true, true);

            renderBySortedEntityType(count);

            // Insert fence AFTER draw — signals when GPU finishes reading this buffer slot
            fences[bufIdx] = org.lwjgl.opengl.GL32C.glFenceSync(
                org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

            int err = glGetError();
            if (err != 0) {
                Rentities.LOGGER.error("GL ERROR during entity draw: 0x{}", Integer.toHexString(err));
            }
        } else {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("FLUSH: meshBaker VAO is 0, skipping draw");
        }

        glBindVertexArray(prevVAO);
        glUseProgram(prevProgram);
        glDepthFunc(prevDepthFunc);
        glDepthMask(true);
        if (prevCull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glColorMask(true, true, true, true);
        glActiveTexture(prevActiveTexture);
        glBindTexture(GL_TEXTURE_2D, prevTex2DBinding);

        // Flush glitch cubes — reuse already-computed vp float array
        float[] vpArr = vp; // vp was already filled above — reuse, no new allocation
        errorRenderer.flush(vpArr, gameTime);

        // Clear queued references to release entity render states
        for (int i = 0; i < count; i++) {
            queuedTypes[i] = null;
            extractionTypes[i] = null;
        }
    }

    public static void queueErrorEntity(double x, double y, double z) {
        if (INSTANCE != null) INSTANCE.errorRenderer.queueError(x, y, z);
    }

    // Pre-allocated sort key buffer — avoids heap allocation every frame (no GC pressure)
    private final long[] sortKeys = new long[MAX_QUEUE];

    private void sortByEntityType(int count) {
        for (int i = 0; i < count; i++) {
            int typeId = System.identityHashCode(queuedTypes[i]);
            sortKeys[i] = ((long) typeId << 32) | (queuedOriginalIndices[i] & 0xFFFFFFFFL);
        }
        java.util.Arrays.sort(sortKeys, 0, count);
        for (int i = 0; i < count; i++) {
            int originalIdx = (int) (sortKeys[i] & 0xFFFFFFFFL);
            queuedOriginalIndices[i] = originalIdx;
        }
        for (int i = 0; i < count; i++) {
            queuedTypes[i] = getEntityTypeFromIdx(queuedOriginalIndices[i]);
        }
    }

    private EntityType<?> getEntityTypeFromIdx(int idx) {
        return extractionTypes[idx];
    }

    private void renderBySortedEntityType(int count) {
        var meshInfoMap = meshBaker.getMeshInfoMap();
        int instanceOffset = 0;
        EntityType<?> currentType = null;
        int currentCount = 0;

        for (int i = 0; i <= count; i++) {
            EntityType<?> type = (i < count) ? queuedTypes[i] : null;
            if (type != currentType) {
                if (currentType != null && currentCount > 0) {
                    var meshInfo = meshInfoMap.get(currentType);
                    if (meshInfo != null) {
                        // Bind Minecraft's own texture for this entity type directly
                        bindEntityTexture(currentType);
                        glUniform1i(uBaseInstance, instanceOffset);
                        if (Rentities.IS_DEBUG && (System.currentTimeMillis() % 2000 < 50)) {
                            Rentities.LOGGER.info("DRAW: type={}, count={}, indexCount={}, indexOffset={}, baseInstance={}",
                                currentType, currentCount, meshInfo.indexCount, meshInfo.indexOffset, instanceOffset);
                        }
                        // glDrawElementsInstanced is in GL31C
                        glDrawElementsInstanced(
                                GL_TRIANGLES, meshInfo.indexCount, GL_UNSIGNED_INT,
                                (long)meshInfo.indexOffset, currentCount);
                    } else {
                        if (Rentities.IS_DEBUG) {
                            Rentities.LOGGER.warn("SKIP_NO_MESH: type={}, count={} — no meshInfo found!", currentType, currentCount);
                        }
                    }
                    instanceOffset += currentCount;
                }
                currentType = type;
                currentCount = 1;
            } else {
                currentCount++;
            }
        }
    }

    // Camera position for world-to-relative transformation
    public static double cameraX, cameraY, cameraZ;

    public void setCamera(double x, double y, double z) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
    }

    private static final org.joml.Matrix4f lastViewMatrix = new org.joml.Matrix4f();

    public static void updateProjectionMatrix(org.joml.Matrix4f projection) {
        storedViewProjection = new org.joml.Matrix4f(projection).mul(lastViewMatrix);
    }

    public static void setViewMatrix(org.joml.Matrix4f view) {
        lastViewMatrix.set(view);
        // Zero out translation component (m30, m31, m32) because the shader
        // already uses camera-relative coordinates (inst.posX, etc.)
        lastViewMatrix.m30(0.0f);
        lastViewMatrix.m31(0.0f);
        lastViewMatrix.m32(0.0f);
    }

    // MethodHandles are ~10-100x faster than Field.get() after JIT warmup
    private static class StateAccessor {
        // Typed as exact primitive return to allow invokeExact — zero boxing, zero GC
        final java.lang.invoke.MethodHandle yaw, limbSwing, limbSwingAmt, headYaw, headPitch, attackProgress;
        final java.lang.invoke.MethodHandle deathTime, swimProgress, hurtTime, sneaking;
        final java.lang.invoke.MethodHandle type, texture, invisible, onGround, inWater;

        StateAccessor(Class<?> cls) {
            this.yaw            = mhFloat(cls,   "field_53329", "yBodyRot", "M");
            this.limbSwing      = mhFloat(cls,   "field_53446", "walkAnimationPos", "ab");
            this.limbSwingAmt   = mhFloat(cls,   "field_53447", "walkAnimationSpeed", "ac");
            this.headYaw        = mhFloat(cls,   "field_53448", "headYaw", "ad");
            this.headPitch      = mhFloat(cls,   "field_53449", "headPitch", "ae");
            this.attackProgress = mhFloat(cls,   "field_53450", "attackAnim", "af");
            this.deathTime      = mhFloat(cls,   "field_53452", "deathTime", "ah");
            this.swimProgress   = mhFloat(cls,   "field_53451", "swimAmount", "ag");
            this.hurtTime       = mhBool(cls,    "field_53456", "isHurt", "al");
            this.sneaking       = mhBool(cls,    "field_53455", "isSneaking", "ak");
            this.type           = mhObj(cls,     "field_58171", "entityType", "H");
            this.texture        = mhObj(cls,     "field_53336", "texture", "V");
            this.invisible      = mhBool(cls,    "field_53333", "invisible", "Q");
            this.onGround       = mhBool(cls,    "field_53334", "onGround", "R");
            this.inWater        = mhBool(cls,    "field_53335", "inWater", "S");
        }

        // Returns (Object)->float — invokeExact returns float directly, no boxing
        private static java.lang.invoke.MethodHandle mhFloat(Class<?> cls, String... names) {
            Field f = findField(cls, float.class, names);
            if (f == null) return null;
            try {
                return java.lang.invoke.MethodHandles.lookup().unreflectGetter(f)
                    .asType(java.lang.invoke.MethodType.methodType(float.class, Object.class));
            } catch (Exception e) { return null; }
        }

        // Returns (Object)->boolean — no boxing
        private static java.lang.invoke.MethodHandle mhBool(Class<?> cls, String... names) {
            Field f = findField(cls, boolean.class, names);
            if (f == null) return null;
            try {
                return java.lang.invoke.MethodHandles.lookup().unreflectGetter(f)
                    .asType(java.lang.invoke.MethodType.methodType(boolean.class, Object.class));
            } catch (Exception e) { return null; }
        }

        // Returns (Object)->Object — for reference types, no boxing needed
        private static java.lang.invoke.MethodHandle mhObj(Class<?> cls, String... names) {
            Field f = findField(cls, null, names); // null = any reference type
            if (f == null) return null;
            try {
                return java.lang.invoke.MethodHandles.lookup().unreflectGetter(f)
                    .asType(java.lang.invoke.MethodType.methodType(Object.class, Object.class));
            } catch (Exception e) { return null; }
        }

        private static Field findField(Class<?> cls, Class<?> type, String... names) {
            for (String name : names) {
                Class<?> c = cls;
                while (c != null) {
                    try {
                        Field f = c.getDeclaredField(name);
                        if (type == null || f.getType() == type) {
                            f.setAccessible(true);
                            return f;
                        }
                    } catch (NoSuchFieldException ignored) {}
                    c = c.getSuperclass();
                }
            }
            return null;
        }
    }

    private static final ClassValue<StateAccessor> ACCESSOR_CACHE = new ClassValue<>() {
        @Override protected StateAccessor computeValue(Class<?> type) { return new StateAccessor(type); }
    };

    // Entity types that need texture capture on the next flush
    // Cache entity type -> AbstractTexture object (method_4619 result)
    public final java.util.Map<net.minecraft.world.entity.EntityType<?>, Object> entityTextures
        = new java.util.concurrent.ConcurrentHashMap<>();
    public final java.util.Map<net.minecraft.world.entity.EntityType<?>, Integer> entityGlTexIds
        = new java.util.concurrent.ConcurrentHashMap<>();
    // Types that permanently failed texture resolution — never retry to avoid per-frame reflection
    public final java.util.Set<net.minecraft.world.entity.EntityType<?>> entityTexFailed
        = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private void writeEntityInstance(long ptr, Object state, double rx, double ry, double rz) {
        if (state == null) return;
        StateAccessor acc = ACCESSOR_CACHE.get(state.getClass());
        try {
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_X, (float) rx);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_Y, (float) ry);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_Z, (float) rz);

            float yawDeg = acc.yaw != null ? (float) acc.yaw.invoke(state) : 0f;
            float rotY = (float)(Math.toRadians(yawDeg) - Math.PI);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_ROTATION_Y, rotY);

            // ArmorStandRenderState doesn't have walkAnimationPos/Speed fields.
            // Running the BIPED animation with zero inputs still produces idle sway
            // from uGameTime terms in the shader, causing jitter. Force everything to 0.
            EntityType<?> type = acc.type != null ? (EntityType<?>) acc.type.invoke(state) : null;
            boolean isArmorStand = (type == EntityType.ARMOR_STAND);

            float limbSwing    = 0f;
            float limbSwingAmt = 0f;
            float headYawRel   = 0f;
            float headPitchRel = 0f;
            float attackProgress = 0f;
            float swimProgress   = 0f;
            float sneakProg      = 0f;
            float deathTime      = 0f;
            float hurtTime       = 0f;

            if (!isArmorStand) {
                if (acc.limbSwing != null) limbSwing = (float) acc.limbSwing.invoke(state);
                if (acc.limbSwingAmt != null) {
                    float raw = (float) acc.limbSwingAmt.invoke(state);
                    limbSwingAmt = raw < 0f ? 0f : raw > 1f ? 1f : raw;
                }
                // headYaw in render state is absolute world-space degrees.
                // We need head turn RELATIVE to body so shader doesn't double-rotate.
                if (acc.headYaw != null) {
                    float absHeadYaw = (float) acc.headYaw.invoke(state);
                    float relDeg = absHeadYaw - yawDeg;
                    // Wrap to [-180, 180] so head doesn't snap 360° when crossing yaw boundary
                    while (relDeg >  180f) relDeg -= 360f;
                    while (relDeg < -180f) relDeg += 360f;
                    headYawRel = (float) Math.toRadians(relDeg);
                }
                if (acc.headPitch != null)
                    headPitchRel = (float) Math.toRadians((float) acc.headPitch.invoke(state));
                if (acc.attackProgress != null) attackProgress = (float) acc.attackProgress.invoke(state);
                if (acc.swimProgress   != null) swimProgress   = (float) acc.swimProgress.invoke(state);
                if (acc.sneaking       != null && (boolean) acc.sneaking.invoke(state)) sneakProg = 1f;
                if (acc.hurtTime       != null && (boolean) acc.hurtTime.invoke(state))  hurtTime  = 10f;
                if (acc.deathTime      != null) {
                    float raw = (float) acc.deathTime.invoke(state);
                    if (raw >= 0f && raw <= 20f) deathTime = raw;
                }
            }

            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_LIMB_SWING,      limbSwing);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_LIMB_SWING_AMT,  limbSwingAmt);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_HEAD_YAW,        headYawRel);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_HEAD_PITCH,      headPitchRel);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_ATTACK_PROGRESS, attackProgress);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_BOW_PULL,        0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_HURT_TIME,       hurtTime);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_DEATH_TIME,      deathTime);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SNEAK_PROGRESS,  sneakProg);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SWIM_PROGRESS,   swimProgress);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_RIPTIDE,         0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SIT_PROGRESS,    0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_EAT_PROGRESS,    0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SWELL_AMOUNT,    0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_EXPLODE_PROGRESS,0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_ROLL_PROGRESS,   0f);

            int flags = 0;
            if (acc.invisible != null && (boolean) acc.invisible.invoke(state)) flags |= EntityInstance.FLAG_IS_INVISIBLE;
            if (acc.onGround  != null && (boolean) acc.onGround.invoke(state))  flags |= EntityInstance.FLAG_ON_GROUND;
            if (acc.inWater   != null && (boolean) acc.inWater.invoke(state))   flags |= EntityInstance.FLAG_IS_IN_WATER;
            if (type == EntityType.PLAYER) flags |= EntityInstance.FLAG_IS_PLAYER;
            if (type != null && EntityBatchRegistry.hasZombieArms(type)) flags |= EntityInstance.FLAG_ZOMBIE_ARMS;
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_FLAGS, flags);

            int typeIdx  = type != null ? EntityBatchRegistry.getEntityTypeIndex(type) : 0;
            EntityAnimationCategory cat = type != null ? EntityBatchRegistry.getCategory(type) : EntityAnimationCategory.CPU_ANIMATED;
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ENTITY_TYPE,   typeIdx);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ANIM_CATEGORY, cat.glslId);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_TEXTURE_LAYER, 0);

            // Texture is resolved via MixinEntityRenderDispatcher.tryResolveTexture

            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_HELD_MAIN,       EntityInstance.NO_ITEM);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_HELD_OFFHAND,    EntityInstance.NO_ITEM);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_HEAD,      EntityInstance.NO_ARMOR);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_CHEST,     EntityInstance.NO_ARMOR);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_LEGS,      EntityInstance.NO_ARMOR);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_FEET,      EntityInstance.NO_ARMOR);
            MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_MOUNT_ID,        EntityInstance.NO_MOUNT);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SEAT_OFFSET_X, 0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SEAT_OFFSET_Y, 0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SEAT_OFFSET_Z, 0f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_TEX_SCALE_X,   1f);
            MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_TEX_SCALE_Y,   1f);

        } catch (Throwable e) {
            MemoryUtil.memSet(ptr, 0, EntityInstance.STRIDE);
        }
    }

    // In 1.21.11, method_4615 on TextureManager (ilr) = bindTexture(ResourceLocation) -> void
    // This routes through the new GpuTexture pipeline correctly.
    public final java.util.Map<EntityType<?>, Object> entityTextureLocs = new java.util.concurrent.ConcurrentHashMap<>();

    // Cached TextureManager method for binding textures - resolved once at first use
    private java.lang.reflect.Method cachedBindTexMethod = null;
    private java.lang.reflect.Method cachedGetTexMethod = null;
    private Object cachedTextureManager = null;

    private void ensureTexMethodsCached() {
        if (cachedTextureManager != null) return;
        try {
            var mc = Minecraft.getInstance();
            if (mc == null) return;
            var tm = mc.getTextureManager();
            if (tm == null) return;
            cachedTextureManager = tm;
            // We'll resolve methods lazily when we have a loc object to match against
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("ensureTexMethodsCached failed: {}", e.getMessage());
        }
    }

    private void resolveTexMethods(Object loc) {
        if (cachedBindTexMethod != null && cachedGetTexMethod != null) return;
        if (cachedTextureManager == null) return;
        try {
            // From 1.21.11 tiny mappings on ilr (TextureManager):
            // method_4615: (Lamo;)V = bindTexture(ResourceLocation) -> void
            // method_4619: (Lamo;)Likz; = getTexture(ResourceLocation) -> AbstractTexture
            String[] bindNames = {"method_4615", "bindTexture"};
            String[] getNames  = {"method_4619", "getTexture", "method_4620"};
            for (java.lang.reflect.Method m : cachedTextureManager.getClass().getMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(loc.getClass())) continue;
                String name = m.getName();
                for (String n : bindNames) if (n.equals(name)) { cachedBindTexMethod = m; break; }
                for (String n : getNames)  if (n.equals(name)) { cachedGetTexMethod  = m; break; }
            }
            if (Rentities.IS_DEBUG)
                Rentities.LOGGER.info("Resolved tex methods: bind={} get={}",
                    cachedBindTexMethod != null ? cachedBindTexMethod.getName() : "null",
                    cachedGetTexMethod  != null ? cachedGetTexMethod.getName()  : "null");
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("resolveTexMethods failed: {}", e.getMessage());
        }
    }

    private void bindEntityTexture(EntityType<?> type) {
        Object loc = entityTextureLocs.get(type);
        if (loc == null) return;
        ensureTexMethodsCached();
        if (cachedTextureManager == null) return;
        resolveTexMethods(loc);
        try {
            // Step 1: bind via MC texture manager (registers texture if needed)
            if (cachedBindTexMethod != null)
                cachedBindTexMethod.invoke(cachedTextureManager, loc);

            // Step 2: get the texture object and extract GpuTexture GL handle
            if (cachedGetTexMethod != null) {
                Object texObj = cachedGetTexMethod.invoke(cachedTextureManager, loc);
                if (texObj != null) {
                    // Find GpuTexture via method returning GpuTexture
                    Object gpuTex = null;
                    for (java.lang.reflect.Method m : texObj.getClass().getMethods()) {
                        if (m.getParameterCount() == 0
                                && m.getReturnType().getSimpleName().equals("GpuTexture")) {
                            gpuTex = m.invoke(texObj);
                            break;
                        }
                    }
                    if (gpuTex != null) {
                        for (java.lang.reflect.Field f : gpuTex.getClass().getDeclaredFields()) {
                            if (f.getType() == int.class) {
                                f.setAccessible(true);
                                int glId = f.getInt(gpuTex);
                                if (Rentities.IS_DEBUG)
                                    Rentities.LOGGER.info("GpuTexture field {}={} for {}", f.getName(), glId, type);
                                if (glId > 0) {
                                    glActiveTexture(GL_TEXTURE0);
                                    glBindTexture(GL_TEXTURE_2D, glId);
                                    glUniform1i(uEntityTextures, 0);
                                    if (Rentities.IS_DEBUG)
                                        Rentities.LOGGER.info("Bound glId={} for {}", glId, type);
                                    return;
                                }
                            }
                        }
                        if (Rentities.IS_DEBUG)
                            Rentities.LOGGER.warn("GpuTexture has no valid int field for {}, fields: {}",
                                type, java.util.Arrays.toString(gpuTex.getClass().getDeclaredFields()));
                    } else {
                        if (Rentities.IS_DEBUG)
                            Rentities.LOGGER.warn("gpuTex is null for {}, texObj class: {}", type, texObj.getClass().getName());
                    }
                }
            }
            // Fallback: read whatever GL_TEXTURE_2D is now bound
            int glId = glGetInteger(GL_TEXTURE_BINDING_2D);
            if (Rentities.IS_DEBUG)
                Rentities.LOGGER.info("Fallback GL_TEXTURE_BINDING_2D={} for {}", glId, type);
            if (glId > 0) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, glId);
                glUniform1i(uEntityTextures, 0);
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("bindEntityTexture failed: {}", e.getMessage());
        }
    }


    private static float getFloat(Object obj, String... names) {
        try {
            Field f = null;
            for (String name : names) {
                f = getCachedField(obj.getClass(), name);
                if (f != null) break;
            }
            if (f == null) return 0f;
            Object val = f.get(obj);
            if (val instanceof Float) return (Float) val;
            if (val instanceof Double) return ((Double) val).floatValue();
            if (val instanceof Integer) return ((Integer) val).floatValue();
            return 0f;
        } catch (Exception e) { return 0f; }
    }

    private static boolean getBool(Object obj, String... names) {
        try {
            Field f = null;
            for (String name : names) {
                f = getCachedField(obj.getClass(), name);
                if (f != null) break;
            }
            return f != null && f.getBoolean(obj);
        } catch (Exception e) { return false; }
    }

    @SuppressWarnings("unchecked")
    public static EntityType<?> getEntityType(Object state) {
        if (state == null) return null;
        StateAccessor acc = ACCESSOR_CACHE.get(state.getClass());
        if (acc.type != null) {
            try {
                return (EntityType<?>) acc.type.invoke(state);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private int getTextureLayer(Object state) {
        try {
            // First try known mapping names (field_53336 is Vec3 in some states, so be careful)
            for (String name : new String[]{"field_53336", "texture", "V"}) {
                Field f = getCachedField(state.getClass(), name);
                if (f != null && (f.getType().getSimpleName().equals("Identifier") || 
                                 f.getType().getSimpleName().equals("ResourceLocation") || 
                                 f.getType().getName().contains("class_2960"))) {
                    Object loc = f.get(state);
                    if (loc != null) return textureAtlas.getOrUpload(loc.toString());
                }
            }
            // Fallback: search by type
            Class<?> cls = state.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType().getSimpleName().equals("Identifier") || 
                        f.getType().getSimpleName().equals("ResourceLocation") || 
                        f.getType().getName().contains("class_2960")) {
                        f.setAccessible(true);
                        Object loc = f.get(state);
                        if (loc != null) return textureAtlas.getOrUpload(loc.toString());
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) { }
        return 0;
    }

    private static Field getCachedField(Class<?> cls, String name) {
        var classMap = fieldCache.computeIfAbsent(cls, c -> new ConcurrentHashMap<>());
        return classMap.computeIfAbsent(name, n -> {
            Class<?> current = cls;
            while (current != null) {
                try {
                    Field f = current.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            return null;
        });
    }


    private void compileShader() {
        try {
            String vertSrc = loadShader("assets/rentities/shaders/entity/entity_vert.glsl");
            String fragSrc = loadShader("assets/rentities/shaders/entity/entity_frag.glsl");
            entityShader = GlShader.builder()
                    .vert(vertSrc)
                    .frag(fragSrc)
                    .compile();
            entityShader.bind();
            uViewProjection = entityShader.getUniformLocation("uViewProjection");
            uGameTime       = entityShader.getUniformLocation("uGameTime");
            uEntityTextures = entityShader.getUniformLocation("uEntityTexture");
            uBaseInstance   = entityShader.getUniformLocation("uBaseInstance");
            glUseProgram(0);
        } catch (Exception e) {
            Rentities.LOGGER.error("Entity shader compilation failed", e);
            entityShader = null;
        }
    }

    private String loadShader(String path) {
        try {
            var stream = getClass().getClassLoader().getResourceAsStream(path);
            if (stream == null) throw new RuntimeException("Shader not found: " + path);
            return new String(stream.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load entity shader: " + path, e);
        }
    }

    public boolean hasMeshFor(EntityType<?> type) {
        return meshBaker.getMeshInfoMap().containsKey(type);
    }

    public void reloadShaders() {
        if (entityShader != null) entityShader.delete();
        compileShader();
    }

    public void delete() {
        if (entityShader != null) entityShader.delete();
        for (int i = 0; i < NUM_BUFFERS; i++) {
            if (ssboIds[i] != 0) {
                glUnmapNamedBuffer(ssboIds[i]);
                glDeleteBuffers(ssboIds[i]);
            }
        }
        if (extractionBuffer != 0) MemoryUtil.nmemFree(extractionBuffer);
        meshBaker.delete();
        errorRenderer.delete();
        textureAtlas.delete();
        skinCache.delete();
        INSTANCE = null;
    }
}
