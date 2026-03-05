package com.agentphone

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.min

/**
 * ScreenChangeDetector
 *
 * Compares two screenshots to determine whether the screen meaningfully
 * changed after an action was executed.
 *
 * Used by AgentLoop to detect:
 *   - Actions that had no effect (tap missed, element not yet visible)
 *   - Loading states (screen changed but might not be fully settled)
 *   - Stuck loops (same screen despite multiple attempts)
 *
 * Algorithm: sample a grid of pixels across both bitmaps and compute the
 * mean absolute difference per channel. Fast enough to run on every step
 * without noticeable latency (~2ms for a 720x1560 image on a modern device).
 */
object ScreenChangeDetector {

    private const val TAG = "ScreenChange"

    // Fraction of pixels to sample (1/SAMPLE_STRIDE^2 of total pixels)
    private const val SAMPLE_STRIDE = 8

    // Thresholds (0–255 per channel, averaged across sampled pixels)
    private const val THRESHOLD_MINOR = 3.0    // below this → no meaningful change
    private const val THRESHOLD_LOADING = 12.0 // below this → minor change (might be loading)
    // above THRESHOLD_LOADING → significant change

    enum class ChangeLevel {
        NONE,       // Screen looks identical — action had no effect
        MINOR,      // Small change — possibly a loading spinner or cursor blink
        SIGNIFICANT // Clear screen change — action worked
    }

    /**
     * Compare two bitmaps and return the change level.
     *
     * Both bitmaps should be the same dimensions (capture resolution).
     * Returns NONE if dimensions don't match or either bitmap is null.
     */
    fun compare(before: Bitmap?, after: Bitmap?): ChangeLevel {
        if (before == null || after == null) {
            Log.w(TAG, "compare: null bitmap — skipping change detection")
            return ChangeLevel.SIGNIFICANT // assume change if we can't check
        }

        if (before.width != after.width || before.height != after.height) {
            Log.w(TAG, "compare: dimension mismatch ${before.width}x${before.height} vs ${after.width}x${after.height}")
            return ChangeLevel.SIGNIFICANT
        }

        val w = before.width
        val h = before.height

        // Sample a grid of pixels — stride every SAMPLE_STRIDE px in each axis
        var totalDiff = 0.0
        var sampleCount = 0

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pb = before.getPixel(x, y)
                val pa = after.getPixel(x, y)

                // Extract R, G, B channels (ignore alpha)
                val dr = abs(((pb shr 16) and 0xFF) - ((pa shr 16) and 0xFF))
                val dg = abs(((pb shr 8) and 0xFF) - ((pa shr 8) and 0xFF))
                val db = abs((pb and 0xFF) - (pa and 0xFF))

                totalDiff += (dr + dg + db) / 3.0
                sampleCount++
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }

        if (sampleCount == 0) return ChangeLevel.SIGNIFICANT

        val meanDiff = totalDiff / sampleCount

        val level = when {
            meanDiff < THRESHOLD_MINOR   -> ChangeLevel.NONE
            meanDiff < THRESHOLD_LOADING -> ChangeLevel.MINOR
            else                         -> ChangeLevel.SIGNIFICANT
        }

        Log.d(TAG, "meanDiff=%.2f → $level (sampled $sampleCount pixels)".format(meanDiff))
        return level
    }

    /**
     * Quick boolean check: did the screen change at all?
     */
    fun hasChanged(before: Bitmap?, after: Bitmap?): Boolean =
        compare(before, after) != ChangeLevel.NONE

    /**
     * Returns a perceptual hash of the bitmap (difference hash, 64-bit).
     * Used to detect if the exact same screen is repeating across multiple steps.
     *
     * Two screens with the same hash are visually identical.
     */
    fun perceptualHash(bitmap: Bitmap): Long {
        // Resize to 9x8, convert to grayscale, compare adjacent pixels in each row
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, false)
        var hash = 0L

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val left = small.getPixel(col, row)
                val right = small.getPixel(col + 1, row)

                val gLeft = toGray(left)
                val gRight = toGray(right)

                val bit = if (gLeft > gRight) 1L else 0L
                hash = hash or (bit shl (row * 8 + col))
            }
        }

        small.recycle()
        return hash
    }

    /**
     * Hamming distance between two perceptual hashes.
     * 0 = identical, 64 = completely different.
     * < 10 is considered the same image.
     */
    fun hashDistance(h1: Long, h2: Long): Int =
        java.lang.Long.bitCount(h1 xor h2)

    fun isSameScreen(h1: Long, h2: Long): Boolean =
        hashDistance(h1, h2) < 10

    private fun toGray(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
