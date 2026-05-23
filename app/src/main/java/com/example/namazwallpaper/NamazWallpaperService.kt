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
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

class NamazWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = NamazEngine()

    inner class NamazEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var isVisible = false
        
        // 1. Initial Load Defaults (Hubballi)
        private var currentLat = 15.3647
        private var currentLng = 75.1240
        private var isGpsLocked = false

        // Night Vision Palette (Single Color)
        private val bgPaint = Paint().apply { color = Color.parseColor("#040714") } // Deep Night Vision Blue/Black
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
        private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)

        // The 60-FPS Drawing Loop
        private val drawRunner = object : Runnable {
            override fun run() {
                drawFrame()
                if (isVisible) handler.postDelayed(this, 1000) // Update clock every 1 second
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
                // Check GPS every 15 minutes (900,000 ms) in the background
                handler.postDelayed(this, 15 * 60 * 1000)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                handler.post(drawRunner)
                handler.post(gpsRunner)
            } else {
                handler.removeCallbacks(drawRunner)
                // We leave gpsRunner running in the background depending on OS constraints
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
            // 1. Draw Night Vision Background
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)

            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            val cx = width / 2f
            val cy = height / 2f
            
            // Dashboard MUST fit the screen size in width
            val radius = width * 0.40f 
            val innerRadius = radius * 0.50f
            val rectF = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            // Current Time as Decimal (0 to 24)
            val cal = Calendar.getInstance()
            val currentDec = cal.get(Calendar.HOUR_OF_DAY) + 
                             (cal.get(Calendar.MINUTE) / 60f) + 
                             (cal.get(Calendar.SECOND) / 3600f)

            // Calculate active Hijri Date natively (No API required)
            val hijriDate = getNativeHijriDate(currentDec)

            // --- DRAW CENTER UI ---
            canvas.drawText(hijriDate, cx, cy - 60f, textPaint)
            
            val locationText = if (isGpsLocked) "GPS LIVE" else "HUBBALLI (DEFAULT)"
            textPaint.textSize = 24f
            textPaint.color = Color.parseColor("#A0AEC0")
            canvas.drawText(locationText, cx, cy + 80f, textPaint)

            // --- MOCK PRAYER TIMES FOR EXAMPLE (Replace with dynamic array map implementation) ---
            val dFajr = 5.6f
            val dSunr = 6.9f
            
            // Draw an Arc segment for Fajr
            arcPaint.color = Color.parseColor("#1B2A47") // Muted night vision segment
            val fajrStartAngle = (dFajr / 24f) * 360f - 90f
            val fajrSweepAngle = ((dSunr - dFajr) / 24f) * 360f
            canvas.drawArc(rectF, fajrStartAngle, fajrSweepAngle, true, arcPaint)
            
            // Draw center cutout to make it a donut
            arcPaint.color = bgPaint.color
            canvas.drawCircle(cx, cy, innerRadius, arcPaint)

            // --- DRAW LIVE NEEDLE ---
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
            
            // Calculate Day/Hour/Minute Planetary Ruler
            val planets = getPlanetaryRulers(currentDec, dSunr)
            canvas.drawText("HOUR PLANET: ${planets[1]}", cx, cy + 250f, textPaint)
        }

        // Native Hijri Calculation (No Internet Needed)
        private fun getNativeHijriDate(currentDecTime: Float): String {
            // Android 8.0+ native Islamic Calendar
            var hijrahDate = HijrahDate.now()
            
            // If it's past Maghrib (e.g. 18.5 decimal), add 1 day automatically
            if (currentDecTime > 18.5f) {
                hijrahDate = hijrahDate.plusDays(1)
            }
            
            val formatter = DateTimeFormatter.ofPattern("dd MMMM yyyy 'AH'")
            return formatter.format(hijrahDate)
        }

        // Chaldean Planetary Calculation
        private fun getPlanetaryRulers(decTime: Float, sunriseDec: Float): Array<String> {
            val planets = arrayOf("Sun", "Venus", "Mercury", "Moon", "Saturn", "Jupiter", "Mars")
            val dayRulers = arrayOf(0, 3, 6, 2, 5, 1, 4) // Sun to Sat
            
            val cal = Calendar.getInstance()
            var dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
            if (decTime < sunriseDec) dayOfWeek = (dayOfWeek + 6) % 7
            
            val rulerStartIndex = dayRulers[dayOfWeek]
            return arrayOf(planets[rulerStartIndex], planets[(rulerStartIndex + 1) % 7]) 
        }
    }
}