package com.optimizer.namaz

import android.graphics.*
import kotlin.math.*

object DashboardRenderer {

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        drawBackground(canvas, width, height)

        val cx = width / 2f
        val cy = height * 0.40f

        drawOuterTicks(canvas, cx, cy, width)

        drawPrayerSegments(
            canvas,
            cx,
            cy,
            width,
            state
        )

        drawCenterCircle(
            canvas,
            cx,
            cy,
            width
        )

        drawNeedle(
            canvas,
            cx,
            cy,
            width,
            state
        )

        drawCenterText(
            canvas,
            cx,
            cy,
            width,
            state
        )

        drawBottomGlassPanel(
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
                Color.parseColor("#5B5AA8"),
                Color.parseColor("#45448F"),
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

    private fun drawOuterTicks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Int
    ) {

        val radius = width * 0.40f

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.argb(90, 255, 255, 255)

                strokeWidth = 2f

                textSize = width * 0.03f

                textAlign = Paint.Align.CENTER
            }

        for (i in 1..24) {

            val angle =
                Math.toRadians(
                    (i * 15f - 90f).toDouble()
                )

            val x1 =
                cx + cos(angle).toFloat() * radius

            val y1 =
                cy + sin(angle).toFloat() * radius

            val x2 =
                cx + cos(angle).toFloat() * (radius - 20)

            val y2 =
                cy + sin(angle).toFloat() * (radius - 20)

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                paint
            )

            val tx =
                cx + cos(angle).toFloat() * (radius + 30)

            val ty =
                cy + sin(angle).toFloat() * (radius + 30)

            canvas.drawText(
                i.toString(),
                tx,
                ty,
                paint
            )
        }
    }

    private fun drawPrayerSegments(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Int,
        state: NamazState
    ) {

        val radius = width * 0.31f

        val rect =
            RectF(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius
            )

        state.segments.forEach {

            val start =
                ((it.start / 24f) * 360f - 90f).toFloat()

            val sweep =
                (((it.end - it.start) / 24f) * 360f).toFloat()

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

                    strokeWidth = 85f

                    this.shader = shader

                    strokeCap = Paint.Cap.ROUND

                    setShadowLayer(
                        20f,
                        0f,
                        0f,
                        Color.BLACK
                    )
                }

            canvas.drawArc(
                rect,
                start,
                sweep,
                false,
                paint
            )
        }
    }

    private fun drawCenterCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Int
    ) {

        val radius = width * 0.18f

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                shader =
                    RadialGradient(
                        cx,
                        cy,
                        radius,
                        Color.parseColor("#171738"),
                        Color.parseColor("#0D1025"),
                        Shader.TileMode.CLAMP
                    )
            }

        canvas.drawCircle(
            cx,
            cy,
            radius,
            paint
        )
    }

    private fun drawNeedle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Int,
        state: NamazState
    ) {

        val radius = width * 0.24f

        val angle =
            Math.toRadians(
                (state.currentDec * 15f - 90f)
            )

        val nx =
            cx + cos(angle).toFloat() * radius

        val ny =
            cy + sin(angle).toFloat() * radius

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.parseColor("#FF2E63")

                strokeWidth = 6f
            }

        canvas.drawLine(
            cx,
            cy,
            nx,
            ny,
            paint
        )

        canvas.drawCircle(
            nx,
            ny,
            12f,
            paint
        )

        canvas.drawCircle(
            cx,
            cy,
            14f,
            paint
        )
    }

    private fun drawCenterText(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Int,
        state: NamazState
    ) {

        val big =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.WHITE

                textAlign = Paint.Align.CENTER

                textSize = width * 0.16f

                isFakeBoldText = true
            }

        val mid =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.WHITE

                textAlign = Paint.Align.CENTER

                textSize = width * 0.07f
            }

        val small =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.parseColor("#FFD54F")

                textAlign = Paint.Align.CENTER

                textSize = width * 0.045f
            }

        val time =
            java.text.SimpleDateFormat(
                "H:mm",
                java.util.Locale.getDefault()
            ).format(state.now)

        canvas.drawText(
            time,
            cx,
            cy - 20,
            big
        )

        canvas.drawText(
            state.currentSegment?.name ?: "",
            cx,
            cy + 60,
            mid
        )

        canvas.drawText(
            state.timerStr,
            cx,
            cy + 110,
            small
        )
    }

    private fun drawBottomGlassPanel(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        val rect =
            RectF(
                width * 0.06f,
                height * 0.72f,
                width * 0.94f,
                height * 0.92f
            )

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color =
                    Color.argb(
                        90,
                        20,
                        22,
                        50
                    )
            }

        canvas.drawRoundRect(
            rect,
            50f,
            50f,
            paint
        )

        val title =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.WHITE

                textAlign = Paint.Align.CENTER

                textSize = width * 0.05f

                isFakeBoldText = true
            }

        val value =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.parseColor("#FFD54F")

                textAlign = Paint.Align.CENTER

                textSize = width * 0.06f
            }

        canvas.drawText(
            "PLANETARY HOUR ACTIVE",
            width / 2f,
            height * 0.79f,
            title
        )

        canvas.drawText(
            state.currentSegment?.name ?: "",
            width / 2f,
            height * 0.86f,
            value
        )
    }
}
