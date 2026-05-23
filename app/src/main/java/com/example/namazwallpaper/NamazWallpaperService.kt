package com.example.namazwallpaper

import android.annotation.SuppressLint
import android.graphics.*
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.google.android.gms.location.LocationServices
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

class NamazWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = NamazEngine()

    inner class NamazEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        
        // FIXED 1: Renamed from 'isVisible' to avoid clashing with Android's system property
        private var engineVisible = false 

        // Initial Load Defaults (Hubballi)
        private var currentLat = 15.3647
        private var currentLng = 75.1240
        private var isGpsLocked = false

        // Night Vision Palette (Single Color)
        private val bgPaint = Paint().apply { color = Color.parseColor("#040714") }
        private val textPaint = Paint().apply {
            color = Color.parseColor("#FBBF24")
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 35f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        private val arcPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        // Fused Location for Background GPS
        private val fusedLocationClient by lazy {
            LocationServices.getFusedLocationProviderClient(this@NamazWallpaperService)
        }

        // The 60-FPS Drawing Loop
        private val drawRunner = object : Runnable {
            override fun run() {
                drawFrame()
                // FIXED 2: Appended 'L' to force Kotlin to treat the number as a Long
                if (engineVisible) handler.postDelayed(this, 1000L) 
            }
        }

        // The 15-Minute Background GPS Loop
        private val gpsRunner = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        currentLat = location.latitude
                        currentLng = location.longitude
                        isGpsLocked = true
                    }
                }
                // FIXED 3: Passed explicit Long (900,000 milliseconds) instead of Int math
                handler.postDelayed(this, 900000L) 
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.engineVisible = visible
            if (visible) {
                handler.post(drawRunner)
                handler.post(gpsRunner)
            } else {
                handler.removeCallbacks(drawRunner)
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    renderDashboard(canvas)
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun renderDashboard(canvas: Canvas) {
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)

            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            val cx = width / 2f
            val cy = height / 2f
            
            val radius = width * 0.40f 
            val innerRadius = radius * 0.50f
            val rectF = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            val cal = Calendar.getInstance()
            val currentDec = cal.get(Calendar.HOUR_OF_DAY) + 
                             (cal.get(Calendar.MINUTE) / 60f) + 
                             (cal.get(Calendar.SECOND) / 3600f)

            val hijriDate = getNativeHijriDate(currentDec)

            canvas.drawText(hijriDate, cx, cy - 60f, textPaint)
            
            val locationText = if (isGpsLocked) "GPS LIVE" else "HUBBALLI (DEFAULT)"
            textPaint.textSize = 24f
            textPaint.color = Color.parseColor("#A0AEC0")
            canvas.drawText(locationText, cx, cy + 80f, textPaint)

            val dFajr = 5.6f
            val dSunr = 6.9f
            
            arcPaint.color = Color.parseColor("#1B2A47")
            val fajrStartAngle = (dFajr / 24f) * 360f - 90f
            val fajrSweepAngle = ((dSunr - dFajr) / 24f) * 360f
            canvas.drawArc(rectF, fajrStartAngle, fajrSweepAngle, true, arcPaint)
            
            arcPaint.color = bgPaint.color
            canvas.drawCircle(cx, cy, innerRadius, arcPaint)

            val needleAngle = ((currentDec / 24f) * 360f - 90f) * (Math.PI / 180f)
            val nx = cx + cos(needleAngle).toFloat() * (innerRadius - 20f)
            val ny = cy + sin(needleAngle).toFloat() * (innerRadius - 20f)
            
            val needlePaint = Paint().apply {
                color = Color.parseColor("#FF3366")
                strokeWidth = 6f
                isAntiAlias = true
            }
            canvas.drawLine(cx, cy, nx, ny, needlePaint)
            canvas.drawCircle(nx, ny, 10f, needlePaint)
            
            val planets = getPlanetaryRulers(currentDec, dSunr)
            canvas.drawText("HOUR PLANET: ${planets[1]}", cx, cy + 250f, textPaint)
        }

        private fun getNativeHijriDate(currentDecTime: Float): String {
            var hijrahDate = HijrahDate.now()
            
            if (currentDecTime > 18.5f) {
                // FIXED 4: Used correct ChronoUnit addition method for HijrahDate class
                hijrahDate = hijrahDate.plus(1, ChronoUnit.DAYS)
            }
            
            val formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy 'AH'")
            return formatter.format(hijrahDate)
        }

        private fun getPlanetaryRulers(decTime: Float, sunriseDec: Float): Array<String> {
            val planets = arrayOf("Sun", "Venus", "Mercury", "Moon", "Saturn", "Jupiter", "Mars")
            val dayRulers = arrayOf(0, 3, 6, 2, 5, 1, 4) 
            
            val cal = Calendar.getInstance()
            var dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
            if (decTime < sunriseDec) dayOfWeek = (dayOfWeek + 6) % 7
            
            val rulerStartIndex = dayRulers[dayOfWeek]
            return arrayOf(planets[rulerStartIndex], planets[(rulerStartIndex + 1) % 7]) 
        }
    }
}
