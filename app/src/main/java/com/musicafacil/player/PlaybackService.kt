package com.musicafacil.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Servicio en segundo plano que reproduce la música, muestra la notificación
 * y responde a los botones de auriculares / Bluetooth / pantalla de bloqueo.
 *
 * Gapless: ExoPlayer trabaja con una lista de reproducción y empieza a cargar y
 * decodificar la siguiente canción antes de que termine la actual, enlazando el
 * audio sin silencio. En MP3 recorta el relleno del codificador (cabeceras
 * LAME/Xing e iTunSMPB); FLAC no tiene relleno, así que es gapless de por sí.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Copia de la biblioteca para elegir el siguiente disco sin tener la app abierta
    private var albumsById: Map<Long, Album> = emptyMap()
    private var artists: List<Artist> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pausa al desconectar los auriculares
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Al empezar la última canción se refresca la biblioteca, para tenerla al día al terminar
                if (!player.hasNextMediaItem()) refreshLibrary()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !AppVisibility.visible &&
                    player.repeatMode == Player.REPEAT_MODE_OFF
                ) continueWithSameArtist(player)
            }
        })
        refreshLibrary()

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Al pasar por la sesión se pierde la URI; la reconstruimos desde el id.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(
                        mediaItems.map { item ->
                            val id = item.mediaId.toLongOrNull()
                            if (item.localConfiguration == null && id != null) {
                                item.buildUpon().setUri(songUri(id)).build()
                            } else item
                        }.toMutableList()
                    )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    private fun refreshLibrary() {
        scope.launch {
            val (albums, artistList) = withContext(Dispatchers.IO) {
                runCatching {
                    val edits = MetadataStore(this@PlaybackService).load()
                    val songs = MusicLibrary.loadSongs(this@PlaybackService, FolderSettings(this@PlaybackService).load())
                        .map { edits.apply(it) }
                    val years = AlbumYears.resolve(this@PlaybackService, MusicLibrary.groupAlbums(songs))
                    val albums = MusicLibrary.groupAlbums(songs, years)
                    albums to MusicLibrary.groupArtists(albums)
                }.getOrNull()
            } ?: return@launch
            albumsById = albums.associateBy { it.id }
            artists = artistList
        }
    }

    /**
     * Ha terminado el disco con la app cerrada: otro disco al azar del mismo artista.
     * Se hace en el mismo instante para que el servicio no llegue a salir de primer plano.
     */
    private fun continueWithSameArtist(player: Player) {
        val lastAlbumId = player.currentMediaItem?.mediaMetadata?.extras?.getLong(EXTRA_ALBUM_ID) ?: return
        val album = albumsById[lastAlbumId] ?: return
        val artist = artists.firstOrNull { artistKey(it.name) == artistKey(album.artist) } ?: return
        val next = artist.albums.filter { it.id != lastAlbumId }.ifEmpty { artist.albums }.randomOrNull() ?: return
        val cover = OnlineCovers.cachedFile(this, next.id)?.let { android.net.Uri.fromFile(it) }
        player.shuffleModeEnabled = false
        player.setMediaItems(next.songs.map { it.toMediaItem(cover) }, 0, 0L)
        player.prepare()
        player.play()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
