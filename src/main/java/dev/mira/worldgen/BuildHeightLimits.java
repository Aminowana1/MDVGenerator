package dev.mira.worldgen;

import java.util.function.ToIntFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

final class BuildHeightLimits implements Listener {
    private final ToIntFunction<World> ceiling;

    BuildHeightLimits(ToIntFunction<World> ceiling) {
        this.ceiling = ceiling;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        var placed = event.getBlockPlaced();
        int limit = ceiling.applyAsInt(placed.getWorld());
        if (placed.getY() > limit || (event instanceof BlockMultiPlaceEvent multi
                && multi.getReplacedBlockStates().stream().anyMatch(state -> state.getY() > limit))) {
            event.setCancelled(true);
            notifyLimit(event.getPlayer(), limit);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        var target = event.getBlock();
        int limit = ceiling.applyAsInt(target.getWorld());
        if (target.getY() > limit) {
            event.setCancelled(true);
            notifyLimit(event.getPlayer(), limit);
        }
    }

    private static void notifyLimit(Player player, int limit) {
        player.sendActionBar(Component.text("Altura máxima de construcción: Y" + limit + "."));
    }
}
