package com.musicafacil.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Escribe título, artista, álbum y artista del álbum en las etiquetas (Vorbis comment)
 * de un archivo FLAC.
 *
 * Si el bloque nuevo cabe en el espacio de metadatos que ya existe (los programas de
 * etiquetado suelen dejar relleno), se reescribe solo esa parte del archivo, sin tocar
 * el audio. Si no cabe, se genera el archivo completo en una copia temporal y se vuelca
 * encima del original.
 */
object FlacTagWriter {

    /** null = no cambiar; texto vacío = borrar la etiqueta. */
    data class Changes(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        /** Se escribe en DATE */
        val year: String? = null,
    )

    private class Block(val type: Int, val data: ByteArray)

    private const val STREAMINFO = 0
    private const val PADDING = 1
    private const val VORBIS_COMMENT = 4
    private const val MAX_BLOCK = 0xFFFFFF

    fun write(context: Context, uri: Uri, changes: Changes) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("No se puede abrir")
        pfd.use {
            // Los streams creados desde un FileDescriptor no lo cierran; lo cierra pfd.use
            val input = FileInputStream(it.fileDescriptor).channel
            val output = FileOutputStream(it.fileDescriptor).channel
            write(input, output, changes, context.cacheDir)
        }
    }

    /** Núcleo sin dependencias de Android (se puede probar en el PC). */
    fun write(input: FileChannel, output: FileChannel, changes: Changes, tmpDir: File) {
        run {
            if (String(readAt(input, 0, 4), Charsets.US_ASCII) != "fLaC") throw IOException("No es un FLAC")

            // Lee todos los bloques de metadatos (el relleno se descarta y se recalcula)
            val blocks = ArrayList<Block>()
            var pos = 4L
            var last = false
            while (!last) {
                val h = readAt(input, pos, 4)
                last = h[0].toInt() and 0x80 != 0
                val type = h[0].toInt() and 0x7F
                val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                pos += 4
                if (type != PADDING) blocks += Block(type, readAt(input, pos, len))
                pos += len
            }
            val audioStart = pos
            val oldArea = audioStart - 4
            if (blocks.firstOrNull()?.type != STREAMINFO) throw IOException("FLAC no válido")

            val i = blocks.indexOfFirst { b -> b.type == VORBIS_COMMENT }
            val vorbis = Block(VORBIS_COMMENT, buildVorbis(blocks.getOrNull(i)?.data, changes))
            if (i >= 0) blocks[i] = vorbis else blocks.add(1, vorbis)
            if (blocks.any { b -> b.data.size > MAX_BLOCK }) throw IOException("Bloque demasiado grande")

            val used = blocks.sumOf { b -> 4L + b.data.size }
            if (used == oldArea || used + 4 <= oldArea) {
                // Cabe: se reescriben solo los metadatos, con el mismo tamaño total
                val padding = if (used == oldArea) null else (oldArea - used - 4).toInt()
                if (padding != null && padding > MAX_BLOCK) throw IOException("Relleno demasiado grande")
                val meta = serialize(blocks, padding)
                writeAt(output, 4, meta)
                output.force(true)
            } else {
                // No cabe: archivo nuevo en una copia temporal y se vuelca sobre el original
                val meta = serialize(blocks, 8192)
                val tmp = File.createTempFile("tag", ".flac", tmpDir)
                try {
                    FileOutputStream(tmp).use { out ->
                        out.write("fLaC".toByteArray(Charsets.US_ASCII))
                        out.write(meta)
                        val ch = out.channel
                        val size = input.size()
                        var off = audioStart
                        while (off < size) off += input.transferTo(off, size - off, ch)
                    }
                    val expected = 4 + meta.size + (input.size() - audioStart)
                    if (tmp.length() != expected) throw IOException("Copia incompleta")
                    FileInputStream(tmp).channel.use { src ->
                        output.position(0)
                        var off = 0L
                        val size = src.size()
                        while (off < size) off += src.transferTo(off, size - off, output)
                    }
                    output.force(true)
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    /** Copia el bloque de etiquetas cambiando solo los campos indicados. */
    private fun buildVorbis(old: ByteArray?, c: Changes): ByteArray {
        var vendor = "EDplayer".toByteArray()
        val kept = ArrayList<ByteArray>()
        val replace = buildSet {
            if (c.title != null) add("TITLE")
            if (c.artist != null) add("ARTIST")
            if (c.album != null) add("ALBUM")
            if (c.albumArtist != null) { add("ALBUMARTIST"); add("ALBUM ARTIST") }
            if (c.year != null) { add("DATE"); add("YEAR") }
        }
        if (old != null) {
            val b = ByteBuffer.wrap(old).order(ByteOrder.LITTLE_ENDIAN)
            vendor = ByteArray(b.int).also { b.get(it) }
            repeat(b.int) {
                val entry = ByteArray(b.int).also { b.get(it) }
                val key = String(entry, Charsets.UTF_8).substringBefore('=').uppercase()
                if (key !in replace) kept += entry
            }
        }
        fun add(key: String, value: String?) {
            val v = value?.trim()
            if (!v.isNullOrEmpty()) kept += "$key=$v".toByteArray(Charsets.UTF_8)
        }
        add("TITLE", c.title)
        add("ARTIST", c.artist)
        add("ALBUM", c.album)
        add("ALBUMARTIST", c.albumArtist)
        add("DATE", c.year)

        val size = 4 + vendor.size + 4 + kept.sumOf { 4 + it.size }
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(vendor.size).put(vendor).putInt(kept.size)
        kept.forEach { out.putInt(it.size).put(it) }
        return out.array()
    }

    private fun serialize(blocks: List<Block>, padding: Int?): ByteArray {
        val all = if (padding != null) blocks + Block(PADDING, ByteArray(padding)) else blocks
        val out = ByteBuffer.allocate(all.sumOf { 4 + it.data.size })
        all.forEachIndexed { idx, b ->
            val lastFlag = if (idx == all.lastIndex) 0x80 else 0
            out.put((lastFlag or b.type).toByte())
            out.put((b.data.size shr 16 and 0xFF).toByte())
            out.put((b.data.size shr 8 and 0xFF).toByte())
            out.put((b.data.size and 0xFF).toByte())
            out.put(b.data)
        }
        return out.array()
    }

    private fun readAt(ch: FileChannel, pos: Long, n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n)
        var read = 0
        while (read < n) {
            val r = ch.read(buf, pos + read)
            if (r < 0) throw IOException("Fin de archivo inesperado")
            read += r
        }
        return buf.array()
    }

    private fun writeAt(ch: FileChannel, pos: Long, data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        var written = 0
        while (buf.hasRemaining()) written += ch.write(buf, pos + written)
    }
}
