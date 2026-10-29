package com.musicafacil.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Año de cada disco leído de las etiquetas del propio archivo: el año original si
 * existe (para reediciones y remasterizaciones), si no la fecha normal. Se lee una
 * sola canción por disco y el resultado se guarda, así solo cuesta la primera vez.
 */
object AlbumYears {

    private fun file(context: Context) = File(context.filesDir, "album_years.json")

    /** albumId -> año (solo los discos en los que se ha encontrado). */
    @Synchronized
    fun resolve(context: Context, albums: List<Album>): Map<Long, Int> {
        val cache = load(context)
        val used = HashMap<String, Int>()
        val result = HashMap<Long, Int>()
        var changed = false
        for (album in albums) {
            val song = album.songs.firstOrNull() ?: continue
            // La fecha de modificación forma parte de la clave: si el archivo cambia, se vuelve a leer
            val key = "${song.id}:${song.modified}"
            val year = cache[key] ?: run {
                changed = true
                val tags = TagReader.read(context, song.uri)
                tags?.originalYear ?: tags?.year ?: 0
            }
            used[key] = year
            if (year > 0) result[album.id] = year
        }
        if (changed || used.size != cache.size) save(context, used)
        return result
    }

    private fun load(context: Context): Map<String, Int> = runCatching {
        val o = JSONObject(file(context).readText())
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    private fun save(context: Context, map: Map<String, Int>) {
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject(map as Map<*, *>).toString())
            tmp.renameTo(f)
        }
    }
}
