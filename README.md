# MDVGenerator

Plugin para **Purpur 1.21.6** que te deja diseñar biomas, guardar construcciones y configurar la generación desde menús dentro de Minecraft. Cada proyecto tiene su catálogo y exporta un datapack propio. Podés instalar varios a la vez y acceder a sus dimensiones con **Multiverse-Core 5.7.3**.

## Compilar e instalar

**En GitHub.** Subí al repositorio el contenido de la carpeta del proyecto, incluida `.github`. En **Actions → Compilar plugin**, elegí **Run workflow**. Cuando termine, descargá el artefacto **MDVGenerator** y extraé `MDVGenerator-2.0.0.jar`.

**En tu computadora.** Con JDK 21 y Maven, ejecutá este comando desde la carpeta que contiene `pom.xml`.

~~~bash
mvn -B clean verify
~~~

El JAR queda en `target/MDVGenerator-2.0.0.jar`. Copialo a `plugins/` y reiniciá el servidor. Los operadores tienen acceso por defecto. Para habilitar el editor a otro administrador, asignale `mdvgenerator.admin`. Los permisos existentes `generamundos.admin` siguen siendo válidos.

### Si ya usabas GeneraMundos

1. Apagá el servidor y hacé una copia de seguridad.
2. Retirá únicamente el JAR anterior de `plugins/` y poné `MDVGenerator-2.0.0.jar` en su lugar. Conservá la carpeta `plugins/GeneraMundos/`.
3. Arrancá el servidor. MDVGenerator copia cada proyecto y sus plantillas a `plugins/MDVGenerator/proyectos/` si todavía no existen ahí. Las copias y exportaciones anteriores siguen en `plugins/GeneraMundos/`.
4. Abrí `/mdv` y elegí el proyecto importado. Su dimensión sigue siendo `gm_<proyecto>:world` y el ZIP conserva el nombre `gm_<proyecto>.zip`. Los datapacks instalados siguen activos. Si querés que una nueva exportación conserve los mobs naturales que heredabas del bioma vanilla, habilitá **Mobs heredados** en ese bioma.

## Crear y probar un mundo desde el juego

1. Abrí **`/mdv` → Crear proyecto** y escribí un ID como `arena_volcanica`. El proyecto inicial trae un bioma, `bosque_umbrio`. Usalo como punto de partida o agregá otros desde **Biomas → Crear bioma**. Podés darle un nombre visible en el menú, como **Arena volcánica**, y elegir su base vanilla. El ID es la clave que se registra en Minecraft.
2. En **Mundo**, elegí el terreno base, el ambiente visual y las reglas de dimensión. Por ejemplo, combiná islas flotantes con cielo del End y camas habilitadas. En **Biomas → tu bioma**, definí sus capas, colores, clima, relieve preferido y mobs naturales.
3. Para guardar un árbol o una construcción, sacá la **Herramienta de selección** del menú del proyecto. Marcá los bloques de las dos esquinas opuestas con clic izquierdo y derecho. Volvé con `/mdv` y abrí **Biomas → tu bioma → Árboles y estructuras → Capturar una plantilla**. Elegí la categoría y guardala con un ID como `sauce_01`. Si capturás desde el catálogo general, agregá la plantilla a un grupo de aparición y elegí sus biomas.
4. Volvé al proyecto, elegí **Validar** y después **Instalar datapack**. **Exportar ZIP** sirve si preferís copiar el datapack a mano. Cuando ingreses texto o números, podés usar el chat o `/mdv entrada <valor>`. `/mdv cancelar` vuelve al menú anterior.
5. Reiniciá el servidor completo. Usá `/mv list` y `/mv tp <nombre>` con el nombre exacto que muestre Multiverse. La clave de Minecraft para este ejemplo es `gm_arena_volcanica:world`.

Purpur carga la dimensión desde el datapack al arrancar. Multiverse-Core 5.7.3 registra el mundo cargado si `world.auto-import-3rd-party-worlds` está habilitado en `plugins/Multiverse-Core/config.yml`. Si el mundo falta en `/mv list`, probá `/execute in gm_arena_volcanica:world run time query daytime` para verificar si Minecraft lo cargó. Con Multiverse-Core 5.1.1 se observó que Purpur cargaba la dimensión pero Multiverse fallaba al registrarla. [Multiverse-Core 5.7.3](https://modrinth.com/plugin/multiverse-core/version/5.7.3) · [Datapacks y Multiverse](https://mvplugins.org/core/reference/faq/) · [Configuración de Multiverse](https://mvplugins.org/core/reference/configuration-file/)

## Diseñar el terreno y las capas

La **forma** del mundo viene de **Mundo → Terreno base**. El bloque base, el fluido y el nivel del mar cambian sus materiales y el nivel de referencia del agua o la lava. En **Capas generales** podés definir materiales comunes. Los biomas pueden sobrescribirlos en **Capas del terreno**.

| Terreno | Forma de partida |
| --- | --- |
| `overworld` | Continentes, montañas, océanos y cuevas del Overworld. |
| `large_biomes` | Overworld con regiones de clima más amplias. |
| `amplified` | Overworld con montañas más pronunciadas. |
| `nether` | Cavernas y techo del Nether. |
| `end` | Terreno basado en las islas del End. |
| `caves` | Perfil de mundo de cavernas. |
| `floating_islands` | Islas flotantes con vacío debajo. |

### Ejemplo de superficie para Arena volcánica

En **Capas del terreno → Superficie**, abrí la paleta. Si trae un bloque al 100%, elegí **Heredar capa** para limpiar el borrador. Agregá estos cinco bloques con los porcentajes indicados y elegí **Guardar paleta** cuando el total llegue a 100%.

| Bloque para buscar | Porcentaje |
| --- | ---: |
| `granite` | 20% |
| `cobblestone` | 15% |
| `dirt` | 15% |
| `coal_block` | 40% |
| `obsidian` | 10% |

En **Grosor de la superficie**, ingresá `5`. Si querés una capa distinta debajo de esas cinco capas, configurá **Subsuelo** y **Grosor del subsuelo**. Los porcentajes son probabilidades de elección al generar el terreno. En una zona pequeña, la proporción observada puede diferir. Usá el bloque base para modificar la masa del terreno por debajo de estas capas.

Si querés más control sobre la forma, usá **Mundo → Generador avanzado**. En el ZIP exportado, `data/gm_<proyecto>/worldgen/noise_settings/terrain.json` muestra un punto de partida. En `world.noise-settings`, `noise_router` controla la distribución tridimensional del terreno, `noise` define el volumen de generación y `surface_rule` define reglas de materiales. Cada clave de primer nivel reemplaza la sección completa del perfil. Si editás un objeto como `noise_router`, incluí todos sus campos obligatorios. [Formato de datos de Java 1.21.6](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6)

### Ambiente, reglas y portales

**Ambiente visual** selecciona cielo, iluminación y horario de Overworld, Nether o End. **Reglas de dimensión → Reglas base** usa cualquiera de esos perfiles y hereda el del ambiente si lo dejás sin configurar. Desde ese menú también podés ajustar camas, anclas, evaporación del agua, lava rápida, piglins, incursiones y escala de coordenadas.

Para enlazar el mundo con un portal, configurá su destino con Multiverse-Portals. En 1.21.6, la pelea vanilla del dragón está ligada a la dimensión original del End. El cielo del End se puede usar en tu mundo personalizado. [Multiverse-Portals](https://mvplugins.org/portals/) · [Pelea del dragón en dimensiones personalizadas desde Java 26.1](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)

## Configurar biomas

- **Base y nombre.** El bioma base aporta clima, decoración y rasgos de Minecraft. **Nombre en el menú** solo cambia la etiqueta del editor. Por ejemplo, `bosque_umbrio` en el proyecto `arena_volcanica` se registra como `gm_arena_volcanica:bosque_umbrio`.
- **Relieve preferido.** Montañas, colinas, llanuras, costas y océanos orientan la ubicación del bioma en terrenos `overworld`, `large_biomes` y `amplified`. El ajuste selecciona zonas a partir de las señales de terreno, así que los bordes pueden mezclarse. Con un solo bioma, ese bioma ocupa todo el mundo. Agregá otro y dejalo con **Cualquier relieve** si querés una alternativa para otras zonas.
- **Clima y ubicación.** Temperatura local, humedad y lluvia o nieve describen el bioma donde estás. **Ubicación en el generador** usa temperatura, humedad, continentalidad, erosión, profundidad y rareza para decidir dónde puede aparecer. Podés dar un valor entre −2 y 2 o un rango ordenado como `-0,5 0,5`. Desplazamiento acepta un solo valor de 0 a 1. Las señales disponibles dependen del terreno. Por ejemplo, continentalidad ayuda a distinguir costa e interior en el Overworld.
- **Colores.** Configurá cielo, niebla, agua, niebla submarina, pasto o follaje con un código como `#55486e`. El ambiente y las reglas del cliente determinan qué colores se ven en cada dimensión.
- **Vegetación.** La decoración del bioma base viene activada por defecto. Podés desactivarla y conservar las plantas del catálogo y los árboles capturados que hayas asignado.
- **Mobs.** La aparición natural del bioma base está desactivada por defecto. En **Mobs naturales**, elegí una categoría y agregá las especies que querés, con peso de aparición y tamaño mínimo y máximo del grupo. **Mobs heredados** conserva las categorías del bioma base donde todavía no configuraste una lista. La dificultad, la luz, los bloques y las reglas de aparición de Minecraft siguen influyendo.

Para revisar la distribución, probá `/execute in gm_arena_volcanica:world run locate biome gm_arena_volcanica:bosque_umbrio` después del reinicio. Usá un ID de bioma que exista en el proyecto.

## Catálogo, variantes y aparición

El **Catálogo** separa árboles, construcciones, vegetación de un bloque y otros elementos. Podés guardar una plantilla, usarla en distintos biomas, copiarla a otro proyecto y actualizar su captura sin cambiar el ID. Cuando una plantilla se captura desde un bioma, se registra también un grupo de aparición para ese bioma.

**Grupos de aparición** reúne variantes que comparten frecuencia, altura y biomas. Por ejemplo, un grupo puede contener `sauce_01` con peso 3 y `sauce_02` con peso 1. Cuando el juego elige un sauce, la primera variante pesa tres veces más. Sumá la variante nueva al grupo y revisá sus otros grupos para que aparezca donde querés.

- **Distancia y probabilidad.** Distancia es el tamaño en chunks de la zona donde se busca una posición. Separación tiene que ser menor que la distancia. La probabilidad admite de 0 a 1 y filtra candidatos. Para árboles más juntos, probá una distancia baja. Para una ruina aislada, subila.
- **Altura.** Podés seguir la superficie, usar el fondo oceánico o fijar una altura. El desplazamiento vertical levanta la plantilla si es positivo y la hunde si es negativo. En cavernas o Nether, la superficie puede ser el techo. Probá una altura fija para estructuras subterráneas.
- **Terreno y aire.** Ajustá la adaptación del terreno si querés que una construcción se integre al suelo. **Conservar el terreno en huecos** mantiene los bloques donde tu selección tenía aire. Desactivalo para despejar el interior de una estructura cuando atraviesa terreno.
- **Selección.** El volumen incluye ambas esquinas y admite hasta 48 bloques por eje. Mantené cargados todos los chunks de la selección. Su cara inferior será la base de la plantilla. Activá entidades para conservar elementos como soportes para armaduras. Los bloques y sus datos, como los datos de cofres, se guardan siempre.
- **Vegetación de un bloque.** Desde el catálogo creá una planta, elegí su bloque, ajustá intentos por chunk, biomas y bloques de apoyo. La altura puede seguir la superficie o el fondo oceánico. Los intentos pueden fallar si el terreno elegido no admite el bloque.

En 1.21.6 las plantillas de árboles usan la colocación de estructuras. Pueden rotar y su densidad depende de las oportunidades de aparición por zona. Para un bosque más tupido, agrupá varios árboles en una plantilla o combiná tus variantes con la vegetación del bioma base. Para localizar una construcción usá el ID del grupo, por ejemplo `/execute in gm_arena_volcanica:world run locate structure gm_arena_volcanica:ruina_01`.

## Actualizar un mundo existente

1. Editá el mismo proyecto. Por ejemplo, capturá una nueva variante de un árbol y agregala al grupo que ya aparece en el mundo. Conservá el ID del proyecto y los IDs de biomas usados por los chunks existentes.
2. Hacé una copia del mundo y del datapack instalado. Elegí **Validar → Instalar datapack**. El plugin reemplaza el ZIP del proyecto y archiva la versión instalada anterior en `plugins/MDVGenerator/copias/<proyecto>/`.
3. Reiniciá el servidor por completo. Entrá al mismo mundo y explorá una zona que nunca se haya generado. Los cambios de colocación y terreno se ven en **chunks nuevos**. Los chunks ya generados, incluso los descargados o pregenerados, mantienen los bloques y las estructuras que tenían. Ajustes de registro como el color de un bioma pueden cambiar cómo se ve un chunk anterior.

Si el servidor tiene abierto el ZIP y no permite reemplazarlo, apagalo y copiá `plugins/MDVGenerator/exportaciones/gm_<proyecto>.zip` a `datapacks/` dentro del mundo principal. Guardá una copia del ZIP anterior antes de reemplazarlo. Conservá el nombre del archivo, los IDs de biomas y los límites verticales. Si restaurás una versión después de generar chunks con biomas nuevos, recuperá juntos el ZIP y el mundo de la misma copia.

Los cambios de relieve pueden dejar bordes visibles entre terreno viejo y nuevo. La instalación rechaza actualizaciones que eliminan un ID de bioma o cambian los límites verticales (`min_y` o `height`). Para esos cambios, creá otro proyecto. Mantené instalado el datapack mientras exista el mundo que usa sus registros. [Dependencia entre mundos y datapacks de generación](https://mvplugins.org/core/reference/faq/)

Para tener otra generación, volvé a **Proyectos → Crear proyecto**. Cada proyecto se exporta a su propio ZIP y crea una dimensión distinta. Desde el catálogo podés copiar una plantilla a otro proyecto y elegir su bioma de destino.

## Configuración avanzada y archivos

El editor guarda cambios en `plugins/MDVGenerator/proyectos/<proyecto>/generacion.yml`. Dentro de ese proyecto, `plantillas/` contiene los archivos `.nbt`. Los ZIP exportados quedan en `plugins/MDVGenerator/exportaciones/`.

Si preferís ajustar YAML, usá espacios para la indentación. Este ejemplo equivale a un bioma volcánico con una paleta de cinco capas, ambiente del End, reglas del Overworld, un tipo de mob y vegetación de un bloque. Ajustá los IDs al proyecto que estás editando. Si agregás un bioma nuevo a un mundo que ya existe, conservá sus entradas anteriores.

~~~yaml
world:
  terrain: overworld
  environment: end
  rules: overworld
  default-block: minecraft:basalt
  default-fluid: minecraft:water
  sea-level: 63
  dimension-type:
    bed_works: true
    respawn_anchor_works: false
    ultrawarm: false

biomes:
  bosque_umbrio:
    name: Arena volcánica
    base: minecraft:badlands
    surface:
      granite: 20
      cobblestone: 15
      dirt: 15
      coal_block: 40
      obsidian: 10
    surface-depth: 5
    subsurface: minecraft:blackstone
    subsurface-depth: 2
    relief: mountains
    temperature: 1.4
    downfall: 0
    precipitation: false
    colors:
      fog: '#55486e'
    vanilla-vegetation: false
    vanilla-mobs: false
    mobs:
      monster:
        magma_cube:
          weight: 12
          min: 1
          max: 3

vegetation:
  flor_lunar:
    block: minecraft:blue_orchid
    count: 8
    heightmap: surface
    substrate: [minecraft:dirt]
    biomes: [bosque_umbrio]
~~~

En este ejemplo, `flor_lunar` solo se coloca sobre tierra. Omití `substrate` para usar el soporte sólido automático, sujeto a las reglas de supervivencia del bloque.

**Rutas de ajustes.** En las rutas siguientes, `<bioma>`, `<grupo>`, `<elemento>` y `<plantilla>` son IDs de tu proyecto.

| Ruta en `generacion.yml` | Ajustes |
| --- | --- |
| `world` | `terrain`, `environment`, `rules`, `default-block`, `default-fluid`, `sea-level`. |
| `world` y `biomes.<bioma>` | `surface` y `subsurface` admiten un bloque o un mapa de porcentajes enteros que sumen 100. `surface-depth` de 1 a 16 y `subsurface-depth` de 0 a 64. |
| `world.noise-settings`, `world.dimension-type` | Campos nativos de Minecraft. Los menús avanzados también permiten editarlos en JSON. |
| `biomes.<bioma>` | `name`, `base`, `relief`, `temperature`, `downfall`, `precipitation`, `vanilla-vegetation`, `vanilla-mobs`. |
| `biomes.<bioma>.colors` | `sky`, `fog`, `water`, `water-fog`, `grass`, `foliage`. |
| `biomes.<bioma>.climate` | `temperature`, `humidity`, `continentalness`, `erosion`, `depth`, `weirdness` y `offset`. Los valores indicados reemplazan los parámetros del relieve para esas señales. |
| `biomes.<bioma>.mobs.<categoría>.<especie>` | `weight` de 1 a 1000 y `min`/`max` de 1 a 64. Una categoría configurada reemplaza su lista heredada. Una categoría con `{}` queda vacía. |
| `biomes.<bioma>.data` | Campos avanzados de la definición vanilla del bioma. |
| `catalog.<plantilla>.kind` | Tipo de plantilla guardada, `tree`, `building` u `other`. |
| `structures.<grupo>` | `kind`, `biomes`, `templates`, `spacing`, `separation`, `frequency`, `salt`, `heightmap`, `height`, `height-offset`, `terrain-adaptation` e `ignore-air`. |
| `vegetation.<elemento>` | `block`, `count`, `heightmap`, `substrate` y `biomes`. |

Este ejemplo de grupo presupone que `sauce_01.nbt` y `sauce_02.nbt` ya están en `plantillas/`.

~~~yaml
structures:
  sauces:
    kind: tree
    biomes: [bosque_umbrio]
    templates:
      sauce_01: 3
      sauce_02: 1
    spacing: 2
    separation: 1
    frequency: 0.8
    heightmap: surface
    height-offset: 0
    ignore-air: true
~~~

En `templates`, una lista da el mismo peso a cada variante. Los pesos individuales van de 1 a 150. `heightmap` admite `surface`, `ocean_floor` y `fixed`. `terrain-adaptation` admite `none`, `bury`, `beard_thin` y `beard_box`. Los IDs de proyecto y plantilla admiten hasta 32 caracteres con letras minúsculas, números y guion bajo, empezando por una letra. Los biomas nuevos creados desde el menú admiten también guion medio.

## Comandos

| Comando | Uso |
| --- | --- |
| `/mdv` | Abre el último proyecto de tu sesión o la lista de proyectos. |
| `/mdv menu [proyecto]` | Abre la lista o el proyecto indicado. |
| `/mdv entrada <valor>`, `/mdv cancelar` | Envía el valor pedido por el menú o cancela la entrada. |
| `/mdv crear <proyecto>`, `/mdv proyectos` | Crea o lista proyectos. |
| `/mdv pos1`, `/mdv pos2` | Marca los bloques donde están tus pies para seleccionar un volumen. |
| `/mdv guardar <proyecto> <plantilla> <bioma> <arbol\|estructura\|otro> [--entidades]` | Captura la selección y la asocia al bioma elegido. |
| `/mdv actualizar <proyecto> <plantilla> [--entidades]` | Vuelve a capturar una plantilla conservando sus grupos. |
| `/mdv copiar <origen> <plantilla> <destino> <bioma>` | Reutiliza una plantilla en otro proyecto. |
| `/mdv validar <proyecto>` | Comprueba la configuración del plugin y las referencias. Purpur revisa los datos nativos al arrancar. |
| `/mdv exportar <proyecto>` | Escribe el ZIP en `exportaciones/`. |
| `/mdv instalar <proyecto>` | Escribe o reemplaza el ZIP del proyecto en `datapacks/` del mundo principal. Reiniciá después. |

`/gm`, `/mdvgenerator` y `/generamundos` son alias de `/mdv`.
