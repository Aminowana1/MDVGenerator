package dev.mira.worldgen;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureSpawnEvent;

import java.lang.reflect.Method;

/** Checks the native terrain column at a large structure start without loading any chunks. */
final class NativeGround implements Listener {
    static final String TAG = "mdv_requires_ground";

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStart(AsyncStructureSpawnEvent event) {
        int y = (int) event.getBoundingBox().getMinY();
        NamespacedKey key = Registry.STRUCTURE.getKeyOrThrow(event.getStructure());
        if (!allowed(event.getWorld(), key, event.getChunkX() << 4, event.getChunkZ() << 4, y)) {
            event.setCancelled(true);
        }
    }

    static boolean allowed(World world, NamespacedKey id, int x, int z, int supportY) {
        if (!id.getNamespace().startsWith("gm_")) {
            return true;
        }
        var tag = RegistryKey.STRUCTURE.tagKey(new NamespacedKey(id.getNamespace(), TAG));
        if (!Registry.STRUCTURE.hasTag(tag) || !Registry.STRUCTURE.getTag(tag).contains(TypedKey.create(RegistryKey.STRUCTURE, id))) {
            return true;
        }
        if (supportY < world.getMinHeight() || supportY >= world.getMaxHeight()) {
            return false;
        }
        try {
            Object level = Noise.WORLD_HANDLE.invoke(world);
            Object source = Noise.CHUNK_SOURCE.invoke(level);
            Object generator = Noise.GENERATOR.invoke(source);
            Object state = Noise.RANDOM_STATE.invoke(source);
            Object column = Noise.BASE_COLUMN.invoke(generator, x, z, level, state);
            return (boolean) Noise.SOLID.invoke(Noise.BLOCK.invoke(column, supportY));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("No se pudo comprobar el suelo debajo de «" + id + "».", ex);
        }
    }

    private static final class Noise {
        private static final Class<?> LEVEL = type("net.minecraft.server.level.ServerLevel");
        private static final Class<?> SOURCE = type("net.minecraft.server.level.ServerChunkCache");
        private static final Class<?> RANDOM = type("net.minecraft.world.level.levelgen.RandomState");
        private static final Method WORLD_HANDLE = method(type("org.bukkit.craftbukkit.CraftWorld"), "getHandle");
        private static final Method CHUNK_SOURCE = method(LEVEL, "getChunkSource");
        private static final Method GENERATOR = method(SOURCE, "getGenerator");
        private static final Method RANDOM_STATE = method(SOURCE, "randomState");
        private static final Method BASE_COLUMN = method(type("net.minecraft.world.level.chunk.ChunkGenerator"),
                "getBaseColumn", int.class, int.class, type("net.minecraft.world.level.LevelHeightAccessor"), RANDOM);
        private static final Method BLOCK = method(type("net.minecraft.world.level.NoiseColumn"), "getBlock", int.class);
        private static final Method SOLID = method(type("net.minecraft.world.level.block.state.BlockState"), "isSolid");

        private static Class<?> type(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("No se encontró el generador de Purpur 1.21.6.", ex);
            }
        }

        private static Method method(Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("No se encontró el generador de Purpur 1.21.6.", ex);
            }
        }
    }
}
