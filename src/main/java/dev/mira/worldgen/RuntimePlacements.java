package dev.mira.worldgen;

import com.google.gson.Gson;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockTransformer;
import org.bukkit.util.BlockVector;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

final class RuntimePlacements implements Listener {
    private static final String MANIFEST = "mdvgenerator/placements.json";
    private static final Gson JSON = new Gson();
    private static final int NO_SURFACE = Integer.MIN_VALUE;
    private static final int MISSING_HEIGHT = Integer.MIN_VALUE;
    private static final NamespacedKey OVERWORLD = NamespacedKey.minecraft("overworld");
    private static final List<BlockTransformer> PRESERVE_UNDER_AIR = List.of((region, x, y, z, block, state) ->
            block.getType().isAir() ? state.getWorld() : block);

    private final JavaPlugin plugin;
    private final Map<UUID, Populator> attached = new HashMap<>();
    private Map<String, List<LoadedEntry>> activePacks;

    RuntimePlacements(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    static void export(Path root, String namespace, ConfigurationSection config, List<String> biomeIds) throws IOException {
        var entries = new ArrayList<Entry>();
        ConfigurationSection structures = config.getConfigurationSection("structures");
        if (structures != null) {
            for (String id : structures.getKeys(false)) {
                ConfigurationSection group = Worldgen.requiredSection(structures, id);
                if (group.contains("placement")) {
                    entries.add(structure(group, namespace, id, biomeIds));
                }
            }
        }
        ConfigurationSection vegetation = config.getConfigurationSection("vegetation");
        if (vegetation != null) {
            for (String id : vegetation.getKeys(false)) {
                ConfigurationSection plant = Worldgen.requiredSection(vegetation, id);
                if ("template".equals(plant.getString("mode", "block"))) {
                    Entry entry = plant(root, plant, namespace, id, biomeIds);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
        }
        if (!entries.isEmpty()) {
            Path file = root.resolve(MANIFEST);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJson(new Manifest(1, namespace, entries)), StandardCharsets.UTF_8);
        }
    }

    private static Entry structure(ConfigurationSection group, String namespace, String id, List<String> biomeIds) {
        if (!group.getString("terrain-adaptation", "none").equals("none")) {
            throw Worldgen.invalid(group, "terrain-adaptation", "elegí none para usar las opciones de colocación avanzada");
        }
        ConfigurationSection settings = Worldgen.requiredSection(group, "placement");
        HeightRules rules = new HeightRules(Worldgen.bool(settings, "surface", false),
                Worldgen.bool(settings, "ocean-floor", false),
                Worldgen.integer(settings, "fixed-height", MISSING_HEIGHT, -2032, 2031),
                band(settings, "height", -2032, 2031), band(settings, "depth", 0, 4063),
                Worldgen.bool(settings, "allow-void", false));
        if (!rules.surface() && !rules.oceanFloor() && rules.fixed() == MISSING_HEIGHT
                && rules.height() == null && rules.depth() == null) {
            throw Worldgen.invalid(group, "placement", "activá superficie, fondo, altura fija, rango de alturas o profundidad");
        }
        boolean tree = group.getString("kind", "building").equals("tree");
        int spacing = Worldgen.integer(group, "spacing", tree ? 2 : 32, 1, 4096);
        int separation = Worldgen.integer(group, "separation", tree ? 0 : Math.min(8, spacing - 1), 0, spacing - 1);
        double frequency = group.contains("frequency")
                ? Worldgen.decimal(group.get("frequency"), group, "frequency", 0, 1) : 1;
        int salt = Worldgen.integer(group, "salt", (namespace + ":" + id).hashCode() & Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
        int attempts = Worldgen.integer(settings, "attempts", 1, 1, 16);
        List<Variant> variants = Worldgen.templates(id, group).entrySet().stream()
                .map(entry -> new Variant(entry.getKey(), entry.getValue())).toList();
        return new Entry(id, targets(group, namespace, biomeIds), variants, rules, new Grid(spacing, separation, frequency),
                salt, attempts, Worldgen.integer(group, "height-offset", 0, -2032, 2031),
                Worldgen.bool(group, "ignore-air", true), true, List.of());
    }

    private static Entry plant(Path root, ConfigurationSection plant, String namespace, String id,
                               List<String> biomeIds) {
        if (!plant.contains("biomes") || (plant.isList("biomes") && plant.getList("biomes", List.of()).isEmpty())) {
            return null;
        }
        String template = Worldgen.validateId(plant.getString("template", ""));
        if (!Files.isRegularFile(root.resolve(templatePath(namespace, template)))) {
            throw Worldgen.invalid(plant, "template", "la plantilla «" + template + "» no está guardada");
        }
        boolean oceanFloor = Worldgen.choice(plant, "heightmap", "surface", List.of("surface", "ocean_floor"))
                .equals("ocean_floor");
        HeightRules rules = new HeightRules(!oceanFloor, oceanFloor, MISSING_HEIGHT, null, null,
                Worldgen.bool(plant, "allow-void", false));
        return new Entry(id, targets(plant, namespace, biomeIds), List.of(new Variant(template, 1)), rules, null,
                (namespace + ":flora/" + id).hashCode(), Worldgen.integer(plant, "count", 4, 1, 128),
                0, true, false, substrates(plant));
    }

    private static List<String> targets(ConfigurationSection section, String namespace, List<String> biomeIds) {
        List<String> ids = Worldgen.biomeTargets(section, namespace, biomeIds);
        if (ids.isEmpty() || !biomeIds.containsAll(ids)) {
            throw Worldgen.invalid(section, "biomes", "indicá biomas del proyecto");
        }
        return ids.stream().map(id -> namespace + ":" + id).toList();
    }

    private static List<String> substrates(ConfigurationSection section) {
        Object raw = section.get("substrate");
        if (raw == null || "auto".equals(raw)) {
            return List.of();
        }
        if (!(raw instanceof String) && !(raw instanceof List<?>)) {
            throw Worldgen.invalid(section, "substrate", "indicá un bloque o una lista de bloques");
        }
        List<?> entries = raw instanceof List<?> list ? list : List.of(raw);
        List<String> materials = new ArrayList<>();
        for (Object item : entries) {
            Material block = item instanceof String name ? Material.matchMaterial(name) : null;
            if (block == null || block.isLegacy() || !block.isBlock()) {
                throw Worldgen.invalid(section, "substrate", "bloque inválido: " + item);
            }
            materials.add(block.getKey().toString());
        }
        return materials;
    }

    private static Band band(ConfigurationSection settings, String key, int min, int max) {
        boolean hasMin = settings.contains("min-" + key);
        boolean hasMax = settings.contains("max-" + key);
        if (hasMin != hasMax) {
            throw Worldgen.invalid(settings, key, "indicá los dos extremos del rango");
        }
        if (!hasMin) {
            return null;
        }
        int from = Worldgen.integer(settings, "min-" + key, min, min, max);
        int to = Worldgen.integer(settings, "max-" + key, max, from, max);
        return new Band(from, to);
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        attach(event.getWorld());
    }

    void attach(World world) {
        if (activePacks == null) {
            World main = world.getKey().equals(OVERWORLD) ? world : plugin.getServer().getWorld(OVERWORLD);
            if (main == null) {
                return;
            }
            activePacks = snapshot(main);
        }
        String namespace = world.getKey().getNamespace();
        if (!world.getKey().getKey().equals("world") || world.getPopulators().stream()
                .anyMatch(populator -> populator instanceof Populator existing && existing.isActive())) {
            return;
        }
        List<LoadedEntry> entries = activePacks.get(namespace);
        if (entries != null) {
            var populator = new Populator(entries, plugin.getLogger());
            world.getPopulators().add(populator);
            attached.put(world.getUID(), populator);
            plugin.getLogger().info("Colocación avanzada de " + world.getKey() + ": " + entries.size() + " grupo(s).");
        }
    }

    void detach() {
        // Chunk workers can still be iterating CraftWorld's populator list during plugin shutdown.
        attached.values().forEach(Populator::disable);
        attached.clear();
        activePacks = null;
    }

    private Map<String, List<LoadedEntry>> snapshot(World main) {
        Path datapacks = main.getWorldFolder().toPath().resolve("datapacks");
        Map<String, List<LoadedEntry>> snapshot = new HashMap<>();
        for (var pack : plugin.getServer().getDatapackManager().getEnabledPacks()) {
            String name = pack.getName();
            if (!name.startsWith("file/gm_")) {
                continue;
            }
            String filename = name.substring("file/".length());
            String namespace = filename.endsWith(".zip") ? filename.substring(0, filename.length() - 4) : filename;
            try {
                Worldgen.validateId(namespace);
                List<LoadedEntry> entries = loadPack(datapacks.resolve(filename), namespace);
                if (!entries.isEmpty()) {
                    snapshot.put(namespace, entries);
                }
            } catch (IOException | RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "No se pudo cargar la colocación avanzada de " + name, ex);
            }
        }
        return Map.copyOf(snapshot);
    }

    private List<LoadedEntry> loadPack(Path pack, String namespace) throws IOException {
        if (Files.isDirectory(pack)) {
            return load(namespace, path -> {
                Path file = pack.resolve(path);
                return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
            });
        }
        try (ZipFile archive = new ZipFile(pack.toFile())) {
            return load(namespace, path -> {
                var file = archive.getEntry(path);
                return file == null ? null : archive.getInputStream(file);
            });
        }
    }

    private List<LoadedEntry> load(String namespace, FilesInPack pack) throws IOException {
        Manifest manifest;
        try (InputStream input = pack.open(MANIFEST)) {
            if (input == null) {
                return List.of();
            }
            manifest = JSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), Manifest.class);
        }
        if (manifest == null || manifest.version() != 1 || !namespace.equals(manifest.namespace())) {
            throw new IOException("Manifiesto de generación incompatible");
        }
        var loaded = new ArrayList<LoadedEntry>();
        var templates = new HashMap<String, Structure>();
        for (Entry entry : manifest.entries()) {
            validateEntry(entry);
            var variants = new ArrayList<LoadedVariant>();
            for (Variant variant : entry.variants()) {
                if (variant.weight() < 1 || variant.weight() > 150) {
                    throw new IOException("Peso de plantilla inválido en «" + entry.id() + "»");
                }
                String name = Worldgen.validateId(variant.name());
                Structure template = templates.get(name);
                if (template == null) {
                    try (InputStream input = pack.open(templatePath(namespace, name))) {
                        if (input == null) {
                            throw new IOException("Falta la plantilla «" + name + "»");
                        }
                        template = plugin.getServer().getStructureManager().loadStructure(input);
                        templates.put(name, template);
                    }
                }
                variants.add(new LoadedVariant(template, variant.weight()));
            }
            loaded.add(new LoadedEntry(entry, List.copyOf(variants), Set.copyOf(entry.biomes()), Set.copyOf(entry.substrate())));
        }
        return List.copyOf(loaded);
    }

    private static void validateEntry(Entry entry) throws IOException {
        Grid grid = entry.grid();
        if (entry.attempts() < 1 || entry.attempts() > (grid == null ? 128 : 16)
                || entry.variants().isEmpty() || entry.heights() == null
                || (grid != null && (grid.spacing() < 1 || grid.spacing() > 4096
                || grid.separation() < 0 || grid.separation() >= grid.spacing()
                || !Double.isFinite(grid.frequency()) || grid.frequency() < 0 || grid.frequency() > 1))) {
            throw new IOException("Configuración de colocación inválida en «" + entry.id() + "»");
        }
    }

    private static String templatePath(String namespace, String name) {
        return "data/" + namespace + "/structure/" + name + ".nbt";
    }

    @FunctionalInterface
    private interface FilesInPack {
        InputStream open(String path) throws IOException;
    }

    private record Manifest(int version, String namespace, List<Entry> entries) {}
    private record Entry(String id, List<String> biomes, List<Variant> variants, HeightRules heights, Grid grid,
                         int salt, int attempts, int offset, boolean ignoreAir, boolean entities, List<String> substrate) {}
    private record Variant(String name, int weight) {}
    private record Band(int min, int max) {}
    private record HeightRules(boolean surface, boolean oceanFloor, int fixed, Band height, Band depth, boolean allowVoid) {}
    private record Grid(int spacing, int separation, double frequency) {}
    private record LoadedVariant(Structure template, int weight) {}
    private record LoadedEntry(Entry settings, List<LoadedVariant> variants, Set<String> biomes, Set<String> substrate) {}

    private static final class Populator extends BlockPopulator {
        private volatile List<LoadedEntry> entries;
        private final Logger log;
        private final AtomicBoolean reportedError = new AtomicBoolean();

        private Populator(List<LoadedEntry> entries, Logger log) {
            this.entries = entries;
            this.log = log;
        }

        private boolean isActive() {
            return !entries.isEmpty();
        }

        private void disable() {
            entries = List.of();
        }

        @Override
        public void populate(WorldInfo world, Random ignored, int chunkX, int chunkZ, LimitedRegion region) {
            for (LoadedEntry entry : entries) {
                try {
                    populateEntry(world, chunkX, chunkZ, region, entry);
                } catch (RuntimeException ex) {
                    if (reportedError.compareAndSet(false, true)) {
                        log.log(Level.SEVERE, "Falló la colocación de «" + entry.settings().id() + "» en " + world.getName(), ex);
                    }
                }
            }
        }

        private static void populateEntry(WorldInfo world, int chunkX, int chunkZ, LimitedRegion region, LoadedEntry entry) {
            Entry settings = entry.settings();
            Grid grid = settings.grid();
            Random random = new Random(seed(world.getSeed(), chunkX, chunkZ, settings.salt()) ^ 0x6BC3A9112D20L);
            if (grid != null && (!candidate(world.getSeed(), chunkX, chunkZ, settings.salt(), grid)
                    || random.nextDouble() >= grid.frequency())) {
                return;
            }
            boolean randomizeColumns = grid == null || settings.attempts() > 1;
            Set<Integer> usedPositions = randomizeColumns ? new LinkedHashSet<>() : Set.of();
            for (int i = 0; i < settings.attempts(); i++) {
                int position = 8 * 16 + 8;
                if (randomizeColumns) {
                    do {
                        position = random.nextInt(256);
                    } while (!usedPositions.add(position));
                }
                int x = (chunkX << 4) + (position & 15);
                int z = (chunkZ << 4) + (position >> 4);
                Structure template = pick(entry.variants(), random);
                var column = new Column(world, region, entry, x, z, template.getSize().getBlockY());
                for (int y : column.heights(random)) {
                    place(region, entry.settings(), template, x, y, z, random);
                }
            }
        }

        private static boolean candidate(long seed, int x, int z, int salt, Grid grid) {
            int cellX = Math.floorDiv(x, grid.spacing());
            int cellZ = Math.floorDiv(z, grid.spacing());
            var random = new Random(seed(seed, cellX, cellZ, salt));
            int range = grid.spacing() - grid.separation();
            return x == cellX * grid.spacing() + random.nextInt(range)
                    && z == cellZ * grid.spacing() + random.nextInt(range);
        }

        private static long seed(long seed, int x, int z, int salt) {
            return seed + x * 341873128712L + z * 132897987541L + salt;
        }

        private static Structure pick(List<LoadedVariant> variants, Random random) {
            int index = random.nextInt(variants.stream().mapToInt(LoadedVariant::weight).sum());
            for (LoadedVariant variant : variants) {
                index -= variant.weight();
                if (index < 0) {
                    return variant.template();
                }
            }
            throw new IllegalArgumentException("El grupo no tiene una plantilla elegible");
        }
    }

    private record Column(WorldInfo world, LimitedRegion region, LoadedEntry entry, int x, int z, int height) {
        Set<Integer> heights(Random random) {
            HeightRules rules = entry.settings().heights();
            Set<Integer> result = new LinkedHashSet<>();
            int surface = rules.surface() || rules.depth() != null ? surface(false) : NO_SURFACE;
            if (rules.surface()) {
                addSurface(result, surface);
            }
            if (rules.oceanFloor()) {
                addSurface(result, surface(true));
            }
            if (rules.fixed() != MISSING_HEIGHT) {
                add(result, rules.fixed());
            }
            if (rules.height() != null) {
                addRange(result, rules.height().min(), rules.height().max(), random);
            }
            if (rules.depth() != null && surface != NO_SURFACE) {
                addRange(result, surface - rules.depth().max(), surface - rules.depth().min(), random);
            }
            return result;
        }

        private void addSurface(Set<Integer> heights, int y) {
            if (y != NO_SURFACE) {
                add(heights, y);
            } else if (entry.settings().heights().allowVoid() && world.getMaxHeight() - height >= world.getMinHeight()) {
                add(heights, Math.clamp(64, world.getMinHeight(), world.getMaxHeight() - height));
            }
        }

        private void add(Set<Integer> heights, int baseY) {
            int y = baseY + entry.settings().offset();
            if (eligible(y)) {
                heights.add(y);
            }
        }

        private void addRange(Set<Integer> heights, int min, int max, Random random) {
            int from = Math.max(world.getMinHeight(), min + entry.settings().offset());
            int to = Math.min(world.getMaxHeight() - height, max + entry.settings().offset());
            int selected = MISSING_HEIGHT;
            int eligibleCount = 0;
            for (int y = from; y <= to; y++) {
                if (eligible(y) && !heights.contains(y) && random.nextInt(++eligibleCount) == 0) {
                    selected = y;
                }
            }
            if (selected != MISSING_HEIGHT) {
                heights.add(selected);
            }
        }

        private boolean eligible(int y) {
            if (y < world.getMinHeight() || y + height > world.getMaxHeight()) {
                return false;
            }
            boolean allowVoid = entry.settings().heights().allowVoid();
            if (!allowVoid || !entry.substrate().isEmpty()) {
                if (y == world.getMinHeight()) {
                    return false;
                }
                Material below = region.getType(x, y - 1, z);
                if ((!allowVoid && !below.isSolid()) || (!entry.substrate().isEmpty()
                        && !entry.substrate().contains(below.getKey().toString()))) {
                    return false;
                }
            }
            return entry.biomes().contains(region.getBiome(x, y, z).getKey().toString());
        }

        private int surface(boolean oceanFloor) {
            int top = region.getHighestBlockYAt(x, z, oceanFloor ? HeightMap.OCEAN_FLOOR_WG : HeightMap.WORLD_SURFACE_WG);
            for (int y = Math.min(top, world.getMaxHeight() - 1); y >= world.getMinHeight(); y--) {
                Material block = region.getType(x, y, z);
                if (block.isSolid() || (!oceanFloor && (block == Material.WATER || block == Material.LAVA))) {
                    return y + 1;
                }
            }
            return NO_SURFACE;
        }
    }

    private static void place(LimitedRegion region, Entry settings, Structure template, int x, int y, int z, Random random) {
        BlockVector size = template.getSize();
        StructureRotation rotation = StructureRotation.values()[random.nextInt(StructureRotation.values().length)];
        int sizeX = size.getBlockX();
        int sizeZ = size.getBlockZ();
        boolean sideways = rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.COUNTERCLOCKWISE_90;
        int width = sideways ? sizeZ : sizeX;
        int depth = sideways ? sizeX : sizeZ;
        int left = x - width / 2;
        int front = z - depth / 2;
        int lastY = y + size.getBlockY() - 1;
        if (!region.isInRegion(left, y, front) || !region.isInRegion(left + width - 1, lastY, front + depth - 1)) {
            return;
        }
        int originX = left + (rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.CLOCKWISE_180 ? width - 1 : 0);
        int originZ = front + (rotation == StructureRotation.COUNTERCLOCKWISE_90 || rotation == StructureRotation.CLOCKWISE_180 ? depth - 1 : 0);
        template.place(region, new BlockVector(originX, y, originZ), settings.entities(), rotation, Mirror.NONE,
                -1, 1, random, settings.ignoreAir() ? PRESERVE_UNDER_AIR : List.of(), List.of());
    }
}
