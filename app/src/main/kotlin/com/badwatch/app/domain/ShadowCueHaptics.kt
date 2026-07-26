package com.badwatch.app.domain

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.badwatch.core.training.CourtCorner

/**
 * Learnable six-corner rhythm: one opening pulse means forehand, two mean backhand; the
 * final pulse is short for front, medium for mid-court, and long for rear court.
 */
object ShadowCueHaptics {

    fun patternFor(corner: CourtCorner): List<Long> {
        val opening = when (corner) {
            CourtCorner.ForehandFront,
            CourtCorner.ForehandMid,
            CourtCorner.ForehandRear -> listOf(0L, SIDE_PULSE_MILLIS)

            CourtCorner.BackhandFront,
            CourtCorner.BackhandMid,
            CourtCorner.BackhandRear -> listOf(
                0L,
                SIDE_PULSE_MILLIS,
                SIDE_PULSE_GAP_MILLIS,
                SIDE_PULSE_MILLIS
            )
        }
        val depthPulse = when (corner) {
            CourtCorner.ForehandFront, CourtCorner.BackhandFront -> FRONT_PULSE_MILLIS
            CourtCorner.ForehandMid, CourtCorner.BackhandMid -> MID_PULSE_MILLIS
            CourtCorner.ForehandRear, CourtCorner.BackhandRear -> REAR_PULSE_MILLIS
        }
        return opening + listOf(DEPTH_GAP_MILLIS, depthPulse)
    }

    fun play(context: Context, corner: CourtCorner) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.cancel()
        vibrator.vibrate(
            VibrationEffect.createWaveform(patternFor(corner).toLongArray(), -1)
        )
    }

    private const val SIDE_PULSE_MILLIS = 45L
    private const val SIDE_PULSE_GAP_MILLIS = 45L
    private const val DEPTH_GAP_MILLIS = 130L
    private const val FRONT_PULSE_MILLIS = 45L
    private const val MID_PULSE_MILLIS = 110L
    private const val REAR_PULSE_MILLIS = 190L
}
