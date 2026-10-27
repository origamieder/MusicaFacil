package com.musicafacil.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

private val Orange = Color(0xFFFF7A1A)
private val AppColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color.Black,
    secondary = Orange,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF2A2A2A),
    surfaceContainer = Color(0xFF1E1E1E),
)

private fun audioPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED

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

    var openAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showPlayer || openAlbumId != null) {
        if (showPlayer) showPlayer = false else openAlbumId = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showPlayer && vm.currentItem != null) MiniPlayer(vm) { showPlayer = true }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val album = openAlbumId?.let { vm.album(it) }
            when {
                showPlayer -> PlayerScreen(vm) { showPlayer = false }
                album != null -> AlbumScreen(vm, album) { openAlbumId = null }
                else -> HomeScreen(vm) { openAlbumId = it.id }
            }
        }
    }
}

// ---------------------------------------------------------------- Permiso

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.LibraryMusic, null, Modifier.size(96.dp), tint = Orange)
        Spacer(Modifier.height(24.dp))
        Text(
            "Para ver tu música, la app necesita permiso para leer tus archivos de audio.",
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        BigButton("Dar permiso", Icons.Filled.Check, onClick = onAsk)
    }
}

// ---------------------------------------------------------------- Inicio

@Composable
private fun HomeScreen(vm: MainViewModel, onAlbum: (Album) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSongs by rememberSaveable { mutableStateOf(false) }

    val q = query.trim().lowercase()
    val albums = remember(vm.albums, q) {
        if (q.isEmpty()) vm.albums
        else vm.albums.filter { q in it.title.lowercase() || q in it.artist.lowercase() }
    }
    val songs = remember(vm.songs, q, showSongs) {
        if (!showSongs) emptyList()
        else vm.songs
            .filter { q.isEmpty() || q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
            .sortedBy { it.title.lowercase() }
    }

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mi música", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (vm.loading) "Buscando canciones…"
                        else "${vm.songs.size} canciones · ${vm.albums.size} álbumes",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = vm::loadLibrary) {
                    Icon(Icons.Filled.Refresh, "Volver a buscar canciones")
                }
            }
            Spacer(Modifier.height(16.dp))
            BigButton("Todo en aleatorio", Icons.Filled.Shuffle, enabled = vm.songs.isNotEmpty(), onClick = vm::shuffleAll)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar álbum, artista o canción") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Borrar") }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("Álbumes", !showSongs, Modifier.weight(1f)) { showSongs = false }
                TabChip("Canciones", showSongs, Modifier.weight(1f)) { showSongs = true }
            }
            Spacer(Modifier.height(12.dp))
            if (vm.loaded && vm.songs.isEmpty()) {
                Text(
                    "No se ha encontrado música. Copia tus archivos FLAC o MP3 a la carpeta Music del teléfono y pulsa ⟳.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val pad = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
    if (!showSongs) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = pad,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(albums, key = { it.id }) { album -> AlbumCard(album) { onAlbum(album) } }
        }
    } else {
        val currentId = vm.currentItem?.mediaId
        LazyColumn(contentPadding = pad) {
            item { header() }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    leading = { AlbumArt(song.albumId, Modifier.size(48.dp), sizePx = 150) },
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
private fun TabChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = if (selected) ButtonDefaults.buttonColors()
    else ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    Button(onClick = onClick, modifier = modifier.height(48.dp), colors = colors) {
        Text(text, fontSize = 16.sp)
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AlbumArt(album.id, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------------------------------------------------------------- Álbum

@Composable
private fun AlbumScreen(vm: MainViewModel, album: Album, onBack: () -> Unit) {
    val currentId = vm.currentItem?.mediaId
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumArt(album.id, Modifier.fillMaxWidth(0.7f).aspectRatio(1f), sizePx = 800)
                Spacer(Modifier.height(16.dp))
                Text(album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(album.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${album.songs.size} canciones · ${formatTime(album.songs.sumOf { it.durationMs })}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Reproducir", Icons.Filled.PlayArrow, Modifier.weight(1f)) { vm.playAlbum(album) }
                    BigButton("Aleatorio", Icons.Filled.Shuffle, Modifier.weight(1f), filled = false) {
                        vm.playAlbum(album, shuffle = true)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        itemsIndexed(album.songs, key = { _, s -> s.id }) { i, song ->
            SongRow(
                leading = {
                    Text(
                        if (song.track > 0) (song.track % 1000).toString() else "${i + 1}",
                        Modifier.width(32.dp), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                title = song.title,
                subtitle = song.artist.takeIf { it != album.artist },
                trailing = formatTime(song.durationMs),
                playing = song.id.toString() == currentId,
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .background(if (playing) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp,
                color = if (playing) Orange else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            )
            if (subtitle != null) Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Reproductor

@Composable
private fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit) {
    val meta = vm.currentItem?.mediaMetadata ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(meta.extras?.getLong(EXTRA_ALBUM_ID), Modifier.size(52.dp), sizePx = 150)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    meta.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = vm::togglePlay, Modifier.size(56.dp)) {
                Icon(
                    if (vm.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (vm.playWhenReady) "Pausa" else "Reproducir", Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { vm.next() }, Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun PlayerScreen(vm: MainViewModel, onClose: () -> Unit) {
    val controller = vm.controller
    val meta = vm.currentItem?.mediaMetadata
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(controller) {
        while (controller != null) {
            position = controller.currentPosition
            duration = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose, Modifier.size(56.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "Cerrar", Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.weight(0.5f))
        AlbumArt(
            meta?.extras?.getLong(EXTRA_ALBUM_ID),
            Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f),
            sizePx = 1000,
        )
        Spacer(Modifier.weight(0.5f))
        Text(
            meta?.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(meta?.artist?.toString() ?: "", style = MaterialTheme.typography.titleMedium, color = Orange, maxLines = 1)
        Text(
            meta?.albumTitle?.toString() ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))

        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { controller?.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::toggleShuffle, Modifier.size(56.dp)) {
                Icon(
                    Icons.Filled.Shuffle, "Aleatorio", Modifier.size(30.dp),
                    tint = if (vm.shuffle) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.previous() }, Modifier.size(64.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(44.dp))
            }
            FilledIconButton(onClick = vm::togglePlay, Modifier.size(88.dp)) {
                Icon(
                    if (vm.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (vm.playWhenReady) "Pausa" else "Reproducir", Modifier.size(52.dp),
                )
            }
            IconButton(onClick = { vm.next() }, Modifier.size(64.dp)) {
                Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(44.dp))
            }
            IconButton(onClick = vm::cycleRepeat, Modifier.size(56.dp)) {
                Icon(
                    if (vm.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repetir", Modifier.size(30.dp),
                    tint = if (vm.repeatMode != Player.REPEAT_MODE_OFF) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(0.5f))
    }
}

// ---------------------------------------------------------------- Utilidades

@Composable
private fun BigButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    filled: Boolean = true,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
    val m = modifier.height(64.dp)
    val shape = RoundedCornerShape(20.dp)
    if (filled) Button(onClick, m, enabled = enabled, shape = shape, content = content)
    else OutlinedButton(onClick, m, enabled = enabled, shape = shape, content = content)
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
