package dev.mira.worldgen;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public final class MDVGenerator extends JavaPlugin implements Listener {
    private static final List<String> ACTIONS = List.of("menu", "crear", "proyectos", "pos1", "pos2", "guardar",
            "actualizar", "copiar", "validar", "exportar", "instalar", "entrada", "cancelar");
    private final Capture capture = new Capture();
    private Projects projects;
    private Menus menus;

    @Override
    public void onEnable() {
        if (!getServer().getMinecraftVersion().equals("1.21.6")) {
            getLogger().severe("Este exportador requiere Minecraft 1.21.6 (formato de datapack 80).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getPluginManager().getPlugin("GeneraMundos") != null) {
            getLogger().severe("Retirá el JAR de GeneraMundos y reiniciá para migrar sus proyectos a MDVGenerator.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        projects = new Projects(this);
        try {
            int migrated = projects.migrateLegacy();
            if (migrated > 0) {
                getLogger().info("Proyectos importados de GeneraMundos: " + migrated);
            }
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "No se pudieron copiar los proyectos de GeneraMundos.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        menus = new Menus(this, projects, capture);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menus, this);
    }

    @Override
    public void onDisable() {
        if (menus != null) {
            menus.shutdown();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        capture.clear(event.getPlayer().getUniqueId());
        menus.forget(event.getPlayer());
    }

    static boolean hasAccess(CommandSender sender) {
        return sender.hasPermission("mdvgenerator.admin") || sender.hasPermission("generamundos.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAccess(sender)) {
            sender.sendMessage("Necesitás el permiso mdvgenerator.admin.");
            return true;
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
                        throw new IllegalArgumentException("Uso /mdv entrada <valor>");
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
                    sender.sendMessage(names.isEmpty() ? "Todavía no hay proyectos. Usá /gm crear <proyecto>."
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
                case "validar" -> {
                    requireArgs(args, 2, "validar <proyecto>");
                    projects.validate(args[1]);
                    sender.sendMessage("El proyecto está listo para exportar.");
                }
                case "exportar" -> {
                    requireArgs(args, 2, "exportar <proyecto>");
                    sender.sendMessage("Datapack exportado a " + projects.export(args[1]) + ".");
                }
                case "instalar" -> {
                    requireArgs(args, 2, "instalar <proyecto>");
                    var mainWorld = getServer().getWorlds().getFirst();
                    var path = projects.install(args[1], mainWorld.getWorldFolder().toPath().resolve("datapacks"));
                    sender.sendMessage("Datapack instalado en " + path + ".");
                    sender.sendMessage("Reiniciá el servidor. La dimensión es " + Projects.namespace(args[1]) + ":world.");
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("MDVGenerator · " + ex.getMessage());
        } catch (IOException ex) {
            sender.sendMessage("MDVGenerator · Error de archivo. Revisá la consola. Si el ZIP está en uso, reemplazalo con el servidor apagado.");
            getLogger().log(Level.WARNING, "Falló /mdv " + args[0], ex);
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
        } else if (args.length == 2 && List.of("menu", "guardar", "actualizar", "copiar", "validar", "exportar", "instalar").contains(args[0])
                || args.length == 4 && args[0].equals("copiar")) {
            try {
                options = projects.list();
            } catch (IOException ex) {
                getLogger().log(Level.FINE, "No se pudieron listar los proyectos", ex);
            }
        } else if (args.length == 5 && args[0].equals("guardar")) {
            options = List.of("arbol", "estructura", "otro");
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
            throw new IllegalArgumentException("Uso /mdv " + usage);
        }
    }

    private static boolean optionalEntities(String[] args, int count, String usage) {
        if (args.length == count + 1 && args[count].equals("--entidades")) {
            return true;
        }
        requireArgs(args, count, usage + " [--entidades]");
        return false;
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("/mdv abre el menú dentro del juego. /mdv menu [proyecto] abre un proyecto.");
        sender.sendMessage("/mdv crear <proyecto> · /mdv proyectos");
        sender.sendMessage("/mdv pos1 · /mdv pos2");
        sender.sendMessage("/mdv guardar <proyecto> <plantilla> <bioma> <arbol|estructura|otro> [--entidades]");
        sender.sendMessage("/mdv actualizar <proyecto> <plantilla> [--entidades]");
        sender.sendMessage("/mdv copiar <origen> <plantilla> <destino> <bioma>");
        sender.sendMessage("/mdv validar <proyecto> · /mdv exportar <proyecto> · /mdv instalar <proyecto>");
    }
}
