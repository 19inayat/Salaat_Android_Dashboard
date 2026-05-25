package com.optimizer.namaz

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

data class Segment(
    val name: String,
    val start: Double,
    val end: Double,
    val color: Int
)

data class NamazState(
    val now: Date,
    val currentDec: Double,
    val segments: List<Segment>,
    val currentSegment: Segment?,
    val timerStr: String
)

object DataEngine {

    @Volatile
    var state: NamazState? = null

    fun update() {

        val now = Date()

        val cal = Calendar.getInstance()

        val currentDec =
            cal.get(Calendar.HOUR_OF_DAY) +
                    (cal.get(Calendar.MINUTE) / 60.0) +
                    (cal.get(Calendar.SECOND) / 3600.0)

        val fajr = 5.0
        val sunrise = 6.2
        val dhuhr = 12.5
        val asr = 16.5
        val maghrib = 18.5
        val isha = 19.5

        val segments = listOf(

            Segment("FAJR", fajr, sunrise, android.graphics.Color.parseColor("#FFD54F")),
            Segment("ZUHR", dhuhr, asr, android.graphics.Color.parseColor("#40C4FF")),
            Segment("ASR", asr, maghrib, android.graphics.Color.parseColor("#FF8A65")),
            Segment("ISHA", isha, fajr + 24, android.graphics.Color.parseColor("#37474F"))
        )

        var checkTime = currentDec

        if (currentDec < fajr) {
            checkTime += 24.0
        }

        val currentSeg =
            segments.firstOrNull {
                checkTime >= it.start &&
                        checkTime < it.end
            }

        val remDec = (currentSeg?.end ?: 0.0) - checkTime

        val h = remDec.toInt()

        val m = ((remDec - h) * 60).toInt()

        val s = ((((remDec - h) * 60) - m) * 60).toInt()

        val timerStr = String.format("%02d:%02d:%02d", h, m, s)

        state = NamazState(
            now = now,
            currentDec = currentDec,
            segments = segments,
            currentSegment = currentSeg,
            timerStr = timerStr
        )
    }
}

object DashboardRenderer {

    fun draw(
        canvas: android.graphics.Canvas,
        width: Int,
        height: Int,
        state: NamazState
    ) {

        canvas.drawColor(android.graphics.Color.parseColor("#1D2036"))

        val timePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = android.graphics.Color.WHITE
                textSize = width * 0.12f
                textAlign = Paint.Align.CENTER
            }

        val timeStr =
            SimpleDateFormat("hh:mm:ss a", Locale.US).format(state.now)

        canvas.drawText(
            timeStr,
            width / 2f,
            height * 0.18f,
            timePaint
        )

        val cx = width / 2f
        val cy = height * 0.5f

        val radius = width * 0.33f

        val rect =
            RectF(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius
            )

        val arcPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                style = Paint.Style.STROKE
                strokeWidth = 50f
            }

        state.segments.forEach {

            val start =
                ((it.start / 24.0) * 360f - 90f).toFloat()

            val sweep =
                (((it.end - it.start) / 24.0) * 360f).toFloat()

            arcPaint.color = it.color

            canvas.drawArc(
                rect,
                start,
                sweep,
                false,
                arcPaint
            )
        }

        val centerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = android.graphics.Color.WHITE
                textSize = width * 0.06f
                textAlign = Paint.Align.CENTER
            }

        canvas.drawText(
            state.currentSegment?.name ?: "--",
            cx,
            cy,
            centerPaint
        )

        canvas.drawText(
            state.timerStr,
            cx,
            cy + 70f,
            centerPaint
        )

        val angle =
            ((state.currentDec / 24.0) * 360.0 - 90.0) *
                    (Math.PI / 180.0)

        val nx =
            cx + cos(angle).toFloat() * (radius - 20)

        val ny =
            cy + sin(angle).toFloat() * (radius - 20)

        val needle =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {

                color = android.graphics.Color.WHITE
                strokeWidth = 6f
            }

        canvas.drawLine(
            cx,
            cy,
            nx,
            ny,
            needle
        )
    }
}

class NamazWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return NamazEngine()
    }

    inner class NamazEngine : Engine() {

    private val handler =
        android.os.Handler(
            android.os.Looper.getMainLooper()
        )

    private var visible = false

    private val runnable = object : Runnable {

        override fun run() {

            drawFrame()

            if (visible) {

                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onVisibilityChanged(
        visible: Boolean
    ) {

        this.visible = visible

        if (visible) {

            handler.post(runnable)

        } else {

            handler.removeCallbacks(runnable)
        }
    }

    private fun drawFrame() {

        val canvas = surfaceHolder.lockCanvas()

        canvas?.let {

            DataEngine.update()

            DataEngine.state?.let { state ->

                DashboardRenderer.draw(
                    it,
                    it.width,
                    it.height,
                    state
                )
            }

            surfaceHolder.unlockCanvasAndPost(it)
        }
    }
}
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {}

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        setContent {

            MaterialTheme {

                var trigger by remember {
                    mutableStateOf(0)
                }

                LaunchedEffect(Unit) {

                    while (true) {

                        DataEngine.update()

                        delay(1000)

                        trigger++
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {

                    Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {

                        trigger

                        DataEngine.state?.let {

                            DashboardRenderer.draw(
                                drawContext.canvas.nativeCanvas,
                                size.width.toInt(),
                                size.height.toInt(),
                                it
                            )
                        }
                    }

                    Button(

                        onClick = {

                            val intent =
                                Intent(
                                    WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                                ).apply {

                                    putExtra(
                                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                        ComponentName(
                                            this@MainActivity,
                                            NamazWallpaperService::class.java
                                        )
                                    )
                                }

                            startActivity(intent)
                        },

                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp),

                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF353A55)
                        )

                    ) {

                        Text(
                            "SET AS LIVE WALLPAPER",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
