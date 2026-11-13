package dev.mira.worldgen;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.generator.LimitedRegion;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class NativeFootprints {
    static final int LOCAL_SPAN = 48;
    private static final int START_RADIUS = 8;
    private static final NativeFootprints EMPTY = new NativeFootprints(null, Set.of());
    private static final Lookup UNRESTRICTED = (minX, minZ, maxX, maxZ) -> false;

    private final Access access;
    private final Set<Object> giantGroups;

    private NativeFootprints(Access access, Set<Object> giantGroups) {
        this.access = access;
        this.giantGroups = giantGroups;
    }

    static NativeFootprints empty() {
        return EMPTY;
    }

    static NativeFootprints load(Set<String> ids) throws IOException {
        try {
            Access access = new Access();
            var groups = new HashSet<>();
            for (String id : ids) {
                NamespacedKey key = NamespacedKey.fromString(id);
                var structure = key == null ? null : Registry.STRUCTURE.get(key);
                if (structure == null) {
                    throw new IOException("No se encontró la estructura de generación «" + id + "»");
                }
                groups.add(access.nativeStructure.invoke(structure));
            }
            return new NativeFootprints(access, Set.copyOf(groups));
        } catch (ReflectiveOperationException ex) {
            throw new IOException("No se pueden consultar las huellas de estructuras en esta versión del servidor", ex);
        }
    }

    Lookup forRegion(LimitedRegion region, int centerX, int centerZ) {
        if (giantGroups.isEmpty()) {
            return UNRESTRICTED;
        }
        var occupiedChunks = new HashMap<Long, Boolean>();
        return (minX, minZ, maxX, maxZ) -> {
            for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
                for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                    long key = (chunkX & 0xffffffffL) | (long) chunkZ << 32;
                    Boolean occupied = occupiedChunks.get(key);
                    if (occupied == null) {
                        occupied = hasLargeStart(region, centerX, centerZ, chunkX, chunkZ);
                        occupiedChunks.put(key, occupied);
                    }
                    if (occupied) {
                        return true;
                    }
                }
            }
            return false;
        };
    }

    private boolean hasLargeStart(LimitedRegion region, int centerX, int centerZ, int x, int z) {
        try {
            Object handle = access.regionHandle.invoke(region);
            Object chunk = access.getChunk.invoke(handle, x, z);
            var references = (Map<?, ?>) access.chunkReferences.invoke(chunk);
            for (var reference : references.entrySet()) {
                Object structure = reference.getKey();
                if (!giantGroups.contains(structure)) {
                    continue;
                }
                for (Object startPosition : (Iterable<?>) reference.getValue()) {
                    long position = (Long) startPosition;
                    int startX = (int) position;
                    int startZ = (int) (position >> 32);
                    // Neighboring chunks may reference starts outside this generation worker's readable radius.
                    if (Math.abs(startX - centerX) > START_RADIUS || Math.abs(startZ - centerZ) > START_RADIUS) {
                        return true;
                    }
                    Object startChunk = access.getStartChunk.invoke(handle, startX, startZ, access.startsStatus, true);
                    Object start = access.chunkStart.invoke(startChunk, structure);
                    if (start != null && Boolean.TRUE.equals(access.validStart.invoke(start))) {
                        Object box = access.startBounds.invoke(start);
                        if ((int) access.width.invoke(box) > LOCAL_SPAN || (int) access.depth.invoke(box) > LOCAL_SPAN) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("No se pudo comprobar la huella de las estructuras", ex);
        }
    }

    @FunctionalInterface
    interface Lookup {
        boolean intersects(int minX, int minZ, int maxX, int maxZ);
    }

    private static final class Access {
        final Method nativeStructure;
        final Method regionHandle;
        final Method getChunk;
        final Method getStartChunk;
        final Object startsStatus;
        final Method chunkReferences;
        final Method chunkStart;
        final Method validStart;
        final Method startBounds;
        final Method width;
        final Method depth;

        Access() throws ReflectiveOperationException {
            Class<?> craftStructure = Class.forName("org.bukkit.craftbukkit.generator.structure.CraftStructure");
            Class<?> craftRegion = Class.forName("org.bukkit.craftbukkit.generator.CraftLimitedRegion");
            Class<?> worldgen = Class.forName("net.minecraft.server.level.WorldGenRegion");
            Class<?> chunk = Class.forName("net.minecraft.world.level.chunk.ChunkAccess");
            Class<?> status = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus");
            Class<?> structure = Class.forName("net.minecraft.world.level.levelgen.structure.Structure");
            Class<?> start = Class.forName("net.minecraft.world.level.levelgen.structure.StructureStart");
            Class<?> box = Class.forName("net.minecraft.world.level.levelgen.structure.BoundingBox");
            nativeStructure = craftStructure.getMethod("getHandle");
            regionHandle = craftRegion.getMethod("getHandle");
            getChunk = worldgen.getMethod("getChunk", int.class, int.class);
            getStartChunk = worldgen.getMethod("getChunk", int.class, int.class, status, boolean.class);
            startsStatus = status.getField("STRUCTURE_STARTS").get(null);
            chunkReferences = chunk.getMethod("getAllReferences");
            chunkStart = chunk.getMethod("getStartForStructure", structure);
            validStart = start.getMethod("isValid");
            startBounds = start.getMethod("getBoundingBox");
            width = box.getMethod("getXSpan");
            depth = box.getMethod("getZSpan");
        }
    }
}
