package dev.mira.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class BiomeEssences {
    private static final String BIOMES = "/worldgen/vanilla/worldgen/biome/";

    // One entry per bundled Java 1.21.6 biome. Climate comes from the same definitions the exporter uses.
    private static final String DESCRIPTIONS = """
            badlands|Tierras baldías|RED_TERRACOTTA|Overworld árido|Arena roja y terracota|Cactus y arbustos secos|Mesetas áridas con estratos de colores.|mesa cañón desierto rojo
            bamboo_jungle|Jungla de bambú|BAMBOO|Overworld tropical|Podzol y pasto|Bambú, jungla y helechos|Cañaverales densos con árboles tropicales.|selva cañas pandas
            basalt_deltas|Deltas de basalto|BASALT|Nether|Basalto y piedra negra|Hongos dispersos|Columnas de roca y charcos de lava.|volcán ceniza magma
            beach|Playa|SAND|Costa del Overworld|Arena|Vegetación escasa|Franja arenosa junto al mar.|litoral tortugas
            birch_forest|Bosque de abedules|BIRCH_LOG|Overworld templado|Pasto y tierra|Abedules, pasto y flores|Bosque claro de troncos blancos.|bosque claro birch
            cherry_grove|Arboleda de cerezos|CHERRY_SAPLING|Montaña del Overworld|Pasto y tierra|Cerezos y pétalos rosas|Laderas suaves con copas rosadas.|sakura bosque rosa cherry
            cold_ocean|Océano frío|COD|Océano del Overworld|Fondo de grava|Algas y pasto marino|Aguas frías con algas y bacalao.|mar frío litoral
            crimson_forest|Bosque carmesí|CRIMSON_FUNGUS|Nether|Necelio carmesí y netherrack|Hongos gigantes y lianas colgantes|Bosque rojo de hongos del Nether.|nether rojo crimson carmesi
            dark_forest|Bosque oscuro|DARK_OAK_SAPLING|Overworld templado|Pasto y tierra|Robles oscuros y hongos gigantes|Un dosel cerrado que deja pasar poca luz.|bosque sombrío dark roofed
            deep_cold_ocean|Océano frío profundo|COD_BUCKET|Océano del Overworld|Fondo profundo de grava|Algas y pasto marino|La variante más profunda del mar frío.|mar abismo frío
            deep_dark|Oscuridad profunda|SCULK|Caverna del Overworld|Pizarra profunda y sculk|Sin vegetación común|Cavernas hondas con sensores de sculk.|warden ciudad antigua cueva
            deep_frozen_ocean|Océano helado profundo|PACKED_ICE|Océano del Overworld|Grava bajo aguas profundas|Vegetación marina escasa|Mar profundo con icebergs y aguas gélidas.|mar ártico glaciar
            deep_lukewarm_ocean|Océano templado profundo|CLAY|Océano del Overworld|Arena y grava|Algas y pasto marino|Aguas templadas por encima de un fondo profundo.|mar tibio océano tibio
            deep_ocean|Océano profundo|PRISMARINE|Océano del Overworld|Fondo profundo de grava|Algas y pasto marino|Mar abierto con gran profundidad.|abismo monumento marino
            desert|Desierto|CACTUS|Overworld árido|Arena y arenisca|Cactus y arbustos secos|Dunas cálidas y vegetación dispersa.|dunas arena camello
            dripstone_caves|Cuevas de espeleotemas|POINTED_DRIPSTONE|Caverna del Overworld|Piedra y espeleotemas|Sin vegetación común|Cuevas de estalactitas y estalagmitas.|cuevas de estalactitas dripstone
            end_barrens|Bordes del End|END_STONE_BRICKS|End|Piedra del End|Sin plantas características|Bordes bajos de las islas exteriores.|end periferia tierras baldías
            end_highlands|Tierras altas del End|CHORUS_FLOWER|End|Piedra del End|Plantas de coro|Mesetas altas de las islas exteriores.|end ciudad coro chorus
            end_midlands|Tierras medias del End|END_STONE|End|Piedra del End|Vegetación escasa|Pendientes entre las zonas altas y bajas.|end islas medianas
            eroded_badlands|Tierras baldías erosionadas|ORANGE_TERRACOTTA|Overworld árido|Arena roja y terracota|Cactus y arbustos secos|Agujas de terracota altas y estrechas.|mesa bryce cañón chimeneas
            flower_forest|Bosque de flores|ALLIUM|Overworld templado|Pasto y tierra|Robles, abedules y muchas flores|Claros floridos entre árboles templados.|flores abejas bosque florido
            forest|Bosque templado|OAK_SAPLING|Overworld templado|Pasto y tierra|Robles, abedules y flores|Mezcla clásica de árboles y claros verdes.|bosque normal roble
            frozen_ocean|Océano helado|POLAR_BEAR_SPAWN_EGG|Océano del Overworld|Grava y hielo en la superficie|Vegetación marina escasa|Mar frío con hielo e icebergs.|mar glaciar ártico
            frozen_peaks|Picos helados|BLUE_ICE|Montaña del Overworld|Nieve, hielo y piedra|Vegetación escasa|Cimas altas con grandes masas de hielo.|montaña congelada glaciares
            frozen_river|Río helado|SALMON_BUCKET|Río del Overworld|Grava, arena y hielo|Vegetación ribereña escasa|Cauce frío con hielo en la superficie.|río congelado nieve
            grove|Arboleda nevada|SPRUCE_SAPLING|Montaña del Overworld|Nieve y tierra|Abetos y vegetación de montaña|Bosque de abetos en laderas frías.|arboleda de montaña pinos
            ice_spikes|Púas de hielo|ICE|Overworld frío|Nieve y hielo compacto|Vegetación escasa|Agujas de hielo sobre una llanura nevada.|pináculos hielo estacas
            jagged_peaks|Picos escarpados|SNOW_BLOCK|Montaña del Overworld|Piedra y nieve|Vegetación escasa|Cumbres afiladas con laderas abruptas.|picos dentados cordillera cabras
            jungle|Jungla|JUNGLE_SAPLING|Overworld tropical|Pasto y tierra|Árboles de jungla, lianas y cacao|Copas altas en una selva densa y húmeda.|selva tropical loros cacao
            lukewarm_ocean|Océano templado|TROPICAL_FISH|Océano del Overworld|Arena y grava|Algas y pasto marino|Mar de temperatura intermedia con peces tropicales.|mar tibio oceano tibio
            lush_caves|Cuevas exuberantes|AZALEA|Caverna del Overworld|Musgo, arcilla y piedra|Azaleas, esporas y bayas luminosas|Vegetación abundante y pozas bajo tierra.|cueva verde ajolotes musgo lush
            mangrove_swamp|Pantano de manglares|MANGROVE_PROPAGULE|Humedal del Overworld|Barro y raíces|Manglares y lianas|Humedal cálido de árboles con raíces altas.|manglar ciénaga ranas barro
            meadow|Pradera de montaña|CORNFLOWER|Montaña del Overworld|Pasto y tierra|Flores y pastos|Pradera elevada con flores y laderas suaves.|pradera alpina campo florido
            mushroom_fields|Campos de hongos|MYCELIUM|Overworld insular|Micelio|Hongos rojos y marrones gigantes|Islas de micelio con vegetación fúngica.|isla champiñones setas mooshroom
            nether_wastes|Erial del Nether|NETHERRACK|Nether|Netherrack y lava|Hongos dispersos|El terreno rojo clásico del Nether.|nether normal desechos eriales infierno
            ocean|Océano|KELP|Océano del Overworld|Fondo de grava|Algas y pasto marino|Mar templado del Overworld.|mar agua oceano normal
            old_growth_birch_forest|Bosque de abedules antiguos|BIRCH_WOOD|Overworld templado|Pasto y tierra|Abedules altos y flores|Abedules más altos que en el bosque común.|bosque antiguo abedules gigantes
            old_growth_pine_taiga|Taiga de pinos antiguos|PODZOL|Overworld frío|Podzol y tierra estéril|Pinos altos con copas elevadas|Coníferas gigantes de tronco despejado.|megataiga pino gigante
            old_growth_spruce_taiga|Taiga de abetos antiguos|SPRUCE_WOOD|Overworld frío|Podzol y tierra estéril|Abetos gigantes con copas largas|Bosque alto de coníferas tupidas.|megataiga abeto gigante
            pale_garden|Jardín pálido|PALE_OAK_SAPLING|Overworld templado|Pasto, tierra y musgo pálido|Robles pálidos y musgo colgante|Bosque de tonos apagados y madera clara.|pale garden crujidor jardín gris
            plains|Llanura|GRASS_BLOCK|Overworld templado|Pasto y tierra|Pasto, flores y algunos robles|Terreno abierto con vegetación baja.|planicie campo llano plains
            river|Río|WATER_BUCKET|Río del Overworld|Grava y arena|Vegetación ribereña|Canales de agua que atraviesan el paisaje.|cauce arroyo riberas
            savanna|Sabana|ACACIA_SAPLING|Overworld seco|Pasto y tierra|Acacias y pasto alto|Llanura cálida con acacias espaciadas.|acacia sabana árida
            savanna_plateau|Meseta de sabana|ACACIA_LOG|Overworld seco|Pasto y tierra|Acacias y pasto alto|Variante elevada de la sabana.|altiplano meseta acacia
            small_end_islands|Islas pequeñas del End|PURPUR_BLOCK|End|Piedra del End|Sin vegetación común|Islotes dispersos en el vacío.|end archipiélago islotes
            snowy_beach|Playa nevada|SNOWBALL|Costa del Overworld|Arena y nieve|Vegetación escasa|Costa de arena con nieve.|litoral helado playa fría
            snowy_plains|Llanura nevada|SNOW|Overworld frío|Nieve, pasto y tierra|Abetos dispersos|Paisaje abierto cubierto de nieve.|tundra campo nieve
            snowy_slopes|Laderas nevadas|POWDER_SNOW_BUCKET|Montaña del Overworld|Nieve y nieve en polvo|Vegetación escasa|Pendientes frías con nieve profunda.|montaña falda nieve polvo
            snowy_taiga|Taiga nevada|SPRUCE_LEAVES|Overworld frío|Nieve, pasto y tierra|Abetos y helechos|Bosque de coníferas nevado.|pinos nieve bosque frío
            soul_sand_valley|Valle de arena de almas|SOUL_SAND|Nether|Arena y tierra de almas|Vegetación escasa|Valles abiertos con fósiles y fuego azul.|soul sand valle almas fósiles
            sparse_jungle|Jungla dispersa|JUNGLE_LEAVES|Overworld tropical|Pasto y tierra|Árboles de jungla espaciados|Selva más abierta que la jungla densa.|selva dispersa borde jungla
            stony_peaks|Picos pedregosos|CALCITE|Montaña del Overworld|Piedra, grava y calcita|Vegetación escasa|Cumbres rocosas con vetas claras de calcita.|montaña rocosa picos pétreos
            stony_shore|Costa pedregosa|STONE|Costa del Overworld|Piedra y grava|Vegetación escasa|Orilla rocosa entre el mar y terreno alto.|playa rocosa acantilado litoral
            sunflower_plains|Llanura de girasoles|SUNFLOWER|Overworld templado|Pasto y tierra|Girasoles y pastos|Planicie abierta con abundantes girasoles.|campo flores girasol
            swamp|Pantano|LILY_PAD|Humedal del Overworld|Pasto, tierra y arcilla|Robles con lianas y nenúfares|Humedal bajo con charcas y agua turbia.|ciénaga pantano normal nenúfar
            taiga|Taiga|SPRUCE_LOG|Overworld frío|Pasto y tierra|Abetos, helechos y bayas|Bosque de coníferas con sotobosque bajo.|bosque pinos coníferas
            the_end|El End|ENDER_EYE|End|Piedra del End|Sin vegetación común|Bioma de la isla central del End vanilla.|end central dragón
            the_void|El vacío|BLACK_STAINED_GLASS_PANE|Especial|Sin suelo típico|Sin vegetación común|Base especial sin decoración natural común.|mundo vacío void
            warm_ocean|Océano cálido|BRAIN_CORAL|Océano del Overworld|Fondo de arena|Corales y pasto marino|Mar cálido con arrecifes de coral.|arrecife coral mar tropical
            warped_forest|Bosque distorsionado|WARPED_FUNGUS|Nether|Necelio distorsionado y netherrack|Hongos gigantes y enredaderas retorcidas|Bosque azul verdoso de hongos del Nether.|nether azul bosque deformado warped
            windswept_forest|Bosque azotado por el viento|OAK_LEAVES|Montaña del Overworld|Pasto, piedra y grava|Robles y abetos dispersos|Árboles salpicados en colinas abruptas.|bosque ventoso montaña
            windswept_gravelly_hills|Colinas de grava ventosas|GRAVEL|Montaña del Overworld|Grava, piedra y pasto|Vegetación dispersa|Laderas expuestas con mucha grava.|colinas gravosas montaña grava
            windswept_hills|Colinas azotadas por el viento|COARSE_DIRT|Montaña del Overworld|Piedra, pasto y grava|Árboles dispersos|Relieve abrupto con paredes de piedra.|colinas ventosas montañas extremas
            windswept_savanna|Sabana azotada por el viento|ACACIA_LEAVES|Overworld seco|Piedra, tierra estéril y pasto|Acacias y pasto alto|Formas abruptas y acantilados secos.|sabana ventosa quebrada meseta
            wooded_badlands|Tierras baldías boscosas|RED_SANDSTONE|Overworld árido|Terracota, tierra estéril y pasto|Robles en las zonas altas|Mesetas de terracota con partes arboladas.|mesa arbolada cañón bosque
            """;

    private final List<Essence> entries;
    private final Map<String, Essence> byId;

    BiomeEssences() {
        Collator spanish = Collator.getInstance(Locale.forLanguageTag("es-AR"));
        entries = DESCRIPTIONS.lines().map(BiomeEssences::load)
                .sorted((first, second) -> spanish.compare(first.name(), second.name()))
                .toList();
        byId = entries.stream().collect(Collectors.toUnmodifiableMap(Essence::id, Function.identity()));
    }

    List<Essence> entries() {
        return entries;
    }

    Essence get(String id) {
        return byId.get(id.startsWith("minecraft:") ? id : "minecraft:" + id);
    }

    private static Essence load(String entry) {
        var fields = entry.split("\\|", -1);
        String id = fields[0];
        JsonObject biome = definition(BIOMES + id + ".json");
        JsonObject effects = biome.getAsJsonObject("effects");
        var colors = effects.entrySet().stream().filter(value -> value.getKey().endsWith("_color"))
                .filter(value -> value.getValue().isJsonPrimitive() && value.getValue().getAsJsonPrimitive().isNumber())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, value -> hex(value.getValue().getAsInt())));
        return new Essence("minecraft:" + id, fields[1], Material.valueOf(fields[2]), fields[3], fields[4], fields[5], fields[6], fields[7],
                biome.get("temperature").getAsDouble(), biome.get("downfall").getAsDouble(),
                biome.get("has_precipitation").getAsBoolean(), colors);
    }

    static JsonObject definition(String path) {
        try (var input = BiomeEssences.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Falta la definición vanilla " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String number(Number value) {
        if (!Double.isFinite(value.doubleValue())) {
            return value.toString();
        }
        return BigDecimal.valueOf(value.doubleValue()).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color);
    }

    record Essence(String id, String name, Material icon, String environment, String substrate, String vegetation,
                   String description, String aliases, double temperature, double humidity, boolean precipitation,
                   Map<String, String> colors) {
        List<String> lore() {
            return List.of(description, "Ambiente · " + environment,
                    "Suelo típico · " + substrate, "Vegetación · " + vegetation,
                    "Temperatura · " + number(temperature) + "  |  Humedad · " + number(humidity),
                    "Precipitación · " + (precipitation ? "Sí" : "No"),
                    "ID · " + id, "También · " + id.substring("minecraft:".length()).replace('_', ' ') + " · " + aliases);
        }
    }
}
