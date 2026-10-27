package com.musicafacil.player

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Busca en internet (iTunes y, si no, Deezer) las portadas de los discos que no
 * tienen ninguna, y las guarda en la memoria de la app para no repetir la búsqueda.
 */
object OnlineCovers {

    @Volatile
    var enabled = true

    private data class Info(val artist: String, val title: String)

    private val albums = ConcurrentHashMap<Long, Info>()
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val network = Semaphore(2)

    /** Se llama cada vez que cambia la biblioteca, para saber qué buscar de cada álbum. */
    fun register(list: List<Album>) {
        albums.clear()
        list.forEach { albums[it.id] = Info(it.artist, it.title) }
    }

    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    fun cachedFile(context: Context, albumId: Long): File? =
        File(dir(context), "$albumId.jpg").takeIf { it.exists() && it.length() > 0 }

    private fun prefs(context: Context) = context.getSharedPreferences("covers", Context.MODE_PRIVATE)

    private fun notFound(context: Context): Set<String> =
        prefs(context).getStringSet("not_found", emptySet())!!

    private fun markNotFound(context: Context, albumId: Long) {
        prefs(context).edit().putStringSet("not_found", notFound(context) + albumId.toString()).apply()
    }

    /** Olvida los discos que no se encontraron, para volver a intentarlo. */
    fun retryMissing(context: Context, albumId: Long? = null) {
        val keep = if (albumId == null) emptySet() else notFound(context) - albumId.toString()
        prefs(context).edit().putStringSet("not_found", keep).apply()
    }

    /** Portada descargada (o descargada ahora) a unos [px] píxeles; null si no hay. */
    suspend fun load(context: Context, albumId: Long, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cachedFile(context, albumId)?.let { return@withContext decode(it, px) }
        if (!enabled) return@withContext null
        val info = albums[albumId] ?: return@withContext null
        if (info.title == UNKNOWN_ALBUM || info.artist == UNKNOWN_ARTIST) return@withContext null

        locks.getOrPut(albumId) { Mutex() }.withLock {
            cachedFile(context, albumId)?.let { return@withLock decode(it, px) }
            if (albumId.toString() in notFound(context)) return@withLock null
            val bytes = network.withPermit {
                runCatching {
                    val url = findOnItunes(info) ?: findOnDeezer(info)
                    url?.let { download(it) }
                }.getOrNull()
            }
            if (bytes == null) {
                markNotFound(context, albumId)
                null
            } else {
                val file = File(dir(context), "$albumId.jpg")
                file.writeBytes(bytes)
                decode(file, px)
            }
        }
    }

    private fun decode(file: File, px: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    // --- Búsqueda ---

    /** Quita "(Remastered 2009)", "[Deluxe]"… y signos, para comparar títulos. */
    private fun normalize(s: String) =
        s.lowercase().replace(Regex("""[(\[].*?[)\]]"""), "").filter { it.isLetterOrDigit() }

    private fun matches(info: Info, artist: String, title: String): Boolean {
        val a1 = artistKey(info.artist)
        val a2 = artistKey(artist)
        val t1 = normalize(info.title)
        val t2 = normalize(title)
        val artistOk = a1 == a2 || a1.contains(a2) || a2.contains(a1)
        val titleOk = t1.isNotEmpty() && t2.isNotEmpty() && (t1.contains(t2) || t2.contains(t1))
        return artistOk && titleOk
    }

    private fun findOnItunes(info: Info): String? {
        val term = URLEncoder.encode("${info.artist} ${info.title}", "UTF-8")
        val json = JSONObject(get("https://itunes.apple.com/search?term=$term&entity=album&limit=10") ?: return null)
        val results = json.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (matches(info, r.optString("artistName"), r.optString("collectionName"))) {
                val art = r.optString("artworkUrl100").takeIf { it.isNotEmpty() } ?: continue
                return art.replace("100x100bb", "1200x1200bb")
            }
        }
        return null
    }

    private fun findOnDeezer(info: Info): String? {
        val q = URLEncoder.encode("artist:\"${info.artist}\" album:\"${info.title}\"", "UTF-8")
        val json = JSONObject(get("https://api.deezer.com/search/album?q=$q&limit=10") ?: return null)
        val data = json.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val r = data.getJSONObject(i)
            val artist = r.optJSONObject("artist")?.optString("name").orEmpty()
            if (matches(info, artist, r.optString("title"))) {
                return r.optString("cover_xl").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "EDplayer/1.0 (Android)")
        }

    private fun get(url: String): String? {
        val c = open(url)
        return try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): ByteArray? {
        val c = open(url)
        return try {
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.takeIf { it.size > 1000 } else null
        } finally {
            c.disconnect()
        }
    }
}
