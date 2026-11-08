package dev.mira.worldgen;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

final class BiomeMenus {
    private static final int PAGE_SIZE = 45;
    private static final List<Option> ENVIRONMENTS = List.of(
            new Option("overworld", "Overworld", Material.GRASS_BLOCK),
            new Option("nether", "Nether", Material.NETHERRACK),
            new Option("end", "End", Material.END_STONE));
    private static final List<Option> TERRAINS = List.of(
            new Option("overworld", "Overworld", Material.GRASS_BLOCK),
            new Option("large_biomes", "Biomas amplios", Material.MAP),
            new Option("amplified", "Relieve amplificado", Material.STONE),
            new Option("nether", "Cavernas del Nether", Material.NETHERRACK),
            new Option("end", "Islas del End", Material.END_STONE),
            new Option("caves", "Cavernas", Material.DEEPSLATE),
            new Option("floating_islands", "Islas flotantes", Material.PHANTOM_MEMBRANE));
    private static final List<Option> RELIEF = List.of(
            new Option("any", "Cualquier relieve", Material.MAP),
            new Option("mountains", "Montañas", Material.SNOW_BLOCK),
            new Option("hills", "Colinas", Material.STONE),
            new Option("plains", "Llanuras", Material.GRASS_BLOCK),
            new Option("coast", "Costas", Material.SAND),
            new Option("ocean", "Océanos", Material.KELP));
    private static final List<Option> CLIMATE = List.of(
            new Option("temperature", "Temperatura", Material.BLAZE_POWDER),
            new Option("humidity", "Humedad", Material.WATER_BUCKET),
            new Option("continentalness", "Continentalidad", Material.MAP),
            new Option("erosion", "Erosión", Material.GRAVEL),
            new Option("depth", "Profundidad", Material.DEEPSLATE),
            new Option("weirdness", "Rareza del relieve", Material.AMETHYST_SHARD),
            new Option("offset", "Desplazamiento", Material.COMPASS));
    private static final List<Option> COLORS = List.of(
            new Option("sky", "Cielo", Material.LIGHT_BLUE_DYE),
            new Option("fog", "Niebla", Material.LIGHT_GRAY_DYE),
            new Option("water", "Agua", Material.BLUE_DYE),
            new Option("water-fog", "Niebla submarina", Material.CYAN_DYE),
            new Option("grass", "Pasto", Material.LIME_DYE),
            new Option("foliage", "Follaje", Material.GREEN_DYE));
    private static final List<Option> MOB_CATEGORIES = List.of(
            new Option("monster", "Monstruos", Material.ZOMBIE_SPAWN_EGG),
            new Option("creature", "Animales", Material.COW_SPAWN_EGG),
            new Option("ambient", "Fauna ambiental", Material.BAT_SPAWN_EGG),
            new Option("axolotls", "Ajolotes", Material.AXOLOTL_SPAWN_EGG),
            new Option("underground_water_creature", "Fauna acuática subterránea", Material.GLOW_SQUID_SPAWN_EGG),
            new Option("water_creature", "Fauna acuática", Material.SQUID_SPAWN_EGG),
            new Option("water_ambient", "Peces y fauna ambiental acuática", Material.COD_SPAWN_EGG));
    // Vanilla 1.21.6 MobCategory values. Bukkit exposes them on entity instances, but not on EntityType.
    private static final Map<String, Set<String>> MOB_TYPES = Map.of(
            "ambient", Set.of("bat"),
            "axolotls", Set.of("axolotl"),
            "creature", Set.of("allay", "armadillo", "bee", "camel", "cat", "chicken", "cow", "donkey", "fox",
                    "frog", "happy_ghast", "goat", "horse", "llama", "mooshroom", "mule", "ocelot", "panda", "parrot",
                    "pig", "polar_bear", "rabbit", "sheep", "skeleton_horse", "sniffer", "strider", "tadpole",
                    "trader_llama", "turtle", "wandering_trader", "wolf", "zombie_horse"),
            "monster", Set.of("blaze", "bogged", "breeze", "cave_spider", "creaking", "creeper", "drowned",
                    "elder_guardian", "enderman", "endermite", "ender_dragon", "evoker", "ghast", "giant", "guardian",
                    "hoglin", "husk", "illusioner", "magma_cube", "phantom", "piglin", "piglin_brute", "pillager",
                    "ravager", "shulker", "silverfish", "skeleton", "slime", "spider", "stray", "vex", "vindicator",
                    "warden", "witch", "wither", "wither_skeleton", "zoglin", "zombie", "zombie_villager", "zombified_piglin"),
            "underground_water_creature", Set.of("glow_squid"),
            "water_ambient", Set.of("cod", "pufferfish", "salmon", "tropical_fish"),
            "water_creature", Set.of("dolphin", "squid"));
    private static final List<Material> BLOCKS = Arrays.stream(Material.values())
            .filter(material -> material.isBlock() && !material.isLegacy() && !material.isAir())
            .sorted(Comparator.comparing(Material::name)).toList();

    private final Menus menus;
    private List<String> bases;

    BiomeMenus(Menus menus) {
        this.menus = menus;
    }

    void list(Player player, String project) throws Exception {
        list(player, project, 0);
    }

    private void list(Player player, String project, int requestedPage) throws Exception {
        var config = menus.projects.readConfig(project);
        var biomes = config.getConfigurationSection("biomes");
        List<String> ids = biomes == null ? List.of() : List.copyOf(biomes.getKeys(false));
        var view = menus.menu(player, "Biomas · " + project);
        int page = page(view, ids.size(), requestedPage, next -> () -> list(player, project, next));
        int first = page * PAGE_SIZE;
        for (int index = first; index < Math.min(first + PAGE_SIZE, ids.size()); index++) {
            String id = ids.get(index);
            String path = "biomes." + id;
            view.button(index - first, Material.GRASS_BLOCK, display(config, path, id),
                    () -> biome(player, project, id), "ID · " + id, "Base · " + value(config, path + ".base", "Según el terreno"));
        }
        view.button(48, Material.EMERALD, "Crear bioma", () -> newBiome(player, project));
        view.button(49, Material.ARROW, "Volver al proyecto", () -> menus.project(player, project));
        view.open();
    }

    private void newBiome(Player player, String project) throws Exception {
        Menus.Action back = () -> list(player, project);
        menus.input(player, "ID del bioma. Usá minúsculas, números, - o _.", null, back, answer -> {
            String id = answer.strip();
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
                throw new IllegalArgumentException("Usá un ID de 1 a 32 caracteres. Empezá con una letra minúscula o un número.");
            }
            if (menus.projects.readConfig(project).contains("biomes." + id)) {
                throw new IllegalArgumentException("Ya existe el bioma «" + id + "».");
            }
            chooseBase(player, back, selected -> {
                menus.projects.editConfig(project, config -> {
                    String path = "biomes." + id;
                    if (config.contains(path)) {
                        throw new IllegalArgumentException("Ya existe el bioma «" + id + "».");
                    }
                    config.set(path + ".base", selected);
                });
                biome(player, project, id);
            });
        });
    }

    void biome(Player player, String project, String biome) throws Exception {
        var config = menus.projects.readConfig(project);
        String path = "biomes." + biome;
        if (!config.isConfigurationSection(path)) {
            throw new IllegalArgumentException("No existe el bioma «" + biome + "» en este proyecto.");
        }
        Menus.Action back = () -> biome(player, project, biome);
        var view = menus.menu(player, display(config, path, biome));
        view.button(10, Material.NAME_TAG, "Nombre en el menú", () -> editInput(player, project, config, path + ".name",
                "Nombre del bioma en los menús, hasta 48 caracteres, o heredar.", back, BiomeMenus::name),
                display(config, path, biome), "El ID del bioma sigue siendo " + biome);
        view.button(12, Material.OAK_SAPLING, "Bioma base", () -> chooseBase(player, back,
                selected -> set(project, path + ".base", selected, back)), value(config, path + ".base", "Según el terreno"));
        view.button(14, Material.GRASS_BLOCK, "Capas del terreno", () -> layers(player, project, path, back),
                "Elegí bloques, porcentajes y profundidad");
        view.button(16, Material.SNOW_BLOCK, "Relieve preferido", () -> chooseOption(player, project, path + ".relief",
                "Relieve preferido", RELIEF, back), optionLabel(RELIEF, config.getString(path + ".relief", "any")),
                "Ubica el bioma según el terreno del mundo");
        view.button(28, Material.SUNFLOWER, "Clima y ubicación", () -> climate(player, project, path, back));
        view.button(30, Material.PAINTING, "Colores", () -> colors(player, project, path, back));
        view.button(32, Material.COW_SPAWN_EGG, "Mobs naturales", () -> mobs(player, project, path, back));
        view.button(34, Material.STRUCTURE_BLOCK, "Árboles y estructuras", () -> menus.catalog(player, project, biome));
        boolean vegetation = config.getBoolean(path + ".vanilla-vegetation", true);
        view.button(39, Material.FERN, "Vegetación del bioma base", () -> set(project, path + ".vanilla-vegetation", !vegetation, back),
                enabled(vegetation), "Clic para cambiar");
        view.button(41, Material.WRITABLE_BOOK, "Datos avanzados del bioma", () -> menus.raw(player, project, path + ".data",
                "Datos avanzados del bioma", back), "Campos nativos de Minecraft");
        view.button(49, Material.ARROW, "Volver a biomas", () -> list(player, project));
        view.open();
    }

    void world(Player player, String project) throws Exception {
        var config = menus.projects.readConfig(project);
        Menus.Action back = () -> world(player, project);
        var view = menus.menu(player, "Mundo · " + project);
        String terrain = config.getString("world.terrain", "overworld");
        String environment = config.getString("world.environment", "overworld");
        view.button(10, Material.MAP, "Terreno base", () -> chooseOption(player, project, "world.terrain", "Terreno base", TERRAINS, back),
                optionLabel(TERRAINS, terrain), "La forma del mundo al generar chunks");
        view.button(12, Material.ENDER_EYE, "Ambiente visual", () -> chooseOption(player, project, "world.environment",
                "Ambiente visual", ENVIRONMENTS, back), optionLabel(ENVIRONMENTS, environment), "Cielo e iluminación");
        view.button(14, Material.REPEATER, "Reglas de dimensión", () -> dimension(player, project), "Camas, agua, anclas y otras reglas");
        view.button(16, Material.WATER_BUCKET, "Nivel del mar", () -> editInput(player, project, config, "world.sea-level",
                "Altura del mar. Escribí un entero entre -2032 y 2031, o heredar.", back, integer(-2032, 2031)),
                value(config, "world.sea-level", "Según el terreno"));
        view.button(28, Material.STONE, "Bloque base", () -> chooseBlock(player, "Bloque base", BLOCKS, back,
                material -> set(project, "world.default-block", material.getKey().toString(), back),
                () -> set(project, "world.default-block", null, back)), value(config, "world.default-block", "Según el terreno"));
        view.button(30, Material.BUCKET, "Fluido base", () -> chooseFluid(player, project, back),
                value(config, "world.default-fluid", "Según el terreno"));
        view.button(32, Material.GRASS_BLOCK, "Capas generales", () -> layers(player, project, "world", back),
                "Se usan si el bioma no define sus capas");
        view.button(34, Material.WRITABLE_BOOK, "Generador avanzado", () -> menus.raw(player, project, "world.noise-settings",
                "Generador avanzado", back), "Campos nativos del generador de Minecraft");
        view.button(49, Material.ARROW, "Volver al proyecto", () -> menus.project(player, project));
        view.open();
    }

    private void dimension(Player player, String project) throws Exception {
        var config = menus.projects.readConfig(project);
        String path = "world.dimension-type";
        Menus.Action back = () -> dimension(player, project);
        var view = menus.menu(player, "Reglas de dimensión");
        List<Option> choices = new ArrayList<>(ENVIRONMENTS);
        choices.add(new Option("", "Según el ambiente", Material.COMPASS));
        view.button(10, Material.REPEATER, "Reglas base", () -> chooseOption(player, project, "world.rules", "Reglas base", choices, back),
                optionLabel(choices, config.getString("world.rules", "")));
        toggle(view, 12, project, config, path + ".ultrawarm", "Evaporar agua y lava rápida", Material.LAVA_BUCKET, back);
        toggle(view, 14, project, config, path + ".bed_works", "Camas habilitadas", Material.RED_BED, back);
        toggle(view, 16, project, config, path + ".respawn_anchor_works", "Anclas habilitadas", Material.RESPAWN_ANCHOR, back);
        toggle(view, 28, project, config, path + ".piglin_safe", "Piglins a salvo de zombificación", Material.GOLD_INGOT, back);
        toggle(view, 30, project, config, path + ".has_raids", "Incursiones habilitadas", Material.CROSSBOW, back);
        view.button(32, Material.COMPASS, "Escala de coordenadas", () -> editInput(player, project, config, path + ".coordinate_scale",
                "Escala de coordenadas entre 0,00001 y 30000000, o heredar.", back, decimal(0.00001, 30000000)),
                value(config, path + ".coordinate_scale", "Según las reglas"));
        view.button(34, Material.WRITABLE_BOOK, "Dimensión avanzada", () -> menus.raw(player, project, path,
                "Dimensión avanzada", back));
        view.button(49, Material.ARROW, "Volver al mundo", () -> world(player, project));
        view.open();
    }

    private void climate(Player player, String project, String path, Menus.Action biomeMenu) throws Exception {
        var config = menus.projects.readConfig(project);
        Menus.Action back = () -> climate(player, project, path, biomeMenu);
        var view = menus.menu(player, "Clima del bioma");
        view.button(11, Material.BLAZE_POWDER, "Temperatura local", () -> editInput(player, project, config, path + ".temperature",
                "Temperatura del bioma entre -2 y 2, o heredar.", back, decimal(-2, 2)),
                value(config, path + ".temperature", "Según el bioma base"));
        view.button(13, Material.WATER_BUCKET, "Humedad local", () -> editInput(player, project, config, path + ".downfall",
                "Humedad del bioma entre 0 y 1, o heredar.", back, decimal(0, 1)),
                value(config, path + ".downfall", "Según el bioma base"));
        toggle(view, 15, project, config, path + ".precipitation", "Lluvia o nieve", Material.SNOWBALL, back);
        view.button(31, Material.COMPASS, "Ubicación en el generador", () -> climatePlacement(player, project, path, back),
                "Ajustá los rangos que el generador usa para ubicar este bioma");
        view.button(49, Material.ARROW, "Volver al bioma", biomeMenu);
        view.open();
    }

    private void climatePlacement(Player player, String project, String path, Menus.Action climateMenu) throws Exception {
        var config = menus.projects.readConfig(project);
        Menus.Action back = () -> climatePlacement(player, project, path, climateMenu);
        var view = menus.menu(player, "Ubicación en el generador");
        for (int index = 0; index < CLIMATE.size(); index++) {
            Option option = CLIMATE.get(index);
            String key = path + ".climate." + option.id();
            boolean offset = option.id().equals("offset");
            String prompt = offset ? "Desplazamiento de 0 a 1, o heredar."
                    : option.label() + " de -2 a 2. Escribí un valor, un intervalo como -0,5 0,5, o heredar.";
            view.button(10 + index, option.icon(), option.label(), () -> editInput(player, project, config, key, prompt, back,
                    offset ? decimal(0, 1) : BiomeMenus::climateRange), value(config, key, "Automático"));
        }
        view.button(49, Material.ARROW, "Volver al clima", climateMenu);
        view.open();
    }

    private void colors(Player player, String project, String path, Menus.Action biomeMenu) throws Exception {
        var config = menus.projects.readConfig(project);
        Menus.Action back = () -> colors(player, project, path, biomeMenu);
        var view = menus.menu(player, "Colores del bioma");
        for (int index = 0; index < COLORS.size(); index++) {
            Option option = COLORS.get(index);
            String key = path + ".colors." + option.id();
            view.button(11 + index, option.icon(), option.label(), () -> editInput(player, project, config, key,
                    "Escribí un color como #7457ac, o heredar.", back, BiomeMenus::color),
                    value(config, key, "Según el bioma base"));
        }
        view.button(49, Material.ARROW, "Volver al bioma", biomeMenu);
        view.open();
    }

    private void layers(Player player, String project, String path, Menus.Action parent) throws Exception {
        var config = menus.projects.readConfig(project);
        Menus.Action back = () -> layers(player, project, path, parent);
        var view = menus.menu(player, "Capas del terreno");
        layerButton(view, 11, player, project, config, path + ".surface", "Superficie", Material.GRASS_BLOCK, back);
        layerButton(view, 15, player, project, config, path + ".subsurface", "Subsuelo", Material.DIRT, back);
        view.button(29, Material.SNOW, "Grosor de la superficie", () -> editInput(player, project, config, path + ".surface-depth",
                "Capas de superficie entre 1 y 16, o heredar.", back, integer(1, 16)),
                value(config, path + ".surface-depth", "1 bloque"));
        view.button(33, Material.DEEPSLATE, "Grosor del subsuelo", () -> editInput(player, project, config, path + ".subsurface-depth",
                "Capas debajo de la superficie entre 0 y 64, o heredar.", back, integer(0, 64)),
                value(config, path + ".subsurface-depth", "2 bloques"));
        view.button(49, Material.ARROW, "Volver", parent);
        view.open();
    }

    private void layerButton(Menus.View view, int slot, Player player, String project, YamlConfiguration config,
                             String path, String label, Material icon, Menus.Action back) {
        Map<String, Integer> current = readPalette(config, path);
        view.button(slot, icon, label,
                () -> palette(player, project, path, label, new Palette(current, new LinkedHashMap<>(current)), 0, back),
                current.isEmpty() ? "Heredada" : current.size() + " bloque(s) · " + paletteTotal(current) + "%",
                "Abrí la paleta para combinar bloques");
    }

    private void palette(Player player, String project, String path, String title, Palette draft,
                         int requestedPage, Menus.Action parent) throws Exception {
        var view = menus.menu(player, title + " · paleta");
        var blocks = List.copyOf(draft.blocks().keySet());
        int page = page(view, blocks.size(), requestedPage, next -> () -> palette(player, project, path, title, draft, next, parent));
        Menus.Action back = () -> palette(player, project, path, title, draft, page, parent);
        int first = page * PAGE_SIZE;
        for (int index = first; index < Math.min(first + PAGE_SIZE, blocks.size()); index++) {
            String block = blocks.get(index);
            view.button(index - first, blockIcon(Material.matchMaterial(block)), block,
                    () -> paletteBlock(player, block, draft, back), draft.blocks().get(block) + "%");
        }
        view.button(47, Material.EMERALD, "Agregar un bloque", () -> chooseBlock(player, "Agregar bloque",
                BLOCKS.stream().filter(block -> !draft.blocks().containsKey(block.getKey().getKey())).toList(), back,
                block -> palettePercent(player, block.getKey().getKey(), draft, back), null),
                "Buscá el bloque y asignale un porcentaje");
        int total = paletteTotal(draft.blocks());
        boolean valid = draft.blocks().isEmpty() || total == 100;
        view.button(48, Material.BUCKET, "Heredar capa", () -> {
            draft.blocks().clear();
            back.run();
        }, "Vacía el borrador. Guardá para aplicar el cambio.");
        view.button(49, valid ? Material.LIME_DYE : Material.RED_DYE, "Guardar paleta", () -> {
            if (!valid) {
                throw new IllegalArgumentException("Los porcentajes tienen que sumar 100%. El borrador suma " + total + "%.");
            }
            menus.projects.editConfig(project, config -> {
                requireBiome(config, path);
                if (!readPalette(config, path).equals(draft.original())) {
                    throw new IllegalArgumentException("Otra edición cambió esta capa. Salí y volvé a abrir la paleta.");
                }
                config.set(path, null);
                if (!draft.blocks().isEmpty()) {
                    config.createSection(path, draft.blocks());
                }
            });
            parent.run();
        }, draft.blocks().isEmpty() ? "Heredar la capa" : "Total · " + total + "% / 100%");
        view.button(50, Material.ARROW, "Salir sin guardar", parent);
        view.open();
    }

    private void paletteBlock(Player player, String block, Palette draft, Menus.Action back) {
        var view = menus.menu(player, "Bloque · " + block);
        view.button(21, Material.PAPER, "Editar porcentaje", () -> palettePercent(player, block, draft, back),
                draft.blocks().get(block) + "%");
        view.button(23, Material.BARRIER, "Quitar del borrador", () -> {
            draft.blocks().remove(block);
            back.run();
        });
        view.button(49, Material.ARROW, "Volver a la paleta", back);
        view.open();
    }

    private void palettePercent(Player player, String block, Palette draft, Menus.Action back) throws Exception {
        int current = draft.blocks().getOrDefault(block, Math.max(1, 100 - paletteTotal(draft.blocks())));
        menus.input(player, "Porcentaje de " + block + ", de 1 a 100.", String.valueOf(current), back, answer -> {
            draft.blocks().put(block, (Integer) integer(1, 100).apply(answer.strip()));
            back.run();
        });
    }

    private static Map<String, Integer> readPalette(YamlConfiguration config, String path) {
        Map<String, Integer> blocks = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                blocks.put(key.replaceFirst("^minecraft:", ""), section.getInt(key));
            }
        } else if (config.isString(path)) {
            blocks.put(config.getString(path, "").replaceFirst("^minecraft:", ""), 100);
        }
        return blocks;
    }

    private static int paletteTotal(Map<String, Integer> palette) {
        return palette.values().stream().mapToInt(Integer::intValue).sum();
    }

    private void mobs(Player player, String project, String path, Menus.Action parent) throws Exception {
        var config = menus.projects.readConfig(project);
        Menus.Action back = () -> mobs(player, project, path, parent);
        var view = menus.menu(player, "Mobs naturales del bioma");
        boolean vanilla = config.getBoolean(path + ".vanilla-mobs", false);
        view.button(4, Material.SPAWNER, "Mobs heredados", () -> set(project, path + ".vanilla-mobs", !vanilla, back),
                enabled(vanilla), "Solo afecta a categorías que no hayas configurado");
        for (int index = 0; index < MOB_CATEGORIES.size(); index++) {
            Option category = MOB_CATEGORIES.get(index);
            var configured = config.getConfigurationSection(path + ".mobs." + category.id());
            String detail = configured == null ? (vanilla ? "Heredada" : "Sin mobs")
                    : configured.getKeys(false).size() + " especies configuradas";
            view.button(19 + index, category.icon(), category.label(),
                    () -> categoryMobs(player, project, path, category, back, 0), detail);
        }
        view.button(49, Material.ARROW, "Volver al bioma", parent);
        view.open();
    }

    private void categoryMobs(Player player, String project, String path, Option category, Menus.Action parent,
                              int requestedPage) throws Exception {
        var config = menus.projects.readConfig(project);
        String categoryPath = path + ".mobs." + category.id();
        var section = config.getConfigurationSection(categoryPath);
        List<String> ids = section == null ? List.of() : List.copyOf(section.getKeys(false));
        var view = menus.menu(player, category.label());
        int page = page(view, ids.size(), requestedPage,
                next -> () -> categoryMobs(player, project, path, category, parent, next));
        Menus.Action back = () -> categoryMobs(player, project, path, category, parent, page);
        int first = page * PAGE_SIZE;
        for (int index = first; index < Math.min(first + PAGE_SIZE, ids.size()); index++) {
            String id = ids.get(index);
            String entityPath = categoryPath + "." + id;
            SpawnRule rule = new SpawnRule(config.getInt(entityPath + ".weight", 10), config.getInt(entityPath + ".min", 1),
                    config.getInt(entityPath + ".max", 4));
            view.button(index - first, spawnIcon(id), id, () -> editMob(player, project, entityPath, id, rule, true, back),
                    "Peso · " + rule.weight(), "Grupo · " + rule.min() + " a " + rule.max());
        }
        view.button(47, Material.EGG, "Agregar mob", () -> chooseMob(player, category, ids, back, entity -> {
            String id = entity.getKey().getKey();
            editMob(player, project, categoryPath + "." + id, id, new SpawnRule(10, 1, 4), false, back);
        }), "Al guardar, esta lista reemplaza la categoría del bioma base");
        view.button(48, Material.BARRIER, "Dejar la categoría vacía", () -> {
            menus.projects.editConfig(project, current -> {
                requireBiome(current, path);
                current.set(categoryPath, null);
                current.createSection(categoryPath);
            });
            back.run();
        }, "Evita la aparición natural de esta categoría en el bioma");
        view.button(49, Material.ARROW, "Volver a mobs", parent);
        view.button(50, Material.HOPPER, "Heredar ajuste general", () -> set(project, categoryPath, null, back),
                "Quita las excepciones de esta categoría");
        view.open();
    }

    private void editMob(Player player, String project, String path, String id, SpawnRule rule, boolean existing,
                         Menus.Action parent) {
        var view = menus.menu(player, "Aparición · " + id);
        Menus.Action back = () -> editMob(player, project, path, id, rule, existing, parent);
        view.button(11, Material.GOLD_NUGGET, "Peso de aparición", () -> spawnInput(player, "Peso entre 1 y 1000.",
                rule.weight(), 1000, back, value -> editMob(player, project, path, id,
                        new SpawnRule(value, rule.min(), rule.max()), existing, parent)), String.valueOf(rule.weight()));
        view.button(13, Material.EGG, "Mínimo por grupo", () -> spawnInput(player, "Mínimo entre 1 y 64.",
                rule.min(), 64, back, value -> editMob(player, project, path, id,
                        new SpawnRule(rule.weight(), value, rule.max()), existing, parent)), String.valueOf(rule.min()));
        view.button(15, Material.EGG, "Máximo por grupo", () -> spawnInput(player, "Máximo entre 1 y 64.",
                rule.max(), 64, back, value -> editMob(player, project, path, id,
                        new SpawnRule(rule.weight(), rule.min(), value), existing, parent)), String.valueOf(rule.max()));
        view.button(31, Material.LIME_DYE, "Guardar aparición", () -> {
            if (rule.min() > rule.max()) {
                throw new IllegalArgumentException("El mínimo del grupo no puede superar el máximo.");
            }
            menus.projects.editConfig(project, config -> {
                requireBiome(config, path);
                if (!existing && config.contains(path)) {
                    throw new IllegalArgumentException("Ese mob ya se agregó. Volvé a abrir la categoría para editarlo.");
                }
                config.set(path + ".weight", rule.weight());
                config.set(path + ".min", rule.min());
                config.set(path + ".max", rule.max());
            });
            parent.run();
        });
        if (existing) {
            view.button(33, Material.BARRIER, "Quitar de la categoría", () -> set(project, path, null, parent));
        }
        view.button(49, Material.ARROW, "Salir sin guardar", parent);
        view.open();
    }

    private void spawnInput(Player player, String prompt, int current, int max, Menus.Action back,
                            Menus.Choice<Integer> done) throws Exception {
        menus.input(player, prompt, String.valueOf(current), back,
                answer -> done.select((Integer) integer(1, max).apply(answer.strip())));
    }

    private void chooseMob(Player player, Option category, List<String> selected, Menus.Action back,
                           Menus.Choice<EntityType> pick) throws Exception {
        Set<String> candidates = MOB_TYPES.get(category.id());
        List<EntityType> entities = Arrays.stream(EntityType.values()).filter(EntityType::isAlive).filter(EntityType::isSpawnable)
                .filter(type -> candidates.contains(type.getKey().getKey()))
                .filter(type -> !selected.contains(type.getKey().getKey()) && !selected.contains(type.getKey().toString()))
                .sorted(Comparator.comparing(type -> type.getKey().getKey())).toList();
        menus.choose(player, category.label() + " · elegir mob", entities, type -> type.getKey().getKey(),
                type -> spawnIcon(type.getKey().getKey()), back, pick);
    }

    private List<String> biomeBases() {
        if (bases == null) {
            bases = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).keyStream()
                    .filter(key -> key.getNamespace().equals("minecraft"))
                    .filter(key -> BiomeMenus.class.getResource(biomeResource(key.getKey())) != null)
                    .map(NamespacedKey::toString).sorted().toList();
        }
        return bases;
    }

    private static String biomeResource(String name) {
        return "/worldgen/vanilla/worldgen/biome/" + name + ".json";
    }

    private void chooseBase(Player player, Menus.Action back, Menus.Choice<String> pick) throws Exception {
        menus.choose(player, "Elegir bioma base", biomeBases(), Function.identity(), ignored -> Material.GRASS_BLOCK, back, pick);
    }

    private void chooseFluid(Player player, String project, Menus.Action back) throws Exception {
        var choices = List.of(new Option("", "Según el terreno", Material.COMPASS),
                new Option("minecraft:water", "Agua", Material.WATER_BUCKET),
                new Option("minecraft:lava", "Lava", Material.LAVA_BUCKET),
                new Option("minecraft:air", "Sin fluido", Material.BUCKET));
        chooseOption(player, project, "world.default-fluid", "Fluido base", choices, back);
    }

    private void chooseOption(Player player, String project, String path, String title, List<Option> options,
                              Menus.Action back) throws Exception {
        menus.choose(player, title, options, Option::label, Option::icon, back,
                option -> set(project, path, option.id().isEmpty() ? null : option.id(), back));
    }

    private void chooseBlock(Player player, String title, List<Material> blocks, Menus.Action back,
                             Menus.Choice<Material> pick, Menus.Action inherit) throws Exception {
        List<Material> choices = new ArrayList<>(blocks);
        if (inherit != null) {
            choices.addFirst(Material.AIR);
        }
        menus.choose(player, title, choices, material -> material.isAir() ? "Heredar" : material.getKey().getKey(),
                material -> material.isAir() ? Material.COMPASS : blockIcon(material), back, material -> {
                    if (material.isAir()) {
                        inherit.run();
                    } else {
                        pick.select(material);
                    }
                });
    }

    private void editInput(Player player, String project, YamlConfiguration config, String path, String prompt,
                           Menus.Action back, Function<String, Object> parse) throws Exception {
        Object current = config.get(path);
        menus.input(player, prompt, current == null ? null : current.toString(), back, answer -> {
            String value = answer.strip();
            set(project, path, value.equalsIgnoreCase("heredar") ? null : parse.apply(value), back);
        });
    }

    private void toggle(Menus.View view, int slot, String project, YamlConfiguration config, String path,
                        String label, Material icon, Menus.Action back) {
        Object current = config.get(path);
        Boolean next = current == null ? Boolean.TRUE : Boolean.TRUE.equals(current) ? Boolean.FALSE : null;
        view.button(slot, icon, label, () -> set(project, path, next, back),
                current instanceof Boolean active ? enabled(active) : "Heredado",
                "Clic para alternar entre sí, no y heredado");
    }

    private void set(String project, String path, Object value, Menus.Action back) throws Exception {
        menus.projects.editConfig(project, config -> {
            requireBiome(config, path);
            config.set(path, value);
        });
        back.run();
    }

    private static void requireBiome(YamlConfiguration config, String path) {
        if (path.startsWith("biomes.")) {
            String id = path.split("\\.", 3)[1];
            if (!config.isConfigurationSection("biomes." + id)) {
                throw new IllegalArgumentException("El bioma «" + id + "» ya no existe.");
            }
        }
    }

    private static int page(Menus.View view, int size, int requested, IntFunction<Menus.Action> open) {
        int last = Math.max(0, (size - 1) / PAGE_SIZE);
        int current = Math.clamp(requested, 0, last);
        String position = "Página " + (current + 1) + " de " + (last + 1);
        if (current > 0) {
            view.button(45, Material.ARROW, "Página anterior", open.apply(current - 1), position);
        }
        if (current < last) {
            view.button(53, Material.ARROW, "Página siguiente", open.apply(current + 1), position);
        }
        return current;
    }

    private static Material blockIcon(Material material) {
        return material != null && material.isItem() ? material : Material.STONE;
    }

    private static Material spawnIcon(String id) {
        Material egg = Material.matchMaterial(id.replaceFirst("^minecraft:", "") + "_spawn_egg");
        return egg == null ? Material.EGG : egg;
    }

    private static String display(YamlConfiguration config, String path, String id) {
        String name = config.getString(path + ".name", id);
        return name.isBlank() ? id : name;
    }

    private static String value(YamlConfiguration config, String path, String fallback) {
        Object value = config.get(path);
        return Objects.toString(value, fallback);
    }

    private static String enabled(boolean active) {
        return active ? "Habilitado" : "Deshabilitado";
    }

    private static String optionLabel(List<Option> options, String id) {
        return options.stream().filter(option -> option.id().equals(id)).map(Option::label).findFirst().orElse(id);
    }

    private static String name(String value) {
        if (value.isBlank() || value.length() > 48 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Usá un nombre de 1 a 48 caracteres.");
        }
        return value;
    }

    private static String color(String value) {
        if (!value.matches("#?[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Escribí seis dígitos hexadecimales, como #7457ac.");
        }
        return "#" + value.replace("#", "").toLowerCase(Locale.ROOT);
    }

    private static Object climateRange(String value) {
        String[] parts = value.split("\\s+");
        if (parts.length == 1) {
            return number(parts[0], -2, 2);
        }
        if (parts.length == 2) {
            double minimum = number(parts[0], -2, 2);
            double maximum = number(parts[1], -2, 2);
            if (minimum <= maximum) {
                return List.of(minimum, maximum);
            }
        }
        throw new IllegalArgumentException("Escribí un valor o dos números ordenados, separados por un espacio.");
    }

    private static Function<String, Object> integer(int minimum, int maximum) {
        return value -> {
            double number = number(value, minimum, maximum);
            if (Math.rint(number) != number) {
                throw new IllegalArgumentException("Escribí un número entero.");
            }
            return (int) number;
        };
    }

    private static Function<String, Object> decimal(double minimum, double maximum) {
        return value -> number(value, minimum, maximum);
    }

    private static double number(String value, double minimum, double maximum) {
        try {
            double result = Double.parseDouble(value.replace(',', '.'));
            if (Double.isFinite(result) && result >= minimum && result <= maximum) {
                return result;
            }
        } catch (NumberFormatException ignored) {
            // The same message also covers values outside the accepted range.
        }
        throw new IllegalArgumentException("Escribí un número entre " + minimum + " y " + maximum + ".");
    }

    private record Option(String id, String label, Material icon) {}
    private record Palette(Map<String, Integer> original, Map<String, Integer> blocks) {}
    private record SpawnRule(int weight, int min, int max) {}
}
