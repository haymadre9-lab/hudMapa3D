package com.d3dclone.viewer

import android.content.res.AssetManager
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Wraps a model in a pivot node: the model is scaled to [target] metres (height, or its longest
 * horizontal side when [byLength]), centred on the pivot and sitting on y = 0.
 */
fun fitModel(engine: Engine, inst: FilamentInstance, target: Float, byLength: Boolean): Pair<Node, Float> {
    val model = ModelNode(modelInstance = inst)
    val bb = inst.asset.boundingBox
    val h = bb.halfExtent; val c = bb.center
    val size = if (byLength) 2f * max(h[0], h[2]) else 2f * h[1]
    val f = target / max(size, 1e-4f)
    model.scale = Scale(f, f, f)
    model.position = Position(-c[0] * f, -(c[1] - h[1]) * f, -c[2] * f)
    val pivot = Node(engine)
    model.parent = pivot
    return pivot to max(h[0], h[2]) * f      // pivot, horizontal radius in metres
}

fun hashf(i: Long, k: Int): Float {
    var h = (i * 73856093L) xor (k * 19349663L)
    h = (h xor (h ushr 13)) * 1274126177L
    return ((h xor (h ushr 16)) and 0xffff).toFloat() / 65535f
}

/** One kind of roadside object. A folder of .glb files in assets/ is all that is needed to add models. */
class Cat(
    val dir: String,
    val spacing: Float,          // metres between consecutive objects on one side
    val ahead: Float,            // how far ahead they are drawn
    val back: Float,
    val edgeOffset: Float,       // distance from the road edge
    val jitter: Float,           // extra random distance from the road
    val both: Boolean,           // both sides, or only the right one
    val faceRoad: Boolean,       // else random orientation
    val defaultHeight: Float,
    val heights: Map<String, Float> = emptyMap(),
    val lengths: Map<String, Float> = emptyMap(),   // files sized by their longest side instead of height
    val scaleJitter: Float = 0f,
    val yawFix: Float = 0f,
    val maxPerFile: Map<String, Int> = emptyMap(),
    val cityOnly: Boolean = false,
    val centreTowardsRoad: Boolean = false,   // lamps: the bounding box centre sits between post and arm
)

private class Slot(val pivot: Node, val side: Int, val radius: Float)

class Scenery(private val engine: Engine, private val loader: ModelLoader, private val assets: AssetManager) {
    private val cats = listOf(
        Cat("scene/lamps", 32f, 100f, 14f, 1.1f, 0f, true, true, 7.5f, yawFix = 90f, centreTowardsRoad = true),
        Cat("scene/trees", 12f, 100f, 14f, 5f, 7f, true, false, 9f, scaleJitter = 0.25f),
        Cat("scene/buildings", 48f, 110f, 26f, 12f, 10f, true, true, 18f,
            heights = mapOf("edificio_3" to 16f, "edificio_5" to 26f, "edificio_brick" to 20f),
            maxPerFile = mapOf("edificio_brick" to 1), cityOnly = true),
        Cat("scene/landmarks", 520f, 330f, 40f, 60f, 25f, true, true, 45f,
            heights = mapOf("edificio_corficolombiana_colombian_skyscraper" to 120f), cityOnly = true),
        Cat("scene/structures", 600f, 330f, 40f, 24f, 4f, false, true, 20f,
            lengths = mapOf("tesla_super_charger" to 22f), cityOnly = true),
    )
    private val slots = HashMap<Cat, List<Slot>>()
    private val counts = HashMap<Cat, Int>()
    val total get() = slots.values.sumOf { it.size }

    private fun files(dir: String): List<String> =
        (assets.list(dir) ?: emptyArray()).filter { it.endsWith(".glb") }.sorted()

    private fun stem(f: String) = f.removeSuffix(".glb")

    /** Runs on the main thread (Filament calls). Adds the pivot nodes to [out]. */
    fun load(out: MutableList<Node>) {
        for (cat in cats) {
            val fs = files(cat.dir)
            if (fs.isEmpty()) continue
            val sides = if (cat.both) intArrayOf(-1, 1) else intArrayOf(1)
            val n = ceil((cat.ahead + cat.back) / cat.spacing).toInt() + 2
            // Choose which file each slot shows (cycling, honouring per-file caps), then load each file once, instanced.
            val plan = ArrayList<Pair<Int, String>>()     // (side, file)
            val used = HashMap<String, Int>()
            var cursor = 0
            for (side in sides) for (s in 0 until n) {
                var pick = fs[cursor % fs.size]; cursor++
                var guard = 0
                while ((used[pick] ?: 0) >= (cat.maxPerFile[stem(pick)] ?: Int.MAX_VALUE) && guard++ < fs.size) { pick = fs[cursor % fs.size]; cursor++ }
                used[pick] = (used[pick] ?: 0) + 1
                plan.add(side to pick)
            }
            val pools = HashMap<String, ArrayDeque<FilamentInstance>>()
            for ((f, c) in used) {
                val list = runCatching { loader.createInstancedModel("${cat.dir}/$f", c) }.getOrNull() ?: continue
                pools[f] = ArrayDeque(list)
            }
            val made = ArrayList<Slot>()
            for ((side, f) in plan) {
                val inst = pools[f]?.removeFirstOrNull() ?: continue
                val st = stem(f)
                val byLen = cat.lengths.containsKey(st)
                val target = cat.lengths[st] ?: cat.heights[st] ?: cat.defaultHeight
                val (pivot, radius) = fitModel(engine, inst, target, byLen)
                pivot.isVisible = false
                out.add(pivot)
                made.add(Slot(pivot, side, radius))
            }
            slots[cat] = made
            counts[cat] = n
        }
    }

    /** [pose] fills (x, z, yawDeg) of the road at distance d ahead and lateral offset lat from the car lane. */
    fun update(d0: Double, enabled: Boolean, highway: Boolean, pose: (Float, Float, FloatArray) -> Unit) {
        val out = FloatArray(3)
        for ((cat, list) in slots) {
            val n = counts[cat] ?: continue
            val perSide = if (cat.both) 2 else 1
            for ((idx, slot) in list.withIndex()) {
                val s = idx % n
                val nStart = floor((d0 - cat.back) / cat.spacing).toLong()
                val k = nStart + Math.floorMod(s.toLong() - nStart, n.toLong())
                val shift = if (slot.side < 0) cat.spacing * 0.5f else 0f
                val d = (k * cat.spacing + shift - d0).toFloat()
                val vis = enabled && !(highway && cat.cityOnly) && d >= -cat.back && d <= cat.ahead
                slot.pivot.isVisible = vis
                if (!vis) continue
                val j = hashf(k, slot.side + 3)
                val off = cat.edgeOffset + cat.jitter * j + (if (cat.centreTowardsRoad) -slot.radius else slot.radius * 1.05f)
                val leftEdge = if (highway) -18.45f else -5.55f
                val lat = if (slot.side > 0) 1.85f + off else leftEdge - off
                pose(d, lat, out)
                val yaw = out[2] + (if (cat.faceRoad) slot.side * 90f else hashf(k, 9) * 360f) + cat.yawFix
                slot.pivot.position = Position(out[0], 0f, out[1])
                slot.pivot.rotation = io.github.sceneview.math.Rotation(0f, yaw, 0f)
                if (cat.scaleJitter > 0f) {
                    val f = 1f + (hashf(k, 5) - 0.5f) * 2f * cat.scaleJitter
                    slot.pivot.scale = Scale(f, f, f)
                }
            }
            @Suppress("UNUSED_VARIABLE") val unused = perSide
        }
    }
}
