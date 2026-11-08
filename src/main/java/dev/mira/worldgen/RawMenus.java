package dev.mira.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

final class RawMenus {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Menus menus;

    RawMenus(Menus menus) {
        this.menus = menus;
    }

    void open(Player player, String project, String path, String title, Menus.Action back) throws Exception {
        draw(player, new Section(project, path, title, back), List.of());
    }

    private record Section(String project, String path, String title, Menus.Action back) {}

    private void draw(Player player, Section section, List<String> address) throws Exception {
        JsonElement original = read(menus.projects.readConfig(section.project()), section.path());
        JsonElement current = get(original, address);
        List<String> parent = address.isEmpty() ? List.of() : address.subList(0, address.size() - 1);
        Menus.Action back = address.isEmpty() ? section.back() : () -> draw(player, section, parent);
        Menus.Action here = () -> draw(player, section, address);
        String title = address.isEmpty() ? section.title() : section.title() + " · " + address.getLast();
        var view = menus.menu(player, title);
        if (current.isJsonObject() || current.isJsonArray()) {
            List<String> entries = current.isJsonObject() ? List.copyOf(current.getAsJsonObject().keySet())
                    : IntStream.range(0, current.getAsJsonArray().size()).mapToObj(Integer::toString).toList();
            view.button(11, Material.BOOKSHELF, "Abrir campos", () -> menus.choose(player, "Campos · " + title, entries,
                    key -> key + " · " + summary(child(current, key)), key -> icon(child(current, key)), here,
                    key -> draw(player, section, append(address, key))), entries.size() + " campo(s)");
            view.button(15, Material.WRITABLE_BOOK, current.isJsonObject() ? "Agregar campo" : "Agregar elemento",
                    () -> add(player, section, address, original, current, here));
        }
        view.button(22, icon(current), "Reemplazar valor JSON", () -> menus.input(player,
                "Escribí un valor JSON (los textos van entre comillas)", current.toString(), here, text -> {
                    save(section, original, replace(original, address, parse(text)));
                    here.run();
                }), summary(current));
        view.button(31, Material.LAVA_BUCKET, address.isEmpty() ? "Quitar ajustes avanzados" : "Quitar campo",
                () -> menus.confirm(player, "Quitar valor", here, () -> {
                    save(section, original, replace(original, address, null));
                    back.run();
                }, "El cambio se guarda en este proyecto."));
        view.button(45, Material.ARROW, "Volver", back);
        view.open();
    }

    private void add(Player player, Section section, List<String> address, JsonElement original, JsonElement current,
                     Menus.Action here) {
        if (current.isJsonArray()) {
            menus.input(player, "Ingresá el nuevo elemento JSON", null, here, value -> {
                JsonElement changed = original.deepCopy();
                get(changed, address).getAsJsonArray().add(parse(value));
                save(section, original, changed);
                here.run();
            });
            return;
        }
        menus.input(player, "Nombre del campo", null, here, key -> {
            if (!key.matches("[a-zA-Z0-9_:/#-]{1,96}") || current.getAsJsonObject().has(key)) {
                throw new IllegalArgumentException("Usá una clave nueva sin espacios ni puntos.");
            }
            menus.input(player, "Valor JSON de " + key, null, here, value -> {
                save(section, original, replace(original, append(address, key), parse(value)));
                here.run();
            });
        });
    }

    private void save(Section section, JsonElement original, JsonElement changed) throws Exception {
        if (changed != null && !changed.isJsonObject()) {
            throw new IllegalArgumentException("La sección completa tiene que ser un objeto JSON.");
        }
        Object value = changed == null ? null : toYaml(changed);
        menus.projects.editConfig(section.project(), config -> {
            if (!original.equals(read(config, section.path()))) {
                throw new IllegalArgumentException("La sección cambió mientras la editabas. Volvé a abrirla.");
            }
            config.set(section.path(), value);
        });
    }

    private static JsonElement read(ConfigurationSection config, String path) {
        Object value = config.get(path);
        return value == null ? new JsonObject() : toJson(value);
    }

    private static JsonElement toJson(Object value) {
        if (value instanceof ConfigurationSection section) {
            return toJson(section.getValues(false));
        }
        if (value instanceof Map<?, ?> map) {
            var object = new JsonObject();
            map.forEach((key, element) -> object.add(String.valueOf(key), toJson(element)));
            return object;
        }
        if (value instanceof List<?> list) {
            var array = new JsonArray();
            list.forEach(element -> array.add(toJson(element)));
            return array;
        }
        return JSON.toJsonTree(value);
    }

    private static Object toYaml(JsonElement value) {
        if (value.isJsonNull()) {
            throw new IllegalArgumentException("Usá Quitar campo para eliminar un valor.");
        }
        if (value.isJsonObject()) {
            var map = new LinkedHashMap<String, Object>();
            value.getAsJsonObject().entrySet().forEach(entry -> {
                if (entry.getKey().contains(".")) {
                    throw new IllegalArgumentException("Los nombres de campos YAML no pueden contener puntos.");
                }
                map.put(entry.getKey(), toYaml(entry.getValue()));
            });
            return map;
        }
        if (value.isJsonArray()) {
            var list = new ArrayList<>();
            value.getAsJsonArray().forEach(element -> list.add(toYaml(element)));
            return list;
        }
        var scalar = value.getAsJsonPrimitive();
        if (scalar.isNumber()) {
            try {
                return scalar.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException ex) {
                double number = scalar.getAsDouble();
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("El número excede el rango admitido.");
                }
                return number;
            }
        }
        return scalar.isBoolean() ? scalar.getAsBoolean() : scalar.getAsString();
    }

    private static JsonElement parse(String text) {
        try {
            JsonElement value = JSON.fromJson(text, JsonElement.class);
            if (value == null || value.isJsonNull()) {
                throw new IllegalArgumentException("Ingresá un valor JSON o usá Quitar campo.");
            }
            return value;
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException("El valor no tiene formato JSON. Por ejemplo 15, true o \"minecraft:stone\".", ex);
        }
    }

    private static List<String> append(List<String> address, String key) {
        var path = new ArrayList<>(address);
        path.add(key);
        return List.copyOf(path);
    }

    private static JsonElement get(JsonElement node, List<String> address) {
        for (String key : address) {
            node = child(node, key);
        }
        return node;
    }

    private static JsonElement child(JsonElement node, String key) {
        JsonElement next = node.isJsonObject() ? node.getAsJsonObject().get(key)
                : node.isJsonArray() ? node.getAsJsonArray().get(Integer.parseInt(key)) : null;
        if (next == null) {
            throw new IllegalArgumentException("El campo cambió. Volvé a abrir la sección.");
        }
        return next;
    }

    private static JsonElement replace(JsonElement original, List<String> address, JsonElement value) {
        if (address.isEmpty()) {
            return value;
        }
        JsonElement changed = original.deepCopy();
        JsonElement parent = get(changed, address.subList(0, address.size() - 1));
        String key = address.getLast();
        if (parent.isJsonObject()) {
            if (value == null) {
                parent.getAsJsonObject().remove(key);
            } else {
                parent.getAsJsonObject().add(key, value);
            }
        } else if (value == null) {
            parent.getAsJsonArray().remove(Integer.parseInt(key));
        } else {
            parent.getAsJsonArray().set(Integer.parseInt(key), value);
        }
        return changed;
    }

    private static Material icon(JsonElement value) {
        return value.isJsonObject() || value.isJsonArray() ? Material.BOOKSHELF : Material.PAPER;
    }

    private static String summary(JsonElement value) {
        String text = value.isJsonObject() ? "objeto (" + value.getAsJsonObject().size() + ")"
                : value.isJsonArray() ? "lista (" + value.getAsJsonArray().size() + ")" : value.toString();
        return text.length() > 55 ? text.substring(0, 52) + "…" : text;
    }
}
