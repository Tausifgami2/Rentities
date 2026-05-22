package me.balancinglight.rentities.entities;

public enum EntityAnimationCategory {
    BIPED(0),
    QUADRUPED(1),
    HORSE(2),
    BIRD(3),
    ARTHROPOD(4),
    INSECT(5),
    WORM(6),
    FISH(7),
    AQUATIC_LEGS(8),
    SWIMMING(9),
    SLIME(10),
    FLOATING(11),
    FLOATING_SPINNING(12),
    GHAST(13),
    SHULKER(14),
    STRIDER(15),
    FROG(16),
    GOAT(17),
    SNIFFER(18),
    ARMADILLO(19),
    CREEPER(20),
    CPU_ANIMATED(-1);

    public final int glslId;

    EntityAnimationCategory(int glslId) {
        this.glslId = glslId;
    }
}

