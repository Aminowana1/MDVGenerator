package dev.mira.worldgen;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.event.world.AsyncStructureSpawnEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class NativeTreeGuard {
    private static final int START_RADIUS = 8;
    private static final int CACHE_CAPACITY = 4096;

    private final World world;
    private final Set<String> treeIds;
    private final long seed;
    private final Access access;
    private final Object serverLevel;
    private final Object dimension;
    private final Object registries;
    private final Object generator;
    private final Object biomeSource;
    private final Object generatorState;
    private final Object randomState;
    private final Object templates;
    private final List<Giant> giants;
    private final ConcurrentHashMap<Start, Optional<Footprint>> candidates = new ConcurrentHashMap<>();

    NativeTreeGuard(World world, Set<String> treeIds, Set<String> giantIds) throws ReflectiveOperationException {
        this.world = world;
        this.treeIds = treeIds;
        this.seed = world.getSeed();
        this.access = new Access();
        this.serverLevel = access.worldHandle.invoke(world);
        Object chunkSource = access.chunkSource.invoke(serverLevel);
        this.dimension = access.dimension.invoke(serverLevel);
        this.registries = access.registries.invoke(serverLevel);
        this.generator = access.generator.invoke(chunkSource);
        this.biomeSource = access.biomeSource.invoke(generator);
        this.generatorState = access.generatorState.invoke(chunkSource);
        this.randomState = access.randomState.invoke(chunkSource);
        this.templates = access.templates.invoke(serverLevel);
        this.giants = findGiants(giantIds);
    }

    private List<Giant> findGiants(Set<String> giantIds) throws ReflectiveOperationException {
        var result = new ArrayList<Giant>();
        for (Object setHolder : (List<?>) access.structureSets.invoke(generatorState)) {
            Object setKey = ((Optional<?>) access.holderKey.invoke(setHolder)).orElse(null);
            if (setKey == null) {
                continue;
            }
            String id = access.keyLocation.invoke(setKey).toString();
            if (!giantIds.contains(id)) {
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(id);
            var bukkit = Registry.STRUCTURE.get(key);
            if (bukkit == null) {
                throw new IllegalStateException("No se encontró la estructura de generación «" + id + "»");
            }
            Object holder = access.structureHolder.invoke(bukkit);
            Object structure = access.holderValue.invoke(holder);
            Object validBiomes = access.structureBiomes.invoke(structure);
            Predicate<Object> biomeFilter = biome -> {
                try {
                    return (boolean) access.biomeContains.invoke(validBiomes, biome);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("No se pudo comprobar el bioma de «" + id + "»", ex);
                }
            };
            Object placement = access.placement.invoke(access.holderValue.invoke(setHolder));
            result.add(new Giant(key, treeIds.contains(id), setKey, placement, holder, structure, biomeFilter));
        }
        return List.copyOf(result);
    }

    boolean shouldCancel(AsyncStructureSpawnEvent event) throws ReflectiveOperationException {
        String id = Registry.STRUCTURE.getKeyOrThrow(event.getStructure()).toString();
        if (!treeIds.contains(id)) {
            return false;
        }
        var bounds = event.getBoundingBox();
        var tree = new Footprint((int) bounds.getMinX(), (int) bounds.getMinZ(),
                (int) bounds.getMaxX(), (int) bounds.getMaxZ(), (int) bounds.getMinY());
        var treeStart = new Start(id, event.getChunkX(), event.getChunkZ());
        long priority = priority(treeStart);
        for (Giant giant : giants) {
            String giantId = giant.id().toString();
            for (int x = tree.minChunkX() - START_RADIUS; x <= tree.maxChunkX() + START_RADIUS; x++) {
                for (int z = tree.minChunkZ() - START_RADIUS; z <= tree.maxChunkZ() + START_RADIUS; z++) {
                    var otherStart = new Start(giantId, x, z);
                    if (otherStart.equals(treeStart)
                            || (giant.tree() && tree.isLarge() && !outranks(otherStart, treeStart, priority))) {
                        continue;
                    }
                    Footprint other = candidate(giant, otherStart);
                    if (other != null && other.isLarge() && tree.sharesChunk(other)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Footprint candidate(Giant giant, Start start) throws ReflectiveOperationException {
        Optional<Footprint> cached = candidates.get(start);
        if (cached != null) {
            return cached.orElse(null);
        }
        if (candidates.size() >= CACHE_CAPACITY) {
            candidates.clear();
        }
        if (!(boolean) access.isStructureChunk.invoke(giant.placement(), generatorState, start.x(), start.z(), giant.setKey())) {
            candidates.putIfAbsent(start, Optional.empty());
            return null;
        }
        // Evaluate the same vanilla start without loading a chunk or publishing a second Bukkit event.
        Object chunkPos = access.chunkPos.newInstance(start.x(), start.z());
        Object result = access.generate.invoke(giant.structure(), giant.holder(), dimension, registries, generator,
                biomeSource, randomState, templates, seed, chunkPos, 0, serverLevel, giant.biomes());
        Footprint footprint = null;
        if ((boolean) access.validStart.invoke(result)) {
            Object box = access.startBounds.invoke(result);
            var actual = new Footprint((int) access.minX.invoke(box), (int) access.minZ.invoke(box),
                    (int) access.maxX.invoke(box), (int) access.maxZ.invoke(box), (int) access.minY.invoke(box));
            if (NativeGround.allowed(world, giant.id(), start.x() << 4, start.z() << 4, actual.minY())) {
                footprint = actual;
            }
        }
        candidates.putIfAbsent(start, Optional.ofNullable(footprint));
        return footprint;
    }

    private boolean outranks(Start other, Start tree, long treePriority) {
        int order = Long.compareUnsigned(priority(other), treePriority);
        if (order != 0) {
            return order < 0;
        }
        int byId = other.id().compareTo(tree.id());
        return byId < 0 || (byId == 0 && (other.x() < tree.x() || (other.x() == tree.x() && other.z() < tree.z())));
    }

    private long priority(Start start) {
        long value = seed ^ (start.x() * 341873128712L) ^ (start.z() * 132897987541L) ^ start.id().hashCode();
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private record Giant(NamespacedKey id, boolean tree, Object setKey, Object placement, Object holder,
                         Object structure, Predicate<Object> biomes) {}

    private record Start(String id, int x, int z) {}

    private record Footprint(int minX, int minZ, int maxX, int maxZ, int minY) {
        int minChunkX() { return minX >> 4; }
        int minChunkZ() { return minZ >> 4; }
        int maxChunkX() { return maxX >> 4; }
        int maxChunkZ() { return maxZ >> 4; }

        boolean isLarge() {
            return maxX - minX + 1 > NativeFootprints.LOCAL_SPAN || maxZ - minZ + 1 > NativeFootprints.LOCAL_SPAN;
        }

        boolean sharesChunk(Footprint other) {
            return minChunkX() <= other.maxChunkX() && maxChunkX() >= other.minChunkX()
                    && minChunkZ() <= other.maxChunkZ() && maxChunkZ() >= other.minChunkZ();
        }
    }

    private static final class Access {
        final Method worldHandle, chunkSource, dimension, registries, generator, biomeSource, generatorState,
                randomState, templates, structureSets, holderKey, keyLocation, structureHolder, holderValue,
                structureBiomes, biomeContains, placement, isStructureChunk, generate, validStart, startBounds,
                minX, minZ, maxX, maxZ, minY;
        final Constructor<?> chunkPos;

        Access() throws ReflectiveOperationException {
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            Class<?> craftStructure = Class.forName("org.bukkit.craftbukkit.generator.structure.CraftStructure");
            Class<?> level = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> source = Class.forName("net.minecraft.server.level.ServerChunkCache");
            Class<?> chunkGenerator = Class.forName("net.minecraft.world.level.chunk.ChunkGenerator");
            Class<?> generatorStateClass = Class.forName("net.minecraft.world.level.chunk.ChunkGeneratorStructureState");
            Class<?> holder = Class.forName("net.minecraft.core.Holder");
            Class<?> holderSet = Class.forName("net.minecraft.core.HolderSet");
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
            Class<?> registryAccess = Class.forName("net.minecraft.core.RegistryAccess");
            Class<?> biomeSourceClass = Class.forName("net.minecraft.world.level.biome.BiomeSource");
            Class<?> randomStateClass = Class.forName("net.minecraft.world.level.levelgen.RandomState");
            Class<?> templatesClass = Class.forName("net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager");
            Class<?> chunkPosClass = Class.forName("net.minecraft.world.level.ChunkPos");
            Class<?> heightAccessor = Class.forName("net.minecraft.world.level.LevelHeightAccessor");
            Class<?> structure = Class.forName("net.minecraft.world.level.levelgen.structure.Structure");
            Class<?> structureSet = Class.forName("net.minecraft.world.level.levelgen.structure.StructureSet");
            Class<?> placementClass = Class.forName("net.minecraft.world.level.levelgen.structure.placement.StructurePlacement");
            Class<?> start = Class.forName("net.minecraft.world.level.levelgen.structure.StructureStart");
            Class<?> bounds = Class.forName("net.minecraft.world.level.levelgen.structure.BoundingBox");
            worldHandle = craftWorld.getMethod("getHandle");
            chunkSource = level.getMethod("getChunkSource");
            dimension = level.getMethod("dimension");
            registries = level.getMethod("registryAccess");
            generator = source.getMethod("getGenerator");
            biomeSource = chunkGenerator.getMethod("getBiomeSource");
            generatorState = source.getMethod("getGeneratorState");
            randomState = source.getMethod("randomState");
            templates = level.getMethod("getStructureManager");
            structureSets = generatorStateClass.getMethod("possibleStructureSets");
            holderKey = holder.getMethod("unwrapKey");
            keyLocation = resourceKey.getMethod("location");
            structureHolder = craftStructure.getMethod("getHolder");
            holderValue = holder.getMethod("value");
            structureBiomes = structure.getMethod("biomes");
            biomeContains = holderSet.getMethod("contains", holder);
            placement = structureSet.getMethod("placement");
            isStructureChunk = placementClass.getMethod("isStructureChunk", generatorStateClass, int.class,
                    int.class, resourceKey);
            generate = structure.getMethod("generate", holder, resourceKey, registryAccess, chunkGenerator,
                    biomeSourceClass, randomStateClass, templatesClass, long.class, chunkPosClass,
                    int.class, heightAccessor, Predicate.class);
            chunkPos = chunkPosClass.getConstructor(int.class, int.class);
            validStart = start.getMethod("isValid");
            startBounds = start.getMethod("getBoundingBox");
            minX = bounds.getMethod("minX");
            minZ = bounds.getMethod("minZ");
            maxX = bounds.getMethod("maxX");
            maxZ = bounds.getMethod("maxZ");
            minY = bounds.getMethod("minY");
        }
    }
}
