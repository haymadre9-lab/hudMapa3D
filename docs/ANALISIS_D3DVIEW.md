# Análisis de D3Dview V3.1.15.70 (hecho con REA 6.7.0 + JADX 0.7.1)

Paquete `cn.cardoor.d3dview`, minSdk 21 / targetSdk 27.

## Lo que REA pudo ver
- **El código Java está empaquetado con 360 Jiagu** (`com.stub.StubApp`, `assets/libjiagu*.so`). JADX solo ve 5 clases;
  el resto del DEX va cifrado y se descifra en ejecución. **No se intentó desempaquetarlo.**
- **Motor 3D propio en C++/OpenGL ES** (`lib/armeabi-v7a/libnative-lib.so`, 6 MB), no Filament. Usa libktx (cubemaps KTX),
  un cargador glTF/GLB, y sus propios shaders GLSL embebidos.
- **Pipeline** (strings de shaders): PBR con IBL (irradiance + prefiltered radiance + brdfLUT), clearcoat, emisivo,
  bloom (down/upsample), TAA con reproyección YCoCg, tonemap, dithering anti-banding, niebla, desenfoque por distancia,
  reflejo plano (`ReflectView/ReflectProjection`), máscara de bordes redondeados, MSAA.
- **API JNI** (`cn.cardoor.auto3dpro.D3D`, ~150 métodos): SetCarModelPath, SetNightDayStatus, SetWeatherType, SetSpeed,
  SetCarDoorStatus, SetHeadlightStatus, Set{Left,Right}SignalLightStatus, SetDoubleFlashStatus, SetCarCoatMode/Path,
  SetTirePressureData, SetCameraFollowState, ChangeCameraDistance, BloomSwitch, ReflectSwitch, SetMSAA, SetBrightness,
  escena ADAS (coches/peatones/camiones low-poly, carriles), modo giroscopio, editor de matrícula, etc.
- **Convención de nodos del modelo**: `wheel_{left,right}_{front,back}`, `door_*`, `headlight_*`, `turn_signal_*`,
  `brake_light`, `body`, `shadow`, `window_*`, material `car_cover`, `license_plate`.
- **Assets**: IBL día/noche (`.ktx`), clima (lluvia, nieve, niebla, rayo, granizo, arena), `gyro*.glb`, `tirePressure`, ADAS.

## Qué reproduce D3DClone (código propio, sin reutilizar nada del APK)
Filament (SceneView) en lugar del motor propio: IBL procedural día/noche, bloom, MSAA/FXAA, AO, niebla, clima por partículas,
carretera con velocidad, ruedas que giran y puertas por nombre de nodo, faros/freno/intermitentes/emergencia como luces reales,
pintura, presión de neumáticos, modo giroscopio (acelerómetro), cámara orbital y modelos propios (.glb importables).

## No reproducido
ADAS/vehículos vecinos, bridge de datos del coche (`BridgeService`), tienda/cuentas, editor de matrícula, rayos/granizo,
reflejo plano, TAA propio.

## Notas de ajuste con el Tesla Model 3 (probado en dispositivo)
- Ruedas: `wheels` / `wheels.001` son pares (delanteras / traseras) en un solo nodo; se giran sobre el centro del eje.
  `hub_*` son pinzas de freno y no giran.
- Puertas: bisagra en el borde delantero, apertura 35°.
- Luces: se encienden por nombre de material (`*_front_light`, `indicator_*`, `breaklight_*`, `*_rear_light`) con emisión +
  focos reales de noche. Los intermitentes traseros del modelo quedan ocultos dentro del piloto.
- Orientación: frente nativo = -Z; cámara detrás; "Invertir frente" gira el coche e invierte la carretera.
