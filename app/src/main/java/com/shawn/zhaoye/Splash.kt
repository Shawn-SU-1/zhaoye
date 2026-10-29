package com.shawn.zhaoye

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp

/**
 * The first second of the app.
 *
 * It says the same thing the icon does, in the same order: the lamp comes up
 * first (a warm bloom where the gate will be), then the four film sheets land
 * on the easel from the bottom up, cream last so the last thing to arrive is
 * the light. The wordmark follows once the picture is there.
 *
 * One clock drives every element -- a single [Animatable] from 0 to 1 read
 * through piecewise maps. Four independent animations would drift apart on a
 * slow first frame, and the whole point is that they stay in step.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val clock = remember { Animatable(0f) }
    val holdMs = 1100
    LaunchedEffect(Unit) {
        clock.animateTo(1f, tween(durationMillis = holdMs, easing = LinearEasing))
        // A beat on the finished frame; the crossfade in MainActivity does the
        // rest. Without it the wordmark appears and is gone in the same frame.
        kotlinx.coroutines.delay(140)
        onDone()
    }
    val p = clock.value

    // The icon's own numbers, so the animation ends on the mark the launcher
    // shows: 620 x 207 rounds with 153/105/155 between their tops.
    val cfg = LocalConfiguration.current
    val stackW: Dp = (cfg.screenWidthDp * 0.56f).dp
    // One band is 620 x 207 in the icon; the stack is three bands tall because
    // the tops step by 153 + 105 + 155 = 413, a little over 2 x 207.
    val bandH: Dp = stackW * (207f / 620f)
    val stackH: Dp = bandH * 3f
    val bandColours = listOf(
        Color(0xFFFFF7E6), Color(0xFFFFC069), Color(0xFFE2603A),
        Color(0xFF7E2318), Color(0xFF2A1512),
    )
    val glow = Color(0xFFFFB969)

    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = stackW, height = stackH)) {
                val w = size.width
                val h = size.height
                val bh = w * (207f / 620f)
                val rad = bh / 2f
                val s = bh / 207f
                val steps = listOf(153f * s, 105f * s, 155f * s)
                val tops = FloatArray(4).also { a ->
                    a[0] = 0f
                    a[1] = steps[0]
                    a[2] = steps[0] + steps[1]
                    a[3] = steps[0] + steps[1] + steps[2]
                }

                // The lamp. Rises fast, then drops back so it reads as a flare
                // rather than a permanent halo behind the mark.
                val lamp = seg(p, 0f, 0.34f)
                val settle = seg(p, 0.34f, 0.62f)
                val lampA = (0.42f * easeOut(lamp)) * (1f - 0.50f * settle)
                if (lampA > 0.002f) {
                    val cy = tops[3] + bh / 2f
                    // Kept inside the stack: a halo that reaches the wordmark
                    // reads as fog over the type rather than a lamp behind the
                    // film.
                    val rr = h * (0.22f + 0.38f * easeOut(lamp))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow.copy(alpha = lampA), Color.Transparent),
                            center = Offset(w / 2f, cy),
                            radius = rr,
                        ),
                        radius = rr,
                        center = Offset(w / 2f, cy),
                    )
                }

                // The sheets. Painted top first so each lower sheet covers the
                // one above it, the way the icon is built.
                //
                // The whole stack rises as one and only the opacities stagger:
                // giving each sheet its own offset tore visible gaps between
                // neighbours mid-flight, which read as a broken reflection.
                val rise = easeOut(seg(p, 0.06f, 0.40f))
                val dy = (1f - rise) * 18.dp.toPx()
                for (i in 0..3) {
                    val start = 0.06f + (3 - i) * 0.05f
                    val t = easeOut(seg(p, start, start + 0.22f)) * rise
                    if (t <= 0.001f) continue
                    val top = tops[i] + dy
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(bandColours[i], bandColours[i + 1]),
                            startY = top, endY = top + bh,
                        ),
                        topLeft = Offset(0f, top),
                        size = Size(w, bh),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(rad, rad),
                        alpha = t,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(top = 26.dp)
                    .graphicsLayer {
                        alpha = easeOut(seg(p, 0.62f, 0.88f))
                        translationY = (1f - easeOut(seg(p, 0.62f, 0.88f))) * 10.dp.toPx()
                    },
            ) {
                Text(
                    "照夜",
                    color = Bone,
                    fontSize = 30.sp,
                    letterSpacing = 8.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "不是滤镜，是冲印",
                    color = Color(0xFF9C9184),
                    fontSize = 11.5.sp,
                    letterSpacing = 3.sp,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .graphicsLayer { alpha = easeOut(seg(p, 0.70f, 0.92f)) },
                )
            }
        }
    }
}

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

private fun seg(p: Float, from: Float, to: Float): Float =
    ((p - from) / (to - from)).coerceIn(0f, 1f)
