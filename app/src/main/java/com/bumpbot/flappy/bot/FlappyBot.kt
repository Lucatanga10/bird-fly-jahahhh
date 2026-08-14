package com.bumpbot.flappy.bot

import android.graphics.Bitmap
import android.graphics.Color

enum class BotAction { TAP, WAIT }

data class BotState(
    val birdY: Int = -1,
    val gapCenterY: Int = -1,
    val pipeX: Int = -1,
    val action: BotAction = BotAction.WAIT,
    val fps: Int = 0
)

class FlappyBot {

    private var calibrated = false
    private var lastTapTime: Long = 0
    private val minTapInterval: Long = 250

    private var prevBirdY: Int = -1
    private var birdFalling = false

    fun calibrate(frame: Bitmap) {
        calibrated = true
        prevBirdY = -1
    }

    fun analyze(frame: Bitmap): BotState {
        val w = frame.width
        val h = frame.height

        if (!calibrated) calibrated = true

        val gameTop = (h * 0.12f).toInt()
        val gameBottom = (h * 0.82f).toInt()

        val birdY = findBird(frame, w, h, gameTop, gameBottom)

        if (prevBirdY > 0 && birdY > 0) {
            birdFalling = birdY > prevBirdY
        }
        prevBirdY = birdY

        val (gapCenter, pipeX) = findNextGap(frame, w, h, gameTop, gameBottom)

        val action = decideAction(birdY, gapCenter, gameTop, gameBottom)

        return BotState(birdY, gapCenter, pipeX, action)
    }

    private fun findBird(frame: Bitmap, w: Int, h: Int, gameTop: Int, gameBottom: Int): Int {
        val xStart = (w * 0.05f).toInt()
        val xEnd = (w * 0.40f).toInt()

        var sumY = 0L
        var count = 0

        var y = gameTop
        while (y < gameBottom) {
            var x = xStart
            while (x < xEnd) {
                val pixel = frame.getPixel(x, y)
                if (isBirdColor(pixel)) {
                    sumY += y
                    count++
                }
                x += 3
            }
            y += 3
        }

        return if (count > 3) (sumY / count).toInt() else (gameTop + gameBottom) / 2
    }

    private fun isBirdColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        if (r > 200 && g > 170 && b < 80) return true
        if (r > 180 && g > 140 && g < 200 && b < 60) return true
        if (r > 220 && g > 100 && g < 170 && b < 60) return true

        return false
    }

    private fun findNextGap(frame: Bitmap, w: Int, h: Int, gameTop: Int, gameBottom: Int): Pair<Int, Int> {
        val scanXStart = (w * 0.35f).toInt()
        val scanXEnd = (w * 0.92f).toInt()
        val step = 4

        var firstPipeX = -1

        var x = scanXStart
        while (x < scanXEnd) {
            val cx = x.coerceIn(0, w - 1)
            var pipePixels = 0
            var totalPixels = 0

            var y = gameTop
            while (y < gameBottom) {
                if (isPipeColor(frame.getPixel(cx, y))) pipePixels++
                totalPixels++
                y += 3
            }

            val ratio = pipePixels.toFloat() / totalPixels.coerceAtLeast(1)
            if (ratio > 0.20f) {
                firstPipeX = cx
                break
            }
            x += step
        }

        if (firstPipeX < 0) {
            return Pair((gameTop + gameBottom) / 2, -1)
        }

        var bestGapCenter = (gameTop + gameBottom) / 2
        var bestGapSize = 0

        val checkCols = mutableListOf(firstPipeX)
        for (offset in listOf(-6, -3, 3, 6)) {
            val col = (firstPipeX + offset).coerceIn(0, w - 1)
            checkCols.add(col)
        }

        for (col in checkCols) {
            var longestGapStart = 0
            var longestGapLen = 0
            var curGapStart = -1
            var curGapLen = 0

            var y = gameTop
            while (y < gameBottom) {
                val pixel = frame.getPixel(col, y)
                if (!isPipeColor(pixel)) {
                    if (curGapStart < 0) curGapStart = y
                    curGapLen++
                } else {
                    if (curGapLen > longestGapLen) {
                        longestGapStart = curGapStart
                        longestGapLen = curGapLen
                    }
                    curGapStart = -1
                    curGapLen = 0
                }
                y += 2
            }
            if (curGapLen > longestGapLen) {
                longestGapStart = curGapStart
                longestGapLen = curGapLen
            }

            if (longestGapLen > bestGapSize) {
                bestGapSize = longestGapLen
                bestGapCenter = longestGapStart + (longestGapLen * 2) / 2
            }
        }

        return Pair(bestGapCenter, firstPipeX)
    }

    private fun isPipeColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        if (r > 140 && g > 80 && g < 180 && b < 120 && r > g && g > b) return true
        if (r > 160 && g > 100 && b > 50 && b < 110 && r > g && (r - b) > 60) return true
        if (r > 120 && g > 90 && b > 40 && b < 100 && r > b + 40) return true

        return false
    }

    private fun decideAction(birdY: Int, targetY: Int, gameTop: Int, gameBottom: Int): BotAction {
        if (birdY < 0) return BotAction.WAIT

        val now = System.currentTimeMillis()
        if (now - lastTapTime < minTapInterval) return BotAction.WAIT

        val gameHeight = gameBottom - gameTop

        if (birdY < gameTop + gameHeight * 0.08f) {
            return BotAction.WAIT
        }

        if (birdY > gameBottom - gameHeight * 0.10f) {
            lastTapTime = now
            return BotAction.TAP
        }

        val margin = (gameHeight * 0.04f).toInt().coerceAtLeast(8)

        if (birdY > targetY + margin) {
            lastTapTime = now
            return BotAction.TAP
        }

        if (birdY > targetY && birdFalling) {
            lastTapTime = now
            return BotAction.TAP
        }

        return BotAction.WAIT
    }

    fun isCalibrated(): Boolean = calibrated

    fun resetCalibration() {
        calibrated = false
        prevBirdY = -1
    }
}
