package dev.mira.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Mob;

public final class Worldgen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final List<String> CLIMATE = List.of(
            "temperature", "humidity", "continentalness", "erosion", "depth", "weirdness");
    private static final List<String> RULE_KEYS = List.of(
            "ultrawarm", "natural", "coordinate_scale", "bed_works", "respawn_anchor_works",
            "infiniburn", "piglin_safe", "has_raids", "monster_spawn_block_light_limit",
            "monster_spawn_light_level");
    private static final List<String> TERRAIN = List.of(
            "overworld", "large_biomes", "amplified", "nether", "end", "caves", "floating_islands");
    private static final List<String> RELIEF = List.of("any", "mountains", "hills", "plains", "coast", "ocean");
    private static final List<String> RELIEF_TERRAIN = List.of("overworld", "large_biomes", "amplified");
    private static final List<String> MOB_CATEGORIES = List.of("monster", "creature", "ambient", "axolotls",
            "underground_water_creature", "water_creature", "water_ambient", "misc");
    private static final int VEGETATION_STEP = 9;

    private Worldgen() {}

    public static void write(Path packRoot, String namespace, ConfigurationSection config) throws IOException {
        validateId(namespace);
        Path data = packRoot.resolve("data").resolve(namespace);
        ConfigurationSection world = requiredSection(config, "world");
        ConfigurationSection biomes = requiredSection(config, "biomes");
        List<String> biomeIds = List.copyOf(biomes.getKeys(false));
        if (biomeIds.isEmpty()) {
            throw new IllegalArgumentException("Agregá al menos un bioma a biomes.");
        }
        String terrainName = choice(world, "terrain", "overworld", TERRAIN);
        String environmentName = choice(world, "environment", "overworld", List.of("overworld", "nether", "end"));
        String rulesName = choice(world, "rules", environmentName, List.of("overworld", "nether", "end"));
        JsonObject terrain = baseline("worldgen/noise_settings", terrainName);
        if (terrainName.equals("end")) {
            // Vanilla End's biome source doesn't use temperature. Make it available for our custom biome map.
            JsonObject overworld = baseline("worldgen/noise_settings", "overworld").getAsJsonObject("noise_router");
            terrain.getAsJsonObject("noise_router").add("temperature", overworld.get("temperature"));
            terrain.getAsJsonObject("noise_router").add("vegetation", overworld.get("vegetation"));
        }
        ConfigurationSection advancedNoise = world.getConfigurationSection("noise-settings");
        applyOverrides(terrain, advancedNoise);
        editBlock(terrain, "default_block", world, "default-block");
        editBlock(terrain, "default_fluid", world, "default-fluid");
        if (world.contains("sea-level")) {
            terrain.addProperty("sea_level", integer(world, "sea-level", 63, -2032, 2031));
        }
        JsonObject dimensionType = baseline("dimension_type", dimensionName(environmentName));
        JsonObject rules = baseline("dimension_type", dimensionName(rulesName));
        RULE_KEYS.forEach(key -> dimensionType.add(key, rules.get(key)));
        JsonObject noise = terrain.getAsJsonObject("noise");
        int minY = noise.get("min_y").getAsInt();
        int noiseHeight = noise.get("height").getAsInt();
        if (minY < -2032 || noiseHeight < 16 || noiseHeight > 4064 || minY % 16 != 0 || noiseHeight % 16 != 0
                || (long) minY + noiseHeight > 2032) {
            throw invalid(world, "noise-settings.noise", "revisá min_y y height, deben respetar los límites de Minecraft y ser múltiplos de 16");
        }
        int height = terrainName.equals("nether") || terrainName.equals("end")
                ? Math.max(256, noiseHeight) : noiseHeight;
        if ((long) minY + height > 2032) {
            throw invalid(world, "noise-settings.noise", "la altura de la dimensión supera el límite de Minecraft");
        }
        dimensionType.addProperty("has_ceiling", terrainName.equals("nether") || terrainName.equals("caves"));
        dimensionType.addProperty("logical_height", Math.min(rules.get("logical_height").getAsInt(), height));
        applyOverrides(dimensionType, world.getConfigurationSection("dimension-type"));
        dimensionType.addProperty("min_y", minY);
        dimensionType.addProperty("height", height);

        JsonArray positions = new JsonArray();
        JsonArray surfaceRules = new JsonArray();
        Map<String, List<String>> biomeAliases = new LinkedHashMap<>();
        Map<String, List<String>> vegetation = writeVegetation(data, namespace, config, biomeIds);
        Map<String, List<String>> reliefGroups = reliefGroups(biomes, biomeIds);
        boolean useRelief = reliefGroups.keySet().stream().anyMatch(relief -> !relief.equals("any"));
        if (useRelief && !RELIEF_TERRAIN.contains(terrainName)) {
            throw invalid(world, "terrain", "los presets de relieve por bioma requieren overworld, large_biomes o amplified");
        }
        Layers worldLayers = layers(world, new Layers(null, null, 1, 2));
        int maxNoiseY = minY + noiseHeight - 1;
        boolean weightedSurface = false;
        Set<String> climatePoints = new HashSet<>();
        for (int index = 0; index < biomeIds.size(); index++) {
            String id = validateId(biomeIds.get(index));
            String qualified = namespace + ":" + id;
            ConfigurationSection biome = requiredSection(biomes, id);
            String base = biomeBase(biome, terrainName);
            biomeAliases.computeIfAbsent("minecraft:" + base, ignored -> new ArrayList<>()).add(qualified);
            JsonObject definition = biomeDefinition(biome, base, vegetation.getOrDefault(id, List.of()));
            for (JsonObject climate : climates(biome, id, index, biomeIds.size(), useRelief, reliefGroups)) {
                if (!climatePoints.add(climate.toString())) {
                    throw new IllegalArgumentException("El bioma " + id + " repite las coordenadas climáticas de otro bioma.");
                }
                positions.add(object("biome", qualified, "parameters", climate));
            }
            Layers biomeLayers = layers(biome, worldLayers);
            weightedSurface |= biomeLayers.weighted();
            addSurfaceRules(surfaceRules, namespace, id, biomeLayers, minY, maxNoiseY);
            writeJson(data.resolve("worldgen/biome").resolve(id + ".json"), definition);
        }
        if (advancedNoise == null || !advancedNoise.contains("surface_rule")) {
            includeBiomeAliases(terrain.get("surface_rule"), biomeAliases);
        }
        if (!surfaceRules.isEmpty()) {
            terrain.add("surface_rule", withSurfaceRules(terrain.getAsJsonObject("surface_rule"), surfaceRules));
        }
        JsonObject generator = object(
                "type", "minecraft:noise",
                "settings", namespace + ":terrain",
                "biome_source", object("type", "minecraft:multi_noise", "biomes", positions));
        writeJson(data.resolve("dimension/world.json"), object("type", namespace + ":environment", "generator", generator));
        writeJson(data.resolve("dimension_type/environment.json"), dimensionType);
        writeJson(data.resolve("worldgen/noise_settings/terrain.json"), terrain, weightedSurface ? COMPACT_GSON : GSON);

        ConfigurationSection structures = config.getConfigurationSection("structures");
        if (structures != null && !structures.getKeys(false).isEmpty()) {
            writeJson(data.resolve("worldgen/processor_list/ignore_air.json"), object("processors", List.of(object(
                    "processor_type", "minecraft:block_ignore",
                    "blocks", List.of(blockState("air"), blockState("cave_air"), blockState("void_air"))))));
            for (String id : structures.getKeys(false)) {
                writeStructure(data, namespace, validateId(id), requiredSection(structures, id), biomeIds);
            }
        }
    }

    private static String biomeBase(ConfigurationSection biome, String terrain) {
        String fallback = switch (terrain) {
            case "nether" -> "nether_wastes";
            case "end" -> "end_highlands";
            default -> "plains";
        };
        return validateId(biome.getString("base", fallback).replaceFirst("^minecraft:", ""));
    }

    private static JsonObject biomeDefinition(ConfigurationSection biome, String base, List<String> vegetation)
            throws IOException {
        JsonObject definition = baseline("worldgen/biome", base);
        applyOverrides(definition, biome.getConfigurationSection("data"));
        configureMobs(definition, biome);
        if (biome.contains("temperature")) {
            definition.addProperty("temperature", decimal(biome.get("temperature"), biome, "temperature", -2, 2));
        }
        if (biome.contains("downfall")) {
            definition.addProperty("downfall", decimal(biome.get("downfall"), biome, "downfall", 0, 1));
        }
        if (biome.contains("precipitation")) {
            definition.addProperty("has_precipitation", bool(biome, "precipitation", true));
        }
        boolean vanillaVegetation = bool(biome, "vanilla-vegetation", true);
        if (!vanillaVegetation || !vegetation.isEmpty()) {
            JsonArray features = definition.getAsJsonArray("features");
            while (features.size() <= VEGETATION_STEP) {
                features.add(new JsonArray());
            }
            if (vanillaVegetation && !features.get(VEGETATION_STEP).isJsonArray()) {
                throw invalid(biome, "data.features", "para sumar vegetación, la etapa 9 debe ser una lista de IDs");
            }
            JsonArray decoration = vanillaVegetation ? features.get(VEGETATION_STEP).getAsJsonArray() : new JsonArray();
            for (String feature : vegetation) {
                decoration.add(feature);
            }
            features.set(VEGETATION_STEP, decoration);
        }
        ConfigurationSection colors = biome.getConfigurationSection("colors");
        if (colors != null) {
            JsonObject effects = definition.getAsJsonObject("effects");
            for (String key : List.of("sky", "fog", "water", "water-fog", "grass", "foliage")) {
                if (colors.contains(key)) {
                    effects.addProperty(key.replace('-', '_') + "_color", color(colors, key));
                }
            }
        }
        return definition;
    }

    private static void configureMobs(JsonObject definition, ConfigurationSection biome) {
        boolean vanilla = bool(biome, "vanilla-mobs", false);
        ConfigurationSection advanced = biome.getConfigurationSection("data");
        if (!vanilla) {
            if (advanced == null || !advanced.contains("spawners")) {
                definition.add("spawners", new JsonObject());
            }
            if (advanced == null || !advanced.contains("spawn_costs")) {
                definition.add("spawn_costs", new JsonObject());
            }
            if (advanced == null || !advanced.contains("creature_spawn_probability")) {
                definition.remove("creature_spawn_probability");
            }
        }
        ConfigurationSection custom = optionalSection(biome, "mobs");
        if (custom == null) {
            return;
        }
        JsonObject spawners = definition.getAsJsonObject("spawners");
        for (String category : custom.getKeys(false)) {
            if (!MOB_CATEGORIES.contains(category)) {
                throw invalid(custom, category, "categorías admitidas: " + String.join(", ", MOB_CATEGORIES));
            }
            ConfigurationSection group = requiredSection(custom, category);
            JsonArray entries = new JsonArray();
            Set<NamespacedKey> seen = new HashSet<>();
            for (String entity : group.getKeys(false)) {
                NamespacedKey key = NamespacedKey.fromString(entity);
                var type = key == null ? null : Registry.ENTITY_TYPE.get(key);
                if (type == null || type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass())) {
                    throw invalid(group, entity, "indicá una criatura válida de Minecraft");
                }
                if (!seen.add(key)) {
                    throw invalid(group, entity, "esta criatura está repetida");
                }
                ConfigurationSection spawn = requiredSection(group, entity);
                int weight = integer(spawn, "weight", 10, 1, 1000);
                int min = integer(spawn, "min", 1, 1, 64);
                int max = integer(spawn, "max", min, min, 64);
                entries.add(object("type", key.toString(), "weight", weight, "minCount", min, "maxCount", max));
            }
            spawners.add(category, entries);
        }
    }

    private static Map<String, List<String>> writeVegetation(Path data, String namespace, ConfigurationSection config,
            List<String> biomeIds) throws IOException {
        Map<String, List<String>> byBiome = new LinkedHashMap<>();
        ConfigurationSection vegetation = optionalSection(config, "vegetation");
        if (vegetation == null) {
            return byBiome;
        }
        for (String key : vegetation.getKeys(false)) {
            String id = validateId(key);
            ConfigurationSection entry = requiredSection(vegetation, id);
            JsonObject block = blockState(entry.getString("block", ""));
            int count = integer(entry, "count", 4, 1, 128);
            String heightmap = choice(entry, "heightmap", "surface", List.of("surface", "ocean_floor"));
            JsonObject substrate = substratePredicate(entry);
            List<String> targets = biomeTargets(entry, namespace, List.of());
            if (!biomeIds.containsAll(targets)) {
                throw invalid(entry, "biomes", "indicá únicamente biomas definidos en este perfil");
            }
            if (targets.isEmpty()) {
                continue;
            }
            String featureId = "vegetation/" + id;
            String qualified = namespace + ":" + featureId;
            JsonObject feature = object("type", "minecraft:simple_block", "config", object(
                    "to_place", object("type", "minecraft:simple_state_provider", "state", block)));
            List<String> replaceable = List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
            if (heightmap.equals("ocean_floor") && List.of("minecraft:seagrass", "minecraft:tall_seagrass",
                    "minecraft:kelp", "minecraft:kelp_plant", "minecraft:sea_pickle").contains(block.get("Name").getAsString())) {
                replaceable = List.of("minecraft:water");
            }
            JsonObject filter = object("type", "minecraft:all_of", "predicates", List.of(
                    object("type", "minecraft:matching_blocks", "blocks", replaceable), substrate));
            JsonObject placed = object("feature", qualified, "placement", List.of(
                    object("type", "minecraft:count", "count", count),
                    object("type", "minecraft:in_square"),
                    object("type", "minecraft:heightmap", "heightmap",
                            heightmap.equals("ocean_floor") ? "OCEAN_FLOOR_WG" : "WORLD_SURFACE_WG"),
                    object("type", "minecraft:block_predicate_filter", "predicate", filter),
                    object("type", "minecraft:biome")));
            writeJson(data.resolve("worldgen/configured_feature").resolve(featureId + ".json"), feature);
            writeJson(data.resolve("worldgen/placed_feature").resolve(featureId + ".json"), placed);
            for (String target : targets) {
                byBiome.computeIfAbsent(target, ignored -> new ArrayList<>()).add(qualified);
            }
        }
        return byBiome;
    }

    private static JsonObject substratePredicate(ConfigurationSection entry) {
        List<Integer> below = List.of(0, -1, 0);
        Object raw = entry.get("substrate");
        if (raw == null) {
            return object("type", "minecraft:solid", "offset", below);
        }
        List<?> materials = raw instanceof List<?> list ? list : List.of(raw);
        Set<String> blockIds = new LinkedHashSet<>();
        for (Object material : materials) {
            if (!(material instanceof String id)) {
                throw invalid(entry, "substrate", "usá un bloque o una lista de bloques");
            }
            blockIds.add(blockState(id).get("Name").getAsString());
        }
        if (blockIds.isEmpty()) {
            throw invalid(entry, "substrate", "agregá al menos un bloque");
        }
        return object("type", "minecraft:matching_blocks", "blocks", blockIds, "offset", below);
    }

    private static List<String> biomeTargets(ConfigurationSection entry, String namespace, List<String> fallback) {
        Object raw = entry.get("biomes");
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof List<?> list)) {
            throw invalid(entry, "biomes", "usá una lista de biomas");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String key)) {
                throw invalid(entry, "biomes", "cada bioma debe tener un nombre");
            }
            targets.add(key.startsWith(namespace + ":") ? key.substring(namespace.length() + 1) : key);
        }
        return List.copyOf(targets);
    }

    private static int color(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (value instanceof Number) {
            return integer(section, key, 0, 0, 0xffffff);
        }
        if (value instanceof String text && text.matches("#?[0-9a-fA-F]{6}")) {
            return Integer.parseInt(text.replace("#", ""), 16);
        }
        throw invalid(section, key, "usá un color como '#7457ac' o un entero de 0 a 16777215");
    }

    private static Map<String, List<String>> reliefGroups(ConfigurationSection biomes, List<String> ids) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String id : ids) {
            String relief = choice(requiredSection(biomes, id), "relief", "any", RELIEF);
            groups.computeIfAbsent(relief, ignored -> new ArrayList<>()).add(id);
        }
        return groups;
    }

    private static List<JsonObject> climates(ConfigurationSection biome, String id, int index, int count,
            boolean useRelief, Map<String, List<String>> groups) {
        ConfigurationSection input = optionalSection(biome, "climate");
        if (!useRelief) {
            double temperature = count == 1 ? 0 : -0.75 + 1.5 * index / (count - 1);
            return List.of(climate(input, Map.of("temperature", temperature), 0));
        }
        String relief = choice(biome, "relief", "any", RELIEF);
        List<String> peers = groups.get(relief);
        List<Double> anyRange = List.of(-2.0, 2.0);
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (String key : CLIMATE) {
            defaults.put(key, anyRange);
        }
        int position = peers.indexOf(id);
        double lowTemperature = position == 0 ? -2.0 : -0.9 + 1.8 * position / peers.size();
        double highTemperature = position + 1 == peers.size() ? 2.0 : -0.9 + 1.8 * (position + 1) / peers.size();
        defaults.put("temperature", List.of(lowTemperature, highTemperature));
        switch (relief) {
            case "mountains" -> {
                defaults.put("continentalness", List.of(0.25, 2.0));
                defaults.put("erosion", List.of(-2.0, -0.375));
                defaults.put("weirdness", List.of(0.40, 0.95));
                if (input == null || !input.contains("weirdness")) {
                    JsonObject positiveRidge = climate(input, defaults, 0);
                    defaults.put("weirdness", List.of(-0.95, -0.40));
                    return List.of(positiveRidge, climate(input, defaults, 0));
                }
            }
            case "hills" -> {
                defaults.put("continentalness", List.of(0.05, 2.0));
                defaults.put("erosion", List.of(-0.375, 0.05));
            }
            case "plains" -> {
                defaults.put("continentalness", List.of(-0.11, 0.80));
                defaults.put("erosion", List.of(0.05, 2.0));
            }
            case "coast" -> defaults.put("continentalness", List.of(-0.19, -0.11));
            case "ocean" -> defaults.put("continentalness", List.of(-2.0, -0.19));
            default -> { }
        }
        // A small offset lets a matching relief band outrank a broad fallback.
        return List.of(climate(input, defaults, relief.equals("any") ? 0.08 : 0));
    }

    private static JsonObject climate(ConfigurationSection input, Map<String, ?> defaults, double offset) {
        JsonObject result = new JsonObject();
        for (String key : CLIMATE) {
            Object value = input == null ? null : input.get(key);
            if (value instanceof List<?> range) {
                if (range.size() != 2) {
                    throw invalid(input, key, "el intervalo debe tener dos números");
                }
                double low = decimal(range.get(0), input, key, -2, 2);
                double high = decimal(range.get(1), input, key, -2, 2);
                if (low > high) {
                    throw invalid(input, key, "el mínimo del intervalo supera el máximo");
                }
                result.add(key, GSON.toJsonTree(List.of(low, high)));
            } else if (value != null) {
                result.addProperty(key, decimal(value, input, key, -2, 2));
            } else {
                Object fallback = defaults.get(key);
                result.add(key, GSON.toJsonTree(fallback == null ? 0 : fallback));
            }
        }
        result.addProperty("offset", input != null && input.contains("offset")
                ? decimal(input.get("offset"), input, "offset", 0, 1) : offset);
        return result;
    }

    private record MaterialShare(JsonObject rule, int percent) {}

    private record Layers(List<MaterialShare> surface, List<MaterialShare> subsurface, int surfaceDepth,
                          int subsurfaceDepth) {
        boolean weighted() {
            return (surface != null && surface.size() > 1)
                    || (subsurfaceDepth > 0 && subsurface != null && subsurface.size() > 1);
        }
    }

    private static Layers layers(ConfigurationSection section, Layers defaults) {
        return new Layers(palette(section, "surface", defaults.surface()),
                palette(section, "subsurface", defaults.subsurface()),
                integer(section, "surface-depth", defaults.surfaceDepth(), 1, 16),
                integer(section, "subsurface-depth", defaults.subsurfaceDepth(), 0, 64));
    }

    private static List<MaterialShare> palette(ConfigurationSection section, String key, List<MaterialShare> fallback) {
        Object raw = section.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof String material) {
            return List.of(new MaterialShare(materialRule(blockState(material)), 100));
        }
        ConfigurationSection input = requiredSection(section, key);
        Set<String> materials = new HashSet<>();
        List<MaterialShare> shares = new ArrayList<>();
        int total = 0;
        for (String material : input.getKeys(false)) {
            JsonObject block = blockState(material);
            if (!materials.add(block.get("Name").getAsString())) {
                throw invalid(input, material, "este bloque está repetido");
            }
            int percent = integer(input, material, 0, 1, 100);
            total += percent;
            shares.add(new MaterialShare(materialRule(block), percent));
        }
        if (total != 100) {
            throw invalid(section, key, "los porcentajes enteros deben sumar 100 y ahora suman " + total);
        }
        if (shares.size() > 16) {
            throw invalid(section, key, "usá hasta 16 bloques por capa");
        }
        return shares;
    }

    private static JsonObject materialRule(JsonObject block) {
        return object("type", "minecraft:block", "result_state", block);
    }

    private static void addSurfaceRules(JsonArray allRules, String namespace, String id, Layers layers,
            int minY, int maxY) {
        JsonArray rules = new JsonArray();
        String randomBase = namespace + ":layers/" + id + "/";
        int top = layers.surfaceDepth() - 1;
        if (layers.surface() != null) {
            rules.add(condition(stoneDepth(top), chooseMaterial(layers.surface(), randomBase + "surface", minY, maxY)));
        }
        if (layers.subsurface() != null && layers.subsurfaceDepth() > 0) {
            JsonObject subsurface = chooseMaterial(layers.subsurface(), randomBase + "subsurface", minY, maxY);
            rules.add(condition(object("type", "minecraft:not", "invert", stoneDepth(top)),
                    condition(stoneDepth(top + layers.subsurfaceDepth()), subsurface)));
        }
        if (!rules.isEmpty()) {
            allRules.add(condition(object("type", "minecraft:biome", "biome_is", List.of(namespace + ":" + id)),
                    object("type", "minecraft:sequence", "sequence", rules)));
        }
    }

    private static JsonObject withSurfaceRules(JsonObject original, JsonArray custom) {
        JsonArray result = new JsonArray();
        if (original.get("type").getAsString().equals("minecraft:sequence")) {
            JsonArray old = original.getAsJsonArray("sequence");
            int index = 0;
            while (index < old.size() && isBedrockRule(old.get(index).getAsJsonObject())) {
                result.add(old.get(index++));
            }
            result.addAll(custom);
            for (; index < old.size(); index++) {
                result.add(old.get(index));
            }
        } else {
            result.addAll(custom);
            result.add(original);
        }
        return object("type", "minecraft:sequence", "sequence", result);
    }

    private static void includeBiomeAliases(JsonElement element, Map<String, List<String>> aliases) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> includeBiomeAliases(child, aliases));
        } else if (element.isJsonObject()) {
            JsonObject node = element.getAsJsonObject();
            if (node.get("biome_is") instanceof JsonArray ids) {
                List<String> additions = new ArrayList<>();
                for (JsonElement id : ids) {
                    additions.addAll(aliases.getOrDefault(id.getAsString(), List.of()));
                }
                additions.forEach(ids::add);
            }
            node.asMap().values().forEach(child -> includeBiomeAliases(child, aliases));
        }
    }

    private static boolean isBedrockRule(JsonObject rule) {
        if (!rule.has("then_run") || !rule.getAsJsonObject("then_run").has("result_state")) {
            return false;
        }
        JsonElement block = rule.getAsJsonObject("then_run").getAsJsonObject("result_state").get("Name");
        return block != null && block.getAsString().equals("minecraft:bedrock");
    }

    private static JsonObject chooseMaterial(List<MaterialShare> shares, String randomName, int minY, int maxY) {
        if (shares.size() == 1) {
            return shares.getFirst().rule();
        }
        return chooseAtHeight(shares, randomName, minY, maxY, minY, maxY);
    }

    private static JsonObject chooseAtHeight(List<MaterialShare> shares, String randomName, int bottom, int top,
            int low, int high) {
        if (low == high) {
            JsonArray choices = new JsonArray();
            int cumulative = 0;
            for (int index = 0; index < shares.size() - 1; index++) {
                MaterialShare share = shares.get(index);
                cumulative += share.percent();
                // A vertical gradient samples the same random value at (x,y,z) for the same random_name.
                // At y, [y-(100-p), y+p] makes its threshold p/100 at every height.
                JsonObject test = object("type", "minecraft:vertical_gradient", "random_name", randomName,
                        "true_at_and_below", anchor(low - (100 - cumulative), bottom, top),
                        "false_at_and_above", anchor(low + cumulative, bottom, top));
                choices.add(condition(test, share.rule()));
            }
            choices.add(shares.getLast().rule());
            return object("type", "minecraft:sequence", "sequence", choices);
        }
        int middle = low + (high - low + 1) / 2;
        JsonObject lower = chooseAtHeight(shares, randomName, bottom, top, low, middle - 1);
        JsonObject higher = chooseAtHeight(shares, randomName, bottom, top, middle, high);
        JsonObject test = object("type", "minecraft:y_above", "anchor", anchor(middle, bottom, top),
                "surface_depth_multiplier", 0, "add_stone_depth", false);
        return object("type", "minecraft:sequence", "sequence", List.of(condition(test, higher), lower));
    }

    private static JsonObject anchor(int y, int bottom, int top) {
        if (y < -2032) {
            return object("above_bottom", y - bottom);
        }
        if (y > 2031) {
            return object("below_top", top - y);
        }
        return object("absolute", y);
    }

    private static JsonObject stoneDepth(int depth) {
        return object("type", "minecraft:stone_depth", "offset", depth,
                "add_surface_depth", false, "secondary_depth_range", 0, "surface_type", "floor");
    }

    private static JsonObject condition(JsonObject test, JsonObject rule) {
        return object("type", "minecraft:condition", "if_true", test, "then_run", rule);
    }

    private static void writeStructure(Path data, String namespace, String id, ConfigurationSection entry,
            List<String> biomeIds) throws IOException {
        String qualified = namespace + ":" + id;
        String kind = choice(entry, "kind", "building", List.of("tree", "building", "other"));
        boolean tree = kind.equals("tree");
        int spacing = integer(entry, "spacing", tree ? 2 : 32, 1, 4096);
        int separation = integer(entry, "separation", tree ? 0 : Math.min(8, spacing - 1), 0, spacing - 1);
        double frequency = entry.contains("frequency")
                ? decimal(entry.get("frequency"), entry, "frequency", 0, 1) : 1;
        int salt = integer(entry, "salt", qualified.hashCode() & Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
        String heightmap = choice(entry, "heightmap", "surface", List.of("surface", "ocean_floor", "fixed"));
        int startHeight = heightmap.equals("fixed") ? integer(entry, "height", 64, -2032, 2031) : 0;
        int offset = integer(entry, "height-offset", 0, -2032, 2031);
        if (startHeight + offset < -2032 || startHeight + offset > 2031) {
            throw invalid(entry, "height-offset", "la altura resultante sale del rango de Minecraft");
        }
        List<String> targets = biomeTargets(entry, namespace, biomeIds);
        if (targets.isEmpty() || !biomeIds.containsAll(targets)) {
            throw invalid(entry, "biomes", "indicá una lista de biomas definidos en este perfil");
        }
        JsonArray elements = new JsonArray();
        String processor = bool(entry, "ignore-air", true) ? namespace + ":ignore_air" : "minecraft:empty";
        for (Map.Entry<String, Integer> variant : templates(id, entry).entrySet()) {
            String template = variant.getKey();
            if (!Files.isRegularFile(data.resolve("structure").resolve(template + ".nbt"))) {
                throw invalid(entry, "templates", "no está guardada la plantilla " + template);
            }
            elements.add(object("weight", variant.getValue(), "element", object(
                    "element_type", "minecraft:single_pool_element", "location", namespace + ":" + template,
                    "processors", processor, "projection", "rigid")));
        }
        JsonObject pool = object("fallback", "minecraft:empty", "elements", elements);
        JsonObject structure = object("type", "minecraft:jigsaw",
                "biomes", targets.stream().map(biome -> namespace + ":" + biome).toList(),
                // In 1.21.6, a depth of zero also skips adding the starting piece.
                "size", 1, "start_pool", qualified, "start_height", object("absolute", startHeight + offset),
                "step", tree ? "vegetal_decoration" : "surface_structures",
                "spawn_overrides", new JsonObject(), "max_distance_from_center", 80, "use_expansion_hack", false);
        String adaptation = choice(entry, "terrain-adaptation", "none", List.of("none", "bury", "beard_thin", "beard_box"));
        structure.addProperty("terrain_adaptation", adaptation);
        if (!heightmap.equals("fixed")) {
            structure.addProperty("project_start_to_heightmap",
                    heightmap.equals("ocean_floor") ? "OCEAN_FLOOR_WG" : "WORLD_SURFACE_WG");
        }
        JsonObject structureSet = object("structures", List.of(object("structure", qualified, "weight", 1)),
                "placement", object("type", "minecraft:random_spread", "spacing", spacing,
                        "separation", separation, "frequency", frequency, "salt", salt));
        writeJson(data.resolve("worldgen/template_pool").resolve(id + ".json"), pool);
        writeJson(data.resolve("worldgen/structure").resolve(id + ".json"), structure);
        writeJson(data.resolve("worldgen/structure_set").resolve(id + ".json"), structureSet);
    }

    private static Map<String, Integer> templates(String id, ConfigurationSection section) {
        ConfigurationSection weighted = section.getConfigurationSection("templates");
        Map<String, Integer> templates = new LinkedHashMap<>();
        if (weighted != null) {
            for (String name : weighted.getKeys(false)) {
                templates.put(validateId(name), integer(weighted, name, 1, 1, 150));
            }
        } else if (section.get("templates") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof String name)) {
                    throw invalid(section, "templates", "cada plantilla debe tener un nombre");
                }
                templates.put(validateId(name), 1);
            }
        } else if (!section.contains("templates")) {
            templates.put(id, 1);
        }
        if (templates.isEmpty()) {
            throw invalid(section, "templates", "agregá una lista o un mapa de plantillas");
        }
        return templates;
    }

    private static String dimensionName(String preset) {
        return preset.equals("overworld") ? preset : "the_" + preset;
    }

    private static void editBlock(JsonObject target, String property, ConfigurationSection section, String key) {
        if (section.contains(key)) {
            target.add(property, blockState(section.getString(key, "")));
        }
    }

    private static JsonObject blockState(String blockId) {
        String id = blockId.startsWith("minecraft:") ? blockId.substring(10) : blockId;
        Material material = Material.matchMaterial(id);
        if (material == null || !material.isBlock() || material.isLegacy()) {
            throw new IllegalArgumentException("Bloque vanilla inválido: " + blockId);
        }
        return object("Name", material.getKey().toString());
    }

    private static void applyOverrides(JsonObject json, ConfigurationSection section) {
        if (section != null) {
            section.getValues(false).forEach((key, value) ->
                    json.add(key, value instanceof ConfigurationSection nested ? sectionJson(nested) : GSON.toJsonTree(value)));
        }
    }

    private static JsonObject sectionJson(ConfigurationSection section) {
        JsonObject json = new JsonObject();
        applyOverrides(json, section);
        return json;
    }

    private static ConfigurationSection requiredSection(ConfigurationSection config, String key) {
        ConfigurationSection section = config.getConfigurationSection(key);
        if (section == null) {
            throw invalid(config, key, "falta una sección de configuración");
        }
        return section;
    }

    private static ConfigurationSection optionalSection(ConfigurationSection config, String key) {
        return config.contains(key) ? requiredSection(config, key) : null;
    }

    private static String choice(ConfigurationSection section, String key, String fallback, List<String> allowed) {
        String value = section.getString(key, fallback);
        if (!allowed.contains(value)) {
            throw invalid(section, key, "elegí entre " + String.join(", ", allowed));
        }
        return value;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        if (section.contains(key) && !section.isBoolean(key)) {
            throw invalid(section, key, "usá true o false");
        }
        return section.getBoolean(key, fallback);
    }

    private static int integer(ConfigurationSection section, String key, int fallback, int minimum, int maximum) {
        if (!section.contains(key)) {
            return fallback;
        }
        double value = decimal(section.get(key), section, key, minimum, maximum);
        if (Math.rint(value) != value) {
            throw invalid(section, key, "usá un número entero");
        }
        return (int) value;
    }

    private static double decimal(Object value, ConfigurationSection section, String key, double minimum, double maximum) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw invalid(section, key, "usá un número entre " + minimum + " y " + maximum);
        }
        return number.doubleValue();
    }

    private static IllegalArgumentException invalid(ConfigurationSection section, String key, String explanation) {
        String prefix = section.getCurrentPath();
        return new IllegalArgumentException((prefix == null || prefix.isEmpty() ? "" : prefix + ".") + key + ": " + explanation);
    }

    private static String validateId(String id) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Identificador inválido: " + id + ". Usá minúsculas, números, guiones o _.");
        }
        return id;
    }

    private static JsonObject baseline(String registry, String id) throws IOException {
        String path = "/worldgen/vanilla/" + registry + "/" + id + ".json";
        InputStream input = Worldgen.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalArgumentException("No existe la base vanilla " + id + " en " + registry + ".");
        }
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonObject object(Object... fields) {
        JsonObject object = new JsonObject();
        for (int index = 0; index < fields.length; index += 2) {
            Object value = fields[index + 1];
            object.add((String) fields[index], value instanceof JsonElement element ? element : GSON.toJsonTree(value));
        }
        return object;
    }

    private static void writeJson(Path file, JsonObject json) throws IOException {
        writeJson(file, json, GSON);
    }

    private static void writeJson(Path file, JsonObject json, Gson formatter) throws IOException {
        Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            formatter.toJson(json, writer);
            writer.newLine();
        }
    }
}
