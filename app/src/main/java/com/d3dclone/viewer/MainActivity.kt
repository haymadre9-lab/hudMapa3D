package com.d3dclone.viewer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import kotlin.concurrent.thread
import android.os.Looper
import android.os.SystemClock
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private companion object {
        const val OFF_ROUTE_M = 50.0          // metres from the route that count as "off"
        const val OFF_ROUTE_FIXES = 4         // consecutive GPS fixes (about 2 s) before recalculating
        const val REROUTE_COOLDOWN_MS = 20_000L
    }

    private val state = ViewerState()
    private var locMgr: LocationManager? = null
    private var lastRefresh = 0L

    fun switchToAlternative(alt: Route) {
        Course.startS = 0.0
        state.alts = emptyList(); state.curTravelSec = alt.travelSec
        Course.set(alt); state.routeVer++
        state.routeStatus = "Ruta alternativa · " + String.format("%.1f", alt.total / 1000.0) + " km"
    }

    /** Every few minutes: ask TomTom again. Same road -> take the fresh traffic colours; a faster different road -> offer it. */
    private fun maybeRefreshTraffic(lat: Double, lon: Double) {
        val dest = state.destPlace; val cur = Course.route
        if (cur == null || dest == null || state.demo || state.tomtomKey.isBlank() || state.rerouting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefresh < 240_000L) return
        lastRefresh = now
        if (cur.viaFlow) {
            thread { val tr = Router.flowTraffic(cur, state.routeS, state.tomtomKey); if (Course.route === cur) { cur.traffic = tr; state.trafficVer++ } }
            return
        }
        thread {
            val rs = Router.routes(lat, lon, dest, state.tomtomKey)
            if (rs.isEmpty() || Course.route !== cur) return@thread
            val same = rs.firstOrNull { it.similarTo(cur) }
            if (same != null) {
                Course.startS = 0.0
                Course.set(same); state.routeVer++
                state.curTravelSec = same.travelSec
                state.alts = rs.filter { it !== same && it.travelSec < same.travelSec - 60 }
            } else {
                val left = (cur.travelSec * (1.0 - Course.sNow(0.0).coerceAtLeast(0.0) / cur.total)).toInt()
                state.alts = rs.filter { it.travelSec < left - 60 }
            }
        }
    }

    private var offCount = 0
    private var lastReroute = 0L

    /** The GPS says we left the route: calculate a new one to the same destination from here. */
    private fun maybeReroute(lat: Double, lon: Double) {
        val dest = state.destPlace
        if (Course.route == null || dest == null || state.demo || state.rerouting) { offCount = 0; return }
        offCount = if (Course.offRoute > OFF_ROUTE_M) offCount + 1 else 0
        val now = SystemClock.elapsedRealtime()
        if (offCount < OFF_ROUTE_FIXES || now - lastReroute < REROUTE_COOLDOWN_MS) return
        offCount = 0; lastReroute = now
        state.rerouting = true
        thread {
            val rs = Router.routes(lat, lon, dest, state.tomtomKey)
            val r = rs.firstOrNull()
            if (r != null && state.destPlace === dest) {
                Course.startS = 0.0
                state.alts = rs.drop(1); state.curTravelSec = r.travelSec
                Course.set(r); state.routeVer++
                addFlowTraffic(r)
                state.routeStatus = "Ruta recalculada a " + dest.name.take(30) + " · " + String.format("%.1f", r.total / 1000.0) + " km"
            } else if (r == null) state.routeStatus = "No se pudo recalcular la ruta"
            state.rerouting = false
        }
    }

    private val gpsListener = LocationListener { loc ->
        state.gpsKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        state.gpsStamp = SystemClock.elapsedRealtime()
        if (loc.hasBearing() && loc.speed > 1.5f) state.bearing = loc.bearing
        state.lat = loc.latitude; state.lon = loc.longitude; state.hasFix = true
        Course.onFix(loc.latitude, loc.longitude)
        if (loc.speed > 2f) maybeReroute(loc.latitude, loc.longitude)
        maybeRefreshTraffic(loc.latitude, loc.longitude)
        if (!state.demo) { state.items = RoadData.ahead(loc.latitude, loc.longitude, state.bearing); state.itemsVersion++ }
        RoadData.requestRoad(loc.latitude, loc.longitude, state.tomtomKey)
    }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startGps() }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locMgr = lm
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, gpsListener, Looper.getMainLooper())
            state.gpsOn = true
        }
    }

    /** Where a route starts: the GPS position, the last known one, or (tests) the demo coordinates. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun origin(): DoubleArray? {
        if (state.hasFix) return doubleArrayOf(state.lat, state.lon)
        if (state.demoLat != 0.0) return doubleArrayOf(state.demoLat, state.demoLon)
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER))
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { return doubleArrayOf(it.latitude, it.longitude) }
        }
        return null
    }

    private fun doSearch() {
        val q = state.dest.trim(); if (q.isEmpty()) return
        state.routeStatus = "Buscando…"
        val o = origin()
        thread {
            val res = Router.search(q, o?.get(0), o?.get(1), state.tomtomKey)
            state.places = res
            state.routeStatus = if (res.isEmpty()) "Sin resultados (¿hay internet?)" else "Elige un destino"
        }
    }

    private fun startRoute(p: Router.Place) {
        val o = origin()
        if (o == null) { state.routeStatus = "Sin posición GPS todavía"; return }
        state.routeStatus = "Calculando ruta…"; state.places = emptyList(); state.destPlace = p
        PlaceBook.addRecent(p)
        thread {
            val rs = Router.routes(o[0], o[1], p, state.tomtomKey)
            val r = rs.firstOrNull()
            if (r == null) { state.routeStatus = "No se pudo calcular la ruta"; return@thread }
            state.alts = rs.drop(1); state.curTravelSec = r.travelSec
            Course.set(r); state.routeVer++
            state.routeStatus = "Ruta a " + p.name.take(30) + " · " + String.format("%.1f", r.total / 1000.0) + " km"
            addFlowTraffic(r)
        }
    }

    /** Drag-down shortcut: a route to the saved home address. Returns false when none is saved. */
    private fun goHome(): Boolean { val h = PlaceBook.home ?: return false; startRoute(h); return true }

    /** Route from a free service: colour it with TomTom's traffic flow (needs a key with the Traffic product). */
    private fun addFlowTraffic(r: Route) {
        if (state.tomtomKey.isBlank() || r.travelSec > 0 || Course.route !== r) return
        r.viaFlow = true
        r.traffic = Router.flowTraffic(r, 0.0, state.tomtomKey)
        state.trafficVer++
    }

    private fun startTestRoute() {
        val o = origin() ?: doubleArrayOf(43.2630, -2.9350)
        val r = Router.testRoute(o[0], o[1])
        state.destPlace = null
        state.alts = listOf(Router.testAlternative(r)); state.curTravelSec = r.travelSec
        Course.set(r); state.routeVer++
        state.routeStatus = "Ruta de prueba · " + String.format("%.1f", r.total / 1000.0) + " km"
    }

    private fun clearRoute() { state.alts = emptyList(); state.destPlace = null; Course.set(null); state.routeVer++; state.routeStatus = "" }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps()
        else askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onDestroy() {
        state.voiceGuide?.shutdown(); state.voiceGuide = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        locMgr?.removeUpdates(gpsListener); state.gpsOn = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RoadData.load(applicationContext)
        PlaceBook.load(applicationContext)
        Router.noteSink = { state.keyNote = it }
        state.voiceGuide = VoiceGuide(this)
        if (intent.hasExtra("lat")) { state.demoLat = intent.getDoubleExtra("lat", 0.0); state.demoLon = intent.getDoubleExtra("lon", 0.0) }
        if (intent.getBooleanExtra("nomap", false)) state.showMap = false
        if (intent.hasExtra("s0")) Course.startS = intent.getDoubleExtra("s0", 0.0)
        if (intent.getBooleanExtra("highway", false)) { state.highway = true; state.autoRoad = false }
        if (intent.getBooleanExtra("demo", false)) { state.demo = true; state.speedKmh = intent.getFloatExtra("speed", 50f); state.autoRotate = false
            if (intent.hasExtra("yaw")) state.camInit = floatArrayOf(intent.getFloatExtra("yaw", 0f), intent.getFloatExtra("pitch", 11f), intent.getFloatExtra("dist", 7f)) }
        state.tomtomKey = getSharedPreferences("d3d", MODE_PRIVATE).getString("tomtom", "") ?: ""
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (intent.getBooleanExtra("testroute", false)) window.decorView.post { startTestRoute() }
        intent.getStringExtra("dest")?.let { d ->
            state.dest = d
            thread {
                val o = origin()
                val res = Router.search(d, o?.get(0), o?.get(1), state.tomtomKey)
                state.places = res
                if (res.isNotEmpty()) startRoute(res[0]) else state.routeStatus = "Sin resultados"
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Root(state)
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Root(s: ViewerState) {
        var panel by remember { mutableStateOf(false) }
        var searchOpen by remember { mutableStateOf(false) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@rememberLauncherForActivityResult
            s.customBytes = bytes
            s.customName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else "modelo.glb"
            } ?: "modelo.glb"
            s.carKey = "custom"; s.reloadTick++
        }
        val cars = remember { (assets.list("cars") ?: emptyArray()).sorted() }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ViewerScene(s, Modifier.fillMaxSize())

            // HUD
            Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                Text("D3D CLONE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${s.fps} fps · ${s.speedKmh.toInt()} km/h · ${if (s.night) "noche" else "día"}", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                Text(s.rigInfo, color = Color(0x99FFFFFF), fontSize = 11.sp)
                if (s.gyro) Text("cabeceo %.1f° · alabeo %.1f°".format(s.pitchDeg, s.rollDeg), color = Color(0xFF7CE0FF), fontSize = 12.sp)
            }
            OverspeedHalo(s)
            val landscape = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
            if (s.showMap) MiniMap(s, Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 64.dp)
                .size(width = if (landscape) 230.dp else 150.dp, height = if (landscape) 270.dp else 330.dp))
            Speedometer(s, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp))
            val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
            RadarSign(s, if (narrow) Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 40.dp) else Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            TrafficCard(s, { a -> switchToAlternative(a) }, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 110.dp))
            ManeuverCard(s, Modifier.align(Alignment.TopCenter).padding(top = 8.dp).widthIn(max = if (landscape) 340.dp else 185.dp))
            if (s.tires) TirePressureCard(s, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 180.dp))

            // Panel toggle
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(40.dp).clip(CircleShape)
                    .background(Color(0x99000000)).clickable { panel = !panel },
                contentAlignment = Alignment.Center,
            ) { Text(if (panel) "✕" else "☰", color = Color.White, fontSize = 18.sp) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 56.dp).size(40.dp).clip(CircleShape)
                    .background(Color(0x99000000)).clickable { searchOpen = true; panel = false },
                contentAlignment = Alignment.Center,
            ) { Text("🔍", fontSize = 17.sp) }
            if (searchOpen) SearchBar(s, onPick = { p -> startRoute(p); searchOpen = false }, onHome = { goHome().also { ok -> if (ok) searchOpen = false } },
                onClose = { searchOpen = false }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 12.dp, end = 12.dp).fillMaxWidth())

            if (panel) {
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(top = 56.dp, end = 10.dp, bottom = 10.dp)
                        .fillMaxWidth(0.62f).widthIn(max = 300.dp).fillMaxHeight(0.8f)
                        .clip(RoundedCornerShape(18.dp)).background(Color(0xCC11141A))
                        .verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Section("Coche")
                    cars.forEach { f ->
                        val key = "cars/$f"
                        Chip(f.removeSuffix(".glb").replace('_', ' '), s.carKey == key) { s.carKey = key; s.paintIndex = 0 }
                    }
                    s.customName?.let { Chip("📂 $it", s.carKey == "custom") { s.carKey = "custom"; s.reloadTick++ } }
                    TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Importar .glb…") }

                    Section("Pintura")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ViewerState.PAINTS.forEachIndexed { i, p ->
                            val c = if (p == null) Color.DarkGray else Color(p.r.coerceAtMost(1f).pow22(), p.g.pow22(), p.b.pow22())
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(c)
                                    .border(if (s.paintIndex == i) 2.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { s.paintIndex = i; if (i == 0) s.reloadTick++ },
                            )
                        }
                    }

                    Section("Escena")
                    Toggle("Noche", s.night) { s.night = it }
                    Text("Clima", color = Color.LightGray, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Weather.entries.forEach { w -> Chip(w.label, s.weather == w) { s.weather = w } }
                    }
                    Toggle("Carretera", s.road) { s.road = it }
                    Toggle("Tipo de vía automático (GPS)", s.autoRoad) { s.autoRoad = it }
                    Toggle("Autopista (2+2 carriles)", s.highway) { s.highway = it; if (it) s.autoRoad = false }
                    Toggle("Carteles, radares y señales", s.signs) { s.signs = it }
                    Toggle("Avisos por voz" + (if (s.voiceGuide?.ready == false) " (sin voz del sistema)" else ""), s.voice) { s.voice = it }
                    Toggle("Demostración de señales (sin GPS)", s.demo) { s.demo = it; if (it && s.speedKmh < 5f) s.speedKmh = 50f }
                    Toggle("Tráfico", s.traffic) { s.traffic = it }
                    Toggle("Entorno (edificios, árboles…)", s.scenery) { s.scenery = it }
                    Toggle("Cámara de seguimiento", s.drive) { s.drive = it }
                    Toggle("Girar solo", s.autoRotate) { s.autoRotate = it }
                    Text("Velocidad: ${s.speedKmh.toInt()} km/h", color = Color.LightGray, fontSize = 12.sp)
                    Toggle("Velocidad por GPS" + (if (s.useGps && !s.gpsOn) " (sin señal)" else ""), s.useGps) { s.useGps = it }
                    if (!(s.useGps && s.gpsOn && !s.demo)) Slider(s.speedKmh, { s.speedKmh = it }, valueRange = 0f..200f)

                    Section("Luces y puertas")
                    Toggle("Faros", s.headlights) { s.headlights = it }
                    Toggle("Freno", s.brake) { s.brake = it }
                    Toggle("Intermitente izq.", s.leftSignal) { s.leftSignal = it; if (it) s.rightSignal = false }
                    Toggle("Intermitente der.", s.rightSignal) { s.rightSignal = it; if (it) s.leftSignal = false }
                    Toggle("Emergencia", s.hazard) { s.hazard = it }
                    Toggle("Abrir puertas", s.doorsOpen) { s.doorsOpen = it }

                    Section("Ruta")
                    TextButton(onClick = { startTestRoute() }) { Text("Ruta de prueba") }
                    if (s.routeStatus.isNotEmpty()) Text(s.routeStatus, color = Color(0xFF7CE0FF), fontSize = 12.sp)
                    s.alts.forEach { a -> Chip("Alternativa: " + (a.travelSec / 60) + " min · " + String.format("%.1f", a.total / 1000.0) + " km", false) { switchToAlternative(a) } }
                    if (Course.route != null) TextButton(onClick = { clearRoute() }) { Text("Cancelar ruta") }

                    Section("TomTom (opcional)")
                    OutlinedTextField(value = s.tomtomKey, onValueChange = {
                        s.tomtomKey = it.trim()
                        getSharedPreferences("d3d", MODE_PRIVATE).edit().putString("tomtom", s.tomtomKey).apply()
                    }, singleLine = true, label = { Text("Clave API", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                    Text("Se usa solo cuando OpenStreetMap no indica el límite de la vía.", color = Color.Gray, fontSize = 11.sp)
                    if (s.keyNote.isNotEmpty()) Text(s.keyNote, color = Color(0xFFFFB74D), fontSize = 11.sp)

                    Section("Calidad")
                    Toggle("Bloom", s.bloom) { s.bloom = it }
                    Toggle("MSAA 4x", s.msaa) { s.msaa = it }
                    Toggle("Oclusión ambiental (pesado)", s.ao) { s.ao = it }
                    Toggle("Sombras (pesado)", s.shadows) { s.shadows = it }
                    Toggle("Reflejos", s.reflections) { s.reflections = it }
                    Text("Brillo", color = Color.LightGray, fontSize = 12.sp)
                    Slider(s.brightness, { s.brightness = it }, valueRange = 0.3f..1.8f)

                    Section("Extras")
                    Toggle("Presión de neumáticos", s.tires) { s.tires = it }
                    Toggle("Giroscopio (inclinar móvil)", s.gyro) { s.gyro = it }
                    Toggle("Invertir frente del coche", s.flipFront) { s.flipFront = it }
                    TextButton(onClick = { s.resetCamera++ }) { Text("Reiniciar cámara") }
                }
            }
        }
    }
}

private fun Float.pow22(): Float = Math.pow(this.toDouble(), 1 / 2.2).toFloat().coerceIn(0f, 1f)

@Composable
private fun Section(t: String) = Text(t.uppercase(), color = Color(0xFF7CE0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(on, set)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp) })
}

@Composable
private fun TirePressureCard(s: ViewerState, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xAA000000)).padding(12.dp)) {
        Text("PRESIÓN (bar)", color = Color(0xFF7CE0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val names = listOf("Del. izq.", "Del. der.", "Tras. izq.", "Tras. der.")
        for (i in 0..3) {
            val p = s.tirePressure[i]
            val c = if (p < 2.2f) Color(0xFFFF5252) else Color.White
            Text("${names[i]}  %.1f".format(p), color = c, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Speedometer(s: ViewerState, modifier: Modifier) {
    val speed = s.speedKmh
    val lim = s.limit
    val over = lim != null && speed > lim + 3
    val accent = if (over) Color(0xFFFF5252) else Color(0xFF7CE0FF)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                val tl = Offset(inset, inset); val sz = GSize(size.width - stroke, size.height - stroke)
                drawArc(Color(0x66FFFFFF), 150f, 240f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = 240f * (speed / 240f).coerceIn(0f, 1f)
                drawArc(accent, 150f, sweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                if (lim != null) {
                    val a = Math.toRadians((150f + 240f * (lim / 240f).coerceIn(0f, 1f)).toDouble())
                    val r = size.width / 2f - inset; val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Color.White, 5.dp.toPx(), Offset(c.x + (r * Math.cos(a)).toFloat(), c.y + (r * Math.sin(a)).toFloat()))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${speed.toInt()}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("km/h", color = Color(0xCCFFFFFF), fontSize = 10.sp)
            }
        }
        if (lim != null) Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White).border(5.dp, Color(0xFFD6191E), CircleShape), contentAlignment = Alignment.Center) {
            Text((if (s.limitApprox) "~" else "") + lim, color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = if (lim >= 100) 14.sp else 17.sp)
        }
    }
}

@Composable
private fun RadarSign(s: ViewerState, modifier: Modifier) {
    val max = s.radarMax ?: return
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "radar").animateFloat(
        0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "a",
    )
    Column(modifier.alpha(pulse), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(92.dp).clip(CircleShape).background(Color.White).border(9.dp, Color(0xFFD6191E), CircleShape), contentAlignment = Alignment.Center) {
            Text("$max", color = Color(0xFF111111), fontWeight = FontWeight.Bold, fontSize = if (max >= 100) 30.sp else 36.sp)
        }
        Text("📷 ${s.radarKind.uppercase()} · ${s.radarDist} m", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun OverspeedHalo(s: ViewerState) {
    val max = s.radarMax ?: return
    if (s.speedKmh <= max + 2f) return
    val a by androidx.compose.animation.core.rememberInfiniteTransition(label = "halo").animateFloat(
        0.15f, 0.5f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "h",
    )
    Box(Modifier.fillMaxSize().drawBehind {
        val r = Math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat() / 2f
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color(0xFFFF1744).copy(alpha = a)), center = Offset(size.width / 2f, size.height / 2f), radius = r))
    })
}


@Composable
private fun ManeuverCard(s: ViewerState, modifier: Modifier) {
    if (s.rerouting) {
        Row(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("↻", color = Color(0xFF7CE0FF), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Recalculando ruta…", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    if (s.navArrow.isEmpty()) return
    val hw = s.highway
    val bg = if (hw) Color(0xFF0A4FA3) else Color.White
    val fg = if (hw) Color.White else Color(0xFF111111)
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(bg).border(2.5.dp, fg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.navArrow, color = fg, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Column {
            Text(s.navText.ifBlank { "Continúe" }, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (s.navDist >= 1000) String.format("%.1f km", s.navDist / 1000.0) else "${s.navDist} m", color = if (hw) Color(0xFFCFE3FF) else Color(0xFF555555), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TrafficCard(s: ViewerState, onAlt: (Route) -> Unit, modifier: Modifier) {
    val lvl = s.trafficLevel
    if (lvl <= 0) return
    val col = when (lvl) { 1 -> Color(0xFFF5D000); 2 -> Color(0xFFFF8A00); else -> Color(0xFFE53935) }
    val title = when (lvl) { 1 -> "Tráfico"; 2 -> "Tráfico denso"; else -> "Caravana o corte" }
    val alt = s.alts.minByOrNull { it.travelSec }
    val save = if (alt != null && s.curTravelSec > 0) (s.curTravelSec - alt.travelSec) / 60 else 0
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC101214)).border(2.dp, col, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${title}" + (if (s.trafficDist > 0) " en " + (if (s.trafficDist >= 1000) String.format("%.1f km", s.trafficDist / 1000.0) else "${s.trafficDist} m") else " ahora"),
            color = col, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val extra = (if (s.trafficLen > 0) "${s.trafficLen} m" else "") + (if (s.trafficDelayMin > 0) " · +${s.trafficDelayMin} min" else "")
        if (extra.isNotEmpty()) Text(extra, color = Color.White, fontSize = 13.sp)
        if (alt != null && save >= 1) {
            Text("Alternativa: −${save} min", color = Color(0xFF111111), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF7CE0FF))
                    .clickable { onAlt(alt) }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** Search bar: recents and favourites that match what you type, plus live suggestions. Drag it down to go home. */
@Composable
private fun SearchBar(s: ViewerState, onPick: (Router.Place) -> Unit, onHome: () -> Boolean, onClose: () -> Unit, modifier: Modifier) {
    var q by remember { mutableStateOf("") }
    var live by remember { mutableStateOf<List<Router.Place>>(emptyList()) }
    var hint by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.runtime.LaunchedEffect(q) {
        live = emptyList(); loading = false
        if (q.trim().length >= 3) {
            loading = true
            kotlinx.coroutines.delay(350)
            val la = if (s.hasFix) s.lat else null; val lo = if (s.hasFix) s.lon else null
            live = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Router.search(q.trim(), la, lo, s.tomtomKey) }
            loading = false
        }
    }
    val local = (PlaceBook.favs.map { it to true } + PlaceBook.recents.filter { r -> PlaceBook.favs.none { it.name == r.name } }.map { it to false })
        .filter { q.isBlank() || it.first.name.contains(q.trim(), ignoreCase = true) }
    val liveNew = live.filter { l -> local.none { it.first.name == l.name } }

    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xF2101214)).padding(8.dp)
            .androidx_pointer_drag({ dy -> drag += dy }, { if (drag > 140f) { if (!onHome()) hint = "No hay casa guardada: pulsa 🏠 en un favorito" }; drag = 0f }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextField(
                value = q, onValueChange = { q = it; hint = "" }, singleLine = true,
                placeholder = { Text("Buscar destino") },
                modifier = Modifier.weight(1f).androidx_focus(focus),
            )
            Text("✕", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { onClose() }.padding(12.dp))
        }
        Text(if (hint.isNotEmpty()) hint else if (PlaceBook.home != null) "↓ Arrastra hacia abajo para ir a casa" else "↓ Arrastra hacia abajo para ir a casa (marca 🏠 en un favorito)",
            color = if (hint.isNotEmpty()) Color(0xFFFFB74D) else Color(0xFF8A9199), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
            for ((p, fav) in local) PlaceRow(p, if (fav) "⭐" else "🕘", onPick)
            for (p in liveNew) PlaceRow(p, "📍", onPick)
            if (local.isEmpty() && liveNew.isEmpty()) Text(if (q.trim().length < 3) "Escribe al menos 3 letras" else if (loading) "Buscando…" else "Sin resultados", color = Color(0xFF8A9199), fontSize = 13.sp, modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
private fun PlaceRow(p: Router.Place, icon: String, onPick: (Router.Place) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 16.sp, modifier = Modifier.widthIn(min = 30.dp))
        Text(p.name, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (PlaceBook.isFav(p)) Text(if (PlaceBook.isHome(p)) "🏠" else "🏡", fontSize = 17.sp, modifier = Modifier.clickable { PlaceBook.markHome(p) }.padding(horizontal = 8.dp))
        Text(if (PlaceBook.isFav(p)) "★" else "☆", color = Color(0xFFFFD54F), fontSize = 22.sp, modifier = Modifier.clickable { PlaceBook.toggleFav(p) }.padding(horizontal = 8.dp))
    }
}

private fun Modifier.androidx_pointer_drag(onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectVerticalDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { _, dy -> onDrag(dy) }
    }

private fun Modifier.androidx_focus(r: androidx.compose.ui.focus.FocusRequester): Modifier = this.focusRequester(r)
