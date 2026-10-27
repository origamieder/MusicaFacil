package com.musicafacil.player

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // --- Biblioteca ---
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var artists by mutableStateOf<List<Artist>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    private var songById: Map<Long, Song> = emptyMap()

    // --- Favoritas y playlists ---
    private val store = PlaylistStore(app)
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    private var saveJob: Job? = null

    /** Canciones pendientes de añadir a una playlist (abre el diálogo). */
    var addToPlaylistRequest by mutableStateOf<List<Long>?>(null)

    // --- Estado del reproductor ---
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var currentItem by mutableStateOf<MediaItem?>(null)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    /** Cambia cada vez que la cola cambia, para que la UI la vuelva a leer. */
    var queueVersion by mutableIntStateOf(0)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) queueVersion++
        }
    }

    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    init {
        viewModelScope.launch {
            playlists = withContext(Dispatchers.IO) { store.load() }
        }
        controllerFuture.addListener({
            val c = controllerFuture.get()
            c.addListener(listener)
            controller = c
            sync(c)
        }, ContextCompat.getMainExecutor(app))
    }

    private fun sync(p: Player) {
        playWhenReady = p.playWhenReady
        currentItem = p.currentMediaItem
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
    }

    fun loadLibrary() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!editsLoaded) {
                    edits = metadataStore.load()
                    editsLoaded = true
                }
                rawSongs = MusicLibrary.loadSongs(getApplication())
            }
            rebuild()
            loading = false
            loaded = true
        }
    }

    /** Aplica las ediciones a las canciones leídas y vuelve a agrupar álbumes y artistas. */
    private suspend fun rebuild() {
        val current = edits
        val raw = rawSongs
        val (s, a, ar) = withContext(Dispatchers.Default) {
            val s = raw.map { current.apply(it) }
            val a = MusicLibrary.groupAlbums(s)
            Triple(s, a, MusicLibrary.groupArtists(a))
        }
        songs = s
        songById = s.associateBy { it.id }
        albums = a
        artists = ar
    }

    fun album(id: Long): Album? = albums.firstOrNull { it.id == id }
    fun artist(name: String): Artist? = artists.firstOrNull { artistKey(it.name) == artistKey(name) }

    // --- Edición de etiquetas ---

    private val metadataStore = MetadataStore(app)
    private var edits = MetadataEdits()
    private var editsLoaded = false
    private var rawSongs: List<Song> = emptyList()

    /** Canción cuyo diálogo de edición está abierto. */
    var editSongRequest by mutableStateOf<Song?>(null)

    fun editSong(id: Long, title: String, artist: String, album: String, albumArtist: String) {
        val raw = rawSongs.firstOrNull { it.id == id } ?: return
        fun changed(new: String, old: String) = new.trim().takeIf { it.isNotEmpty() && it != old }
        val e = SongEdit(
            title = changed(title, raw.title),
            artist = changed(artist, raw.artist),
            album = changed(album, raw.album),
            albumArtist = changed(albumArtist, raw.albumArtist),
        )
        saveEdits(edits.copy(songs = if (e.isEmpty) edits.songs - id else edits.songs + (id to e)))
    }

    fun resetSong(id: Long) = saveEdits(edits.copy(songs = edits.songs - id))

    fun editAlbum(album: Album, title: String, albumArtist: String) {
        val t = title.trim()
        val aa = albumArtist.trim()
        val songs = edits.songs.toMutableMap()
        album.songs.forEach { s ->
            val e = songs[s.id] ?: SongEdit()
            songs[s.id] = e.copy(
                album = t.ifEmpty { null } ?: e.album,
                albumArtist = aa.ifEmpty { null } ?: e.albumArtist,
            )
        }
        saveEdits(edits.copy(songs = songs))
    }

    /** Renombra un artista; si el nombre nuevo es el de otro artista, se unen. */
    fun renameArtist(artist: Artist, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        val aliases = edits.artistAliases.toMutableMap()
        artist.songs.flatMap { listOf(it.artist, it.albumArtist) }
            .filter { artistKey(it) == artistKey(artist.name) }
            .plus(artist.name)
            .forEach { aliases[artistKey(it)] = name }
        saveEdits(edits.copy(artistAliases = aliases))
    }

    private fun saveEdits(newEdits: MetadataEdits) {
        edits = newEdits
        viewModelScope.launch {
            rebuild()
            withContext(Dispatchers.IO) { metadataStore.save(newEdits) }
        }
    }

    // --- Acciones ---

    fun shuffleAll() {
        if (songs.isEmpty()) return
        play(songs, songs.indices.random(), shuffle = true)
    }

    fun playAlbum(album: Album, startIndex: Int = 0, shuffle: Boolean = false) {
        val start = if (shuffle) album.songs.indices.random() else startIndex
        play(album.songs, start, shuffle)
    }

    fun playArtist(artist: Artist, shuffle: Boolean) {
        val list = artist.songs
        if (list.isEmpty()) return
        play(list, if (shuffle) list.indices.random() else 0, shuffle)
    }

    fun playSongs(list: List<Song>, startIndex: Int) = play(list, startIndex, shuffle = false)

    private fun play(list: List<Song>, startIndex: Int, shuffle: Boolean) {
        val c = controller ?: return
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(list.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.playWhenReady) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
            c.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()

    /** Siempre a la canción anterior (sin reiniciar la actual), para el gesto de deslizar. */
    fun previousTrack() = controller?.seekToPreviousMediaItem()

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // --- Cola ---

    data class QueueEntry(val index: Int, val item: MediaItem)

    /** Canciones que vienen después de la actual, en el orden en que sonarán. */
    fun upcoming(): List<QueueEntry> {
        val c = controller ?: return emptyList()
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<QueueEntry>()
        var i = timeline.getNextWindowIndex(c.currentMediaItemIndex, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        while (i != C.INDEX_UNSET) {
            out += QueueEntry(i, c.getMediaItemAt(i))
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return out
    }

    fun playQueueItem(index: Int) {
        controller?.run { seekToDefaultPosition(index); play() }
    }

    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)

    fun moveInQueue(from: Int, to: Int) = controller?.moveMediaItem(from, to)

    // --- Playlists ---

    fun playlist(id: Long): Playlist? = playlists.firstOrNull { it.id == id }

    /** Canciones de la playlist que siguen existiendo en el móvil. */
    fun songsOf(playlist: Playlist): List<Song> = playlist.songIds.mapNotNull { songById[it] }

    val favorites: Playlist? get() = playlist(FAVORITES_ID)

    fun isFavorite(songId: Long?): Boolean =
        songId != null && favorites?.songIds?.contains(songId) == true

    fun toggleFavorite(songId: Long) {
        if (isFavorite(songId)) removeFromPlaylist(FAVORITES_ID, songId)
        else addToPlaylist(FAVORITES_ID, listOf(songId))
    }

    fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Long {
        val id = System.currentTimeMillis()
        update(playlists + Playlist(id, name.trim().ifEmpty { "Nueva playlist" }, songIds.distinct()))
        return id
    }

    fun renamePlaylist(id: Long, name: String) = edit(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deletePlaylist(id: Long) {
        if (id != FAVORITES_ID) update(playlists.filter { it.id != id })
    }

    fun addToPlaylist(id: Long, songIds: List<Long>) = edit(id) { p ->
        p.copy(songIds = p.songIds + songIds.filter { it !in p.songIds }.distinct())
    }

    fun removeFromPlaylist(id: Long, songId: Long) = edit(id) { p ->
        p.copy(songIds = p.songIds - songId)
    }

    fun moveInPlaylist(id: Long, fromSongId: Long, toSongId: Long) = edit(id) { p ->
        val list = p.songIds.toMutableList()
        val from = list.indexOf(fromSongId)
        val to = list.indexOf(toSongId)
        if (from < 0 || to < 0) p else p.copy(songIds = list.apply { add(to, removeAt(from)) })
    }

    fun playPlaylist(playlist: Playlist, startIndex: Int = 0, shuffle: Boolean = false) {
        val list = songsOf(playlist)
        if (list.isEmpty()) return
        play(list, if (shuffle) list.indices.random() else startIndex, shuffle)
    }

    private fun edit(id: Long, change: (Playlist) -> Playlist) =
        update(playlists.map { if (it.id == id) change(it) else it })

    private fun update(newList: List<Playlist>) {
        playlists = newList
        // Guardado con pequeño retardo: al arrastrar canciones llegan muchos cambios seguidos
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { store.save(newList) }
        }
    }

    override fun onCleared() {
        saveJob?.let { if (it.isActive) store.save(playlists) }
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}
