package com.d3dclone.viewer

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Procedural image-based lighting. The original app ships pre-baked KTX cubemaps
 * (radiance / irradiance / skybox, day and night); this builds equivalents at run time so no
 * third-party texture is reused: a gradient sky, a horizon band, a dark ground and a few
 * "softbox" strips that give the paint its long reflections.
 */
object EnvironmentFactory {
    private const val SIZE = 128
    private const val LEVELS = 8          // 128 -> 1
    private const val IRR_SIZE = 8

    class Env(val reflections: Texture, val irradiance: Texture, val light: IndirectLight, val skybox: Skybox) {
        fun destroy(engine: Engine) {
            engine.destroyIndirectLight(light); engine.destroySkybox(skybox)
            engine.destroyTexture(reflections); engine.destroyTexture(irradiance)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t)
    }

    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 2147483647
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 65535f
    }

    /** Linear radiance (0..1+) seen along a direction. */
    private fun radiance(dx: Float, dy: Float, dz: Float, night: Boolean, out: FloatArray) {
        val up = dy
        val r: Float; val g: Float; val b: Float
        if (!night) {
            if (up >= 0f) {
                val t = smooth(0f, 0.9f, up)
                r = lerp(0.80f, 0.20f, t); g = lerp(0.88f, 0.42f, t); b = lerp(0.98f, 0.85f, t)
            } else {
                val t = smooth(0f, -0.6f, up)
                r = lerp(0.55f, 0.16f, t); g = lerp(0.56f, 0.17f, t); b = lerp(0.58f, 0.18f, t)
            }
            out[0] = r; out[1] = g; out[2] = b
            // Sun glow and overhead softboxes.
            val sun = max(0f, dx * 0.45f + dy * 0.75f + dz * 0.48f).pow(40f)
            out[0] += sun * 2.2f; out[1] += sun * 2.0f; out[2] += sun * 1.6f
            if (up > 0.55f) { val s = smooth(0.55f, 0.7f, up) * 0.8f; out[0] += s; out[1] += s; out[2] += s }
            val strip = smooth(0.82f, 0.9f, abs(dx)) * smooth(-0.1f, 0.2f, up) * 0.9f
            out[0] += strip; out[1] += strip; out[2] += strip
        } else {
            if (up >= 0f) {
                val t = smooth(0f, 0.9f, up)
                r = lerp(0.045f, 0.004f, t); g = lerp(0.06f, 0.008f, t); b = lerp(0.12f, 0.03f, t)
            } else {
                r = 0.012f; g = 0.013f; b = 0.02f
            }
            out[0] = r; out[1] = g; out[2] = b
            val moon = max(0f, -dx * 0.3f + dy * 0.8f - dz * 0.5f).pow(60f)
            out[0] += moon * 0.7f; out[1] += moon * 0.75f; out[2] += moon * 0.9f
            // Cold overhead strip and a couple of warm street lamps on the horizon.
            if (up > 0.7f) { val s = smooth(0.7f, 0.85f, up) * 0.16f; out[0] += s; out[1] += s * 1.1f; out[2] += s * 1.5f }
            val lamp = smooth(0.93f, 0.98f, abs(dx)) * smooth(0.02f, 0.12f, up) * (1f - smooth(0.12f, 0.25f, up))
            out[0] += lamp * 1.1f; out[1] += lamp * 0.7f; out[2] += lamp * 0.3f
        }
    }

    /** Direction of texel (s,t in 0..1) on a cube face, in the OpenGL cubemap convention. */
    private fun faceDir(face: Int, s: Float, t: Float, out: FloatArray) {
        val sc = 2f * s - 1f; val tc = 2f * t - 1f
        var x = 0f; var y = 0f; var z = 0f
        when (face) {
            0 -> { x = 1f; z = -sc; y = -tc }
            1 -> { x = -1f; z = sc; y = -tc }
            2 -> { y = 1f; x = sc; z = tc }
            3 -> { y = -1f; x = sc; z = -tc }
            4 -> { z = 1f; x = sc; y = -tc }
            else -> { z = -1f; x = -sc; y = -tc }
        }
        val l = sqrt(x * x + y * y + z * z)
        out[0] = x / l; out[1] = y / l; out[2] = z / l
    }

    private fun pack(v: Float): Byte =
        (((v.coerceIn(0f, 1f)).pow(1f / 2.2f)) * 255f + 0.5f).toInt().toByte()

    private fun unpack(b: Byte): Float = ((b.toInt() and 0xff) / 255f).pow(2.2f)

    fun build(engine: Engine, night: Boolean): Env {
        // Level 0: sample the analytic environment (HDR values are tone-clamped into RGBA8).
        val faces0 = Array(6) { ByteArray(SIZE * SIZE * 4) }
        val d = FloatArray(3); val c = FloatArray(3)
        for (f in 0 until 6) for (y in 0 until SIZE) for (x in 0 until SIZE) {
            faceDir(f, (x + 0.5f) / SIZE, (y + 0.5f) / SIZE, d)
            radiance(d[0], d[1], d[2], night, c)
            // Compress highlights so the clamp keeps their shape.
            val k = ((y * SIZE + x) * 4)
            for (i in 0..2) faces0[f][k + i] = pack(c[i] / (1f + c[i] * 0.35f) * 1.35f)
            faces0[f][k + 3] = -1
            if (night) {
                // sparse stars on the upper hemisphere
                if (d[1] > 0.1f && hash(x, y, f) > 0.9965f) {
                    for (i in 0..2) faces0[f][k + i] = 255.toByte()
                }
            }
        }

        // Mip chain by 2x2 box filtering: rougher reflections sample blurrier mips.
        val chain = ArrayList<Array<ByteArray>>()
        chain.add(faces0)
        var sz = SIZE
        for (l in 1 until LEVELS) {
            val ns = sz / 2
            val prev = chain[l - 1]
            val next = Array(6) { ByteArray(ns * ns * 4) }
            for (f in 0 until 6) for (y in 0 until ns) for (x in 0 until ns) for (ch in 0..2) {
                var sum = 0f
                for (dy in 0..1) for (dx in 0..1) sum += unpack(prev[f][(((y * 2 + dy) * sz) + (x * 2 + dx)) * 4 + ch])
                next[f][(y * ns + x) * 4 + ch] = pack(sum / 4f)
                next[f][(y * ns + x) * 4 + 3] = -1
            }
            chain.add(next); sz = ns
        }

        val refl = Texture.Builder()
            .width(SIZE).height(SIZE).levels(LEVELS)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
        sz = SIZE
        for (l in 0 until LEVELS) {
            val buf = ByteBuffer.allocateDirect(sz * sz * 4 * 6)
            for (f in 0 until 6) buf.put(chain[l][f])
            buf.rewind()
            refl.setImage(engine, l, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE), IntArray(6) { it * sz * sz * 4 })
            sz = max(1, sz / 2)
        }

        // Diffuse irradiance: cosine-weighted blur into a tiny cubemap, computed brute force from level 3.
        val src = chain[3]; val ssz = SIZE shr 3
        val irr = Texture.Builder()
            .width(IRR_SIZE).height(IRR_SIZE).levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
        val ibuf = ByteBuffer.allocateDirect(IRR_SIZE * IRR_SIZE * 4 * 6)
        val sd = FloatArray(3)
        for (f in 0 until 6) for (y in 0 until IRR_SIZE) for (x in 0 until IRR_SIZE) {
            faceDir(f, (x + 0.5f) / IRR_SIZE, (y + 0.5f) / IRR_SIZE, d)
            var r = 0f; var g = 0f; var b = 0f; var wsum = 0f
            for (sf in 0 until 6) for (sy in 0 until ssz) for (sx in 0 until ssz) {
                faceDir(sf, (sx + 0.5f) / ssz, (sy + 0.5f) / ssz, sd)
                val w = max(0f, d[0] * sd[0] + d[1] * sd[1] + d[2] * sd[2])
                if (w <= 0f) continue
                val i = (sy * ssz + sx) * 4
                r += unpack(src[sf][i]) * w; g += unpack(src[sf][i + 1]) * w; b += unpack(src[sf][i + 2]) * w
                wsum += w
            }
            val k = ((f * IRR_SIZE + y) * IRR_SIZE + x) * 4
            ibuf.put(k, pack(r / wsum)); ibuf.put(k + 1, pack(g / wsum)); ibuf.put(k + 2, pack(b / wsum)); ibuf.put(k + 3, -1)
        }
        ibuf.rewind()
        irr.setImage(engine, 0, Texture.PixelBufferDescriptor(ibuf, Texture.Format.RGBA, Texture.Type.UBYTE), IntArray(6) { it * IRR_SIZE * IRR_SIZE * 4 })

        val light = IndirectLight.Builder()
            .reflections(refl)
            .irradiance(irr)
            .intensity(if (night) 20_000f else 38_000f)
            .build(engine)
        val sky = Skybox.Builder().environment(refl).showSun(false).build(engine)
        return Env(refl, irr, light, sky)
    }
}
