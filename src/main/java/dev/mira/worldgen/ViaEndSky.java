package dev.mira.worldgen;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.minecraft.RegistryEntry;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.Protocol1_21_9To1_21_11;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ViaEndSky {
    private final Logger logger;
    private volatile boolean active = true;

    ViaEndSky(Logger logger) {
        this.logger = logger;
    }

    void start() {
        try {
            ViaManager manager = Via.getManager();
            if (manager.isInitialized()) {
                install(manager);
            } else {
                manager.addEnableListener(() -> install(manager));
            }
        } catch (RuntimeException | LinkageError error) {
            warn(error);
        }
    }

    void stop() {
        active = false;
    }

    private void install(ViaManager manager) {
        if (!active) {
            return;
        }
        try {
            var protocols = manager.getProtocolManager();
            var protocol = protocols.getProtocol(Protocol1_21_9To1_21_11.class);
            if (protocol == null) {
                logger.warning("ViaVersion no tiene la traducción del cielo para Java 1.21.11.");
                return;
            }
            protocols.completeMappingDataLoading(Protocol1_21_9To1_21_11.class);
            protocol.appendClientbound(ClientboundConfigurationPackets1_21_9.REGISTRY_DATA, this::rewriteRegistry);
            logger.info("Compatibilidad con el cielo del End activada en ViaVersion.");
        } catch (RuntimeException | LinkageError error) {
            warn(error);
        }
    }

    private void rewriteRegistry(PacketWrapper packet) throws InformativeException {
        if (!active || !packet.is(Types.STRING, 0) || !packet.is(Types.REGISTRY_ENTRY_ARRAY, 0)) {
            return;
        }
        String registry = packet.get(Types.STRING, 0);
        if (!registry.equals("minecraft:dimension_type") && !registry.equals("dimension_type")) {
            return;
        }
        for (RegistryEntry entry : packet.get(Types.REGISTRY_ENTRY_ARRAY, 0)) {
            if (entry.key().startsWith("gm_") && entry.key().endsWith(":environment")
                    && entry.tag() instanceof CompoundTag dimension
                    && "minecraft:the_end".equals(dimension.getString("effects"))
                    && !"end".equals(dimension.getString("skybox"))) {
                // ViaVersion assigns Overworld defaults to custom type IDs. Keep the original End appearance.
                dimension.putString("skybox", "end");
                dimension.put("timelines", new ListTag<>(List.of(new StringTag("villager_schedule"))));
            }
        }
    }

    private void warn(Throwable error) {
        logger.log(Level.WARNING, "No se pudo activar el cielo del End en ViaVersion. Versión comprobada: 5.12.2.", error);
    }
}
