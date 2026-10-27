package com.musicafacil.player

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ArtCache {
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

/** Carátula del álbum (de la etiqueta del archivo o cover.jpg), con caché en memoria. */
@Composable
fun AlbumArt(albumId: Long?, modifier: Modifier = Modifier, sizePx: Int = 400) {
    val context = LocalContext.current
    val key = "$albumId@$sizePx"
    val art by produceState(initialValue = ArtCache.bitmaps.get(key), key) {
        if (value == null && albumId != null && key !in ArtCache.missing) {
            val loaded = withContext(Dispatchers.IO) { loadArt(context, albumId, sizePx) }
            if (loaded != null) ArtCache.bitmaps.put(key, loaded) else ArtCache.missing += key
            value = loaded
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
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
                modifier = Modifier.fillMaxWidth(0.5f).fillMaxHeight(0.5f),
            )
        }
    }
}
