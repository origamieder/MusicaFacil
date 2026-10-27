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
    val album: String,
    val albumId: Long,
    /** MediaStore guarda disco*1000 + pista */
    val track: Int,
    val durationMs: Long,
) {
    val uri: Uri get() = songUri(id)
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songs: List<Song>,
)

val audioCollection: Uri
    get() = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

fun songUri(id: Long): Uri = ContentUris.withAppendedId(audioCollection, id)

fun albumArtUri(albumId: Long): Uri =
    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUri(albumId))
                .setExtras(android.os.Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) })
                .build()
        )
        .build()

const val EXTRA_ALBUM_ID = "album_id"

object MusicLibrary {

    /** Lee todas las canciones (FLAC, MP3, etc.) que Android tiene indexadas. */
    fun loadSongs(context: Context): List<Song> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
        )
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
            while (c.moveToNext()) {
                songs += Song(
                    id = c.getLong(idCol),
                    title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Sin título",
                    artist = c.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                        ?: "Artista desconocido",
                    album = c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Álbum desconocido",
                    albumId = c.getLong(albumIdCol),
                    track = c.getInt(trackCol),
                    durationMs = c.getLong(durCol),
                )
            }
        }
        return songs
    }

    fun groupAlbums(songs: List<Song>): List<Album> =
        songs.groupBy { it.albumId }
            .map { (id, list) ->
                val sorted = list.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))
                // El artista más repetido del álbum (evita "Varios" por un featuring)
                val artist = list.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }!!.key
                Album(id, sorted.first().album, artist, sorted)
            }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
}
