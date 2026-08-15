package com.bumpbot.flappy.bot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

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

    private var prevBirdY: Int = -1
    private var birdVelocity: Float = 0f
    private var lastFrameTime: Long = 0

    private var consecutiveTaps = 0

    fun calibrate(frame: Bitmap) {
        calibrated = true
        prevBirdY = -1
        birdVelocity = 0f
        consecutiveTaps = 0
    }

    fun analyze(frame: Bitmap): BotState {
        val w = frame.width
        val h = frame.height

        if (!calibrated) calibrated = true

        val gameTop = (h * 0.12f).toInt()
        val gameBottom = (h * 0.78f).toInt()
        val gameHeight = gameBottom - gameTop

        val birdY = findBird(frame, w, gameTop, gameBottom)

        val now = System.currentTimeMillis()
        if (prevBirdY > 0 && birdY > 0 && lastFrameTime > 0) {
            val dt = (now - lastFrameTime).coerceAtLeast(1).toFloat()
            val rawVel = (birdY - prevBirdY).toFloat() * (16f / dt)
            birdVelocity = birdVelocity * 0.5f + rawVel * 0.5f
        }
        if (birdY > 0) prevBirdY = birdY
        lastFrameTime = now

        val (gapCenter, pipeX) = findNextGap(frame, w, h, gameTop, gameBottom)

        val birdScreenX = (w * 0.18f).toInt()
        val pipeDistance = if (pipeX > 0) (pipeX - birdScreenX).toFloat() / w else 0.5f

        val action = decideAction(birdY, gapCenter, gameTop, gameBottom, gameHeight, pipeDistance)

        return BotState(birdY, gapCenter, pipeX, action)
    }

    private fun findBird(frame: Bitmap, w: Int, gameTop: Int, gameBottom: Int): Int {
        val xStart = (w * 0.06f).toInt()
        val xEnd = (w * 0.36f).toInt()

        var bestRowStart = -1
        var bestRowEnd = -1
        var bestScore = 0

        var currentRunStart = -1
        var currentScore = 0
        var runLength = 0

        var y = gameTop
        while (y < gameBottom) {
            var hits = 0
            var maxRun = 0
            var run = 0
            var x = xStart
            while (x < xEnd) {
                if (isBirdColor(frame.getPixel(x, y))) {
                    hits++
                    run++
                    if (run > maxRun) maxRun = run
                } else {
                    run = 0
                }
                x += 2
            }

            val isGoodRow = maxRun >= 3 && hits >= 4

            if (isGoodRow) {
                if (currentRunStart < 0) {
                    currentRunStart = y
                    currentScore = 0
                    runLength = 0
                }
                currentScore += hits
                runLength++
            } else {
                if (currentRunStart >= 0 && runLength >= 3 && currentScore > bestScore) {
                    bestScore = currentScore
                    bestRowStart = currentRunStart
                    bestRowEnd = y
                }
                currentRunStart = -1
                runLength = 0
                currentScore = 0
            }
            y += 2
        }

        if (currentRunStart >= 0 && runLength >= 3 && currentScore > bestScore) {
            bestScore = currentScore
            bestRowStart = currentRunStart
            bestRowEnd = gameBottom
        }

        return if (bestScore >= 12) (bestRowStart + bestRowEnd) / 2 else -1
    }

    private fun isBirdColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        if (r > 200 && g > 160 && b < 100 && r > g) return true
        if (r > 190 && g > 120 && b < 80 && r > g && g > b + 20) return true
        if (r > 220 && g > 180 && b < 60) return true
        if (r > 210 && g > 90 && g < 180 && b < 70) return true

        return false
    }

    private fun findNextGap(
        frame: Bitmap, w: Int, h: Int,
        gameTop: Int, gameBottom: Int
    ): Pair<Int, Int> {
        val scanXStart = (w * 0.28f).toInt()
        val scanXEnd = (w * 0.95f).toInt()

        var closestPipeX = -1
        var closestGapCenter = (gameTop + gameBottom) / 2

        var x = scanXStart
        while (x < scanXEnd) {
            val cx = x.coerceIn(0, w - 1)

            val gapInfo = scanColumnForGap(frame, cx, gameTop, gameBottom)

            if (gapInfo != null) {
                var confirmCount = 0
                val gapValues = mutableListOf(gapInfo)

                for (offset in intArrayOf(-6, -3, 3, 6)) {
                    val rx = (cx + offset).coerceIn(0, w - 1)
                    val g = scanColumnForGap(frame, rx, gameTop, gameBottom)
                    if (g != null && abs(g - gapInfo) < (gameBottom - gameTop) * 0.15f) {
                        confirmCount++
                        gapValues.add(g)
                    }
                }

                if (confirmCount >= 1) {
                    closestPipeX = cx
                    closestGapCenter = gapValues.sorted()[gapValues.size / 2]
                    break
                }
            }
            x += 4
        }

        return Pair(closestGapCenter, closestPipeX)
    }

    private fun scanColumnForGap(
        frame: Bitmap, x: Int,
        gameTop: Int, gameBottom: Int
    ): Int? {
        val cx = x.coerceIn(0, frame.width - 1)
        val totalH = gameBottom - gameTop

        var topPipeEnd = -1
        var bottomPipeStart = -1

        var y = gameTop
        var pipeRunLen = 0
        var inPipe = false
        while (y < gameBottom) {
            val yc = y.coerceIn(0, frame.height - 1)
            val pipe = isPipeColor(frame.getPixel(cx, yc))
            if (pipe) {
                pipeRunLen++
                if (pipeRunLen >= 4) inPipe = true
            } else {
                if (inPipe) {
                    topPipeEnd = y - 1
                    break
                }
                pipeRunLen = 0
            }
            y += 2
        }

        if (topPipeEnd < 0) return null

        y = topPipeEnd + 2
        var gapRunLen = 0
        while (y < gameBottom) {
            val yc = y.coerceIn(0, frame.height - 1)
            val pipe = isPipeColor(frame.getPixel(cx, yc))
            if (!pipe) {
                gapRunLen++
            } else {
                if (gapRunLen >= 4) {
                    bottomPipeStart = y
                    break
                }
                gapRunLen = 0
                topPipeEnd = -1
                return null
            }
            y += 2
        }

        if (bottomPipeStart < 0) {
            y = gameBottom - 2
            while (y > topPipeEnd + 20) {
                val yc = y.coerceIn(0, frame.height - 1)
                if (isPipeColor(frame.getPixel(cx, yc))) {
                    bottomPipeStart = y
                    break
                }
                y -= 2
            }
        }

        if (bottomPipeStart < 0) return null

        val gapSize = bottomPipeStart - topPipeEnd
        if (gapSize < totalH * 0.07f) return null
        if (gapSize > totalH * 0.50f) return null

        val topPipeLen = topPipeEnd - gameTop
        if (topPipeLen < totalH * 0.06f) return null

        return topPipeEnd + gapSize / 2
    }

    private fun isPipeColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        if (r < 80 || g < 40) return false

        val sat = r - b
        if (sat < 25) return false

        if (r > g && g > b) {
            if (r in 100..250 && g in 55..200 && b in 15..140) return true
        }

        if (r > b + 30 && r in 120..250 && g in 70..210 && b in 20..130) return true

        return false
    }

    @Suppress("unused")
    private fun isGrassColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return g > r && g > b && g > 80
    }

    private fun decideAction(
        birdY: Int, targetY: Int,
        gameTop: Int, gameBottom: Int, gameHeight: Int,
        pipeDistance: Float
    ): BotAction {
        if (birdY < 0) return BotAction.WAIT

        val now = System.currentTimeMillis()

        val minInterval = when {
            pipeDistance < 0.10f -> 120L
            pipeDistance < 0.20f -> 160L
            else -> 190L
        }
        if (now - lastTapTime < minInterval) return BotAction.WAIT

        if (birdY < gameTop + gameHeight * 0.04f) {
            consecutiveTaps = 0
            return BotAction.WAIT
        }

        if (birdY > gameBottom - gameHeight * 0.05f) {
            lastTapTime = now
            consecutiveTaps++
            return BotAction.TAP
        }

        val error = birdY - targetY

        if (error < -(gameHeight * 0.04f) && birdVelocity < 0.5f) {
            consecutiveTaps = 0
            return BotAction.WAIT
        }

        val futureFrames = if (pipeDistance < 0.20f) 5f else 8f
        val futureY = birdY + (birdVelocity * futureFrames).toInt()
        val futureError = futureY - targetY

        val urgentMargin = (gameHeight * 0.07f).toInt()
        val tapMargin = (gameHeight * 0.025f).toInt()

        if (error > urgentMargin) {
            lastTapTime = now
            consecutiveTaps++
            return BotAction.TAP
        }

        if (error > tapMargin && birdVelocity >= -0.3f) {
            lastTapTime = now
            consecutiveTaps++
            return BotAction.TAP
        }

        if (error > 0 && birdVelocity > 0.5f) {
            lastTapTime = now
            consecutiveTaps++
            return BotAction.TAP
        }

        if (futureError > urgentMargin && pipeDistance < 0.30f) {
            lastTapTime = now
            consecutiveTaps++
            return BotAction.TAP
        }

        if (error > 0 && birdVelocity > 0.2f && pipeDistance < 0.15f) {
            lastTapTime = now
            consecutiveTaps++
            return BotAction.TAP
        }

        consecutiveTaps = 0
        return BotAction.WAIT
    }

    fun isCalibrated(): Boolean = calibrated

    fun resetCalibration() {
        calibrated = false
        prevBirdY = -1
        birdVelocity = 0f
        consecutiveTaps = 0
    }
}
