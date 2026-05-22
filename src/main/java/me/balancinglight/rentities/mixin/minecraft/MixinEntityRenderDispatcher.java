package me.balancinglight.rentities.mixin.minecraft;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.entities.EntityBatchRenderer;
import me.balancinglight.rentities.entities.EntityBatchRegistry;
import me.balancinglight.rentities.entities.EntityAnimationCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(targets = "net/minecraft/class_898", remap = false)
public abstract class MixinEntityRenderDispatcher {

    @Inject(method = "method_72976", at = @At("HEAD"), cancellable = true, remap = false)
    private void interceptEntityRender(
            @Coerce Object state,
            @Coerce Object light,
            double x, double y, double z,
            @Coerce Object poseStack,
            @Coerce Object buffers,
            CallbackInfo ci) {

        if (!Rentities.IS_ENABLED) return;
        if (state == null) return;

        EntityType<?> type = EntityBatchRenderer.getEntityType(state);
        if (type == null) return;

        EntityAnimationCategory category = EntityBatchRegistry.getCategory(type);
        if (category == EntityAnimationCategory.CPU_ANIMATED) return;

        EntityBatchRenderer renderer = EntityBatchRenderer.INSTANCE;

        if (renderer != null && !renderer.hasMeshFor(type)) {
            if (!Rentities.config.entity_scan_mode) {
                EntityBatchRenderer.queueErrorEntity(x, y, z);
                ci.cancel();
            }
            return;
        }

        if (renderer != null && !renderer.entityGlTexIds.containsKey(type)
                && !renderer.entityTexFailed.contains(type)) {
            tryResolveTexture(renderer, type, state);
        }

        EntityBatchRenderer.queueEntityStateDirect(state, x, y, z, type);
        if (Rentities.IS_DEBUG)
            Rentities.LOGGER.info("Queued entity type={}, category={}", type, category);
        ci.cancel();
    }

    private void tryResolveTexture(EntityBatchRenderer renderer, EntityType<?> type, Object state) {
        try {
            Class<?> cls = state.getClass();
            while (cls != null && cls != Object.class) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    String typeName = f.getType().getSimpleName();
                    if (typeName.equals("ResourceLocation") || typeName.equals("Identifier")) {
                        f.setAccessible(true);
                        Object loc = f.get(state);
                        if (loc != null) {
                            if (Rentities.IS_DEBUG)
                                Rentities.LOGGER.info("Found texture loc via state field {} = {}", f.getName(), loc);
                            renderer.entityTextureLocs.put(type, loc);
                            renderer.entityGlTexIds.put(type, 1);
                            return;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Texture lookup via render state failed for {}: {}", type, e.getMessage());
        }

        try {
            String targetKey = "entity.minecraft." + type.toShortString(); // "entity.minecraft.zombie"
            if (Rentities.IS_DEBUG)
                Rentities.LOGGER.info("Looking for renderer, targetKey={}", targetKey);

            Object entityRenderer = null;
            try {
                java.lang.reflect.Field rf = null;
                Class<?> sc = this.getClass();
                while (sc != null && sc != Object.class) {
                    try { rf = sc.getDeclaredField("field_4696"); break; }
                    catch (NoSuchFieldException ignored) { sc = sc.getSuperclass(); }
                }
                if (rf != null) {
                    rf.setAccessible(true);
                    java.util.Map<?,?> map = (java.util.Map<?,?>) rf.get(this);
                    if (Rentities.IS_DEBUG)
                        Rentities.LOGGER.info("field_4696 map size={}", map.size());
                    for (java.util.Map.Entry<?,?> entry : map.entrySet()) {
                        if (entry.getKey() != null && targetKey.equals(entry.getKey().toString())) {
                            entityRenderer = entry.getValue();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("field_4696 access failed: {}", e.getMessage());
            }

            if (entityRenderer == null) {
                if (Rentities.IS_DEBUG)
                    Rentities.LOGGER.warn("Could not find renderer for {} in renderers map", targetKey);
            } else {
                Class<?> rc = entityRenderer.getClass();
                while (rc != null && rc != Object.class) {
                    for (java.lang.reflect.Method m : rc.getDeclaredMethods()) {
                        if (m.getParameterCount() == 1 &&
                            (m.getName().equals("method_3885") ||
                             m.getName().equals("getTextureLocation"))) {
                            m.setAccessible(true);
                            try {
                                Object loc = m.invoke(entityRenderer, state);
                                if (Rentities.IS_DEBUG)
                                    Rentities.LOGGER.info("method_3885({}) = {}", targetKey, loc);
                                if (loc != null) {
                                    // the new GpuTexture pipeline in 1.21.11
                                    renderer.entityTextureLocs.put(type, loc);
                                    renderer.entityGlTexIds.put(type, 1);
                                    if (Rentities.IS_DEBUG)
                                        Rentities.LOGGER.info("Cached texture loc for {}: {}", type, loc);
                                    return;
                                }
                            } catch (Exception e) {
                                if (Rentities.IS_DEBUG)
                                    Rentities.LOGGER.warn("method_3885 invoke failed: {}", e.getMessage());
                            }
                        }
                    }
                    rc = rc.getSuperclass();
                }
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Texture lookup via renderer map failed for {}: {}", type, e.getMessage());
        }

        if (Rentities.IS_DEBUG)
            Rentities.LOGGER.warn("Could not resolve texture for {}", type);
        renderer.entityTexFailed.add(type);
    }

}

