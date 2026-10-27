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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private enum class Tab(val label: String) { Albums("Álbumes"), Artists("Artistas"), Songs("Canciones") }

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
    var showPlayer by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showPlayer || openAlbumId != null || openArtist != null) {
        when {
            showPlayer -> showPlayer = false
            openAlbumId != null -> openAlbumId = null
            else -> openArtist = null
        }
    }

    val goArtist: (String) -> Unit = { openArtist = it; openAlbumId = null; showPlayer = false }

    Scaffold(
        containerColor = Black,
        bottomBar = {
            if (!showPlayer && vm.currentItem != null) MiniPlayer(vm) { showPlayer = true }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val album = openAlbumId?.let { vm.album(it) }
            val artist = openArtist?.let { vm.artist(it) }
            when {
                showPlayer -> PlayerScreen(vm, onArtist = goArtist) { showPlayer = false }
                album != null -> AlbumScreen(vm, album, onArtist = goArtist) { openAlbumId = null }
                artist != null -> ArtistScreen(vm, artist, onAlbum = { openAlbumId = it.id }) { openArtist = null }
                else -> HomeScreen(vm, onAlbum = { openAlbumId = it.id }, onArtist = goArtist)
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
private fun HomeScreen(vm: MainViewModel, onAlbum: (Album) -> Unit, onArtist: (String) -> Unit) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
                    trailing = formatTime(song.durationMs),
                    playing = song.id.toString() == currentId,
                ) { vm.playSongs(songs, i) }
            }
        }
    }
}

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
private fun ArtistScreen(vm: MainViewModel, artist: Artist, onAlbum: (Album) -> Unit, onBack: () -> Unit) {
    AlbumGrid(
        albums = artist.albums,
        pad = PaddingValues(20.dp),
        header = {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                BackButton(onBack)
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
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item {
            BackButton(onBack)
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
                trailing = formatTime(song.durationMs),
                playing = playing,
            ) { vm.playAlbum(album, startIndex = i) }
        }
    }
}

@Composable
private fun SongRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    trailing: String,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 9.dp),
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
        Text(trailing, fontSize = 13.sp, color = Gray)
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
private fun PlayerScreen(vm: MainViewModel, onArtist: (String) -> Unit, onClose: () -> Unit) {
    val controller = vm.controller
    val meta = vm.currentItem?.mediaMetadata
    val (position, duration) = rememberProgress(vm)
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, Modifier.offset(x = (-12).dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("REPRODUCIENDO", fontSize = 11.sp, letterSpacing = 2.sp, color = Gray, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(36.dp))
        }
        Spacer(Modifier.weight(1f))
        AlbumArt(
            meta?.extras?.getLong(EXTRA_ALBUM_ID),
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1f),
            sizePx = 1000,
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.weight(1f))
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
