package com.musicafacil.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    /** Artista del álbum (etiqueta ALBUMARTIST) si existe, si no el de la pista */
    val albumArtist: String,
    val album: String,
    val albumId: Long,
    /** MediaStore guarda disco*1000 + pista */
    val track: Int,
    val durationMs: Long,
    /** Ruta del archivo (para saber si es FLAC y avisar al escáner tras editarlo). */
    val path: String? = null,
    /** Año de la etiqueta (0 si no tiene). */
    val year: Int = 0,
    /** Fecha de modificación del archivo (segundos), para saber si hay que releer sus etiquetas. */
    val modified: Long = 0,
    /** El año lo ha puesto el usuario en la app (manda sobre el del archivo). */
    val yearEdited: Boolean = false,
) {
    val uri: Uri get() = songUri(id)
    val isFlac: Boolean get() = path?.endsWith(".flac", ignoreCase = true) == true
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songs: List<Song>,
    /** Año del disco (el que más se repite entre sus canciones; 0 si no tiene). */
    val year: Int = 0,
)

data class Artist(
    val name: String,
    val albums: List<Album>,
) {
    val songs: List<Song> get() = albums.flatMap { it.songs }
    val songCount: Int get() = albums.sumOf { it.songs.size }
}

val audioCollection: Uri
    get() = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

fun songUri(id: Long): Uri = ContentUris.withAppendedId(audioCollection, id)

fun albumArtUri(albumId: Long): Uri =
    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

/** @param artwork portada descargada de internet, si la hay */
fun Song.toMediaItem(artwork: Uri? = null): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artwork ?: albumArtUri(albumId))
                .setExtras(android.os.Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) })
                .build()
        )
        .build()

const val EXTRA_ALBUM_ID = "album_id"
/** Letra del índice lateral: primera letra sin tilde ("Ábbey" -> A); números y símbolos -> '#'. */
fun indexLetter(title: String): Char {
    val c = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
        .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

const val UNKNOWN_ARTIST = "Artista desconocido"
const val UNKNOWN_ALBUM = "Álbum desconocido"

object MusicLibrary {

    /** Lee todas las canciones (FLAC, MP3, etc.) que Android tiene indexadas. */
    /** @param folders si no está vacío, solo canciones dentro de esas carpetas */
    fun loadSongs(context: Context, folders: List<String> = emptyList()): List<Song> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_MODIFIED,
            // Ruta del archivo, para filtrar por carpeta (obsoleta pero se sigue rellenando)
            MediaStore.Audio.Media.DATA,
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.Audio.Media.ALBUM_ARTIST) else emptyArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val songs = ArrayList<Song>()
        context.contentResolver.query(audioCollection, projection, selection, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val albumArtistCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val artist = c.getString(artistCol)
                    ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                    ?: UNKNOWN_ARTIST
                if (isInFolders(c.getString(dataCol), folders)) songs += Song(
                    id = c.getLong(idCol),
                    title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Sin título",
                    artist = artist,
                    albumArtist = albumArtistCol.takeIf { it >= 0 }?.let { c.getString(it) }
                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                        ?: artist,
                    album = c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM,
                    albumId = c.getLong(albumIdCol),
                    track = c.getInt(trackCol),
                    durationMs = c.getLong(durCol),
                    path = c.getString(dataCol),
                    year = c.getInt(yearCol).takeIf { it in 1000..9999 } ?: 0,
                    modified = c.getLong(modCol),
                )
            }
        }
        // Repara tildes y eñes mal leídas ("TRÃ‰" -> "TRÉ")
        return songs.map { TextFix.fix(context, it) }
    }

    /** @param years años leídos de los archivos ([AlbumYears]); mandan sobre los de Android */
    fun groupAlbums(songs: List<Song>, years: Map<Long, Int> = emptyMap()): List<Album> =
        songs.groupBy { it.albumId }
            .map { (id, list) ->
                val sorted = list.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))
                // El artista más repetido del álbum (evita "Varios" por un featuring)
                val artist = list.groupingBy { it.albumArtist }.eachCount().maxByOrNull { it.value }!!.key
                val year = list.firstOrNull { it.yearEdited }?.year ?: years[id] ?: list.map { it.year }.filter { it > 0 }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
                Album(id, sorted.first().album, artist, sorted, year)
            }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))

    fun groupArtists(albums: List<Album>): List<Artist> =
        albums.groupBy { artistKey(it.artist) }
            .map { (_, list) ->
                // Se muestra la forma del nombre que más se repite ("AC/DC" frente a "AC-DC")
                val name = list.flatMap { it.songs }.map { it.albumArtist }
                    .filter { artistKey(it) == artistKey(list.first().artist) }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: list.first().artist
                // Discografía por año (los que no tienen año, al final por título)
                Artist(
                    name,
                    list.map { if (it.artist == name) it else it.copy(artist = name) }
                        .sortedWith(compareBy({ it.year == 0 }, { it.year }, { it.title.lowercase() })),
                )
            }
            .sortedBy { it.name.lowercase().removePrefix("the ") }
}
