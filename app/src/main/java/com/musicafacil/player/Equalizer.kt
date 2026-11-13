package com.musicafacil.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ajustes del ecualizador, compartidos entre la pantalla y el reproductor (mismo proceso). */
object EqSettings {
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    const val BANDS = 10
    const val MAX_DB = 12f

    @Volatile var enabled = false
        private set
    @Volatile var preamp = 0f
        private set
    @Volatile var gains = FloatArray(BANDS)
        private set
    var preset = "Plano"
        private set
    /** Cambia con cada ajuste: el procesador recalcula los filtros al verlo cambiar. */
    @Volatile var version = 0
        private set

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences("eq", Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", false)
        preamp = p.getFloat("preamp", 0f)
        gains = FloatArray(BANDS) { p.getFloat("band$it", 0f) }
        preset = p.getString("preset", "Plano") ?: "Plano"
        version++
    }

    fun update(context: Context, enabled: Boolean, preamp: Float, gains: FloatArray, preset: String) {
        this.enabled = enabled
        this.preamp = preamp
        this.gains = gains.copyOf()
        this.preset = preset
        version++
        context.getSharedPreferences("eq", Context.MODE_PRIVATE).edit().apply {
            putBoolean("enabled", enabled)
            putFloat("preamp", preamp)
            gains.forEachIndexed { i, g -> putFloat("band$i", g) }
            putString("preset", preset)
        }.apply()
    }

    val PRESETS: List<Pair<String, FloatArray>> = listOf(
        "Plano" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Graves" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Agudos" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Rock" to floatArrayOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 2f, 3f, 4f),
        "Pop" to floatArrayOf(-1f, 0f, 1f, 2f, 3f, 2f, 1f, 0f, -1f, -1f),
        "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Clásica" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Electrónica" to floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f),
        "Voz" to floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
        "Acústica" to floatArrayOf(3f, 2f, 1f, 1f, 2f, 2f, 3f, 3f, 2f, 1f),
        "Sonoridad" to floatArrayOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
    )
}

/**
 * Ecualizador de 10 bandas hecho a mano: un filtro de pico (biquad, fórmulas de
 * Robert Bristow-Johnson) por banda, aplicado a las muestras dentro de ExoPlayer.
 * Funciona igual en cualquier móvil y no interfiere con el gapless.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class EqualizerProcessor : BaseAudioProcessor() {

    private val bands = EqSettings.BANDS
    private var channels = 0
    private var sampleRate = 0
    private var isFloat = false
    private var version = -1

    private val b0 = FloatArray(bands)
    private val b1 = FloatArray(bands)
    private val b2 = FloatArray(bands)
    private val a1 = FloatArray(bands)
    private val a2 = FloatArray(bands)
    private var active = IntArray(0)
    private var pre = 1f
    /** Estado de cada filtro: por canal, 4 valores por banda (x1, x2, y1, y2). */
    private var state: Array<FloatArray> = emptyArray()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        state = Array(channels.coerceAtLeast(0)) { FloatArray(bands * 4) }
        version = -1
    }

    override fun onReset() {
        state = emptyArray()
        channels = 0
    }

    private fun recompute() {
        version = EqSettings.version
        val gains = EqSettings.gains
        pre = 10f.pow(EqSettings.preamp / 20f)
        val q = 1.41
        val list = ArrayList<Int>()
        for (k in 0 until bands) {
            val f = EqSettings.FREQS[k].toDouble()
            val g = gains.getOrElse(k) { 0f }.toDouble()
            if (abs(g) < 0.05 || f >= sampleRate * 0.45) continue
            val a = 10.0.pow(g / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1.0 + alpha / a
            b0[k] = ((1.0 + alpha * a) / a0).toFloat()
            b1[k] = ((-2.0 * c) / a0).toFloat()
            b2[k] = ((1.0 - alpha * a) / a0).toFloat()
            a1[k] = ((-2.0 * c) / a0).toFloat()
            a2[k] = ((1.0 - alpha / a) / a0).toFloat()
            list += k
        }
        active = list.toIntArray()
    }

    private fun process(ch: Int, input: Float): Float {
        var x = input * pre
        val st = state[ch]
        for (k in active) {
            val o = k * 4
            val y = b0[k] * x + b1[k] * st[o] + b2[k] * st[o + 1] - a1[k] * st[o + 2] - a2[k] * st[o + 3]
            st[o + 1] = st[o]
            st[o] = x
            st[o + 3] = st[o + 2]
            st[o + 2] = y
            x = y
        }
        return x.coerceIn(-1f, 1f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (!EqSettings.enabled || channels <= 0 || state.size != channels) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        if (version != EqSettings.version) recompute()
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (isFloat) {
            val frame = 4 * channels
            while (inputBuffer.remaining() >= frame) {
                for (ch in 0 until channels) out.putFloat(process(ch, inputBuffer.float))
            }
        } else {
            val frame = 2 * channels
            while (inputBuffer.remaining() >= frame) {
                for (ch in 0 until channels) {
                    val y = process(ch, inputBuffer.short / 32768f)
                    out.putShort((y * 32767f).roundToInt().toShort())
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
