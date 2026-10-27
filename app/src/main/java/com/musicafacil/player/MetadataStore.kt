package com.musicafacil.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Cambios de etiquetas hechos en la app. null = se deja el valor del archivo. */
data class SongEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
) {
    val isEmpty: Boolean get() = title == null && artist == null && album == null && albumArtist == null
}

data class MetadataEdits(
    val songs: Map<Long, SongEdit> = emptyMap(),
    /** artistKey(nombre original) -> nombre que se muestra */
    val artistAliases: Map<String, String> = emptyMap(),
) {
    fun apply(song: Song): Song {
        val e = songs[song.id]
        val edited = if (e == null) song else song.copy(
            title = e.title ?: song.title,
            artist = e.artist ?: song.artist,
            album = e.album ?: song.album,
            albumArtist = e.albumArtist ?: song.albumArtist,
        )
        return edited.copy(artist = alias(edited.artist), albumArtist = alias(edited.albumArtist))
    }

    private fun alias(name: String): String {
        var current = name
        // Se repite por si hay renombrados encadenados (A -> B -> C)
        repeat(5) {
            val next = artistAliases[artistKey(current)] ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }
}

/**
 * Clave para agrupar artistas: sin mayúsculas ni signos, así
 * "AC/DC", "AC-DC" y "acdc" son el mismo artista.
 */
fun artistKey(name: String): String {
    val key = name.lowercase().filter { it.isLetterOrDigit() }
    return key.ifEmpty { name.lowercase() }
}

class MetadataStore(context: Context) {

    private val file = File(context.filesDir, "metadata.json")

    fun load(): MetadataEdits = try {
        if (!file.exists()) MetadataEdits()
        else {
            val root = JSONObject(file.readText())
            val songs = HashMap<Long, SongEdit>()
            root.optJSONObject("songs")?.let { o ->
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    songs[k.toLong()] = SongEdit(
                        title = e.optStringOrNull("title"),
                        artist = e.optStringOrNull("artist"),
                        album = e.optStringOrNull("album"),
                        albumArtist = e.optStringOrNull("albumArtist"),
                    )
                }
            }
            val aliases = HashMap<String, String>()
            root.optJSONObject("artistAliases")?.let { o ->
                o.keys().forEach { k -> aliases[k] = o.getString(k) }
            }
            MetadataEdits(songs, aliases)
        }
    } catch (e: Exception) {
        MetadataEdits()
    }

    fun save(edits: MetadataEdits) {
        val songs = JSONObject()
        edits.songs.forEach { (id, e) ->
            songs.put(id.toString(), JSONObject().apply {
                e.title?.let { put("title", it) }
                e.artist?.let { put("artist", it) }
                e.album?.let { put("album", it) }
                e.albumArtist?.let { put("albumArtist", it) }
            })
        }
        val root = JSONObject()
            .put("songs", songs)
            .put("artistAliases", JSONObject(edits.artistAliases as Map<*, *>))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
