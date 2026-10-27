package com.musicafacil.player

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Arreglo de tildes y eñes mal leídas.
 *
 * Muchos programas guardan las etiquetas en UTF-8 pero las marcan como Latin-1,
 * y Android las muestra como "TRÃ‰" en lugar de "TRÉ". Otras veces el texto
 * está en Latin-1 donde se espera UTF-8 y sale "TR�". Aquí se detectan ambos casos:
 * el primero se repara directamente sobre el texto; el segundo leyendo la
 * etiqueta del archivo (MP3 o FLAC) y eligiendo la codificación correcta.
 */
object TextFix {

    private val cp1252: Charset = Charset.forName("windows-1252")

    /** Carácter de Windows-1252 -> byte original (€, ‰, “, …). */
    private val cp1252Reverse: Map<Char, Byte> by lazy {
        (0x80..0x9F).mapNotNull { b ->
            val c = String(byteArrayOf(b.toByte()), cp1252).single()
            if (c == '�') null else c to b.toByte()
        }.toMap()
    }

    // "Ã" o "Â" (inicio de una secuencia UTF-8 leída como Latin-1) seguido de un byte de continuación
    private val mojibake = Regex("[Â-ô][\u0080-¿€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ]")

    /** El texto tiene pinta de estar mal descodificado. */
    fun looksBroken(s: String): Boolean =
        '�' in s || s.any { it in '\u0080'..'\u009F' } || mojibake.containsMatchIn(s)

    /** Intenta reparar texto UTF-8 que se leyó como Latin-1 / Windows-1252. */
    fun repair(s: String): String {
        if (!mojibake.containsMatchIn(s)) return s
        val bytes = ByteArray(s.length)
        for ((i, c) in s.withIndex()) {
            bytes[i] = when {
                c.code < 0x100 -> c.code.toByte()
                else -> cp1252Reverse[c] ?: return s
            }
        }
        return strictUtf8(bytes) ?: s
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Bytes de texto de codificación dudosa: UTF-8 si es válido, si no Windows-1252. */
    fun decodeGuess(bytes: ByteArray): String =
        if (bytes.all { it >= 0 }) String(bytes, Charsets.US_ASCII)
        else strictUtf8(bytes) ?: String(bytes, cp1252)

    /** Devuelve la canción con el texto arreglado (o tal cual si estaba bien). */
    fun fix(context: Context, song: Song): Song {
        val fields = listOf(song.title, song.artist, song.album, song.albumArtist)
        if (fields.none(::looksBroken)) return song
        var s = song.copy(
            title = repair(song.title),
            artist = repair(song.artist),
            album = repair(song.album),
            albumArtist = repair(song.albumArtist),
        )
        // Si aún queda algo roto, la información se perdió al leerla: se va al archivo
        if (listOf(s.title, s.artist, s.album, s.albumArtist).any(::looksBroken)) {
            TagReader.read(context, song.uri)?.let { t ->
                s = s.copy(
                    title = t.title ?: s.title,
                    artist = t.artist ?: s.artist,
                    album = t.album ?: s.album,
                    albumArtist = t.albumArtist ?: t.artist ?: s.albumArtist,
                )
            }
        }
        return s
    }
}

/** Lector mínimo de etiquetas ID3v2 (MP3) y Vorbis comment (FLAC). */
object TagReader {

    data class Tags(val title: String?, val artist: String?, val album: String?, val albumArtist: String?)

    fun read(context: Context, uri: Uri): Tags? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            val magic = input.readN(4)
            when {
                magic.startsWith("ID3") -> readId3(magic, input)
                magic.startsWith("fLaC") -> readFlac(input)
                else -> null
            }
        }
    }.getOrNull()

    // --- ID3v2 ---

    private fun readId3(magic: ByteArray, input: InputStream): Tags? {
        val head = magic + input.readN(6)
        val version = head[3].toInt()
        val flags = head[5].toInt()
        val tagSize = syncsafe(head, 6)
        if (version !in 2..4) return null
        if (flags and 0x80 != 0) return null // desincronización de todo el tag: muy raro, se deja a Android
        var read = 0
        if (flags and 0x40 != 0 && version >= 3) {
            val ext = input.readN(4)
            val extSize = if (version == 4) syncsafe(ext, 0) - 4 else int32(ext, 0)
            input.skipN(extSize.toLong())
            read += 4 + extSize
        }
        val idLen = if (version == 2) 3 else 4
        val headerLen = if (version == 2) 6 else 10
        val wanted = if (version == 2) mapOf("TT2" to 0, "TP1" to 1, "TAL" to 2, "TP2" to 3)
        else mapOf("TIT2" to 0, "TPE1" to 1, "TALB" to 2, "TPE2" to 3)
        val values = arrayOfNulls<String>(4)

        while (read + headerLen <= tagSize && values.any { it == null }) {
            val fh = input.readN(headerLen)
            read += headerLen
            if (fh[0].toInt() == 0) break // relleno
            val id = String(fh, 0, idLen, Charsets.US_ASCII)
            val size = when (version) {
                2 -> ((fh[3].toInt() and 0xFF) shl 16) or ((fh[4].toInt() and 0xFF) shl 8) or (fh[5].toInt() and 0xFF)
                3 -> int32(fh, 4)
                else -> syncsafe(fh, 4)
            }
            if (size <= 0 || read + size > tagSize) break
            val slot = wanted[id]
            val formatFlags = if (version == 4) fh[9].toInt() else 0
            // Solo marcos de texto normales (sin comprimir, cifrar ni desincronizar)
            if (slot != null && size < 64 * 1024 && formatFlags and 0x0F == 0) {
                values[slot] = decodeText(input.readN(size))?.takeIf { it.isNotBlank() }
            } else {
                input.skipN(size.toLong())
            }
            read += size
        }
        return Tags(values[0], values[1], values[2], values[3])
    }

    private fun decodeText(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val body = data.copyOfRange(1, data.size)
        val text = when (data[0].toInt()) {
            0 -> TextFix.decodeGuess(body.trimNulls(1))
            1 -> String(body.trimNulls(2), Charsets.UTF_16)
            2 -> String(body.trimNulls(2), Charsets.UTF_16BE)
            3 -> TextFix.decodeGuess(body.trimNulls(1))
            else -> return null
        }
        // En ID3v2.4 varios valores van separados por \u0000: nos quedamos con el primero
        return text.split('\u0000').firstOrNull { it.isNotBlank() }?.trim()
    }

    // --- FLAC ---

    private fun readFlac(input: InputStream): Tags? {
        while (true) {
            val h = input.readN(4)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (type == 4) return parseVorbis(input.readN(len))
            input.skipN(len.toLong())
            if (last) return null
        }
    }

    private fun parseVorbis(b: ByteArray): Tags {
        var p = 0
        fun le32(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        p += le32() // vendor
        val count = le32()
        val map = HashMap<String, String>()
        repeat(count) {
            val len = le32()
            val entry = TextFix.decodeGuess(b.copyOfRange(p, p + len))
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) map.putIfAbsent(entry.substring(0, eq).uppercase(), entry.substring(eq + 1))
        }
        return Tags(map["TITLE"], map["ARTIST"], map["ALBUM"], map["ALBUMARTIST"] ?: map["ALBUM ARTIST"])
    }

    // --- utilidades ---

    private fun ByteArray.startsWith(s: String) = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }

    private fun ByteArray.trimNulls(width: Int): ByteArray {
        var end = size
        while (end >= width && (end - width until end).all { this[it].toInt() == 0 }) end -= width
        return copyOf(end)
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun InputStream.readN(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
        return out
    }

    private fun InputStream.skipN(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) {
                if (read() < 0) throw java.io.EOFException()
                left--
            } else left -= s
        }
    }
}
