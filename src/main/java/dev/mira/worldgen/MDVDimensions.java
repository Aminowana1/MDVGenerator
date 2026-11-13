package dev.mira.worldgen;

import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class MDVDimensions extends JavaPlugin implements Listener {
    private static final List<String> ACTIONS = List.of("menu", "crear", "proyectos", "pos1", "pos2", "guardar",
            "actualizar", "copiar", "pegar", "validar", "exportar", "instalar", "entrada", "cancelar");
    private static final String PASTE_USAGE = "pegar <proyecto> <plantilla> [0|90|180|270|azar] [--aire] [--bajar <bloques>]";
    private final Capture capture = new Capture();
    private Projects projects;
    private PackJobs packJobs;
    private Menus menus;
    private RuntimePlacements placements;
    private ViaEndSky viaEndSky;

    @Override
    public void onEnable() {
        if (!getServer().getMinecraftVersion().equals("1.21.6")) {
            getLogger().severe("Este exportador requiere Minecraft 1.21.6 (formato de datapack 80).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getPluginManager().getPlugin("GeneraMundos") != null
                || getServer().getPluginManager().getPlugin("MDVGenerator") != null) {
            getLogger().severe("Retirá los JAR anteriores de GeneraMundos o MDVGenerator y reiniciá con MDVDimensions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        projects = new Projects(this);
        try {
            int migrated = projects.migrateLegacy();
            if (migrated > 0) {
                getLogger().info("Proyectos importados de versiones anteriores: " + migrated);
            }
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "No se pudieron copiar los proyectos anteriores.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        packJobs = new PackJobs(this, projects);
        placements = new RuntimePlacements(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new NativeGround(), this);
        getServer().getPluginManager().registerEvents(placements, this);
        getServer().getWorlds().forEach(placements::attach);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        initializeMenus();
        if (viaEndSky == null && getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
            viaEndSky = new ViaEndSky(getLogger());
            viaEndSky.start();
        }
    }

    private void initializeMenus() {
        if (menus == null) {
            menus = new Menus(this, projects, capture, packJobs);
            getServer().getPluginManager().registerEvents(menus, this);
        }
    }

    @Override
    public void onDisable() {
        if (packJobs != null) {
            packJobs.shutdown();
        }
        if (viaEndSky != null) {
            viaEndSky.stop();
        }
        if (placements != null) {
            placements.detach();
        }
        if (menus != null) {
            menus.shutdown();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        capture.clear(event.getPlayer().getUniqueId());
        if (menus != null) {
            menus.forget(event.getPlayer());
        }
    }

    static boolean hasAccess(CommandSender sender) {
        return sender.hasPermission("mdvdimensions.admin") || sender.hasPermission("mdvgenerator.admin")
                || sender.hasPermission("generamundos.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAccess(sender)) {
            sender.sendMessage("Necesitás el permiso mdvdimensions.admin.");
            return true;
        }
        if (sender instanceof Player) {
            initializeMenus();
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                menus.run(player, () -> menus.home(player));
            } else {
                help(sender);
            }
            return true;
        }
        try {
            switch (args[0]) {
                case "menu" -> {
                    Player player = player(sender);
                    if (args.length == 2) {
                        menus.run(player, () -> menus.project(player, args[1]));
                    } else {
                        requireArgs(args, 1, "menu [proyecto]");
                        menus.run(player, () -> menus.projects(player));
                    }
                }
                case "entrada" -> {
                    if (args.length < 2) {
                        throw new IllegalArgumentException("Uso /dim entrada <valor>");
                    }
                    menus.submit(player(sender), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
                case "cancelar" -> {
                    requireArgs(args, 1, "cancelar");
                    menus.cancel(player(sender));
                }
                case "crear" -> {
                    requireArgs(args, 2, "crear <proyecto>");
                    sender.sendMessage("Proyecto creado. Editá " + projects.create(args[1]) + ".");
                }
                case "proyectos" -> {
                    requireArgs(args, 1, "proyectos");
                    var names = projects.list();
                    sender.sendMessage(names.isEmpty() ? "Todavía no hay proyectos. Usá /dim crear <proyecto>."
                            : "Proyectos disponibles · " + String.join(", ", names));
                }
                case "pos1", "pos2" -> {
                    requireArgs(args, 1, args[0]);
                    int corner = args[0].equals("pos1") ? 1 : 2;
                    Player player = player(sender);
                    menus.mark(player, corner, player.getLocation());
                }
                case "guardar" -> {
                    boolean entities = optionalEntities(args, 5, "guardar <proyecto> <plantilla> <bioma> <arbol|estructura|otro>");
                    String kind = switch (args[4]) {
                        case "arbol" -> "tree";
                        case "estructura" -> "building";
                        case "otro" -> "other";
                        default -> throw new IllegalArgumentException("Elegí arbol, estructura u otro.");
                    };
                    projects.capture(capture, player(sender), args[1], args[2], args[3], kind, entities);
                    sender.sendMessage("Plantilla «" + args[2] + "» guardada en «" + args[1] + "» para «" + args[3] + "».");
                }
                case "actualizar" -> {
                    boolean entities = optionalEntities(args, 3, "actualizar <proyecto> <plantilla>");
                    projects.recapture(capture, player(sender), args[1], args[2], entities);
                    sender.sendMessage("Plantilla actualizada. Volvé a exportar o instalar el proyecto para incluirla.");
                }
                case "copiar" -> {
                    requireArgs(args, 5, "copiar <origen> <plantilla> <destino> <bioma>");
                    projects.copy(args[1], args[2], args[3], args[4]);
                    sender.sendMessage("Plantilla copiada al proyecto «" + args[3] + "».");
                }
                case "pegar" -> {
                    var options = pasteOptions(args);
                    projects.paste(player(sender), args[1], args[2], options.rotation(), options.includeAir(), options.lowerBy());
                    sender.sendMessage("Plantilla «" + args[2] + "» pegada.");
                }
                case "validar", "exportar", "instalar" -> {
                    requireArgs(args, 2, args[0] + " <proyecto>");
                    packJobs.submit(sender, args[1], PackJobs.Operation.valueOf(args[0].toUpperCase(java.util.Locale.ROOT)));
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("MDVDimensions · " + ex.getMessage());
        } catch (IOException ex) {
            sender.sendMessage(args[0].equals("pegar") ? "MDVDimensions · No se pudo leer o pegar la plantilla. Revisá la consola."
                    : "MDVDimensions · Error de archivo. Revisá la consola. Si el ZIP está en uso, reemplazalo con el servidor apagado.");
            getLogger().log(Level.WARNING, "Falló /dim " + args[0], ex);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAccess(sender) || args.length == 0) {
            return List.of();
        }
        List<String> options = List.of();
        if (args.length == 1) {
            options = ACTIONS;
        } else if (args.length == 2 && List.of("menu", "guardar", "actualizar", "copiar", "pegar", "validar", "exportar", "instalar").contains(args[0])
                || args.length == 4 && args[0].equals("copiar")) {
            try {
                options = projects.list();
            } catch (IOException ex) {
                getLogger().log(Level.FINE, "No se pudieron listar los proyectos", ex);
            }
        } else if (args.length == 5 && args[0].equals("guardar")) {
            options = List.of("arbol", "estructura", "otro");
        } else if (args[0].equals("pegar") && args.length == 3) {
            try {
                options = projects.listTemplates(args[1]);
            } catch (IOException | IllegalArgumentException ignored) {
                return List.of();
            }
        } else if (args[0].equals("pegar") && args.length == 4) {
            options = List.of("0", "90", "180", "270", "azar", "--aire", "--bajar");
        }
        String prefix = args[args.length - 1];
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }

    private static Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalArgumentException("Usá este comando dentro del juego para trabajar con tu selección.");
    }

    private static void requireArgs(String[] args, int count, String usage) {
        if (args.length != count) {
            throw new IllegalArgumentException("Uso /dim " + usage);
        }
    }

    private static boolean optionalEntities(String[] args, int count, String usage) {
        if (args.length == count + 1 && args[count].equals("--entidades")) {
            return true;
        }
        requireArgs(args, count, usage + " [--entidades]");
        return false;
    }

    private static PasteOptions pasteOptions(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Uso /dim " + PASTE_USAGE);
        }
        StructureRotation rotation = null;
        boolean includeAir = false;
        Integer lowerBy = null;
        for (int index = 3; index < args.length; index++) {
            switch (args[index]) {
                case "--aire" -> {
                    if (includeAir) throw new IllegalArgumentException("Indicá --aire una sola vez.");
                    includeAir = true;
                }
                case "--bajar" -> {
                    if (lowerBy != null || ++index == args.length) {
                        throw new IllegalArgumentException("Indicá --bajar <bloques> una sola vez.");
                    }
                    try {
                        lowerBy = Integer.parseInt(args[index]);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Indicá un número entero después de --bajar.");
                    }
                    if (lowerBy < 0) throw new IllegalArgumentException("La profundidad de pegado no puede ser negativa.");
                }
                default -> {
                    if (rotation != null) throw new IllegalArgumentException("Indicá una sola rotación.");
                    rotation = switch (args[index]) {
                        case "0" -> StructureRotation.NONE;
                        case "90" -> StructureRotation.CLOCKWISE_90;
                        case "180" -> StructureRotation.CLOCKWISE_180;
                        case "270" -> StructureRotation.COUNTERCLOCKWISE_90;
                        case "azar" -> {
                            var rotations = StructureRotation.values();
                            yield rotations[ThreadLocalRandom.current().nextInt(rotations.length)];
                        }
                        default -> throw new IllegalArgumentException("Uso /dim " + PASTE_USAGE);
                    };
                }
            }
        }
        return new PasteOptions(rotation == null ? StructureRotation.NONE : rotation, includeAir, lowerBy == null ? 0 : lowerBy);
    }

    private record PasteOptions(StructureRotation rotation, boolean includeAir, int lowerBy) {}

    private static void help(CommandSender sender) {
        sender.sendMessage("/dim abre el menú dentro del juego. /dim menu [proyecto] abre un proyecto.");
        sender.sendMessage("/dim crear <proyecto> · /dim proyectos");
        sender.sendMessage("/dim pos1 · /dim pos2");
        sender.sendMessage("/dim guardar <proyecto> <plantilla> <bioma> <arbol|estructura|otro> [--entidades]");
        sender.sendMessage("/dim actualizar <proyecto> <plantilla> [--entidades]");
        sender.sendMessage("/dim copiar <origen> <plantilla> <destino> <bioma>");
        sender.sendMessage("/dim " + PASTE_USAGE);
        sender.sendMessage("/dim validar <proyecto> · /dim exportar <proyecto> · /dim instalar <proyecto>");
    }
}
