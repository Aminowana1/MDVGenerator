package dev.mira.worldgen;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.structure.Structure;

final class TemplateBlocks {
    private static final String TEMPLATE =
            "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate";

    private TemplateBlocks() {}

    static void filter(Structure template, boolean ignoreAir) throws IOException {
        // Paper's block transformers read through a region bounded to the paste origin.
        // Filter each palette on the fresh template before it is published or placed instead.
        try {
            Object handle = template.getClass().getMethod("getHandle").invoke(template);
            List<?> palettes = (List<?>) handle.getClass().getField("palettes").get(handle);
            Method blocks = Class.forName(TEMPLATE + "$Palette").getMethod("blocks");
            Method state = Class.forName(TEMPLATE + "$StructureBlockInfo").getMethod("state");
            Method getBlock = state.getReturnType().getMethod("getBlock");
            Method isAir = state.getReturnType().getMethod("isAir");
            Object structureVoid = Class.forName("net.minecraft.world.level.block.Blocks")
                    .getField("STRUCTURE_VOID").get(null);
            Map<Object, Boolean> keptStates = new IdentityHashMap<>();
            for (Object palette : palettes) {
                @SuppressWarnings("unchecked")
                List<Object> blockInfos = (List<Object>) blocks.invoke(palette);
                int next = 0;
                for (int index = 0; index < blockInfos.size(); index++) {
                    Object block = blockInfos.get(index);
                    Object blockState = state.invoke(block);
                    Boolean keep = keptStates.get(blockState);
                    if (keep == null) {
                        keep = getBlock.invoke(blockState) != structureVoid
                                && (!ignoreAir || !(boolean) isAir.invoke(blockState));
                        keptStates.put(blockState, keep);
                    }
                    if (keep) {
                        blockInfos.set(next++, block);
                    }
                }
                blockInfos.subList(next, blockInfos.size()).clear();
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new IOException("No se pudo preparar la paleta de la plantilla en Purpur 1.21.6.", ex);
        }
    }
}
