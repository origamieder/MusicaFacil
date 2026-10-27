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
    private var loadJob: Job? = null

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
            // Con la app fuera de pantalla no se pregunta: el servicio sigue solo con el mismo artista
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED && player.mediaItemCount > 0 &&
                AppVisibility.visible
            ) askWhatNext(player)
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
        loadJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!editsLoaded) {
                    edits = metadataStore.load()
                    editsLoaded = true
                }
                rawSongs = MusicLibrary.loadSongs(getApplication(), folders)
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
        OnlineCovers.register(a)
        songs = s
        songById = s.associateBy { it.id }
        albums = a
        artists = ar
        ArtCache.refresh()
    }

    fun album(id: Long): Album? = albums.firstOrNull { it.id == id }
    fun artist(name: String): Artist? = artists.firstOrNull { artistKey(it.name) == artistKey(name) }

    // --- Vista de álbumes ---

    var albumView by mutableStateOf(
        AlbumView.entries.getOrNull(
            app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getInt("album_view", AlbumView.Large.ordinal)
        ) ?: AlbumView.Large
    )
        private set

    /** Semilla del orden aleatorio de los álbumes: fija mientras dura la sesión. */
    var albumShuffleSeed by mutableStateOf(kotlin.random.Random.nextLong())
        private set

    fun reshuffleAlbums() {
        albumShuffleSeed = kotlin.random.Random.nextLong()
    }

    /** Álbumes mezclados; el orden de cada disco depende solo de su id y la semilla, así no se descoloca al editar. */
    fun shuffledAlbums(): List<Album> {
        val seed = albumShuffleSeed
        return albums.sortedBy { a ->
            var x = (a.id xor seed) * -7046029254386353131L
            x = x xor (x ushr 31)
            x
        }
    }

    fun changeAlbumView(view: AlbumView) {
        albumView = view
        settingsPrefs.edit().putInt("album_view", view.ordinal).apply()
    }

    // --- Portadas de internet ---

    private val settingsPrefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var onlineCovers by mutableStateOf(settingsPrefs.getBoolean("online_covers", true).also { OnlineCovers.enabled = it })
        private set

    fun changeOnlineCovers(enabled: Boolean) {
        onlineCovers = enabled
        OnlineCovers.enabled = enabled
        settingsPrefs.edit().putBoolean("online_covers", enabled).apply()
        if (enabled) ArtCache.refresh()
    }

    /** Vuelve a buscar en internet las portadas que no se encontraron (de todos o de un álbum). */
    fun retryCovers(albumId: Long? = null) {
        OnlineCovers.retryMissing(getApplication(), albumId)
        ArtCache.refresh()
    }

    // --- Carpetas ---

    private val folderSettings = FolderSettings(app)
    var folders by mutableStateOf(folderSettings.load())
        private set

    fun addFolder(uri: android.net.Uri) {
        val path = folderPathFromTreeUri(uri) ?: return
        // Si ya hay una carpeta que la contiene, no hace falta añadirla
        if (folders.any { isInFolders("$path/x", listOf(it)) }) return
        applyFolders(folders.filter { !isInFolders("$it/x", listOf(path)) } + path)
    }

    fun removeFolder(path: String) = applyFolders(folders - path)

    private fun applyFolders(list: List<String>) {
        folders = list.sorted()
        folderSettings.save(folders)
        reloadLibrary()
    }

    /** Vuelve a leer la biblioteca aunque ya haya una lectura en marcha. */
    private fun reloadLibrary() {
        loadJob?.cancel()
        loading = false
        loadLibrary()
    }

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

    // --- Escribir etiquetas en los archivos FLAC ---

    /** Permiso del sistema pendiente de pedir para modificar archivos (Android 11+). */
    var writeRequest by mutableStateOf<android.content.IntentSender?>(null)
        private set
    private var pendingWrites: Map<Long, FlacTagWriter.Changes> = emptyMap()

    val canWriteFiles: Boolean get() = android.os.Build.VERSION.SDK_INT >= 30

    /** Pide permiso para escribir los cambios en los FLAC indicados (los no FLAC se ignoran). */
    fun requestFileWrite(changes: Map<Long, FlacTagWriter.Changes>) {
        if (!canWriteFiles) return
        val flac = changes.filterKeys { rawSongs.firstOrNull { s -> s.id == it }?.isFlac == true }
        if (flac.isEmpty()) return
        pendingWrites = flac
        val pi = android.provider.MediaStore.createWriteRequest(getApplication<Application>().contentResolver, flac.keys.map(::songUri))
        writeRequest = pi.intentSender
    }

    fun onWritePermission(granted: Boolean) {
        writeRequest = null
        val work = pendingWrites
        pendingWrites = emptyMap()
        val app = getApplication<Application>()
        if (!granted) {
            toast("No se han modificado los archivos")
            return
        }
        viewModelScope.launch {
            val paths = ArrayList<String>()
            var failed = 0
            withContext(Dispatchers.IO) {
                val byId = rawSongs.associateBy { it.id }
                work.forEach { (id, change) ->
                    runCatching { FlacTagWriter.write(app, songUri(id), change) }
                        .onSuccess { byId[id]?.path?.let(paths::add) }
                        .onFailure { failed++ }
                }
            }
            // Avisa a Android para que el resto de apps vean las etiquetas nuevas
            if (paths.isNotEmpty()) android.media.MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null)
            toast(
                when {
                    failed == 0 -> "Guardado en ${paths.size} ${if (paths.size == 1) "archivo" else "archivos"}"
                    paths.isEmpty() -> "No se pudo modificar ningún archivo"
                    else -> "Guardado en ${paths.size}; fallaron $failed"
                }
            )
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(getApplication(), msg, android.widget.Toast.LENGTH_LONG).show()

    /** Cambios de archivo para renombrar un artista: solo en las etiquetas que tenían ese artista. */
    fun artistFileChanges(artist: Artist, newName: String): Map<Long, FlacTagWriter.Changes> {
        val key = artistKey(artist.name)
        val name = newName.trim()
        return artist.songs.associate { s ->
            s.id to FlacTagWriter.Changes(
                artist = name.takeIf { artistKey(s.artist) == key },
                albumArtist = name.takeIf { artistKey(s.albumArtist) == key },
            )
        }
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
        // Para la notificación y la pantalla de bloqueo: usa la portada descargada si el disco no tiene
        val app = getApplication<Application>()
        val downloaded = list.map { it.albumId }.distinct()
            .associateWith { id -> OnlineCovers.cachedFile(app, id)?.let { android.net.Uri.fromFile(it) } }
        c.setMediaItems(list.map { it.toMediaItem(downloaded[it.albumId]) }, startIndex, 0L)
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

    /** Siguiente canción; si era la última del disco, pregunta qué escuchar después. */
    fun next() {
        val c = controller ?: return
        if (!c.hasNextMediaItem() && c.repeatMode == Player.REPEAT_MODE_OFF && c.mediaItemCount > 0) askWhatNext(c)
        else c.seekToNext()
    }

    // --- Al terminar un disco ---

    /** El disco (o la lista) ha terminado: qué artista y disco sonaban. */
    data class WhatNext(val artist: Artist?, val albumId: Long?)

    var whatNext by mutableStateOf<WhatNext?>(null)
        private set

    private fun askWhatNext(player: Player) {
        val albumId = player.currentMediaItem?.mediaMetadata?.extras?.getLong(EXTRA_ALBUM_ID)
        val artist = albumId?.let { album(it) }?.artist?.let { artist(it) }
        whatNext = WhatNext(artist, albumId)
    }

    fun dismissWhatNext() {
        whatNext = null
    }

    /** Reproduce un disco al azar del artista, evitando repetir el que acaba de sonar. */
    fun playRandomAlbumOf(artist: Artist) {
        val last = whatNext?.albumId
        val choices = artist.albums.filter { it.id != last }.ifEmpty { artist.albums }
        whatNext = null
        if (choices.isNotEmpty()) playAlbum(choices.random())
    }
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
