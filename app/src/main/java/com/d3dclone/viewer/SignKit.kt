package com.d3dclone.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import io.github.sceneview.loaders.MaterialLoader
import kotlin.math.cos
import kotlin.math.sin

/** Road signs drawn at run time into textures (no image files needed), cached by content. */
class SignKit(private val engine: Engine, private val ml: MaterialLoader) {
    private val cache = HashMap<String, MaterialInstance>()
    private val fallback = ml.createColorInstance(Color(0xFFCCCCCC), 0f, 0.8f, 0.3f)

    private fun get(key: String, w: Int, h: Int, draw: (Canvas, Int, Int) -> Unit): MaterialInstance = cache.getOrPut(key) {
        runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(w.toFloat(), 0f); c.scale(-1f, 1f)       // the cube face samples the image mirrored
            draw(c, w, h)
            val tex = Texture.Builder().width(w).height(h).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8).build(engine)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()
            ml.createImageInstance(tex, TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
        }.getOrElse { fallback }
    }

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, stroke: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style; strokeWidth = stroke }

    private fun text(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, bold: Boolean = true) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; textAlign = Paint.Align.CENTER
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        c.drawText(s, cx, cy - (p.descent() + p.ascent()) / 2f, p)
    }

    fun limit(n: Int, approx: Boolean = false) = get("lim$n$approx", 256, 256) { c, w, h ->
        val r = w / 2f
        c.drawCircle(r, r, r - 4, paint(0xFFFFFFFF.toInt()))
        c.drawCircle(r, r, r - 4 - 17, paint(0xFFD6191E.toInt(), Paint.Style.STROKE, 34f))
        text(c, n.toString(), r, r, if (n >= 100) 92f else 110f, 0xFF111111.toInt())
        if (approx) text(c, "~", r, r + 78f, 40f, 0xFF555555.toInt())
    }

    fun stop() = get("stop", 256, 256) { c, w, h ->
        val p = Path(); val r = w / 2f - 6
        for (i in 0 until 8) { val a = Math.toRadians(22.5 + 45.0 * i); val x = w / 2f + (r * cos(a)).toFloat(); val y = h / 2f + (r * sin(a)).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        p.close()
        c.drawPath(p, paint(0xFFFFFFFF.toInt())); c.save(); c.scale(0.9f, 0.9f, w / 2f, h / 2f); c.drawPath(p, paint(0xFFC8102E.toInt())); c.restore()
        text(c, "STOP", w / 2f, h / 2f, 78f, 0xFFFFFFFF.toInt())
    }

    fun ceda() = get("ceda", 256, 256) { c, w, h ->
        val p = Path(); p.moveTo(10f, 40f); p.lineTo(w - 10f, 40f); p.lineTo(w / 2f, h - 20f); p.close()
        c.drawPath(p, paint(0xFFFFFFFF.toInt()))
        c.drawPath(p, paint(0xFFD6191E.toInt(), Paint.Style.STROKE, 26f))
        text(c, "CEDA", w / 2f, 100f, 40f, 0xFF111111.toInt())
    }

    fun crossing() = get("paso", 256, 256) { c, w, h ->
        c.drawRoundRect(RectF(6f, 6f, w - 6f, h - 6f), 24f, 24f, paint(0xFF0A58A8.toInt()))
        val p = Path(); p.moveTo(w / 2f, 34f); p.lineTo(w - 40f, h - 44f); p.lineTo(40f, h - 44f); p.close()
        c.drawPath(p, paint(0xFFFFFFFF.toInt()))
        text(c, "🚶", w / 2f, h / 2f + 34f, 96f, 0xFF111111.toInt())
    }

    fun radar(max: Int, kind: String) = get("rad$max$kind", 256, 256) { c, w, h ->
        c.drawRoundRect(RectF(6f, 6f, w - 6f, h - 6f), 24f, 24f, paint(0xFF0A58A8.toInt()))
        text(c, "RADAR", w / 2f, 52f, 50f, 0xFFFFFFFF.toInt())
        text(c, "📷", w / 2f, 128f, 80f, 0xFFFFFFFF.toInt())
        text(c, if (max > 0) "$max" else kind.uppercase(), w / 2f, 210f, 62f, 0xFFFFE066.toInt())
    }

    private fun headTri(c: Canvas, color: Int, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }
        c.drawPath(p, paint(color))
    }

    /** Plain block arrow (motorway style, M1): points right at angle 0, rotated clockwise by deg. */
    private fun plainArrow(c: Canvas, cx: Float, cy: Float, u: Float, deg: Float, color: Int) {
        c.save(); c.translate(cx, cy); c.rotate(deg)
        val p = Path().apply {
            moveTo(-55 * u, -12 * u); lineTo(8 * u, -12 * u); lineTo(8 * u, -34 * u); lineTo(55 * u, 0f)
            lineTo(8 * u, 34 * u); lineTo(8 * u, 12 * u); lineTo(-55 * u, 12 * u); close()
        }
        c.drawPath(p, paint(color)); c.restore()
    }

    /** Road style B: a thick bent arrow for turns, plus straight / slight / U-turn variants. */
    private fun turnArrow(c: Canvas, cx: Float, cy: Float, u: Float, kind: String, color: Int) {
        val st = paint(color, Paint.Style.STROKE, 17 * u).apply { strokeJoin = Paint.Join.MITER; strokeCap = Paint.Cap.BUTT }
        c.save(); c.translate(cx, cy)
        when (kind) {
            "R", "L" -> {
                if (kind == "L") c.scale(-1f, 1f)
                val p = Path().apply { moveTo(-30 * u, 58 * u); lineTo(-30 * u, -8 * u); lineTo(16 * u, -8 * u) }
                c.drawPath(p, st)
                headTri(c, color, 12 * u, -36 * u, 56 * u, -8 * u, 12 * u, 20 * u)
            }
            "U" -> {
                val p = Path().apply {
                    moveTo(-24 * u, 58 * u); lineTo(-24 * u, -6 * u)
                    arcTo(RectF(-24 * u, -52 * u, 24 * u, -4 * u), 180f, 180f, false)
                    lineTo(24 * u, 22 * u)
                }
                c.drawPath(p, st)
                headTri(c, color, -2 * u, 18 * u, 50 * u, 18 * u, 24 * u, 54 * u)
            }
            else -> {
                c.rotate(when (kind) { "SR", "X" -> 32f; "SL" -> -32f; else -> 0f })
                c.drawLine(0f, 60 * u, 0f, -18 * u, st)
                headTri(c, color, -28 * u, -16 * u, 0f, -60 * u, 28 * u, -16 * u)
            }
        }
        c.restore()
    }

    /** Roundabout R2: ring plus exit stubs; the exit to take is thick with an arrowhead. Counter-clockwise from the entry. */
    private fun roundaboutIcon(c: Canvas, cx: Float, cy: Float, u: Float, exitNo: Int, color: Int) {
        val grey = 0xFFAAAAAA.toInt()
        val chosen = when (exitNo) { 1 -> 0; 3 -> 2; else -> 1 }        // 0 right, 1 ahead, 2 left
        val ang = floatArrayOf(0f, -90f, 180f)                          // direction of each exit stub
        c.drawCircle(cx, cy, 30 * u, paint(color, Paint.Style.STROKE, 9 * u))
        for (i in 0..2) {
            val a = Math.toRadians(ang[i].toDouble())
            val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
            val x1 = cx + dx * 30 * u; val y1 = cy + dy * 30 * u
            val x2 = cx + dx * 66 * u; val y2 = cy + dy * 66 * u
            if (i == chosen) {
                c.drawLine(x1, y1, cx + dx * 56 * u, cy + dy * 56 * u, paint(color, Paint.Style.STROKE, 14 * u))
                c.save(); c.translate(x2, y2); c.rotate(ang[i])
                headTri(c, color, 0f, 0f, -20 * u, -16 * u, -20 * u, 16 * u)
                c.restore()
            } else c.drawLine(x1, y1, x2, y2, paint(grey, Paint.Style.STROKE, 9 * u))
        }
        c.drawLine(cx, cy + 30 * u, cx, cy + 66 * u, paint(color, Paint.Style.STROKE, 11 * u))     // entry
    }

    private fun fitText(c: Canvas, s: String, cx: Float, cy: Float, maxW: Float, size0: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        var size = size0
        p.textSize = size
        while (p.measureText(s) > maxW && size > 26f) { size -= 3f; p.textSize = size }
        var t = s
        while (p.measureText(t) > maxW && t.length > 3) t = t.dropLast(2)
        if (t != s) t = t.trimEnd() + "…"
        c.drawText(t, cx, cy - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun motorwayAngle(kind: String) = when (kind) { "R" -> 0f; "L" -> 180f; "SR", "X" -> -35f; "SL" -> -145f; "U" -> 180f; else -> -90f }

    /** Roadside direction plate. Road: white, black letters, bent arrow (B) and roundabout with exits (R2). Motorway: blue, white letters, exit number (M2). */
    fun direction(motorway: Boolean, kind: String, text: String, exitNo: Int = 0) = get("dir" + motorway + kind + text + exitNo, 760, 400) { c, w, h ->
        val bg = if (motorway) 0xFF0A4FA3.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (motorway) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 30f, 30f, paint(bg))
        c.drawRoundRect(RectF(18f, 18f, w - 18f, h - 18f), 22f, 22f, paint(fg, Paint.Style.STROKE, 8f))
        val cy = h / 2f
        when {
            kind == "ARRIVE" -> { text(c, "🏁", 150f, cy, 150f, fg); fitText(c, text, 500f, cy, 410f, 80f, fg) }
            motorway && kind == "X" && exitNo > 0 -> {      // M2: exit number + exit arrow + destination
                text(c, exitNo.toString(), 150f, 120f, 110f, fg)
                plainArrow(c, 150f, 290f, 1.2f, -35f, fg)
                fitText(c, "Salida " + exitNo, 500f, 130f, 410f, 84f, fg)
                fitText(c, text, 500f, 270f, 410f, 62f, 0xFFCFE3FF.toInt())
            }
            motorway -> { plainArrow(c, 150f, cy, 1.9f, motorwayAngle(kind), fg); fitText(c, text, 500f, cy, 410f, 80f, fg) }
            kind == "RB" -> {
                roundaboutIcon(c, 150f, cy - 4f, 1.7f, exitNo, fg)
                fitText(c, text.ifBlank { "Rotonda" }, 500f, cy, 410f, 80f, fg)
            }
            else -> { turnArrow(c, 150f, cy - 4f, 1.9f, kind, fg); fitText(c, text, 500f, cy, 410f, 80f, fg) }
        }
    }

    /** Motorway gantry panel (blue, white letters): the route direction (block arrow), or the road and its limit. */
    fun gantry(kind: String, title: String, limit: Int?) = get("gan" + kind + title + limit, 1024, 256) { c, w, h ->
        c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 26f, 26f, paint(0xFF0A4FA3.toInt()))
        c.drawRoundRect(RectF(16f, 16f, w - 16f, h - 16f), 20f, 20f, paint(0xFFFFFFFF.toInt(), Paint.Style.STROKE, 6f))
        val left = if (kind.isNotEmpty()) 230f else 60f
        if (kind.isNotEmpty()) plainArrow(c, 120f, h / 2f, 1.15f, motorwayAngle(kind), 0xFFFFFFFF.toInt())
        val right = if (limit != null) w - 270f else w - 60f
        fitText(c, title, (left + right) / 2f, h / 2f, right - left, 100f, 0xFFFFFFFF.toInt())
        if (limit != null) {
            val cx = w - 150f; val cy = h / 2f; val r = 88f
            c.drawCircle(cx, cy, r, paint(0xFFFFFFFF.toInt()))
            c.drawCircle(cx, cy, r - 9, paint(0xFFD6191E.toInt(), Paint.Style.STROKE, 18f))
            text(c, limit.toString(), cx, cy, 72f, 0xFF111111.toInt())
        }
    }
}
