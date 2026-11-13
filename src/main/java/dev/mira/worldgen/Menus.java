package dev.mira.worldgen;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

final class Menus implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final long INPUT_TIMEOUT = Duration.ofMinutes(2).toNanos();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    final Projects projects;
    final Capture capture;
    private final MDVDimensions plugin;
    private final PackJobs packJobs;
    private final NamespacedKey wandKey;
    private final BiomeMenus biomes;
    private final CatalogMenus catalog;
    private final RawMenus raw;
    private final Map<UUID, String> recent = new HashMap<>();
    private final Map<UUID, Input> pending = new ConcurrentHashMap<>();

    Menus(MDVDimensions plugin, Projects projects, Capture capture, PackJobs packJobs) {
        this.plugin = plugin;
        this.projects = projects;
        this.capture = capture;
        this.packJobs = packJobs;
        wandKey = new NamespacedKey(plugin, "selector");
        biomes = new BiomeMenus(this);
        catalog = new CatalogMenus(this);
        raw = new RawMenus(this);
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface Answer {
        void accept(String text) throws Exception;
    }

    @FunctionalInterface
    interface Choice<T> {
        void select(T value) throws Exception;
    }

    private record Input(String prompt, String current, Action back, Answer answer, long expiresAt) {}

    void home(Player player) throws Exception {
        String last = recent.get(player.getUniqueId());
        if (last != null && projects.list().contains(last)) {
            project(player, last);
        } else {
            projects(player);
        }
    }

    void projects(Player player) throws Exception {
        List<String> available = projects.list();
        var view = menu(player, "MDVDimensions · Proyectos");
        view.button(11, Material.BOOKSHELF, "Abrir proyecto", () -> choose(player, "Elegí un proyecto", available,
                Function.identity(), ignored -> Material.MAP, () -> projects(player), id -> project(player, id)),
                available.size() + " proyecto(s) guardados");
        view.button(15, Material.WRITABLE_BOOK, "Crear proyecto", () -> input(player,
                "ID del proyecto (por ejemplo arena_volcanica)", null, () -> projects(player), id -> {
                    projects.create(id);
                    project(player, id);
                }), "Usá minúsculas, números y guion bajo.");
        view.button(49, Material.BARRIER, "Cerrar", player::closeInventory);
        view.open();
    }

    void project(Player player, String project) throws Exception {
        var config = projects.readConfig(project);
        var biomeSettings = config.getConfigurationSection("biomes");
        var placementGroups = config.getConfigurationSection("structures");
        var vegetation = config.getConfigurationSection("vegetation");
        recent.put(player.getUniqueId(), project);
        var view = menu(player, "MDVDimensions · " + project);
        view.button(10, Material.FILLED_MAP, "Biomas", () -> biomes.list(player, project),
                (biomeSettings == null ? 0 : biomeSettings.getKeys(false).size()) + " bioma(s) configurados",
                "Base vanilla, clima, relieve, capas y criaturas");
        view.button(13, Material.COMPASS, "Mundo", () -> biomes.world(player, project),
                "Terreno: " + config.getString("world.terrain", "overworld"),
                "Cielo: " + config.getString("world.environment", "overworld"),
                "Reglas: " + config.getString("world.rules", "según el cielo"));
        view.button(16, Material.CHEST, "Catálogo", () -> catalog(player, project, null),
                projects.listTemplates(project).size() + " plantilla(s)",
                (vegetation == null ? 0 : vegetation.getKeys(false).size()) + " elemento(s) de flora",
                (placementGroups == null ? 0 : placementGroups.getKeys(false).size()) + " grupo(s) de aparición",
                "Árboles, estructuras, vegetación y otros elementos");
        view.button(22, Material.WOODEN_AXE, "Herramienta de selección", () -> selector(player),
                "Marcá los dos extremos antes de capturar una plantilla.");
        view.button(29, Material.SPYGLASS, "Validar",
                () -> packJobs.submit(player, project, PackJobs.Operation.VALIDAR),
                "Comprueba la configuración y las plantillas.");
        view.button(31, Material.WRITABLE_BOOK, "Exportar ZIP",
                () -> packJobs.submit(player, project, PackJobs.Operation.EXPORTAR),
                "Guardá un datapack listo para instalar.");
        view.button(33, Material.ENDER_CHEST, "Instalar datapack", () -> confirm(player, "Instalar " + project,
                () -> project(player, project), () -> {
                    packJobs.submit(player, project, PackJobs.Operation.INSTALAR);
                    project(player, project);
                }, "Reemplaza el ZIP de este proyecto y guarda una copia del anterior.",
                "Dimensión: " + Projects.namespace(project) + ":world"));
        view.button(45, Material.ARROW, "Proyectos", () -> projects(player));
        view.button(49, Material.PAPER, "Resumen", () -> {},
                "Dimensión: " + Projects.namespace(project) + ":world",
                "Terreno: " + config.getString("world.terrain", "overworld"));
        view.button(53, Material.BARRIER, "Cerrar", player::closeInventory);
        view.open();
    }

    void biome(Player player, String project, String biome) throws Exception {
        biomes.biome(player, project, biome);
    }

    void catalog(Player player, String project, String biome) throws Exception {
        catalog.open(player, project, biome);
    }

    void raw(Player player, String project, String path, String title, Action back) throws Exception {
        raw.open(player, project, path, title, back);
    }

    void confirm(Player player, String title, Action back, Action confirmed, String... details) {
        var view = menu(player, title);
        view.button(20, Material.LIME_CONCRETE, "Confirmar", confirmed, details);
        view.button(24, Material.RED_CONCRETE, "Volver", back);
        view.open();
    }

    View menu(Player player, String title) {
        return new View(player, title);
    }

    final class View implements InventoryHolder {
        private final Player player;
        private final Inventory inventory;
        private final Map<Integer, Action> buttons = new HashMap<>();

        private View(Player player, String title) {
            this.player = player;
            inventory = Bukkit.createInventory(this, 54, Component.text(title, NamedTextColor.DARK_AQUA));
        }

        View button(int slot, Material icon, String title, Action action, String... lore) {
            inventory.setItem(slot, item(icon, title, lore));
            buttons.put(slot, action);
            return this;
        }

        void open() {
            pending.remove(player.getUniqueId());
            player.openInventory(inventory);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    <T> void choose(Player player, String title, List<T> entries, Function<T, String> label,
                    Function<T, Material> icon, Action back, Choice<T> picked) {
        choose(player, title, entries, label, icon, back, picked, ignored -> List.of());
    }

    <T> void choose(Player player, String title, List<T> entries, Function<T, String> label,
                    Function<T, Material> icon, Action back, Choice<T> picked, Function<T, List<String>> lore) {
        page(player, title, List.copyOf(entries), label, icon, back, picked, lore, "", 0);
    }

    private <T> void page(Player player, String title, List<T> entries, Function<T, String> label,
                          Function<T, Material> icon, Action back, Choice<T> picked, Function<T, List<String>> lore,
                          String filter, int page) {
        String query = searchable(filter);
        var shown = query.isEmpty() ? entries : entries.stream().filter(entry ->
                searchable(label.apply(entry) + " " + String.join(" ", lore.apply(entry))).contains(query)).toList();
        int pages = Math.max(1, (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int from = current * PAGE_SIZE;
        var view = menu(player, title);
        for (int index = from; index < Math.min(from + PAGE_SIZE, shown.size()); index++) {
            T entry = shown.get(index);
            view.button(index - from, icon.apply(entry), label.apply(entry), () -> picked.select(entry),
                    lore.apply(entry).toArray(String[]::new));
        }
        if (shown.isEmpty()) {
            view.button(22, Material.PAPER, "Sin resultados", () -> {}, "Probá con otro término.");
        }
        view.button(45, Material.ARROW, "Volver", back);
        if (current > 0) {
            view.button(46, Material.FEATHER, "Página anterior",
                    () -> page(player, title, entries, label, icon, back, picked, lore, filter, current - 1));
        }
        view.button(48, Material.SPYGLASS, "Buscar", () -> input(player, "Escribí un término para buscar", filter,
                () -> page(player, title, entries, label, icon, back, picked, lore, filter, current),
                text -> page(player, title, entries, label, icon, back, picked, lore, text, 0)));
        view.button(49, Material.PAPER, "Página " + (current + 1) + " de " + pages, () -> {},
                shown.size() + " resultado(s)", filter.isBlank() ? "Todos" : "Filtro: " + filter);
        if (!filter.isBlank()) {
            view.button(50, Material.BUCKET, "Limpiar búsqueda",
                    () -> page(player, title, entries, label, icon, back, picked, lore, "", 0));
        }
        if (current + 1 < pages) {
            view.button(52, Material.FEATHER, "Página siguiente",
                    () -> page(player, title, entries, label, icon, back, picked, lore, filter, current + 1));
        }
        view.open();
    }

    private static String searchable(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .strip().toLowerCase(Locale.ROOT);
    }

    void input(Player player, String prompt, String current, Action back, Answer answer) {
        pending.put(player.getUniqueId(), new Input(prompt, current, back, answer, System.nanoTime() + INPUT_TIMEOUT));
        player.closeInventory();
        showPrompt(player, prompt, current);
    }

    private static void showPrompt(Player player, String prompt, String current) {
        player.sendMessage(Component.text("MDVDimensions · " + prompt, NamedTextColor.AQUA));
        if (current != null && !current.isBlank()) {
            String preview = current.length() > 170 ? current.substring(0, 167) + "…" : current;
            player.sendMessage(Component.text("Actual: " + preview, NamedTextColor.GRAY));
        }
        player.sendMessage(Component.text("Escribí en el chat o usá /dim entrada <valor>. /dim cancelar vuelve al menú.",
                NamedTextColor.GRAY));
    }

    void submit(Player player, String text) {
        Input request = pending.get(player.getUniqueId());
        if (request == null) {
            player.sendMessage("MDVDimensions · Abrí una edición desde /dim antes de ingresar un valor.");
            return;
        }
        submit(player, text, request);
    }

    private void submit(Player player, String text, Input request) {
        UUID id = player.getUniqueId();
        if (!pending.remove(id, request) || !player.isOnline() || !MDVDimensions.hasAccess(player)) {
            return;
        }
        if (System.nanoTime() >= request.expiresAt()) {
            player.sendMessage("MDVDimensions · La edición venció. Abrila otra vez desde el menú.");
            run(player, request.back());
            return;
        }
        try {
            request.answer().accept(text.strip());
        } catch (IllegalArgumentException ex) {
            player.sendMessage("MDVDimensions · " + ex.getMessage());
            input(player, request.prompt(), request.current(), request.back(), request.answer());
        } catch (Exception ex) {
            report(player, ex);
        }
    }

    void cancel(Player player) {
        Input request = pending.remove(player.getUniqueId());
        if (request != null) {
            run(player, request.back());
        } else {
            run(player, () -> home(player));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Input request = pending.get(player.getUniqueId());
        if (request == null) {
            return;
        }
        event.setCancelled(true);
        String text = PLAIN.serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> submit(player, text, request));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.equals(view.player)
                || event.getRawSlot() < 0 || event.getRawSlot() >= view.inventory.getSize()
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
            return;
        }
        Action action = view.buttons.get(event.getRawSlot());
        if (action != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() == view) {
                    run(player, action);
                }
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }

    void selector(Player player) {
        player.closeInventory();
        if (Arrays.stream(player.getInventory().getStorageContents()).noneMatch(this::isSelector)) {
            var wand = item(Material.WOODEN_AXE, "Selector de MDVDimensions",
                    "Clic izquierdo: esquina 1", "Clic derecho: esquina 2", "Después abrí /dim para guardar.");
            wand.editMeta(meta -> meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1));
            if (!player.getInventory().addItem(wand).isEmpty()) {
                throw new IllegalArgumentException("Liberá un espacio en el inventario para recibir el selector.");
            }
        }
        player.sendMessage("MDVDimensions · Izquierdo marca la esquina 1, derecho marca la 2. Volvé con /dim.");
    }

    private boolean isSelector(ItemStack stack) {
        return stack != null && stack.getType() == Material.WOODEN_AXE && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSelect(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || !MDVDimensions.hasAccess(event.getPlayer()) || !isSelector(event.getItem())) {
            return;
        }
        int corner = switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> 1;
            case RIGHT_CLICK_BLOCK -> 2;
            default -> 0;
        };
        if (corner != 0) {
            event.setCancelled(true);
            run(event.getPlayer(), () -> mark(event.getPlayer(), corner, event.getClickedBlock().getLocation()));
        }
    }

    void mark(Player player, int corner, Location location) {
        Location pos = capture.select(player, corner, location);
        player.sendMessage("MDVDimensions · Esquina " + corner + " en " + pos.getWorld().getName()
                + " (" + pos.getBlockX() + ", " + pos.getBlockY() + ", " + pos.getBlockZ() + ").");
    }

    void run(Player player, Action action) {
        if (!player.isOnline() || !MDVDimensions.hasAccess(player)) {
            return;
        }
        try {
            action.run();
        } catch (Exception ex) {
            report(player, ex);
        }
    }

    private void report(Player player, Exception ex) {
        if (ex instanceof IllegalArgumentException) {
            player.sendMessage("MDVDimensions · " + ex.getMessage());
        } else if (ex instanceof IOException) {
            player.sendMessage("MDVDimensions · Error de archivo. Si el ZIP está en uso, reemplazalo con el servidor apagado.");
            plugin.getLogger().log(Level.WARNING, "Error de archivo desde el menú", ex);
        } else {
            player.sendMessage("MDVDimensions · La acción falló. Revisá la consola.");
            plugin.getLogger().log(Level.WARNING, "Falló una acción del menú", ex);
        }
    }

    void forget(Player player) {
        pending.remove(player.getUniqueId());
        recent.remove(player.getUniqueId());
    }

    void shutdown() {
        pending.clear();
        recent.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof View) {
                player.closeInventory();
            }
        }
    }

    private static ItemStack item(Material icon, String name, String... lore) {
        Material safeIcon = icon != null && icon.isItem() && !icon.isAir() ? icon : Material.PAPER;
        var item = new ItemStack(safeIcon);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(Arrays.stream(lore).map(line -> (Component) Component.text(line, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }
}
