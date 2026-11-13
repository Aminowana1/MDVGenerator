package dev.mira.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

final class CatalogMenus {
    private static final int MIN_Y = -2032;
    private static final int MAX_Y = 2031;
    private static final int MAX_DEPTH = 4063;
    private static final int MAX_GROUP_ATTEMPTS = 16;
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
                () -> menus.selector(player), "Clic izquierdo y derecho para marcar las esquinas.", "Usá /dim para volver al menú.");
        view.button(31, Material.BONE_MEAL, "Crear vegetación",
                () -> createPlant(player, project, biome, null), "Usá un bloque vanilla o una plantilla que capturaste.");
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
                    name -> plantIcon(config, name), back, name -> plant(player, project, name, back),
                    name -> plantLore(config, project, name));
            return;
        }
        List<String> names = templates(config, menus.projects.listTemplates(project), project, biome, kind);
        menus.choose(player, kind.label, names, name -> name, name -> kind.icon, back,
                name -> asset(player, project, biome, name, () -> showKind(player, project, biome, kind)),
                name -> templateLore(config, project, biome, name));
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
                "Marcá ambas esquinas con clic izquierdo y derecho.", "Volvé con /dim.");
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
                        }), "Mantené cargados todos los chunks de la selección.", "Máximo de 2.000.000 de posiciones en total.");
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
                                }, name -> plantLore(config, project, name));
                    } else {
                        menus.choose(player, "Plantillas · " + kind.label,
                                templates(config, menus.projects.listTemplates(project), project, null, kind), name -> name,
                                name -> kind.icon, chooseAgain, name -> asset(player, project, biome, name, chooseAgain),
                                name -> templateLore(config, project, null, name));
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
                id -> group(player, project, id, reopen), id -> groupLore(config, project, id)),
                "Podés ajustar su peso o quitarla del grupo.");
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
        view.button(35, Material.BONE_MEAL, "Usar como vegetación", () -> createPlant(player, project, biome, name),
                "Crea una regla de vegetación para esta plantilla.");
        view.button(40, Material.STRUCTURE_VOID, "Pegar en mi posición", () -> {
            menus.projects.paste(player, project, name, StructureRotation.NONE, false, 0);
            player.closeInventory();
            player.sendMessage("Plantilla pegada: " + name + ".");
        }, "Centra la plantilla a la altura de tus pies.", "Conserva el terreno en las celdas de aire.",
                "Para girar o excavar, usá /dim pegar.");
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void recapture(Player player, String project, String name, boolean entities, Menus.Action back) throws Exception {
        Menus.View view = menus.menu(player, "Actualizar · " + name);
        view.button(20, Material.GOLDEN_AXE, "Marcar la selección", () -> menus.selector(player),
                "Volvé al menú con /dim después de seleccionar.");
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
                id -> group(player, project, id, () -> chooseGroup(player, project, biome, back)),
                id -> groupLore(config, project, id));
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
        }, id -> groupLore(config, project, id));
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
        ConfigurationSection placement = entry.getConfigurationSection("placement");
        String adaptation = entry.getString("terrain-adaptation", "none");
        boolean ignoreAir = entry.getBoolean("ignore-air", true);
        String path = "structures." + id;
        Map<String, Integer> weights = variants(entry, id);
        List<String> targets = groupBiomes(config, project, entry);
        Menus.View view = menus.menu(player, "Aparición · " + id);
        view.button(10, kind.icon, "Variantes · " + weights.size(),
                () -> variantList(player, project, id, reopen),
                summarize(weights.entrySet().stream().map(weight -> weight.getKey() + " ×" + weight.getValue()).toList()),
                "Agregá plantillas y ajustá su peso relativo.", "Cada aparición elige uno de cuatro giros.");
        view.button(12, Material.GRASS_BLOCK, "Biomas · " + targets.size(),
                () -> groupBiomeList(player, project, id, reopen), biomeSummary(targets), "Elegí en qué biomas aparece el grupo.");
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
        if (placement == null) {
            view.button(21, Material.FEATHER, "Altura nativa: " + heightLabel(heightmap), () ->
                    menus.choose(player, "Altura de referencia", List.of("surface", "ocean_floor", "fixed"),
                            CatalogMenus::heightLabel, CatalogMenus::heightIcon, reopen, value -> {
                                menus.projects.editConfig(project, configNow -> {
                                    ConfigurationSection nativeGroup = requireNativeGroup(configNow, id);
                                    checkHeight(value, nativeGroup.getInt("height", 64), nativeGroup.getInt("height-offset", 0));
                                    nativeGroup.set("heightmap", value);
                                });
                                reopen.run();
                            }), "El Nether con techo suele necesitar una altura fija.");
            view.button(23, Material.LADDER, "Altura fija: " + entry.getInt("height", 64), () ->
                    menus.input(player, "Escribí la altura Y fija.", Integer.toString(entry.getInt("height", 64)), reopen,
                            value -> {
                                int height = whole(value, MIN_Y, MAX_Y);
                                menus.projects.editConfig(project, configNow -> {
                                    ConfigurationSection nativeGroup = requireNativeGroup(configNow, id);
                                    checkHeight(nativeGroup.getString("heightmap", "surface"), height, nativeGroup.getInt("height-offset", 0));
                                    nativeGroup.set("height", height);
                                });
                                reopen.run();
                            }), "Se usa si elegís la referencia Altura fija.");
        }
        view.button(25, Material.SCAFFOLDING, "Desplazamiento vertical: " + offset, () ->
                menus.input(player, "Escribí cuántos bloques subir o bajar la plantilla.", Integer.toString(offset),
                        reopen, value -> {
                            int next = whole(value, MIN_Y, MAX_Y);
                            menus.projects.editConfig(project, current -> {
                                ConfigurationSection group = requireGroup(current, id);
                                ConfigurationSection rules = group.getConfigurationSection("placement");
                                if (rules == null) {
                                    checkHeight(group.getString("heightmap", "surface"), group.getInt("height", 64), next);
                                } else if (rules.isInt("fixed-height")) {
                                    checkHeight("fixed", rules.getInt("fixed-height"), next);
                                }
                                group.set("height-offset", next);
                            });
                            reopen.run();
                        }), "Un valor negativo entierra la base.");
        view.button(29, Material.DIRT, "Ajuste del terreno: " + adaptationLabel(adaptation), () ->
                menus.choose(player, "Ajuste del terreno", placement == null
                                ? List.of("none", "bury", "beard_thin", "beard_box") : List.of("none"),
                        CatalogMenus::adaptationLabel, choice -> Material.DIRT, reopen,
                        choice -> {
                            menus.projects.editConfig(project, configNow -> {
                                ConfigurationSection target = choice.equals("none") ? requireGroup(configNow, id)
                                        : requireNativeGroup(configNow, id);
                                target.set("terrain-adaptation", choice);
                            });
                            reopen.run();
                        }),
                "Modifica la forma del terreno alrededor de la construcción en modo nativo.");
        view.button(31, Material.GLASS, ignoreAir ? "Conservar el terreno en huecos: sí" : "Conservar el terreno en huecos: no",
                () -> set(project, path + ".ignore-air", !ignoreAir, reopen),
                "Si lo desactivás, el aire guardado excava el terreno.",
                "El vacío de estructura siempre conserva el bloque original.");
        view.button(33, Material.REDSTONE, "Semilla del patrón: " + (entry.contains("salt") ? entry.getInt("salt") : "automática"),
                () -> menus.input(player, "Escribí un entero de 0 a 2147483647 o auto.",
                        entry.contains("salt") ? Integer.toString(entry.getInt("salt")) : "auto", reopen,
                        value -> set(project, path + ".salt", value.strip().equalsIgnoreCase("auto")
                                ? null : whole(value, 0, Integer.MAX_VALUE), reopen)),
                "Cambiala si querés redistribuir los intentos en zonas nuevas.");
        view.button(37, Material.SPYGLASS, "Zonas de aparición", () -> placement(player, project, id, reopen),
                placementSummary(entry), attemptsSummary(placement), "Combiná superficie, fondo, altura y profundidad.",
                "Podés usar superficie y una altura fija al mismo tiempo.");
        view.button(39, Material.DEEPSLATE, "Cráter o gruta", () -> menus.input(player,
                "¿Cuántos bloques hay entre la base y el nivel de la superficie en tu plantilla?",
                Integer.toString(offset < 0 ? -offset : 8), reopen, value -> {
                    int depth = whole(value, 1, -MIN_Y);
                    menus.projects.editConfig(project, current -> {
                        ConfigurationSection group = requireGroup(current, id);
                        ConfigurationSection rules = group.getConfigurationSection("placement");
                        if (rules == null) {
                            group.set("heightmap", "surface");
                        } else {
                            if (rules.isInt("fixed-height")) {
                                checkHeight("fixed", rules.getInt("fixed-height"), -depth);
                            }
                            rules.set("surface", true);
                        }
                        group.set("height-offset", -depth);
                        group.set("terrain-adaptation", "none");
                        group.set("ignore-air", false);
                    });
                    reopen.run();
                }), "Apoya el borde en la superficie y hunde la base.", "El aire guardado excava el terreno.",
                "Protegé el exterior con bloques de vacío de estructura.");
        view.button(43, Material.BARRIER, "Quitar grupo", () -> menus.confirm(player, "Quitar · " + id, reopen,
                () -> removeGroup(project, id, back), "Las plantillas se conservan en el catálogo.",
                "Podés fijar la probabilidad en 0 para pausarlo y conservar el ID."));
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void placement(Player player, String project, String id, Menus.Action back) throws Exception {
        ConfigurationSection group = requireGroup(config(project), id);
        ConfigurationSection current = group.getConfigurationSection("placement");
        String nativeMode = group.getString("heightmap", "surface");
        boolean surface = current == null ? nativeMode.equals("surface") : current.getBoolean("surface");
        boolean oceanFloor = current == null ? nativeMode.equals("ocean_floor") : current.getBoolean("ocean-floor");
        Integer fixed = null;
        if (current == null && nativeMode.equals("fixed")) {
            fixed = group.getInt("height", 64);
        } else if (current != null && current.isInt("fixed-height")) {
            fixed = current.getInt("fixed-height");
        }
        boolean allowVoid = current != null && current.getBoolean("allow-void");
        int attempts = current == null ? 1 : current.getInt("attempts", 1);
        String fixedLabel = fixed == null ? "desactivada" : "Y " + fixed;
        String fixedValue = fixed == null ? "desactivar" : fixed.toString();
        Menus.Action reopen = () -> placement(player, project, id, back);
        Menus.View view = menus.menu(player, "Zonas de aparición · " + id);
        view.button(4, Material.PAPER, current == null ? "Modo nativo" : "Modo combinado activo", () -> {},
                current == null ? "El modo combinado se activa al guardar el primer cambio acá."
                        : "Cada zona activa aporta una posible altura para el intento.",
                "Podés activar varias zonas y combinarlas.", "Requiere Ajuste del terreno: Ninguno.");
        view.button(10, Material.GRASS_BLOCK, "Superficie: " + enabled(surface),
                () -> editPlacement(project, id, rules -> rules.set("surface", !rules.getBoolean("surface")), reopen),
                "Coloca la base sobre el terreno de la columna.");
        view.button(12, Material.KELP, "Fondo oceánico: " + enabled(oceanFloor),
                () -> editPlacement(project, id, rules -> rules.set("ocean-floor", !rules.getBoolean("ocean-floor")), reopen),
                "Toma como referencia el suelo debajo del agua.");
        view.button(14, Material.LADDER, "Altura fija: " + fixedLabel, () -> menus.input(player,
                "Escribí una coordenada Y o desactivar.", fixedValue, reopen, value ->
                        editPlacement(project, id, rules -> rules.set("fixed-height", inactive(value)
                                ? null : whole(value, MIN_Y, MAX_Y)), reopen)), "Usa siempre la altura Y que indiques.");
        view.button(16, Material.COMPASS, "Rango de alturas: " + rangeLabel(current, "height"), () ->
                rangeInput(player, project, id, current, "height", MIN_Y, MAX_Y, reopen),
                "Ejemplo: -40 10 permite apariciones entre esas coordenadas Y.");
        view.button(28, Material.DEEPSLATE, "Profundidad: " + rangeLabel(current, "depth"), () ->
                rangeInput(player, project, id, current, "depth", 0, MAX_DEPTH, reopen),
                "Ejemplo: 6 18 son entre 6 y 18 bloques debajo de la superficie.",
                "La profundidad 0 corresponde al bloque justo arriba de la superficie.");
        view.button(30, Material.GLASS, "Permitir vacío: " + enabled(allowVoid), () ->
                editPlacement(project, id, rules -> rules.set("allow-void", !rules.getBoolean("allow-void")), reopen),
                "Permite colocar la base aunque no haya un bloque sólido debajo.");
        view.button(32, Material.BONE_MEAL, "Intentos por chunk elegible: " + attempts, () ->
                menus.input(player, "Escribí cuántas posiciones probar por chunk, de 1 a 16.", Integer.toString(attempts),
                        reopen, value -> editPlacement(project, id,
                                rules -> rules.set("attempts", whole(value, 1, MAX_GROUP_ATTEMPTS)), reopen)),
                "Con un intento se usa el centro. Con varios se prueban posiciones distintas.",
                "Las plantillas grandes admiten un intento por zona activa.",
                "Distancia 1 y separación 0 hacen elegible cada chunk antes de aplicar la probabilidad.");
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void rangeInput(Player player, String project, String id, ConfigurationSection current,
                            String kind, int min, int max, Menus.Action back) {
        String minKey = "min-" + kind;
        String maxKey = "max-" + kind;
        String previous = current != null && current.isInt(minKey) && current.isInt(maxKey)
                ? current.getInt(minKey) + " " + current.getInt(maxKey) : "desactivar";
        menus.input(player, "Escribí el mínimo y máximo separados por un espacio, o desactivar.", previous,
                back, value -> {
                    int[] range = parseRange(value, min, max);
                    editPlacement(project, id, rules -> {
                        rules.set(minKey, range == null ? null : range[0]);
                        rules.set(maxKey, range == null ? null : range[1]);
                    }, back);
                });
    }

    private void editPlacement(String project, String id, Consumer<ConfigurationSection> update, Menus.Action back)
            throws Exception {
        menus.projects.editConfig(project, config -> {
            ConfigurationSection group = requireGroup(config, id);
            if (!group.getString("terrain-adaptation", "none").equals("none")) {
                throw new IllegalArgumentException("Antes de usar el modo combinado, poné Ajuste del terreno en Ninguno.");
            }
            ConfigurationSection rules = group.getConfigurationSection("placement");
            if (rules == null) {
                rules = group.createSection("placement");
                String mode = group.getString("heightmap", "surface");
                rules.set("surface", mode.equals("surface"));
                rules.set("ocean-floor", mode.equals("ocean_floor"));
                if (mode.equals("fixed")) {
                    rules.set("fixed-height", group.getInt("height", 64));
                }
            }
            update.accept(rules);
            boolean absolute = validateRange(rules, "height", MIN_Y, MAX_Y);
            boolean depth = validateRange(rules, "depth", 0, MAX_DEPTH);
            boolean fixed = rules.isInt("fixed-height");
            if (rules.contains("fixed-height") && (!fixed || rules.getInt("fixed-height") < MIN_Y
                    || rules.getInt("fixed-height") > MAX_Y)) {
                throw new IllegalArgumentException("Revisá la altura fija. Tiene que ser un entero entre -2032 y 2031.");
            }
            if (!rules.getBoolean("surface") && !rules.getBoolean("ocean-floor") && !fixed && !absolute && !depth) {
                throw new IllegalArgumentException("Dejá al menos una zona de aparición activa.");
            }
            if (fixed) {
                checkHeight("fixed", rules.getInt("fixed-height"), group.getInt("height-offset", 0));
            }
        });
        back.run();
    }

    private static boolean validateRange(ConfigurationSection rules, String kind, int low, int high) {
        String minKey = "min-" + kind;
        String maxKey = "max-" + kind;
        if (!rules.contains(minKey) && !rules.contains(maxKey)) {
            return false;
        }
        int min = rules.getInt(minKey);
        int max = rules.getInt(maxKey);
        if (!rules.isInt(minKey) || !rules.isInt(maxKey) || min < low || min > max || max > high) {
            throw new IllegalArgumentException("Revisá el mínimo y máximo del rango entre " + low + " y " + high + ".");
        }
        return true;
    }

    private static int[] parseRange(String input, int min, int max) {
        if (inactive(input)) {
            return null;
        }
        String[] parts = input.strip().split("[\\s,]+", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Escribí dos enteros separados por un espacio, o desactivar.");
        }
        int start = whole(parts[0], min, max);
        int end = whole(parts[1], min, max);
        if (start > end) {
            throw new IllegalArgumentException("El mínimo debe ser menor o igual al máximo.");
        }
        return new int[]{start, end};
    }

    private static boolean inactive(String input) {
        return input.strip().equalsIgnoreCase("desactivar");
    }

    private static String enabled(boolean value) {
        return value ? "sí" : "no";
    }

    private static String rangeLabel(ConfigurationSection rules, String kind) {
        return rules != null && rules.isInt("min-" + kind) && rules.isInt("max-" + kind)
                ? rules.getInt("min-" + kind) + " a " + rules.getInt("max-" + kind) : "desactivado";
    }

    private static String placementSummary(ConfigurationSection group) {
        ConfigurationSection rules = group.getConfigurationSection("placement");
        if (rules == null) {
            String mode = group.getString("heightmap", "surface");
            return "Nativo · " + (mode.equals("fixed") ? "Y " + group.getInt("height", 64) : heightLabel(mode));
        }
        List<String> modes = new ArrayList<>();
        if (rules.getBoolean("surface")) {
            modes.add("superficie");
        }
        if (rules.getBoolean("ocean-floor")) {
            modes.add("fondo oceánico");
        }
        if (rules.isInt("fixed-height")) {
            modes.add("Y " + rules.getInt("fixed-height"));
        }
        if (rules.isInt("min-height") && rules.isInt("max-height")) {
            modes.add("Y " + rangeLabel(rules, "height"));
        }
        if (rules.isInt("min-depth") && rules.isInt("max-depth")) {
            modes.add("prof. " + rangeLabel(rules, "depth"));
        }
        return "Combinado · " + (modes.isEmpty() ? "sin zonas activas" : String.join(" · ", modes));
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
                        name -> name, name -> kind.icon, reopen,
                        name -> editVariant(player, project, id, name, reopen),
                        name -> List.of("Peso relativo: " + weights.get(name))));
        view.button(24, Material.CHEST, "Agregar desde el catálogo", () -> {
            List<String> available = templates(config, menus.projects.listTemplates(project), project, null, kind).stream()
                    .filter(name -> !weights.containsKey(name)).toList();
            menus.choose(player, "Nueva variante · " + id, available, name -> name, name -> kind.icon, reopen, name ->
                    menus.input(player, "Escribí el peso relativo de " + name + " (1–150).", "1", reopen,
                            value -> editWeight(project, id, name, whole(value, 1, 150), reopen)),
                    name -> templateLore(config, project, null, name));
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

    private void createPlant(Player player, String project, String biome, String template) throws Exception {
        Menus.Action back = () -> open(player, project, biome);
        menus.input(player, "Escribí el ID de la vegetación, por ejemplo flor_lunar.", template, back, value -> {
            String name = inputId(value);
            if (config(project).contains("vegetation." + name)) {
                throw new IllegalArgumentException("Ya existe vegetación con ese ID.");
            }
            if (template != null) {
                registerPlant(player, project, biome, name, "template", template, back);
                return;
            }
            choosePlantMode(player, project, name, () -> createPlant(player, project, biome, null),
                    (mode, source) -> registerPlant(player, project, biome, name, mode, source, back));
        });
    }

    private void registerPlant(Player player, String project, String biome, String name, String mode, String source,
                               Menus.Action back) throws Exception {
        verifyPlantTemplate(project, mode, source);
        menus.projects.editConfig(project, config -> {
            String path = "vegetation." + name;
            if (config.contains(path)) {
                throw new IllegalArgumentException("Ya existe vegetación con ese ID.");
            }
            if (biome != null) {
                requireBiome(config, biome);
            }
            ConfigurationSection entry = config.createSection(path);
            setPlantSource(entry, mode, source);
            entry.set("count", 4);
            entry.set("biomes", biome == null ? List.of() : List.of(biome));
        });
        plant(player, project, name, back);
    }

    private void choosePlantMode(Player player, String project, String name, Menus.Action back, PlantSource picked) {
        Menus.Action reopen = () -> choosePlantMode(player, project, name, back, picked);
        Menus.View view = menus.menu(player, "Modo de vegetación · " + name);
        view.button(20, Material.POPPY, "Bloque vanilla", () ->
                choosePlantBlock(player, "Bloque para " + name, "minecraft:blue_orchid", reopen,
                        block -> picked.select("block", block)),
                "Flores, pasto, hongos y otros bloques del juego.");
        view.button(24, Material.STRUCTURE_BLOCK, "Plantilla capturada", () ->
                choosePlantTemplate(player, project, name, reopen, template -> picked.select("template", template)),
                "Usá los bloques de una plantilla guardada en el catálogo del proyecto.");
        view.button(49, Material.ARROW, "Volver", back);
        view.open();
    }

    private void choosePlantTemplate(Player player, String project, String name, Menus.Action back, Menus.Answer picked)
            throws Exception {
        YamlConfiguration config = config(project);
        List<String> saved = menus.projects.listTemplates(project);
        if (saved.isEmpty()) {
            Menus.View view = menus.menu(player, "Plantilla para " + name);
            view.button(22, Material.PAPER, "El catálogo está vacío", () -> {},
                    "Volvé al catálogo y capturá una plantilla para usarla como vegetación.");
            view.button(49, Material.ARROW, "Volver", back);
            view.open();
            return;
        }
        menus.choose(player, "Plantilla para " + name, saved, template -> template,
                template -> kind(config, template).icon, back, picked::accept,
                template -> templateLore(config, project, null, template));
    }

    private void changePlantSource(String project, String name, String mode, String source, Menus.Action back) throws Exception {
        verifyPlantTemplate(project, mode, source);
        menus.projects.editConfig(project, config -> setPlantSource(requirePlant(config, name), mode, source));
        back.run();
    }

    private void verifyPlantTemplate(String project, String mode, String source) throws Exception {
        if (mode.equals("template") && !menus.projects.listTemplates(project).contains(source)) {
            throw new IllegalArgumentException("La plantilla ya no existe en el catálogo del proyecto.");
        }
    }

    private static void setPlantSource(ConfigurationSection entry, String mode, String source) {
        boolean template = mode.equals("template");
        entry.set("mode", mode);
        entry.set("template", template ? source : null);
        entry.set("block", template ? null : source);
        entry.set(template ? "support" : "allow-void", null);
    }

    private void plant(Player player, String project, String name, Menus.Action back) throws Exception {
        YamlConfiguration config = config(project);
        String path = "vegetation." + name;
        ConfigurationSection entry = requirePlant(config, name);
        boolean template = isPlantTemplate(entry);
        String block = entry.getString("block", "minecraft:blue_orchid");
        String templateName = entry.getString("template", "sin plantilla");
        int count = entry.getInt("count", 4);
        String heightmap = entry.getString("heightmap", "surface");
        String substrate = substrateLabel(entry);
        List<String> biomes = plantBiomes(config, project, name);
        Menus.Action reopen = () -> plant(player, project, name, back);
        Menus.View view = menus.menu(player, "Vegetación · " + name);
        view.button(4, Material.COMPASS, "Modo: " + (template ? "Plantilla capturada" : "Bloque vanilla"), () ->
                choosePlantMode(player, project, name, reopen,
                        (mode, source) -> changePlantSource(project, name, mode, source, reopen)),
                "Cambiá el tipo y elegí lo que se coloca. Se conservan los biomas, los intentos y la altura.");
        if (template) {
            view.button(10, plantIcon(config, name), "Plantilla: " + templateName, () ->
                    choosePlantTemplate(player, project, name, reopen,
                            value -> changePlantSource(project, name, "template", value, reopen)));
            boolean allowVoid = entry.getBoolean("allow-void");
            view.button(29, Material.GLASS, "Permitir vacío: " + enabled(allowVoid), () ->
                    set(project, path + ".allow-void", !allowVoid, reopen),
                    "Permite colocar la base aunque no haya un bloque sólido debajo.",
                    "Se conserva cualquier filtro de bloques de apoyo que hayas indicado.");
        } else {
            view.button(10, plantIcon(config, name), "Bloque: " + shortBlock(block), () ->
                    choosePlantBlock(player, "Bloque para " + name, block, reopen,
                            value -> changePlantSource(project, name, "block", value, reopen)),
                    shortBlock(block).equals("sea_pickle") ? "Para colocarlo en seco, usá Superficie y Cualquier apoyo."
                            : "Elegí cualquier bloque vanilla de la lista o escribí su ID.");
            boolean vanilla = entry.getString("support", "any").equals("vanilla");
            view.button(29, Material.FARMLAND, "Soporte: " + (vanilla ? "Reglas vanilla" : "Cualquier apoyo"), () ->
                    set(project, path + ".support", vanilla ? "any" : "vanilla", reopen),
                    "Reglas vanilla respeta la supervivencia normal de la planta al generarla.",
                    "Cualquier apoyo permite generarla sobre cualquier bloque sólido con apoyo automático.",
                    "Un filtro de bloques de apoyo se aplica en cualquiera de los dos modos.");
        }
        view.button(12, Material.BONE_MEAL, "Intentos por chunk: " + count, () -> menus.input(player,
                "Escribí un valor de 1 a 128.", Integer.toString(count), reopen,
                value -> set(project, path + ".count", whole(value, 1, 128), reopen)),
                "La vegetación aparece donde se cumplan las reglas que configuraste.");
        view.button(14, Material.DIRT, "Bloques de apoyo", () -> menus.input(player,
                "Escribí bloques de apoyo separados por comas, o auto.", substrate, reopen,
                value -> set(project, path + ".substrate", substrates(value), reopen)),
                substrate.equals("auto") ? automaticSubstrate(entry) : substrate);
        view.button(16, Material.FEATHER, "Altura: " + heightLabel(heightmap), () ->
                menus.choose(player, "Altura de vegetación", List.of("surface", "ocean_floor"),
                        CatalogMenus::heightLabel, CatalogMenus::heightIcon, reopen,
                        value -> set(project, path + ".heightmap", value, reopen)),
                "Superficie coloca en seco. Fondo permite plantas acuáticas bajo el agua.");
        view.button(22, Material.GRASS_BLOCK, "Biomas · " + biomes.size(),
                () -> plantBiomeList(player, project, name, reopen),
                biomeSummary(biomes));
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
            ConfigurationSection entry = requirePlant(config, name);
            List<String> targets = plantBiomes(config, project, name);
            if (attach) {
                if (!targets.contains(biome)) {
                    targets.add(biome);
                }
            } else {
                targets.remove(biome);
            }
            entry.set("biomes", targets);
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
        return keys(config, "vegetation").stream().filter(name -> config.isConfigurationSection("vegetation." + name))
                .filter(name -> biome == null || plantBiomes(config, project, name).contains(biome)).toList();
    }

    private static List<String> plantBiomes(YamlConfiguration config, String project, String name) {
        return localBiomes(project, config.getStringList("vegetation." + name + ".biomes"));
    }

    private static Material plantIcon(YamlConfiguration config, String name) {
        ConfigurationSection entry = requirePlant(config, name);
        if (isPlantTemplate(entry)) {
            return kind(config, entry.getString("template", "sin_plantilla")).icon;
        }
        Material material = Material.matchMaterial(entry.getString("block", "blue_orchid"));
        return material != null && !material.isLegacy() && material.isItem() && !material.isAir() ? material : Material.BONE_MEAL;
    }

    private static boolean isPlantTemplate(ConfigurationSection entry) {
        return entry.getString("mode", "block").equals("template");
    }

    private static ConfigurationSection requirePlant(YamlConfiguration config, String name) {
        ConfigurationSection entry = config.getConfigurationSection("vegetation." + name);
        if (entry == null) {
            throw new IllegalArgumentException("Ese elemento de vegetación ya no existe.");
        }
        return entry;
    }

    private static List<String> plantLore(YamlConfiguration config, String project, String name) {
        ConfigurationSection entry = requirePlant(config, name);
        boolean template = isPlantTemplate(entry);
        List<String> targets = plantBiomes(config, project, name);
        String source = template ? "Plantilla: " + entry.getString("template", "sin plantilla")
                : "Bloque: " + shortBlock(entry.getString("block", "sin bloque"));
        String support = template ? "Permite vacío: " + enabled(entry.getBoolean("allow-void"))
                : "Soporte: " + (entry.getString("support", "any").equals("vanilla") ? "reglas vanilla" : "cualquier apoyo");
        String substrate = substrateLabel(entry);
        return List.of(source, entry.getInt("count", 4) + " intentos por chunk · " + heightLabel(entry.getString("heightmap", "surface")),
                support, "Bloques de apoyo: " + (substrate.equals("auto") ? automaticSubstrate(entry) : substrate), biomeSummary(targets));
    }

    private static List<String> templateLore(YamlConfiguration config, String project, String biome, String name) {
        List<String> lore = new ArrayList<>();
        lore.add("Categoría: " + kind(config, name).label);
        List<String> uses = new ArrayList<>();
        for (String group : groups(config, project, biome)) {
            Integer weight = variants(requireGroup(config, group), group).get(name);
            if (weight != null) {
                uses.add(group + " (peso " + weight + ")");
            }
        }
        lore.add(uses.isEmpty() ? "Sin grupos en esta vista." : "Grupos: " + summarize(uses));
        List<String> vegetation = plants(config, project, biome).stream().filter(plant -> {
            ConfigurationSection entry = requirePlant(config, plant);
            return isPlantTemplate(entry) && name.equals(entry.getString("template"));
        }).toList();
        if (!vegetation.isEmpty()) {
            lore.add("Vegetación: " + summarize(vegetation));
        }
        return lore;
    }

    private static List<String> groupLore(YamlConfiguration config, String project, String id) {
        ConfigurationSection group = requireGroup(config, id);
        int spacing = group.getInt("spacing", groupKind(group) == Kind.TREE ? 2 : 32);
        Map<String, Integer> weights = variants(group, id);
        return List.of(weights.size() + " variantes · " + spacing + " chunks entre intentos",
                "Plantillas: " + summarize(weights.entrySet().stream().map(variant -> variant.getKey() + " ×" + variant.getValue()).toList()),
                "Probabilidad: " + group.getDouble("frequency", 1.0), placementSummary(group),
                attemptsSummary(group.getConfigurationSection("placement")),
                biomeSummary(groupBiomes(config, project, group)));
    }

    private static String attemptsSummary(ConfigurationSection rules) {
        int attempts = rules == null ? 1 : rules.getInt("attempts", 1);
        return attempts + (attempts == 1 ? " intento" : " intentos") + " por chunk elegible";
    }

    private static String automaticSubstrate(ConfigurationSection plant) {
        return isPlantTemplate(plant) && plant.getBoolean("allow-void")
                ? "Automático: permite vacío." : "Automático: cualquier bloque sólido.";
    }

    private static String biomeSummary(List<String> targets) {
        return targets.isEmpty() ? "Sin biomas: guardado en el catálogo." : "Biomas: " + summarize(targets);
    }

    private static String summarize(List<String> values) {
        if (values.isEmpty()) {
            return "ninguna";
        }
        String visible = String.join(", ", values.stream().limit(3).toList());
        return values.size() > 3 ? visible + " y " + (values.size() - 3) + " más" : visible;
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

    private static ConfigurationSection requireNativeGroup(YamlConfiguration config, String id) {
        ConfigurationSection group = requireGroup(config, id);
        if (group.contains("placement")) {
            throw new IllegalArgumentException("El grupo usa el modo combinado. Revisá sus zonas de aparición.");
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
        if (result < MIN_Y || result > MAX_Y) {
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

    @FunctionalInterface
    private interface PlantSource {
        void select(String mode, String source) throws Exception;
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
