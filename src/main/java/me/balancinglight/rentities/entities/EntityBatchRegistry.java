package me.balancinglight.rentities.entities;

import net.minecraft.world.entity.EntityType;
import java.util.HashMap;
import java.util.Map;

public final class EntityBatchRegistry {

    private static final Map<EntityType<?>, EntityAnimationCategory> REGISTRY = new HashMap<>();

    static {
        reg(EntityType.ARMOR_STAND,         EntityAnimationCategory.BIPED);
        reg(EntityType.ZOMBIE,              EntityAnimationCategory.BIPED);
        reg(EntityType.SKELETON,            EntityAnimationCategory.BIPED);
        reg(EntityType.WITHER_SKELETON,     EntityAnimationCategory.BIPED);
        reg(EntityType.ZOMBIFIED_PIGLIN,    EntityAnimationCategory.BIPED);
        reg(EntityType.PIGLIN,              EntityAnimationCategory.BIPED);
        reg(EntityType.PIGLIN_BRUTE,        EntityAnimationCategory.BIPED);
        reg(EntityType.HUSK,                EntityAnimationCategory.BIPED);
        reg(EntityType.DROWNED,             EntityAnimationCategory.BIPED);
        reg(EntityType.STRAY,               EntityAnimationCategory.BIPED);
        reg(EntityType.BOGGED,              EntityAnimationCategory.BIPED);
        reg(EntityType.VINDICATOR,          EntityAnimationCategory.BIPED);
        reg(EntityType.PILLAGER,            EntityAnimationCategory.BIPED);
        reg(EntityType.EVOKER,              EntityAnimationCategory.BIPED);
        reg(EntityType.ILLUSIONER,          EntityAnimationCategory.BIPED);
        reg(EntityType.WITCH,               EntityAnimationCategory.BIPED);
        reg(EntityType.VILLAGER,            EntityAnimationCategory.BIPED);
        reg(EntityType.WANDERING_TRADER,    EntityAnimationCategory.BIPED);
        reg(EntityType.ZOMBIE_VILLAGER,     EntityAnimationCategory.BIPED);
        reg(EntityType.IRON_GOLEM,          EntityAnimationCategory.BIPED);
        reg(EntityType.SNOW_GOLEM,          EntityAnimationCategory.BIPED);
        reg(EntityType.ENDERMAN,            EntityAnimationCategory.BIPED);
        reg(EntityType.CREAKING,            EntityAnimationCategory.BIPED);
        // Player handled separately via player batching path

        reg(EntityType.COW,                 EntityAnimationCategory.QUADRUPED);
        reg(EntityType.PIG,                 EntityAnimationCategory.QUADRUPED);
        reg(EntityType.SHEEP,               EntityAnimationCategory.QUADRUPED);
        reg(EntityType.MOOSHROOM,           EntityAnimationCategory.QUADRUPED);
        reg(EntityType.WOLF,                EntityAnimationCategory.QUADRUPED);
        reg(EntityType.CAT,                 EntityAnimationCategory.QUADRUPED);
        reg(EntityType.OCELOT,              EntityAnimationCategory.QUADRUPED);
        reg(EntityType.FOX,                 EntityAnimationCategory.QUADRUPED);
        reg(EntityType.HOGLIN,              EntityAnimationCategory.QUADRUPED);
        reg(EntityType.ZOGLIN,              EntityAnimationCategory.QUADRUPED);
        reg(EntityType.DONKEY,              EntityAnimationCategory.QUADRUPED);
        reg(EntityType.MULE,                EntityAnimationCategory.QUADRUPED);
        reg(EntityType.SKELETON_HORSE,      EntityAnimationCategory.QUADRUPED);
        reg(EntityType.ZOMBIE_HORSE,        EntityAnimationCategory.QUADRUPED);
        reg(EntityType.LLAMA,               EntityAnimationCategory.QUADRUPED);
        reg(EntityType.TRADER_LLAMA,        EntityAnimationCategory.QUADRUPED);
        reg(EntityType.PANDA,               EntityAnimationCategory.QUADRUPED);
        reg(EntityType.POLAR_BEAR,          EntityAnimationCategory.QUADRUPED);
        reg(EntityType.RABBIT,              EntityAnimationCategory.QUADRUPED);

        reg(EntityType.HORSE,               EntityAnimationCategory.HORSE);
        reg(EntityType.CAMEL,               EntityAnimationCategory.HORSE);

        reg(EntityType.CHICKEN,             EntityAnimationCategory.BIRD);
        reg(EntityType.PARROT,              EntityAnimationCategory.BIRD);
        reg(EntityType.BAT,                 EntityAnimationCategory.BIRD);

        reg(EntityType.SPIDER,              EntityAnimationCategory.ARTHROPOD);
        reg(EntityType.CAVE_SPIDER,         EntityAnimationCategory.ARTHROPOD);

        reg(EntityType.BEE,                 EntityAnimationCategory.INSECT);

        reg(EntityType.SILVERFISH,          EntityAnimationCategory.WORM);
        reg(EntityType.ENDERMITE,           EntityAnimationCategory.WORM);

        reg(EntityType.COD,                 EntityAnimationCategory.FISH);
        reg(EntityType.SALMON,              EntityAnimationCategory.FISH);
        reg(EntityType.TROPICAL_FISH,       EntityAnimationCategory.FISH);
        reg(EntityType.PUFFERFISH,          EntityAnimationCategory.FISH);

        reg(EntityType.AXOLOTL,             EntityAnimationCategory.AQUATIC_LEGS);

        reg(EntityType.DOLPHIN,             EntityAnimationCategory.SWIMMING);
        reg(EntityType.SQUID,               EntityAnimationCategory.SWIMMING);
        reg(EntityType.GLOW_SQUID,          EntityAnimationCategory.SWIMMING);
        reg(EntityType.TADPOLE,             EntityAnimationCategory.SWIMMING);

        reg(EntityType.SLIME,               EntityAnimationCategory.SLIME);
        reg(EntityType.MAGMA_CUBE,          EntityAnimationCategory.SLIME);

        reg(EntityType.ALLAY,               EntityAnimationCategory.FLOATING);
        reg(EntityType.VEX,                 EntityAnimationCategory.FLOATING);
        reg(EntityType.PHANTOM,             EntityAnimationCategory.FLOATING);

        reg(EntityType.BLAZE,               EntityAnimationCategory.FLOATING_SPINNING);
        reg(EntityType.BREEZE,              EntityAnimationCategory.FLOATING_SPINNING);

        reg(EntityType.GHAST,               EntityAnimationCategory.GHAST);

        reg(EntityType.SHULKER,             EntityAnimationCategory.SHULKER);

        reg(EntityType.STRIDER,             EntityAnimationCategory.STRIDER);

        reg(EntityType.FROG,                EntityAnimationCategory.FROG);

        reg(EntityType.GOAT,                EntityAnimationCategory.GOAT);

        reg(EntityType.SNIFFER,             EntityAnimationCategory.SNIFFER);

        reg(EntityType.ARMADILLO,           EntityAnimationCategory.ARMADILLO);

        reg(EntityType.CREEPER,             EntityAnimationCategory.CREEPER);

        reg(EntityType.ENDER_DRAGON,        EntityAnimationCategory.CPU_ANIMATED);
        reg(EntityType.WARDEN,              EntityAnimationCategory.CPU_ANIMATED);
        reg(EntityType.RAVAGER,             EntityAnimationCategory.CPU_ANIMATED);
        reg(EntityType.WITHER,              EntityAnimationCategory.CPU_ANIMATED);
    }

    private static final Map<EntityType<?>, Integer> TYPE_INDEX_MAP = new HashMap<>();
    private static int nextTypeIndex = 0;

    public static int getEntityTypeIndex(EntityType<?> type) {
        if (type == null) return 0;
        return TYPE_INDEX_MAP.computeIfAbsent(type, t -> nextTypeIndex++);
    }

    public static java.util.Set<EntityType<?>> REGISTRY_TYPES() {
        return REGISTRY.keySet();
    }

    private static void reg(EntityType<?> type, EntityAnimationCategory category) {
        REGISTRY.put(type, category);
    }

    public static EntityAnimationCategory getCategory(EntityType<?> type) {
        return REGISTRY.getOrDefault(type, EntityAnimationCategory.CPU_ANIMATED);
    }

    public static boolean hasZombieArms(EntityType<?> type) {
        return type == EntityType.ZOMBIE        || type == EntityType.HUSK          ||
               type == EntityType.DROWNED       || type == EntityType.ZOMBIE_VILLAGER||
               type == EntityType.SKELETON      || type == EntityType.STRAY         ||
               type == EntityType.BOGGED        || type == EntityType.WITHER_SKELETON||
               type == EntityType.ZOMBIFIED_PIGLIN;
    }

    public static boolean isGpuBatchable(EntityType<?> type) {
        return getCategory(type) != EntityAnimationCategory.CPU_ANIMATED;
    }
}

