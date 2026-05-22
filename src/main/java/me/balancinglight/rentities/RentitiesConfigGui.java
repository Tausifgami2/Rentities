package me.balancinglight.rentities;

import me.balancinglight.rentities.entities.EntityBatchRenderer;
import me.balancinglight.rentities.entities.EntityMeshBaker;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


public class RentitiesConfigGui implements ConfigEntryPoint {

    private static final RentitiesConfigStore store = new RentitiesConfigStore();
    private final StorageEventHandler saveConfig = store::save;
    private final StorageEventHandler noSave = () -> {};

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var page = builder.createOptionPage()
                .setName(Component.literal("Rentities"));

        // Master toggle — takes effect immediately, no reload needed
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:entity_batching"))
                .setName(Component.literal("GPU Entity Batching"))
                .setTooltip(Component.literal(
                    "Renders entities using GPU instancing. " +
                    "Batches thousands of entities into a single draw call. " +
                    "Requires an NVIDIA GPU."))
                .setDefaultValue(true)
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(
                    value -> {
                        store.getData().entity_batching_enabled = value;
                        store.save();
                        Rentities.IS_ENABLED = Rentities.IS_COMPATIBLE && value;
                        if (!value) {
                            // Disable: delete renderer so entities fall back to vanilla immediately
                            if (EntityBatchRenderer.INSTANCE != null) {
                                EntityBatchRenderer.INSTANCE.delete();
                            }
                        } else {
                            // Enable: create renderer if not already running
                            if (EntityBatchRenderer.INSTANCE == null) {
                                new EntityBatchRenderer();
                            }
                        }
                    },
                    () -> store.getData().entity_batching_enabled
                )
                .setStorageHandler(noSave) // already saved manually above
        );

        // Scan mode — needs reload to take effect (changes what happens at world load)
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:scan_mode"))
                .setName(Component.literal("Entity Scan Mode"))
                .setTooltip(Component.literal(
                    "ON: scans entity meshes and saves to disk. " +
                    "OFF: loads from saved cache. " +
                    "Rejoin your world after changing this."))
                .setDefaultValue(true)
                .setImpact(OptionImpact.VARIES)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(
                    value -> {
                        store.getData().entity_scan_mode = value;
                        store.save();
                    },
                    () -> store.getData().entity_scan_mode
                )
                .setStorageHandler(noSave)
        );

        // Cache status — read only
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:cache_status"))
                .setName(Component.literal("Mesh Cache: " +
                    (EntityMeshBaker.cacheExists() ? "§aSaved" : "§cNot saved")))
                .setTooltip(Component.literal(
                    "Shows whether a mesh cache file exists on disk."))
                .setDefaultValue(false)
                .setImpact(OptionImpact.VARIES)
                .setEnabledProvider(c -> false)
                .setBinding(v -> {}, () -> EntityMeshBaker.cacheExists())
                .setStorageHandler(noSave)
        );

        // Delete cache
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:delete_cache"))
                .setName(Component.literal("Delete Mesh Cache"))
                .setTooltip(Component.literal(
                    "Deletes the saved mesh cache. " +
                    "Re-enable Scan Mode to rebuild it."))
                .setDefaultValue(false)
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(c -> EntityMeshBaker.cacheExists())
                .setBinding(
                    value -> { if (value) { EntityMeshBaker.deleteCache(); EntityMeshBaker.deleteTextureCache(); } },
                    () -> false
                )
                .setStorageHandler(noSave)
        );

        builder.registerOwnModOptions()
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x76B900))
                .addPage(page);
    }
}
