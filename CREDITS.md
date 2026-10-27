# Créditos y licencias de los modelos 3D

Todos los modelos incluidos en `app/src/main/assets/` descargados de Sketchfab llevan la licencia
**Creative Commons Attribution 4.0 (CC BY 4.0)** — https://creativecommons.org/licenses/by/4.0/ —
que obliga a **citar al autor** e **indicar si se han hecho cambios**.
Los datos (autor, licencia, enlace) están leídos de los metadatos que traen los propios archivos `.glb`.

Cambios hechos por este proyecto: escalado y recentrado de los modelos; el coche se puede repintar en la app
(se cambia el color base del material de la carrocería); los modelos de coche añadidos después (Model 3 alternativo, Porsche Carrera 4S, Opel) están además **simplificados** (menos triángulos) y con texturas reducidas a 1024 px para que quepan en el móvil.

| Archivo | Modelo | Autor | Enlace |
|---|---|---|---|
| `cars/tesla_model3.glb` | Tesla 2018 Model 3 (versión con luces e intermitentes mejorados, modificada por el autor del proyecto) | Ameer Studio (CC BY 4.0) | https://sketchfab.com/3d-models/tesla-2018-model-3-5ef9b845aaf44203b6d04e2c677e444f |
| `cars/opel_gt.glb` | Opel GT Retopo | systmh (**CC BY-NC-SA 4.0**: solo uso no comercial, y compartir igual) | https://sketchfab.com/3d-models/opel-gt-retopo-badcab3c8a3d42359c8416db8a7427fe |
| `cars/tesla_cybertruck.glb` | Tesla Cybertruck low poly | Igor Tretyakov (CC BY 4.0) | https://sketchfab.com/3d-models/tesla-cybertruk-low-poly-e3558b991e75418cb45624fab4d980e5 |
| `cars/ford_mustang_1965.glb` | Ford Mustang 1965 | Pooya_dh (**CC BY-NC 4.0**) | https://sketchfab.com/3d-models/ford-mustang-1965-5f4e3965f79540a9888b5d05acea5943 |
| `cars/xiaomi_su7.glb` | 2024 Xiaomi SU7 Max | Ddiaz Design (**CC BY-NC-SA 4.0**) | https://sketchfab.com/3d-models/2024-xiaomi-su7-max-db30dd1e254243b4ba002c6ce65df052 |
| `traffic/*.glb` (compact, coupe, hatchback, minivan, offroad, pickup, sedan, sport, suv, wagon) | Generic passenger car pack (CC BY 4.0) | Comrade1280 | https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5 |
| `scene/buildings/edificio_3.glb` | Edificio 3 | Elbolillo | https://sketchfab.com/3d-models/edificio-3-089d8e3e06fc4d27b9871abe3afc96fa |
| `scene/buildings/edificio_5.glb` | Edificio 5 | Elbolillo | https://sketchfab.com/3d-models/edificio-5-3aa4895820e648e9b05d7630e3b787ae |
| `scene/buildings/edificio_brick.glb` | Edificio Brick | 3D JavierV | https://sketchfab.com/3d-models/edificio-brick-5c62728aa48c4f9b9a4051b304b040e9 |
| `scene/lamps/farola.glb` | Farola | Juan (joan151006) | https://sketchfab.com/3d-models/farola-ddbfc67ef66641a7b4fc8df38911ffda |
| `scene/landmarks/edificio_corficolombiana_colombian_skyscraper.glb` | Edificio Corficolombiana | Robal | https://sketchfab.com/3d-models/edificio-corficolombiana-colombian-skyscraper-df951b34189b405996108bea499fee86 |
| `scene/structures/tesla_super_charger.glb` | Tesla Super Charger (low-poly) | Suyog modak | https://sketchfab.com/3d-models/tesla-super-charger-low-poly-b9fc975778f542babbb2e861d32b1acd |
| `scene/trees/*.glb` | Pino, álamo, árbol redondo | Generados por código para este proyecto | — |

## Versión web
El modelo `web/assets/tesla_model3.glb` es el Tesla 2018 Model 3 de Ameer Studio (CC BY 4.0) comprimido con meshopt y texturas WebP de 1024 px (herramienta gltf-transform); la web muestra el crédito en el botón ⓘ.

Los edificios de `web/assets/` (Edificio 3, Edificio 5, Edificio Brick y Edificio Corficolombiana) son los mismos modelos de la tabla de arriba, comprimidos con meshopt y texturas WebP.

## Marcas
Tesla y Model 3 son marcas de Tesla, Inc. Este proyecto no está afiliado a Tesla ni a ninguna marca de automoción;
los modelos se usan solo como contenido de demostración.

## Datos
- `app/src/main/assets/data/senales.json` y `radares.json`: puntos de stop, ceda el paso, semáforos, pasos de peatones y radares.
  Según el autor del proyecto proceden de **TomTom** (los archivos no lo indican y no se ha podido verificar). El autor ha decidido publicarlos en este repositorio bajo su responsabilidad.
  Si el titular de los datos pide retirarlos, se eliminan del repositorio; regenerarlos desde OpenStreetMap (ODbL, citando «© colaboradores de OpenStreetMap») es la alternativa libre.
- Mapas: [OpenFreeMap](https://openfreemap.org/) con datos de [OpenStreetMap](https://www.openstreetmap.org/copyright).
- Límites de velocidad y tipo de vía: OpenStreetMap vía Overpass API.
- Búsqueda de lugares: Photon (Komoot) y, si hay clave, TomTom. Rutas: OSRM y, si hay clave, TomTom.
- Tráfico: TomTom (requiere clave del usuario con el producto Traffic).

## Librerías
- [Filament](https://github.com/google/filament) y [SceneView](https://github.com/SceneView/sceneview-android) — Apache 2.0.
- [MapLibre Native](https://github.com/maplibre/maplibre-native) — BSD 2-Clause.
- Jetpack Compose y AndroidX — Apache 2.0.

## Modelos añadidos a la web (comprimidos con meshopt y WebP; los de la tabla anterior que se repiten aquí son los mismos autores)

| Archivo (web/assets) | Modelo | Autor y licencia | Enlace |
|---|---|---|---|
| `cars/modelo3.glb` | Tesla Model 3 | David_Holiday (CC BY 4.0) | https://sketchfab.com/3d-models/tesla-model-3-123c10f376ec4f18b93c73afc382808b |
| `cars/tesla_cybertruck.glb` | Tesla Cybertruck low poly | Igor Tretyakov (CC BY 4.0) | https://sketchfab.com/3d-models/tesla-cybertruk-low-poly-e3558b991e75418cb45624fab4d980e5 |
| `cars/tesla_model_y.glb` | 2025 Tesla Model Y | BloxBloger (**CC BY-NC 4.0**: solo uso no comercial) | https://sketchfab.com/3d-models/2025-tesla-model-y-619601e7800d418da5922c4fa7833f74 |
| `traffic/*.glb` | Generic passenger car pack | Comrade1280 (CC BY 4.0) | https://sketchfab.com/3d-models/generic-passenger-car-pack-20f9af9b8a404d5cb022ac6fe87f21f5 |
