package dev.mira.worldgen;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

final class Capture {
    private static final long MAX_VOLUME = 2_000_000;
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
        checkVolume(width, height, depth);
        requireLoadedChunks(world, minX, minZ, maxX, maxZ);

        StructureManager manager = player.getServer().getStructureManager();
        Structure template = manager.createStructure();
        template.fill(new Location(world, minX, minY, minZ), new BlockVector(width, height, depth), includeEntities);
        writeTemplate(manager, template, destination.toAbsolutePath());
    }

    static void paste(Location center, Path source, StructureRotation rotation, boolean includeAir, int lowerBy)
            throws IOException {
        World world = center.getWorld();
        Structure template = Bukkit.getStructureManager().loadStructure(source.toFile());
        BlockVector size = template.getSize();
        int sizeX = size.getBlockX();
        int height = size.getBlockY();
        int sizeZ = size.getBlockZ();
        checkVolume(sizeX, height, sizeZ);

        boolean swapAxes = rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.COUNTERCLOCKWISE_90;
        int width = swapAxes ? sizeZ : sizeX;
        int depth = swapAxes ? sizeX : sizeZ;
        int minX = center.getBlockX() - width / 2;
        int minZ = center.getBlockZ() - depth / 2;
        int maxX = minX + width - 1;
        int maxZ = minZ + depth - 1;
        long baseY = (long) center.getBlockY() - lowerBy;
        if (lowerBy < 0 || baseY < world.getMinHeight() || baseY + height > world.getMaxHeight()
                || minX < -MAX_COORDINATE || maxX > MAX_COORDINATE
                || minZ < -MAX_COORDINATE || maxZ > MAX_COORDINATE) {
            throw new IllegalArgumentException("El volumen de pegado sale de los límites del mundo.");
        }
        requireLoadedChunks(world, minX, minZ, maxX, maxZ);

        int x = rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.CLOCKWISE_180
                ? maxX : minX;
        int z = rotation == StructureRotation.COUNTERCLOCKWISE_90 || rotation == StructureRotation.CLOCKWISE_180
                ? maxZ : minZ;
        TemplateBlocks.filter(template, !includeAir);
        template.place(new Location(world, x, baseY, z), true, rotation, Mirror.NONE, -1, 1, new Random());
    }

    private static void checkVolume(int width, int height, int depth) {
        if (width < 1 || height < 1 || depth < 1 || (long) width * height > MAX_VOLUME / depth) {
            throw new IllegalArgumentException("El volumen debe tener entre 1 y 2.000.000 de bloques.");
        }
    }

    private static void requireLoadedChunks(World world, int minX, int minZ, int maxX, int maxZ) {
        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new IllegalArgumentException("Acercate al centro de la zona y esperá a que se carguen todos sus chunks.");
                }
            }
        }
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
