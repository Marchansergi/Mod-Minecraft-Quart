# Quart – mod de Minecraft

Mod de **Fabric para Minecraft 1.21.1** que genera el pueblo de **Quart (Girona)** a **escala real
(1 bloque = 1 metro)** alrededor del punto de aparición. El resto del mundo es Minecraft normal.

- El jugador aparece delante de la **Estació de Quart**.
- La zona del pueblo (unos 2 × 2,4 km, de la Estació a la C-65 y hasta Casa 1 al sureste) usa
  el **relieve real**, las **calles**, las **parcelas**, los **edificios** y los **usos del suelo** reales.
- Alrededor hay una franja de 96 bloques en la que el terreno real se funde con el terreno vanilla.
  Más allá, el mundo es un mundo normal (con la semilla que elijas).
- Dentro del pueblo no se generan aldeas, templos ni minas vanilla, y el bioma es llanura.

![Vista previa del ráster](docs/preview.png)

## Instalación

1. Instala [Fabric Loader](https://fabricmc.net/use/) para Minecraft **1.21.1** y el
   [Fabric API](https://modrinth.com/mod/fabric-api) (0.116.x+1.21.1).
2. Compila el mod (`./gradlew build`) o descarga el `.jar` y cópialo en la carpeta `mods`.
3. Crea un **mundo nuevo** (tipo "Por defecto"). El pueblo solo aparece en mundos nuevos.

## Qué sale en el mundo

| Elemento | Cómo se genera |
|---|---|
| Relieve | Modelo digital de elevación (EU-DEM/SRTM, ~30 m, suavizado). La Estació (98 m s.n.m.) queda en Y = 72 |
| Calles | OpenStreetMap: calzada gris con líneas discontinuas en la C-250z, aceras con baldosas rosadas y blancas, bordillo y farolas |
| Edificios | 682 plantas de OpenStreetMap + 228 de Microsoft Building Footprints (para cubrir los huecos de OSM en el centro) |
| Alturas | Heurística: casas de 2 plantas, edificios del centro y de la carretera de 3, naves de 1 planta alta |
| Tejados | A cuatro aguas de teja (ladrillo/granito), planos en bloques y naves, bóveda roja en el pabellón |
| Suelo | OSM + ESA WorldCover: bosques (pinos piñoneros y encinas), campos de trigo, prados, jardines, piscinas |
| Deporte | Campo de fútbol Gaudenci Sellés con líneas y porterías, pistas con canastas |
| Foto aérea (PNOA) | De la ortofoto oficial del IGN (0,5 m) salen: el tipo de suelo de cada parcela (césped, tierra seca, patio pavimentado), unos 31.000 árboles en su posición real y con su tamaño de copa, las piscinas (forma real), el color del tejado de cada edificio (teja roja, naranja, marrón, gris o terraza) y 310 cobertizos/garajes que no salen en ningún mapa |
| Via Verda | El antiguo Carrilet Girona–Sant Feliu, en rojo |

### Edificios y lugares destacados (`tools/landmarks.json`)

Estació (spawn, con la locomotora y los bancos de la plaza), Museu de la Terrissa (con la chimenea
de la bòbila), Local social (con la zona de bancos, olivos y escultura), Pavelló, Escola Nou de Quart,
Parc de la Tirolina (tirolina y parque infantil), El teu Súper y las casas 1 a 9 de las fotos.
Cada uno tiene materiales, número de plantas, tejado y una **fachada dibujada a mano** a partir
de Street View (puertas, ventanas con persiana o reja, garajes, balcones con su barandilla, escaparates,
zócalo, alero, tejado a cuatro o a dos aguas, chimenea, porche y el muro de la parcela con sus paneles).

**Interiores** de los edificios destacados, según su uso real:
- Casas (1–9): escalera entre plantas, tabiques con puertas, salón (sofá, tele, estantería, alfombra), cocina
  (nevera, fregadero, horno, mesa con sillas), dormitorios (camas, armario, escritorio) y baño. Las casas
  adosadas (Casa 2, Casa 5, Casa 9) tienen cada vivienda separada.
- Estació: punto de información juvenil (mostrador, folletos, bancos), con el cartel **QUART** en la fachada.
- Museu de la Terrissa: pedestales con vasijas, vitrinas y estantes de cerámica; arriba, el taller (horno, torno, arcilla).
- Local social: barra, mesas y sillas. Escola Nou de Quart: aulas con pizarra, pupitres y estanterías.
- Pavelló: pista con líneas, canastas y gradas. El teu Súper: pasillos, neveras, fruta y caja; piso arriba.
- Casa 8: la tienda de telas "El Cuc de Seda" en la planta baja (con su rótulo) y pisos arriba.

**Plaza del Pavelló:** explanada de hormigón con el dibujo de líneas blancas sacado de la foto aérea.
Las cintas de líneas paralelas forman una lágrima alrededor de la **montañita** (asfalto oscuro con anillos
blancos) y unas horquillas hacia el sur. También tiene las farolas altas y el banco largo de hormigón. El
pabellón tiene su fachada de Street View: planta baja ocre con puertas, porche plano sobre pilares, franja
de paneles granate y beige con montantes, y la bóveda roja con borde blanco.

**Calles:** pasos de cebra donde están registrados en OpenStreetMap, y los bancos, fuentes y paradas de
autobús que constan allí.

Entre la escuela/Local social y el Museu de la Terrissa hay un **parque con bancos**: camino de tierra,
árboles a los dos lados, bancos mirando al camino, postes de madera en la entrada y una farola.

## Limitaciones (importante)

- **Las casas se parecen a las reales, pero no son copias exactas.** Street View solo muestra la
  fachada de la calle y a 1 bloque = 1 m no caben detalles de menos de un metro. La parte trasera,
  los patios y el interior son genéricos.
- **Alturas aproximadas.** No he podido descargar datos con la altura real de cada edificio: el
  Catastro y el ICGC bloquean la conexión desde el servidor donde se ha hecho el mod. Intenté
  calcularlas con la longitud de las sombras de la foto aérea, pero el resultado no era fiable y lo
  descarté. Si descargas tú los datos del Catastro (ver abajo), el pipeline puede usarlos.
- **Google Maps / Earth / Street View no se usan directamente:** sus imágenes no se pueden descargar de
  forma automatizada (lo prohíben sus condiciones). Lo de Street View sale de tus capturas; la vista
  aérea sale de la ortofoto oficial PNOA del IGN, que es abierta.
- **Pasos de cebra:** solo salen los 10 registrados en OpenStreetMap. Intenté detectarlos en la foto aérea,
  pero a 0,5 m las franjas casi no se distinguen del asfalto y no era fiable.
- En la plaza del Pavelló, las líneas reales son muchas y muy finas. A 1 bloque = 1 m se dibujan como
  líneas de 1 bloque alternas, siguiendo el contorno de cada cinta.
- Los interiores son una distribución típica según el uso de cada edificio: nadie ha publicado cómo
  son por dentro, así que no son copias del interior real.
- El modelo de elevación es de ~30 m, así que los desniveles pequeños (un muro, un talud) no salen.
- Las casas 2, 8, 9 y El teu Súper no están en OpenStreetMap: su planta es un rectángulo
  colocado delante de la cámara de Street View, con las medidas estimadas a partir de las fotos.
- Bajo el pueblo **no hay minerales** (las features vanilla se desactivan en esa zona para que no
  salgan árboles ni lagos en medio de las calles). Las cuevas sí se generan.

## Cómo mejorar la precisión

Todo el pueblo sale de `tools/build_town.py`, que lee `tools/data/` y `tools/landmarks.json` y
escribe `src/main/resources/data/quartmod/town/`.

```bash
pip install numpy scipy shapely pillow
python3 tools/fetch_ortho.py       # (opcional) descarga la ortofoto PNOA a tools/cache/ (~1.600 imágenes)
python3 tools/ortho_features.py    # (opcional) extrae suelos, árboles y piscinas -> tools/data/
python3 tools/build_town.py        # regenera los datos y docs/preview.png
./gradlew build                    # compila el mod
```

- **Datos oficiales:** descarga desde tu ordenador la *Referència Topogràfica Territorial* del
  ICGC en GeoPackage 3D (edificios con altura) o los edificios del Catastro INSPIRE del municipio
  17151 (Quart) y pásamelos. Con eso las alturas y las plantas serán exactas.
- **Más casas destacadas:** añade una entrada a `tools/landmarks.json` con el punto (o la cámara
  de Street View), materiales y la fachada.

## Estructura

```
src/main/java/cat/quart/mod/
  QuartMod.java              inicialización
  town/TownData.java         carga el ráster del pueblo
  town/TownGenerator.java    terreno, suelo, muros de parcela, farolas
  town/Buildings.java        paredes, ventanas, fachadas, pisos, tejados
  town/Trees.java            árboles mediterráneos
  town/Decorations.java      locomotora, chimenea, bancos, tirolina, porterías, rótulos...
  town/Interiors.java        interiores de los edificios destacados
  mixin/                     enganches con la generación de mundo de Minecraft
tools/
  build_town.py              datos reales -> ráster del mod
  landmarks.json             edificios destacados
  render_world.py            dibuja un mundo generado visto desde arriba (para comprobar)
  iso_render.py              vista isométrica de una zona del mundo generado
  elevation.py               fachada vista de frente (para comparar con Street View)
  floor_plan.py              planta de un piso (para revisar los interiores)
  fetch_ortho.py             descarga la ortofoto PNOA
  ortho_features.py          analiza la ortofoto (suelo, árboles, piscinas, tejados)
  data/                      datos de origen
docs/
  preview.png                vista previa del ráster
  referencias/               tus fotos de Google Maps / Street View
```

## Créditos de los datos

- © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL)
- [Microsoft Global ML Building Footprints](https://github.com/microsoft/GlobalMLBuildingFootprints) (ODbL)
- [ESA WorldCover 2021](https://esa-worldcover.org) (CC BY 4.0)
- [AWS Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) (EU-DEM, SRTM)
- Ortofoto [PNOA](https://pnoa.ign.es/) © Instituto Geográfico Nacional de España (CC BY 4.0)
- Fotos de la Estació y la locomotora: Wikimedia Commons (Panoramio), usadas solo como referencia
