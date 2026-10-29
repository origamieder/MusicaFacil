package com.musicafacil.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Busca en internet el año de un disco, como hace MusicBee: primero en MusicBrainz
 * (fecha del primer lanzamiento, así una remasterización da el año del original) y,
 * si no aparece, en iTunes. Los resultados se guardan para no repetir búsquedas.
 */
object OnlineMetadata {

    private const val USER_AGENT = "EDplayer/1.0 (https://github.com/origamieder/MusicaFacil)"

    // MusicBrainz pide como mucho una petición por segundo
    private val mbLock = Mutex()
    private var lastMbRequest = 0L

    private var cache: MutableMap<String, Int>? = null

    private fun file(context: Context) = File(context.filesDir, "online_years.json")

    private fun key(artist: String, title: String) = artistKey(artist) + "|" + normalizeTitle(title)

    @Synchronized
    private fun cache(context: Context): MutableMap<String, Int> =
        cache ?: runCatching {
            val o = JSONObject(file(context).readText())
            o.keys().asSequence().associateWith { o.getInt(it) }.toMutableMap()
        }.getOrDefault(HashMap()).also { cache = it }

    @Synchronized
    private fun store(context: Context, key: String, year: Int) {
        val c = cache(context)
        c[key] = year
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject(c as Map<*, *>).toString())
            tmp.renameTo(f)
        }
    }

    /** Año ya buscado: null = nunca se buscó; 0 = se buscó y no se encontró. */
    fun cachedYear(context: Context, artist: String, title: String): Int? = cache(context)[key(artist, title)]

    /**
     * Año del disco o null si no se encuentra. Si falla la conexión no se guarda nada,
     * para volver a intentarlo más adelante.
     * @param force buscar aunque ya se hubiera buscado antes
     */
    suspend fun findYear(context: Context, artist: String, title: String, force: Boolean = false): Int? {
        if (artist == UNKNOWN_ARTIST || title == UNKNOWN_ALBUM) return null
        if (!force) cachedYear(context, artist, title)?.let { return it.takeIf { y -> y > 0 } }
        return withContext(Dispatchers.IO) {
            try {
                val year = musicBrainzYear(artist, title) ?: itunesYear(artist, title)
                store(context, key(artist, title), year ?: 0)
                year
            } catch (e: IOException) {
                null
            } catch (e: org.json.JSONException) {
                null
            }
        }
    }

    // --- MusicBrainz ---

    private suspend fun musicBrainzYear(artist: String, title: String): Int? {
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val query = "releasegroup:${quote(title)} AND artist:${quote(artist)}"
        val url = "https://musicbrainz.org/ws/2/release-group/?fmt=json&limit=10&query=" +
            URLEncoder.encode(query, "UTF-8")
        val body = mbLock.withLock {
            val wait = lastMbRequest + 1100 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                get(url)
            } finally {
                lastMbRequest = System.currentTimeMillis()
            }
        } ?: return null
        val groups = JSONObject(body).optJSONArray("release-groups") ?: return null
        var best: Int? = null
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val credit = g.optJSONArray("artist-credit")
            val names = (0 until (credit?.length() ?: 0)).joinToString(" ") { j ->
                credit!!.getJSONObject(j).optString("name")
            }
            if (!albumMatches(artist, title, names, g.optString("title"))) continue
            val y = TagReader.parseYear(g.optString("first-release-date")) ?: continue
            // Si hay varias coincidencias (ediciones, recopilatorios…), la más antigua
            if (best == null || y < best) best = y
        }
        return best
    }

    // --- iTunes (respaldo; suele dar la fecha de la edición digital) ---

    private fun itunesYear(artist: String, title: String): Int? {
        val term = URLEncoder.encode("$artist $title", "UTF-8")
        val body = get("https://itunes.apple.com/search?term=$term&entity=album&limit=10") ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        var best: Int? = null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (!albumMatches(artist, title, r.optString("artistName"), r.optString("collectionName"))) continue
            val y = TagReader.parseYear(r.optString("releaseDate")) ?: continue
            if (best == null || y < best) best = y
        }
        return best
    }

    private fun get(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }
}

/** Quita "(Remastered 2009)", "[Deluxe]"… y signos, para comparar títulos. */
fun normalizeTitle(s: String) =
    s.lowercase().replace(Regex("[(\\[].*?[)\\]]"), "").filter { it.isLetterOrDigit() }

/** ¿El resultado encontrado es el mismo disco? (mismo artista y título parecido) */
fun albumMatches(artist: String, title: String, foundArtist: String, foundTitle: String): Boolean {
    val a1 = artistKey(artist)
    val a2 = artistKey(foundArtist)
    val t1 = normalizeTitle(title)
    val t2 = normalizeTitle(foundTitle)
    val artistOk = a1.isNotEmpty() && a2.isNotEmpty() && (a1 == a2 || a1.contains(a2) || a2.contains(a1))
    val titleOk = t1.isNotEmpty() && t2.isNotEmpty() && (t1 == t2 || t1.contains(t2) || t2.contains(t1))
    return artistOk && titleOk
}
