package me.balancinglight.rentities.mixin.minecraft;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.entities.EntityBatchRenderer;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_761", remap = false)
public class MixinLevelRenderer {

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void onInit(CallbackInfo ci) {
        Rentities.checkAndEnable();

        if (Rentities.IS_ENABLED && EntityBatchRenderer.INSTANCE == null) {
            new EntityBatchRenderer();
        }
    }

    @Inject(method = "close", at = @At("TAIL"), remap = false)
    private void onClose(CallbackInfo ci) {
        if (EntityBatchRenderer.INSTANCE != null) {
            EntityBatchRenderer.INSTANCE.delete();
        }
    }

    @Inject(method = "method_22710", at = @At("HEAD"), remap = false)
    private void captureWorldMatrices(
            @Coerce Object allocator,
            @Coerce Object deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            @Coerce Object basicProjectionMatrix,
            Matrix4f projectionMatrix,
            @Coerce Object fogBuffer,
            @Coerce Object fogColor,
            boolean renderSky,
            CallbackInfo ci) {
        if (!Rentities.IS_ENABLED || EntityBatchRenderer.INSTANCE == null) return;

        Vec3 camPos = camera.position();
        EntityBatchRenderer.cameraX = camPos.x;
        EntityBatchRenderer.cameraY = camPos.y;
        EntityBatchRenderer.cameraZ = camPos.z;

        EntityBatchRenderer.setViewMatrix(new Matrix4f(positionMatrix));
        EntityBatchRenderer.updateProjectionMatrix(new Matrix4f(projectionMatrix));
    }

    @Inject(method = "method_62214", at = @At("TAIL"), remap = false)
    private void afterRenderEntities(CallbackInfo ci) {
        if (!Rentities.IS_ENABLED) return;
        EntityBatchRenderer.flushBatch();
    }
}
