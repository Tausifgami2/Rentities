package me.balancinglight.rentities.entities;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class EntityFactory {

    private static final Map<EntityType<?>, Entity> DUMMY_CACHE = new HashMap<>();

    public static Entity getOrCreateDummy(EntityType<?> type) {
        if (type == null) return null;
        return DUMMY_CACHE.computeIfAbsent(type, EntityFactory::createDummy);
    }

    private static Entity createDummy(EntityType<?> type) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;

        // Try every possible 'create' method on EntityType
        for (Method m : type.getClass().getMethods()) {
            if (m.getName().equals("create") || m.getName().equals("method_5883")) {
                try {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(level.getClass())) {
                        return (Entity) m.invoke(type, level);
                    }
                    if (m.getParameterCount() == 2 && m.getParameterTypes()[0].isAssignableFrom(level.getClass())) {
                        // Likely Level + EntitySpawnReason (enum)
                        Object[] args = new Object[2];
                        args[0] = level;
                        args[1] = m.getParameterTypes()[1].getEnumConstants()[0]; // Just use first enum constant
                        return (Entity) m.invoke(type, args);
                    }
                } catch (Exception ignored) {}
            }
        }

        try {
            // Strategy 2: Reflectively find the factory field (field_18934 in EntityType)
            var factoryField = EntityType.class.getDeclaredField("field_18934");
            factoryField.setAccessible(true);
            Object factory = factoryField.get(type);
            if (factory != null) {
                for (Method m : factory.getClass().getMethods()) {
                    if (m.getName().equals("create") || m.getName().equals("method_5883")) {
                        if (m.getParameterCount() == 2) {
                            return (Entity) m.invoke(factory, type, level);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }
}

