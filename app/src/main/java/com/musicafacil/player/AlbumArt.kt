package com.musicafacil.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ArtCache {
    /** Al cambiar, todas las portadas se vuelven a evaluar (p. ej. tras activar la búsqueda online). */
    var generation by mutableIntStateOf(0)

    fun refresh() {
        missing.clear()
        generation++
    }

    val bitmaps = LruCache<String, ImageBitmap>(300)
    val missing = HashSet<String>()
}

private fun loadArt(context: Context, albumId: Long, px: Int): ImageBitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
        context.contentResolver.loadThumbnail(uri, Size(px, px), null).asImageBitmap()
    } else {
        context.contentResolver.openInputStream(albumArtUri(albumId))?.use { input ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
        }
    }
} catch (e: Exception) {
    null
}

@Composable
fun rememberAlbumArt(albumId: Long?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val key = "$albumId@$sizePx"
    val generation = ArtCache.generation
    // El estado va ligado a la clave: al cambiar de canción se parte de cero y no se queda la portada anterior
    val art = remember(key, generation) { mutableStateOf(ArtCache.bitmaps.get(key)) }
    LaunchedEffect(key, generation) {
        if (art.value == null && albumId != null && key !in ArtCache.missing) {
            val loaded = withContext(Dispatchers.IO) { loadArt(context, albumId, sizePx) }
                ?: OnlineCovers.load(context, albumId, sizePx)
            if (loaded != null) ArtCache.bitmaps.put(key, loaded) else ArtCache.missing += key
            art.value = loaded
        }
    }
    return art.value
}

/**
 * Versión diminuta de la portada (8x8 con un borde transparente de 2 px).
 * Al ampliarla con filtrado bilineal se convierte en un degradado suave con
 * los colores de cada zona de la portada, que se desvanece hacia fuera.
 */
private fun glowBitmap(art: ImageBitmap): ImageBitmap {
    val src = art.asAndroidBitmap().let {
        if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
    }
    val inner = Bitmap.createScaledBitmap(src, 8, 8, true)
    val out = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Algo más de saturación para que el brillo parezca luz
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
    }
    Canvas(out).drawBitmap(inner, 2f, 2f, paint)
    return out.asImageBitmap()
}

private val GlowCache = LruCache<Long, ImageBitmap>(200)

/** Portada grande con un halo de luz de sus propios colores alrededor. */
@Composable
fun GlowingAlbumArt(
    albumId: Long?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    /** Multiplica la intensidad del halo (se lee al dibujar, sin recomponer). */
    glowAlpha: () -> Float = { 1f },
) {
    // El halo sale de la miniatura pequeña (casi siempre ya en memoria por el mini reproductor),
    // así aparece enseguida sin esperar a la portada grande; y se guarda por álbum.
    val small = rememberAlbumArt(albumId, 150)
    var glow by remember { mutableStateOf(albumId?.let { GlowCache.get(it) }) }
    LaunchedEffect(albumId, small) {
        val cached = albumId?.let { GlowCache.get(it) }
        glow = cached ?: small?.let { s ->
            withContext(Dispatchers.Default) { glowBitmap(s) }.also { g -> albumId?.let { GlowCache.put(it, g) } }
        }
    }
    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.65f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val intensity by animateFloatAsState(if (playing) 1f else 0.55f, tween(800), label = "intensity")

    Box(modifier) {
        Crossfade(targetState = glow, animationSpec = tween(250), label = "glowFade") { g ->
            if (g != null) {
                Image(
                    g, contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.6f
                            scaleY = 1.6f
                            alpha = (if (playing) pulse else 0.8f) * intensity * glowAlpha()
                        }
                        // En Android 12+ difumina aún más; en versiones anteriores no hace nada
                        .blur(36.dp, BlurredEdgeTreatment.Unbounded),
                )
            }
        }
        AlbumArt(albumId, Modifier.fillMaxSize(), sizePx = 1000, shape = RoundedCornerShape(8.dp))
    }
}

/** Carátula del álbum (de la etiqueta del archivo o cover.jpg), con caché en memoria. */
@Composable
fun AlbumArt(
    albumId: Long?,
    modifier: Modifier = Modifier,
    sizePx: Int = 400,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    val art = rememberAlbumArt(albumId, sizePx)
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val img = art
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Filled.Album, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(0.4f).fillMaxHeight(0.4f),
            )
        }
    }
}
