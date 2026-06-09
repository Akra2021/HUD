package com.hud.extension

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object CameraRegionCapture {

    const val CROP_X = 1675
    const val CROP_Y = 128
    const val CROP_WIDTH = 842
    const val CROP_HEIGHT = 474
    const val OUTPUT_WIDTH = 800
    const val OUTPUT_HEIGHT = 480

    fun captureCropAndScale(): Bitmap? {
        val screenshot = captureScreenshotInMemory() ?: return null
        return try {
            cropAndScale(screenshot)
        } finally {
            if (!screenshot.isRecycled) screenshot.recycle()
        }
    }

    fun cropAndScale(screenshot: Bitmap): Bitmap? {
        if (screenshot.width < CROP_X + CROP_WIDTH || screenshot.height < CROP_Y + CROP_HEIGHT) {
            HudLog.w(
                "turn-signal crop out of bounds: screenshot=${screenshot.width}x${screenshot.height}, " +
                    "crop=$CROP_X,$CROP_Y ${CROP_WIDTH}x$CROP_HEIGHT"
            )
            return null
        }

        val cropped = Bitmap.createBitmap(screenshot, CROP_X, CROP_Y, CROP_WIDTH, CROP_HEIGHT)
        return try {
            Bitmap.createScaledBitmap(cropped, OUTPUT_WIDTH, OUTPUT_HEIGHT, true)
        } finally {
            if (cropped !== screenshot && !cropped.isRecycled) {
                cropped.recycle()
            }
        }
    }

    /** Screencap via shell stdout — no temp files on disk. */
    private fun captureScreenshotInMemory(): Bitmap? {
        val commands = listOf(
            arrayOf("screencap", "-p"),
            arrayOf("screencap", "-d", "0", "-p"),
            arrayOf("sh", "-c", "screencap -p"),
            arrayOf("sh", "-c", "screencap -d 0 -p"),
            arrayOf("sh", "-c", "/system/bin/screencap -p"),
        )

        for (command in commands) {
            try {
                val process = Runtime.getRuntime().exec(command)
                val bytes = process.inputStream.use { it.readBytes() }
                val exitCode = process.waitFor()
                if (exitCode != 0 || bytes.isEmpty()) {
                    HudLog.w("turn-signal screencap stdout empty: exit=$exitCode cmd=${command.joinToString(" ")}")
                    continue
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                    HudLog.i("turn-signal screencap in-memory ${bitmap.width}x${bitmap.height} (${bytes.size}b)")
                    return bitmap
                }
            } catch (error: Exception) {
                HudLog.w("turn-signal screencap error: ${error.message}")
            }
        }
        return null
    }
}
