package com.shawn.zhaoye

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

enum class HapticCue {
    Selection,
    Detent,
    LongPress,
    Confirm,
    Error,
}

/**
 * One small motion vocabulary for the whole product. View feedback is used
 * first because it respects Android's system haptics setting and needs no
 * vibration permission; Compose's long-press cue is only a fallback.
 */
@Stable
class ZhaoyeHaptics internal constructor(
    private val view: View,
    private val composeHaptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    fun perform(cue: HapticCue) {
        val constant = when (cue) {
            HapticCue.Selection -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticCue.Detent -> HapticFeedbackConstants.CLOCK_TICK
            HapticCue.LongPress -> HapticFeedbackConstants.LONG_PRESS
            HapticCue.Confirm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            HapticCue.Error -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }
        if (!view.performHapticFeedback(constant)) {
            composeHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

@Composable
fun rememberZhaoyeHaptics(): ZhaoyeHaptics {
    val view = LocalView.current
    val composeHaptics = LocalHapticFeedback.current
    return remember(view, composeHaptics) {
        ZhaoyeHaptics(view, composeHaptics)
    }
}
