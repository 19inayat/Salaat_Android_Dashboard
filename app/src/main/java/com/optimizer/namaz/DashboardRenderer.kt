package com.optimizer.namaz

import android.graphics.*
import kotlin.math.cos
import kotlin.math.sin

object DashboardRenderer {

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        drawBackground(
            canvas,
            width,
            height
        )

        drawPrayerRing(
            canvas,
            width,
            height,
            state
        )

        drawNeedle(
            canvas,
            width,
            height,
            state
        )

        drawCenterInfo(
            canvas,
            width,
            height,
            state
        )

        drawBottomPanel(
            canvas,
            width,
            height,
            state
        )
    }

    private fun drawBackground(
        canvas: Canvas,
        width: Int,
        height: Int
    ) {

        val shader =
            LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                Color.parseColor("#09111F"),
                Color.parseColor("#16243B"),
                Shader.TileMode.CLAMP
            )

        val paint = Paint()

        paint.shader = shader

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            paint
        )
    }

    private fun drawPrayerRing(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        val cx = width / 2f

        val cy = height * 0.42f

        val radius = width * 0.34f

        val rect =
            RectF(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius
            )

        state.segments.forEach {

            val shader =
                SweepGradient(
                    cx,
                    cy,
                    intArrayOf(
                        it.color1,
                        it.color2
                    ),
                    null
                )

            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {

                    style = Paint.Style.STROKE

                    strokeWidth = 55f

                    this.shader = shader

                    strokeCap = Paint.Cap.ROUND
                }

            val start =
                ((it.start / 24.0) * 360f - 90f).toFloat()

            val sweep =
                (((it.end - it.start) / 24.0) * 360f).toFloat()

            canvas.drawArc(
                rect,
                start,
                sweep,
                false,
                paint
            )
        }
    }

    private fun drawNeedle(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        val cx = width / 2f

        val cy = height * 0.42f

        val radius = width * 0.30f

        val angle =
            ((state.currentDec / 24.0) * 360.0 - 90.0) *
                    (Math.PI / 180.0)

        val nx =
            cx + cos(angle).toFloat() * radius

        val ny =
            cy + sin(angle).toFloat() * radius

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.WHITE

                strokeWidth = 8f
            }

        canvas.drawLine(
            cx,
            cy,
            nx,
            ny,
            paint
        )

        canvas.drawCircle(
            cx,
            cy,
            18f,
            paint
        )
    }

    private fun drawCenterInfo(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        val cx = width / 2f

        val cy = height * 0.42f

        val title =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.WHITE

                textSize = width * 0.08f

                textAlign = Paint.Align.CENTER

                isFakeBoldText = true
            }

        canvas.drawText(
            state.currentSegment?.name ?: "--",
            cx,
            cy,
            title
        )

        val timer =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.parseColor("#90CAF9")

                textSize = width * 0.05f

                textAlign = Paint.Align.CENTER
            }

        canvas.drawText(
            state.timerStr,
            cx,
            cy + 80f,
            timer
        )
    }

    private fun drawBottomPanel(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        val rect =
            RectF(
                width * 0.08f,
                height * 0.78f,
                width * 0.92f,
                height * 0.93f
            )

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color =
                    Color.argb(
                        120,
                        20,
                        28,
                        45
                    )
            }

        canvas.drawRoundRect(
            rect,
            40f,
            40f,
            paint
        )

        val txt =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.WHITE

                textSize = width * 0.045f

                textAlign = Paint.Align.CENTER
            }

        canvas.drawText(
            "PLANETARY HOUR ACTIVE",
            width / 2f,
            height * 0.84f,
            txt
        )

        canvas.drawText(
            state.currentSegment?.name ?: "",
            width / 2f,
            height * 0.89f,
            txt
        )
    }
}
