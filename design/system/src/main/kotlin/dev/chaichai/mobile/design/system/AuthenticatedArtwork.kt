package dev.chaichai.mobile.design.system

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

@Composable
fun AuthenticatedArtwork(
    cacheIdentity: Any,
    contentDescription: String,
    load: suspend () -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    var bytes by remember(cacheIdentity) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(cacheIdentity) { bytes = load() }
    bytes?.let { encoded ->
        val bitmap = remember(encoded) { BitmapFactory.decodeByteArray(encoded, 0, encoded.size)?.asImageBitmap() }
        bitmap?.let {
            Image(it, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
        }
    }
}
