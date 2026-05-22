package me.balancinglight.rentities.entities;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL45C.*;

// Extracts entity meshes from vanilla renderers and bakes them into a shared GPU VBO.
public class EntityMeshBaker {

    public static final int VERTEX_STRIDE = 36; // 9 floats × 4 bytes
    public static final int MAX_BONES        = 8;
    public static final int MAX_ENTITY_TYPES = 256;

    // Pivot data: [typeIdx * MAX_BONES + boneIdx] * 4 floats (x,y,z,0)
    // Populated during baking, uploaded to GPU as a static SSBO.
    private final float[] bonePivotData = new float[MAX_ENTITY_TYPES * MAX_BONES * 4];
    private int pivotSSBOId = 0;
    private int currentBakingTypeIdx = -1; // set per-type during bake loop

    private int vaoId;
    private int vboId;
    private int eboId;

    public static class MeshInfo {
        public final int vertexOffset; // byte offset in VBO
        public final int indexOffset;  // byte offset in EBO
        public final int indexCount;

        public MeshInfo(int vertexOffset, int indexOffset, int indexCount) {
            this.vertexOffset = vertexOffset;
            this.indexOffset = indexOffset;
            this.indexCount = indexCount;
        }
    }

    private final Map<EntityType<?>, MeshInfo> meshInfoMap = new HashMap<>();
    private boolean baked = false;

    // Bone name → bone index maps per category
    // These match vanilla ModelPart child names exactly
    private static final Map<String, Integer> BIPED_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> QUADRUPED_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> HORSE_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> BIRD_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> ARTHROPOD_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> INSECT_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> WORM_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> FISH_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> SLIME_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> GHAST_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> CREEPER_BONES = new LinkedHashMap<>();

    static {
        // Vanilla ModelPart child names from decompiled sources
        BIPED_BONES.put("head",       0);
        BIPED_BONES.put("hat",        0); // same bone as head
        BIPED_BONES.put("body",       1);
        BIPED_BONES.put("left_arm",   2);
        BIPED_BONES.put("right_arm",  3);
        BIPED_BONES.put("left_leg",   4);
        BIPED_BONES.put("right_leg",  5);
        // Iron golem extras — still use closest bone
        BIPED_BONES.put("nose",       0);
        BIPED_BONES.put("left_ear",   0);
        BIPED_BONES.put("beard",      1);

        QUADRUPED_BONES.put("head",       0);
        QUADRUPED_BONES.put("body",       1);
        QUADRUPED_BONES.put("leg1",       2); // front left
        QUADRUPED_BONES.put("leg2",       3); // front right
        QUADRUPED_BONES.put("leg3",       4); // back left
        QUADRUPED_BONES.put("leg4",       5); // back right
        // 1.21.11 names
        QUADRUPED_BONES.put("left_front_leg",  2);
        QUADRUPED_BONES.put("right_front_leg", 3);
        QUADRUPED_BONES.put("left_hind_leg",   4);
        QUADRUPED_BONES.put("right_hind_leg",  5);
        // More variants
        QUADRUPED_BONES.put("left_front_leg_tip",  2);
        QUADRUPED_BONES.put("right_front_leg_tip", 3);
        QUADRUPED_BONES.put("left_hind_leg_tip",   4);
        QUADRUPED_BONES.put("right_hind_leg_tip",  5);
        QUADRUPED_BONES.put("tail",       1); // tail = body bone
        QUADRUPED_BONES.put("mane",       0);
        QUADRUPED_BONES.put("upper_body", 1);

        HORSE_BONES.put("head",        0);
        HORSE_BONES.put("body",        1);
        HORSE_BONES.put("front_left_leg",  2);
        HORSE_BONES.put("front_right_leg", 3);
        HORSE_BONES.put("back_left_leg",   4);
        HORSE_BONES.put("back_right_leg",  5);
        HORSE_BONES.put("tail",        6);
        HORSE_BONES.put("neck",        0);
        HORSE_BONES.put("mane",        0);
        HORSE_BONES.put("left_ear",    0);
        HORSE_BONES.put("right_ear",   0);

        BIRD_BONES.put("head",       0);
        BIRD_BONES.put("body",       1);
        BIRD_BONES.put("left_wing",  2);
        BIRD_BONES.put("right_wing", 3);
        BIRD_BONES.put("left_leg",   4);
        BIRD_BONES.put("right_leg",  5);
        BIRD_BONES.put("beak",       0);
        BIRD_BONES.put("left_foot",  4);
        BIRD_BONES.put("right_foot", 5);

        ARTHROPOD_BONES.put("head",        0);
        ARTHROPOD_BONES.put("body",        1);
        ARTHROPOD_BONES.put("right_middle_front_leg", 2);
        ARTHROPOD_BONES.put("left_middle_front_leg",  3);
        ARTHROPOD_BONES.put("right_middle_leg",  4);
        ARTHROPOD_BONES.put("left_middle_leg",   5);
        ARTHROPOD_BONES.put("right_back_leg",    6);
        ARTHROPOD_BONES.put("left_back_leg",     7);

        INSECT_BONES.put("body",          0);
        INSECT_BONES.put("torso",         0);
        INSECT_BONES.put("right_wing",    1);
        INSECT_BONES.put("left_wing",     2);
        INSECT_BONES.put("front_legs",    3);
        INSECT_BONES.put("middle_legs",   3);
        INSECT_BONES.put("back_legs",     3);
        INSECT_BONES.put("stinger",       0);
        INSECT_BONES.put("left_antenna",  0);
        INSECT_BONES.put("right_antenna", 0);

        WORM_BONES.put("body",    0);
        WORM_BONES.put("segment", 0);

        FISH_BONES.put("body",     0);
        FISH_BONES.put("tail",     1);
        FISH_BONES.put("top_fin",  0);
        FISH_BONES.put("back_fin", 0);

        SLIME_BONES.put("cube",           0);
        SLIME_BONES.put("inside_cube",    0);
        SLIME_BONES.put("left_eye",       0);
        SLIME_BONES.put("right_eye",      0);
        SLIME_BONES.put("mouth",          0);

        GHAST_BONES.put("body",       0);
        GHAST_BONES.put("tentacle0",  1);
        GHAST_BONES.put("tentacle1",  2);
        GHAST_BONES.put("tentacle2",  3);
        GHAST_BONES.put("tentacle3",  4);
        GHAST_BONES.put("tentacle4",  5);
        GHAST_BONES.put("tentacle5",  6);
        GHAST_BONES.put("tentacle6",  7);
        GHAST_BONES.put("tentacle7",  8);
        GHAST_BONES.put("tentacle8",  9);

        CREEPER_BONES.put("head",        0);
        CREEPER_BONES.put("body",        1);
        CREEPER_BONES.put("leg1",        2);
        CREEPER_BONES.put("leg2",        3);
        CREEPER_BONES.put("leg3",        4);
        CREEPER_BONES.put("leg4",        5);
    }

    public void bake() {
        if (baked) return;
        baked = true;

        // When cache exists and scan mode is OFF, skip the expensive extraction
        // pipeline entirely and upload straight to GPU from the saved file.
        if (!Rentities.config.entity_scan_mode && loadFromCache()) {
            Rentities.LOGGER.info("[EntityCache] Using cached mesh data — skipping bake");
            return;
        }

        if (Rentities.IS_DEBUG) {
            Rentities.LOGGER.info("Starting Entity Mesh Baking...");
        }

        List<float[]> allVertices = new ArrayList<>();
        List<int[]> allIndices = new ArrayList<>();
        int vertexCount = 0;
        int indexCount = 0;

        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        if (dispatcher == null) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("EntityRenderDispatcher is NULL — cannot bake meshes");
            return;
        }

        if (Rentities.IS_DEBUG) {
            Rentities.LOGGER.info("EntityRenderDispatcher found. Registry has {} types.", EntityBatchRegistry.REGISTRY_TYPES().size());
        }

        var consumer = new EntityMeshCapturingConsumer();
        var poseStack = new PoseStack();

        // Get the renderer map via reflection (field_4696 in 1.21.11 EntityRenderDispatcher)
        Map<EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer> rendererMap = null;
        try {
            Field f = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class.getDeclaredField("field_4696");
            f.setAccessible(true);
            rendererMap = (Map<EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer>) f.get(dispatcher);
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("Failed to access renderer map: {}", e.getMessage());
        }

        for (EntityType<?> type : EntityBatchRegistry.REGISTRY_TYPES()) {
            EntityAnimationCategory category = EntityBatchRegistry.getCategory(type);
            if (category == EntityAnimationCategory.CPU_ANIMATED) {
                if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Skipping CPU_ANIMATED type: {}", type);
                continue;
            }
            currentBakingTypeIdx = EntityBatchRegistry.getEntityTypeIndex(type);

            float[] vertices = null;

            // Try real extraction from renderer
            try {
                net.minecraft.client.renderer.entity.EntityRenderer renderer = null;
                if (rendererMap != null) {
                    renderer = rendererMap.get(type);
                }
                
                if (renderer == null) {
                    if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("No renderer found in map for {}", type);
                } else if (renderer instanceof LivingEntityRenderer livingRenderer) {
                    try {
                        net.minecraft.world.entity.Entity dummy = EntityFactory.getOrCreateDummy(type);
                        if (dummy == null) {
                            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Could not create dummy for {}", type);
                            continue;
                        }
                    } catch (Exception e) {
                        if (Rentities.IS_DEBUG) Rentities.LOGGER.error("Failed to create dummy for {}: {}", type, e.getMessage());
                    }
                    vertices = extractFromLivingRenderer(livingRenderer, category, consumer, poseStack);
                    if (Rentities.IS_DEBUG) {
                        if (vertices != null) {
                            Rentities.LOGGER.info("Successfully baked mesh for {}: {} vertices", type, vertices.length / 9);
                        } else {
                            Rentities.LOGGER.warn("Mesh extraction returned NULL for {}", type);
                        }
                    }
                } else {
                    if (Rentities.IS_DEBUG) {
                        String rName = renderer != null ? renderer.getClass().getName() : "NULL";
                        Rentities.LOGGER.warn("Renderer for {} is not a LivingEntityRenderer (is {})", type, rName);
                    }
                }
            } catch (Exception e) {
                if (Rentities.IS_DEBUG) {
                    Rentities.LOGGER.error("Failed to bake real mesh for {}:", type, e);
                }
            }

            // Fallback to placeholder if extraction failed
            if (vertices == null || vertices.length == 0) {
                if (Rentities.IS_DEBUG) {
                    Rentities.LOGGER.warn("Using placeholder mesh for {}", type);
                }
                vertices = generatePlaceholderMesh(category);
            }

            int[] indices = generateIndices(vertices.length / 9, vertexCount);

            int byteVertexOffset = vertexCount * VERTEX_STRIDE;
            int byteIndexOffset = indexCount * 4;
            meshInfoMap.put(type, new MeshInfo(byteVertexOffset, byteIndexOffset, indices.length));

            allVertices.add(vertices);
            allIndices.add(indices);
            vertexCount += vertices.length / 9;
            indexCount += indices.length;
        }

        if (Rentities.IS_DEBUG) {
            Rentities.LOGGER.info("Baking complete. Total vertices: {}, Total indices: {}", vertexCount, indexCount);
        }

        uploadToGPU(allVertices, allIndices, vertexCount, indexCount);

        // Always save after a fresh bake so next launch loads from cache.
        // Resolve file path NOW on render thread before handing to background thread.
        getCacheFile(); // ensure cacheFile is set while Minecraft.getInstance() is safe
        final List<float[]> vFinal = allVertices;
        final List<int[]>   iFinal = allIndices;
        Thread saveThread = new Thread(() -> saveToCache(vFinal, iFinal), "rentities-cache-save");
        saveThread.setDaemon(true);
        saveThread.start();
        // Texture atlas save needs to happen on the render thread (GL reads).
        // Signal EntityBatchRenderer to save it on the next flush.
        pendingTextureSave = true;
    }

    /** Set to true after bake to request a texture cache save from EntityBatchRenderer. */
    public volatile boolean pendingTextureSave = false;

    /**
     * Extracts geometry from a LivingEntityRenderer by walking its model's
     * named children and rendering each into the capturing consumer.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private float[] extractFromLivingRenderer(LivingEntityRenderer renderer,
                                               EntityAnimationCategory category,
                                               EntityMeshCapturingConsumer consumer,
                                               PoseStack poseStack) {
        if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Extracting from renderer: {}", renderer.getClass().getName());
        @SuppressWarnings("rawtypes") EntityModel model = getModelFromRenderer(renderer);
        if (model == null) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("Could not find EntityModel in renderer {}", renderer.getClass().getName());
            return null;
        }

        Map<String, Integer> boneMap = getBoneMap(category);
        consumer.reset();

        // Get root ModelPart (the model itself is a ModelPart tree)
        ModelPart root = getRootPart(model);
        if (root == null) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Root ModelPart is NULL for renderer {}", renderer.getClass().getName());
            
            // Try fallback: check for any ModelPart field on the model itself
            for (Field f : model.getClass().getDeclaredFields()) {
                if (f.getType() == ModelPart.class) {
                    try {
                        f.setAccessible(true);
                        root = (ModelPart) f.get(model);
                        if (root != null) {
                            if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Found fallback root ModelPart in field {}", f.getName());
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (root == null) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Final root ModelPart is NULL for renderer {}", renderer.getClass().getName());
            return null;
        }

        // Reset all bone rotations to bind pose
        resetPose(root);

        // Root transform derivation:
        // - translateAndRotate() divides pivots by 16 (pixels → blocks)
        // - Cube vertices are ALSO in block units after pose transform
        // - Shader expects vertices in PIXEL units, Y-up, feet at y=0
        //
        // We need: final_y = -16 * block_y + 24
        // i.e. scale Y by -16 (flip+scale back to pixels) then shift 24 pixels up
        //
        // Verified: arm_l pivot (5/16, 2/16, 0) → shader space (5, 22, 0) ✓
        //           feet (−,12/16+12/16,−) = (−,1.5,−) → shader space y=0 ✓
        //           head top (−,−8/16−0,−) = (−,−0.5,−) → shader space y=32 ✓
        //
        // In PoseStack (post-multiply): scale first, then translate in scaled space
        poseStack.pushPose();
        poseStack.scale(16.0f, -16.0f, 16.0f);   // scale up + flip Y
        poseStack.translate(0.0f, -1.5f, 0.0f);   // shift so feet land at y=0

        renderPartTree(root, boneMap, 0, consumer, poseStack, true);
        
        poseStack.popPose();

        float[] captured = consumer.bakeAndReset();
        if (Rentities.IS_DEBUG) {
            if (captured.length > 0) {
                Rentities.LOGGER.info("Extraction finished: captured {} vertices", captured.length / 9);
            } else {
                Rentities.LOGGER.warn("Extraction finished: captured 0 vertices!");
            }
        }
        return captured.length > 0 ? captured : null;
    }

    /**
     * Recursively walks a ModelPart tree, rendering each named part
     * with its assigned bone index from the bone map.
     *
     * KEY INVARIANT: Named bones are extracted in BONE-LOCAL space.
     * The shader pivot math assumes each bone's vertices are centered
     * at the bone's own origin (0,0,0 = pivot point). If we applied
     * translateAndRotate() for named bones, the vertices would be in
     * world/body space and the shader's pivot rotations would orbit wrongly.
     *
     * Non-named sub-parts (e.g. hat inside head) DO get their relative
     * transform applied so their position within the bone is correct.
     */
    private void renderPartTree(ModelPart part, Map<String, Integer> boneMap,
                                 int inheritedBone, EntityMeshCapturingConsumer consumer,
                                 PoseStack poseStack, boolean isRoot) {
        try {
            Field childrenField = getChildrenField();
            if (childrenField == null) return;

            @SuppressWarnings("unchecked")
            Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);
            if (children == null) return;

            if (isRoot) renderPartCubesDirectly(part, consumer, poseStack);

            for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
                String name = entry.getKey();
                ModelPart child = entry.getValue();
                int boneIdx = boneMap.getOrDefault(name, inheritedBone);
                consumer.setBone(boneIdx);
                poseStack.pushPose();
                child.translateAndRotate(poseStack);

                // translateAndRotate() with reset pose (rotation=0) only applies the
                // pivot translation.  After the root scale(16,-16,16)+translate(0,-1.5,0)
                // the matrix's translation column gives the pivot in shader pixel space.
                if (currentBakingTypeIdx >= 0 && currentBakingTypeIdx < MAX_ENTITY_TYPES
                        && boneIdx >= 0 && boneIdx < MAX_BONES) {
                    Matrix4f m = poseStack.last().pose();
                    int base = (currentBakingTypeIdx * MAX_BONES + boneIdx) * 4;
                    // Only write if not already set (first named bone wins per index)
                    if (bonePivotData[base] == 0.0f && bonePivotData[base+1] == 0.0f && bonePivotData[base+2] == 0.0f) {
                        bonePivotData[base]   = m.m30();
                        bonePivotData[base+1] = m.m31();
                        bonePivotData[base+2] = m.m32();
                        bonePivotData[base+3] = 0.0f;
                    }
                }

                renderPartCubesDirectly(child, consumer, poseStack);
                renderPartTree(child, boneMap, boneIdx, consumer, poseStack, false);
                poseStack.popPose();
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("renderPartTree error: {}", e.getMessage());
        }
    }

    private Field cachedChildrenField = null;
    private Field getChildrenField() {
        if (cachedChildrenField != null) return cachedChildrenField;
        for (String name : new String[]{"field_3661", "children", "n"}) {
            Field f = getCachedField(ModelPart.class, name);
            if (f != null && Map.class.isAssignableFrom(f.getType())) {
                cachedChildrenField = f;
                return f;
            }
        }
        for (Field f : ModelPart.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                cachedChildrenField = f;
                return f;
            }
        }
        return null;
    }

    /**
     * Renders the cubes of a ModelPart directly into the consumer.
     * Avoids ModelPart.render() recursion and double-transform issues.
     */
    private void renderPartCubesDirectly(ModelPart part, EntityMeshCapturingConsumer consumer,
                                   PoseStack poseStack) {
        try {
            // Get cubes list via reflection
            // field_3663 = cubes (Yarn), c = cubes (Mojang)
            Field cubesField = null;
            for (String name : new String[]{"field_3663", "cubes", "m"}) {
                cubesField = getCachedField(ModelPart.class, name);
                if (cubesField != null && List.class.isAssignableFrom(cubesField.getType())) break;
            }

            if (cubesField == null) {
                // Fallback: search for any List field
                for (Field f : ModelPart.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        cubesField = f;
                        break;
                    }
                }
            }

            if (cubesField == null) return;

            @SuppressWarnings("unchecked")
            List<?> cubes = (List<?>) cubesField.get(part);
            if (cubes == null || cubes.isEmpty()) return;

            if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Rendering {} cubes", cubes.size());

            // ModelPart.Cube.render(PoseStack.Entry, VertexConsumer, int, int, int)
            // Intermediate name for Cube.render: method_32089
            for (Object cube : cubes) {
                try {
                    Method m = null;
                    // Search for any method with 5 parameters (PoseStack.Entry, VertexConsumer, int, int, int)
                    for (Method cand : cube.getClass().getDeclaredMethods()) {
                        if (cand.getParameterCount() == 5 && 
                            VertexConsumer.class.isAssignableFrom(cand.getParameterTypes()[1])) {
                            m = cand;
                            break;
                        }
                    }

                    if (m != null) {
                        m.setAccessible(true);
                        // Parameters: (PoseStack.Entry, VertexConsumer, int, int, int)
                        m.invoke(cube, poseStack.last(), consumer, 0xF000F0, 0, 0xFFFFFFFF);
                    }
                } catch (Exception e) { }
            }
        } catch (Exception e) { }
    }

    private void renderPartCubes(ModelPart part, EntityMeshCapturingConsumer consumer,
                                   PoseStack poseStack) {
        // Replaced by renderPartCubesDirectly
    }


    /**
     * Extracts the EntityModel from a LivingEntityRenderer using reflection.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private EntityModel getModelFromRenderer(LivingEntityRenderer renderer) {
        // Try field named "model" first (official)
        try {
            Field f = renderer.getClass().getField("model");
            f.setAccessible(true);
            return (EntityModel) f.get(renderer);
        } catch (Exception ignored) {}

        // Then try "field_4744" (intermediary)
        try {
            Field f = getCachedField(renderer.getClass(), "field_4744");
            if (f != null) return (EntityModel) f.get(renderer);
        } catch (Exception ignored) {}

        // Fallback: search for any EntityModel field
        for (Field f : getAllFields(renderer.getClass())) {
            if (EntityModel.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(renderer);
                    if (val != null) {
                        if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Found EntityModel field: {} in {}", f.getName(), renderer.getClass().getSimpleName());
                        return (EntityModel) val;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private ModelPart getRootPart(EntityModel<?> model) {
        if (model == null) return null;
        
        // Strategy 1: Search for any public method root()
        try {
            for (Method m : model.getClass().getMethods()) {
                if (m.getName().equals("root") || m.getName().equals("method_62471") || m.getName().equals("a")) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == ModelPart.class) {
                        ModelPart root = (ModelPart) m.invoke(model);
                        if (root != null) return root;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Strategy 2: Search for ANY field of type ModelPart (prioritize name "root")
        List<ModelPart> candidates = new ArrayList<>();
        try {
            Class<?> current = model.getClass();
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.getType() == ModelPart.class) {
                        f.setAccessible(true);
                        ModelPart part = (ModelPart) f.get(model);
                        if (part != null) {
                            if (f.getName().equalsIgnoreCase("root") || f.getName().equals("field_52912")) {
                                return part; // Highest priority
                            }
                            candidates.add(part);
                        }
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Exception ignored) {}

        // If multiple candidates, pick the one that has the most children in its map
        ModelPart best = null;
        int maxChildren = -1;
        for (ModelPart p : candidates) {
            int c = countChildren(p);
            if (c > maxChildren) {
                maxChildren = c;
                best = p;
            }
        }

        return best;
    }

    private int countChildren(ModelPart part) {
        try {
            for (String name : new String[]{"field_3661", "children", "n"}) {
                Field f = getCachedField(ModelPart.class, name);
                if (f != null && Map.class.isAssignableFrom(f.getType())) {
                    Map<?, ?> map = (Map<?, ?>) f.get(part);
                    return map != null ? map.size() : 0;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void resetPose(ModelPart part) {
        try {
            // Try known method names for resetPose
            // In 1.21.11, resetPose is method_41923 (Yarn) or 'c' (Mojang)
            for (String name : new String[]{"resetPose", "method_41923", "c"}) {
                try {
                    Method m = part.getClass().getMethod(name);
                    m.invoke(part);
                    return;
                } catch (NoSuchMethodException ignored) {}
            }
            
            // Manually zero rotations if method not found
            part.xRot = 0; part.yRot = 0; part.zRot = 0;
            // Search for children field by name/type
            Field childrenField = null;
            for (String name : new String[]{"children", "field_3661", "d"}) {
                childrenField = getCachedField(ModelPart.class, name);
                if (childrenField != null && Map.class.isAssignableFrom(childrenField.getType())) break;
            }
            
            if (childrenField != null) {
                @SuppressWarnings("unchecked")
                Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);
                if (children != null) children.values().forEach(this::resetPose);
            }
        } catch (Exception ignored) {}
    }

    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();

    private static Field getCachedField(Class<?> startClass, String name) {
        String key = startClass.getName() + "#" + name;
        if (FIELD_CACHE.containsKey(key)) return FIELD_CACHE.get(key);
        
        Class<?> cls = startClass;
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException e) { 
                cls = cls.getSuperclass(); 
            }
        }
        
        // Final fallback: search for field by type if name search failed
        // For example, if "field_3661" (Map) or "field_3663" (List) changes
        if (name.equals("field_3661")) { // Map<String, ModelPart>
            for (Field f : startClass.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    FIELD_CACHE.put(key, f);
                    if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Fell back to Map field {} for field_3661", f.getName());
                    return f;
                }
            }
        } else if (name.equals("field_3663")) { // List<Cube>
            for (Field f : startClass.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    FIELD_CACHE.put(key, f);
                    if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Fell back to List field {} for field_3663", f.getName());
                    return f;
                }
            }
        }

        FIELD_CACHE.put(key, null);
        return null;
    }

    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return fields;
    }

    private static Map<String, Integer> getBoneMap(EntityAnimationCategory category) {
        return switch (category) {
            case BIPED, FLOATING, FLOATING_SPINNING, SHULKER, STRIDER -> BIPED_BONES;
            case QUADRUPED, GOAT, SNIFFER, ARMADILLO, AQUATIC_LEGS, SWIMMING, FROG -> QUADRUPED_BONES;
            case HORSE -> HORSE_BONES;
            case BIRD -> BIRD_BONES;
            case ARTHROPOD -> ARTHROPOD_BONES;
            case INSECT -> INSECT_BONES;
            case WORM -> WORM_BONES;
            case FISH -> FISH_BONES;
            case SLIME -> SLIME_BONES;
            case GHAST -> GHAST_BONES;
            case CREEPER -> CREEPER_BONES;
            default -> BIPED_BONES;
        };
    }


    private float[] generatePlaceholderMesh(EntityAnimationCategory category) {
        List<float[]> bones = new ArrayList<>();
        // In 1.21.11, we capture meshes in PIXEL units (16 units = 1 block).
        // The shader then scales by 0.0625.
        // So placeholders must also be in pixels.
        switch (category) {
            case BIPED, CREEPER -> {
                bones.add(box(-4, 24, -4, 8, 8, 8, 0));   // HEAD
                bones.add(box(-4, 12, -2, 8, 12, 4, 1));  // BODY
                bones.add(box(-8, 12, -2, 4, 12, 4, 2));  // ARM_L
                bones.add(box( 4, 12, -2, 4, 12, 4, 3));  // ARM_R
                bones.add(box(-4,  0, -2, 4, 12, 4, 4));  // LEG_L
                bones.add(box( 0,  0, -2, 4, 12, 4, 5));  // LEG_R
            }
            case QUADRUPED, GOAT, SNIFFER, ARMADILLO -> {
                bones.add(box(-4, 16, -8, 8, 8, 8, 0));    // HEAD
                bones.add(box(-5, 10, -6, 10, 8, 16, 1));  // BODY
                bones.add(box(-7,  0, -2, 4, 10, 4, 2));   // LEG_FL
                bones.add(box( 3,  0, -2, 4, 10, 4, 3));   // LEG_FR
                bones.add(box(-7,  0,  8, 4, 10, 4, 4));   // LEG_BL
                bones.add(box( 3,  0,  8, 4, 10, 4, 5));   // LEG_BR
            }
            case HORSE -> {
                bones.add(box(-3, 16, -7, 6, 8, 6, 0));   // HEAD
                bones.add(box(-5, 10, -6, 10, 8, 16, 1)); // BODY
                bones.add(box(-6,  0, -2, 4, 10, 4, 2));
                bones.add(box( 2,  0, -2, 4, 10, 4, 3));
                bones.add(box(-6,  0,  8, 4, 10, 4, 4));
                bones.add(box( 2,  0,  8, 4, 10, 4, 5));
                bones.add(box(-1,  6, 10, 2, 8, 2, 6));   // TAIL
            }
            case BIRD -> {
                bones.add(box(-2, 14,  -4, 4, 4, 4, 0));  // HEAD
                bones.add(box(-3,  9,  -3, 6, 6, 8, 1));  // BODY
                bones.add(box(-6,  9,  -3, 3, 2, 6, 2));  // WING_L
                bones.add(box( 3,  9,  -3, 3, 2, 6, 3));  // WING_R
                bones.add(box(-2,  0,  -1, 2, 9, 2, 4));  // LEG_L
                bones.add(box( 0,  0,  -1, 2, 9, 2, 5));  // LEG_R
            }
            case SLIME -> {
                bones.add(box(-3, 1, -3, 6, 6, 6, 0));   // BODY
                bones.add(box(-2, 2, -4, 4, 4, 4, 0));   // INNER
            }
            default -> bones.add(box(-4, 0, -4, 8, 16, 8, 0));
        }
        int total = bones.stream().mapToInt(b -> b.length).sum();
        float[] result = new float[total];
        int pos = 0;
        for (float[] b : bones) { System.arraycopy(b, 0, result, pos, b.length); pos += b.length; }
        return result;
    }

    /** Box mesh: 24 vertices (4 per face × 6 faces), 9 floats each. */
    private float[] box(float x, float y, float z, float w, float h, float d, int bone) {
        float x2 = x+w, y2 = y+h, z2 = z+d, bf = (float)bone;
        // Vertices: pos(3), normal(3), uv(2), bone(1) = 9 floats
        return new float[]{
            // Top (Y+) - Normal (0,1,0)
            x,y2,z,  0,1,0, 0,0,bf,  
            x,y2,z2,  0,1,0, 0,1,bf,  
            x2,y2,z2, 0,1,0, 1,1,bf,  
            x2,y2,z, 0,1,0, 1,0,bf,
            
            // Bottom (Y-) - Normal (0,-1,0)
            x,y,z,  0,-1,0, 0,0,bf,  
            x2,y,z,  0,-1,0, 1,0,bf,  
            x2,y,z2, 0,-1,0, 1,1,bf,  
            x,y,z2, 0,-1,0, 0,1,bf,
            
            // Right (X+) - Normal (1,0,0)
            x2,y,z,  1,0,0, 0,0,bf,  
            x2,y2,z, 1,0,0, 0,1,bf,  
            x2,y2,z2, 1,0,0, 1,1,bf,  
            x2,y,z2, 1,0,0, 1,0,bf,
            
            // Left (X-) - Normal (-1,0,0)
            x,y,z,  -1,0,0, 0,0,bf,  
            x,y,z2,  -1,0,0, 1,0,bf,  
            x,y2,z2, -1,0,0, 1,1,bf,  
            x,y2,z, -1,0,0, 0,1,bf,
            
            // Front (Z+) - Normal (0,0,1)
            x,y,z2,  0,0,1, 0,0,bf,  
            x2,y,z2, 0,0,1, 1,0,bf,  
            x2,y2,z2, 0,0,1, 1,1,bf,  
            x,y2,z2, 0,0,1, 0,1,bf,
            
            // Back (Z-) - Normal (0,0,-1)
            x,y,z,   0,0,-1,0,0,bf,  
            x,y2,z,  0,0,-1,0,1,bf,  
            x2,y2,z, 0,0,-1, 1,1,bf,  
            x2,y,z,  0,0,-1,1,0,bf,
        };
    }


    private void uploadToGPU(List<float[]> allVertices, List<int[]> allIndices,
                              int totalVertices, int totalIndices) {
        vaoId = glCreateVertexArrays();
        vboId = glCreateBuffers();
        eboId = glCreateBuffers();

        long vboSize = (long) totalVertices * VERTEX_STRIDE;
        long eboSize = (long) totalIndices * 4;
        glNamedBufferData(vboId, vboSize, GL_STATIC_DRAW);
        glNamedBufferData(eboId, eboSize, GL_STATIC_DRAW);

        long vboOff = 0, eboOff = 0;
        for (int i = 0; i < allVertices.size(); i++) {
            float[] verts = allVertices.get(i);
            int[] inds = allIndices.get(i);

            ByteBuffer vbuf = MemoryUtil.memAlloc(verts.length * 4);
            vbuf.asFloatBuffer().put(verts);
            glNamedBufferSubData(vboId, vboOff, vbuf);
            MemoryUtil.memFree(vbuf);

            ByteBuffer ibuf = MemoryUtil.memAlloc(inds.length * 4);
            // Verify indices are within total vertex range
            for (int idx : inds) {
                if (idx >= totalVertices) {
                    if (Rentities.IS_DEBUG) Rentities.LOGGER.error("INDEX OUT OF BOUNDS: {} >= {}", idx, totalVertices);
                }
            }
            ibuf.asIntBuffer().put(inds);
            glNamedBufferSubData(eboId, eboOff, ibuf);
            MemoryUtil.memFree(ibuf);

            vboOff += (long) verts.length * 4;
            eboOff += (long) inds.length * 4;
        }

        // VAO attrib layout
        glVertexArrayVertexBuffer(vaoId, 0, vboId, 0, VERTEX_STRIDE);
        // Attrib 0: position (vec3, offset 0)
        glEnableVertexArrayAttrib(vaoId, 0);
        glVertexArrayAttribFormat(vaoId, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vaoId, 0, 0);
        // Attrib 1: normal (vec3, offset 12)
        glEnableVertexArrayAttrib(vaoId, 1);
        glVertexArrayAttribFormat(vaoId, 1, 3, GL_FLOAT, false, 12);
        glVertexArrayAttribBinding(vaoId, 1, 0);
        // Attrib 2: texcoord (vec2, offset 24)
        glEnableVertexArrayAttrib(vaoId, 2);
        glVertexArrayAttribFormat(vaoId, 2, 2, GL_FLOAT, false, 24);
        glVertexArrayAttribBinding(vaoId, 2, 0);
        // Attrib 3: boneIndex (float, offset 32)
        glEnableVertexArrayAttrib(vaoId, 3);
        glVertexArrayAttribFormat(vaoId, 3, 1, GL_FLOAT, false, 32);
        glVertexArrayAttribBinding(vaoId, 3, 0);

        glVertexArrayElementBuffer(vaoId, eboId);
        
        // Final sanity check: force VAO binding to ensure state is captured
        glBindVertexArray(vaoId);
        glBindVertexArray(0);
    }

    private int[] generateIndices(int vertexCount, int baseVertex) {
        int quadCount = vertexCount / 4;
        int[] idx = new int[quadCount * 6];
        for (int i = 0; i < quadCount; i++) {
            int b = baseVertex + i*4, o = i*6;
            idx[o]=b; idx[o+1]=b+1; idx[o+2]=b+2;
            idx[o+3]=b; idx[o+4]=b+2; idx[o+5]=b+3;
        }
        return idx;
    }

    public int getVaoId() { return vaoId; }
    public Map<EntityType<?>, MeshInfo> getMeshInfoMap() { return meshInfoMap; }
    public boolean isBaked() { return baked; }


    /**
     * Uploads the bone pivot table to a static SSBO (binding 13).
     * Must be called AFTER bake() completes.
     * Returns the GL buffer id.
     */
    public int uploadPivotSSBO() {
        if (pivotSSBOId != 0) return pivotSSBOId; // already uploaded
        pivotSSBOId = glCreateBuffers();
        ByteBuffer buf = MemoryUtil.memAlloc(bonePivotData.length * 4);
        buf.asFloatBuffer().put(bonePivotData).flip();
        glNamedBufferData(pivotSSBOId, buf, GL_STATIC_DRAW);
        MemoryUtil.memFree(buf);
        if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Uploaded bone pivot SSBO: {} entries", bonePivotData.length / 4);
        return pivotSSBOId;
    }

    public int getPivotSSBOId() { return pivotSSBOId; }

    // Cache format (little-endian binary):
    //   [int magic=0xECAC1021] [int version=1]
    //   [int entityTypeCount]
    //   per type:
    //     [int idLen] [byte[] id utf8]
    //     [int vertexFloatCount] [float[] vertices]
    //     [int indexCount]       [int[]   indices]
    //     [int boneIdx0..7 pivotX,Y,Z,0 = 32 floats]  (bone pivot table for this type)
    //     [int meshVertexOffset] [int meshIndexOffset] [int meshIndexCount]
    //   [int pivotTableSize] [float[] full bonePivotData] (all types × MAX_BONES × 4)

    private static final int CACHE_MAGIC   = 0xECAC1021;
    private static final int CACHE_VERSION = 1;

    /** Cache file location: .minecraft/nvidium_entity_mesh_cache.bin */
    private static java.io.File cacheFile = null;

    private static java.io.File getCacheFile() {
        if (cacheFile == null) {
            cacheFile = new java.io.File(
                net.minecraft.client.Minecraft.getInstance().gameDirectory,
                "rentities_entity_mesh_cache.bin");
        }
        return cacheFile;
    }

    /**
     * Serialises the baked mesh data to disk.
     * Call after bake() succeeds.
     */
    public void saveToCache(List<float[]> allVertices, List<int[]> allIndices) {
        java.io.File f = getCacheFile();
        List<EntityType<?>> types = new ArrayList<>(meshInfoMap.keySet());

        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            out.writeInt(CACHE_MAGIC);
            out.writeInt(CACHE_VERSION);
            out.writeInt(types.size());

            int i = 0;
            for (EntityType<?> type : types) {
                MeshInfo info = meshInfoMap.get(type);
                float[] verts = allVertices.get(i);
                int[]   inds  = allIndices.get(i);
                i++;

                // Type ID
                String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(type).toString();
                byte[] idBytes = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(idBytes.length);
                out.write(idBytes);

                // Vertices
                out.writeInt(verts.length);
                for (float v : verts) out.writeFloat(v);

                // Indices
                out.writeInt(inds.length);
                for (int idx : inds) out.writeInt(idx);

                // Mesh info
                out.writeInt(info.vertexOffset);
                out.writeInt(info.indexOffset);
                out.writeInt(info.indexCount);
            }

            // Full bone pivot table
            out.writeInt(bonePivotData.length);
            for (float p : bonePivotData) out.writeFloat(p);

            Rentities.LOGGER.info("[EntityCache] Saved {} entity types to {}", types.size(), f.getPath());
        } catch (Exception e) {
            Rentities.LOGGER.error("[EntityCache] Failed to save cache: {}", e.getMessage());
        }
    }

    /**
     * Loads previously saved mesh data directly to GPU without running the
     * full vanilla model extraction pipeline.
     * Returns true if cache was loaded successfully.
     */
    public boolean loadFromCache() {
        java.io.File f = getCacheFile();
        if (!f.exists()) return false;

        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {

            if (in.readInt() != CACHE_MAGIC)   { Rentities.LOGGER.warn("[EntityCache] Bad magic"); return false; }
            if (in.readInt() != CACHE_VERSION)  { Rentities.LOGGER.warn("[EntityCache] Version mismatch"); return false; }

            int typeCount = in.readInt();
            List<float[]> allVertices = new ArrayList<>(typeCount);
            List<int[]>   allIndices  = new ArrayList<>(typeCount);
            int totalVertexCount = 0, totalIndexCount = 0;

            for (int i = 0; i < typeCount; i++) {
                // Type ID
                int idLen = in.readInt();
                byte[] idBytes = new byte[idLen];
                in.readFully(idBytes);
                String id = new String(idBytes, java.nio.charset.StandardCharsets.UTF_8);
                // Find entity type by matching the saved ID string against all known types.
                // Avoids ResourceLocation/reflection issues — same iteration used in bake().
                EntityType<?> type = null;
                for (EntityType<?> candidate : EntityBatchRegistry.REGISTRY_TYPES()) {
                    Object key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(candidate);
                    if (key != null && key.toString().equals(id)) {
                        type = candidate;
                        break;
                    }
                }
                if (type == null) {
                    Rentities.LOGGER.warn("[EntityCache] Unknown entity type: {}, skipping", id);
                    // Still need to read past the data
                    int vLen = in.readInt(); for (int j=0;j<vLen;j++) in.readFloat();
                    int iLen = in.readInt(); for (int j=0;j<iLen;j++) in.readInt();
                    in.readInt(); in.readInt(); in.readInt(); // meshInfo
                    continue;
                }

                // Vertices
                int vLen = in.readInt();
                float[] verts = new float[vLen];
                for (int j = 0; j < vLen; j++) verts[j] = in.readFloat();

                // Indices
                int iLen = in.readInt();
                int[] inds = new int[iLen];
                for (int j = 0; j < iLen; j++) inds[j] = in.readInt();

                // Mesh info
                int vOffset = in.readInt();
                int iOffset = in.readInt();
                int iCount  = in.readInt();
                meshInfoMap.put(type, new MeshInfo(vOffset, iOffset, iCount));

                allVertices.add(verts);
                allIndices.add(inds);
                totalVertexCount += verts.length / 9;
                totalIndexCount  += iLen;
            }

            // Pivot table
            int pivotLen = in.readInt();
            for (int i = 0; i < Math.min(pivotLen, bonePivotData.length); i++)
                bonePivotData[i] = in.readFloat();

            // Upload to GPU
            uploadToGPU(allVertices, allIndices, totalVertexCount, totalIndexCount);
            baked = true;
            Rentities.LOGGER.info("[EntityCache] Loaded {} entity types from cache", meshInfoMap.size());
            return true;

        } catch (Exception e) {
            Rentities.LOGGER.error("[EntityCache] Failed to load cache: {}", e.getMessage());
            meshInfoMap.clear();
            baked = false;
            return false;
        }
    }

    public static boolean cacheExists() { return getCacheFile().exists(); }
    public static void deleteCache()    { getCacheFile().delete(); }
    public static void deleteTextureCache() { EntityTextureAtlas.deleteTextureCache(); }

    public void delete() {
        if (vaoId != 0) glDeleteVertexArrays(vaoId);
        if (vboId != 0) glDeleteBuffers(vboId);
        if (eboId != 0) glDeleteBuffers(eboId);
    }
}
