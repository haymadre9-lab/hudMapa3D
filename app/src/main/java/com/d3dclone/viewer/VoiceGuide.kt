package com.d3dclone.viewer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Spoken guidance with the system's text-to-speech (Spanish): route manoeuvres, speed cameras and traffic ahead.
 * Called every frame from the scene; it remembers what it already said so each thing is announced once per stage.
 */
class VoiceGuide(ctx: Context) : TextToSpeech.OnInitListener {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(ctx.applicationContext, this)
    @Volatile var ready = false

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val r = t.setLanguage(Locale("es", "ES"))
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        t.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { focus(true) }
            override fun onDone(id: String?) { focus(false) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { focus(false) }
        })
    }

    @Suppress("DEPRECATION")
    private fun focus(on: Boolean) {
        if (on) am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        else am.abandonAudioFocus(null)
    }

    fun say(text: String, urgent: Boolean = false) {
        Log.d("D3DVOICE", text)
        if (!ready) return
        tts?.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "d3d" + SystemClock.elapsedRealtime())
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null; ready = false }

    // ------------------------------------------------------------------ what to say
    private var last = 0L
    private val stage = HashMap<Int, Int>()          // manoeuvre id -> 0 none, 1 far said, 2 near said
    private val saidKeys = HashSet<String>()
    private var radarId = ""; private var radarPrevD = 1e9f; private var radarFar = false; private var radarNear = false; private var radarWarn = 0L
    private var lastTrafficKey = ""

    fun reset() { stage.clear(); saidKeys.clear(); radarId = ""; lastTrafficKey = "" }

    private fun dist(m: Double): String {
        if (m >= 950) { val km = Math.round(m / 100.0) / 10.0; return if (km == Math.floor(km)) km.toInt().toString() + (if (km.toInt() == 1) " kilómetro" else " kilómetros") else String.format(Locale("es"), "%.1f", km) + " kilómetros" }
        val r = (Math.round(m / 50.0) * 50).toInt().coerceAtLeast(50)
        return "$r metros"
    }

    private val ORD = listOf("", "primera", "segunda", "tercera", "cuarta", "quinta", "sexta")

    private fun action(st: Step): String {
        val por = if (st.text.isBlank()) "" else " por " + st.text
        return when (st.kind) {
            "L" -> "gira a la izquierda$por"
            "R" -> "gira a la derecha$por"
            "SL" -> "gira ligeramente a la izquierda$por"
            "SR" -> "gira ligeramente a la derecha$por"
            "S" -> "sigue recto$por"
            "U" -> "haz un cambio de sentido"
            "X" -> "toma la salida" + (if (st.exitNo > 0) " número " + st.exitNo else "") + (if (st.text.isBlank()) "" else " hacia " + st.text)
            "RB" -> when {
                st.exitNo in 1..6 -> "en la rotonda, toma la " + ORD[st.exitNo] + " salida" + por
                st.exitNo > 0 -> "en la rotonda, toma la salida número " + st.exitNo + por
                else -> "en la rotonda, toma la salida indicada" + por
            }
            else -> "continúa$por"
        }
    }

    private fun radarWord(kind: String) = when (kind) { "fijo" -> "fijo"; "movil" -> "móvil"; "tramo" -> "de tramo"; "semaforo" -> "de semáforo"; else -> "" }

    fun update(s: ViewerState, rt: Route?, sN: Double, items: List<RoadData.Item>, travelled: Float) {
        if (!s.voice || !ready) return
        val now = SystemClock.elapsedRealtime()
        if (now - last < 250) return
        last = now

        // --- route manoeuvres
        val nx = rt?.next(sN)
        if (nx != null) {
            val st = nx.first; val d = nx.second
            val id = st.offset.toInt()
            val far = if (s.highway) 1000.0 else 400.0
            val near = if (s.highway) 400.0 else 150.0
            val done = stage[id] ?: 0
            if (st.kind == "ARRIVE") {
                if (d <= 70 && done < 2) { stage[id] = 2; say("Has llegado a tu destino") }
                else if (d <= far && d > 150 && done < 1) { stage[id] = 1; say("Tu destino está a " + dist(d)) }
            } else if (d <= near && done < 2) {
                stage[id] = 2
                say(action(st).replaceFirstChar { it.uppercase() })
            } else if (d <= far && d > near * 1.5 && done < 1) {
                stage[id] = 1
                say("En " + dist(d) + ", " + action(st))
            }
        }

        // --- speed cameras
        var rd = 0f; var ri: RoadData.Item? = null
        for (it in items) { val d = it.dist - travelled; if (it.kind == 'r' && d > 0f && d < 700f) { rd = d; ri = it; break } }
        if (ri == null) { radarId = ""; radarPrevD = 1e9f } else {
            val id = ri.max.toString() + "/" + ri.label
            if (id != radarId || rd > radarPrevD + 80f) { radarId = id; radarFar = false; radarNear = false; radarWarn = 0L }
            radarPrevD = rd
            val lim = ri.max.takeIf { it > 0 } ?: s.limit ?: 0
            val kind = radarWord(ri.label)
            if (rd <= 600f && !radarFar) {
                radarFar = true
                say("Radar " + kind + " a " + dist(rd.toDouble()) + (if (lim > 0) ", límite " + lim else ""))
            } else if (rd <= 250f && !radarNear) { radarNear = true; say("Radar a " + dist(rd.toDouble())) }
            if (lim > 0 && s.speedKmh > lim + 2f && rd < 500f && now - radarWarn > 9000) { radarWarn = now; say("Reduce la velocidad, límite " + lim, urgent = true) }
        }

        // --- traffic ahead
        if (rt != null && s.trafficLevel > 0 && s.trafficDist <= 3000) {
            val key = "t" + (Math.round((sN + s.trafficDist) / 200.0)) + "/" + s.trafficLevel
            if (key != lastTrafficKey) {
                lastTrafficKey = key
                val what = when (s.trafficLevel) { 1 -> "Algo de tráfico"; 2 -> "Tráfico denso"; else -> "Atención, caravana o corte de tráfico" }
                val where = if (s.trafficDist <= 30) " en tu ruta" else " dentro de " + dist(s.trafficDist.toDouble())
                val delay = if (s.trafficDelayMin > 0) ", " + s.trafficDelayMin + (if (s.trafficDelayMin == 1) " minuto" else " minutos") + " de retraso" else ""
                say(what + where + delay)
                val alt = s.alts.minByOrNull { it.travelSec }
                val save = if (alt != null && s.curTravelSec > 0) (s.curTravelSec - alt.travelSec) / 60 else 0
                if (save >= 2 && saidKeys.add("alt" + alt!!.travelSec)) say("Hay una ruta alternativa que ahorra " + save + " minutos. Puedes elegirla en la pantalla.")
            }
        } else if (s.trafficLevel == 0) lastTrafficKey = ""
    }
}
