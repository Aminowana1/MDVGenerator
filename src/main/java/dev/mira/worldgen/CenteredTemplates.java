package dev.mira.worldgen;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Makes an export-only copy with a jigsaw anchor in the center. Vanilla only
 * searches eight chunks from a structure start when building chunk references.
 */
final class CenteredTemplates {
    static final String ANCHOR = "mdvdimensions:centro_de_plantilla";
    static final int MAX_WIDTH = 257;
    private static final String FOLDER = "mdv_centered/";

    private CenteredTemplates() {}

    static Size size(Path file) throws IOException {
        try {
            return sizeOf(Nbt.read(file));
        } catch (ReflectiveOperationException ex) {
            throw ioFailure(file, ex);
        }
    }

    static String write(Path folder, String name) throws IOException {
        String reference = FOLDER + name;
        Path target = folder.resolve(reference + ".nbt");
        if (Files.isRegularFile(target)) {
            return reference;
        }
        Path source = folder.resolve(name + ".nbt");
        try {
            Object root = Nbt.read(source);
            Size size = sizeOf(root);
            List<Object> blocks = Nbt.list(root, "blocks");
            for (Object block : blocks) {
                rise(Nbt.list(block, "pos"));
            }
            for (Object entity : Nbt.list(root, "entities")) {
                rise(Nbt.list(entity, "blockPos"));
                List<Object> pos = Nbt.list(entity, "pos");
                if (pos.size() == 3) {
                    double y = ((Number) Nbt.LIST_DOUBLE.invoke(pos, 1, 0.0)).doubleValue();
                    pos.set(1, Nbt.DOUBLE_TAG.invoke(null, y + 1.0));
                }
            }
            rise(Nbt.list(root, "size"));
            int paletteIndex = addAnchorState(root);
            blocks.add(Nbt.parse("""
                    {pos:[%d,0,%d],state:%d,nbt:{id:"minecraft:jigsaw",name:"%s",target:"minecraft:empty",
                    pool:"minecraft:empty",final_state:"minecraft:structure_void",joint:"rollable"}}
                    """.formatted(size.width() / 2, size.depth() / 2, paletteIndex, ANCHOR)));
            Files.createDirectories(target.getParent());
            Nbt.WRITE.invoke(null, root, target);
            return reference;
        } catch (ReflectiveOperationException ex) {
            throw ioFailure(source, ex);
        }
    }

    private static Size sizeOf(Object root) throws ReflectiveOperationException, IOException {
        List<Object> size = Nbt.list(root, "size");
        if (size.size() != 3) {
            throw new IOException("La plantilla no tiene tres dimensiones válidas.");
        }
        Size result = new Size(Nbt.integer(size, 0), Nbt.integer(size, 1), Nbt.integer(size, 2));
        if (result.width() < 1 || result.height() < 1 || result.depth() < 1) {
            throw new IOException("La plantilla tiene una dimensión vacía.");
        }
        return result;
    }

    private static void rise(List<Object> coordinates) throws ReflectiveOperationException {
        if (coordinates.size() == 3) {
            coordinates.set(1, Nbt.INT_TAG.invoke(null, Nbt.integer(coordinates, 1) + 1));
        }
    }

    private static int addAnchorState(Object root) throws ReflectiveOperationException, IOException {
        List<Object> palettes = Nbt.list(root, "palettes");
        List<Object> palette = Nbt.list(root, "palette");
        Object state = Nbt.parse("{Name:\"minecraft:jigsaw\",Properties:{orientation:\"north_up\"}}");
        if (palettes.isEmpty()) {
            int index = palette.size();
            palette.add(state);
            return index;
        }
        int index = asList(palettes.getFirst()).size();
        for (Object entry : palettes) {
            var variant = asList(entry);
            if (variant.size() != index) {
                throw new IOException("Las variantes de paleta tienen tamaños distintos.");
            }
            variant.add(state);
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static IOException ioFailure(Path file, ReflectiveOperationException ex) {
        Throwable cause = ex instanceof InvocationTargetException wrapped ? wrapped.getCause() : ex;
        return new IOException("No se pudo leer o centrar la plantilla «" + file.getFileName() + "».", cause);
    }

    record Size(int width, int height, int depth) {
        int horizontal() {
            return Math.max(width, depth);
        }
    }

    private static final class Nbt {
        private static final Class<?> COMPOUND = type("CompoundTag");
        private static final Class<?> LIST = type("ListTag");
        private static final Class<?> ACCOUNT = type("NbtAccounter");
        private static final Method READ = method(type("NbtIo"), "readCompressed", Path.class, ACCOUNT);
        private static final Method WRITE = method(type("NbtIo"), "writeCompressed", COMPOUND, Path.class);
        private static final Method HEAP_LIMIT = method(ACCOUNT, "create", long.class);
        private static final Method LIST_TAG = method(COMPOUND, "getListOrEmpty", String.class);
        private static final Method LIST_INT = method(LIST, "getIntOr", int.class, int.class);
        private static final Method LIST_DOUBLE = method(LIST, "getDoubleOr", int.class, double.class);
        private static final Method INT_TAG = method(type("IntTag"), "valueOf", int.class);
        private static final Method DOUBLE_TAG = method(type("DoubleTag"), "valueOf", double.class);
        private static final Method PARSE = method(type("TagParser"), "parseCompoundFully", String.class);

        private static Object read(Path file) throws ReflectiveOperationException {
            return READ.invoke(null, file, HEAP_LIMIT.invoke(null, 768L * 1024L * 1024L));
        }

        private static List<Object> list(Object compound, String key) throws ReflectiveOperationException {
            return asList(LIST_TAG.invoke(compound, key));
        }

        private static int integer(List<Object> list, int index) throws ReflectiveOperationException {
            return (int) LIST_INT.invoke(list, index, 0);
        }

        private static Object parse(String snbt) throws ReflectiveOperationException {
            return PARSE.invoke(null, snbt);
        }

        private static Class<?> type(String name) {
            try {
                return Class.forName("net.minecraft.nbt." + name);
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("MDVDimensions requiere el formato NBT de Purpur 1.21.6.", ex);
            }
        }

        private static Method method(Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("MDVDimensions requiere el formato NBT de Purpur 1.21.6.", ex);
            }
        }
    }
}
