# GeneraMundos

Plugin para **Purpur 1.21.6** que convierte construcciones de tu servidor en plantillas reutilizables y exporta un datapack de generación por proyecto. Cada proyecto tiene su propia dimensión, su configuración y su biblioteca de árboles y estructuras. Podés instalar varios a la vez y entrar a ellos desde **Multiverse-Core 5.7.3**.

## Compilar e instalar el plugin

**En GitHub.** Descomprimí el código y subí el contenido de la carpeta al repositorio, incluida la carpeta oculta `.github`. En **Actions**, abrí **Compilar plugin**, ejecutá **Run workflow** y descargá el artefacto **GeneraMundos** desde el resultado. Dentro vas a encontrar `GeneraMundos-1.0.0.jar`.

**En tu computadora.** Con JDK 21 y Maven, ejecutá desde la carpeta que tiene `pom.xml`:

```bash
mvn -B clean verify
```

El JAR queda en `target/GeneraMundos-1.0.0.jar`. Copialo a `plugins/` del servidor Purpur 1.21.6 y reiniciá. Los comandos `/gm` requieren `generamundos.admin`, que tienen los operadores por defecto. Para la importación automática de las dimensiones, usá Multiverse-Core 5.7.3. En la prueba con 5.1.1, Purpur cargó la dimensión y Multiverse falló al registrarla. [Multiverse-Core 5.7.3](https://modrinth.com/plugin/multiverse-core/version/5.7.3)

## Crear la primera generación

1. Ejecutá `/gm crear bosque_del_vacio`. Eso crea `plugins/GeneraMundos/proyectos/bosque_del_vacio/generacion.yml`. Editá ese archivo con un editor de texto para definir terreno y biomas.
2. Construí un árbol en tu mundo de construcción. Parate en una esquina inferior del volumen y ejecutá `/gm pos1`. Parate en la esquina superior opuesta y ejecutá `/gm pos2`. Ambos comandos toman el bloque donde están tus pies. Incluí el tronco entero, la copa y las raíces que quieras conservar.
3. Ejecutá `/gm guardar bosque_del_vacio sauce_01 bosque_umbrio arbol`. `bosque_umbrio` es el ID de bioma del ejemplo inicial. Para una ruina, repetí la selección y usá `/gm guardar bosque_del_vacio ruina_01 bosque_umbrio estructura`. Las plantillas quedan en `plantillas/` dentro del proyecto.
4. Ejecutá `/gm validar bosque_del_vacio` para revisar la configuración. Después, `/gm instalar bosque_del_vacio` prepara y coloca `gm_bosque_del_vacio.zip` en `datapacks/` del mundo principal. Si preferís copiarlo a mano, `/gm exportar bosque_del_vacio` lo guarda en `plugins/GeneraMundos/exportaciones/`.
5. Reiniciá el servidor completo. Entrá en creativo para inspeccionar la primera generación. Usá `/mv list` y después `/mv tp <nombre>` con el nombre exacto que muestre Multiverse. La clave de la dimensión de Minecraft es `gm_bosque_del_vacio:world`.

Con Multiverse-Core 5.7.3, Purpur carga la dimensión desde el datapack al arrancar y Multiverse registra el mundo cargado. En `plugins/Multiverse-Core/config.yml`, `world.auto-import-3rd-party-worlds` debe estar habilitado. Para verificar si Minecraft cargó la dimensión, ejecutá `/execute in gm_bosque_del_vacio:world run time query daytime`. Si eso funciona y el mundo falta en `/mv list`, revisá la versión y la configuración de importación de Multiverse. Usá `/gm instalar` para crear la dimensión y `/mv tp` para entrar. [Datapacks y Multiverse](https://mvplugins.org/core/reference/faq/) · [Configuración de Multiverse](https://mvplugins.org/core/reference/configuration-file/)

## Comandos

| Comando | Qué hace |
| --- | --- |
| `/gm crear <proyecto>` | Crea una generación independiente y su archivo editable. |
| `/gm proyectos` | Lista las generaciones guardadas. |
| `/gm pos1`, `/gm pos2` | Marcan las esquinas de la selección desde tu posición. |
| `/gm guardar <proyecto> <plantilla> <bioma> <arbol\|estructura> [--entidades]` | Guarda la selección y la asocia al bioma elegido. |
| `/gm actualizar <proyecto> <plantilla> [--entidades]` | Vuelve a capturar los bloques de una plantilla conservando su configuración. |
| `/gm copiar <origen> <plantilla> <destino> <bioma>` | Reutiliza una plantilla en otra generación con su mismo nombre y el bioma que elegís. |
| `/gm validar <proyecto>` | Revisa los ajustes del plugin, las referencias de plantillas y la exportación. Purpur comprueba los ajustes avanzados al arrancar. |
| `/gm exportar <proyecto>` | Escribe el datapack `.zip` en `exportaciones/`. |
| `/gm instalar <proyecto>` | Escribe o reemplaza el `.zip` del proyecto en el mundo principal. Reiniciá después. |

Para proyectos y plantillas, usá IDs de hasta 32 caracteres con minúsculas, números y guiones bajos, empezando por una letra. Por ejemplo, `pantano_lunar`. Para entrar a un mundo por un nombre con espacios, podés darle un alias desde Multiverse. La selección incluye ambas esquinas y puede medir hasta 48 bloques por eje. Mantené cargados todos los chunks de la selección. La altura de la esquina inferior será la base de la plantilla. `--entidades` sirve para una construcción que deba conservar entidades seleccionadas, como soportes para armaduras. Una plantilla normal guarda los bloques y sus datos, por ejemplo las variantes de troncos y los datos de cofres.

## Diseñar el terreno y el ambiente

En `generacion.yml`, usá espacios para mantener la indentación. Este ejemplo crea islas con cielo del End y reglas del Overworld. Podés usar la parte de `world` y `biomes` antes de capturar las primeras plantillas.

```yaml
world:
  terrain: floating_islands
  environment: end
  rules: overworld
  default-block: minecraft:end_stone
  surface: minecraft:moss_block
biomes:
  bosque_umbrio:
    base: minecraft:forest
    temperature: 0.6
    downfall: 0.8
    precipitation: true
    vanilla-vegetation: false
    surface: minecraft:grass_block
    subsurface: minecraft:dirt
    colors:
      fog: '#55486e'
      grass: '#748b5a'
```

### Forma y materiales

| `world.terrain` | Forma de partida |
| --- | --- |
| `overworld` | Continentes, montañas, océanos y cuevas del Overworld. |
| `large_biomes` | Perfil del Overworld con regiones de clima más amplias. |
| `amplified` | Relieve del Overworld con montañas más pronunciadas. |
| `nether` | Cavernas y techo del Nether. |
| `end` | Terreno basado en las islas del End. |
| `caves` | Perfil de mundo de cavernas. |
| `floating_islands` | Islas flotantes con vacío debajo. |

`world.default-block` define el material base del terreno. `world.default-fluid` define el fluido de base y `world.sea-level` ajusta el nivel de referencia del agua o la lava. `world.surface` cubre la capa superior y `world.subsurface` las dos capas siguientes. También podés poner `surface` y `subsurface` en cada bioma para darle sus propios materiales. Usá IDs de bloques Java 1.21.6, como `minecraft:deepslate`, `minecraft:water` o `minecraft:blackstone`. Probá primero el perfil que más se parezca al resultado que querés y cambiá un ajuste por vez.

Para modificar la forma de manera más fina, exportá una vez y abrí `data/gm_<proyecto>/worldgen/noise_settings/terrain.json` dentro del ZIP. Usalo como referencia para escribir las claves de Minecraft 1.21.6 en `world.noise-settings`. Cada clave de primer nivel que indiques sustituye esa sección completa del perfil. `noise_router` controla la distribución tridimensional del terreno, `noise` define el volumen de generación y `surface_rule` controla las capas de bloques. Si cambiás un objeto como `noise_router`, incluí todas sus claves obligatorias. Guardá esos cambios en `generacion.yml` para que cada exportación los reproduzca. [Cambios del formato de datos en Java 1.21.6](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6)

### Cielo, reglas y portales

`world.environment` usa `overworld`, `nether` o `end` para el aspecto del cielo, la iluminación y el horario base de la dimensión. `world.rules` admite esos mismos perfiles y hereda el de `environment` si lo omitís. Por ejemplo, usá `environment: nether` y `rules: overworld` si querés el ambiente del Nether y reglas de camas y agua del Overworld.

Para personalizar reglas individuales, agregá `world.dimension-type`. En Java 1.21.6, `bed_works` controla las camas, `respawn_anchor_works` controla las anclas y `ultrawarm` aplica el comportamiento cálido del Nether, incluida la evaporación del agua. Por ejemplo, debajo de `world`:

```yaml
  dimension-type:
    bed_works: true
    respawn_anchor_works: false
    ultrawarm: false
```

Vinculá el acceso al mundo con Multiverse-Portals si querés que tus jugadores entren por un portal. Las reglas de la dimensión y el destino de un portal son ajustes distintos. En 1.21.6, el cielo del End se puede usar en una dimensión propia y la pelea vanilla está ligada a la dimensión original del End. La opción de habilitar esa pelea en otras dimensiones llegó en Java 26.1. [Multiverse-Portals](https://mvplugins.org/portals/) · [Pelea del dragón en Java 26.1](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)

## Agregar y ubicar biomas

Cada entrada de `biomes` tiene un ID que elegís. `bosque_umbrio` en el proyecto `bosque_del_vacio` se registra como `gm_bosque_del_vacio:bosque_umbrio`. `base` toma de un bioma vanilla su configuración inicial, incluidos clima, mobs y decoración. Elegí `surface` y `subsurface` explícitamente si querés un suelo concreto, por ejemplo arena en tu desierto. `temperature`, `downfall` y `precipitation` ajustan el clima del bioma. `colors` admite `sky`, `fog`, `water`, `water-fog`, `grass` y `foliage`, con colores entre comillas como `'#55486e'`. Con `vanilla-vegetation: false` podés dejar que destaquen los árboles que construiste vos. Para ajustes avanzados, `data` permite sustituir secciones del bioma de Minecraft, por ejemplo `spawners` para personalizar la lista de mobs.

Para agregar otro, sumá una entrada al mismo nivel, por ejemplo `pico_helado`. Con un solo bioma, la dimensión lo usa en todo el terreno. Con varios, el generador los distribuye por el ruido climático. `climate` permite dirigir esa distribución con `temperature`, `humidity`, `continentalness`, `erosion`, `depth`, `weirdness` y `offset`. Salvo `offset`, podés usar un número o un rango de dos números. `climate.temperature` decide **dónde aparece** el bioma en el mapa. `temperature` directamente bajo el bioma describe el **clima del lugar** una vez generado.

Este fragmento, colocado bajo `biomes`, orienta cada bioma hacia un rango diferente de temperatura del generador:

```yaml
  bosque_umbrio:
    base: minecraft:forest
    climate:
      temperature: [0.1, 1.0]
  pico_helado:
    base: minecraft:snowy_slopes
    climate:
      temperature: [-1.0, 0.1]
```

Las señales disponibles dependen del perfil de terreno. Por ejemplo, `continentalness` ayuda a distinguir costa e interior con `overworld`, mientras que algunos perfiles usan un valor constante para esa señal. Para probar la distribución después de reiniciar, usá `/execute in gm_bosque_del_vacio:world run locate biome gm_bosque_del_vacio:pico_helado` y compará el resultado en distintos puntos del mapa. Elegí los IDs definitivos antes de instalar el mundo, así podés cambiar el aspecto de un bioma en el futuro sin cambiar la clave que guardaron los chunks.

## Controlar árboles, ruinas y otras plantillas

En `structures`, cada grupo define dónde y con qué frecuencia puede aparecer un conjunto de plantillas. `/gm guardar` crea un grupo con el mismo ID que la plantilla. Para reunir variantes, usá un solo grupo y agregá sus nombres bajo `templates`. Este ejemplo presupone que ya guardaste `sauce_01`, `sauce_02` y `ruina_01`. Podés conservar `sauce_01` como grupo de ambos árboles y quitar del YAML el grupo `sauce_02` que acabás de crear. La plantilla `sauce_02.nbt` sigue en `plantillas/` y se elige desde el grupo `sauce_01`.

```yaml
structures:
  sauce_01:
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
  ruina_01:
    kind: building
    biomes: [bosque_umbrio]
    templates: [ruina_01]
    spacing: 24
    separation: 8
    frequency: 0.7
```

- **Variantes.** Una lista da el mismo peso a todas las plantillas. Con el mapa del ejemplo, `sauce_01` tiene tres veces el peso de `sauce_02` cuando el juego elige un sauce. Si movés plantillas de un grupo a otro, revisá que cada una quede en los grupos donde querés verla.
- **Frecuencia.** `spacing` marca el tamaño en chunks de cada zona donde se busca una posición. `separation` debe ser menor que `spacing`. `frequency` va de `0` a `1` y reduce la proporción de candidatos que se intentan colocar. Para árboles más juntos, empezá con un `spacing` bajo. Para ruinas aisladas, subilo. El bioma y el relieve también influyen en lo que aparece.
- **Altura.** `heightmap: surface` sigue la superficie. `ocean_floor` toma el fondo de una zona cubierta por agua. `fixed` usa la altura `height` que indiques. `height-offset` levanta la plantilla si es positivo y la hunde si es negativo. En terreno de cavernas o Nether, la superficie puede ser el techo. Para un sitio subterráneo, probá con `fixed` y una altura adecuada. En zonas irregulares, diseñá cimientos o raíces que disimulen desniveles. `terrain-adaptation` permite probar `none`, `bury`, `beard_thin` o `beard_box` para ajustar cómo se forma el terreno alrededor de una construcción.
- **Aire.** `ignore-air: true` protege el terreno donde tu selección contenía aire. Cambialo a `false` para una estructura con interiores que necesiten despejar los bloques que atraviesa. Revisá cómo queda en una zona de prueba, porque el volumen de la selección también afecta el resultado.

En 1.21.6 las plantillas se colocan como estructuras del generador. Los árboles hechos a mano conservan su diseño y pueden rotar. Su densidad depende de las oportunidades de estructura por zona. Si querés un bosque muy poblado, combiná los árboles hechos a mano con vegetación del bioma base o agrupá varios árboles en una sola plantilla.

Para buscar una construcción, usá el ID del **grupo**. Por ejemplo, `/execute in gm_bosque_del_vacio:world run locate structure gm_bosque_del_vacio:ruina_01`.

## Actualizar un mundo existente

Si querés sumar más árboles, capturá otra variante en **el mismo proyecto** y agregala a `templates` dentro del grupo de árboles que ya usás. Conservá el ID del proyecto, el nombre del ZIP, los IDs de biomas y los de grupos que ya existen. Así Minecraft puede leer las referencias guardadas por el mundo.

1. Hacé una copia del mundo y del datapack instalado. Probá primero en una copia del servidor si vas a tocar terreno o biomas.
2. Ejecutá `/gm validar bosque_del_vacio` y `/gm instalar bosque_del_vacio`. Cuando se reemplaza una versión instalada, el ZIP anterior se archiva en `plugins/GeneraMundos/copias/bosque_del_vacio/`.
3. Reiniciá el servidor por completo. En ese mismo mundo, explorá una zona que nunca se haya generado. La nueva versión del datapack rige para los chunks que el servidor genere desde ese arranque. Los chunks ya generados, incluso los que estén descargados o los que preparó un pregenerador, conservan sus bloques y sus estructuras.

Si el servidor mantiene ocupado el ZIP, como ocurrió en la prueba en Windows, `/gm instalar` deja la nueva versión en `plugins/GeneraMundos/exportaciones/`. Apagá el servidor y usá ese archivo para reemplazar `gm_bosque_del_vacio.zip` dentro de `datapacks/` antes de volver a iniciarlo. Conservá el nombre, los IDs de biomas existentes y los límites verticales al reemplazarlo. Si necesitás restaurar una versión después de haber generado chunks con biomas nuevos, restaurá juntos el ZIP y la copia del mundo del mismo momento.

Agregar variantes o cambiar sus pesos es el cambio más simple. Purpur 1.21.6 vuelve a leer las definiciones de la dimensión al arrancar. Eso permite sumar biomas o cambiar su distribución para los próximos chunks. El relieve nuevo puede formar bordes visibles junto a los chunks anteriores. `/gm instalar` protege el mundo y rechaza una actualización que quite un ID de bioma o cambie los límites verticales (`min_y` o `height`). Para hacer esos cambios, creá otro proyecto. Mantené instalado el datapack mientras exista un mundo que use sus registros y hacé cambios desde la configuración del proyecto. [Dependencia de mundos y datapacks de generación](https://mvplugins.org/core/reference/faq/)

## Varias generaciones

Usá `/gm crear cavernas_rojas` para tener un segundo archivo `generacion.yml` y un segundo ZIP. Podés copiar una plantilla existente con `/gm copiar bosque_del_vacio ruina_01 cavernas_rojas gruta_roja`, después de definir `gruta_roja` en el proyecto nuevo. Cada proyecto instalado registra una dimensión distinta. Volvé al proyecto original cuando quieras cambiar lo que aparecerá en sus próximos chunks.

## Prueba de integración

En Purpur 1.21.6 con Multiverse-Core 5.7.3 se verificó la importación de dos proyectos. En uno de ellos se capturó una segunda variante, se agregó un bioma y se reemplazó el ZIP conservando la dimensión. Tras reiniciar, los 9 chunks de la primera muestra conservaron la plantilla inicial, ambas variantes aparecieron en una muestra de 25 chunks nuevos y `locate biome` encontró el bioma agregado.
