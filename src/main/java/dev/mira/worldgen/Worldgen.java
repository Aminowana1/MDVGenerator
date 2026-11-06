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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

public final class Worldgen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final List<String> CLIMATE = List.of(
            "temperature", "humidity", "continentalness", "erosion", "depth", "weirdness");
    private static final List<String> RULE_KEYS = List.of(
            "ultrawarm", "natural", "coordinate_scale", "bed_works", "respawn_anchor_works",
            "infiniburn", "piglin_safe", "has_raids", "monster_spawn_block_light_limit",
            "monster_spawn_light_level");
    private static final List<String> TERRAIN = List.of(
            "overworld", "large_biomes", "amplified", "nether", "end", "caves", "floating_islands");

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
        applyOverrides(terrain, world.getConfigurationSection("noise-settings"));
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
        if (minY < -2032 || noiseHeight < 16 || minY % 16 != 0 || noiseHeight % 16 != 0
                || (long) minY + noiseHeight > 2032) {
            throw invalid(world, "noise-settings.noise", "revisá min_y y height, deben respetar los límites de Minecraft y ser múltiplos de 16");
        }
        int height = terrainName.equals("nether") || terrainName.equals("end")
                ? Math.max(256, noiseHeight) : noiseHeight;
        dimensionType.addProperty("has_ceiling", terrainName.equals("nether") || terrainName.equals("caves"));
        dimensionType.addProperty("logical_height", Math.min(rules.get("logical_height").getAsInt(), height));
        applyOverrides(dimensionType, world.getConfigurationSection("dimension-type"));
        dimensionType.addProperty("min_y", minY);
        dimensionType.addProperty("height", height);

        JsonArray positions = new JsonArray();
        JsonArray surfaceRules = new JsonArray();
        JsonArray subsurfaceRules = new JsonArray();
        Set<String> climatePoints = new HashSet<>();
        for (int index = 0; index < biomeIds.size(); index++) {
            String id = validateId(biomeIds.get(index));
            String qualified = namespace + ":" + id;
            ConfigurationSection biome = requiredSection(biomes, id);
            JsonObject definition = biomeDefinition(biome, terrainName);
            JsonObject climate = climate(biome, index, biomeIds.size());
            if (!climatePoints.add(climate.toString())) {
                throw new IllegalArgumentException("El bioma " + id + " repite las coordenadas climáticas de otro bioma.");
            }
            positions.add(object("biome", qualified, "parameters", climate));
            addSurfaceRule(surfaceRules, qualified, biome, "surface", 0);
            addSurfaceRule(subsurfaceRules, qualified, biome, "subsurface", 2);
            writeJson(data.resolve("worldgen/biome").resolve(id + ".json"), definition);
        }
        addSurfaceRule(surfaceRules, null, world, "surface", 0);
        addSurfaceRule(subsurfaceRules, null, world, "subsurface", 2);
        surfaceRules.addAll(subsurfaceRules);
        if (!surfaceRules.isEmpty()) {
            surfaceRules.add(terrain.get("surface_rule"));
            terrain.add("surface_rule", object("type", "minecraft:sequence", "sequence", surfaceRules));
        }
        JsonObject generator = object(
                "type", "minecraft:noise",
                "settings", namespace + ":terrain",
                "biome_source", object("type", "minecraft:multi_noise", "biomes", positions));
        writeJson(data.resolve("dimension/world.json"), object("type", namespace + ":environment", "generator", generator));
        writeJson(data.resolve("dimension_type/environment.json"), dimensionType);
        writeJson(data.resolve("worldgen/noise_settings/terrain.json"), terrain);

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

    private static JsonObject biomeDefinition(ConfigurationSection biome, String terrain) throws IOException {
        String fallback = switch (terrain) {
            case "nether" -> "nether_wastes";
            case "end" -> "end_highlands";
            default -> "plains";
        };
        String base = biome.getString("base", fallback).replaceFirst("^minecraft:", "");
        JsonObject definition = baseline("worldgen/biome", validateId(base));
        applyOverrides(definition, biome.getConfigurationSection("data"));
        if (biome.contains("temperature")) {
            definition.addProperty("temperature", decimal(biome.get("temperature"), biome, "temperature", -2, 2));
        }
        if (biome.contains("downfall")) {
            definition.addProperty("downfall", decimal(biome.get("downfall"), biome, "downfall", 0, 1));
        }
        if (biome.contains("precipitation")) {
            definition.addProperty("has_precipitation", bool(biome, "precipitation", true));
        }
        if (!bool(biome, "vanilla-vegetation", true)) {
            JsonArray features = definition.getAsJsonArray("features");
            if (features.size() > 9) {
                features.set(9, new JsonArray());
            }
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

    private static JsonObject climate(ConfigurationSection biome, int index, int count) {
        ConfigurationSection input = biome.getConfigurationSection("climate");
        JsonObject result = new JsonObject();
        double defaultTemperature = count == 1 ? 0 : -0.75 + 1.5 * index / (count - 1);
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
            } else {
                result.addProperty(key, value == null
                        ? (key.equals("temperature") ? defaultTemperature : 0)
                        : decimal(value, input, key, -2, 2));
            }
        }
        result.addProperty("offset", input != null && input.contains("offset")
                ? decimal(input.get("offset"), input, "offset", 0, 1) : 0);
        return result;
    }

    private static void addSurfaceRule(JsonArray rules, String biome, ConfigurationSection section, String key, int depth) {
        if (!section.contains(key)) {
            return;
        }
        JsonObject block = object("type", "minecraft:block", "result_state", blockState(section.getString(key, "")));
        JsonObject rule = condition(stoneDepth(depth), block);
        if (depth > 0) {
            rule = condition(object("type", "minecraft:not", "invert", stoneDepth(0)), rule);
        }
        if (biome != null) {
            rule = condition(object("type", "minecraft:biome", "biome_is", List.of(biome)), rule);
        }
        rules.add(rule);
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
        String kind = choice(entry, "kind", "building", List.of("tree", "building"));
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
        List<String> targets = entry.contains("biomes")
                ? entry.getStringList("biomes").stream().map(key ->
                        key.startsWith(namespace + ":") ? key.substring(namespace.length() + 1) : key).toList()
                : biomeIds;
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
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
    }
}
