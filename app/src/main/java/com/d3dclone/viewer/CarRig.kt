package com.d3dclone.viewer

import android.opengl.Matrix
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import io.github.sceneview.node.ModelNode
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** What we can learn from the glTF JSON of a car without rendering it. */
class GlbInfo(val materialColors: Map<String, FloatArray>, val nodeNames: List<String>) {
    companion object {
        fun parse(bytes: ByteArray): GlbInfo? = runCatching {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val len = bb.getInt(12)
            val json = JSONObject(String(bytes, 20, len, Charsets.UTF_8))
            val colors = HashMap<String, FloatArray>()
            json.optJSONArray("materials")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val f = m.optJSONObject("pbrMetallicRoughness")?.optJSONArray("baseColorFactor")
                    val c = if (f != null) FloatArray(4) { f.getDouble(it).toFloat() } else floatArrayOf(1f, 1f, 1f, 1f)
                    if (m.optString("alphaMode") == "BLEND") c[3] = 0.5f
                    if (m.has("extensions") && m.getJSONObject("extensions").has("KHR_materials_transmission")) c[3] = 0.4f
                    colors[m.optString("name")] = c
                }
            }
            val names = ArrayList<String>()
            json.optJSONArray("nodes")?.let { arr ->
                for (i in 0 until arr.length()) arr.getJSONObject(i).optString("name").takeIf { it.isNotEmpty() }?.let(names::add)
            }
            GlbInfo(colors, names)
        }.getOrNull()
    }
}

/**
 * Dynamic parts of a loaded car, found by node name the same way the original engine does
 * (wheel_left_front, door_left_front, headlight_*, turn_signal_*, brake_light, body...).
 */
class CarRig(private val engine: Engine, val node: ModelNode, info: GlbInfo?) {
    private val asset = node.modelInstance.asset
    private val tm = engine.transformManager

    /** Piece with its pivot and axis already expressed in the entity's own local frame. */
    private class Part(val entity: Int, val base: FloatArray, val pivot: FloatArray, val axis: FloatArray, val sign: Float)

    private val wheels = ArrayList<Part>()
    private val doors = ArrayList<Part>()
    private val paintable = ArrayList<MaterialInstance>()

    val hasDoors get() = doors.isNotEmpty()
    val hasWheels get() = wheels.isNotEmpty()
    val wheelCount get() = wheels.size
    val doorCount get() = doors.size
    val hasPaint get() = paintable.isNotEmpty()

    init {
        val he = asset.boundingBox.halfExtent
        val axleNode = if (he[2] >= he[0]) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)

        for (e in asset.entities) {
            val name = asset.getName(e) ?: continue
            val lower = name.lowercase()
            if (!Regex("^[a-z_]+([.][0-9]+)?$").matches(lower) || lower.endsWith("dummy")) continue
            if (lower.startsWith("wheel") && !lower.contains("model")) {
                buildPart(e, axleNode, hinge = false)?.let(wheels::add)
            } else if (lower.startsWith("door")) {
                buildPart(e, floatArrayOf(0f, 1f, 0f), hinge = true)?.let(doors::add)
            }
        }

        fun hasSelectedAncestor(p: Part, all: List<Part>): Boolean {
            var cur = tm.getParent(tm.getInstance(p.entity))
            while (cur != 0) {
                if (all.any { it.entity == cur }) return true
                cur = tm.getParent(tm.getInstance(cur))
            }
            return false
        }
        wheels.removeAll { hasSelectedAncestor(it, wheels.toList()) }
        doors.removeAll { hasSelectedAncestor(it, doors.toList()) }

        val re = Regex("(?i)(body|paint|primary|car_cover|carpaint|coat|exterior|chassis)")
        val byName = node.modelInstance.materialInstances.filter { mi -> re.containsMatchIn(mi.name ?: "") }
        if (byName.isNotEmpty()) paintable.addAll(byName) else {
            for (mi in node.modelInstance.materialInstances) {
                val c = info?.materialColors?.get(mi.name ?: "") ?: continue
                val lum = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
                if (c[3] >= 0.99f && lum in 0.12f..0.95f) paintable.add(mi)
            }
        }
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray { val o = FloatArray(16); Matrix.multiplyMM(o, 0, a, 0, b, 0); return o }
    private fun xform(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val v = FloatArray(4); Matrix.multiplyMV(v, 0, m, 0, floatArrayOf(x, y, z, 1f), 0); return floatArrayOf(v[0], v[1], v[2])
    }

    /** Local-space AABB (min,max) of every renderable under e, in e's own frame. */
    private fun localBounds(e: Int): Pair<FloatArray, FloatArray>? {
        val mn = floatArrayOf(1e30f, 1e30f, 1e30f); val mx = floatArrayOf(-1e30f, -1e30f, -1e30f)
        var found = false
        val rm = engine.renderableManager
        fun visit(ent: Int, m: FloatArray) {
            val ri = rm.getInstance(ent)
            if (ri != 0) {
                val box = com.google.android.filament.Box(); rm.getAxisAlignedBoundingBox(ri, box)
                val c = box.center; val h = box.halfExtent
                for (i in 0..7) {
                    val p = xform(m, c[0] + (if (i and 1 == 0) -h[0] else h[0]), c[1] + (if (i and 2 == 0) -h[1] else h[1]), c[2] + (if (i and 4 == 0) -h[2] else h[2]))
                    for (k in 0..2) { mn[k] = minOf(mn[k], p[k]); mx[k] = maxOf(mx[k], p[k]) }
                }
                found = true
            }
            val ti = tm.getInstance(ent)
            val n = tm.getChildCount(ti)
            if (n > 0) {
                val kids = IntArray(n); tm.getChildren(ti, kids)
                for (k in kids) { val l = FloatArray(16); tm.getTransform(tm.getInstance(k), l); visit(k, mul(m, l)) }
            }
        }
        val id = FloatArray(16); Matrix.setIdentityM(id, 0)
        visit(e, id)
        return if (found) mn to mx else null
    }

    private fun buildPart(e: Int, axisNode: FloatArray, hinge: Boolean): Part? {
        val (mn, mx) = localBounds(e) ?: return null
        val inst = tm.getInstance(e)
        val base = FloatArray(16); tm.getTransform(inst, base)
        val w = FloatArray(16); tm.getWorldTransform(inst, w)
        val wInv = FloatArray(16); if (!Matrix.invertM(wInv, 0, w, 0)) return null
        // direction in node space -> local space (ignore translation)
        val d = FloatArray(4); Matrix.multiplyMV(d, 0, wInv, 0, floatArrayOf(axisNode[0], axisNode[1], axisNode[2], 0f), 0)
        val len = Math.sqrt((d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).toDouble()).toFloat().coerceAtLeast(1e-9f)
        val axis = floatArrayOf(d[0] / len, d[1] / len, d[2] / len)
        val sc = Math.sqrt((w[0] * w[0] + w[1] * w[1] + w[2] * w[2]).toDouble()).toFloat()
        val dims = floatArrayOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2])
        val ext = (0..2).filter { Math.abs(axis[it]) < 0.5f }.maxOf { dims[it] } * sc   // size across the axle: a wheel pair is wide but only one tyre tall
        if (!hinge && (ext > 1.3f || ext < 0.2f)) return null     // not a single wheel (group node or tiny part)
        if (hinge && ext > 3.2f) return null
        val centerL = floatArrayOf((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2)
        if (!hinge) return Part(e, base, centerL, axis, 1f)
        // hinge: front edge (min Z in node space) of the door, middle of its depth
        val corners = ArrayList<FloatArray>()
        for (i in 0..7) corners.add(xform(w, if (i and 1 == 0) mn[0] else mx[0], if (i and 2 == 0) mn[1] else mx[1], if (i and 4 == 0) mn[2] else mx[2]))
        val minZ = corners.minOf { it[2] }
        val cx = corners.sumOf { it[0].toDouble() }.toFloat() / 8f
        val cy = corners.sumOf { it[1].toDouble() }.toFloat() / 8f
        val pivot = xform(wInv, cx, cy, minZ)
        return Part(e, base, pivot, axis, if (cx >= 0f) 1f else -1f)
    }

    private fun spun(p: Part, deg: Float): FloatArray {
        val r = FloatArray(16); Matrix.setRotateM(r, 0, deg, p.axis[0], p.axis[1], p.axis[2])
        val t1 = FloatArray(16); Matrix.setIdentityM(t1, 0); Matrix.translateM(t1, 0, p.pivot[0], p.pivot[1], p.pivot[2])
        val t2 = FloatArray(16); Matrix.setIdentityM(t2, 0); Matrix.translateM(t2, 0, -p.pivot[0], -p.pivot[1], -p.pivot[2])
        return mul(mul(mul(p.base, t1), r), t2)
    }

    fun spinWheels(angleDeg: Float) {
        for (w in wheels) tm.setTransform(tm.getInstance(w.entity), spun(w, angleDeg))
    }

    /** open in 0..1 */
    fun setDoors(open: Float) {
        for (d in doors) tm.setTransform(tm.getInstance(d.entity), spun(d, d.sign * 35f * open))
    }

    private enum class Lamp { HEAD, TAIL, BRAKE, SIG_L, SIG_R }
    private val lampMats = HashMap<Lamp, MutableList<MaterialInstance>>()
    private val lampState = HashMap<Lamp, Float>()
    private val baseColors = info?.materialColors ?: emptyMap()
    val hasLamps get() = lampMats.values.any { it.isNotEmpty() }

    init { classifyLamps() }

    private fun classifyLamps() {
        for (mi in node.modelInstance.materialInstances) {
            val n = (mi.name ?: "").lowercase()
            val lamp = when {
                Regex("indicator|turn|signal|blink").containsMatchIn(n) ->
                    if (Regex("indicator_l|_left|signal_l|turn_l").containsMatchIn(n)) Lamp.SIG_L else Lamp.SIG_R
                Regex("brake|break|brems").containsMatchIn(n) -> Lamp.BRAKE
                Regex("rear.?light|tail|back.?light").containsMatchIn(n) -> Lamp.TAIL
                Regex("(front|head).{0,6}light|headlight|fog").containsMatchIn(n) && !n.contains("indicator") -> Lamp.HEAD
                else -> null
            } ?: continue
            lampMats.getOrPut(lamp) { ArrayList() }.add(mi)
        }
    }

    private fun emissiveType(mi: MaterialInstance): com.google.android.filament.Material.Parameter? =
        runCatching { mi.material.parameters.firstOrNull { it.name == "emissiveFactor" } }.getOrNull()

    private fun setLamp(l: Lamp, level: Float, r: Float, g: Float, b: Float) {
        if (lampState[l] == level) return
        lampState[l] = level
        for (mi in lampMats[l] ?: return) {
            val p = emissiveType(mi)
            if (p != null) {
                if (p.type == com.google.android.filament.Material.Parameter.Type.FLOAT4) mi.setParameter("emissiveFactor", r * level * 4f, g * level * 4f, b * level * 4f, 1f)
                else mi.setParameter("emissiveFactor", r * level * 4f, g * level * 4f, b * level * 4f)
            }
            val base = baseColors[mi.name ?: ""] ?: floatArrayOf(1f, 1f, 1f, 1f)
            if (level > 0f) mi.setParameter("baseColorFactor", r, g, b, base[3].coerceAtLeast(0.9f))
            else mi.setParameter("baseColorFactor", base[0], base[1], base[2], base[3])
        }
    }

    fun lamps(head: Boolean, tail: Boolean, brake: Boolean, left: Boolean, right: Boolean) {
        setLamp(Lamp.HEAD, if (head) 1f else 0f, 1f, 0.96f, 0.8f)
        setLamp(Lamp.TAIL, if (tail) 1f else 0f, 1f, 0.05f, 0.03f)
        setLamp(Lamp.BRAKE, if (brake) 1.5f else if (tail) 0.5f else 0f, 1f, 0.05f, 0.03f)
        setLamp(Lamp.SIG_L, if (left) 1f else 0f, 1f, 0.55f, 0.02f)
        setLamp(Lamp.SIG_R, if (right) 1f else 0f, 1f, 0.55f, 0.02f)
    }

    fun paint(r: Float, g: Float, b: Float) {
        fun lin(c: Float) = c.pow(2.2f)
        for (mi in paintable) mi.setParameter("baseColorFactor", lin(r), lin(g), lin(b), 1f)
    }
}
