package com.musicafacil.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors, typography = AppType) {
                Surface(Modifier.fillMaxSize(), color = Black) { App() }
            }
        }
    }
}

// ---------------------------------------------------------------- Estilo

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Gray = Color(0xFF8E8E8E)
private val DarkGray = Color(0xFF4A4A4A)
private val Card = Color(0xFF141414)
private val Line = Color(0xFF262626)

private val AppColors = darkColorScheme(
    primary = White,
    onPrimary = Black,
    secondary = White,
    onSecondary = Black,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
    surfaceVariant = Card,
    onSurfaceVariant = Gray,
    surfaceContainer = Color(0xFF0C0C0C),
    outline = Line,
)

private val AppType = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    )
}

// ---------------------------------------------------------------- Permisos

private fun audioPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------- Navegación

private enum class Tab(val label: String) { Albums("Álbumes"), Artists("Artistas"), Songs("Canciones"), Playlists("Playlists") }

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasAudioPermission(ctx) }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission(), Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(audioPermission())
    }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(permissions) }
    LaunchedEffect(granted) { if (granted && !vm.loaded) vm.loadLibrary() }

    if (!granted) {
        PermissionScreen { launcher.launch(permissions) }
        return
    }

    var openArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var openAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showQueue || showPlayer || openAlbumId != null || openPlaylistId != null || openArtist != null) {
        when {
            showQueue -> showQueue = false
            showPlayer -> showPlayer = false
            openAlbumId != null -> openAlbumId = null
            openPlaylistId != null -> openPlaylistId = null
            else -> openArtist = null
        }
    }

    vm.addToPlaylistRequest?.let { ids ->
        AddToPlaylistDialog(vm, ids) { vm.addToPlaylistRequest = null }
    }
    vm.editSongRequest?.let { song ->
        SongEditDialog(vm, song) { vm.editSongRequest = null }
    }

    val goArtist: (String) -> Unit = { openArtist = it; openAlbumId = null; showPlayer = false; showQueue = false }

    Scaffold(
        containerColor = Black,
        bottomBar = {
            if (!showPlayer && !showQueue && vm.currentItem != null) MiniPlayer(vm) { showPlayer = true }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val album = openAlbumId?.let { vm.album(it) }
            val artist = openArtist?.let { vm.artist(it) }
            val playlist = openPlaylistId?.let { vm.playlist(it) }
            when {
                showQueue -> QueueScreen(vm) { showQueue = false }
                showPlayer -> PlayerScreen(
                    vm,
                    onArtist = goArtist,
                    onQueue = { showQueue = true },
                    onAlbum = { openAlbumId = it; showPlayer = false },
                    onHome = { showPlayer = false; openAlbumId = null; openPlaylistId = null; openArtist = null },
                ) { showPlayer = false }
                album != null -> AlbumScreen(vm, album, onArtist = goArtist) { openAlbumId = null }
                playlist != null -> PlaylistScreen(vm, playlist) { openPlaylistId = null }
                artist != null -> ArtistScreen(
                    vm, artist,
                    onAlbum = { openAlbumId = it.id },
                    onRenamed = { openArtist = it },
                ) { openArtist = null }
                else -> HomeScreen(
                    vm,
                    onAlbum = { openAlbumId = it.id },
                    onArtist = goArtist,
                    onPlaylist = { openPlaylistId = it },
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Música", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Necesito acceso a tus archivos de audio para mostrar tu biblioteca.", color = Gray)
        Spacer(Modifier.height(24.dp))
        Pill("Dar acceso", Icons.Filled.Check, filled = true, onClick = onAsk)
    }
}

// ---------------------------------------------------------------- Inicio

@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onAlbum: (Album) -> Unit,
    onArtist: (String) -> Unit,
    onPlaylist: (Long) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Nueva playlist", "", "Crear", onDismiss = { creating = false }) {
            onPlaylist(vm.createPlaylist(it))
            creating = false
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(Tab.Albums) }
    val q = query.trim().lowercase()

    val albums = remember(vm.albums, q) {
        if (q.isEmpty()) vm.albums
        else vm.albums.filter { q in it.title.lowercase() || q in it.artist.lowercase() }
    }
    val artists = remember(vm.artists, q) {
        if (q.isEmpty()) vm.artists else vm.artists.filter { q in it.name.lowercase() }
    }
    val songs = remember(vm.songs, q, tab) {
        if (tab != Tab.Songs) emptyList()
        else vm.songs
            .filter { q.isEmpty() || q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
            .sortedBy { it.title.lowercase() }
    }

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Biblioteca", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        if (vm.loading) "Buscando música…"
                        else "${vm.songs.size} canciones · ${vm.artists.size} artistas",
                        color = Gray, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Pill("Aleatorio", Icons.Filled.Shuffle, enabled = vm.songs.isNotEmpty(), onClick = vm::shuffleAll)
            }
            Spacer(Modifier.height(20.dp))
            SearchField(query) { query = it }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Tab.entries.forEach { t ->
                    TabLabel(t.label, t == tab) { tab = t }
                }
            }
            if (vm.loaded && vm.songs.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "No hay música. Copia tus FLAC o MP3 al móvil (por ejemplo a la carpeta Music) y toca aquí para volver a buscar.",
                    color = Gray, modifier = Modifier.clickable { vm.loadLibrary() },
                )
            }
        }
    }

    val pad = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp)
    val currentId = vm.currentItem?.mediaId
    when (tab) {
        Tab.Albums -> AlbumGrid(albums, pad, header, onAlbum)
        Tab.Artists -> LazyColumn(contentPadding = pad) {
            item { header() }
            items(artists, key = { it.name }) { artist ->
                ArtistRow(artist) { onArtist(artist.name) }
            }
        }
        Tab.Songs -> LazyColumn(contentPadding = pad) {
            item { header() }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    leading = { AlbumArt(song.albumId, Modifier.size(44.dp), sizePx = 150, shape = RoundedCornerShape(4.dp)) },
                    title = song.title,
                    subtitle = "${song.artist} · ${song.album}",
                    playing = song.id.toString() == currentId,
                    onClick = { vm.playSongs(songs, i) },
                ) {
                    Duration(song)
                    SongMenu(vm, song)
                }
            }
        }
        Tab.Playlists -> LazyColumn(contentPadding = pad) {
            item { header() }
            items(vm.playlists, key = { it.id }) { p ->
                PlaylistRow(vm, p) { onPlaylist(p.id) }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { creating = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Card),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, null, tint = White) }
                    Spacer(Modifier.width(14.dp))
                    Text("Nueva playlist", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PlaylistCover(vm: MainViewModel, playlist: Playlist, modifier: Modifier) {
    val first = vm.songsOf(playlist).firstOrNull()
    if (playlist.isFavorites || first == null) {
        Box(modifier.clip(RoundedCornerShape(6.dp)).background(Card), contentAlignment = Alignment.Center) {
            Icon(
                if (playlist.isFavorites) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                null, Modifier.fillMaxSize(0.4f), tint = White,
            )
        }
    } else {
        AlbumArt(first.albumId, modifier, sizePx = 200)
    }
}

@Composable
private fun PlaylistRow(vm: MainViewModel, playlist: Playlist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(vm, playlist, Modifier.size(56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(songCount(vm.songsOf(playlist).size), fontSize = 13.sp, color = Gray)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = DarkGray)
    }
}

private fun songCount(n: Int) = "$n " + if (n == 1) "canción" else "canciones"


@Composable
private fun AlbumGrid(
    albums: List<Album>,
    pad: PaddingValues,
    header: @Composable () -> Unit,
    onAlbum: (Album) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = pad,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(albums, key = { it.id }) { album -> AlbumCard(album) { onAlbum(album) } }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(Card)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = Gray)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Buscar", color = Gray)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = White, fontSize = 16.sp),
                cursorBrush = SolidColor(White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Filled.Close, "Borrar", Modifier.size(20.dp).clickable { onChange("") }, tint = Gray)
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(
            text, fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) White else Gray,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.height(2.dp).width(20.dp)
                .background(if (selected) White else Color.Transparent, RoundedCornerShape(1.dp))
        )
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AlbumArt(album.id, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text(album.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(album.artist, fontSize = 13.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(artist.albums.first().id, Modifier.size(56.dp), sizePx = 200, shape = CircleShape)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(countLabel(artist), fontSize = 13.sp, color = Gray)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = DarkGray)
    }
}

private fun countLabel(artist: Artist): String {
    val a = artist.albums.size
    val s = artist.songCount
    return "$a ${if (a == 1) "álbum" else "álbumes"} · $s ${if (s == 1) "canción" else "canciones"}"
}

// ---------------------------------------------------------------- Artista

@Composable
private fun ArtistScreen(
    vm: MainViewModel,
    artist: Artist,
    onAlbum: (Album) -> Unit,
    onRenamed: (String) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        NameDialog(
            "Renombrar artista", artist.name, "Guardar",
            hint = "Si escribes el nombre de otro artista, se unirán en uno solo.",
            onDismiss = { renaming = false },
        ) {
            vm.renameArtist(artist, it)
            onRenamed(it.trim())
            renaming = false
        }
    }
    AlbumGrid(
        albums = artist.albums,
        pad = PaddingValues(20.dp),
        header = {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BackButton(onBack)
                    Spacer(Modifier.weight(1f))
                    HeaderMenu(
                        MenuAction("Añadir a playlist", Icons.AutoMirrored.Filled.PlaylistAdd) {
                            vm.addToPlaylistRequest = artist.songs.map { it.id }
                        },
                        MenuAction("Renombrar o unir artista", Icons.Filled.Edit) { renaming = true },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(artist.name, style = MaterialTheme.typography.headlineLarge)
                Text(countLabel(artist), color = Gray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Reproducir", Icons.Filled.PlayArrow, filled = true) { vm.playArtist(artist, shuffle = false) }
                    Pill("Aleatorio", Icons.Filled.Shuffle) { vm.playArtist(artist, shuffle = true) }
                }
            }
        },
        onAlbum = onAlbum,
    )
}

// ---------------------------------------------------------------- Álbum

@Composable
private fun AlbumScreen(vm: MainViewModel, album: Album, onArtist: (String) -> Unit, onBack: () -> Unit) {
    val currentId = vm.currentItem?.mediaId
    var editing by remember { mutableStateOf(false) }
    if (editing) AlbumEditDialog(vm, album) { editing = false }
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Spacer(Modifier.weight(1f))
                HeaderMenu(
                    MenuAction("Añadir a playlist", Icons.AutoMirrored.Filled.PlaylistAdd) {
                        vm.addToPlaylistRequest = album.songs.map { it.id }
                    },
                    MenuAction("Editar álbum", Icons.Filled.Edit) { editing = true },
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AlbumArt(album.id, Modifier.fillMaxWidth(0.72f).aspectRatio(1f), sizePx = 900)
            }
            Spacer(Modifier.height(20.dp))
            Text(album.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                album.artist, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onArtist(album.artist) }.padding(vertical = 2.dp),
            )
            Text(
                "${album.songs.size} canciones · ${formatTime(album.songs.sumOf { it.durationMs })}",
                color = Gray, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Reproducir", Icons.Filled.PlayArrow, filled = true) { vm.playAlbum(album) }
                Pill("Aleatorio", Icons.Filled.Shuffle) { vm.playAlbum(album, shuffle = true) }
            }
            Spacer(Modifier.height(16.dp))
        }
        itemsIndexed(album.songs, key = { _, s -> s.id }) { i, song ->
            val playing = song.id.toString() == currentId
            SongRow(
                leading = {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        if (playing) Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp), tint = White)
                        else Text(
                            if (song.track > 0) (song.track % 1000).toString() else "${i + 1}",
                            color = Gray, fontSize = 14.sp,
                        )
                    }
                },
                title = song.title,
                subtitle = song.artist.takeIf { it != album.artist },
                playing = playing,
                onClick = { vm.playAlbum(album, startIndex = i) },
            ) {
                Duration(song)
                SongMenu(vm, song)
            }
        }
    }
}

@Composable
private fun SongRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                color = White,
            )
            if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Gray)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
private fun Duration(song: Song) {
    Text(formatTime(song.durationMs), fontSize = 13.sp, color = Gray)
}

/** Menú ⋮ de cada canción: favoritas, añadir a playlist, quitar de la playlist. */
@Composable
private fun SongMenu(vm: MainViewModel, song: Song, playlistId: Long? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, "Opciones", Modifier.size(20.dp), tint = Gray)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val fav = vm.isFavorite(song.id)
            DropdownMenuItem(
                text = { Text(if (fav) "Quitar de favoritas" else "Añadir a favoritas") },
                leadingIcon = { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
                onClick = { vm.toggleFavorite(song.id); open = false },
            )
            DropdownMenuItem(
                text = { Text("Añadir a playlist") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { vm.addToPlaylistRequest = listOf(song.id); open = false },
            )
            DropdownMenuItem(
                text = { Text("Editar información") },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { vm.editSongRequest = song; open = false },
            )
            if (playlistId != null && playlistId != FAVORITES_ID) {
                DropdownMenuItem(
                    text = { Text("Quitar de esta playlist") },
                    leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, null) },
                    onClick = { vm.removeFromPlaylist(playlistId, song.id); open = false },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Playlist

@Composable
private fun PlaylistScreen(vm: MainViewModel, playlist: Playlist, onBack: () -> Unit) {
    val songs = vm.songsOf(playlist)
    val currentId = vm.currentItem?.mediaId
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (renaming) {
        NameDialog("Renombrar", playlist.name, "Guardar", onDismiss = { renaming = false }) {
            vm.renamePlaylist(playlist.id, it)
            renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Card,
            title = { Text("¿Eliminar \"${playlist.name}\"?") },
            text = { Text("Las canciones no se borran del móvil.", color = Gray) },
            confirmButton = {
                TextButton(onClick = { deleting = false; vm.deletePlaylist(playlist.id); onBack() }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar", color = Gray) } },
        )
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.key as? Long
        val t = to.key as? Long
        if (f != null && t != null) vm.moveInPlaylist(playlist.id, f, t)
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(20.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Spacer(Modifier.weight(1f))
                if (!playlist.isFavorites) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opciones") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Renombrar") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar playlist") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; deleting = true },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaylistCover(vm, playlist, Modifier.size(96.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(playlist.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        songCount(songs.size) + if (songs.isNotEmpty()) " · ${formatTime(songs.sumOf { it.durationMs })}" else "",
                        color = Gray, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (songs.isEmpty()) {
                Text(
                    if (playlist.isFavorites) "Aún no tienes favoritas. Toca ♡ en el reproductor o ⋮ en cualquier canción."
                    else "Playlist vacía. Añade canciones con el menú ⋮ de cualquier canción.",
                    color = Gray,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Reproducir", Icons.Filled.PlayArrow, filled = true) { vm.playPlaylist(playlist) }
                    Pill("Aleatorio", Icons.Filled.Shuffle) { vm.playPlaylist(playlist, shuffle = true) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Arrastra ≡ para cambiar el orden", color = DarkGray, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            ReorderableItem(reorder, key = song.id) { dragging ->
                SongRow(
                    leading = { AlbumArt(song.albumId, Modifier.size(44.dp), sizePx = 150, shape = RoundedCornerShape(4.dp)) },
                    title = song.title,
                    subtitle = song.artist,
                    playing = song.id.toString() == currentId,
                    onClick = { vm.playPlaylist(playlist, startIndex = i) },
                    modifier = Modifier.background(if (dragging) Card else Black, RoundedCornerShape(8.dp)),
                ) {
                    SongMenu(vm, song, playlist.id)
                    Icon(
                        Icons.Filled.DragHandle, "Mover",
                        Modifier.draggableHandle().size(40.dp).padding(8.dp),
                        tint = Gray,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Diálogos

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    hint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Nombre", color = Gray) },
                    colors = fieldColors(),
                )
                if (hint != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(hint, color = Gray, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Gray) } },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = White, unfocusedBorderColor = DarkGray, cursorColor = White,
    focusedLabelColor = White, unfocusedLabelColor = Gray,
)

@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
    )
}

private const val EDIT_NOTE = "Los cambios se guardan en EDplayer; tus archivos no se modifican."

@Composable
private fun SongEditDialog(vm: MainViewModel, song: Song, onDone: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var albumArtist by remember { mutableStateOf(song.albumArtist) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Card,
        title = { Text("Editar canción") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditField("Título", title) { title = it }
                EditField("Artista", artist) { artist = it }
                EditField("Álbum", album) { album = it }
                EditField("Artista del álbum", albumArtist) { albumArtist = it }
                Text(EDIT_NOTE, color = Gray, fontSize = 12.sp)
                Text(
                    "Restablecer valores del archivo", color = White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { vm.resetSong(song.id); onDone() }.padding(vertical = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.editSong(song.id, title, artist, album, albumArtist); onDone() }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar", color = Gray) } },
    )
}

@Composable
private fun AlbumEditDialog(vm: MainViewModel, album: Album, onDone: () -> Unit) {
    var title by remember { mutableStateOf(album.title) }
    var artist by remember { mutableStateOf(album.artist) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Card,
        title = { Text("Editar álbum") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditField("Título del álbum", title) { title = it }
                EditField("Artista del álbum", artist) { artist = it }
                Text("Se aplica a las ${album.songs.size} canciones. $EDIT_NOTE", color = Gray, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.editAlbum(album, title, artist); onDone() }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar", color = Gray) } },
    )
}

@Composable
private fun AddToPlaylistDialog(vm: MainViewModel, songIds: List<Long>, onDone: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Nueva playlist", "", "Crear", onDismiss = onDone) {
            vm.createPlaylist(it, songIds)
            onDone()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Card,
        title = { Text("Añadir a playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item { DialogRow(Icons.Filled.Add, "Nueva playlist") { creating = true } }
                items(vm.playlists, key = { it.id }) { p ->
                    DialogRow(
                        if (p.isFavorites) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                        p.name,
                    ) {
                        vm.addToPlaylist(p.id, songIds)
                        onDone()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancelar", color = Gray) } },
    )
}

@Composable
private fun DialogRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = White)
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


// ---------------------------------------------------------------- Reproductor

/** Posición y duración de la canción actual, actualizadas cada medio segundo. */
@Composable
private fun rememberProgress(vm: MainViewModel): Pair<Long, Long> {
    val controller = vm.controller
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller) {
        while (controller != null) {
            position = controller.currentPosition
            duration = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            delay(500)
        }
    }
    return position to duration
}

@Composable
private fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit) {
    val meta = vm.currentItem?.mediaMetadata ?: return
    val (position, duration) = rememberProgress(vm)
    Column(Modifier.background(Color(0xFF0E0E0E)).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Line)) {
            Box(
                Modifier.fillMaxWidth(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f)
                    .fillMaxHeight().background(White)
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(meta.extras?.getLong(EXTRA_ALBUM_ID), Modifier.size(44.dp), sizePx = 150, shape = RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(meta.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Gray)
            }
            IconButton(onClick = vm::togglePlay) {
                Icon(
                    if (vm.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (vm.playWhenReady) "Pausa" else "Reproducir", Modifier.size(30.dp),
                )
            }
            IconButton(onClick = { vm.next() }) {
                Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(28.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(
    vm: MainViewModel,
    onArtist: (String) -> Unit,
    onQueue: () -> Unit,
    onAlbum: (Long) -> Unit,
    onHome: () -> Unit,
    onClose: () -> Unit,
) {
    val controller = vm.controller
    val meta = vm.currentItem?.mediaMetadata
    val (position, duration) = rememberProgress(vm)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val albumId = meta?.extras?.getLong(EXTRA_ALBUM_ID)

    // Gestos: ← siguiente, → anterior, ↑ abrir álbum, ↓ pantalla principal
    val scope = rememberCoroutineScope()
    val swipeX = remember { Animatable(0f) }
    val swipeY = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val currentOnAlbum by rememberUpdatedState(onAlbum)
    val currentOnHome by rememberUpdatedState(onHome)
    val currentAlbumId by rememberUpdatedState(albumId)
    val swipe = Modifier.pointerInput(Unit) {
        var dx = 0f
        var dy = 0f
        fun reset() {
            scope.launch { swipeX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            scope.launch { swipeY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
        }
        detectDragGestures(
            onDragStart = { dx = 0f; dy = 0f },
            onDragEnd = {
                val horizontal = abs(dx) > abs(dy)
                when {
                    horizontal && dx < -threshold -> vm.next()
                    horizontal && dx > threshold -> vm.previousTrack()
                    !horizontal && dy < -threshold -> currentAlbumId?.let(currentOnAlbum)
                    !horizontal && dy > threshold -> currentOnHome()
                }
                reset()
            },
            onDragCancel = { reset() },
        ) { change, amount ->
            change.consume()
            dx += amount.x
            dy += amount.y
            scope.launch {
                swipeX.snapTo(dx)
                swipeY.snapTo(dy)
            }
        }
    }

    Column(swipe.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, Modifier.offset(x = (-12).dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("REPRODUCIENDO", fontSize = 11.sp, letterSpacing = 2.sp, color = Gray, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onQueue, Modifier.offset(x = 12.dp)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Ver cola", Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        GlowingAlbumArt(
            meta?.extras?.getLong(EXTRA_ALBUM_ID),
            playing = vm.playWhenReady,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1f)
                .graphicsLayer {
                    val x = swipeX.value
                    val y = swipeY.value
                    translationX = x * 0.6f
                    translationY = y * 0.35f
                    alpha = 1f - (maxOf(abs(x), abs(y)) / (threshold * 4)).coerceIn(0f, 0.5f)
                },
        )
        Spacer(Modifier.weight(1f))
        val songId = vm.currentItem?.mediaId?.toLongOrNull()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    meta?.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val artist = meta?.artist?.toString() ?: ""
                Text(
                    artist, fontSize = 16.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = artist.isNotEmpty()) {
                        // Abre el artista del álbum si existe, si no el de la pista
                        val albumId = meta?.extras?.getLong(EXTRA_ALBUM_ID)
                        onArtist(albumId?.let { vm.album(it)?.artist } ?: artist)
                    },
                )
            }
            if (songId != null) {
                IconButton(onClick = { vm.addToPlaylistRequest = listOf(songId) }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Añadir a playlist", tint = Gray)
                }
                val fav = vm.isFavorite(songId)
                IconButton(onClick = { vm.toggleFavorite(songId) }, Modifier.offset(x = 8.dp)) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (fav) "Quitar de favoritas" else "Añadir a favoritas",
                        tint = if (fav) White else Gray,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val sliderColors = SliderDefaults.colors(
            thumbColor = White, activeTrackColor = White, inactiveTrackColor = Line,
        )
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { controller?.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0,
            interactionSource = interaction,
            colors = sliderColors,
            thumb = {
                Box(Modifier.size(14.dp).background(White, CircleShape))
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state, colors = sliderColors,
                    modifier = Modifier.height(3.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                )
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), fontSize = 12.sp, color = Gray)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), fontSize = 12.sp, color = Gray)
        }
        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::toggleShuffle) {
                Icon(Icons.Filled.Shuffle, "Aleatorio", Modifier.size(24.dp), tint = if (vm.shuffle) White else DarkGray)
            }
            IconButton(onClick = { vm.previous() }, Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(38.dp))
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(White).clickable(onClick = vm::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (vm.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (vm.playWhenReady) "Pausa" else "Reproducir", Modifier.size(38.dp), tint = Black,
                )
            }
            IconButton(onClick = { vm.next() }, Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(38.dp))
            }
            IconButton(onClick = vm::cycleRepeat) {
                Icon(
                    if (vm.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repetir", Modifier.size(24.dp),
                    tint = if (vm.repeatMode != Player.REPEAT_MODE_OFF) White else DarkGray,
                )
            }
        }
        Spacer(Modifier.weight(0.6f))
    }
}

// ---------------------------------------------------------------- Cola

@Composable
private fun QueueScreen(vm: MainViewModel, onClose: () -> Unit) {
    val meta = vm.currentItem?.mediaMetadata
    val controller = vm.controller
    var entries by remember { mutableStateOf(emptyList<MainViewModel.QueueEntry>()) }
    LaunchedEffect(vm.queueVersion, controller) { entries = vm.upcoming() }

    // Solo se puede reordenar sin aleatorio: con aleatorio el orden lo decide el reproductor
    val canReorder = !vm.shuffle
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val fi = entries.indexOfFirst { it.item.mediaId == from.key }
        val ti = entries.indexOfFirst { it.item.mediaId == to.key }
        if (fi >= 0 && ti >= 0 && canReorder) {
            val first = entries.first().index
            vm.moveInQueue(entries[fi].index, entries[ti].index)
            entries = entries.toMutableList().apply { add(ti, removeAt(fi)) }
                .mapIndexed { pos, e -> e.copy(index = first + pos) }
        }
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(20.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp))
                }
                Text("Cola", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel("SONANDO AHORA")
            if (meta != null) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(meta.extras?.getLong(EXTRA_ALBUM_ID), Modifier.size(52.dp), sizePx = 150, shape = RoundedCornerShape(4.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(meta.title?.toString() ?: "", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(meta.artist?.toString() ?: "", fontSize = 13.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.GraphicEq, null, Modifier.size(20.dp), tint = White)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("A CONTINUACIÓN · ${entries.size}")
                Spacer(Modifier.weight(1f))
                if (vm.shuffle) Text("Aleatorio activado", fontSize = 12.sp, color = DarkGray)
            }
            if (entries.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("No hay más canciones en la cola.", color = Gray)
            }
            Spacer(Modifier.height(4.dp))
        }
        items(entries, key = { it.item.mediaId }) { entry ->
            ReorderableItem(reorder, key = entry.item.mediaId, enabled = canReorder) { dragging ->
                val m = entry.item.mediaMetadata
                SongRow(
                    leading = {
                        AlbumArt(m.extras?.getLong(EXTRA_ALBUM_ID), Modifier.size(44.dp), sizePx = 150, shape = RoundedCornerShape(4.dp))
                    },
                    title = m.title?.toString() ?: "",
                    subtitle = m.artist?.toString(),
                    playing = false,
                    onClick = { vm.playQueueItem(entry.index) },
                    modifier = Modifier.background(if (dragging) Card else Black, RoundedCornerShape(8.dp)),
                ) {
                    IconButton(onClick = { vm.removeFromQueue(entry.index) }, Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, "Quitar de la cola", Modifier.size(18.dp), tint = Gray)
                    }
                    if (canReorder) {
                        Icon(
                            Icons.Filled.DragHandle, "Mover",
                            Modifier.draggableHandle().size(40.dp).padding(8.dp),
                            tint = Gray,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, letterSpacing = 1.5.sp, color = Gray, fontWeight = FontWeight.SemiBold)
}

// ---------------------------------------------------------------- Componentes

@Composable
private fun Pill(
    text: String,
    icon: ImageVector,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    val m = Modifier.height(38.dp)
    val padding = PaddingValues(horizontal = 16.dp)
    if (filled) {
        Button(onClick, m, enabled = enabled, shape = CircleShape, contentPadding = padding, content = content)
    } else {
        OutlinedButton(
            onClick, m, enabled = enabled, shape = CircleShape, contentPadding = padding,
            border = BorderStroke(1.dp, if (enabled) Color(0xFF5A5A5A) else Line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
            content = content,
        )
    }
}

private class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun HeaderMenu(vararg actions: MenuAction) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Opciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label) },
                    leadingIcon = { Icon(a.icon, null) },
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, Modifier.offset(x = (-12).dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
