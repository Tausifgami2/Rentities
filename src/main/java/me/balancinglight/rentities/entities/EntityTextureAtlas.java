package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;

public class EntityTextureAtlas {

    private static final int TEX_W = 256;
    private static final int TEX_H = 256;
    private static final int MAX_ENTITY_TEXTURES = 256;

    private final int textureArrayId;
    private final Map<String, Integer> texToLayer = new HashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> uploadQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<Integer, Integer> layerToWidth = new HashMap<>();
    private final Map<Integer, Integer> layerToHeight = new HashMap<>();
    private int nextLayer = 0;

    public EntityTextureAtlas() {
        textureArrayId = glCreateTextures(GL_TEXTURE_2D_ARRAY);
        glTextureStorage3D(textureArrayId, 1, GL_RGBA8, TEX_W, TEX_H, MAX_ENTITY_TEXTURES);
        glTextureParameteri(textureArrayId, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(textureArrayId, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(textureArrayId, GL_TEXTURE_MAX_LEVEL, 0);
    }

    /**
     * Returns layer index for this texture key. 
     * If not cached, queues it for upload on the render thread.
     */
    public int getOrUpload(String locationKey) {
        Integer layer = texToLayer.get(locationKey);
        if (layer != null) return layer;
        
        synchronized (texToLayer) {
            if (texToLayer.containsKey(locationKey)) return texToLayer.get(locationKey);
            if (nextLayer >= MAX_ENTITY_TEXTURES) return 0;
            
            int newLayer = nextLayer++;
            texToLayer.put(locationKey, newLayer);
            uploadQueue.add(locationKey);
            return newLayer;
        }
    }

    /** Must be called on the RENDER THREAD to perform queued GL uploads. */
    public void processUploads() {
        String locationKey;
        while ((locationKey = uploadQueue.poll()) != null) {
            uploadTexture(locationKey, texToLayer.get(locationKey));
        }
    }

    private void uploadTexture(String locationKey, int layer) {
        try {
            var textureManager = Minecraft.getInstance().getTextureManager();
            if (textureManager == null) return;

            Object identifier = createIdentifier(locationKey);
            if (identifier == null) return;

            // Find getTexture method
            Method getTextureMethod = null;
            for (Method m : textureManager.getClass().getMethods()) {
                if ((m.getName().equals("method_4615") || m.getName().equals("getTexture")) &&
                    m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(identifier.getClass())) {
                    getTextureMethod = m;
                    break;
                }
            }
            if (getTextureMethod == null) return;

            Object abstractTexture = getTextureMethod.invoke(textureManager, identifier);
            if (abstractTexture == null) return;

            // Get the OpenGL texture ID
            // Approach 1: look for getId / method_4625 by name
            int glTexId = -1;
            for (Method m : abstractTexture.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                    String n = m.getName();
                    if (n.equals("getId") || n.equals("method_4625") || n.equals("getGlId") || n.equals("getGlTexture")) {
                        try { glTexId = (int) m.invoke(abstractTexture); break; } catch (Exception ignored) {}
                    }
                }
            }

            // Approach 2 (bulletproof fallback): bind the texture, then read the current GL binding
            // Works regardless of obfuscated method names
            if (glTexId <= 0) {
                for (Method m : abstractTexture.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 &&
                        (m.getName().equals("bind") || m.getName().equals("method_4620") || m.getName().equals("bindTexture"))) {
                        try { m.invoke(abstractTexture); break; } catch (Exception ignored) {}
                    }
                }
                glTexId = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            }

            if (glTexId <= 0) {
                if (Rentities.IS_DEBUG)
                    Rentities.LOGGER.error("Could not get GL texture ID for: " + locationKey);
                return;
            }

            // Bind source texture and read its actual dimensions from OpenGL
            org.lwjgl.opengl.GL11.glBindTexture(GL_TEXTURE_2D, glTexId);
            int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);

            if (w <= 0 || h <= 0) return;

            int uploadW = Math.min(w, TEX_W);
            int uploadH = Math.min(h, TEX_H);

            layerToWidth.put(layer, uploadW);
            layerToHeight.put(layer, uploadH);

            // Copy directly from Minecraft's GL texture into our array — no NativeImage needed
            glCopyImageSubData(
                glTexId, GL_TEXTURE_2D, 0, 0, 0, 0,
                textureArrayId, GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer,
                uploadW, uploadH, 1
            );

        } catch (Exception e) {
            if (Rentities.IS_DEBUG)
                Rentities.LOGGER.error("Failed to upload texture " + locationKey + ": " + e.getMessage());
        }
    }

    private Object createIdentifier(String key) throws Exception {
        Class<?> idCls = Class.forName("net.minecraft.class_2960");
        // In 1.21+, Identifier.of(String) is the standard way.
        // Intermediary names: method_60655 or method_60654
        for (Method m : idCls.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && 
                m.getParameterCount() == 1 && 
                m.getParameterTypes()[0] == String.class && 
                m.getReturnType() == idCls) {
                return m.invoke(null, key);
            }
        }
        // Fallback to constructor
        java.lang.reflect.Constructor<?> ctor = idCls.getConstructor(String.class);
        return ctor.newInstance(key);
    }

    public int getTextureArrayId() { return textureArrayId; }

    public int getLayerWidth(int layer) {
        return layerToWidth.getOrDefault(layer, 64);
    }

    public int getLayerHeight(int layer) {
        return layerToHeight.getOrDefault(layer, 64);
    }

    // File: nvidium_entity_texture_cache.bin
    // Format:
    //   [int magic=0xECA17EX] [int version=1]
    //   [int count]
    //   per entry:
    //     [int keyLen] [byte[] key utf8]
    //     [int layer] [int width] [int height]
    //     [byte[] RGBA pixels — width*height*4 bytes]

    private static final int TEX_CACHE_MAGIC   = 0xECA17EBC;
    private static final int TEX_CACHE_VERSION = 1;

    private static java.io.File texCacheFile = null;

    private static java.io.File getTextureCacheFile() {
        if (texCacheFile == null) {
            texCacheFile = new java.io.File(
                net.minecraft.client.Minecraft.getInstance().gameDirectory,
                "rentities_entity_texture_cache.bin");
        }
        return texCacheFile;
    }

    public static boolean textureCacheExists() { return getTextureCacheFile().exists(); }
    public static void deleteTextureCache()    { getTextureCacheFile().delete(); }

    /**
     * Reads every uploaded layer back from the GPU and saves to disk.
     * Must be called on the render thread. Runs in a daemon thread for disk IO.
     */
    public void saveTextureCache() {
        // Snapshot the layer map before handing to thread
        Map<String, Integer> snapshot;
        synchronized (texToLayer) { snapshot = new java.util.HashMap<>(texToLayer); }

        // Read all pixel data on render thread first (GL calls must stay here)
        Map<String, byte[]> pixelMap = new java.util.LinkedHashMap<>();
        Map<String, int[]>  dimMap   = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
            int layer = e.getValue();
            int w = layerToWidth.getOrDefault(layer, 0);
            int h = layerToHeight.getOrDefault(layer, 0);
            if (w <= 0 || h <= 0) continue;

            // Read one layer from the texture array into a byte buffer
            org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush();
            try {
                java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
                // glGetTextureSubImage: DSA read of a single array layer
                org.lwjgl.opengl.GL45.glGetTextureSubImage(
                    textureArrayId,
                    0,        // mip level
                    0, 0, layer,  // x, y, z (layer)
                    w, h, 1,      // width, height, depth
                    GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    buf);
                byte[] pixels = new byte[w * h * 4];
                buf.get(pixels);
                org.lwjgl.system.MemoryUtil.memFree(buf);
                pixelMap.put(e.getKey(), pixels);
                dimMap.put(e.getKey(), new int[]{w, h, layer});
            } catch (Exception ex) {
                Rentities.LOGGER.warn("[TexCache] Failed to read layer {}: {}", layer, ex.getMessage());
            } finally {
                stack.pop();
            }
        }

        // Disk write on daemon thread — no GL calls here
        Thread t = new Thread(() -> {
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(getTextureCacheFile())))) {
                out.writeInt(TEX_CACHE_MAGIC);
                out.writeInt(TEX_CACHE_VERSION);
                out.writeInt(pixelMap.size());
                for (Map.Entry<String, byte[]> pe : pixelMap.entrySet()) {
                    byte[] key = pe.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    int[] dim  = dimMap.get(pe.getKey());
                    out.writeInt(key.length); out.write(key);
                    out.writeInt(dim[2]); // layer
                    out.writeInt(dim[0]); // width
                    out.writeInt(dim[1]); // height
                    out.write(pe.getValue()); // raw RGBA
                }
                Rentities.LOGGER.info("[TexCache] Saved {} textures", pixelMap.size());
            } catch (Exception ex) {
                Rentities.LOGGER.error("[TexCache] Save failed: {}", ex.getMessage());
            }
        }, "rentities-tex-cache-save");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Loads texture data from disk and uploads directly to the GL texture array.
     * Must be called on the render thread.
     * Returns true on success.
     */
    public boolean loadTextureCache() {
        java.io.File f = getTextureCacheFile();
        if (!f.exists()) return false;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            if (in.readInt() != TEX_CACHE_MAGIC)   { Rentities.LOGGER.warn("[TexCache] Bad magic"); return false; }
            if (in.readInt() != TEX_CACHE_VERSION)  { Rentities.LOGGER.warn("[TexCache] Version mismatch"); return false; }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int kLen = in.readInt();
                byte[] kBytes = new byte[kLen]; in.readFully(kBytes);
                String key = new String(kBytes, java.nio.charset.StandardCharsets.UTF_8);
                int layer = in.readInt();
                int w     = in.readInt();
                int h     = in.readInt();
                byte[] pixels = new byte[w * h * 4];
                in.readFully(pixels);

                // Ensure layer slot is reserved in map
                synchronized (texToLayer) {
                    texToLayer.put(key, layer);
                    layerToWidth.put(layer, w);
                    layerToHeight.put(layer, h);
                    if (layer >= nextLayer) nextLayer = layer + 1;
                }

                // Upload to GL texture array
                java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(pixels.length);
                buf.put(pixels).flip();
                glTextureSubImage3D(textureArrayId,
                    0,           // mip
                    0, 0, layer, // x, y, z
                    w, h, 1,     // w, h, depth
                    GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);
                org.lwjgl.system.MemoryUtil.memFree(buf);
            }
            Rentities.LOGGER.info("[TexCache] Loaded {} textures", count);
            return true;
        } catch (Exception e) {
            Rentities.LOGGER.error("[TexCache] Load failed: {}", e.getMessage());
            return false;
        }
    }

    public void delete() {
        glDeleteTextures(textureArrayId);
        texToLayer.clear();
    }
}
