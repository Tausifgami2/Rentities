package me.balancinglight.rentities;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rentities implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("rentities");
    public static final boolean IS_DEBUG = System.getProperty("rentities.debug") != null;

    public static volatile boolean IS_ENABLED    = false;
    public static volatile boolean IS_COMPATIBLE = false;

    public static RentitiesConfig config = RentitiesConfig.loadOrCreate();

    @Override
    public void onInitializeClient() {
        LOGGER.info("Rentities initializing...");
        // when the first world loads and GL is ready.
    }

    public static void checkAndEnable() {
        if (!IS_COMPATIBLE) {
            // First time — detect GPU
            String vendor = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            LOGGER.info("GPU: {} — {}", vendor, renderer);
            boolean isNvidia = vendor != null && vendor.toUpperCase().contains("NVIDIA");
            IS_COMPATIBLE = isNvidia;
            if (!isNvidia) {
                LOGGER.warn("Rentities requires an NVIDIA GPU. Detected: {}. Disabled.", vendor);
                IS_ENABLED = false;
                return;
            }
        }
        // Always sync IS_ENABLED from config — covers toggle on/off via settings
        IS_ENABLED = IS_COMPATIBLE && config.entity_batching_enabled;
    }
}

