package com.example.optimalx.ui.editor.panels

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.optimalx.ui.theme.DmSansFamily
import com.example.optimalx.ui.theme.LocalOptimalXColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfViewerPanel(
    fileId: Long,
    filePath: String,
    initialRotation: Float = 0f,
    onRotationCommit: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOptimalXColors.current
    var pageCount by remember(filePath) { mutableIntStateOf(0) }
    var currentPageIndex by remember(filePath) { mutableIntStateOf(0) }
    var pageBitmap by remember(filePath, currentPageIndex) { mutableStateOf<Bitmap?>(null) }
    var error by remember(filePath) { mutableStateOf<String?>(null) }

    var scale by remember(filePath) { mutableFloatStateOf(1f) }
    var offset by remember(filePath) { mutableStateOf(Offset.Zero) }
    var rotation by remember(filePath) { mutableFloatStateOf(0f) }

    LaunchedEffect(filePath, initialRotation) {
        error = null
        scale = 1f
        offset = Offset.Zero
        rotation = initialRotation
        currentPageIndex = 0
        pageBitmap = null
        pageCount = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(filePath)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer -> renderer.pageCount }
                }
            }.getOrElse {
                0
            }
        }
        if (pageCount <= 0) {
            error = "Could not open PDF."
        }
    }

    LaunchedEffect(filePath, currentPageIndex, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        pageBitmap = null
        error = null
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val safeIndex = currentPageIndex.coerceIn(0, renderer.pageCount - 1)
                        val page = renderer.openPage(safeIndex)
                        val renderScale = 2
                        val bmp = Bitmap.createBitmap(
                            page.width * renderScale,
                            page.height * renderScale,
                            Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        withContext(Dispatchers.Main) {
                            pageBitmap = bmp
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = "Could not open PDF: ${e.message}"
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }

        fun fit() {
            scale = 1f
            offset = Offset.Zero
        }

        fun applyScale(newScale: Float) {
            val clamped = newScale.coerceIn(1f, 6f)
            scale = clamped
            offset = if (clamped <= 1.02f) {
                Offset.Zero
            } else {
                clampPdfOffset(
                    raw = offset,
                    scale = clamped,
                    rotation = rotation,
                    bitmap = pageBitmap,
                    viewportW = viewportW,
                    viewportH = viewportH,
                )
            }
        }

        val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
            applyScale(scale * zoomChange)
            offset = if (scale <= 1.02f) {
                Offset.Zero
            } else {
                clampPdfOffset(
                    raw = offset + panChange,
                    scale = scale,
                    rotation = rotation,
                    bitmap = pageBitmap,
                    viewportW = viewportW,
                    viewportH = viewportH,
                )
            }
        }

        when {
            error != null -> Text(
                text = error ?: "Unknown PDF error",
                color = colors.textDim,
                fontFamily = DmSansFamily,
                fontSize = 14.sp,
                modifier = Modifier.padding(24.dp),
            )

            pageCount <= 0 || pageBitmap == null -> CircularProgressIndicator(color = colors.accent)

            else -> {
                Image(
                    bitmap = pageBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 56.dp)
                        .pointerInput(fileId, currentPageIndex) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale <= 1.02f) applyScale(2f) else fit()
                                },
                            )
                        }
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

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (currentPageIndex > 0) {
                                currentPageIndex -= 1
                                fit()
                            }
                        },
                        enabled = currentPageIndex > 0,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Previous page",
                            tint = colors.textPrimary,
                        )
                    }

                    Text(
                        text = "${currentPageIndex + 1}/$pageCount",
                        color = colors.textPrimary,
                        fontFamily = DmSansFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    IconButton(
                        onClick = {
                            if (currentPageIndex < pageCount - 1) {
                                currentPageIndex += 1
                                fit()
                            }
                        },
                        enabled = currentPageIndex < pageCount - 1,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Next page",
                            tint = colors.textPrimary,
                        )
                    }

                    IconButton(
                        onClick = { applyScale(scale - 0.2f) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Zoom out",
                            tint = colors.textPrimary,
                        )
                    }

                    IconButton(
                        onClick = { applyScale(scale + 0.2f) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Zoom in",
                            tint = colors.textPrimary,
                        )
                    }

                    IconButton(
                        onClick = {
                            rotation += 90f
                            fit()
                            onRotationCommit?.invoke(rotation)
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.RotateRight,
                            contentDescription = "Rotate PDF",
                            tint = colors.textPrimary,
                        )
                    }

                    IconButton(
                        onClick = { fit() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitScreen,
                            contentDescription = "Fit PDF to screen",
                            tint = colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

private fun clampPdfOffset(
    raw: Offset,
    scale: Float,
    rotation: Float,
    bitmap: Bitmap?,
    viewportW: Float,
    viewportH: Float,
): Offset {
    if (bitmap == null) return Offset.Zero

    val bmpW = bitmap.width.toFloat().coerceAtLeast(1f)
    val bmpH = bitmap.height.toFloat().coerceAtLeast(1f)
    val fitScale = minOf(viewportW / bmpW, viewportH / bmpH)
    var drawW = bmpW * fitScale * scale
    var drawH = bmpH * fitScale * scale

    val quarterTurns = (((rotation / 90f).toInt() % 4) + 4) % 4
    if (quarterTurns % 2 == 1) {
        val t = drawW
        drawW = drawH
        drawH = t
    }

    val maxX = ((drawW - viewportW) / 2f).coerceAtLeast(0f)
    val maxY = ((drawH - viewportH) / 2f).coerceAtLeast(0f)

    return Offset(
        x = raw.x.coerceIn(-maxX, maxX),
        y = raw.y.coerceIn(-maxY, maxY),
    )
}
