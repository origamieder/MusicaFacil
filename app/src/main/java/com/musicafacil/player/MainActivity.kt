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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.core.FastOutSlowInEasing
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

/** ¿Está la app en pantalla? Si no, al acabar un disco se sigue sola con el mismo artista. */
object AppVisibility {
    @Volatile
    var visible = false
}

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        AppVisibility.visible = true
    }

    override fun onStop() {
        AppVisibility.visible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors, typography = AppType) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }
}

// ---------------------------------------------------------------- Estilo

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Gray = Color(0xFF8E8E8E)
private val DarkGray = Color(0xFF4A4A4A)
private val Card = Color(0xFF171717)
private val Line = Color(0xFF242424)

// Capas de fondo: no todo negro, para que se distingan las secciones
private val Bg = Color(0xFF080808)
private val Surface1 = Color(0xFF121212)
private val Surface2 = Color(0xFF1C1C1C)

private val AppColors = darkColorScheme(
    primary = White,
    onPrimary = Black,
    secondary = White,
    onSecondary = Black,
    background = Bg,
    onBackground = White,
    surface = Bg,
    onSurface = White,
    surfaceVariant = Card,
    onSurfaceVariant = Gray,
    surfaceContainer = Surface2,
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

enum class AlbumView(val label: String, val icon: ImageVector) {
    List("Lista", Icons.AutoMirrored.Filled.ViewList),
    Small("Cuadrícula pequeña", Icons.Filled.Apps),
    Medium("Cuadrícula mediana", Icons.Filled.ViewComfy),
    Large("Cuadrícula grande", Icons.Filled.GridView),
}

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
    var showFolders by rememberSaveable { mutableStateOf(false) }
    var reveal by remember { mutableFloatStateOf(0f) }
    var miniFromPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(showPlayer) { if (!showPlayer) reveal = 0f }

    BackHandler(enabled = showFolders || showQueue || showPlayer || openAlbumId != null || openPlaylistId != null || openArtist != null) {
        when {
            showFolders -> showFolders = false
            showQueue -> showQueue = false
            showPlayer -> { miniFromPlayer = true; showPlayer = false }
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
    vm.whatNext?.let { WhatNextDialog(vm, it) }

    // Ventana del sistema para permitir modificar los archivos FLAC
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onWritePermission(r.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(vm.writeRequest) {
        vm.writeRequest?.let { writeLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) }
    }

    val goArtist: (String) -> Unit = { openArtist = it; openAlbumId = null; showPlayer = false; showQueue = false }
    // Si el mini reproductor aparece porque se cierra el reproductor, va sin animación de entrada
    // (la portada ya ha "volado" hasta su sitio)
    val closePlayer = { miniFromPlayer = true; showPlayer = false }

    // Pantalla actual como texto: permite animar el paso de una a otra y recordar el estado de cada una
    val screen = when {
        showFolders -> "folders"
        showQueue -> "queue"
        showPlayer -> "player"
        openAlbumId != null && vm.album(openAlbumId!!) != null -> "album:$openAlbumId"
        openPlaylistId != null && vm.playlist(openPlaylistId!!) != null -> "playlist:$openPlaylistId"
        openArtist != null && vm.artist(openArtist!!) != null -> "artist:$openArtist"
        else -> "home"
    }
    // Resplandor de fondo: el disco abierto, el artista abierto o lo que está sonando
    val ambientAlbum = when {
        screen.startsWith("album:") -> openAlbumId
        screen.startsWith("artist:") -> openArtist?.let { vm.artist(it) }?.albums?.firstOrNull()?.id
        else -> vm.currentItem?.mediaMetadata?.extras?.getLong(EXTRA_ALBUM_ID)
    }
    val stateHolder = rememberSaveableStateHolder()

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color(0xFF151515), 0.45f to Bg, 1f to Bg)
        )
    ) {
        AmbientBackdrop(ambientAlbum, Modifier.fillMaxWidth().height(420.dp).offset(y = (-90).dp))

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                AnimatedVisibility(
                    visible = !showPlayer && !showQueue && vm.currentItem != null,
                    enter = if (miniFromPlayer) fadeIn(tween(1))
                    else slideInVertically(bouncy(Spring.StiffnessLow)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150)),
                ) { MiniPlayer(vm) { miniFromPlayer = false; showPlayer = true } }
            },
        ) { padding ->
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.padding(padding).fillMaxSize(),
                transitionSpec = {
                    when {
                        // Las salidas del reproductor ya tienen su propia animación (gestos)
                        initialState == "player" || initialState == "queue" || targetState == "queue" ->
                            EnterTransition.None togetherWith ExitTransition.None
                        targetState == "player" ->
                            (slideInVertically(bouncy(Spring.StiffnessLow)) { it / 3 } + fadeIn(tween(180))) togetherWith
                                fadeOut(tween(150))
                        else ->
                            (fadeIn(tween(220)) + scaleIn(bouncy(Spring.StiffnessMediumLow), initialScale = 0.94f)) togetherWith
                                fadeOut(tween(120))
                    }
                },
                label = "pantalla",
            ) { s ->
                stateHolder.SaveableStateProvider(s) {
                    when {
                        s == "folders" -> FoldersScreen(vm) { showFolders = false }
                        s == "queue" -> QueueScreen(vm) { showQueue = false }
                        s == "player" -> Box(Modifier.fillMaxSize()) {
                            // Al deslizar hacia abajo, debajo aparece el disco actual, de borroso a nítido
                            val underAlbum = vm.currentItem?.mediaMetadata?.extras?.getLong(EXTRA_ALBUM_ID)?.let { vm.album(it) }
                            val showUnder by remember { derivedStateOf { reveal > 0.001f } }
                            if (showUnder && underAlbum != null) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer {
                                        alpha = reveal
                                        val r = (1f - reveal) * 28.dp.toPx()
                                        renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                                    }
                                ) {
                                    AlbumScreen(vm, underAlbum, onArtist = goArtist) {}
                                }
                            }
                            PlayerScreen(
                                vm,
                                onArtist = { miniFromPlayer = true; goArtist(it) },
                                onQueue = { showQueue = true },
                                onAlbum = { miniFromPlayer = true; openAlbumId = it; showPlayer = false },
                                onHome = {
                                    miniFromPlayer = true
                                    showPlayer = false; openAlbumId = null; openPlaylistId = null; openArtist = null
                                },
                                onReveal = { reveal = it },
                            ) { closePlayer() }
                        }
                        s.startsWith("album:") -> vm.album(s.substringAfter(':').toLong())?.let {
                            AlbumScreen(vm, it, onArtist = goArtist) { openAlbumId = null }
                        }
                        s.startsWith("playlist:") -> vm.playlist(s.substringAfter(':').toLong())?.let {
                            PlaylistScreen(vm, it) { openPlaylistId = null }
                        }
                        s.startsWith("artist:") -> vm.artist(s.substringAfter(':'))?.let {
                            ArtistScreen(
                                vm, it,
                                onAlbum = { a -> openAlbumId = a.id },
                                onRenamed = { name -> openArtist = name },
                            ) { openArtist = null }
                        }
                        else -> HomeScreen(
                            vm,
                            onAlbum = { openAlbumId = it.id },
                            onArtist = goArtist,
                            onPlaylist = { openPlaylistId = it },
                            onFolders = { showFolders = true },
                        )
                    }
                }
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
    onFolders: () -> Unit,
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

    var letter by rememberSaveable { mutableStateOf<Char?>(null) }
    val shuffled = remember(vm.albums, vm.albumShuffleSeed) { vm.shuffledAlbums() }
    val availableLetters = remember(vm.albums) { vm.albums.map { indexLetter(it.title) }.toSet() }
    val albums = remember(shuffled, q, letter) {
        shuffled.filter {
            (q.isEmpty() || q in it.title.lowercase() || q in it.artist.lowercase()) &&
                (letter == null || indexLetter(it.title) == letter)
        }
    }
    val artists = remember(vm.artists, q) {
        if (q.isEmpty()) vm.artists else vm.artists.filter { q in it.name.lowercase() }
    }
    val songs = remember(vm.songs, q) {
        vm.songs
            .filter { q.isEmpty() || q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
            .sortedBy { it.title.lowercase() }
    }


    val pad = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp)
    val currentId = vm.currentItem?.mediaId
    val emptyLibrary: @Composable () -> Unit = {
        if (vm.loaded && vm.songs.isEmpty()) {
            Text(
                "No hay música. Copia tus FLAC o MP3 al móvil (por ejemplo a la carpeta Music) y toca aquí para volver a buscar.",
                color = Gray, modifier = Modifier.padding(bottom = 16.dp).clickable { vm.loadLibrary() },
            )
        }
    }
    @Composable
    fun TabContent(t: Tab) {
        when (t) {
            Tab.Albums -> Box(Modifier.fillMaxSize()) {
                val albumHeader: @Composable () -> Unit = {
                    Column {
                        emptyLibrary()
                        SectionHeader("${albums.size} álbumes", "orden aleatorio")
                        if (letter != null) {
                            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (albums.isEmpty()) "Ningún disco empieza por «$letter»" else "Discos que empiezan por «$letter»",
                                    color = Gray, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                )
                                Pill("Todos", Icons.Filled.Close) { letter = null }
                            }
                        }
                    }
                }
                // Algo más de margen a la derecha para la barra de letras
                AlbumGrid(albums, vm.albumView, PaddingValues(start = 20.dp, end = 30.dp, top = 16.dp, bottom = 20.dp), albumHeader, onAlbum)
                LetterScrubber(
                    available = availableLetters,
                    selected = letter,
                    onSelect = { letter = it },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, bottom = 12.dp).fillMaxHeight(),
                )
            }
            Tab.Artists -> LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                item { emptyLibrary(); SectionHeader("${artists.size} artistas", null) }
                itemsIndexed(artists, key = { _, a -> a.name }) { i, artist ->
                    ArtistRow(artist, Modifier.animateItem(placementSpec = bouncy()).grouped(i, artists.size)) { onArtist(artist.name) }
                }
            }
            Tab.Songs -> LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                item { emptyLibrary(); SectionHeader("${songs.size} canciones", null) }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                    SongRow(
                        leading = { AlbumArt(song.albumId, Modifier.size(44.dp), sizePx = 150, shape = RoundedCornerShape(6.dp)) },
                        title = song.title,
                        subtitle = "${song.artist} · ${song.album}",
                        playing = song.id.toString() == currentId,
                        onClick = { vm.playSongs(songs, i) },
                        modifier = Modifier.grouped(i, songs.size),
                    ) {
                        Duration(song)
                        SongMenu(vm, song)
                    }
                }
            }
            Tab.Playlists -> LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                item { SectionHeader("${vm.playlists.size} playlists", null) }
                itemsIndexed(vm.playlists, key = { _, p -> p.id }) { i, p ->
                    PlaylistRow(vm, p, Modifier.animateItem(placementSpec = bouncy()).grouped(i, vm.playlists.size)) { onPlaylist(p.id) }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1)
                            .border(1.dp, Line, RoundedCornerShape(18.dp))
                            .bouncyClick(0.97f) { creating = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(White),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Add, null, tint = Black) }
                        Spacer(Modifier.width(14.dp))
                        Text("Nueva playlist", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // ----- Cabecera fija (título, buscador y pestañas); debajo, el contenido de cada pestaña
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Biblioteca", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        if (vm.loading) "Buscando música…"
                        else "${vm.songs.size} canciones · ${vm.artists.size} artistas",
                        color = Gray, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Surface2).bouncyClick(0.88f, onClick = onFolders),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Settings, "Ajustes", Modifier.size(20.dp), tint = White) }
                Spacer(Modifier.width(8.dp))
                Pill("Aleatorio", Icons.Filled.Shuffle, filled = true, enabled = vm.songs.isNotEmpty(), onClick = vm::shuffleAll)
            }
            Spacer(Modifier.height(18.dp))
            SearchField(query) { query = it }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentedTabs(
                    labels = Tab.entries.map { it.label },
                    selected = tab.ordinal,
                    onSelect = { tab = Tab.entries[it] },
                    modifier = Modifier.weight(1f),
                )
                if (tab == Tab.Albums) AlbumViewMenu(vm)
            }
        }

        // ----- Deslizar a los lados cambia de pestaña. El contenido sigue al dedo y,
        // al soltar, cambia con un rebote (o vuelve a su sitio si no se ha deslizado bastante).
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        val threshold = with(LocalDensity.current) { 80.dp.toPx() }
        val currentTab by rememberUpdatedState(tab)
        val tabHolder = rememberSaveableStateHolder()
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            val i = currentTab.ordinal
                            val target = when {
                                total < -threshold && i < Tab.entries.lastIndex -> i + 1
                                total > threshold && i > 0 -> i - 1
                                else -> i
                            }
                            if (target != i) tab = Tab.entries[target]
                            scope.launch { drag.animateTo(0f, bouncy()) }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, bouncy()) } },
                    ) { change, amount ->
                        total += amount
                        val i = currentTab.ordinal
                        // En los extremos (primera y última pestaña) cuesta más: efecto goma
                        val atEdge = (total > 0 && i == 0) || (total < 0 && i == Tab.entries.lastIndex)
                        change.consume()
                        scope.launch { drag.snapTo(total * if (atEdge) 0.15f else 0.35f) }
                    }
                }
                .graphicsLayer { translationX = drag.value },
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(bouncy(Spring.StiffnessMediumLow)) { w -> dir * w / 3 } + fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(tween(160)) { w -> -dir * w / 4 } + fadeOut(tween(120)))
                },
                label = "pestañas",
            ) { t ->
                // Cada pestaña recuerda su propia posición de scroll
                tabHolder.SaveableStateProvider(t.name) {
                    TabContent(t)
                }
            }
        }
    }
}

@Composable
private fun PlaylistCover(vm: MainViewModel, playlist: Playlist, modifier: Modifier) {
    val first = vm.songsOf(playlist).firstOrNull()
    if (playlist.isFavorites || first == null) {
        Box(
            modifier.clip(RoundedCornerShape(10.dp)).background(
                Brush.linearGradient(listOf(Color(0xFF2C2C2C), Color(0xFF151515)))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playlist.isFavorites) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                null, Modifier.fillMaxSize(0.4f), tint = White,
            )
        }
    } else {
        AlbumArt(first.albumId, modifier, sizePx = 200, shape = RoundedCornerShape(10.dp))
    }
}

@Composable
private fun PlaylistRow(vm: MainViewModel, playlist: Playlist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().bouncyClick(0.98f, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(vm, playlist, Modifier.size(52.dp))
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
    view: AlbumView,
    pad: PaddingValues,
    header: @Composable () -> Unit,
    onAlbum: (Album) -> Unit,
    /** Texto bajo el título de cada disco (por defecto, el artista). */
    subtitle: (Album) -> String = { it.artist },
    onLongAlbum: ((Album) -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = when (view) {
            AlbumView.List -> GridCells.Fixed(1)
            AlbumView.Small -> GridCells.Adaptive(76.dp)
            AlbumView.Medium -> GridCells.Adaptive(100.dp)
            AlbumView.Large -> GridCells.Adaptive(150.dp)
        },
        contentPadding = pad,
        horizontalArrangement = Arrangement.spacedBy(if (view == AlbumView.Small) 10.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(
            when (view) {
                AlbumView.List -> 0.dp
                AlbumView.Small -> 12.dp
                else -> 12.dp
            }
        ),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        itemsIndexed(albums, key = { _, a -> a.id }) { i, album ->
            val long = onLongAlbum?.let { { it(album) } }
            val anim = Modifier.animateItem(fadeInSpec = tween(220), placementSpec = bouncy(), fadeOutSpec = tween(120))
            if (view == AlbumView.List) AlbumListRow(album, subtitle(album), long, anim.grouped(i, albums.size)) { onAlbum(album) }
            else AlbumCard(album, view, subtitle(album), long, anim) { onAlbum(album) }
        }
    }
}

@Composable
private fun AlbumViewMenu(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(vm.albumView.icon, "Cambiar vista", tint = Gray)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AlbumView.entries.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.label, fontWeight = if (v == vm.albumView) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { Icon(v.icon, null) },
                    trailingIcon = { if (v == vm.albumView) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) },
                    onClick = { vm.changeAlbumView(v); open = false },
                )
            }
            HorizontalDivider(color = Line)
            DropdownMenuItem(
                text = { Text("Mezclar de nuevo") },
                leadingIcon = { Icon(Icons.Filled.Shuffle, null) },
                onClick = { vm.reshuffleAlbums(); open = false },
            )
        }
    }
}

private val IndexLetters = listOf('#') + ('A'..'Z')

/** Barra de letras del borde derecho: tocar o deslizar elige la letra; tocar la misma la quita. */
@Composable
private fun LetterScrubber(
    available: Set<Char>,
    selected: Char?,
    onSelect: (Char?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var height by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf<Char?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }

    fun letterAt(y: Float) =
        IndexLetters[((y / height) * IndexLetters.size).toInt().coerceIn(0, IndexLetters.lastIndex)]

    fun pick(l: Char) {
        if (l != dragging) {
            dragging = l
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnSelect(l)
        }
    }

    Box(modifier) {
        Column(
            Modifier.fillMaxHeight().width(24.dp)
                .clip(CircleShape).background(Surface1.copy(alpha = 0.85f))
                .padding(vertical = 6.dp)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val l = letterAt(o.y)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnSelect(if (l == currentSelected) null else l)
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> dragY = o.y; pick(letterAt(o.y)) },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        change.consume()
                        dragY = change.position.y
                        pick(letterAt(change.position.y))
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IndexLetters.forEach { l ->
                Text(
                    l.toString(),
                    fontSize = 10.sp,
                    fontWeight = if (l == selected) FontWeight.Black else FontWeight.SemiBold,
                    color = when {
                        l == selected -> White
                        l in available -> Gray
                        else -> Color(0xFF333333)
                    },
                )
            }
        }
        // Letra grande junto al dedo mientras se desliza (aparece con un rebote)
        var lastLetter by remember { mutableStateOf('A') }
        dragging?.let { lastLetter = it }
        val density = LocalDensity.current
        AnimatedVisibility(
            visible = dragging != null,
            enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = 600f)) + fadeIn(tween(100)),
            exit = scaleOut(tween(150)) + fadeOut(tween(150)),
            modifier = Modifier.offset {
                IntOffset(with(density) { (-76).dp.roundToPx() }, (dragY - with(density) { 28.dp.toPx() }).toInt())
            },
        ) {
            Box(
                Modifier.size(56.dp).background(White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(lastLetter.toString(), color = Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AlbumListRow(
    album: Album,
    subtitle: String,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().bouncyClick(0.98f, onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(album.id, Modifier.size(52.dp), sizePx = 200, shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(subtitle, songCount(album.songs.size)).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 13.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(Surface2)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
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
private fun AlbumCard(
    album: Album,
    view: AlbumView,
    subtitle: String,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val small = view == AlbumView.Small
    // En las vistas mediana y grande, cada disco es una pequeña tarjeta
    val tile = if (small) Modifier else Modifier.clip(RoundedCornerShape(16.dp)).background(Surface1)
        .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(8.dp)
    Column(modifier.bouncyClick(0.94f, onLongClick = onLongClick, onClick = onClick).then(tile)) {
        AlbumArt(
            album.id, Modifier.fillMaxWidth().aspectRatio(1f),
            sizePx = if (view == AlbumView.Large) 400 else 250,
            shape = RoundedCornerShape(if (small) 8.dp else 10.dp),
        )
        Spacer(Modifier.height(if (small) 5.dp else 8.dp))
        Text(
            album.title, fontWeight = FontWeight.SemiBold, fontSize = if (small) 12.sp else 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!small) Text(
            subtitle, fontSize = if (view == AlbumView.Medium) 12.sp else 13.sp,
            color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!small) Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ArtistRow(artist: Artist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().bouncyClick(0.98f, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            artist.albums.first().id,
            Modifier.size(50.dp).border(1.5.dp, Line, CircleShape),
            sizePx = 200, shape = CircleShape,
        )
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
    var editingAlbum by remember { mutableStateOf<Album?>(null) }
    editingAlbum?.let { a -> AlbumEditDialog(vm, a) { editingAlbum = null } }
    if (renaming) {
        ArtistRenameDialog(vm, artist, onDismiss = { renaming = false }) {
            onRenamed(it)
            renaming = false
        }
    }
    AlbumGrid(
        albums = artist.albums,
        view = vm.albumView,
        subtitle = { if (it.year > 0) it.year.toString() else "Sin año" },
        // Pulsación larga: editar el disco (p. ej. ponerle el año) sin entrar en él
        onLongAlbum = { editingAlbum = it },
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
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(
                        artist.albums.firstOrNull()?.id,
                        Modifier.size(92.dp).border(2.dp, Surface2, CircleShape),
                        sizePx = 300, shape = CircleShape,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(artist.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(countLabel(artist), color = Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Reproducir", Icons.Filled.PlayArrow, filled = true) { vm.playArtist(artist, shuffle = false) }
                    Pill("Aleatorio", Icons.Filled.Shuffle) { vm.playArtist(artist, shuffle = true) }
                }
                Spacer(Modifier.height(22.dp))
                SectionHeader("Discografía", "${artist.albums.size} · por año")
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
                    MenuAction("Buscar portada en internet", Icons.Filled.ImageSearch) { vm.retryCovers(album.id) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlowingAlbumArt(album.id, playing = false, modifier = Modifier.fillMaxWidth(0.66f).aspectRatio(1f))
            }
            Spacer(Modifier.height(28.dp))
            Text(album.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                album.artist, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.bouncyClick(0.95f) { onArtist(album.artist) }.padding(vertical = 2.dp),
            )
            Text(
                listOfNotNull(
                    album.year.takeIf { it > 0 }?.toString(),
                    songCount(album.songs.size),
                    formatTime(album.songs.sumOf { it.durationMs }),
                ).joinToString(" · "),
                color = Gray, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Reproducir", Icons.Filled.PlayArrow, filled = true) { vm.playAlbum(album) }
                Pill("Aleatorio", Icons.Filled.Shuffle) { vm.playAlbum(album, shuffle = true) }
            }
            Spacer(Modifier.height(24.dp))
            SectionHeader("Canciones", null)
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
                modifier = Modifier.grouped(i, album.songs.size),
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
        modifier.fillMaxWidth().bouncyClick(0.98f, onClick = onClick)
            .padding(vertical = 8.dp),
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
                    modifier = Modifier.grouped(i, songs.size, lifted = dragging),
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

@Composable
private fun YearField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        singleLine = true,
        label = { Text("Año") },
        placeholder = { Text("Ej. 1980", color = DarkGray) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val EDIT_NOTE = "Los cambios se guardan en EDplayer; tus archivos no se modifican."

@Composable
private fun SongEditDialog(vm: MainViewModel, song: Song, onDone: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var albumArtist by remember { mutableStateOf(song.albumArtist) }
    var year by remember { mutableStateOf(song.year.takeIf { it > 0 }?.toString() ?: "") }
    val fileOption = song.isFlac && vm.canWriteFiles
    var toFile by remember { mutableStateOf(fileOption) }
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
                YearField(year) { year = it }
                if (fileOption) FileCheckbox(toFile, "Guardar también en el archivo FLAC") { toFile = it }
                else Text(EDIT_NOTE, color = Gray, fontSize = 12.sp)
                Text(
                    "Restablecer valores del archivo", color = White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { vm.resetSong(song.id); onDone() }.padding(vertical = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.editSong(song.id, title, artist, album, albumArtist, year)
                val y = vm.parseYearInput(year)?.toString()
                if (toFile) vm.requestFileWrite(mapOf(song.id to FlacTagWriter.Changes(title, artist, album, albumArtist, y)))
                onDone()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar", color = Gray) } },
    )
}

@Composable
private fun FileCheckbox(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked, onCheckedChange = onChange,
                colors = CheckboxDefaults.colors(checkedColor = White, checkmarkColor = Black, uncheckedColor = Gray),
            )
            Text(label, fontSize = 14.sp)
        }
        Text(
            if (checked) "Se modifica el archivo; Android te pedirá permiso. Así también lo verán otras apps."
            else EDIT_NOTE,
            color = Gray, fontSize = 12.sp,
        )
    }
}

@Composable
private fun ArtistRenameDialog(vm: MainViewModel, artist: Artist, onDismiss: () -> Unit, onRenamed: (String) -> Unit) {
    var name by remember { mutableStateOf(artist.name) }
    val flacCount = artist.songs.count { it.isFlac }
    val fileOption = flacCount > 0 && vm.canWriteFiles
    var toFile by remember { mutableStateOf(fileOption) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Renombrar artista") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditField("Nombre", name) { name = it }
                Text("Si escribes el nombre de otro artista, se unirán en uno solo.", color = Gray, fontSize = 13.sp)
                if (fileOption) FileCheckbox(toFile, "Guardar también en los archivos FLAC ($flacCount)") { toFile = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val changes = vm.artistFileChanges(artist, name)
                vm.renameArtist(artist, name)
                if (toFile) vm.requestFileWrite(changes)
                onRenamed(name.trim())
            }, enabled = name.isNotBlank()) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Gray) } },
    )
}

@Composable
private fun AlbumEditDialog(vm: MainViewModel, album: Album, onDone: () -> Unit) {
    var title by remember { mutableStateOf(album.title) }
    var artist by remember { mutableStateOf(album.artist) }
    var year by remember { mutableStateOf(album.year.takeIf { it > 0 }?.toString() ?: "") }
    var searching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val flacCount = album.songs.count { it.isFlac }
    val fileOption = flacCount > 0 && vm.canWriteFiles
    var toFile by remember { mutableStateOf(fileOption) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Card,
        title = { Text("Editar álbum") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditField("Título del álbum", title) { title = it }
                EditField("Artista del álbum", artist) { artist = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { YearField(year) { year = it } }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            searching = true
                            scope.launch {
                                val found = OnlineMetadata.findYear(context, artist, title, force = true)
                                searching = false
                                if (found != null) year = found.toString()
                                searchResult = if (found != null) "Encontrado en internet: $found" else "No se ha encontrado en internet"
                            }
                        },
                        enabled = !searching,
                    ) {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = White, strokeWidth = 2.dp)
                        else Text("Buscar en\ninternet", fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
                    }
                }
                searchResult?.let { Text(it, color = Gray, fontSize = 12.sp) }
                Text("Se aplica a las ${album.songs.size} canciones.", color = Gray, fontSize = 12.sp)
                if (fileOption) FileCheckbox(toFile, "Guardar también en los archivos FLAC ($flacCount)") { toFile = it }
                else Text(EDIT_NOTE, color = Gray, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.editAlbum(album, title, artist, year)
                val y = vm.parseYearInput(year)?.toString()
                if (toFile) vm.requestFileWrite(
                    album.songs.associate { it.id to FlacTagWriter.Changes(album = title, albumArtist = artist, year = y) }
                )
                onDone()
            }) { Text("Guardar") }
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

/** Al acabar un disco: otro del mismo artista o elegir otro artista. */
@Composable
private fun WhatNextDialog(vm: MainViewModel, info: MainViewModel.WhatNext) {
    var choosing by remember { mutableStateOf(info.artist == null) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = vm::dismissWhatNext,
        containerColor = Card,
        title = { Text(if (choosing) "Elige artista" else "Fin del disco") },
        text = {
            if (!choosing) {
                val artist = info.artist!!
                val others = artist.albums.count { it.id != info.albumId }
                Column {
                    Text("¿Qué quieres escuchar ahora?", color = Gray)
                    Spacer(Modifier.height(12.dp))
                    DialogRow(
                        Icons.Filled.Shuffle,
                        if (others > 0) "Otro disco de ${artist.name}" else "Repetir este disco de ${artist.name}",
                    ) { vm.playRandomAlbumOf(artist) }
                    DialogRow(Icons.Filled.Person, "Cambiar de artista") { choosing = true }
                }
            } else {
                val q = query.trim().lowercase()
                val list = remember(vm.artists, q) {
                    vm.artists.filter { q.isEmpty() || q in it.name.lowercase() }
                }
                Column {
                    SearchField(query) { query = it }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(list, key = { it.name }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.playRandomAlbumOf(a) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AlbumArt(a.albums.first().id, Modifier.size(40.dp), sizePx = 150, shape = CircleShape)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(countLabel(a), fontSize = 12.sp, color = Gray)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = vm::dismissWhatNext) { Text("Nada por ahora", color = Gray) }
        },
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
    val shape = RoundedCornerShape(20.dp)
    // Tarjeta flotante sobre el contenido (las medidas de aquí las usa también el gesto ↓ del reproductor)
    Column(
        Modifier.navigationBarsPadding()
            .padding(horizontal = MiniOuterX, vertical = MiniOuterY)
            .shadow(18.dp, shape)
            .clip(shape)
            .background(Surface2)
            .border(1.dp, Color(0xFF2E2E2E), shape)
            .bouncyClick(0.97f, onClick = onOpen)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MiniInnerX, vertical = MiniInnerY),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(meta.extras?.getLong(EXTRA_ALBUM_ID), Modifier.size(MiniArt), sizePx = 150, shape = RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(meta.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Gray)
            }
            IconButton(onClick = vm::togglePlay) {
                PlayPauseIcon(vm.playWhenReady, Modifier.size(30.dp), White)
            }
            IconButton(onClick = { vm.next() }) {
                Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(28.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(MiniBar).background(Line)) {
            Box(
                Modifier.fillMaxWidth(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f)
                    .fillMaxHeight().background(White)
            )
        }
    }
}

// Medidas del mini reproductor
private val MiniOuterX = 10.dp
private val MiniOuterY = 8.dp
private val MiniInnerX = 10.dp
private val MiniInnerY = 8.dp
private val MiniArt = 44.dp
private val MiniBar = 2.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(
    vm: MainViewModel,
    onArtist: (String) -> Unit,
    onQueue: () -> Unit,
    onAlbum: (Long) -> Unit,
    onHome: () -> Unit,
    onReveal: (Float) -> Unit,
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
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val threshold = with(density) { 90.dp.toPx() }
    val screenW = with(density) { config.screenWidthDp.dp.toPx() }
    // Recorrido vertical con el que el gesto se completa del todo
    val travelY = with(density) { config.screenHeightDp.dp.toPx() } * 0.6f
    val currentOnAlbum by rememberUpdatedState(onAlbum)
    val currentOnHome by rememberUpdatedState(onHome)
    val currentOnReveal by rememberUpdatedState(onReveal)
    val currentAlbumId by rememberUpdatedState(albumId)

    // 0 = reproductor normal, 1 = deslizado del todo hacia abajo (se ve el disco)
    val reveal by remember { derivedStateOf { (swipeY.value / travelY).coerceIn(0f, 1f) } }

    // Dónde está la portada y dónde está la del mini reproductor, para llevarla hasta allí
    val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    val miniTarget = remember { MiniArtTarget() }
    val miniArtSize = with(density) { 44.dp.toPx() }
    val miniArtCenterX = with(density) { (MiniOuterX + MiniInnerX + MiniArt / 2).toPx() }
    // Desde abajo: margen exterior + barra de progreso + margen interior + media portada
    val miniArtFromBottom = with(density) { (MiniOuterY + MiniBar + MiniInnerY + MiniArt / 2).toPx() }
    LaunchedEffect(Unit) { snapshotFlow { reveal }.collect { currentOnReveal(it) } }

    val swipe = Modifier.pointerInput(Unit) {
        var dx = 0f
        var dy = 0f
        fun reset() {
            scope.launch { swipeX.animateTo(0f, bouncy()) }
            scope.launch { swipeY.animateTo(0f, bouncy()) }
        }
        detectDragGestures(
            onDragStart = { dx = 0f; dy = 0f },
            onDragEnd = {
                val horizontal = abs(dx) > abs(dy)
                val album = currentAlbumId
                when {
                    horizontal && abs(dx) > threshold -> scope.launch {
                        val dir = if (dx < 0) -1f else 1f
                        launch { swipeY.animateTo(0f, tween(160)) }
                        // La portada sale por un lado…
                        swipeX.animateTo(dir * screenW, tween(160))
                        if (dir < 0) vm.next() else vm.previousTrack()
                        // …y la nueva entra por el contrario
                        swipeX.snapTo(-dir * screenW * 0.7f)
                        swipeX.animateTo(0f, bouncy())
                    }
                    // ↓ abre el disco: la portada termina de viajar al mini reproductor
                    !horizontal && dy > threshold && album != null -> scope.launch {
                        launch { swipeX.animateTo(0f, tween(220)) }
                        swipeY.animateTo(travelY, tween(220, easing = FastOutSlowInEasing))
                        currentOnAlbum(album)
                    }
                    // ↑ vuelve a la pantalla principal
                    !horizontal && dy < -threshold -> scope.launch {
                        launch { swipeX.animateTo(0f, tween(180)) }
                        swipeY.animateTo(-travelY, tween(180))
                        currentOnHome()
                    }
                    else -> reset()
                }
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

    // El resto de la pantalla se desvanece al deslizar hacia abajo
    val fade = Modifier.graphicsLayer { alpha = 1f - reveal }

    Column(
        swipe.fillMaxSize()
            .drawBehind { drawRect(Bg.copy(alpha = 1f - reveal)) }
            .padding(horizontal = 28.dp, vertical = 8.dp)
    ) {
        Row(fade, verticalAlignment = Alignment.CenterVertically) {
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
            glowAlpha = { 1f - reveal },
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1f)
                .onGloballyPositioned { c ->
                    val b = c.boundsInRoot()
                    miniTarget.centerX = b.center.x
                    miniTarget.centerY = b.center.y
                    miniTarget.width = b.width
                    miniTarget.rootHeight = c.findRootCoordinates().size.height.toFloat()
                }
                .graphicsLayer {
                    val x = swipeX.value
                    val y = swipeY.value
                    if (y > 0f && abs(y) >= abs(x)) {
                        // ↓ La portada viaja encogiéndose hasta su sitio en el mini reproductor
                        val p = (y / travelY).coerceIn(0f, 1f)
                        val targetY = miniTarget.rootHeight - navBottom - miniArtFromBottom
                        val endScale = miniArtSize / miniTarget.width.coerceAtLeast(1f)
                        val scale = 1f + (endScale - 1f) * p
                        scaleX = scale
                        scaleY = scale
                        translationX = (miniArtCenterX - miniTarget.centerX) * p + x * (1f - p)
                        translationY = (targetY - miniTarget.centerY) * p
                        alpha = 1f
                    } else {
                        // ←, → y ↑: cuanto más se desliza, más pequeña y transparente, "desaparece"
                        val progress = maxOf(abs(x) / screenW, abs(y) / travelY).coerceIn(0f, 1f)
                        val scale = 1f - progress * 0.55f
                        scaleX = scale
                        scaleY = scale
                        translationX = x * 0.8f
                        translationY = y * 0.6f
                        alpha = 1f - progress * 0.7f
                    }
                    // Sin capa intermedia: así el halo (que se dibuja fuera de la portada) no se recorta
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
        Spacer(Modifier.weight(1f))
        val songId = vm.currentItem?.mediaId?.toLongOrNull()
        Row(fade, verticalAlignment = Alignment.CenterVertically) {
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
                // El corazón "late" al marcarlo o desmarcarlo
                val favScale = remember { Animatable(1f) }
                var lastFav by remember { mutableStateOf(fav) }
                LaunchedEffect(fav) {
                    if (fav != lastFav) {
                        lastFav = fav
                        favScale.snapTo(1.45f)
                        favScale.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 500f))
                    }
                }
                IconButton(onClick = { vm.toggleFavorite(songId) }, Modifier.offset(x = 8.dp)) {
                    Icon(
                        modifier = Modifier.graphicsLayer { scaleX = favScale.value; scaleY = favScale.value },
                        imageVector = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (fav) "Quitar de favoritas" else "Añadir a favoritas",
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
            modifier = fade,
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
        Row(fade.fillMaxWidth()) {
            Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), fontSize = 12.sp, color = Gray)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), fontSize = 12.sp, color = Gray)
        }
        Spacer(Modifier.height(20.dp))

        Row(
            fade.fillMaxWidth(),
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
                Modifier.size(72.dp).shadow(14.dp, CircleShape).clip(CircleShape).background(White)
                    .bouncyClick(0.86f, onClick = vm::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseIcon(vm.playWhenReady, Modifier.size(38.dp), Black)
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

/** Posición de la portada grande en pantalla (se actualiza al colocarla). */
private class MiniArtTarget {
    var centerX = 0f
    var centerY = 0f
    var width = 1f
    var rootHeight = 0f
}

// ---------------------------------------------------------------- Carpetas

@Composable
private fun FoldersScreen(vm: MainViewModel, onBack: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::addFolder)
    }
    LazyColumn(contentPadding = PaddingValues(20.dp)) {
        item {
            BackButton(onBack)
            Spacer(Modifier.height(12.dp))
            Text("Ajustes", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            SectionHeader("Carpetas de música", null)
            Text(
                if (vm.folders.isEmpty()) "Ahora se lee la música de todo el móvil. Elige una o varias carpetas para leer solo de ahí (incluye sus subcarpetas)."
                else "Solo se lee la música de estas carpetas y sus subcarpetas.",
                color = Gray, fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
        itemsIndexed(vm.folders, key = { _, f -> f }) { i, path ->
            Row(
                Modifier.animateItem(placementSpec = bouncy()).grouped(i, vm.folders.size).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Surface2),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = White) }
                Spacer(Modifier.width(14.dp))
                Text(folderLabel(path), Modifier.weight(1f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { vm.removeFolder(path) }) {
                    Icon(Icons.Filled.Close, "Quitar carpeta", Modifier.size(20.dp), tint = Gray)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Pill("Añadir carpeta", Icons.Filled.Add, filled = true) { picker.launch(null) }
            Spacer(Modifier.height(20.dp))
            Text(
                if (vm.loading) "Buscando música…" else "${vm.songs.size} canciones en la biblioteca",
                color = DarkGray, fontSize = 13.sp,
            )
            Spacer(Modifier.height(32.dp))
            SectionHeader("Internet", null)
            Column(SectionCard) {
                SettingSwitch(
                    "Buscar portadas",
                    "Si un disco no tiene portada, se busca en iTunes y Deezer y se guarda en el móvil.",
                    vm.onlineCovers, vm::changeOnlineCovers,
                )
                AnimatedVisibility(vm.onlineCovers) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Pill("Reintentar las que faltan", Icons.Filled.Refresh) { vm.retryCovers() }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
                SettingSwitch(
                    "Buscar años de los discos",
                    "Para los discos sin año: se busca en MusicBrainz la fecha del primer lanzamiento (el año del disco original, no el de la reedición).",
                    vm.onlineYearsEnabled, vm::changeOnlineYears,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Gray, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Black, checkedTrackColor = White,
                uncheckedThumbColor = Gray, uncheckedTrackColor = Surface2, uncheckedBorderColor = DarkGray,
            ),
        )
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
        itemsIndexed(entries, key = { _, e -> e.item.mediaId }) { i, entry ->
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
                    modifier = Modifier.grouped(i, entries.size, lifted = dragging),
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

// ---------------------------------------------------------------- Movimiento y superficies

/** Muelle con un poco de rebote: el "tacto" de toda la app. */
private fun <T> bouncy(stiffness: Float = Spring.StiffnessMediumLow) =
    spring<T>(dampingRatio = 0.6f, stiffness = stiffness)

/** Pulsación blandita: el elemento se encoge un poco al pulsarlo y vuelve con un rebote. */
private fun Modifier.bouncyClick(
    scaleDown: Float = 0.95f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) scaleDown else 1f,
        spring(dampingRatio = 0.4f, stiffness = 650f),
        label = "pulsar",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * Fondo de lista agrupada: las filas seguidas forman una tarjeta con esquinas redondeadas
 * arriba (primera) y abajo (última) y una línea fina entre ellas.
 * @param lifted la fila se está arrastrando: se despega como una tarjeta suelta
 */
private fun Modifier.grouped(index: Int, count: Int, lifted: Boolean = false): Modifier {
    val r = 18.dp
    val first = index == 0
    val last = index == count - 1
    val shape = if (lifted) RoundedCornerShape(14.dp) else RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    return this
        .then(if (lifted) Modifier.shadow(16.dp, shape) else Modifier)
        .clip(shape)
        .background(if (lifted) Surface2 else Surface1)
        .drawWithContent {
            drawContent()
            if (!first && !lifted) {
                val inset = 16.dp.toPx()
                drawLine(Line, Offset(inset, 0f), Offset(size.width - inset, 0f), 1.dp.toPx())
            }
        }
        .padding(horizontal = 12.dp)
}

/** Tarjeta de sección (ajustes, bloques de contenido). */
private val SectionCard = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1)
    .border(1.dp, Line, RoundedCornerShape(18.dp)).padding(16.dp)

/** Título de sección: texto en negrita con una marca a la izquierda y un detalle opcional. */
@Composable
private fun SectionHeader(title: String, detail: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 16.dp).background(White, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, fontSize = 13.sp, color = Gray)
        }
    }
}

/** Selector de pestañas tipo píldora: la píldora blanca se desliza con rebote a la elegida. */
@Composable
private fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.height(40.dp).clip(CircleShape).background(Surface2)
            .border(1.dp, Line, CircleShape).padding(4.dp)
    ) {
        val w = maxWidth / labels.size
        val offset by animateDpAsState(w * selected, bouncy(Spring.StiffnessMedium), label = "pestaña")
        Box(Modifier.offset(x = offset).width(w).fillMaxHeight().clip(CircleShape).background(White))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val color by animateColorAsState(if (i == selected) Black else Gray, tween(180), label = "pestañaTexto")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** Icono de play/pausa que cambia con un pequeño salto. */
@Composable
private fun PlayPauseIcon(playing: Boolean, modifier: Modifier, tint: Color) {
    AnimatedContent(
        targetState = playing,
        transitionSpec = {
            (scaleIn(spring(dampingRatio = 0.45f, stiffness = 600f), initialScale = 0.5f) + fadeIn(tween(90))) togetherWith
                (scaleOut(tween(90), targetScale = 0.5f) + fadeOut(tween(90)))
        },
        label = "playPausa",
    ) { p ->
        Icon(
            if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (p) "Pausa" else "Reproducir", modifier, tint = tint,
        )
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
    val fg = if (filled) Black else White
    Row(
        Modifier.height(38.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(if (filled) White else Surface2)
            .then(if (filled) Modifier else Modifier.border(1.dp, Color(0xFF333333), CircleShape))
            .then(if (enabled) Modifier.bouncyClick(0.9f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg)
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
