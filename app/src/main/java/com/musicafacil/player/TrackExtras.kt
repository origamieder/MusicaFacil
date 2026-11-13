package com.musicafacil.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Datos técnicos de la canción que suena: "FLAC · 24 bit · 96 kHz · 2304 kbps". */
object TrackFormat {

    private val cache = LruCache<Long, String>(200)

    suspend fun describe(context: Context, song: Song): String? {
        cache.get(song.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, song.uri)
                    val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                    var rate: Int? = null
                    var bits: Int? = null
                    if (Build.VERSION.SDK_INT >= 31) {
                        rate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                        bits = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                    }
                    if (rate == null) rate = sampleRateFromExtractor(context, song)
                    val codec = song.path?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.length in 2..5 }
                        ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.substringAfter('/')?.uppercase()
                    listOfNotNull(
                        codec,
                        bits?.takeIf { it > 0 }?.let { "$it bit" },
                        rate?.takeIf { it > 0 }?.let { khz(it) },
                        bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
                    ).joinToString(" · ").ifEmpty { null }
                } finally {
                    r.release()
                }
            }.getOrNull()?.also { cache.put(song.id, it) }
        }
    }

    private fun sampleRateFromExtractor(context: Context, song: Song): Int? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, song.uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } finally {
            ex.release()
        }
    }.getOrNull()

    private fun khz(rate: Int): String {
        val k = rate / 1000.0
        return if (k % 1.0 == 0.0) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    }
}

/**
 * Forma de onda real de la canción para la barra de progreso: se decodifica el audio
 * entero (en segundo plano, más rápido que tiempo real) y se guarda el pico de cada tramo.
 */
object Waveform {

    const val BARS = 72
    private val cache = LruCache<Long, FloatArray>(60)

    fun cached(songId: Long): FloatArray? = cache.get(songId)

    suspend fun get(context: Context, song: Song): FloatArray? {
        cache.get(song.id)?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching { compute(context, song) }.getOrNull()
        }?.also { cache.put(song.id, it) }
    }

    private suspend fun compute(context: Context, song: Song): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, song.uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
            else song.durationMs * 1000
            if (durationUs <= 0) return null

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val peaks = FloatArray(BARS)
            val info = MediaCodec.BufferInfo()
            var floatPcm = false
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive() // se cancela si cambia la canción
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 5000)
                when {
                    outIdx >= 0 -> {
                        val out = dec.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0) {
                            val bar = ((info.presentationTimeUs.toDouble() / durationUs) * BARS).toInt().coerceIn(0, BARS - 1)
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val ordered = out.slice().order(ByteOrder.LITTLE_ENDIAN)
                            var peak = peaks[bar]
                            if (floatPcm) {
                                val fb = ordered.asFloatBuffer()
                                var i = 0
                                while (i < fb.limit()) { val v = abs(fb.get(i)); if (v > peak) peak = v; i += 8 }
                            } else {
                                val sb = ordered.asShortBuffer()
                                var i = 0
                                while (i < sb.limit()) { val v = abs(sb.get(i).toInt()) / 32768f; if (v > peak) peak = v; i += 8 }
                            }
                            peaks[bar] = peak
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
            val max = peaks.maxOrNull()?.takeIf { it > 0f } ?: return null
            return FloatArray(BARS) { (peaks[it] / max).coerceIn(0f, 1f) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
