package dev.mira.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

final class CatalogMenus {
    private final Menus menus;

    CatalogMenus(Menus menus) {
        this.menus = menus;
    }

    void open(Player player, String project, String biome) throws Exception {
        YamlConfiguration config = config(project);
        List<String> saved = menus.projects.listTemplates(project);
        Menus.View view = menus.menu(player, biome == null ? "Catálogo · " + project : "Catálogo · " + biome);
        for (Kind kind : Kind.values()) {
            int count = kind == Kind.VEGETATION ? plants(config, project, biome).size()
                    : templates(config, saved, project, biome, kind).size();
            view.button(kind.slot, kind.icon, kind.label, () -> showKind(player, project, biome, kind),
                    count + (count == 1 ? " elemento" : " elementos"));
        }
        view.button(28, Material.STRUCTURE_BLOCK, "Capturar una plantilla",
                () -> captureKind(player, project, biome), "Guardá los bloques entre las dos posiciones marcadas.");
        view.button(29, Material.GOLDEN_AXE, "Herramienta de selección",
                () -> menus.selector(player), "Clic izquierdo y derecho para marcar las esquinas.", "Usá /mdv para volver al menú.");
        view.button(31, Material.BONE_MEAL, "Crear vegetación de un bloque",
                () -> createPlant(player, project, biome), "Flores, pasto, hongos y otros bloques vanilla.");
        view.button(33, Material.COMPASS, "Grupos de aparición",
                () -> chooseGroup(player, project, biome, () -> open(player, project, biome)),
                "Configurá variantes, biomas, frecuencia y altura.");
        if (biome != null) {
            view.button(34, Material.CHEST, "Usar un elemento del catálogo",
                    () -> chooseAttachment(player, project, biome), "Elegí algo que ya guardaste en este proyecto.");
        }
        view.button(49, Material.ARROW, "Volver", () -> {
            if (biome == null) {
                menus.project(player, project);
            } else {
                menus.biome(player, project, biome);
            }
        });
        view.open();
    }

    private void showKind(Player player, String project, String biome, Kind kind) throws Exception {
        YamlConfiguration config = config(project);
        Menus.Action back = () -> open(player, project, biome);
        if (kind == Kind.VEGETATION) {
            menus.choose(player, kind.label, plants(config, project, biome), name -> name,
                    name -> plantIcon(config, name), back, name -> plant(player, project, name, back));
            return;
        }
        List<String> names = templates(config, menus.projects.listTemplates(project), project, biome, kind);
        menus.choose(player, kind.label, names, name -> name, name -> kind.icon, back,
                name -> asset(player, project, biome, name, () -> showKind(player, project, biome, kind)));
    }

    private void captureKind(Player player, String project, String biome) throws Exception {
        Menus.Action back = () -> open(player, project, biome);
        menus.choose(player, "Tipo de plantilla", Kind.NBT, kind -> kind.label, kind -> kind.icon, back,
                kind -> capture(player, project, biome, kind, false));
    }

    private void capture(Player player, String project, String biome, Kind kind, boolean entities) throws Exception {
        Menus.Action back = () -> captureKind(player, project, biome);
        Menus.View view = menus.menu(player, "Capturar · " + kind.label);
        view.button(20, Material.GOLDEN_AXE, "Marcar la selección", () -> menus.selector(player),
                "Marcá ambas esquinas con clic izquierdo y derecho.", "Volvé con /mdv.");
        view.button(22, Material.ARMOR_STAND, entities ? "Incluir entidades: sí" : "Incluir entidades: no",
                () -> capture(player, project, biome, kind, !entities), "Los bloques siempre se guardan.");
        view.button(24, Material.STRUCTURE_BLOCK, "Guardar selección", () ->
                menus.input(player, "Escribí el ID de la plantilla, por ejemplo sauce_01.", null,
                        () -> capture(player, project, biome, kind, entities), value -> {
                            String name = inputId(value);
                            if (biome == null) {
                                menus.projects.captureTemplate(menus.capture, player, project, name, kind.id, entities);
                            } else {
                                menus.projects.capture(menus.capture, player, project, name, biome, kind.id, entities);
                            }
                            asset(player, project, biome, name, () -> showKind(player, project, biome, kind));
                        }), "Cada lado de la selección puede medir hasta 48 bloques.");
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void chooseAttachment(Player player, String project, String biome) throws Exception {
        Menus.Action back = () -> open(player, project, biome);
        menus.choose(player, "Añadir al bioma · " + biome, List.of(Kind.values()), kind -> kind.label,
                kind -> kind.icon, back, kind -> {
                    YamlConfiguration config = config(project);
                    Menus.Action chooseAgain = () -> chooseAttachment(player, project, biome);
                    if (kind == Kind.VEGETATION) {
                        List<String> unattached = plants(config, project, null).stream()
                                .filter(name -> !plantBiomes(config, project, name).contains(biome)).toList();
                        menus.choose(player, "Vegetación del catálogo", unattached, name -> name,
                                name -> plantIcon(config, name), chooseAgain, name -> {
                                    setPlantBiome(project, name, biome, true);
                                    plant(player, project, name, back);
                                });
                    } else {
                        menus.choose(player, "Plantillas · " + kind.label,
                                templates(config, menus.projects.listTemplates(project), project, null, kind), name -> name,
                                name -> kind.icon, chooseAgain, name -> asset(player, project, biome, name, chooseAgain));
                    }
                });
    }

    private void asset(Player player, String project, String biome, String name, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        Kind kind = kind(config, name);
        Menus.Action reopen = () -> asset(player, project, biome, name, back);
        List<String> usages = groups(config, project, null).stream().filter(group -> variants(
                config.getConfigurationSection("structures." + group), group).containsKey(name)).toList();
        List<String> matching = biome == null ? usages : usages.stream().filter(group ->
                groupBiomes(config, project, requireGroup(config, group)).contains(biome)).toList();
        Menus.View view = menus.menu(player, "Plantilla · " + name);
        view.button(13, kind.icon, name, () -> {}, kind.label,
                "Grupos que la usan en esta vista: " + matching.size());
        view.button(20, Material.NETHER_STAR, "Crear un grupo con esta plantilla",
                () -> createGroup(player, project, biome, name, kind, reopen),
                "El grupo define dónde aparece y con qué frecuencia.");
        view.button(21, Material.CHEST, "Agregar a un grupo existente",
                () -> attachToGroup(player, project, biome, name, kind, reopen),
                "El grupo elige entre sus variantes según sus pesos.");
        view.button(23, Material.COMPASS, "Editar grupos que la usan", () -> menus.choose(player, "Grupos · " + name,
                matching, id -> id, id -> kind.icon, reopen,
                id -> group(player, project, id, reopen)), "Podés ajustar su peso o quitarla del grupo.");
        view.button(24, Material.STRUCTURE_BLOCK, "Actualizar bloques", () -> recapture(player, project, name, false, reopen),
                "Usá las dos posiciones seleccionadas para reemplazar la plantilla.", "Sus grupos y sus pesos se conservan.");
        view.button(31, Material.ENDER_CHEST, "Copiar a otro proyecto", () -> menus.choose(player, "Copiar · " + name,
                menus.projects.list().stream().filter(id -> !id.equals(project)).toList(), id -> id,
                id -> Material.ENDER_CHEST, reopen, destination -> {
                    menus.projects.copyTemplate(project, name, destination);
                    player.sendMessage("Plantilla copiada al catálogo de " + destination + ".");
                    reopen.run();
                }), "La copia queda disponible para asociarla en el otro proyecto.");
        if (usages.isEmpty()) {
            view.button(33, Material.ITEM_FRAME, "Cambiar categoría", () -> menus.choose(player, "Categoría · " + name,
                    Kind.NBT, option -> option.label, option -> option.icon, reopen,
                    option -> setTemplateKind(project, name, option, reopen)), "Disponible para plantillas sin grupo.");
        }
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void recapture(Player player, String project, String name, boolean entities, Menus.Action back) throws Exception {
        Menus.View view = menus.menu(player, "Actualizar · " + name);
        view.button(20, Material.GOLDEN_AXE, "Marcar la selección", () -> menus.selector(player),
                "Volvé al menú con /mdv después de seleccionar.");
        view.button(22, Material.ARMOR_STAND, entities ? "Incluir entidades: sí" : "Incluir entidades: no",
                () -> recapture(player, project, name, !entities, back));
        view.button(24, Material.LIME_DYE, "Confirmar actualización", () -> {
            menus.projects.recapture(menus.capture, player, project, name, entities);
            player.sendMessage("Plantilla actualizada. Instalá la nueva versión del datapack para aplicarla.");
            back.run();
        });
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void chooseGroup(Player player, String project, String biome, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        menus.choose(player, "Grupos de aparición", groups(config, project, biome), id -> id,
                id -> groupKind(config.getConfigurationSection("structures." + id)).icon, back,
                id -> group(player, project, id, () -> chooseGroup(player, project, biome, back)));
    }

    private void createGroup(Player player, String project, String biome, String asset, Kind kind, Menus.Action back) throws Exception {
        if (biome == null) {
            menus.choose(player, "Primer bioma del grupo", biomeNames(config(project)), id -> id,
                    id -> Material.GRASS_BLOCK, back, id -> createGroup(player, project, id, asset, kind, back));
            return;
        }
        menus.input(player, "Escribí el ID del grupo, por ejemplo sauces_lunares.", asset, back, value -> {
            String id = inputId(value);
            String path = "structures." + id;
            menus.projects.editConfig(project, config -> {
                requireBiome(config, biome);
                if (config.contains(path)) {
                    throw new IllegalArgumentException("Ya existe un grupo con ese ID.");
                }
                config.set(path + ".kind", kind.id);
                config.set(path + ".biomes", List.of(biome));
                config.set(path + ".templates." + asset, 1);
            });
            group(player, project, id, back);
        });
    }

    private void attachToGroup(Player player, String project, String biome, String asset, Kind kind, Menus.Action back)
            throws Exception {
        YamlConfiguration config = config(project);
        List<String> available = groups(config, project, null).stream()
                .filter(id -> groupKind(config.getConfigurationSection("structures." + id)) == kind).toList();
        menus.choose(player, "Agregar variante · " + asset, available, id -> id, id -> kind.icon, back, id -> {
            Menus.Action attach = () -> {
                menus.projects.editConfig(project, current -> {
                    ConfigurationSection entry = requireGroup(current, id);
                    Map<String, Integer> weights = variants(entry, id);
                    weights.putIfAbsent(asset, 1);
                    entry.set("templates", weights);
                    if (biome != null) {
                        requireBiome(current, biome);
                        List<String> targets = groupBiomes(current, project, entry);
                        if (!targets.contains(biome)) {
                            targets.add(biome);
                            entry.set("biomes", targets);
                        }
                    }
                });
                group(player, project, id, back);
            };
            List<String> targets = groupBiomes(config, project, requireGroup(config, id));
            if (biome == null || (targets.size() == 1 && targets.contains(biome))) {
                attach.run();
            } else {
                String detail = targets.contains(biome) ? "La plantilla podrá aparecer en todos los biomas de este grupo."
                        : "Todas las variantes del grupo aparecerán en " + biome + " y la nueva plantilla también en los demás biomas del grupo.";
                menus.confirm(player, "Asociar grupo · " + id, back, attach, detail);
            }
        });
    }

    private void setTemplateKind(String project, String name, Kind kind, Menus.Action back) throws Exception {
        menus.projects.editConfig(project, config -> {
            for (String id : groups(config, project, null)) {
                if (variants(requireGroup(config, id), id).containsKey(name)) {
                    throw new IllegalArgumentException("Quitá la plantilla de sus grupos antes de cambiarle la categoría.");
                }
            }
            config.set("catalog." + name + ".kind", kind.id);
        });
        back.run();
    }

    private void group(Player player, String project, String id, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        ConfigurationSection entry = requireGroup(config, id);
        Kind kind = groupKind(entry);
        Menus.Action reopen = () -> group(player, project, id, back);
        int spacing = entry.getInt("spacing", kind == Kind.TREE ? 2 : 32);
        int separation = entry.getInt("separation", kind == Kind.TREE ? 0 : Math.min(8, spacing - 1));
        double frequency = entry.getDouble("frequency", 1.0);
        int offset = entry.getInt("height-offset", 0);
        String heightmap = entry.getString("heightmap", "surface");
        String adaptation = entry.getString("terrain-adaptation", "none");
        boolean ignoreAir = entry.getBoolean("ignore-air", true);
        String path = "structures." + id;
        Menus.View view = menus.menu(player, "Aparición · " + id);
        view.button(10, kind.icon, "Variantes · " + variants(entry, id).size(),
                () -> variantList(player, project, id, reopen), "Agregá plantillas y ajustá su peso relativo.");
        view.button(12, Material.GRASS_BLOCK, "Biomas · " + groupBiomes(config, project, entry).size(),
                () -> groupBiomeList(player, project, id, reopen), "Elegí en qué biomas aparece el grupo.");
        view.button(14, Material.MAP, "Distancia: " + spacing + " chunks", () ->
                menus.input(player, "Escribí la distancia entre intentos (1–4096 chunks).", Integer.toString(spacing),
                        reopen, value -> {
                            int next = whole(value, 1, 4096);
                            menus.projects.editConfig(project, current -> {
                                ConfigurationSection group = requireGroup(current, id);
                                group.set("spacing", next);
                                if (group.isInt("separation") && group.getInt("separation") >= next) {
                                    group.set("separation", next - 1);
                                }
                            });
                            reopen.run();
                        }), "Cuanto mayor sea, más separados salen los intentos.");
        view.button(16, Material.COMPASS, "Separación: " + separation + " chunks", () ->
                menus.input(player, "Escribí un valor de 0 a " + (spacing - 1) + ".", Integer.toString(separation),
                        reopen, value -> {
                            int next = whole(value, 0, spacing - 1);
                            menus.projects.editConfig(project, current -> {
                                ConfigurationSection group = requireGroup(current, id);
                                if (next >= group.getInt("spacing", kind == Kind.TREE ? 2 : 32)) {
                                    throw new IllegalArgumentException("La separación tiene que ser menor que la distancia.");
                                }
                                group.set("separation", next);
                            });
                            reopen.run();
                        }), "Controla cuánto se desplaza el intento dentro de cada región.");
        view.button(19, Material.EXPERIENCE_BOTTLE, "Probabilidad: " + frequency, () -> menus.input(player,
                "Escribí un número de 0 a 1. Por ejemplo 0,25 es 25 %.", Double.toString(frequency), reopen,
                value -> set(project, path + ".frequency", probability(value), reopen)), "Se aplica a cada intento de aparición.");
        view.button(21, Material.FEATHER, "Altura de referencia: " + heightLabel(heightmap), () ->
                menus.choose(player, "Altura de referencia", List.of("surface", "ocean_floor", "fixed"),
                        CatalogMenus::heightLabel, CatalogMenus::heightIcon, reopen, value -> {
                            checkHeight(value, entry.getInt("height", 64), offset);
                            set(project, path + ".heightmap", value, reopen);
                        }), "El Nether con techo suele necesitar una altura fija.");
        view.button(23, Material.LADDER, "Altura fija: " + entry.getInt("height", 64), () ->
                menus.input(player, "Escribí la altura Y fija.", Integer.toString(entry.getInt("height", 64)), reopen,
                        value -> {
                            int height = whole(value, -2032, 2031);
                            checkHeight(heightmap, height, offset);
                            set(project, path + ".height", height, reopen);
                        }), "Se usa si elegís la referencia Altura fija.");
        view.button(25, Material.SCAFFOLDING, "Desplazamiento vertical: " + offset, () ->
                menus.input(player, "Escribí cuántos bloques subir o bajar la plantilla.", Integer.toString(offset),
                        reopen, value -> {
                            int next = whole(value, -2032, 2031);
                            checkHeight(heightmap, entry.getInt("height", 64), next);
                            set(project, path + ".height-offset", next, reopen);
                        }), "Un valor negativo entierra la base.");
        view.button(29, Material.DIRT, "Ajuste del terreno: " + adaptationLabel(adaptation), () ->
                menus.choose(player, "Ajuste del terreno", List.of("none", "bury", "beard_thin", "beard_box"),
                        CatalogMenus::adaptationLabel, choice -> Material.DIRT, reopen,
                        choice -> set(project, path + ".terrain-adaptation", choice, reopen)),
                "Modifica la forma del terreno alrededor de la construcción.");
        view.button(31, Material.GLASS, ignoreAir ? "Conservar el terreno en huecos: sí" : "Conservar el terreno en huecos: no",
                () -> set(project, path + ".ignore-air", !ignoreAir, reopen),
                "Activado, los bloques de aire guardados no borran el terreno.");
        view.button(33, Material.REDSTONE, "Semilla del patrón: " + (entry.contains("salt") ? entry.getInt("salt") : "automática"),
                () -> menus.input(player, "Escribí un entero de 0 a 2147483647 o auto.",
                        entry.contains("salt") ? Integer.toString(entry.getInt("salt")) : "auto", reopen,
                        value -> set(project, path + ".salt", value.strip().equalsIgnoreCase("auto")
                                ? null : whole(value, 0, Integer.MAX_VALUE), reopen)),
                "Cambiala si querés redistribuir los intentos en zonas nuevas.");
        view.button(43, Material.BARRIER, "Quitar grupo", () -> menus.confirm(player, "Quitar · " + id, reopen,
                () -> removeGroup(project, id, back), "Las plantillas se conservan en el catálogo.",
                "Podés fijar la probabilidad en 0 para pausarlo y conservar el ID."));
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void variantList(Player player, String project, String id, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        ConfigurationSection entry = requireGroup(config, id);
        Kind kind = groupKind(entry);
        Map<String, Integer> weights = variants(entry, id);
        Menus.Action reopen = () -> variantList(player, project, id, back);
        Menus.View view = menus.menu(player, "Variantes · " + id);
        view.button(20, kind.icon, "Ver variantes · " + weights.size(), () ->
                menus.choose(player, "Plantillas de " + id, List.copyOf(weights.keySet()),
                        name -> name + " · peso " + weights.get(name), name -> kind.icon, reopen,
                        name -> editVariant(player, project, id, name, reopen)));
        view.button(24, Material.CHEST, "Agregar desde el catálogo", () -> {
            List<String> available = templates(config, menus.projects.listTemplates(project), project, null, kind).stream()
                    .filter(name -> !weights.containsKey(name)).toList();
            menus.choose(player, "Nueva variante · " + id, available, name -> name, name -> kind.icon, reopen, name ->
                    menus.input(player, "Escribí el peso relativo de " + name + " (1–150).", "1", reopen,
                            value -> editWeight(project, id, name, whole(value, 1, 150), reopen)));
        });
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void editVariant(Player player, String project, String id, String name, Menus.Action back) throws Exception {
        ConfigurationSection entry = requireGroup(config(project), id);
        Map<String, Integer> weights = variants(entry, id);
        Menus.View view = menus.menu(player, "Variante · " + name);
        view.button(20, Material.GOLD_INGOT, "Peso relativo: " + weights.get(name), () ->
                menus.input(player, "Escribí el peso relativo (1–150).", String.valueOf(weights.get(name)), back,
                        value -> editWeight(project, id, name, whole(value, 1, 150), back)),
                "Con pesos 1 y 3, la segunda variante sale tres veces más que la primera.");
        if (weights.size() > 1) {
            view.button(24, Material.BARRIER, "Quitar variante del grupo", () -> menus.confirm(player, "Quitar · " + name,
                    back, () -> editWeight(project, id, name, null, back), "La plantilla sigue disponible en el catálogo."));
        }
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void editWeight(String project, String group, String name, Integer weight, Menus.Action back) throws Exception {
        menus.projects.editConfig(project, config -> {
            ConfigurationSection entry = requireGroup(config, group);
            Map<String, Integer> weights = variants(entry, group);
            if (weight == null) {
                if (weights.size() == 1 && weights.containsKey(name)) {
                    throw new IllegalArgumentException("El grupo necesita una variante. Podés quitar el grupo completo.");
                }
                rememberKind(config, name);
                weights.remove(name);
            } else {
                weights.put(name, weight);
            }
            entry.set("templates", weights);
        });
        back.run();
    }

    private void removeGroup(String project, String id, Menus.Action back) throws Exception {
        menus.projects.editConfig(project, config -> {
            variants(requireGroup(config, id), id).keySet().forEach(name -> rememberKind(config, name));
            config.set("structures." + id, null);
        });
        back.run();
    }

    private static void rememberKind(YamlConfiguration config, String name) {
        String path = "catalog." + name + ".kind";
        if (!config.contains(path)) {
            config.set(path, Projects.templateKind(config, name));
        }
    }

    private void groupBiomeList(Player player, String project, String id, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        List<String> targets = groupBiomes(config, project, requireGroup(config, id));
        Menus.Action reopen = () -> groupBiomeList(player, project, id, back);
        menus.choose(player, "Biomas · " + id, biomeNames(config),
                name -> (targets.contains(name) ? "✓ " : "○ ") + name,
                name -> targets.contains(name) ? Material.GRASS_BLOCK : Material.DIRT, back, name -> {
                    menus.projects.editConfig(project, current -> {
                        requireBiome(current, name);
                        ConfigurationSection group = requireGroup(current, id);
                        List<String> currentTargets = groupBiomes(current, project, group);
                        if (!currentTargets.remove(name)) {
                            currentTargets.add(name);
                        }
                        if (currentTargets.isEmpty()) {
                            throw new IllegalArgumentException("El grupo necesita un bioma. Podés quitar el grupo completo.");
                        }
                        group.set("biomes", currentTargets);
                    });
                    reopen.run();
                });
    }

    private void createPlant(Player player, String project, String biome) throws Exception {
        Menus.Action back = () -> open(player, project, biome);
        menus.input(player, "Escribí el ID de la vegetación, por ejemplo flor_lunar.", null, back, value -> {
            String name = inputId(value);
            choosePlantBlock(player, "Bloque para " + name, "minecraft:blue_orchid",
                    () -> createPlant(player, project, biome), block -> {
                        String path = "vegetation." + name;
                        menus.projects.editConfig(project, config -> {
                            if (config.contains(path)) {
                                throw new IllegalArgumentException("Ya existe vegetación con ese ID.");
                            }
                            if (biome != null) {
                                requireBiome(config, biome);
                            }
                            config.set(path + ".block", block);
                            config.set(path + ".count", 4);
                            config.set(path + ".biomes", biome == null ? List.of() : List.of(biome));
                        });
                        plant(player, project, name, back);
                    });
        });
    }

    private void plant(Player player, String project, String name, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        String path = "vegetation." + name;
        ConfigurationSection entry = config.getConfigurationSection(path);
        if (entry == null) {
            throw new IllegalArgumentException("Ese elemento de vegetación ya no existe.");
        }
        String block = entry.getString("block", "minecraft:blue_orchid");
        int count = entry.getInt("count", 4);
        String heightmap = entry.getString("heightmap", "surface");
        String substrate = substrateLabel(entry);
        List<String> biomes = plantBiomes(config, project, name);
        Menus.Action reopen = () -> plant(player, project, name, back);
        Menus.View view = menus.menu(player, "Vegetación · " + name);
        view.button(10, plantIcon(config, name), "Bloque: " + shortBlock(block), () ->
                choosePlantBlock(player, "Bloque para " + name, block, reopen,
                        value -> set(project, path + ".block", value, reopen)));
        view.button(12, Material.BONE_MEAL, "Intentos por chunk: " + count, () -> menus.input(player,
                "Escribí un valor de 1 a 128.", Integer.toString(count), reopen,
                value -> set(project, path + ".count", whole(value, 1, 128), reopen)),
                "El bloque aparece si el terreno permite colocarlo.");
        view.button(14, Material.DIRT, "Bloques de apoyo", () -> menus.input(player,
                "Escribí bloques de apoyo separados por comas, o auto.", substrate, reopen,
                value -> set(project, path + ".substrate", substrates(value), reopen)),
                substrate.equals("auto") ? "Cualquier bloque sólido compatible." : substrate);
        view.button(16, Material.FEATHER, "Altura: " + heightLabel(heightmap), () ->
                menus.choose(player, "Altura de vegetación", List.of("surface", "ocean_floor"),
                        CatalogMenus::heightLabel, CatalogMenus::heightIcon, reopen,
                        value -> set(project, path + ".heightmap", value, reopen)),
                "El bloque necesita que el lugar elegido sea apto para crecer.");
        view.button(22, Material.GRASS_BLOCK, "Biomas · " + biomes.size(),
                () -> plantBiomeList(player, project, name, reopen), "Sin biomas, el elemento queda guardado en el catálogo.");
        view.button(43, Material.BARRIER, "Quitar vegetación", () -> menus.confirm(player, "Quitar · " + name,
                reopen, () -> set(project, path, null, back), "Va a desaparecer del catálogo y de los biomas donde la usás."));
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void plantBiomeList(Player player, String project, String name, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        List<String> targets = plantBiomes(config, project, name);
        Menus.Action reopen = () -> plantBiomeList(player, project, name, back);
        menus.choose(player, "Biomas · " + name, biomeNames(config),
                biome -> (targets.contains(biome) ? "✓ " : "○ ") + biome,
                biome -> targets.contains(biome) ? Material.GRASS_BLOCK : Material.DIRT, back, biome -> {
                    setPlantBiome(project, name, biome, !targets.contains(biome));
                    reopen.run();
                });
    }

    private void setPlantBiome(String project, String name, String biome, boolean attach) throws Exception {
        menus.projects.editConfig(project, config -> {
            requireBiome(config, biome);
            String path = "vegetation." + name;
            if (!config.isConfigurationSection(path)) {
                throw new IllegalArgumentException("Ese elemento de vegetación ya no existe.");
            }
            List<String> targets = plantBiomes(config, project, name);
            if (attach) {
                if (!targets.contains(biome)) {
                    targets.add(biome);
                }
            } else {
                targets.remove(biome);
            }
            config.set(path + ".biomes", targets);
        });
    }

    private void set(String project, String path, Object value, Menus.Action back) throws Exception {
        menus.projects.editConfig(project, config -> {
            int first = path.indexOf('.');
            int second = path.indexOf('.', first + 1);
            String entry = second < 0 ? path : path.substring(0, second);
            if (!config.isConfigurationSection(entry)) {
                throw new IllegalArgumentException("Ese elemento ya no existe.");
            }
            config.set(path, value);
        });
        back.run();
    }

    private void choosePlantBlock(Player player, String title, String current, Menus.Action back, Menus.Answer picked) {
        Menus.Action reopen = () -> choosePlantBlock(player, title, current, back, picked);
        List<Material> typical = List.of(Material.SHORT_GRASS, Material.FERN, Material.POPPY, Material.DANDELION,
                Material.BLUE_ORCHID, Material.ALLIUM, Material.PINK_PETALS, Material.BROWN_MUSHROOM,
                Material.RED_MUSHROOM, Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.DEAD_BUSH,
                Material.KELP, Material.SEA_PICKLE, Material.SEAGRASS);
        Menus.View view = menus.menu(player, title);
        for (int slot = 0; slot < typical.size(); slot++) {
            Material block = typical.get(slot);
            String id = block.getKey().toString();
            view.button(slot + 10, block, shortBlock(id), () -> picked.accept(id));
        }
        view.button(39, Material.CHEST, "Buscar cualquier bloque", () -> menus.choose(player, "Bloques vanilla",
                Arrays.stream(Material.values()).filter(block -> !block.isLegacy() && block.isBlock() && block.isItem() && !block.isAir()).toList(),
                block -> shortBlock(block.getKey().toString()), block -> block, reopen,
                block -> picked.accept(block.getKey().toString())));
        view.button(41, Material.WRITABLE_BOOK, "Escribir ID del bloque", () -> menus.input(player,
                "Escribí el ID del bloque, por ejemplo minecraft:blue_orchid.", current, reopen,
                value -> picked.accept(blockId(value))));
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private YamlConfiguration config(String project) throws Exception {
        return menus.projects.readConfig(project);
    }

    private static List<String> templates(YamlConfiguration config, List<String> saved, String project, String biome, Kind kind) {
        if (biome == null) {
            return saved.stream().filter(name -> kind(config, name) == kind).sorted().toList();
        }
        return keys(config, "structures").stream().flatMap(id -> {
            ConfigurationSection group = config.getConfigurationSection("structures." + id);
            if (group == null || !groupBiomes(config, project, group).contains(biome)) {
                return java.util.stream.Stream.<String>empty();
            }
            return variants(group, id).keySet().stream();
        }).filter(saved::contains).filter(name -> kind(config, name) == kind).distinct().sorted().toList();
    }

    private static List<String> plants(YamlConfiguration config, String project, String biome) {
        return keys(config, "vegetation").stream().filter(name -> biome == null || plantBiomes(config, project, name).contains(biome)).toList();
    }

    private static List<String> plantBiomes(YamlConfiguration config, String project, String name) {
        return localBiomes(project, config.getStringList("vegetation." + name + ".biomes"));
    }

    private static Material plantIcon(YamlConfiguration config, String name) {
        Material material = Material.matchMaterial(config.getString("vegetation." + name + ".block", "blue_orchid"));
        return material != null && !material.isLegacy() && material.isItem() && !material.isAir() ? material : Material.BONE_MEAL;
    }

    private static String substrateLabel(ConfigurationSection entry) {
        if (entry.isList("substrate")) {
            return String.join(", ", entry.getStringList("substrate"));
        }
        return entry.getString("substrate", "auto");
    }

    private static Object substrates(String value) {
        if (value.strip().equalsIgnoreCase("auto")) {
            return null;
        }
        List<String> blocks = Arrays.stream(value.split(",", -1)).map(CatalogMenus::blockId).distinct().toList();
        return blocks.size() == 1 ? blocks.getFirst() : blocks;
    }

    private static List<String> groups(YamlConfiguration config, String project, String biome) {
        return keys(config, "structures").stream().filter(id -> {
            ConfigurationSection group = config.getConfigurationSection("structures." + id);
            return group != null && (biome == null || groupBiomes(config, project, group).contains(biome));
        }).toList();
    }

    private static List<String> groupBiomes(YamlConfiguration config, String project, ConfigurationSection group) {
        if (!group.contains("biomes")) {
            return new ArrayList<>(biomeNames(config));
        }
        return localBiomes(project, group.getStringList("biomes"));
    }

    private static List<String> localBiomes(String project, List<String> biomes) {
        String prefix = Projects.namespace(project) + ":";
        return new ArrayList<>(biomes.stream()
                .map(key -> key.startsWith(prefix) ? key.substring(prefix.length()) : key).distinct().toList());
    }

    private static Kind kind(YamlConfiguration config, String name) {
        return Kind.fromId(Projects.templateKind(config, name));
    }

    private static Kind groupKind(ConfigurationSection group) {
        return Kind.fromId(group.getString("kind", "building"));
    }

    private static Map<String, Integer> variants(ConfigurationSection group, String id) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        ConfigurationSection section = group.getConfigurationSection("templates");
        if (section != null) {
            section.getKeys(false).forEach(name -> weights.put(name, section.getInt(name, 1)));
        } else if (group.isList("templates")) {
            group.getStringList("templates").forEach(name -> weights.put(name, 1));
        } else if (!group.contains("templates")) {
            weights.put(id, 1);
        }
        return weights;
    }

    private static ConfigurationSection requireGroup(YamlConfiguration config, String id) {
        ConfigurationSection group = config.getConfigurationSection("structures." + id);
        if (group == null) {
            throw new IllegalArgumentException("Ese grupo ya no existe.");
        }
        return group;
    }

    private static void requireBiome(YamlConfiguration config, String biome) {
        if (!config.isConfigurationSection("biomes." + biome)) {
            throw new IllegalArgumentException("Ese bioma ya no existe en el proyecto.");
        }
    }

    private static List<String> biomeNames(YamlConfiguration config) {
        return keys(config, "biomes");
    }

    private static List<String> keys(YamlConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
    }

    private static String inputId(String value) {
        return Projects.id(value.strip().toLowerCase(Locale.ROOT));
    }

    private static String blockId(String value) {
        Material material = Material.matchMaterial(value.strip());
        if (material == null || material.isLegacy() || !material.isBlock() || material.isAir()) {
            throw new IllegalArgumentException("Ese ID no corresponde a un bloque vanilla válido.");
        }
        return material.getKey().toString();
    }

    private static String shortBlock(String block) {
        return block.startsWith("minecraft:") ? block.substring(10) : block;
    }

    private static int whole(String value, int min, int max) {
        try {
            int number = Integer.parseInt(value.strip());
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Escribí un número entero de " + min + " a " + max + ".");
    }

    private static double probability(String value) {
        try {
            double number = Double.parseDouble(value.strip().replace(',', '.'));
            if (number >= 0 && number <= 1) {
                return number;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Escribí un número de 0 a 1.");
    }

    private static void checkHeight(String heightmap, int height, int offset) {
        int result = offset + (heightmap.equals("fixed") ? height : 0);
        if (result < -2032 || result > 2031) {
            throw new IllegalArgumentException("La altura fija más el desplazamiento tiene que quedar entre -2032 y 2031.");
        }
    }

    private static String heightLabel(String mode) {
        return switch (mode) {
            case "fixed" -> "Altura fija";
            case "ocean_floor" -> "Fondo oceánico";
            default -> "Superficie";
        };
    }

    private static Material heightIcon(String mode) {
        return switch (mode) {
            case "fixed" -> Material.LADDER;
            case "ocean_floor" -> Material.KELP;
            default -> Material.GRASS_BLOCK;
        };
    }

    private static String adaptationLabel(String mode) {
        return switch (mode) {
            case "bury" -> "Enterrar";
            case "beard_thin" -> "Relleno suave";
            case "beard_box" -> "Relleno bajo toda la base";
            default -> "Ninguno";
        };
    }

    private enum Kind {
        TREE("tree", "Árboles", Material.OAK_SAPLING, 10),
        BUILDING("building", "Construcciones", Material.STONE_BRICKS, 12),
        VEGETATION("vegetation", "Vegetación", Material.BLUE_ORCHID, 14),
        OTHER("other", "Otros", Material.AMETHYST_SHARD, 16);

        private static final List<Kind> NBT = List.of(TREE, BUILDING, OTHER);
        private final String id;
        private final String label;
        private final Material icon;
        private final int slot;

        Kind(String id, String label, Material icon, int slot) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.slot = slot;
        }

        private static Kind fromId(String id) {
            return switch (id) {
                case "tree" -> TREE;
                case "other" -> OTHER;
                default -> BUILDING;
            };
        }
    }
}
