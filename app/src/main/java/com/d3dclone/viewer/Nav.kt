package com.d3dclone.viewer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** kind: L R SL SR S U (turns), X exit, RB roundabout, ARRIVE. */
class Step(val offset: Double, val kind: String, val text: String, val ref: String?, val exitNo: Int = 0) {
    val arrow get() = when (kind) { "L" -> "←"; "R" -> "→"; "SL" -> "↖"; "SR", "X" -> "↗"; "U" -> "↩"; "ARRIVE" -> "🏁"; else -> "↑" }
    /** Short label for a road sign. */
    fun label(motorway: Boolean): String {
        val name = text.ifBlank { ref.orEmpty() }
        val full = if (motorway && ref != null && text.isNotBlank() && !text.contains(ref)) "$ref · $text" else name
        return if (full.isBlank()) (if (kind == "ARRIVE") "Destino" else "Continúe") else full
    }
}

/** A calculated route: polyline in lat/lon, cumulative metres, and the guidance steps. */
class Route(val lat: DoubleArray, val lon: DoubleArray, val steps: List<Step>, val name: String = "", val motorway: List<DoubleArray> = emptyList(),
    /** Traffic sections: [startS, endS, level 1 some / 2 dense / 3 jam or closure, delay seconds]. */
    traffic: List<DoubleArray> = emptyList(), val travelSec: Int = 0) {
    @Volatile var traffic: List<DoubleArray> = traffic
    /** True when the traffic comes from TomTom's flow API (route calculated by a free service). */
    @Volatile var viaFlow = false
    val n = lat.size
    private val mx = 111320.0 * cos(Math.toRadians(lat[0])); private val my = 110540.0
    val x = DoubleArray(n) { (lon[it] - lon[0]) * mx }
    val y = DoubleArray(n) { (lat[it] - lat[0]) * my }
    val cum = DoubleArray(n)
    val total: Double
    private val hdg: DoubleArray            // smoothed unwrapped bearing (rad) every STEP_M metres
    companion object { const val STEP_M = 4.0 }

    init {
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        total = cum[n - 1]
        val m = max(2, (total / STEP_M).toInt() + 2)
        val raw = DoubleArray(m)
        var seg = 0
        for (k in 0 until m) {
            val s = k * STEP_M
            while (seg < n - 2 && cum[seg + 1] < s) seg++
            raw[k] = atan2(x[seg + 1] - x[seg], y[seg + 1] - y[seg])
        }
        for (k in 1 until m) { var d = raw[k] - raw[k - 1]; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; raw[k] = raw[k - 1] + d }
        hdg = DoubleArray(m)
        val w = 6
        for (k in 0 until m) {
            var sum = 0.0; var wt = 0.0
            for (j in -w..w) { val q = (k + j).coerceIn(0, m - 1); val c = (w + 1 - abs(j)).toDouble(); sum += raw[q] * c; wt += c }
            hdg[k] = sum / wt
        }
    }

    fun theta(s: Double): Double {
        val f = (s / STEP_M).coerceIn(0.0, (hdg.size - 1).toDouble())
        val i = f.toInt().coerceAtMost(hdg.size - 2)
        return hdg[i] + (hdg[i + 1] - hdg[i]) * (f - i)
    }

    /** Position and bearing (deg) at distance s along the route. */
    fun pose(s: Double): DoubleArray {
        val ss = s.coerceIn(0.0, total)
        var i = 0
        while (i < n - 2 && cum[i + 1] < ss) i++
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) (ss - cum[i]) / seg else 0.0
        return doubleArrayOf(lat[i] + (lat[i + 1] - lat[i]) * t, lon[i] + (lon[i + 1] - lon[i]) * t, Math.toDegrees(theta(ss)))
    }

    /** Distance along the route of the point nearest to (la, lo), searching near [hint]. Returns (s, offRouteMetres). */
    fun project(la: Double, lo: Double, hint: Double): DoubleArray {
        val px = (lo - lon[0]) * mx; val py = (la - lat[0]) * my
        var best = Double.MAX_VALUE; var bestS = hint
        for (pass in 0..1) {
            val from = if (pass == 0) hint - 80.0 else 0.0
            val to = if (pass == 0) hint + 500.0 else total
            for (i in 0 until n - 1) {
                if (cum[i + 1] < from || cum[i] > to) continue
                val dx = x[i + 1] - x[i]; val dy = y[i + 1] - y[i]
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((px - x[i]) * dx + (py - y[i]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(px - (x[i] + dx * t), py - (y[i] + dy * t))
                if (d < best) { best = d; bestS = cum[i] + t * sqrt2(l2) }
            }
            if (best < 120.0) break
        }
        return doubleArrayOf(bestS, best)
    }
    private fun sqrt2(v: Double) = Math.sqrt(v)

    fun trafficLevelAt(s: Double): Int { var l = 0; for (r in traffic) if (s >= r[0] && s <= r[1] && r[2] > l) l = r[2].toInt(); return l }

    /** The first traffic section that is still ahead (or under us) within 6 km: [start, end, level, delay]. */
    fun nextTraffic(s: Double): DoubleArray? = traffic.filter { it[1] > s && it[0] < s + 6000.0 }.minByOrNull { it[0] }

    /** Polyline points (lat, lon) between distances a and b along the route. */
    fun polyline(a: Double, b: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val pa = pose(a); out.add(doubleArrayOf(pa[0], pa[1]))
        for (i in 0 until n) if (cum[i] > a && cum[i] < b) out.add(doubleArrayOf(lat[i], lon[i]))
        val pb = pose(b); out.add(doubleArrayOf(pb[0], pb[1]))
        return out
    }

    /** Roughly the same road as the other route? (five sample points within 60 m). */
    fun similarTo(o: Route): Boolean {
        for (i in 1..5) { val p = pose(total * i / 6.0); if (o.project(p[0], p[1], 0.0)[1] > 60.0) return false }
        return true
    }

    /** True when the route is on a motorway at distance s. */
    fun isMotorway(s: Double): Boolean = motorway.any { s >= it[0] && s <= it[1] }

    /** The next step ahead of s with the metres left to it, or null. */
    fun next(s: Double): Pair<Step, Double>? {
        for (st in steps) if (st.offset > s + 12.0) return st to (st.offset - s)
        return null
    }
}

object Router {
    class Place(val name: String, val lat: Double, val lon: Double)

    /** Shows a message about the TomTom key (set by the activity). */
    @Volatile var noteSink: ((String) -> Unit)? = null
    private fun note(s: String) { noteSink?.invoke(s) }

    private fun get(url: String, ua: Boolean = false): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        if (ua) c.setRequestProperty("User-Agent", "D3DClone/1.0")
        return try {
            val code = c.responseCode
            if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else {
                android.util.Log.e("D3DNAV", "HTTP " + code + " " + url.substringBefore("?"))
                if (code == 403 && url.contains("api.tomtom.com")) note("Tu clave de TomTom no tiene activado " + (if (url.contains("/search/")) "el producto Search" else if (url.contains("/routing/")) "el producto Routing" else "el producto Traffic") + " (error 403). Uso servicios libres donde puedo.")
                null
            }
        } finally { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(q: String, lat: Double?, lon: Double?, key: String): List<Place> = runCatching {
        val out = ArrayList<Place>()
        if (key.isNotBlank()) {
            val bias = if (lat != null && lon != null) "&lat=$lat&lon=$lon" else ""
            val body = get("https://api.tomtom.com/search/2/search/${enc(q)}.json?key=$key&limit=6&language=es-ES$bias")
            if (body == null) return@runCatching photon(q, lat, lon)
            val r = JSONObject(body).optJSONArray("results") ?: return@runCatching out
            for (i in 0 until r.length()) {
                val o = r.getJSONObject(i); val pos = o.optJSONObject("position") ?: continue
                val name = o.optJSONObject("poi")?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: o.optJSONObject("address")?.optString("freeformAddress") ?: continue
                val where = o.optJSONObject("address")?.optString("freeformAddress").orEmpty()
                out.add(Place(if (where.isNotBlank() && where != name) "$name, $where" else name, pos.getDouble("lat"), pos.getDouble("lon")))
            }
        } else return@runCatching photon(q, lat, lon)
        out
    }.getOrDefault(emptyList())

    private fun photon(q: String, lat: Double?, lon: Double?): List<Place> = runCatching {
        val out = ArrayList<Place>()
        run {
            val bias = if (lat != null && lon != null) "&lat=$lat&lon=$lon" else ""
            val body = get("https://photon.komoot.io/api/?limit=6$bias&q=${enc(q)}", true) ?: return@runCatching out
            val f = JSONObject(body).optJSONArray("features") ?: return@runCatching out
            for (i in 0 until f.length()) {
                val o = f.getJSONObject(i); val c = o.getJSONObject("geometry").getJSONArray("coordinates"); val p = o.getJSONObject("properties")
                val name = listOf(p.optString("name"), p.optString("street"), p.optString("city"), p.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
                if (name.isNotBlank()) out.add(Place(name, c.getDouble(1), c.getDouble(0)))
            }
        }
        out
    }.onFailure { android.util.Log.e("D3DNAV", "search failed: " + it) }.getOrDefault(emptyList())

    fun routes(fLat: Double, fLon: Double, to: Place, key: String): List<Route> = runCatching {
        val tt = if (key.isNotBlank()) tomtom(fLat, fLon, to, key) else emptyList()
        if (tt.isNotEmpty()) tt else listOfNotNull(osrm(fLat, fLon, to))
    }.getOrDefault(emptyList())

    fun route(fLat: Double, fLon: Double, to: Place, key: String): Route? = routes(fLat, fLon, to, key).firstOrNull()

    private fun kindOf(m: String): String {
        val u = m.uppercase()
        return when {
            "ARRIVE" in u -> "ARRIVE"
            "ROUNDABOUT" in u || "RB" == u -> "RB"
            "EXIT" in u || "RAMP" in u -> "X"
            "UTURN" in u || "U_TURN" in u || "U-TURN" in u -> "U"
            ("BEAR" in u || "KEEP" in u || "SLIGHT" in u) && "LEFT" in u -> "SL"
            ("BEAR" in u || "KEEP" in u || "SLIGHT" in u) && "RIGHT" in u -> "SR"
            "LEFT" in u -> "L"
            "RIGHT" in u -> "R"
            else -> "S"
        }
    }

    private fun tomtom(fLat: Double, fLon: Double, to: Place, key: String): List<Route> {
        val body = get("https://api.tomtom.com/routing/1/calculateRoute/$fLat,$fLon:${to.lat},${to.lon}/json?key=$key" +
            "&instructionsType=text&language=es-ES&routeType=fastest&traffic=true&maxAlternatives=2&sectionType=motorway&sectionType=traffic") ?: return emptyList()
        val rs = JSONObject(body).optJSONArray("routes") ?: return emptyList()
        val out = ArrayList<Route>()
        for (q in 0 until rs.length()) parseTomTom(rs.getJSONObject(q), to)?.let(out::add)
        return out
    }

    private fun parseTomTom(r: JSONObject, to: Place): Route? {
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        val legs = r.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val pts = legs.getJSONObject(i).getJSONArray("points")
            for (j in 0 until pts.length()) { val p = pts.getJSONObject(j); la.add(p.getDouble("latitude")); lo.add(p.getDouble("longitude")) }
        }
        if (la.size < 2) return null
        val steps = ArrayList<Step>()
        r.optJSONObject("guidance")?.optJSONArray("instructions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val man = o.optString("maneuver")
                if (man.startsWith("DEPART")) continue
                val ref = o.optJSONArray("roadNumbers")?.optString(0)?.takeIf { it.isNotBlank() }
                val text = o.optString("signpostText").ifBlank { o.optString("street") }.ifBlank { o.optString("message") }
                steps.add(Step(o.optDouble("routeOffsetInMeters", 0.0), kindOf(man), text, ref,
                    maxOf(o.optInt("roundaboutExitNumber", 0), o.optString("exitNumber").toIntOrNull() ?: 0)))
            }
        }
        val base = Route(la.toDoubleArray(), lo.toDoubleArray(), steps, to.name)
        val mw = ArrayList<DoubleArray>(); val tr = ArrayList<DoubleArray>()
        val secs = r.optJSONArray("sections")
        if (secs != null) for (i in 0 until secs.length()) {
            val o = secs.getJSONObject(i)
            val a = o.optInt("startPointIndex").coerceIn(0, base.n - 1); val b = o.optInt("endPointIndex").coerceIn(0, base.n - 1)
            when (o.optString("sectionType")) {
                "MOTORWAY" -> mw.add(doubleArrayOf(base.cum[a], base.cum[b]))
                "TRAFFIC" -> {
                    val cat = o.optString("simpleCategory"); val mag = o.optInt("magnitudeOfDelay", 0)
                    val level = when {
                        cat == "ROAD_CLOSURE" || mag >= 4 -> 3
                        mag == 3 -> 3
                        mag == 2 -> 2
                        mag == 1 || cat == "JAM" || cat == "ROAD_WORKS" -> 1
                        else -> 0
                    }
                    if (level > 0) tr.add(doubleArrayOf(base.cum[a], base.cum[b], level.toDouble(), o.optDouble("delayInSeconds", 0.0)))
                }
            }
        }
        val travel = r.optJSONObject("summary")?.optInt("travelTimeInSeconds", 0) ?: 0
        return Route(base.lat, base.lon, steps, to.name, mw, tr, travel)
    }

    /** Traffic colours from TomTom's Flow Segment Data (the "Traffic" product), for routes from a free service. */
    fun flowTraffic(r: Route, fromS: Double, key: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var a = fromS.coerceAtLeast(0.0); var n = 0
        var curLvl = 0; var curStart = a; var curDelay = 0.0
        while (a < r.total && n < 40) {
            val b = minOf(a + 400.0, r.total)
            val mid = r.pose((a + b) / 2)
            val body = get("https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json?point=${mid[0]},${mid[1]}&unit=KMPH&key=$key") ?: return out
            val f = runCatching { JSONObject(body).getJSONObject("flowSegmentData") }.getOrNull()
            var lvl = 0; var delay = 0.0
            if (f != null) {
                val free = f.optDouble("freeFlowSpeed", 0.0); val cur = f.optDouble("currentSpeed", free)
                val ratio = if (free > 0) cur / free else 1.0
                lvl = if (f.optBoolean("roadClosure")) 3 else if (ratio < 0.35) 3 else if (ratio < 0.6) 2 else if (ratio < 0.85) 1 else 0
                if (lvl > 0 && cur > 0 && free > 0) delay = (b - a) / (cur / 3.6) - (b - a) / (free / 3.6)
            }
            if (lvl != curLvl) { if (curLvl > 0) out.add(doubleArrayOf(curStart, a, curLvl.toDouble(), curDelay)); curLvl = lvl; curStart = a; curDelay = 0.0 }
            curDelay += delay
            a = b; n++
        }
        if (curLvl > 0) out.add(doubleArrayOf(curStart, a, curLvl.toDouble(), curDelay))
        return out
    }

    private fun osrm(fLat: Double, fLon: Double, to: Place): Route? {
        val body = get("https://router.project-osrm.org/route/v1/driving/$fLon,$fLat;${to.lon},${to.lat}?overview=full&geometries=geojson&steps=true", true) ?: return null
        val r = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        val c = r.getJSONObject("geometry").getJSONArray("coordinates")
        val la = DoubleArray(c.length()) { c.getJSONArray(it).getDouble(1) }; val lo = DoubleArray(c.length()) { c.getJSONArray(it).getDouble(0) }
        val steps = ArrayList<Step>()
        val motorway = ArrayList<DoubleArray>()
        var acc = 0.0
        val st = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        for (i in 0 until st.length()) {
            val o = st.getJSONObject(i); val man = o.getJSONObject("maneuver")
            val type = man.optString("type"); val mod = man.optString("modifier")
            if (type != "depart") {
                val name = o.optString("destinations").ifBlank { o.optString("name") }.ifBlank { o.optString("ref") }
                val kind = if (type == "arrive") "ARRIVE" else if (type.contains("roundabout") || type == "rotary") "RB"
                    else if (type == "off ramp") "X" else kindOf(mod)
                steps.add(Step(acc, kind, name.replace(", ", " · "), o.optString("ref").ifBlank { null }, maxOf(man.optInt("exit", 0), o.optString("exits").toIntOrNull() ?: 0)))
            }
            val dist = o.optDouble("distance", 0.0)
            val inter = o.optJSONArray("intersections")
            var mw = o.optString("ref").let { it.startsWith("A-") || it.startsWith("AP-") || it.startsWith("E-") }
            if (inter != null) for (q in 0 until inter.length()) {
                val cl = inter.getJSONObject(q).optJSONArray("classes")
                if (cl != null) for (z in 0 until cl.length()) if (cl.optString(z) == "motorway") mw = true
            }
            if (mw) { val last = motorway.lastOrNull(); if (last != null && abs(last[1] - acc) < 1.0) last[1] = acc + dist else motorway.add(doubleArrayOf(acc, acc + dist)) }
            acc += dist
        }
        return if (la.size >= 2) Route(la, lo, steps, to.name, motorway) else null
    }

    /** A made-up route (no network) with bends, turns and a motorway exit: for testing the scene and map. */
    fun testRoute(lat0: Double, lon0: Double): Route {
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        val mx = 111320.0 * cos(Math.toRadians(lat0)); val my = 110540.0
        var x = 0.0; var y = 0.0; var h = 0.0
        // (length m, total turn deg) pieces
        val legs = listOf(150.0 to 0.0, 70.0 to 90.0, 220.0 to 0.0, 110.0 to -75.0, 180.0 to 0.0, 160.0 to 40.0, 160.0 to -40.0,
            400.0 to 0.0, 220.0 to 35.0, 300.0 to 0.0, 120.0 to -85.0, 500.0 to 0.0)
        la.add(lat0); lo.add(lon0)
        for ((len, turn) in legs) {
            val n = (len / 5).toInt().coerceAtLeast(1)
            for (i in 0 until n) { h += Math.toRadians(turn) / n; x += sin(h) * 5; y += cos(h) * 5; la.add(lat0 + y / my); lo.add(lon0 + x / mx) }
        }
        val steps = listOf(
            Step(150.0, "R", "Calle Mayor", null), Step(550.0, "L", "Avenida de la Libertad", null),
            Step(1280.0, "X", "Bilbao centro", "A-8", 12), Step(1700.0, "RB", "Avenida del Puerto", null, 2), Step(1900.0, "L", "Calle Mayor", null), Step(2400.0, "ARRIVE", "Destino de prueba", null),
        )
        return Route(la.toDoubleArray(), lo.toDoubleArray(), steps, "Ruta de prueba", listOf(doubleArrayOf(1000.0, 1450.0)),
            listOf(doubleArrayOf(380.0, 560.0, 1.0, 40.0), doubleArrayOf(700.0, 900.0, 2.0, 150.0), doubleArrayOf(1560.0, 1760.0, 3.0, 420.0)), 780)
    }


/** A faster, traffic-free copy of a route, for the demo of the alternative-route option. */
    fun testAlternative(r: Route) = Route(r.lat, r.lon, r.steps, "Alternativa de prueba", r.motorway, emptyList(), 540)
}

/** Link between the route and the 3D scene: where we are on the route, and its bends as the road's curvature. */
object Course {
    @Volatile var route: Route? = null
    @Volatile var pendingS = -1.0
    @Volatile var pendingVer = 0
    @Volatile var needReset = false
    @Volatile var startS = 0.0
    @Volatile var offRoute = 0.0
    var s0 = 0.0; var odo0 = 0.0
    private var lastS = 0.0

    fun set(r: Route?) { route = r; lastS = 0.0; pendingS = -1.0; needReset = true; pendingVer++ }

    /** Called with each GPS fix (main thread). */
    fun onFix(lat: Double, lon: Double) {
        val r = route ?: return
        val p = r.project(lat, lon, lastS)
        lastS = p[0]; pendingS = p[0]; offRoute = p[1]; pendingVer++
    }

    /** Route progress (m) for the scene odometer reading. */
    fun sNow(odo: Double) = s0 + (odo - odo0)

    /** Bearing (rad, unwrapped) at odometer position x. */
    fun theta(x: Double): Double = route?.theta(sNow(x)) ?: 0.0
}
