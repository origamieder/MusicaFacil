package com.musicafacil.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long>,
) {
    val isFavorites: Boolean get() = id == FAVORITES_ID
}

const val FAVORITES_ID = 0L

/** Guarda favoritas y playlists en un JSON dentro de la app. */
class PlaylistStore(context: Context) {

    private val file = File(context.filesDir, "playlists.json")

    fun load(): List<Playlist> {
        val list = try {
            if (!file.exists()) emptyList()
            else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val ids = o.getJSONArray("songs")
                    Playlist(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        songIds = (0 until ids.length()).map { ids.getLong(it) },
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        // Favoritas siempre existe y va la primera
        val favorites = list.firstOrNull { it.isFavorites } ?: Playlist(FAVORITES_ID, "Favoritas", emptyList())
        return listOf(favorites) + list.filter { !it.isFavorites }
    }

    fun save(playlists: List<Playlist>) {
        val arr = JSONArray()
        playlists.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("songs", JSONArray().apply { p.songIds.forEach { put(it) } })
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
