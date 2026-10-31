package com.shawn.zhaoye

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

/**
 * The preview canvas. The gesture library only scales the pixels we already
 * have; a higher-resolution inspection pass can replace [bitmap] without
 * resetting the pinch position.
 */
@Composable
fun ZoomablePreview(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    originalBitmap: Bitmap? = null,
    compareProgress: Float? = null,
    longPressBitmap: Bitmap? = null,
    colorFilter: ColorFilter? = null,
    onScaleChanged: (Float) -> Unit = {},
) {
    var showingPressedOriginal by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberZhaoyeHaptics()
    val zoomState = rememberZoomState(maxScale = 8f)
    LaunchedEffect(bitmap) {
        zoomState.setContentSize(Size(bitmap.width.toFloat(), bitmap.height.toFloat()))
    }
    LaunchedEffect(zoomState.scale) {
        onScaleChanged(zoomState.scale)
    }
    Box(
        modifier
            .fillMaxSize()
            .zoomable(
                zoomState = zoomState,
                enableOneFingerZoom = false,
                onLongPress = {
                    if (longPressBitmap != null) {
                        haptics.perform(HapticCue.LongPress)
                        showingPressedOriginal = true
                        scope.launch {
                            delay(1400)
                            showingPressedOriginal = false
                        }
                    }
                },
                onDoubleTap = { position ->
                    haptics.perform(HapticCue.Selection)
                    val next = when {
                        zoomState.scale < 1.5f -> 2f
                        zoomState.scale < 3f -> 4f
                        else -> 1f
                    }
                    zoomState.changeScale(next, position)
                },
            ),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "冲印预览，可双指缩放和拖动",
            contentScale = ContentScale.Fit,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = originalBitmap != null && compareProgress != null,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(100)),
        ) {
            val source = originalBitmap ?: return@AnimatedVisibility
            val fraction = (compareProgress ?: 0f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = "原图对比",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(right = size.width * fraction) {
                                this@drawWithContent.drawContent()
                            }
                        },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val x = size.width * fraction
                            drawLine(
                                color = Color(0xF2F4D35E),
                                start = androidx.compose.ui.geometry.Offset(x, 0f),
                                end = androidx.compose.ui.geometry.Offset(x, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                        },
                )
            }
        }
        AnimatedVisibility(
            visible = showingPressedOriginal && longPressBitmap != null,
            enter = fadeIn(tween(130)),
            exit = fadeOut(tween(100)),
        ) {
            val source = longPressBitmap ?: return@AnimatedVisibility
            Image(
                bitmap = source.asImageBitmap(),
                contentDescription = "按住查看原图",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
