package dev.mira.worldgen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

final class PackJobs {
    enum Operation {
        VALIDAR("Validando"), EXPORTAR("Exportando"), INSTALAR("Instalando");

        private final String progress;

        Operation(String progress) {
            this.progress = progress;
        }
    }

    private final MDVDimensions plugin;
    private final Projects projects;
    // A single worker bounds the memory used by the expanded NBT palettes and ZIP staging.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "MDVDimensions Datapacks");
        thread.setDaemon(true);
        return thread;
    });
    private Worldgen exporter;

    PackJobs(MDVDimensions plugin, Projects projects) {
        this.plugin = plugin;
        this.projects = projects;
    }

    void submit(CommandSender sender, String project, Operation operation) {
        Path datapacks = null;
        if (operation == Operation.INSTALAR) {
            var worlds = plugin.getServer().getWorlds();
            if (worlds.isEmpty()) {
                throw new IllegalArgumentException("Esperá a que cargue el mundo principal.");
            }
            datapacks = worlds.getFirst().getWorldFolder().toPath().resolve("datapacks");
        }
        Path source = projects.beginPackOperation(project);
        try {
            if (exporter == null) {
                exporter = new Worldgen(ExportRegistries.snapshot());
            }
            Path destination = datapacks;
            Worldgen currentExporter = exporter;
            worker.execute(() -> prepare(sender, project, source, operation, destination, currentExporter));
        } catch (RejectedExecutionException ex) {
            projects.finishPackOperation(project);
            throw new IllegalArgumentException("El servidor está cerrando. Volvé a intentar cuando arranque.", ex);
        } catch (RuntimeException | LinkageError ex) {
            projects.finishPackOperation(project);
            throw ex;
        }
        sender.sendMessage("MDVDimensions · " + operation.progress + " «" + project + "» en segundo plano.");
    }

    private void prepare(CommandSender sender, String project, Path source, Operation operation, Path datapacks,
                         Worldgen currentExporter) {
        Path output = null;
        Throwable failure = null;
        try {
            output = switch (operation) {
                case VALIDAR -> {
                    projects.validate(project, source, currentExporter);
                    yield null;
                }
                case EXPORTAR -> projects.export(project, source, currentExporter);
                case INSTALAR -> projects.install(project, source, datapacks, currentExporter);
            };
        } catch (IOException | RuntimeException | LinkageError ex) {
            failure = ex;
        }
        if (worker.isShutdown()) {
            return;
        }
        Path result = output;
        Throwable error = failure;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> complete(sender, project, operation, result, error));
        } catch (IllegalPluginAccessException ignored) {
            // The plugin was disabled while the worker was finishing.
        }
    }

    private void complete(CommandSender sender, String project, Operation operation, Path output, Throwable failure) {
        projects.finishPackOperation(project);
        if (failure != null && !(failure instanceof IllegalArgumentException)) {
            plugin.getLogger().log(Level.WARNING, "Falló " + operation.progress.toLowerCase(java.util.Locale.ROOT)
                    + " el datapack de «" + project + "»", failure);
        }
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        if (failure instanceof IllegalArgumentException) {
            sender.sendMessage("MDVDimensions · " + failure.getMessage());
        } else if (failure != null) {
            sender.sendMessage("MDVDimensions · No se pudo preparar el datapack de «" + project + "». Revisá la consola.");
            if (operation == Operation.INSTALAR && failure instanceof IOException) {
                sender.sendMessage("Si el ZIP está en uso, reemplazalo con el servidor apagado.");
            }
        } else {
            switch (operation) {
                case VALIDAR -> sender.sendMessage("El proyecto está listo para exportar.");
                case EXPORTAR -> sender.sendMessage("Datapack exportado a " + output + ".");
                case INSTALAR -> {
                    sender.sendMessage("Datapack instalado en " + output + ".");
                    sender.sendMessage("Reiniciá el servidor. La dimensión es " + Projects.namespace(project) + ":world.");
                }
            }
        }
    }

    void shutdown() {
        worker.shutdownNow();
    }
}
