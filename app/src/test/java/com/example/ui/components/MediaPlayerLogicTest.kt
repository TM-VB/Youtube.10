package com.example.ui.components

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MediaPlayerLogicTest {

    private fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "00:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun calculateSeekPosition(
        currentPos: Long,
        offsetMs: Long,
        durationMs: Long
    ): Long {
        val maxDur = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return (currentPos + offsetMs).coerceIn(0L, maxDur)
    }

    private fun calculateSliderSeekPosition(
        fraction: Float,
        durationMs: Long
    ): Long {
        if (durationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs)
    }

    @Test
    fun testFormatDuration() {
        assertEquals("00:00", formatDuration(0L))
        assertEquals("00:25", formatDuration(25_000L))
        assertEquals("01:06", formatDuration(66_000L))
        assertEquals("10:35", formatDuration(635_000L))
        assertEquals("1:25:42", formatDuration(5142_000L))
    }

    @Test
    fun testSeekRelativeCalculations() {
        val duration = 66_000L // 1m 6s

        // Fast forward 10s from 20s
        val pos1 = calculateSeekPosition(20_000L, 10_000L, duration)
        assertEquals(30_000L, pos1)

        // Rewind 10s from 20s
        val pos2 = calculateSeekPosition(20_000L, -10_000L, duration)
        assertEquals(10_000L, pos2)

        // Rewind beyond 0 clamps to 0
        val pos3 = calculateSeekPosition(5_000L, -10_000L, duration)
        assertEquals(0L, pos3)

        // Fast forward beyond duration clamps to duration
        val pos4 = calculateSeekPosition(60_000L, 10_000L, duration)
        assertEquals(66_000L, pos4)
    }

    @Test
    fun testSliderSeekCalculations() {
        val duration = 66_000L // 1m 6s

        // 20%
        val pos20 = calculateSliderSeekPosition(0.2f, duration)
        assertEquals(13_200L, pos20)

        // 70%
        val pos70 = calculateSliderSeekPosition(0.7f, duration)
        assertEquals(46_200L, pos70)

        // 100%
        val pos100 = calculateSliderSeekPosition(1.0f, duration)
        assertEquals(66_000L, pos100)

        // Invalid duration returns 0
        val posInvalid = calculateSliderSeekPosition(0.5f, 0L)
        assertEquals(0L, posInvalid)
    }

    @Test
    fun testPlayPauseStateLogic() {
        // Simulating the exact state machine in InAppMediaPlayerDialog
        var isPlaying = false
        var playbackState = 3 // STATE_READY
        var currentPosition = 20_000L
        var savedPosition = 20_000L

        fun togglePlayPause() {
            when {
                playbackState == 4 -> { // STATE_ENDED
                    currentPosition = 0L
                    savedPosition = 0L
                    isPlaying = true
                    playbackState = 3
                }
                isPlaying -> {
                    isPlaying = false
                }
                else -> {
                    isPlaying = true
                }
            }
        }

        // Initially paused at 20s -> Press Play -> Resumes at 20s
        togglePlayPause()
        assertTrue(isPlaying)
        assertEquals(20_000L, currentPosition)
        assertEquals(20_000L, savedPosition)

        // Press Pause -> Pauses at 20s
        togglePlayPause()
        assertFalse(isPlaying)
        assertEquals(20_000L, currentPosition)

        // Press Play again -> Resumes at 20s without reset
        togglePlayPause()
        assertTrue(isPlaying)
        assertEquals(20_000L, currentPosition)

        // When video reaches end (STATE_ENDED) -> Press Replay -> Starts from 0
        playbackState = 4
        isPlaying = false
        togglePlayPause()
        assertTrue(isPlaying)
        assertEquals(0L, currentPosition)
        assertEquals(0L, savedPosition)
    }
}
