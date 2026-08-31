package com.example.optimalx.ui.editor.panels

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerPanel(
    filePath: String,
    initialRotation: Float = 0f,
    onRotationCommit: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    val bitmap = remember(filePath) {
        BitmapFactory.decodeFile(filePath)?.asImageBitmap()
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember(filePath) { mutableFloatStateOf(initialRotation) }

    LaunchedEffect(filePath, initialRotation) {
        rotation = initialRotation
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
            val nextScale = (scale * zoomChange).coerceIn(1f, 8f)
            scale = nextScale

            offset = if (nextScale <= 1.02f) {
                Offset.Zero
            } else {
                clampOffset(
                    raw = offset + offsetChange,
                    scale = nextScale,
                    maxWidthPx = maxWidthPx,
                    maxHeightPx = maxHeightPx,
                )
            }
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // Allow pager swipe at base scale; pan image only when zoomed.
                    .transformable(
                        state = transformableState,
                        canPan = { scale > 1.02f },
                    )
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        rotationZ = rotation,
                    ),
            )
        } else {
            Text(
                text = "Could not load image",
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
            )
        }

        if (bitmap != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                IconButton(
                    onClick = {
                        rotation -= 90f
                        onRotationCommit?.invoke(rotation)
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.RotateLeft,
                        contentDescription = "Rotate left",
                        tint = colors.textPrimary,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            ) {
                IconButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                        rotation = 0f
                        onRotationCommit?.invoke(rotation)
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset image",
                        tint = colors.textPrimary,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                IconButton(
                    onClick = {
                        rotation += 90f
                        onRotationCommit?.invoke(rotation)
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.RotateRight,
                        contentDescription = "Rotate right",
                        tint = colors.textPrimary,
                    )
                }
            }
        }
    }
}

private fun clampOffset(
    raw: Offset,
    scale: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
): Offset {
    val maxX = ((scale - 1f) * maxWidthPx) / 2f
    val maxY = ((scale - 1f) * maxHeightPx) / 2f
    return Offset(
        x = raw.x.coerceIn(-maxX, maxX),
        y = raw.y.coerceIn(-maxY, maxY),
    )
}
