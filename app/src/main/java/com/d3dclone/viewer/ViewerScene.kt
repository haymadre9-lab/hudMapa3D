package com.d3dclone.viewer

import android.content.Context
import android.os.SystemClock
import kotlin.math.min
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.LightManager
import com.google.android.filament.View
import io.github.sceneview.Scene
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import com.google.android.filament.MaterialInstance
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val CAR_LEN = 4.4f
private const val WORLD_BACK = 10f
private const val WORLD_LEN = 80f
private const val SEGS = 46
private const val SEG = 2f
private const val BACK = 12f
private const val GRID_STEP = 0.5f
private const val GRID = ((BACK + SEGS * SEG) / GRID_STEP).toInt() + 2
private const val LANE_W = 3.7f
private const val DROPS = 170

private class Drop(val node: CubeNode, var x: Float, var y: Float, var z: Float, val v: Float)

@Composable
fun ViewerScene(s: ViewerState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val envLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val cameraNode = rememberCameraNode(engine)
    val mainLight = rememberMainLightNode(engine) { intensity = 90_000f }
    val nodes = rememberNodes()

    // Environments (day / night), built once.
    val dayEnv = remember(engine) { runCatching { EnvironmentFactory.build(engine, false).let { Environment(it.light, it.skybox) } }.getOrElse { Environment() } }
    val nightEnv = remember(engine) { runCatching { EnvironmentFactory.build(engine, true).let { Environment(it.light, it.skybox) } }.getOrElse { Environment() } }

    // Orbit camera
    val cam = remember { floatArrayOf(0f, 11f, 6.8f) }   // yaw, pitch (deg), distance
    val targetY = 0.7f

    // --- Scenery ---------------------------------------------------------------------------
    val asphalt = remember(engine) { materialLoader.createColorInstance(Color(0xFF2A2D33), 0f, 0.85f, 0.2f) }
    val verge = remember(engine) { materialLoader.createColorInstance(Color(0xFF1D3A24), 0f, 1f, 0.1f) }
    val paintWhite = remember(engine) { materialLoader.createColorInstance(Color(0xFFE8E8E8), 0f, 0.6f, 0.3f) }
    val postMat = remember(engine) { materialLoader.createColorInstance(Color(0xFF808890), 0.6f, 0.4f, 0.5f) }
    val dropMat = remember(engine) { materialLoader.createColorInstance(Color(0xFFB8D4F0), 0f, 0.1f, 0.5f) }
    val snowMat = remember(engine) { materialLoader.createColorInstance(Color(0xFFFFFFFF), 0f, 1f, 0.1f) }
    val amberMat = remember(engine) { materialLoader.createColorInstance(Color(0xFFFFA000), 0f, 0.5f, 0.5f) }

    val grass = remember(engine) {
        CubeNode(engine, Size(400f, 0.02f, 400f), Position(0f, 0f, 0f), verge).apply { position = Position(0f, -0.03f, 0f) }
    }
    // A road piece: carriageway A (our lane + the lane to its left), and for motorways a second
    // carriageway B (oncoming) with a central barrier. Lateral positions are metres from our lane centre.
    class Seg(val roadA: CubeNode, val roadB: CubeNode, val edgeAR: CubeNode, val edgeAL: CubeNode, val edgeBR: CubeNode,
              val edgeBL: CubeNode, val dashA: CubeNode, val dashB: CubeNode, val barrier: CubeNode) {
        val all get() = listOf(roadA, roadB, edgeAR, edgeAL, edgeBR, edgeBL, dashA, dashB, barrier)
    }
    fun tinted(argb: Int) = materialLoader.createColorInstance(Color(argb), 0f, 0.85f, 0.2f)
    val roadMats = remember(engine) { listOf(asphalt, tinted(0xFF625712.toInt()), tinted(0xFF6E3C0F.toInt()), tinted(0xFF701818.toInt())) }
    val segLvl = remember { IntArray(SEGS) }
    val rail = remember(engine) { materialLoader.createColorInstance(Color(0xFF9AA1AA), 0.5f, 0.5f, 0.4f) }
    val segs = remember(engine) {
        fun road() = CubeNode(engine, Size(2f * LANE_W, 0.02f, SEG + 0.08f), Position(0f, -0.01f, 0f), asphalt)
        fun line() = CubeNode(engine, Size(0.14f, 0.012f, SEG + 0.08f), Position(0f, 0f, 0f), paintWhite)
        fun dash() = CubeNode(engine, Size(0.14f, 0.012f, 1.2f), Position(0f, 0f, 0f), paintWhite)
        List(SEGS) {
            Seg(road(), road(), line(), line(), line(), line(), dash(), dash(),
                CubeNode(engine, Size(0.45f, 0.75f, SEG + 0.08f), Position(0f, 0f, 0f), rail))
        }
    }
    val odo = remember { doubleArrayOf(0.0) }          // metres travelled
    val gx = remember { FloatArray(GRID) }; val gz = remember { FloatArray(GRID) }
    val drops = remember(engine) {
        List(DROPS) { i ->
            val snow = i % 2 == 0
            Drop(CubeNode(engine, if (snow) Size(0.045f, 0.045f, 0.045f) else Size(0.012f, 0.38f, 0.012f), Position(0f, 0f, 0f), dropMat),
                (hashf(i, 1) - 0.5f) * 16f, hashf(i, 2) * 7f, -hashf(i, 3) * 26f + 4f, 0.7f + hashf(i, 4))
        }
    }

    // --- Traffic: real models from assets/traffic (+ the Model 3). Lane layout depends on road type. ----
    class Traffic(val pivot: Node, var lat: Float, var d: Float, var vt: Float, val len: Float, val fix: Float,
                  var oncoming: Boolean, var active: Boolean)
    val traffic = remember(engine) { ArrayList<Traffic>() }
    val scenery = remember(engine) { Scenery(engine, modelLoader, ctx.assets) }
    val dyn = remember { floatArrayOf(0f, 0f, 0f, 0f) }       // prevSpeed, decel (km/h/s), park blend, brake hold
    val itemsSeen = remember { intArrayOf(-1) }
    val demoOn = remember { booleanArrayOf(false) }
    val routeSeen = remember { intArrayOf(-1) }
    val voiceVer = remember { intArrayOf(-1) }
    val itemsOdo = remember { doubleArrayOf(0.0) }
    fun part(parent: Node, size: Size, pos: Position, mat: MaterialInstance) =
        CubeNode(engine, size, Position(0f, 0f, 0f), mat).also { it.parent = parent; it.position = pos }
    val kit = remember(engine) { SignKit(engine, materialLoader) }
    val blackMat = remember(engine) { materialLoader.createColorInstance(Color(0xFF15171A), 0.2f, 0.6f, 0.3f) }
    val offRed = remember(engine) { materialLoader.createColorInstance(Color(0xFF4A1414), 0f, 0.6f, 0.2f) }
    val offAmber = remember(engine) { materialLoader.createColorInstance(Color(0xFF4A3A12), 0f, 0.6f, 0.2f) }
    val offGreen = remember(engine) { materialLoader.createColorInstance(Color(0xFF123A22), 0f, 0.6f, 0.2f) }
    val zebra = remember(engine) { materialLoader.createColorInstance(Color(0xFFEDEDED), 0f, 0.7f, 0.2f) }
    class Pool(val pivot: Node, val pole: CubeNode, val plate: CubeNode, val box: CubeNode, val lamps: List<CubeNode>,
               val stripes: List<CubeNode>) { var key = "" }
    val pool = remember(engine) {
        List(10) {
            val pv = Node(engine)
            val pole = part(pv, Size(0.1f, 3f, 0.1f), Position(4.9f, 1.5f, 0f), postMat)
            val plate = part(pv, Size(0.85f, 0.85f, 0.04f), Position(4.9f, 2.9f, 0.07f), paintWhite)
            val box = part(pv, Size(0.36f, 1.05f, 0.3f), Position(4.9f, 3.35f, 0f), blackMat)
            val lamps = listOf(
                part(pv, Size(0.22f, 0.22f, 0.05f), Position(4.9f, 3.7f, 0.17f), offRed),
                part(pv, Size(0.22f, 0.22f, 0.05f), Position(4.9f, 3.35f, 0.17f), offAmber),
                part(pv, Size(0.22f, 0.22f, 0.05f), Position(4.9f, 3.0f, 0.17f), offGreen),
            )
            val stripes = List(8) { i -> part(pv, Size(0.5f, 0.016f, 3.2f), Position(-3.5f + i, 0.01f, 0f), zebra) }
            Pool(pv, pole, plate, box, lamps, stripes)
        }
    }
    class Limit(val pivot: Node, val plate: CubeNode)
    val limitSign = remember(engine) {
        val pv = Node(engine)
        part(pv, Size(0.1f, 2.6f, 0.1f), Position(5.0f, 1.3f, 0f), postMat)
        Limit(pv, part(pv, Size(0.9f, 0.9f, 0.04f), Position(5.0f, 2.6f, 0.07f), paintWhite))
    }
    class Dir(val pivot: Node, val plate: CubeNode)
    val dirSign = remember(engine) {
        val pv = Node(engine)
        part(pv, Size(0.1f, 3.0f, 0.1f), Position(5.4f, 1.5f, 0f), postMat)
        Dir(pv, part(pv, Size(1.9f, 1.0f, 0.05f), Position(5.4f, 3.05f, 0.07f), paintWhite))
    }
    class Gantry(val pivot: Node, val panel: CubeNode)
    val gantry = remember(engine) {
        val pv = Node(engine)
        part(pv, Size(0.35f, 6.2f, 0.35f), Position(-4.3f, 3.1f, 0f), postMat)
        part(pv, Size(0.35f, 6.2f, 0.35f), Position(4.3f, 3.1f, 0f), postMat)
        part(pv, Size(8.9f, 0.4f, 0.4f), Position(0f, 6.1f, 0f), postMat)
        Gantry(pv, part(pv, Size(5.6f, 1.4f, 0.1f), Position(0f, 5.2f, 0.12f), paintWhite))
    }
    val mode = remember { intArrayOf(-1) }       // road type the traffic is currently laid out for

    // --- Lights (headlights, tail lights, signals) -------------------------------------------
    val headL = remember(engine) { LightNode(engine, LightManager.Type.FOCUSED_SPOT) {
        color(1f, 0.95f, 0.8f); intensity(0f); direction(0.05f, -0.12f, -1f); falloff(26f); spotLightCone(0.25f, 0.6f) } }
    val headR = remember(engine) { LightNode(engine, LightManager.Type.FOCUSED_SPOT) {
        color(1f, 0.95f, 0.8f); intensity(0f); direction(-0.05f, -0.12f, -1f); falloff(26f); spotLightCone(0.25f, 0.6f) } }
    val tailL = remember(engine) { LightNode(engine, LightManager.Type.POINT) { color(1f, 0.05f, 0.03f); intensity(0f); falloff(3.5f) } }
    val tailR = remember(engine) { LightNode(engine, LightManager.Type.POINT) { color(1f, 0.05f, 0.03f); intensity(0f); falloff(3.5f) } }
    val sigFL = remember(engine) { LightNode(engine, LightManager.Type.POINT) { color(1f, 0.55f, 0.05f); intensity(0f); falloff(3f) } }
    val sigFR = remember(engine) { LightNode(engine, LightManager.Type.POINT) { color(1f, 0.55f, 0.05f); intensity(0f); falloff(3f) } }
    val sigRL = remember(engine) { LightNode(engine, LightManager.Type.POINT) { color(1f, 0.55f, 0.05f); intensity(0f); falloff(3f) } }
    val sigRR = remember(engine) { LightNode(engine, LightManager.Type.POINT) { color(1f, 0.55f, 0.05f); intensity(0f); falloff(3f) } }
    val lights = listOf(headL, headR, tailL, tailR, sigFL, sigFR, sigRL, sigRR)

    LaunchedEffect(Unit) {
        nodes.add(grass); for (sg in segs) nodes.addAll(sg.all)
        scenery.load(nodes)
        for (p in pool) nodes.add(p.pivot)
        nodes.add(limitSign.pivot); nodes.add(gantry.pivot); nodes.add(dirSign.pivot)
        dirSign.pivot.isVisible = false
        for (p in pool) p.pivot.isVisible = false
        limitSign.pivot.isVisible = false; gantry.pivot.isVisible = false
        // Traffic vehicles: the Model 3 plus every .glb in assets/traffic.
        val files = ArrayList<Triple<String, Float, Float>>()   // path, length (m), yaw fix (deg)
        files.add(Triple("cars/tesla_model3.glb", 4.7f, 0f))
        for (f in (ctx.assets.list("traffic") ?: emptyArray()).filter { it.endsWith(".glb") }.sorted()) {
            val n = f.lowercase()
            val len = when { "semi" in n -> 8.5f; "bus" in n -> 11f; "cyber" in n -> 5.6f; "truck" in n -> 8f; else -> 4.7f }
            val fix = if ("cyber" in n || "semi" in n) 180f else 0f      // these models face +Z
            files.add(Triple("traffic/$f", len, fix))
        }
        val count = 4
        val pick = (0 until count).map { files[(it + 1) % files.size] }
        val perFile = pick.groupingBy { it.first }.eachCount()
        val pools = HashMap<String, ArrayDeque<com.google.android.filament.gltfio.FilamentInstance>>()
        for ((p, c) in perFile) runCatching { modelLoader.createInstancedModel(p, c) }.getOrNull()?.let { pools[p] = ArrayDeque(it) }
        for ((path, len, fix) in pick) {
            val inst = pools[path]?.removeFirstOrNull() ?: continue
            val pivot = fitModel(engine, inst, len, true).first
            pivot.isVisible = false
            traffic.add(Traffic(pivot, 0f, 40f, 0f, len, fix, false, false))
            nodes.add(pivot)
        }
        for (d in drops) nodes.add(d.node)
        nodes.addAll(lights)
        for (d in drops) d.node.isVisible = false
    }

    // --- Car ----------------------------------------------------------------------------------
    val carRig = remember { mutableStateOf<CarRig?>(null) }
    LaunchedEffect(s.carKey, s.reloadTick) {
        carRig.value?.let { nodes.remove(it.node); it.node.destroy() }
        carRig.value = null
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                if (s.carKey == "custom") s.customBytes else ctx.assets.open(s.carKey).use { it.readBytes() }
            }.getOrNull()
        } ?: return@LaunchedEffect
        val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
        val inst = runCatching { modelLoader.createModelInstance(buf) }.getOrNull() ?: return@LaunchedEffect
        val node = ModelNode(modelInstance = inst, scaleToUnits = CAR_LEN)
        val rig = CarRig(engine, node, GlbInfo.parse(bytes))
        nodes.add(node)
        carRig.value = rig
        s.rigInfo = buildString {
            append("ruedas ${rig.wheelCount} ")
            append("puertas ${rig.doorCount} ")
            append(if (rig.hasPaint) "pintura ✓" else "pintura ✗")
        }
    }

    // Paint
    LaunchedEffect(carRig.value, s.paintIndex) {
        val rig = carRig.value ?: return@LaunchedEffect
        ViewerState.PAINTS.getOrNull(s.paintIndex)?.let { rig.paint(it.r, it.g, it.b) }
    }

    // Gyroscope / accelerometer (the "gyro scene" of the original)
    val tilt = remember { floatArrayOf(0f, 0f) }
    DisposableEffect(s.gyro) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val roll = (atan2(x, sqrt(y * y + z * z)) * 180 / PI).toFloat()
                val pitch = (atan2(y, sqrt(x * x + z * z)) * 180 / PI).toFloat()
                tilt[0] += (pitch - tilt[0]) * 0.15f; tilt[1] += (roll - tilt[1]) * 0.15f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (s.gyro && acc != null) sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }

    // View quality switches (SetMSAA, BloomSwitch, ...)
    LaunchedEffect(s.bloom, s.msaa, s.ao, s.night, s.weather, s.shadows) {
        view.bloomOptions = View.BloomOptions().apply { enabled = s.bloom; strength = if (s.night) 0.5f else 0.2f; lensFlare = false; quality = View.QualityLevel.LOW }
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = s.msaa; sampleCount = 4 }
        view.antiAliasing = if (s.msaa) View.AntiAliasing.NONE else View.AntiAliasing.FXAA
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = s.ao }
        view.setShadowingEnabled(s.shadows)
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply { enabled = true; minScale = 0.65f; quality = View.QualityLevel.MEDIUM }
        // Always a light haze: it hides objects appearing at the far end of the road.
        view.fogOptions = View.FogOptions().apply {
            enabled = true
            density = when (s.weather) { Weather.FOG -> 0.09f; Weather.RAIN, Weather.SNOW -> 0.025f; else -> 0.012f }
            distance = when (s.weather) { Weather.FOG -> 4f; Weather.RAIN, Weather.SNOW -> 12f; else -> 40f }
            color = if (s.night) floatArrayOf(0.03f, 0.04f, 0.07f) else floatArrayOf(0.74f, 0.80f, 0.88f)
        }
    }

    val clock = remember { floatArrayOf(0f, 0f, 0f, 0f) }   // phase, wheelAngle, doorOpen, lastTime
    val frames = remember { intArrayOf(0, 0) }
    val lm = engine.lightManager

    fun setLight(n: LightNode, lumens: Float) = lm.setIntensity(lm.getInstance(n.entity), lumens)

    Scene(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                cam[0] -= pan.x * 0.35f
                cam[1] = (cam[1] + pan.y * 0.25f).coerceIn(-5f, 80f)
                cam[2] = (cam[2] / zoom).coerceIn(3.2f, 20f)
                s.lastTouch = SystemClock.uptimeMillis()
            }
        },
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        environmentLoader = envLoader,
        view = view,
        cameraNode = cameraNode,
        cameraManipulator = null,
        childNodes = nodes,
        mainLightNode = mainLight,
        environment = if (s.night) nightEnv else dayEnv,
        isOpaque = true,
        onFrame = { nanos ->
            val now = nanos / 1e9f
            val dt = if (clock[3] == 0f) 0.016f else (now - clock[3]).coerceIn(0f, 0.1f)
            clock[3] = now
            frames[0]++
            if (now - frames[1] >= 1f) { s.fps = frames[0]; frames[0] = 0; frames[1] = now.toInt() }

            if (s.useGps && s.gpsOn && !s.demo) {
                val fresh = SystemClock.elapsedRealtime() - s.gpsStamp < 4000
                val target = if (fresh) s.gpsKmh else 0f
                s.speedKmh += (target - s.speedKmh) * min(1f, dt * 3f)
            }
            val v = s.speedKmh / 3.6f
            // Braking detected from the speed itself (GPS): brake lights + nose dive. Parked look at 0 km/h.
            val dv = (dyn[0] - s.speedKmh) / max(dt, 0.001f)
            dyn[1] += (dv - dyn[1]) * min(1f, dt * 4f)
            dyn[0] = s.speedKmh
            dyn[3] = if (s.speedKmh > 3f && dyn[1] > 6f) 0.5f else max(0f, dyn[3] - dt)
            val brakeOn = s.brake || dyn[3] > 0f
            dyn[2] += ((if (s.speedKmh < 1.5f) 1f else 0f) - dyn[2]) * min(1f, dt * 1.4f)
            val moving = if (s.speedKmh > 3f) 1f else 0f
            val pitchFx = (-min(1f, max(0f, dyn[1]) / 18f) * 2.6f + min(1f, max(0f, -dyn[1]) / 14f) * 1.4f) * moving
            val parkYaw = -8f * dyn[2]; val parkX = 0.55f * dyn[2]
            s.camInit?.let { cam[0] = it[0]; cam[1] = it[1]; cam[2] = it[2]; s.lastTouch = SystemClock.uptimeMillis() + 120000L; s.camInit = null }
            odo[0] += (v * dt).toDouble()
            // Route progress: from the GPS projection, or from the distance driven (no GPS / demo)
            val rt = Course.route
            if (Course.needReset) { Course.needReset = false; Course.s0 = Course.startS; Course.odo0 = odo[0] }
            if (rt != null && Course.pendingVer != routeSeen[0]) {
                routeSeen[0] = Course.pendingVer
                if (Course.pendingS >= 0 && s.gpsOn && !s.demo) { Course.s0 = Course.pendingS; Course.odo0 = odo[0] }
            }
            val sN = Course.sNow(odo[0])
            if (rt != null) {
                s.routeS = sN
                val pm = rt.pose(sN)
                s.mapLat = pm[0]; s.mapLon = pm[1]; s.mapBrg = pm[2].toFloat(); s.mapValid = true
                val tf = rt.nextTraffic(sN)
                if (tf != null) {
                    s.trafficLevel = tf[2].toInt(); s.trafficDist = (Math.max(0.0, tf[0] - sN) / 10.0).toInt() * 10
                    s.trafficLen = ((tf[1] - Math.max(tf[0], sN)) / 10.0).toInt() * 10; s.trafficDelayMin = Math.round(tf[3] / 60.0).toInt()
                } else s.trafficLevel = 0
                val nx = rt.next(sN)
                if (nx != null) { s.navArrow = nx.first.arrow; s.navText = nx.first.label(s.highway); s.navDist = Math.round(nx.second / 10.0).toInt() * 10 }
                else { s.navArrow = ""; s.navText = ""; s.navDist = 0 }
            } else {
                s.trafficLevel = 0
                s.navArrow = ""; s.navText = ""
                if (s.hasFix) { s.mapLat = s.lat; s.mapLon = s.lon; s.mapBrg = s.bearing; s.mapValid = true } else s.mapValid = false
            }
            val kap = kappaFn(odo[0])
            clock[1] = (clock[1] + v / 0.34f * dt * 57.3f) % 360f
            val doorTarget = if (s.doorsOpen) 1f else 0f
            clock[2] += (doorTarget - clock[2]) * (1f - Math.pow(0.001, dt.toDouble()).toFloat())

            // Camera orbit
            if (s.drive) {
                // Follow camera: drifts back behind the car a moment after the finger leaves the screen.
                if (SystemClock.uptimeMillis() - s.lastTouch > 1500) {
                    val dYaw = (((0f - cam[0]) % 360f) + 540f) % 360f - 180f
                    val k = min(1f, dt * 2.5f)
                    cam[0] += dYaw * k
                    cam[1] += (11f - cam[1]) * k
                    cam[2] += ((6.8f + s.speedKmh / 200f * 2.8f) - cam[2]) * k
                }
            } else if (s.autoRotate && s.speedKmh < 1f) cam[0] += 10f * dt
            val yawR = cam[0] * PI.toFloat() / 180f; val pitR = cam[1] * PI.toFloat() / 180f
            val camPos = Position(
                sin(yawR) * cos(pitR) * cam[2],
                targetY + sin(pitR) * cam[2],
                cos(yawR) * cos(pitR) * cam[2],
            )
            cameraNode.position = camPos
            cameraNode.rotation = Rotation(x = -cam[1], y = cam[0], z = 0f)

            // Day / night light
            lm.setIntensity(lm.getInstance(mainLight.entity), (if (s.night) 1_500f else 90_000f) * s.brightness)
            lm.setColor(lm.getInstance(mainLight.entity), if (s.night) 0.55f else 1f, if (s.night) 0.65f else 0.97f, if (s.night) 1f else 0.9f)
            val envScale = (if (s.reflections) 1f else 0.25f) * s.brightness
            (if (s.night) nightEnv else dayEnv).indirectLight?.intensity = (if (s.night) 9_000f else 38_000f) * envScale

            // Road: segments that bend with the (procedural) curvature and stream past the car
            val hw = s.highway
            grass.isVisible = s.road
            val d0 = odo[0]
            val th0 = thetaFn(d0)
            val k0 = (BACK / GRID_STEP).toInt()
            gx[k0] = 0f; gz[k0] = 0f
            for (k in k0 + 1 until GRID) {
                val phi = (thetaFn(d0 + (k - k0 - 0.5f) * GRID_STEP) - th0).toFloat()
                gx[k] = gx[k - 1] + sin(phi) * GRID_STEP; gz[k] = gz[k - 1] - cos(phi) * GRID_STEP
            }
            for (k in k0 - 1 downTo 0) {
                val phi = (thetaFn(d0 + (k - k0 + 0.5f) * GRID_STEP) - th0).toFloat()
                gx[k] = gx[k + 1] - sin(phi) * GRID_STEP; gz[k] = gz[k + 1] + cos(phi) * GRID_STEP
            }
            val baseIdx = Math.floor(d0 / SEG).toLong()
            val frac = (d0 - baseIdx * SEG).toFloat()
            val cA = -LANE_W / 2f            // centre of carriageway A (two lanes), -1.85
            val cB = -14.75f                 // centre of carriageway B on a motorway
            for ((j, sg) in segs.withIndex()) {
                val d = -BACK + j * SEG - frac
                val k = ((d + BACK) / GRID_STEP).toInt().coerceIn(0, GRID - 1)
                val phi = (thetaFn(d0 + d) - th0).toFloat()
                val cp = cos(phi); val sp = sin(phi)
                val yawDeg = -phi * 180f / PI.toFloat()
                val n = baseIdx + j - (BACK / SEG).toInt()
                val rot = Rotation(0f, yawDeg, 0f)
                val dashOn = Math.floorMod(n, 3L) == 0L
                fun place(c: CubeNode, lat: Float, y: Float) { c.position = Position(gx[k] + lat * cp, y, gz[k] + lat * sp); c.rotation = rot }
                val vis = s.road
                sg.roadA.isVisible = vis; sg.edgeAR.isVisible = vis; sg.edgeAL.isVisible = vis; sg.dashA.isVisible = vis && dashOn
                val lv = if (rt != null) rt.trafficLevelAt(sN + d) else 0
                if (segLvl[j] != lv) { segLvl[j] = lv; sg.roadA.materialInstance = roadMats[lv] }
                place(sg.roadA, cA, 0f); place(sg.edgeAR, cA + LANE_W, 0.003f); place(sg.edgeAL, cA - LANE_W, 0.003f); place(sg.dashA, cA, 0.004f)
                val b = vis && hw
                sg.roadB.isVisible = b; sg.edgeBR.isVisible = b; sg.edgeBL.isVisible = b; sg.dashB.isVisible = b && dashOn; sg.barrier.isVisible = b
                if (b) {
                    place(sg.roadB, cB, 0f); place(sg.edgeBR, cB + LANE_W, 0.003f); place(sg.edgeBL, cB - LANE_W, 0.003f)
                    place(sg.dashB, cB, 0.004f); place(sg.barrier, (cA - LANE_W + cB + LANE_W) / 2f, 0.375f)
                }
            }

            // Road pose (x, z, yaw) at distance d ahead and lateral offset lat from our lane centre.
            val dEnd = -BACK + (GRID - 1) * GRID_STEP
            val pose = { d: Float, lat: Float, o: FloatArray ->
                val dd = d.coerceAtMost(dEnd)
                val k = ((dd + BACK) / GRID_STEP).toInt().coerceIn(0, GRID - 1)
                val phi = (thetaFn(d0 + dd) - th0).toFloat()
                val cp = cos(phi); val sp = sin(phi)
                val ext = d - dd
                o[0] = gx[k] + lat * cp + sp * ext
                o[1] = gz[k] + lat * sp - cp * ext
                o[2] = -phi * 180f / PI.toFloat()
            }
            scenery.update(d0, s.road && s.scenery, hw, pose)

            // Traffic: laid out per road type. City: oncoming in the left lane. Motorway: same-direction
            // fast lane on our side + oncoming on the other carriageway.
            if (mode[0] != (if (hw) 1 else 0)) {
                mode[0] = if (hw) 1 else 0
                val spec = if (hw) listOf(
                    floatArrayOf(-LANE_W, -6f, 31f, 0f), floatArrayOf(-LANE_W, 46f, 24f, 0f),
                    floatArrayOf(-12.9f, 55f, -30f, 1f), floatArrayOf(-16.6f, 25f, -27f, 1f),
                ) else listOf(
                    floatArrayOf(-LANE_W, 32f, -14f, 1f), floatArrayOf(-LANE_W, 62f, -17f, 1f),
                    floatArrayOf(-LANE_W, 78f, -13f, 1f), floatArrayOf(0f, 0f, 0f, 2f),
                )
                for ((i, t) in traffic.withIndex()) {
                    val sp = spec.getOrNull(i)
                    t.active = sp != null && sp[3] != 2f
                    if (sp != null) { t.lat = sp[0]; t.d = sp[1]; t.vt = sp[2]; t.oncoming = sp[3] == 1f }
                }
            }
            val po = FloatArray(3)
            for (t in traffic) {
                t.pivot.isVisible = s.traffic && t.active
                if (!t.active) continue
                t.d += (t.vt - v) * dt
                if (t.oncoming) { if (t.d < -20f) t.d = 78f } else { if (t.d > 78f) t.d = -10f else if (t.d < -14f) t.d = 76f }
                pose(t.d, t.lat, po)
                t.pivot.position = Position(po[0], 0f, po[1])
                t.pivot.rotation = Rotation(0f, po[2] + (if (t.oncoming) 180f else 0f) + t.fix, 0f)
            }

            // Road info (OpenStreetMap / TomTom) and what the road ahead holds (stop, yield, lights, crossings, radars)
            val ri = RoadData.road
            s.limit = ri?.limit; s.limitApprox = ri?.approx == true; s.roadName = ri?.ref ?: ri?.name
            if (s.autoRoad) {
                if (rt != null) s.highway = rt.isMotorway(sN + 40.0)
                else if (s.gpsOn && ri != null) s.highway = ri.motorway
            }
            if (s.demo) {
                s.limit = 90; s.limitApprox = false; s.roadName = "A-8"
                if (!demoOn[0] || (odo[0] - itemsOdo[0]) > 520.0) {
                    demoOn[0] = true
                    s.items = listOf(
                        
                        RoadData.Item('s', 55f, 3f, 0, ""), RoadData.Item('c', 120f, 3f, 0, ""), RoadData.Item('p', 185f, 0f, 0, ""),
                        RoadData.Item('t', 250f, 3f, 0, ""), RoadData.Item('r', 330f, 3f, 80, "fijo"),
                    )
                    s.itemsVersion++
                }
            } else demoOn[0] = false
            if (itemsSeen[0] != s.itemsVersion) { itemsSeen[0] = s.itemsVersion; itemsOdo[0] = odo[0] }
            val items = s.items
            val travelled = (odo[0] - itemsOdo[0]).toFloat()
            val cA2 = -LANE_W / 2f
            var alert = ""
            for ((i, p) in pool.withIndex()) {
                val it = items.getOrNull(i)
                val d = if (it != null) it.dist - travelled else -99f
                val show = s.signs && it != null && d > -10f && d < 190f
                p.pivot.isVisible = show
                if (!show || it == null) continue
                val po2 = FloatArray(3); pose(d, cA2, po2)
                p.pivot.position = Position(po2[0], 0f, po2[1]); p.pivot.rotation = Rotation(0f, po2[2], 0f)
                val key = "${it.kind}${it.max}${it.label}"
                if (p.key != key) {
                    p.key = key
                    when (it.kind) {
                        's' -> p.plate.materialInstance = kit.stop()
                        'c' -> p.plate.materialInstance = kit.ceda()
                        'p' -> p.plate.materialInstance = kit.crossing()
                        'r' -> p.plate.materialInstance = kit.radar(it.max, it.label)
                    }
                }
                val tl = it.kind == 't'
                p.plate.isVisible = !tl; p.box.isVisible = tl
                for (l in p.lamps) l.isVisible = tl
                for (z in p.stripes) z.isVisible = it.kind == 'p'
            }
            for (it in items) {
                val d = it.dist - travelled
                if (d <= 0f) continue
                val near = if (it.kind == 'r') 600f else 120f
                if (d < near) {
                    val m = (Math.round(d / 10f) * 10)
                    alert = when (it.kind) {
                        'r' -> "RADAR ${it.label.uppercase()}" + (if (it.max > 0) " ${it.max}" else "") + " · $m m"
                        's' -> "STOP · $m m"; 'c' -> "CEDA EL PASO · $m m"; 't' -> "SEMÁFORO · $m m"; else -> "PASO DE PEATONES · $m m"
                    }
                    break
                }
            }
            var rMax: Int? = null; var rDist = 0; var rKind = ""
            for (it in items) {
                val d = it.dist - travelled
                if (it.kind == 'r' && d > 0f && d < 600f) { rMax = if (it.max > 0) it.max else s.limit; rDist = Math.round(d / 10f) * 10; rKind = it.label; break }
            }
            s.radarMax = rMax; s.radarDist = rDist; s.radarKind = rKind
            if (voiceVer[0] != s.routeVer) { voiceVer[0] = s.routeVer; s.voiceGuide?.reset() }
            s.voiceGuide?.update(s, rt, sN, items, travelled)
            s.alert = alert
            // Direction sign (replaces the stop / crossing plates): blue on motorways, white elsewhere
            val dnx = rt?.next(sN)
            val dd = if (dnx != null) (dnx.second - 70.0).toFloat() else 999f
            dirSign.pivot.isVisible = s.road && s.signs && dnx != null && dd < 230f && dd > -20f
            if (dirSign.pivot.isVisible && dnx != null) {
                dirSign.plate.materialInstance = kit.direction(s.highway, dnx.first.kind, dnx.first.label(s.highway), dnx.first.exitNo)
                val pd = FloatArray(3); pose(dd, cA2, pd)
                dirSign.pivot.position = Position(pd[0], 0f, pd[1]); dirSign.pivot.rotation = Rotation(0f, pd[2], 0f)
            }
            // Roadside limit sign every 450 m, and a gantry every 700 m on motorways
            val lim = s.limit
            val ln = Math.ceil((d0 - 10.0) / 450.0).toLong()
            val ld = (ln * 450.0 - d0).toFloat()
            limitSign.pivot.isVisible = s.road && s.signs && lim != null && ld < 190f
            if (limitSign.pivot.isVisible && lim != null) {
                limitSign.plate.materialInstance = kit.limit(lim, s.limitApprox)
                val pl = FloatArray(3); pose(ld, cA2, pl)
                limitSign.pivot.position = Position(pl[0], 0f, pl[1]); limitSign.pivot.rotation = Rotation(0f, pl[2], 0f)
            }
            val gn = Math.ceil((d0 - 10.0) / 700.0).toLong()
            val gd = (gn * 700.0 - d0).toFloat()
            gantry.pivot.isVisible = s.road && s.signs && hw && gd < 220f
            if (gantry.pivot.isVisible) {
                val gn2 = rt?.next(sN)
                gantry.panel.materialInstance = if (gn2 != null) kit.gantry(gn2.first.kind, gn2.first.label(true), null) else kit.gantry("", s.roadName ?: "AUTOPISTA", lim)
                val pg = FloatArray(3); pose(gd, cA2, pg)
                gantry.pivot.position = Position(pg[0], 0f, pg[1]); gantry.pivot.rotation = Rotation(0f, pg[2], 0f)
            }

            // Weather
            val rain = s.weather == Weather.RAIN; val snow = s.weather == Weather.SNOW
            for ((i, d) in drops.withIndex()) {
                val isSnow = i % 2 == 0
                val active = (rain && !isSnow) || (snow && isSnow)
                d.node.isVisible = active
                if (!active) continue
                d.y -= d.v * (if (isSnow) 1.4f else 9f) * dt
                d.z += v * dt
                if (d.y < 0f) d.y += 7f
                if (d.z > 6f) d.z -= 30f
                if (d.z < -26f) d.z += 30f
                d.node.position = Position(d.x, d.y, d.z)
                if (isSnow) d.node.rotation = Rotation(0f, now * 40f * d.v, 0f)
            }

            // Car + rig
            val rig = carRig.value
            if (rig != null) {
                val yaw = if (s.flipFront) 180f else 0f
                val gp = if (s.gyro) tilt[0] else 0f
                val gr = if (s.gyro) -tilt[1] else 0f
                val steer = (-kap * 450f).toFloat().coerceIn(-9f, 9f)
                val lean = (kap * v * 25f).toFloat().coerceIn(-4f, 4f)
                val yawTotal = yaw + steer + parkYaw
                rig.node.rotation = Rotation(gp * 0.5f + pitchFx, yawTotal, gr * 0.5f + lean)
                run {
                    val bb = rig.node.modelInstance.asset.boundingBox
                    val sc = rig.node.scale.x
                    val ox = -bb.center[0] * sc; val oz = -bb.center[2] * sc
                    val yr = yawTotal * PI.toFloat() / 180f
                    rig.node.position = Position(ox * cos(yr) + oz * sin(yr) + parkX, -(bb.center[1] - bb.halfExtent[1]) * sc, -ox * sin(yr) + oz * cos(yr))
                }
                s.pitchDeg = tilt[0]; s.rollDeg = tilt[1]
                rig.spinWheels(if (s.flipFront) clock[1] else -clock[1])
                rig.setDoors(clock[2])
                val bounds = rig.node.modelInstance.asset.boundingBox.halfExtent
                val half = max(bounds[0], bounds[2]); val scale = CAR_LEN / (2f * max(half, 0.001f))
                val hw = (if (bounds[2] >= bounds[0]) bounds[0] else bounds[2]) * scale * 0.7f
                val hl = CAR_LEN / 2f
                val fz = -(hl - 0.05f)
                val rz = -fz
                headL.position = Position(-hw, 0.62f, fz); headR.position = Position(hw, 0.62f, fz)
                tailL.position = Position(-hw, 0.72f, rz); tailR.position = Position(hw, 0.72f, rz)
                sigFL.position = Position(-hw, 0.6f, fz); sigFR.position = Position(hw, 0.6f, fz)
                sigRL.position = Position(-hw, 0.7f, rz); sigRR.position = Position(hw, 0.7f, rz)
                val dir = -1f
                lm.setDirection(lm.getInstance(headL.entity), 0.05f, -0.12f, dir)
                lm.setDirection(lm.getInstance(headR.entity), -0.05f, -0.12f, dir)
            }
            val blink = (now * 1.6f).toInt() % 2 == 0
            val night = if (s.night) 1f else 0.4f
            setLight(headL, if (s.headlights) 60_000f * night else 0f)
            setLight(headR, if (s.headlights) 60_000f * night else 0f)
            val tail = (if (brakeOn) 6_000f else 0f) + (if (s.headlights) 1_200f else 0f)
            setLight(tailL, tail); setLight(tailR, tail)
            val left = (s.leftSignal || s.hazard) && blink
            val right = (s.rightSignal || s.hazard) && blink
            val sig = 9_000f * night
            setLight(sigFL, if (left) sig else 0f); setLight(sigRL, if (left) sig else 0f)
            setLight(sigFR, if (right) sig else 0f); setLight(sigRR, if (right) sig else 0f)
            carRig.value?.lamps(s.headlights, s.headlights || brakeOn, brakeOn, left, right)
        },
    )

    // Camera reset button
    LaunchedEffect(s.resetCamera) {
        if (s.resetCamera > 0) { cam[0] = 0f; cam[1] = 11f; cam[2] = 6.8f; s.lastTouch = 0L }
    }
}

private fun hashf(i: Int, k: Int): Float {
    var h = i * 73856093 xor k * 19349663
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xffff) / 65535f
}

private fun kappa(d: Double) = 0.012 * sin(d / 90.0) + 0.008 * sin(d / 37.0 + 1.0)
private fun theta(d: Double) = -0.012 * 90.0 * cos(d / 90.0) - 0.008 * 37.0 * cos(d / 37.0 + 1.0)

private fun thetaFn(x: Double) = if (Course.route != null) Course.theta(x) else theta(x)
private fun kappaFn(x: Double) = if (Course.route != null) (Course.theta(x + 6.0) - Course.theta(x - 6.0)) / 12.0 else kappa(x)
