package dev.mira.worldgen;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

final class Capture {
    private static final int MAX_SIDE = 48;
    private static final int MAX_COORDINATE = 29_999_984;

    private final Map<UUID, Selection> selections = new HashMap<>();

    Location select(Player player, int corner) {
        return select(player, corner, player.getLocation());
    }

    Location select(Player player, int corner, Location location) {
        World world = player.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (!world.equals(location.getWorld()) || y < world.getMinHeight() || y >= world.getMaxHeight()
                || Math.abs((long) x) > MAX_COORDINATE || Math.abs((long) z) > MAX_COORDINATE) {
            throw new IllegalArgumentException("La esquina está fuera de los límites de tu mundo.");
        }

        Point point = new Point(world.getUID(), x, y, z);
        Selection previous = selections.getOrDefault(player.getUniqueId(), new Selection(null, null));
        Selection next = switch (corner) {
            case 1 -> new Selection(point, previous.second());
            case 2 -> new Selection(previous.first(), point);
            default -> throw new IllegalArgumentException("La esquina debe ser 1 o 2.");
        };
        selections.put(player.getUniqueId(), next);
        return new Location(world, x, y, z);
    }

    void clear(UUID playerId) {
        selections.remove(playerId);
    }

    void save(Player player, Path destination, boolean includeEntities) throws IOException {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.first() == null || selection.second() == null) {
            throw new IllegalArgumentException("Marcá las dos esquinas antes de guardar.");
        }
        Point first = selection.first();
        Point second = selection.second();
        World world = player.getWorld();
        if (!world.getUID().equals(first.world()) || !world.getUID().equals(second.world())) {
            throw new IllegalArgumentException("Marcá las dos esquinas en el mundo donde estás ahora.");
        }

        int minX = Math.min(first.x(), second.x());
        int minY = Math.min(first.y(), second.y());
        int minZ = Math.min(first.z(), second.z());
        int maxX = Math.max(first.x(), second.x());
        int maxY = Math.max(first.y(), second.y());
        int maxZ = Math.max(first.z(), second.z());
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int depth = maxZ - minZ + 1;
        if (width > MAX_SIDE || height > MAX_SIDE || depth > MAX_SIDE) {
            throw new IllegalArgumentException("La selección supera el máximo de 48 bloques por lado.");
        }

        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new IllegalArgumentException("Acercate a la selección para que se carguen todos sus chunks.");
                }
            }
        }

        StructureManager manager = player.getServer().getStructureManager();
        Structure template = manager.createStructure();
        template.fill(new Location(world, minX, minY, minZ), new BlockVector(width, height, depth), includeEntities);
        writeTemplate(manager, template, destination.toAbsolutePath());
    }

    private static void writeTemplate(StructureManager manager, Structure template, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "capture-", ".nbt.tmp");
        try {
            manager.saveStructure(temporary.toFile(), template);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record Point(UUID world, int x, int y, int z) {}

    private record Selection(Point first, Point second) {}
}
