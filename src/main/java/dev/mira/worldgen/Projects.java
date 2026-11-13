package dev.mira.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class Projects {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern BIOME_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final String MARKER = "generamundos.proyecto";

    private final MDVDimensions plugin;
    private final Path data;
    private final Path projects;
    // Commands and menus take and release these reservations on the server thread.
    private final Set<String> packing = new HashSet<>();

    Projects(MDVDimensions plugin) {
        this.plugin = plugin;
        data = plugin.getDataFolder().toPath();
        projects = data.resolve("proyectos");
    }

    static String namespace(String project) {
        return "gm_" + id(project);
    }

    static String id(String value) {
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Usá un ID de hasta 32 caracteres: letras minúsculas, números y guion bajo. Empezá con una letra.");
        }
        return value;
    }

    int migrateLegacy() throws IOException {
        int imported = 0;
        for (String previous : List.of("MDVGenerator", "GeneraMundos")) {
            imported += importProjects(data.resolveSibling(previous).resolve("proyectos"));
        }
        return imported;
    }

    private int importProjects(Path oldProjects) throws IOException {
        if (!Files.isDirectory(oldProjects)) {
            return 0;
        }
        Files.createDirectories(projects);
        int imported = 0;
        try (var entries = Files.list(oldProjects)) {
            for (Path source : entries.filter(Files::isDirectory).toList()) {
                String name = source.getFileName().toString();
                if (!ID.matcher(name).matches() || !Files.isRegularFile(source.resolve("generacion.yml"))) {
                    continue;
                }
                Path destination = projects.resolve(name);
                if (Files.exists(destination)) {
                    continue;
                }
                Path staging = Files.createTempDirectory(projects, ".migracion-");
                try {
                    try (var files = Files.walk(source)) {
                        for (Path file : files.sorted().toList()) {
                            Path target = staging.resolve(source.relativize(file));
                            if (Files.isDirectory(file) && !Files.isSymbolicLink(file)) {
                                Files.createDirectories(target);
                            } else if (Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
                                Files.copy(file, target);
                            }
                        }
                    }
                    Files.move(staging, destination);
                    imported++;
                } finally {
                    if (Files.exists(staging)) {
                        clean(staging);
                    }
                }
            }
        }
        return imported;
    }

    List<String> list() throws IOException {
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        try (var entries = Files.list(projects)) {
            return entries.filter(path -> Files.isRegularFile(path.resolve("generacion.yml")))
                    .map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    Path create(String project) throws IOException {
        Path path = projects.resolve(id(project));
        Path config = path.resolve("generacion.yml");
        if (Files.exists(config)) {
            throw new IllegalArgumentException("Ese proyecto ya existe.");
        }
        Files.createDirectories(path.resolve("plantillas"));
        try (var template = plugin.getResource("generacion.yml")) {
            if (template == null) {
                throw new IOException("El plugin no incluye la configuración inicial.");
            }
            Files.copy(template, config);
        }
        return config;
    }

    private Path project(String project) {
        Path path = projects.resolve(id(project));
        if (!Files.isRegularFile(path.resolve("generacion.yml"))) {
            throw new IllegalArgumentException("El proyecto «" + project + "» no existe. Crealo con /dim crear " + project + ".");
        }
        return path;
    }

    Path beginPackOperation(String name) {
        Path path = editableProject(name);
        packing.add(name);
        return path;
    }

    void finishPackOperation(String name) {
        packing.remove(name);
    }

    private Path editableProject(String name) {
        if (packing.contains(name)) {
            throw new IllegalArgumentException("El proyecto «" + name + "» tiene una operación en curso. Esperá a que termine.");
        }
        return project(name);
    }

    private static YamlConfiguration read(Path path) throws IOException {
        var config = new YamlConfiguration();
        try {
            config.load(path.resolve("generacion.yml").toFile());
        } catch (InvalidConfigurationException ex) {
            throw new IllegalArgumentException("Revisá la sintaxis de generacion.yml: " + ex.getMessage(), ex);
        }
        return config;
    }

    YamlConfiguration readConfig(String name) throws IOException {
        return read(project(name));
    }

    void editConfig(String name, Consumer<YamlConfiguration> update) throws IOException {
        Path path = editableProject(name);
        var config = read(path);
        update.accept(config);
        writeConfig(path, config);
    }

    List<String> listTemplates(String name) throws IOException {
        Path folder = project(name).resolve("plantillas");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(".nbt"))
                    .map(file -> file.substring(0, file.length() - 4))
                    .filter(nameOnly -> ID.matcher(nameOnly).matches()).sorted().toList();
        }
    }

    void capture(Capture capture, Player player, String project, String name, String biome,
                 String kind, boolean entities) throws IOException {
        Path path = editableProject(project);
        var config = read(path);
        Path destination = register(path, config, name, biome, kind);
        capture.save(player, destination, entities);
        saveRegistration(path, config, destination);
    }

    void recapture(Capture capture, Player player, String project, String name, boolean entities) throws IOException {
        capture.save(player, existingTemplate(editableProject(project), name), entities);
    }

    void paste(Player player, String project, String name, StructureRotation rotation, boolean includeAir, int lowerBy)
            throws IOException {
        Capture.paste(player.getLocation(), existingTemplate(project(project), name), rotation, includeAir, lowerBy);
    }

    void captureTemplate(Capture capture, Player player, String project, String name, String kind,
                         boolean entities) throws IOException {
        Path path = editableProject(project);
        var config = read(path);
        Path destination = registerTemplate(path, config, name, kind);
        capture.save(player, destination, entities);
        saveRegistration(path, config, destination);
    }

    void copyTemplate(String origin, String name, String destination) throws IOException {
        Path from = project(origin);
        Path to = editableProject(destination);
        Path source = existingTemplate(from, name);
        var config = read(to);
        Path target = registerTemplate(to, config, name, templateKind(read(from), name));
        copyReplacing(source, target);
        saveRegistration(to, config, target);
    }

    void copy(String origin, String name, String destination, String biome) throws IOException {
        Path from = project(origin);
        Path to = editableProject(destination);
        var config = read(to);
        Path source = existingTemplate(from, name);
        Path target = register(to, config, name, biome, templateKind(read(from), name));
        copyReplacing(source, target);
        saveRegistration(to, config, target);
    }

    private static Path template(Path project, String name) {
        return project.resolve("plantillas").resolve(id(name) + ".nbt");
    }

    private static Path existingTemplate(Path project, String name) {
        Path path = template(project, name);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("La plantilla «" + name + "» no existe en ese proyecto.");
        }
        return path;
    }

    static String templateKind(YamlConfiguration config, String name) {
        String catalog = config.getString("catalog." + name + ".kind");
        if (catalog != null) {
            return catalog;
        }
        var groups = config.getConfigurationSection("structures");
        if (groups != null) {
            for (String id : groups.getKeys(false)) {
                ConfigurationSection group = groups.getConfigurationSection(id);
                if (group != null && usesTemplate(group, id, name)) {
                    return group.getString("kind", "building");
                }
            }
        }
        return "building";
    }

    private static boolean usesTemplate(ConfigurationSection group, String groupId, String template) {
        var weights = group.getConfigurationSection("templates");
        return weights != null ? weights.contains(template)
                : group.getStringList("templates").contains(template) || (!group.contains("templates") && groupId.equals(template));
    }

    private static Path register(Path project, YamlConfiguration config, String name, String biome,
                                 String kind) throws IOException {
        if (!BIOME_ID.matcher(biome).matches() || !config.isConfigurationSection("biomes." + biome)) {
            throw new IllegalArgumentException("El bioma local «" + biome + "» no existe en generacion.yml.");
        }
        String key = "structures." + id(name);
        if (config.contains(key)) {
            throw new IllegalArgumentException("Ya existe un grupo con ese ID.");
        }
        Path path = registerTemplate(project, config, name, kind);
        config.set(key + ".kind", kind);
        config.set(key + ".biomes", List.of(biome));
        return path;
    }

    private static Path registerTemplate(Path project, YamlConfiguration config, String name, String kind)
            throws IOException {
        if (!List.of("tree", "building", "other").contains(kind)) {
            throw new IllegalArgumentException("Categoría de plantilla desconocida.");
        }
        Path path = template(project, name);
        if (Files.exists(path)) {
            throw new IllegalArgumentException("Ya existe esa plantilla. Usá /dim actualizar para volver a capturarla.");
        }
        Files.createDirectories(path.getParent());
        config.set("catalog." + name + ".kind", kind);
        return path;
    }

    private static void saveRegistration(Path project, YamlConfiguration config, Path newTemplate) throws IOException {
        try {
            writeConfig(project, config);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(newTemplate);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    private static void writeConfig(Path project, YamlConfiguration config) throws IOException {
        Path temporary = Files.createTempFile(project, ".config-", ".tmp");
        try {
            Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8);
            replace(temporary, project.resolve("generacion.yml"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void validate(String project, Path path, Worldgen exporter) throws IOException {
        Path staging = Files.createTempDirectory(data, ".validacion-");
        try {
            stage(project, path, staging, exporter);
        } finally {
            clean(staging);
        }
    }

    Path export(String project, Path path, Worldgen exporter) throws IOException {
        Path staging = Files.createTempDirectory(data, ".exportacion-");
        try {
            stage(project, path, staging, exporter);
            Path output = data.resolve("exportaciones");
            Files.createDirectories(output);
            Path destination = output.resolve(namespace(project) + ".zip");
            Path temporary = Files.createTempFile(output, ".pack-", ".tmp");
            try {
                zip(staging, temporary);
                publish(temporary, destination);
                return destination;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            clean(staging);
        }
    }

    private static void stage(String project, Path path, Path staging, Worldgen exporter) throws IOException {
        var config = read(path);
        String namespace = namespace(project);
        Path templates = path.resolve("plantillas");
        if (Files.isDirectory(templates)) {
            Path target = staging.resolve("data").resolve(namespace).resolve("structure");
            Files.createDirectories(target);
            try (var files = Files.list(templates)) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    checkInterrupted();
                    String filename = source.getFileName().toString();
                    if (filename.endsWith(".nbt") && ID.matcher(filename.substring(0, filename.length() - 4)).matches()
                            && Files.size(source) > 0) {
                        Files.copy(source, target.resolve(filename));
                    }
                }
            }
        }
        checkInterrupted();
        exporter.write(staging, namespace, config);
        Files.writeString(staging.resolve("pack.mcmeta"), """
                {
                  "pack": {
                    "pack_format": 80,
                    "description": "MDVDimensions - %s"
                  }
                }
                """.formatted(project), StandardCharsets.UTF_8);
        Files.writeString(staging.resolve(MARKER), project, StandardCharsets.UTF_8);
    }

    Path install(String project, Path path, Path datapacks, Worldgen exporter) throws IOException {
        Path source = export(project, path, exporter);
        checkInterrupted();
        Files.createDirectories(datapacks);
        Path destination = datapacks.resolve(source.getFileName());
        if (Files.exists(datapacks.resolve(namespace(project)))) {
            throw new IllegalArgumentException("Hay un datapack descomprimido con el mismo ID. Revisá la carpeta datapacks antes de instalar el ZIP.");
        }
        if (Files.exists(destination)) {
            checkCompatible(destination, source, project);
            Path backup = data.resolve("copias").resolve(id(project));
            Files.createDirectories(backup);
            String stamp = Instant.now().toString().replace(':', '-');
            copyReplacing(destination, backup.resolve(stamp + "-" + UUID.randomUUID() + ".zip"));
        }
        copyReplacing(source, destination, true);
        return destination;
    }

    private static void copyReplacing(Path source, Path destination) throws IOException {
        copyReplacing(source, destination, false);
    }

    private static void copyReplacing(Path source, Path destination, boolean publishing) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".gm-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (publishing) {
                publish(temporary, destination);
            } else {
                replace(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void checkCompatible(Path installed, Path next, String project) throws IOException {
        String dataPath = "data/" + namespace(project) + "/";
        String dimension = dataPath + "dimension_type/environment.json";
        String biomePrefix = dataPath + "worldgen/biome/";
        try (var before = new ZipFile(installed.toFile()); var after = new ZipFile(next.toFile())) {
            if (!managed(before, project)) {
                throw new IllegalArgumentException("Ya hay un ZIP con ese nombre y pertenece a otro origen. Revisalo antes de instalar.");
            }
            var removed = before.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith(biomePrefix) && name.endsWith(".json") && after.getEntry(name) == null)
                    .map(name -> name.substring(biomePrefix.length(), name.length() - 5)).sorted().toList();
            if (!removed.isEmpty()) {
                throw new IllegalArgumentException("El ZIP instalado usa estos biomas. Conservá sus IDs para los chunks existentes: "
                        + String.join(", ", removed) + ".");
            }
            JsonObject oldBounds = json(before, dimension);
            JsonObject newBounds = json(after, dimension);
            if (!oldBounds.get("min_y").equals(newBounds.get("min_y"))
                    || !oldBounds.get("height").equals(newBounds.get("height"))) {
                throw new IllegalArgumentException("Los límites verticales cambiaron. Usá un proyecto nuevo para cambiar min_y o height.");
            }
        }
    }

    private static boolean managed(ZipFile zip, String project) throws IOException {
        var marker = zip.getEntry(MARKER);
        if (marker == null || marker.getSize() != project.length()) {
            return false;
        }
        try (var contents = zip.getInputStream(marker)) {
            return project.equals(new String(contents.readNBytes(32), StandardCharsets.UTF_8));
        }
    }

    private static JsonObject json(ZipFile zip, String path) throws IOException {
        var entry = zip.getEntry(path);
        if (entry == null) {
            throw new IOException("El ZIP no contiene " + path);
        }
        try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void zip(Path root, Path output) throws IOException {
        try (var files = Files.walk(root); var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                checkInterrupted();
                var entry = new ZipEntry(root.relativize(file).toString().replace('\\', '/'));
                entry.setTime(315532800000L);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private static void replace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void publish(Path from, Path to) throws IOException {
        checkInterrupted();
        replace(from, to);
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Se canceló la preparación del datapack.");
        }
    }

    private static void clean(Path staging) throws IOException {
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
