package dev.mira.worldgen;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Mob;

/** Takes copies before export so the worker never queries Bukkit's lazy registry caches. */
record ExportRegistries(Map<Material, String> blocks, Set<NamespacedKey> mobs) {
    static ExportRegistries snapshot() {
        var blocks = new EnumMap<Material, String>(Material.class);
        for (Material material : Material.values()) {
            if (!material.isLegacy() && material.isBlock()) {
                blocks.put(material, material.getKey().toString());
            }
        }
        var mobs = new HashSet<NamespacedKey>();
        for (var entity : Registry.ENTITY_TYPE) {
            Class<?> entityClass = entity.getEntityClass();
            if (entityClass != null && Mob.class.isAssignableFrom(entityClass)) {
                mobs.add(Registry.ENTITY_TYPE.getKeyOrThrow(entity));
            }
        }
        return new ExportRegistries(Map.copyOf(blocks), Set.copyOf(mobs));
    }

    String blockId(String name) {
        Material material = Material.matchMaterial(name);
        return material == null ? null : blocks.get(material);
    }
}
