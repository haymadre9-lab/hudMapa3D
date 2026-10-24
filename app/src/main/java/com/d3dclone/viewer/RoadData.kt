package com.d3dclone.viewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Road knowledge for the GPS mode:
 *  - stop / yield / traffic lights / crossings from assets/data/senales.json and speed cameras from radares.json;
 *  - the road under the car (speed limit, class, name) from OpenStreetMap (Overpass, no key), with
 *    TomTom's free-flow speed as an approximate fallback when a TomTom key is configured.
 */
object RoadData {
    /** kind: s stop, c ceda, t semáforo, p paso de peatones, r radar. dist = metres ahead along the heading. */
    class Item(val kind: Char, val dist: Float, val cross: Float, val max: Int, val label: String)
    class Road(val limit: Int?, val approx: Boolean, val cls: String?, val name: String?, val ref: String?) {
        val motorway get() = cls != null && (cls.startsWith("motorway") || cls.startsWith("trunk"))
    }

    @Volatile var road: Road? = null
    @Volatile var ready = false

    private var sig = DoubleArray(0)            // lat, lon, lat, lon...
    private var sigKind = CharArray(0)
    private var rad = DoubleArray(0)
    private var radMax = IntArray(0)
    private var radType = emptyArray<String>()

    fun load(ctx: Context) {
        thread(name = "roaddata") {
            runCatching {
                val s = JSONObject(ctx.assets.open("data/senales.json").bufferedReader().use { it.readText() })
                val p = s.getJSONArray("p")
                sig = DoubleArray(p.length() * 2); sigKind = CharArray(p.length())
                for (i in 0 until p.length()) {
                    val e = p.getJSONArray(i)
                    sig[2 * i] = e.getDouble(0); sig[2 * i + 1] = e.getDouble(1); sigKind[i] = e.getString(2)[0]
                }
                val r = JSONArray(ctx.assets.open("data/radares.json").bufferedReader().use { it.readText() })
                rad = DoubleArray(r.length() * 2); radMax = IntArray(r.length()); radType = Array(r.length()) { "" }
                for (i in 0 until r.length()) {
                    val o = r.getJSONObject(i)
                    rad[2 * i] = o.getDouble("lat"); rad[2 * i + 1] = o.getDouble("lon")
                    radMax[i] = o.optInt("max", 0); radType[i] = o.optString("t")
                }
                ready = true
            }
        }
    }

    /** Things on our road within ~450 m in front, nearest first. */
    fun ahead(lat: Double, lon: Double, bearingDeg: Float): List<Item> {
        if (!ready) return emptyList()
        val b = Math.toRadians(bearingDeg.toDouble())
        val sb = sin(b); val cb = cos(b)
        val mLat = 110540.0; val mLon = 111320.0 * cos(Math.toRadians(lat))
        val out = ArrayList<Item>()
        fun test(la: Double, lo: Double, maxAlong: Double, maxCross: Double): DoubleArray? {
            if (abs(la - lat) > 0.006 || abs(lo - lon) > 0.009) return null
            val dy = (la - lat) * mLat; val dx = (lo - lon) * mLon
            val along = dx * sb + dy * cb; val cross = dx * cb - dy * sb
            return if (along in 4.0..maxAlong && abs(cross) <= maxCross) doubleArrayOf(along, cross) else null
        }
        for (i in sigKind.indices) {
            val r = test(sig[2 * i], sig[2 * i + 1], 450.0, 11.0) ?: continue
            if (sigKind[i] == 's' || sigKind[i] == 'c' || sigKind[i] == 'p' || sigKind[i] == 't')
                out.add(Item(sigKind[i], r[0].toFloat(), r[1].toFloat(), 0, ""))
        }
        for (i in radMax.indices) {
            val r = test(rad[2 * i], rad[2 * i + 1], 700.0, 25.0) ?: continue
            out.add(Item('r', r[0].toFloat(), r[1].toFloat(), radMax[i], radType[i]))
        }
        out.sortBy { it.dist }
        return if (out.size > 10) out.subList(0, 10) else out
    }

    // ------------------------------------------------------------------ road under the car
    @Volatile private var busy = false
    private var lastLat = 0.0; private var lastLon = 0.0; private var lastAt = 0L

    private val RANK = listOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
        "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road")
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    fun requestRoad(lat: Double, lon: Double, tomtomKey: String) {
        val now = System.currentTimeMillis()
        val moved = abs(lat - lastLat) * 110540.0 + abs(lon - lastLon) * 111320.0 * cos(Math.toRadians(lat))
        val due = lastAt == 0L || (now - lastAt > 8000 && moved > 60) || now - lastAt > 45_000
        if (!due || busy) return
        busy = true; lastLat = lat; lastLon = lon; lastAt = now
        thread(name = "roadinfo") {
            try { road = query(lat, lon, tomtomKey) ?: road } catch (_: Exception) { } finally { busy = false }
        }
    }

    private fun get(url: String, ua: Boolean = false): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        if (ua) c.setRequestProperty("User-Agent", "D3DClone/1.0")
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    }

    private fun query(lat: Double, lon: Double, key: String): Road? {
        val q = URLEncoder.encode("[out:json][timeout:6];way(around:25,$lat,$lon)[highway];out tags 6;", "UTF-8")
        var body: String? = null
        for (e in ENDPOINTS) { body = runCatching { get("$e?data=$q", true) }.getOrNull(); if (body != null) break }
        var best: JSONObject? = null
        if (body != null) {
            val els = JSONObject(body).optJSONArray("elements")
            val tags = ArrayList<JSONObject>()
            if (els != null) for (i in 0 until els.length()) els.getJSONObject(i).optJSONObject("tags")?.let { tags.add(it) }
            best = tags.filter { it.optString("highway") in RANK }.minByOrNull { RANK.indexOf(it.optString("highway")) }
        }
        var limit = best?.let { parseMax(it.optString("maxspeed")) }
        var approx = false
        if (limit == null && key.isNotBlank()) {
            val t = runCatching {
                get("https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json?point=$lat,$lon&unit=KMPH&key=$key")
            }.getOrNull()
            val free = t?.let { runCatching { JSONObject(it).getJSONObject("flowSegmentData").optDouble("freeFlowSpeed", 0.0) }.getOrNull() } ?: 0.0
            if (free > 0) { limit = ((free / 10.0).toInt() * 10).coerceAtLeast(10); approx = true }
        }
        if (best == null && limit == null) return null
        return Road(limit, approx, best?.optString("highway"), best?.optString("name")?.ifBlank { null }, best?.optString("ref")?.ifBlank { null })
    }

    private fun parseMax(raw: String?): Int? {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        Regex("^(\\d+)\\s*km/h$").find(s)?.let { return it.groupValues[1].toInt() }
        Regex("^(\\d+)\\s*mph$").find(s)?.let { return (it.groupValues[1].toInt() * 1.609).toInt() }
        return when (s) { "es:urban" -> 50; "es:rural" -> 90; "es:trunk" -> 100; "es:motorway" -> 120; else -> null }
    }
}
