package com.bumpbot.flappy.bot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

enum class BotAction { TAP, WAIT }

data class BotState(
    val birdY: Int = -1,
    val gapCenterY: Int = -1,
    val pipeX: Int = -1,
    val action: BotAction = BotAction.WAIT,
    val fps: Int = 0
)

class FlappyBot {

    private var bgColor: Int = Color.rgb(112, 197, 254)
    private var colorTolerance: Int = 80

    private var birdXRatioStart: Float = 0.08f
    private var birdXRatioEnd: Float = 0.28f
    private var scanStep: Int = 3
    private var gapMargin: Int = 30

    private var lastTapTime: Long = 0
    private val minTapInterval: Long = 80

    private var calibrated = false

    fun calibrate(frame: Bitmap) {
        val cx = frame.width / 2
        val samples = mutableListOf<Int>()
        for (dy in -5..5) {
            val y = (frame.height * 0.08f).toInt() + dy
            if (y in 0 until frame.height) {
                samples.add(frame.getPixel(cx, y))
            }
        }
        for (dx in -5..5) {
            val x = cx + dx
            val y = (frame.height * 0.05f).toInt()
            if (x in 0 until frame.width && y in 0 until frame.height) {
                samples.add(frame.getPixel(x, y))
            }
        }
        if (samples.isNotEmpty()) {
            var rSum = 0L; var gSum = 0L; var bSum = 0L
            for (c in samples) {
                rSum += Color.red(c)
                gSum += Color.green(c)
                bSum += Color.blue(c)
            }
            val n = samples.size
            bgColor = Color.rgb(
                (rSum / n).toInt(),
                (gSum / n).toInt(),
                (bSum / n).toInt()
            )
            calibrated = true
        }
    }

    fun analyze(frame: Bitmap): BotState {
        val w = frame.width
        val h = frame.height

        if (!calibrated) calibrate(frame)

        val birdXStart = (w * birdXRatioStart).toInt()
        val birdXEnd = (w * birdXRatioEnd).toInt()

        val birdY = findBirdY(frame, birdXStart, birdXEnd, h)

        var gapCenterY = h / 2
        var pipeX = -1

        val scanStart = birdXEnd + 10
        val scanEnd = (w * 0.85f).toInt()

        var foundPipe = false
        var x = scanStart
        while (x < scanEnd) {
            val nonBgCount = countNonBgInColumn(frame, x, h)
            if (nonBgCount > h * 0.25) {
                pipeX = x
                foundPipe = true
                break
            }
            x += scanStep * 2
        }

        if (foundPipe && pipeX > 0) {
            gapCenterY = findGapCenter(frame, pipeX, h)
        } else {
            var nearestPipeX = -1
            var nearestDist = Int.MAX_VALUE
            x = scanStart
            while (x < scanEnd) {
                val nonBgCount = countNonBgInColumn(frame, x, h)
                if (nonBgCount > h * 0.15) {
                    val dist = x - birdXEnd
                    if (dist > 0 && dist < nearestDist) {
                        nearestDist = dist
                        nearestPipeX = x
                    }
                }
                x += scanStep
            }
            if (nearestPipeX > 0) {
                pipeX = nearestPipeX
                gapCenterY = findGapCenter(frame, nearestPipeX, h)
            }
        }

        val action = decideAction(birdY, gapCenterY, h)

        return BotState(
            birdY = birdY,
            gapCenterY = gapCenterY,
            pipeX = pipeX,
            action = action
        )
    }

    private fun findBirdY(frame: Bitmap, xStart: Int, xEnd: Int, height: Int): Int {
        var weightedSum = 0L
        var count = 0

        var x = xStart
        while (x < xEnd) {
            var y = (height * 0.1).toInt()
            while (y < (height * 0.9).toInt()) {
                val pixel = frame.getPixel(x, y)
                if (!isBackground(pixel)) {
                    weightedSum += y
                    count++
                }
                y += scanStep
            }
            x += scanStep
        }

        return if (count > 5) (weightedSum / count).toInt() else height / 2
    }

    private fun countNonBgInColumn(frame: Bitmap, x: Int, height: Int): Int {
        if (x < 0 || x >= frame.width) return 0
        var count = 0
        var y = (height * 0.05).toInt()
        while (y < (height * 0.95).toInt()) {
            if (!isBackground(frame.getPixel(x, y))) count++
            y += scanStep
        }
        return count
    }

    private fun findGapCenter(frame: Bitmap, pipeX: Int, height: Int): Int {
        val clampedX = pipeX.coerceIn(0, frame.width - 1)
        var longestStart = 0
        var longestLen = 0
        var curStart = -1
        var curLen = 0

        var y = (height * 0.05).toInt()
        while (y < (height * 0.95).toInt()) {
            val pixel = frame.getPixel(clampedX, y)
            if (isBackground(pixel)) {
                if (curStart < 0) curStart = y
                curLen++
            } else {
                if (curLen > longestLen) {
                    longestStart = curStart
                    longestLen = curLen
                }
                curStart = -1
                curLen = 0
            }
            y += 2
        }
        if (curLen > longestLen) {
            longestStart = curStart
            longestLen = curLen
        }

        return if (longestLen > 10) longestStart + longestLen / 2 else height / 2
    }

    private fun decideAction(birdY: Int, targetY: Int, screenHeight: Int): BotAction {
        if (birdY < 0) return BotAction.WAIT

        val now = System.currentTimeMillis()
        if (now - lastTapTime < minTapInterval) return BotAction.WAIT

        val diff = birdY - targetY

        if (diff > gapMargin) {
            lastTapTime = now
            return BotAction.TAP
        }

        if (birdY > screenHeight * 0.82) {
            lastTapTime = now
            return BotAction.TAP
        }

        return BotAction.WAIT
    }

    private fun isBackground(pixel: Int): Boolean {
        val dr = abs(Color.red(pixel) - Color.red(bgColor))
        val dg = abs(Color.green(pixel) - Color.green(bgColor))
        val db = abs(Color.blue(pixel) - Color.blue(bgColor))
        val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())
        return dist < colorTolerance
    }

    fun setTolerance(value: Int) {
        colorTolerance = value.coerceIn(30, 200)
    }

    fun setGapMargin(value: Int) {
        gapMargin = value.coerceIn(5, 100)
    }

    fun isCalibrated(): Boolean = calibrated

    fun resetCalibration() {
        calibrated = false
    }
}
