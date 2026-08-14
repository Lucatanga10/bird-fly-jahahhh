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

    fun calibrate(frame: Bitmap) {
        calibrated = true
        prevBirdY = -1
        birdVelocity = 0f
    }

    fun analyze(frame: Bitmap): BotState {
        val w = frame.width
        val h = frame.height

        if (!calibrated) calibrated = true

        val gameTop = (h * 0.10f).toInt()
        val gameBottom = (h * 0.83f).toInt()
        val gameHeight = gameBottom - gameTop

        val birdY = findBird(frame, w, gameTop, gameBottom)

        val now = System.currentTimeMillis()
        if (prevBirdY > 0 && lastFrameTime > 0) {
            val dt = ((now - lastFrameTime).coerceAtLeast(1)).toFloat()
            val rawVel = (birdY - prevBirdY) / dt
            birdVelocity = birdVelocity * 0.6f + rawVel * 0.4f
        }
        prevBirdY = birdY
        lastFrameTime = now

        val (gapCenter, pipeX) = findNextGap(frame, w, h, gameTop, gameBottom)

        val pipeDistance = if (pipeX > 0) {
            val birdX = (w * 0.20f).toInt()
            (pipeX - birdX).toFloat() / w
        } else 1.0f

        val action = decideAction(birdY, gapCenter, gameTop, gameBottom, gameHeight, pipeDistance)

        return BotState(birdY, gapCenter, pipeX, action)
    }

    private fun findBird(frame: Bitmap, w: Int, gameTop: Int, gameBottom: Int): Int {
        val xStart = (w * 0.05f).toInt()
        val xEnd = (w * 0.40f).toInt()

        var sumY = 0L
        var count = 0

        var y = gameTop
        while (y < gameBottom) {
            var rowHits = 0
            var x = xStart
            while (x < xEnd) {
                if (isBirdColor(frame.getPixel(x, y))) rowHits++
                x += 2
            }
            if (rowHits >= 2) {
                sumY += y.toLong() * rowHits
                count += rowHits
            }
            y += 2
        }

        return if (count > 4) (sumY / count).toInt() else -1
    }

    private fun isBirdColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        if (r > 200 && g > 160 && b < 90 && r > g) return true
        if (r > 190 && g > 130 && b < 70 && r > g && g > b) return true
        if (r > 210 && g > 90 && g < 180 && b < 70) return true

        return false
    }

    private fun findNextGap(frame: Bitmap, w: Int, h: Int, gameTop: Int, gameBottom: Int): Pair<Int, Int> {
        val scanXStart = (w * 0.30f).toInt()
        val scanXEnd = (w * 0.95f).toInt()

        var closestPipeX = -1
        var closestGapCenter = (gameTop + gameBottom) / 2

        var x = scanXStart
        while (x < scanXEnd) {
            val cx = x.coerceIn(0, w - 1)

            val gapInfo = findGapInColumn(frame, cx, gameTop, gameBottom)

            if (gapInfo != null) {
                closestPipeX = cx
                closestGapCenter = gapInfo

                var refinedSum = 0
                var refinedCount = 0
                for (offset in -4..4 step 2) {
                    val rcx = (cx + offset).coerceIn(0, w - 1)
                    val g = findGapInColumn(frame, rcx, gameTop, gameBottom)
                    if (g != null) {
                        refinedSum += g
                        refinedCount++
                    }
                }
                if (refinedCount > 0) closestGapCenter = refinedSum / refinedCount

                break
            }
            x += 3
        }

        return Pair(closestGapCenter, closestPipeX)
    }

    private fun findGapInColumn(frame: Bitmap, x: Int, gameTop: Int, gameBottom: Int): Int? {
        val cx = x.coerceIn(0, frame.width - 1)

        var topPipeEnd = -1
        var bottomPipeStart = -1

        var y = gameTop
        var inPipe = false
        while (y < gameBottom) {
            val pipe = isPipeColor(frame.getPixel(cx, y))
            if (pipe && !inPipe) {
                inPipe = true
            } else if (!pipe && inPipe) {
                topPipeEnd = y
                break
            }
            y += 1
        }

        if (topPipeEnd < 0) return null

        y = gameBottom
        inPipe = false
        while (y > topPipeEnd) {
            val pipe = isPipeColor(frame.getPixel(cx, y.coerceIn(0, frame.height - 1)))
            if (pipe && !inPipe) {
                inPipe = true
            } else if (!pipe && inPipe) {
                bottomPipeStart = y
                break
            }
            y -= 1
        }

        if (bottomPipeStart < 0) {
            y = gameBottom - 1
            while (y > topPipeEnd + 10) {
                if (isPipeColor(frame.getPixel(cx, y.coerceIn(0, frame.height - 1)))) {
                    bottomPipeStart = y
                    break
                }
                y -= 1
            }
        }

        if (bottomPipeStart < 0) return null

        val gapSize = bottomPipeStart - topPipeEnd
        val minGap = (gameBottom - gameTop) * 0.08f
        if (gapSize < minGap) return null

        return topPipeEnd + gapSize / 2
    }

    private fun isPipeColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        if (r in 130..240 && g in 70..190 && b in 30..130 && r > g && g > b && (r - b) > 40) return true
        if (r in 100..200 && g in 60..160 && b in 20..110 && r > b + 30) return true

        return false
    }

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

        val minInterval = if (pipeDistance < 0.15f) 150L else 200L
        if (now - lastTapTime < minInterval) return BotAction.WAIT

        if (birdY < gameTop + gameHeight * 0.06f) {
            return BotAction.WAIT
        }

        if (birdY > gameBottom - gameHeight * 0.08f) {
            lastTapTime = now
            return BotAction.TAP
        }

        val error = birdY - targetY

        val futureY = birdY + (birdVelocity * 120).toInt()
        val futureError = futureY - targetY

        val urgentMargin = (gameHeight * 0.06f).toInt()
        val tapMargin = (gameHeight * 0.03f).toInt()

        if (error > urgentMargin) {
            lastTapTime = now
            return BotAction.TAP
        }

        if (error > tapMargin && birdVelocity >= 0) {
            lastTapTime = now
            return BotAction.TAP
        }

        if (futureError > urgentMargin && pipeDistance < 0.30f) {
            lastTapTime = now
            return BotAction.TAP
        }

        if (error > 0 && birdVelocity > 0.3f) {
            lastTapTime = now
            return BotAction.TAP
        }

        return BotAction.WAIT
    }

    fun isCalibrated(): Boolean = calibrated

    fun resetCalibration() {
        calibrated = false
        prevBirdY = -1
        birdVelocity = 0f
    }
}
