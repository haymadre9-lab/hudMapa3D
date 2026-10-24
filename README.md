# D3D Clone

Visor 3D de conducción para Android: tu coche en una carretera 3D que se mueve con la velocidad del GPS, sigue la ruta
de un mapa y muestra señales, radares, tráfico y avisos por voz.

Es una implementación **original** (Kotlin + Jetpack Compose + [Filament](https://github.com/google/filament) vía
[SceneView](https://github.com/SceneView/sceneview-android)). Está inspirada en el estudio funcional de una app comercial
de visualización 3D (ver [`docs/ANALISIS_D3DVIEW.md`](docs/ANALISIS_D3DVIEW.md)), pero **no reutiliza su código, binarios ni recursos**.
No está afiliada a ella.

## Qué hace

- **Escena 3D:** coche con ruedas que giran, puertas, luces (faros, freno, intermitentes, emergencia), pintura, día/noche, lluvia/nieve/niebla,
  iluminación generada por código, bloom/MSAA/AO/sombras opcionales y cámara que sigue al coche.
- **Carretera procedural:** ciudad (2 carriles) o autopista (2+2 con barrera), curvas, farolas, árboles, edificios, tráfico real (modelos `.glb`).
- **GPS:** velocidad real, freno detectado por deceleración, coche «aparcado» a 0 km/h.
- **Ruta:** buscador con lupa (recientes, favoritos, casa, autocompletado), ruta con TomTom u OSRM, **la carretera 3D gira como la ruta**,
  minimapa traslúcido (MapLibre), recálculo automático al salirte.
- **Carteles:** de dirección (blanco/negro en carretera, azul/blanco en autopista), límite de velocidad, pórticos de autopista,
  stop, ceda, pasos de peatones, semáforos y radares con aviso.
- **Tráfico:** tramos coloreados (amarillo/naranja/rojo) en mapa, asfalto y HUD, con opción de ruta alternativa.
- **Voz:** maniobras, radares y tráfico con la voz del sistema (no anuncia stop, ceda ni semáforos).
- **Cambio automático** carretera ↔ autopista según la ruta y el GPS.

## Versión web (GitHub Pages)

En `web/` hay una versión web instalable (PWA) hecha con [three.js](https://threejs.org) y [MapLibre GL JS](https://maplibre.org), sin pasos de compilación.

**Fase 1:** escena 3D con el Tesla Model 3 (comprimido: 22 MB -> 2,2 MB), carretera con curvas, farolas y árboles, día/noche, faros, emergencia,
frenado detectado por la velocidad, pintura, **velocidad por GPS** del navegador (o control manual), pantalla encendida y uso sin conexión.

**Fase 2:** minimapa traslúcido (OpenFreeMap), buscador con lupa (recientes, favoritos, casa arrastrando hacia abajo, autocompletado con Photon o TomTom),
rutas con OSRM o TomTom, **la carretera 3D gira como la ruta**, cartel de maniobra (azul en autopista, blanco en carretera),
recálculo al salirte, y botón para **parar la ruta** y volver a conducir libre. La clave de TomTom se guarda solo en el navegador (⚙️).

**Fase 3:** carteles de dirección en la carretera (flecha en codo, rotonda con salidas, salida de autopista con número; blancos en carretera y azules en autopista),
límite de velocidad de la vía (OpenStreetMap) en el HUD y en carteles, anillo del velocímetro en rojo si lo superas,
**tráfico** en el mapa, en el asfalto 3D y en un aviso con ruta alternativa (TomTom, con tu clave: rutas con tráfico y alternativas, o flujo de tráfico si la ruta viene del servicio libre),
y **avisos por voz** (maniobras y tráfico) con la síntesis de voz del navegador.

**Fase 4:** autopista 2+2 con barrera central (automática por la ruta y el GPS, o a mano con 🛣️), edificios (modelos CC BY comprimidos) y puertas del coche que se abren (🚪).

Falta por portar: radares y señales de stop/ceda/semáforos (sus datos no se publican) y el tráfico de otros coches.

Probar en local (la dirección admite `?lat=..&lon=..` para simular la posición y `?test=1` para la ruta de prueba):

```bash
npx serve web
```

Publicar en GitHub Pages: sube el repositorio (público para el plan gratuito) y en **Settings -> Pages -> Source** elige **GitHub Actions**.
El flujo `.github/workflows/pages.yml` publica la carpeta `web/` en cada cambio sobre `main`. La web usa HTTPS, necesario para el GPS.

Los datos de señales y radares **no** forman parte de la web ni deben ir en un repositorio público (ver [`CREDITS.md`](CREDITS.md)).

## Compilar

Requisitos: Android Studio (o JDK 17+ y Android SDK con plataforma 35). `minSdk 24`, necesita OpenGL ES 3.0.

```bash
git clone <este-repo>
cd D3DClone
# Crea local.properties con la ruta de tu SDK (no se sube al repositorio):
echo "sdk.dir=/ruta/al/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

Los modelos `.glb` ya están en `app/src/main/assets/`. Para añadir más, copia el archivo a la carpeta que toque
(`cars/`, `traffic/`, `scene/buildings|lamps|trees|landmarks|structures`) y recompila: se cargan solos.

## Claves y privacidad

- **TomTom (opcional):** se escribe en la app (menú ☰ → «TomTom») y se guarda **solo en el móvil**. No hay ninguna clave en el código.
  Necesita los productos **Search**, **Routing** y/o **Traffic** activados en el panel de TomTom; sin ellos la app usa servicios libres
  (Photon + OSRM) y el tráfico por tramos solo funciona con **Traffic**.
- La app pide **ubicación** (velocidad, ruta, radares) y usa **internet** (mapas, rutas, límites de velocidad, tráfico).
  No envía datos a ningún servidor propio.

## Estructura

```
app/src/main/java/com/d3dclone/viewer/
  MainActivity.kt     HUD, menú, buscador, GPS, rutas
  ViewerScene.kt      escena Filament: carretera, tráfico, luces, señales, cámara
  Scenery.kt          entorno (edificios, árboles, farolas) colocado a lo largo de la carretera
  CarRig.kt           ruedas, puertas, pintura y luces por nombre de nodo/material
  Nav.kt              rutas (TomTom/OSRM), pasos, tráfico y «Course» (ruta -> curvatura de la carretera)
  MiniMap.kt          minimapa MapLibre
  SignKit.kt          carteles dibujados en tiempo de ejecución
  RoadData.kt         radares/señales y límite de velocidad (OpenStreetMap)
  VoiceGuide.kt       avisos por voz
  PlaceBook.kt        favoritos, recientes y casa
  EnvironmentFactory.kt  iluminación por imagen (día/noche) generada por código
```

## Pruebas sin salir a la calle

Menú ☰ → «Demostración de señales» y «Ruta de prueba». Desde `adb` también se puede arrancar en modo prueba:

```bash
adb shell am start -n com.d3dclone.viewer/.MainActivity --ez demo true --ef speed 50 --ez testroute true --ed lat 43.263 --ed lon -2.935
```

Extras: `demo`, `speed`, `testroute`, `lat`, `lon`, `s0` (punto de inicio de la ruta, m), `highway`, `nomap`, `yaw`/`pitch`/`dist` (cámara).

## Estado

Probado en un móvil con Android 16 (Adreno 732, ~60 fps con sombras). Sin probar en conducción real: el seguimiento por GPS,
el recálculo, el tráfico con tu clave y la voz. Los modelos de la ruta de prueba y las señales son demostrativos.

## Licencias

- Código: **por definir** (añade un `LICENSE` antes de publicar).
- Modelos 3D: CC BY 4.0 con atribución obligatoria — ver [`CREDITS.md`](CREDITS.md).
- Datos de señales y radares (`assets/data/*.json`): según el autor proceden de TomTom y no deben redistribuirse públicamente — ver [`CREDITS.md`](CREDITS.md) antes de publicar.
