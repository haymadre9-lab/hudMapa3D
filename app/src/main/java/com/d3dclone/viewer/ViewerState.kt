package com.d3dclone.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Weather(val label: String) { NONE("Despejado"), RAIN("Lluvia"), SNOW("Nieve"), FOG("Niebla") }

/** One-to-one with the settings the original exposes through its JNI surface (SetNightDayStatus, SetWeatherType, SetSpeed...). */
class ViewerState {
    var carKey by mutableStateOf("cars/tesla_model3.glb")
    var customName by mutableStateOf<String?>(null)
    var customBytes: ByteArray? = null
    var reloadTick by mutableIntStateOf(0)

    var night by mutableStateOf(false)
    var weather by mutableStateOf(Weather.NONE)
    var speedKmh by mutableFloatStateOf(0f)

    var headlights by mutableStateOf(false)
    var leftSignal by mutableStateOf(false)
    var rightSignal by mutableStateOf(false)
    var hazard by mutableStateOf(false)
    var brake by mutableStateOf(false)
    var doorsOpen by mutableStateOf(false)

    var bloom by mutableStateOf(true)
    var msaa by mutableStateOf(false)
    var reflections by mutableStateOf(true)
    var ao by mutableStateOf(false)
    var brightness by mutableFloatStateOf(1f)

    var road by mutableStateOf(true)
    var autoRotate by mutableStateOf(false)
    var drive by mutableStateOf(true)
    var traffic by mutableStateOf(true)
    var highway by mutableStateOf(false)
    var useGps by mutableStateOf(true)
    var gpsOn by mutableStateOf(false)
    var gpsKmh = 0f
    var gpsStamp = 0L
    var shadows by mutableStateOf(false)
    var signs by mutableStateOf(true)
    var demo by mutableStateOf(false)
    var showMap by mutableStateOf(true)
    var routeVer by mutableIntStateOf(0)
    var routeStatus by mutableStateOf("")
    var dest by mutableStateOf("")
    var destPlace: Router.Place? = null
    var rerouting by mutableStateOf(false)
    var trafficVer by mutableIntStateOf(0)
    var keyNote by mutableStateOf("")
    var voice by mutableStateOf(true)
    var voiceGuide: VoiceGuide? = null
    var alts by mutableStateOf<List<Route>>(emptyList())
    var curTravelSec = 0
    var trafficLevel by mutableIntStateOf(0)
    var trafficDist by mutableIntStateOf(0)
    var trafficLen by mutableIntStateOf(0)
    var trafficDelayMin by mutableIntStateOf(0)
    var places by mutableStateOf<List<Router.Place>>(emptyList())
    var navArrow by mutableStateOf("")
    var navText by mutableStateOf("")
    var navDist by mutableIntStateOf(0)
    @Volatile var mapLat = 0.0
    @Volatile var mapLon = 0.0
    @Volatile var mapBrg = 0f
    @Volatile var mapValid = false
    @Volatile var routeS = 0.0
    @Volatile var lat = 0.0
    @Volatile var lon = 0.0
    @Volatile var hasFix = false
    var demoLat = 0.0
    var demoLon = 0.0
    var camInit: FloatArray? = null
    var radarMax by mutableStateOf<Int?>(null)
    var radarDist by mutableIntStateOf(0)
    var radarKind by mutableStateOf("")
    var autoRoad by mutableStateOf(true)
    var tomtomKey by mutableStateOf("")
    var limit by mutableStateOf<Int?>(null)
    var limitApprox by mutableStateOf(false)
    var roadName by mutableStateOf<String?>(null)
    var alert by mutableStateOf("")
    @Volatile var items: List<RoadData.Item> = emptyList()
    @Volatile var itemsVersion = 0
    var bearing = 0f
    var scenery by mutableStateOf(true)
    var warn by mutableStateOf(false)
    var lastTouch = 0L
    var flipFront by mutableStateOf(false)
    var paintIndex by mutableIntStateOf(0)       // 0 = original paint
    var tires by mutableStateOf(false)
    var gyro by mutableStateOf(false)
    var resetCamera by mutableIntStateOf(0)

    // Read-outs
    var fps by mutableIntStateOf(0)
    var pitchDeg by mutableFloatStateOf(0f)
    var rollDeg by mutableFloatStateOf(0f)
    var rigInfo by mutableStateOf("")

    val tirePressure = floatArrayOf(2.4f, 2.4f, 2.3f, 2.5f)

    companion object {
        val PAINTS: List<Paint?> = listOf(   // null = keep the model's own paint
            null,
            Paint("Blanco", 0.93f, 0.93f, 0.93f),
            Paint("Negro", 0.02f, 0.02f, 0.02f),
            Paint("Rojo", 0.75f, 0.04f, 0.05f),
            Paint("Azul", 0.05f, 0.18f, 0.70f),
            Paint("Verde", 0.04f, 0.45f, 0.17f),
            Paint("Plata", 0.65f, 0.67f, 0.70f),
            Paint("Naranja", 0.95f, 0.45f, 0.02f),
        )
    }
}

class Paint(val name: String, val r: Float, val g: Float, val b: Float)
