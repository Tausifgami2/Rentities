package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;

public class EntitySkinCache {

    private static final int SKIN_W = 64;
    private static final int SKIN_H = 64;
    private static final int MAX_SKINS = 512;

    private final int textureArrayId;
    private final int[] layerFreeList;
    private int freeHead;

    private final Map<UUID, Integer> skinToLayer = new HashMap<>();
    private final UUID[] layerOwner;

    private final java.util.concurrent.ConcurrentLinkedQueue<SkinUpload> uploadQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private record SkinUpload(UUID uuid, String key, int layer) {}

    public EntitySkinCache() {
        textureArrayId = glCreateTextures(GL_TEXTURE_2D_ARRAY);
        glTextureStorage3D(textureArrayId, 1, GL_RGBA8, SKIN_W, SKIN_H, MAX_SKINS);
        glTextureParameteri(textureArrayId, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(textureArrayId, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(textureArrayId, GL_TEXTURE_MAX_LEVEL, 0);

        layerFreeList = new int[MAX_SKINS];
        for (int i = 0; i < MAX_SKINS; i++) layerFreeList[i] = i;
        freeHead = 0;
        layerOwner = new UUID[MAX_SKINS];
    }

    public int getOrUploadSkin(UUID playerUUID, String skinTextureKey) {
        Integer layer = skinToLayer.get(playerUUID);
        if (layer != null) return layer;

        synchronized (skinToLayer) {
            if (skinToLayer.containsKey(playerUUID)) return skinToLayer.get(playerUUID);

            if (freeHead >= MAX_SKINS) {
                evictLayer(MAX_SKINS - 1);
            }

            int newLayer = layerFreeList[freeHead++];
            skinToLayer.put(playerUUID, newLayer);
            layerOwner[newLayer] = playerUUID;
            uploadQueue.add(new SkinUpload(playerUUID, skinTextureKey, newLayer));
            return newLayer;
        }
    }

    public void processUploads() {
        SkinUpload upload;
        while ((upload = uploadQueue.poll()) != null) {
            uploadSkin(upload.key, upload.layer);
        }
    }

    private void uploadSkin(String skinTextureKey, int layer) {
        try {
            var textureManager = Minecraft.getInstance().getTextureManager();
            Class<?> rlClass;
            try {
                rlClass = Class.forName("net.minecraft.class_2960");
            } catch (Exception e) {
                rlClass = Class.forName("net.minecraft.util.Identifier");
            }

            Object rl;
            try {
                rl = rlClass.getConstructor(String.class).newInstance(skinTextureKey);
            } catch (Exception e) {
                try {
                    rl = rlClass.getMethod("method_43902", String.class).invoke(null, skinTextureKey);
                } catch (Exception e2) {
                    rl = rlClass.getMethod("of", String.class).invoke(null, skinTextureKey);
                }
            }

            var getTexture = textureManager.getClass().getMethod("method_4615", rlClass);
            var texObj = getTexture.invoke(textureManager, rl);
            if (texObj != null) {
                var idMethod = texObj.getClass().getMethod("method_4624");
                int glTexId = (int) idMethod.invoke(texObj);
                if (glTexId > 0) {
                    glCopyImageSubData(
                            glTexId, GL_TEXTURE_2D, 0, 0, 0, 0,
                            textureArrayId, GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer,
                            SKIN_W, SKIN_H, 1
                    );
                }
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("Failed to upload skin {}: {}", skinTextureKey, e.getMessage());
        }
    }

    public void evict(UUID playerUUID) {
        Integer layer = skinToLayer.remove(playerUUID);
        if (layer != null) {
            layerOwner[layer] = null;
            layerFreeList[--freeHead] = layer;
        }
    }

    private void evictLayer(int layer) {
        UUID owner = layerOwner[layer];
        if (owner != null) {
            skinToLayer.remove(owner);
            layerOwner[layer] = null;
            layerFreeList[--freeHead] = layer;
        }
    }

    public int getTextureArrayId() {
        return textureArrayId;
    }

    public void delete() {
        glDeleteTextures(textureArrayId);
        skinToLayer.clear();
    }
}
