package com.dirkraft.macroman.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded thumbnails, so scrolling the log and switching days doesn't decode the same files again. */
private val thumbs = LruCache<String, ImageBitmap>(80)

private const val THUMB_PX = 192

/** Screen-sized is plenty for the viewer; a full 12 MP original would take ~50 MB of memory to show. */
private const val VIEWER_PX = 1080

/**
 * Decodes [file] at no less than [targetPx] on its short edge (power-of-two downsampling), turned upright:
 * the kept originals record their rotation in EXIF rather than in the pixels. Null if missing or unreadable.
 */
private suspend fun decode(file: File, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@withContext null
    val rotation = when (runCatching { ExifInterface(file) }.getOrNull()
        ?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val upright = if (rotation == 0f) bitmap else
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    upright.asImageBitmap()
}

/** A square, cropped preview of a kept food picture. Shows nothing if the picture has been evicted. */
@Composable
fun PhotoThumb(file: File, size: Dp, onClick: () -> Unit) {
    val key = file.path
    val image by produceState(thumbs.get(key), key) {
        if (value == null) value = decode(file, THUMB_PX)?.also { thumbs.put(key, it) }
    }
    image?.let {
        Image(
            it, "Food photo",
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    }
}

/** The whole picture over a dark backdrop; a tap anywhere closes it. */
@Composable
fun PhotoViewer(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val image by produceState<ImageBitmap?>(null, file) { value = decode(file, VIEWER_PX) }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            if (img != null) {
                Image(img, "Food photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
