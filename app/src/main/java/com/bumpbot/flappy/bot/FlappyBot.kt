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
    private var bgColors = mutableListOf<Int>()
    private var colorTolerance: Int = 55
    private var calibrated = false

    private var birdXRatioStart = 0.05f
    private var birdXRatioEnd = 0.35f
    private var scanStep = 2

    private var lastTapTime: Long = 0
    private val minTapInterval: Long = 90

    private var prevBirdY: Int = -1
    private var birdVelocity: Float = 0f
    private var lastFrameTime: Long = 0

    fun calibrate(frame: Bitmap) {
        bgColors.clear()
        val w = frame.width
        val h = frame.height

        val regions = listOf(
            Pair(w / 2, (h * 0.03f).toInt()),
            Pair(w / 4, (h * 0.03f).toInt()),
            Pair(w * 3 / 4, (h * 0.03f).toInt()),
            Pair(w / 2, (h * 0.07f).toInt()),
            Pair(w * 3 / 4, (h * 0.07f).toInt()),
        )

        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for ((cx, cy) in regions) {
            for (dx in -3..3) {
                for (dy in -3..3) {
                    val px = (cx + dx).coerceIn(0, w - 1)
                    val py = (cy + dy).coerceIn(0, h - 1)
                    val c = frame.getPixel(px, py)
                    rSum += Color.red(c)
                    gSum += Color.green(c)
                    bSum += Color.blue(c)
                    bgColors.add(c)
                    count++
                }
            }
        }
        if (count > 0) {
            bgColor = Color.rgb(
                (rSum / count).toInt(),
                (gSum / count).toInt(),
                (bSum / count).toInt()
            )
        }
        calibrated = true
        prevBirdY = -1
        birdVelocity = 0f
    }

    fun analyze(frame: Bitmap): BotState {
        val w = frame.width
        val h = frame.height

        if (!calibrated) calibrate(frame)

        val birdY = findBirdY(frame, w, h)

        val now = System.currentTimeMillis()
        if (prevBirdY > 0 && lastFrameTime > 0) {
            val dt = (now - lastFrameTime).coerceAtLeast(1)
            birdVelocity = ((birdY - prevBirdY).toFloat() / dt) * 16f
        }
        prevBirdY = birdY
        lastFrameTime = now

        val (gapCenterY, pipeX) = findNextGap(frame, w, h)

        val action = decideAction(birdY, gapCenterY, h)

        return BotState(
            birdY = birdY,
            gapCenterY = gapCenterY,
            pipeX = pipeX,
            action = action
        )
    }

    private fun findBirdY(frame: Bitmap, w: Int, h: Int): Int {
        val xStart = (w * birdXRatioStart).toInt()
        val xEnd = (w * birdXRatioEnd).toInt()
        val yStart = (h * 0.08).toInt()
        val yEnd = (h * 0.92).toInt()

        var bestY = -1
        var bestClusterSize = 0

        var y = yStart
        while (y < yEnd) {
            var clusterCount = 0
            var x = xStart
            while (x < xEnd) {
                val pixel = frame.getPixel(x, y)
                if (!isBackground(pixel)) {
                    clusterCount++
                }
                x += scanStep
            }

            if (clusterCount > bestClusterSize) {
                bestClusterSize = clusterCount
                bestY = y
            }
            y += scanStep
        }

        if (bestY > 0 && bestClusterSize >= 3) {
            var weightedSum = 0L
            var totalWeight = 0
            val searchRange = (h * 0.08f).toInt().coerceAtLeast(15)
            var sy = (bestY - searchRange).coerceAtLeast(yStart)
            while (sy < (bestY + searchRange).coerceAtMost(yEnd)) {
                var rowCount = 0
                var x = xStart
                while (x < xEnd) {
                    if (!isBackground(frame.getPixel(x, sy))) rowCount++
                    x += scanStep
                }
                if (rowCount >= 2) {
                    weightedSum += sy.toLong() * rowCount
                    totalWeight += rowCount
                }
                sy += 1
            }
            if (totalWeight > 0) return (weightedSum / totalWeight).toInt()
        }

        return if (bestY > 0) bestY else h / 2
    }

    private fun findNextGap(frame: Bitmap, w: Int, h: Int): Pair<Int, Int> {
        val scanXStart = (w * 0.30f).toInt()
        val scanXEnd = (w * 0.90f).toInt()
        val yTop = (h * 0.05f).toInt()
        val yBot = (h * 0.95f).toInt()

        var bestPipeX = -1
        var bestGapCenter = h / 2
        var minPipeX = Int.MAX_VALUE

        var x = scanXStart
        while (x < scanXEnd) {
            var nonBgCount = 0
            var y = yTop
            while (y < yBot) {
                if (!isBackground(frame.getPixel(x.coerceIn(0, w - 1), y))) nonBgCount++
                y += scanStep
            }

            val totalScanned = (yBot - yTop) / scanStep
            val fillRatio = nonBgCount.toFloat() / totalScanned

            if (fillRatio > 0.30f && fillRatio < 0.85f) {
                if (x < minPipeX) {
                    minPipeX = x
                    bestPipeX = x

                    val gapCenter = findGapInColumn(frame, x, h)
                    bestGapCenter = gapCenter
                }
            }
            x += scanStep * 3
        }

        if (bestPipeX > 0) {
            var refineGap = 0
            var refineCount = 0
            val range = (w * 0.03f).toInt().coerceAtLeast(4)
            var rx = (bestPipeX - range).coerceAtLeast(0)
            while (rx <= (bestPipeX + range).coerceAtMost(w - 1)) {
                val g = findGapInColumn(frame, rx, h)
                if (g > 0) {
                    refineGap += g
                    refineCount++
                }
                rx += 2
            }
            if (refineCount > 0) bestGapCenter = refineGap / refineCount
        }

        return Pair(bestGapCenter, bestPipeX)
    }

    private fun findGapInColumn(frame: Bitmap, x: Int, h: Int): Int {
        val cx = x.coerceIn(0, frame.width - 1)
        val yTop = (h * 0.05f).toInt()
        val yBot = (h * 0.95f).toInt()

        var longestStart = 0
        var longestLen = 0
        var curStart = -1
        var curLen = 0

        var y = yTop
        while (y < yBot) {
            if (isBackground(frame.getPixel(cx, y))) {
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
            y += 1
        }
        if (curLen > longestLen) {
            longestStart = curStart
            longestLen = curLen
        }

        return if (longestLen > 8) longestStart + (longestLen * scanStep) / 2
        else h / 2
    }

    private fun decideAction(birdY: Int, targetY: Int, screenHeight: Int): BotAction {
        if (birdY < 0) return BotAction.WAIT

        val now = System.currentTimeMillis()
        if (now - lastTapTime < minTapInterval) return BotAction.WAIT

        val predictedY = birdY + (birdVelocity * 3).toInt()

        val targetWithOffset = targetY - (screenHeight * 0.03f).toInt()

        val diff = predictedY - targetWithOffset

        if (birdY > screenHeight * 0.85) {
            lastTapTime = now
            return BotAction.TAP
        }

        if (birdY < screenHeight * 0.05) {
            return BotAction.WAIT
        }

        val threshold = (screenHeight * 0.02f).toInt().coerceAtLeast(5)

        if (diff > threshold) {
            lastTapTime = now
            return BotAction.TAP
        }

        if (birdVelocity > 2.5f && diff > -threshold) {
            lastTapTime = now
            return BotAction.TAP
        }

        return BotAction.WAIT
    }

    private fun isBackground(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)
        val dist = sqrt(
            ((r - bgR) * (r - bgR) + (g - bgG) * (g - bgG) + (b - bgB) * (b - bgB)).toDouble()
        )
        return dist < colorTolerance
    }

    fun isCalibrated(): Boolean = calibrated

    fun resetCalibration() {
        calibrated = false
        prevBirdY = -1
        birdVelocity = 0f
    }
}
