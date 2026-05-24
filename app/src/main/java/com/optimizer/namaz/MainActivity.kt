package com.optimizer.namaz

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.icu.util.IslamicCalendar
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

// --- ORIGINAL HTML COLOR GRADIENTS ---
val AppBg = Color(0xFF353A55)
val PanelBg = Color(0xFF282C44)
val GradientMap = mapOf(
    "Fajr" to listOf(Color(0xFFFF4E50), Color(0xFFF9D423)),
    "Tulu" to listOf(Color(0xFFF6D365), Color(0xFFFDA085)),
    "Ishraq" to listOf(Color(0xFFFCCB90), Color(0xFFD57EEB)),
    "Chasht" to listOf(Color(0xFFA18CD1), Color(0xFFFBC2EB)),
    "Zawal" to listOf(Color(0xFF84FAB0), Color(0xFF8FD3F4)),
    "Zuhr" to listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)),
    "Asr" to listOf(Color(0xFFFA709A), Color(0xFFFFB199)),
    "Maghrib" to listOf(Color(0xFFFF0844), Color(0xFF330867)),
    "Isha" to listOf(Color(0xFF09203F), Color(0xFF537895))
)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startLocationWorker(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        } else {
            startLocationWorker(this)
        }

        setContent {
            NamazDashboard()
        }
    }
}

// --- BACKGROUND GPS WORKER ---
class LocationWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val locManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
            ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        
        loc?.let {
            val prefs = applicationContext.getSharedPreferences("NamazPrefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat("LAT", it.latitude.toFloat()).putFloat("LNG", it.longitude.toFloat()).apply()
        }
        return Result.success()
    }
}

fun startLocationWorker(context: Context) {
    val workReq = PeriodicWorkRequestBuilder<LocationWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("LocTracker", ExistingPeriodicWorkPolicy.KEEP, workReq)
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun NamazDashboard() {
    var now by remember { mutableStateOf(Date()) }
    var currentTimeDec by remember { mutableStateOf(getDecTime(now)) }
    var timings by remember { mutableStateOf(getFallbackTimings()) }
    var hijriDate by remember { mutableStateOf("--") }

    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            currentTimeDec = getDecTime(now)
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        val cal = IslamicCalendar()
        val day = cal.get(IslamicCalendar.DAY_OF_MONTH)
        if (day in 29..30) {
            try {
                val dStr = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
                val res = URL("https://api.aladhan.com/v1/gToH?date=$dStr").readText()
                val json = JSONObject(res).getJSONObject("data").getJSONObject("hijri")
                hijriDate = "${json.getString("day")} ${json.getJSONObject("month").getString("en")}\n${json.getString("year")}"
            } catch (e: Exception) {
                hijriDate = "${day} ${cal.get(IslamicCalendar.MONTH)}\n${cal.get(IslamicCalendar.YEAR)}"
            }
        } else {
            hijriDate = "${day} ${cal.get(IslamicCalendar.MONTH)}\n${cal.get(IslamicCalendar.YEAR)}"
        }
    }

    val dFajr = timeStrToDec(timings["Fajr"]!!)
    val dSunr = timeStrToDec(timings["Sunrise"]!!)
    val dZuhr = timeStrToDec(timings["Dhuhr"]!!)
    val dAsr = timeStrToDec(timings["Asr"]!!)
    val dMagr = timeStrToDec(timings["Maghrib"]!!)
    val dIsha = timeStrToDec(timings["Isha"]!!)

    val dZawl = dFajr + (dMagr - dFajr) / 2
    val dIshr = dSunr + (20.0 / 60.0)
    val dChas = dSunr + (dZawl - dSunr) / 2

    val segments = listOf(
        Segment("Fajr", dFajr, dSunr),
        Segment("Tulu", dSunr, dIshr),
        Segment("Ishraq", dIshr, dChas),
        Segment("Chasht", dChas, dZawl),
        Segment("Zawal", dZawl, dZuhr),
        Segment("Zuhr", dZuhr, dAsr),
        Segment("Asr", dAsr, dMagr),
        Segment("Maghrib", dMagr, dIsha),
        Segment("Isha", dIsha, dFajr + 24)
    )

    var checkTime = currentTimeDec
    if (currentTimeDec < dFajr) checkTime += 24
    val currentSegment = segments.firstOrNull { checkTime >= it.start && checkTime < it.end }

    val remDec = (currentSegment?.end ?: 0.0) - checkTime
    val remH = remDec.toInt()
    val remM = ((remDec - remH) * 60).toInt()
    val remS = ((((remDec - remH) * 60) - remM) * 60).toInt()
    val timerStr = String.format("%02d:%02d:%02d", remH, remM, remS)

    val iftarTime = decToTimeStr(dAsr) 
    val sehriTime = decToTimeStr(dFajr - (5.0/60.0))

    val planets = getPlanetaryRulers(currentTimeDec, dSunr, dMagr)
    val degSunr = ((currentTimeDec - dSunr + 24) % 24) * 15
    val degMagr = ((currentTimeDec - dMagr + 24) % 24) * 15

    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier.fillMaxSize().background(AppBg).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        
        // HUGE TOP TIME
        Text(
            text = SimpleDateFormat("h:mm", Locale.US).format(now),
            color = Color.White,
            fontSize = 72.sp,
            fontWeight = FontWeight.Light
        )

        Spacer(modifier = Modifier.height(10.dp))

        // THE 24-HOUR CIRCULAR CLOCK
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                val innerRadius = radius * 0.45f
                val outerRadius = radius * 0.85f
                val strokeW = outerRadius - innerRadius

                // Draw Arcs
                segments.forEach { seg ->
                    val startAngle = (seg.start / 24f) * 360f - 90f
                    val sweepAngle = ((seg.end - seg.start) / 24f) * 360f
                    val colors = GradientMap[seg.name] ?: listOf(Color.Gray, Color.DarkGray)
                    
                    drawArc(
                        brush = Brush.sweepGradient(colors),
                        startAngle = startAngle.toFloat(),
                        sweepAngle = sweepAngle.toFloat(),
                        useCenter = false,
                        style = Stroke(width = strokeW),
                        size = Size(outerRadius * 2 - strokeW, outerRadius * 2 - strokeW),
                        topLeft = Offset(center.x - outerRadius + strokeW / 2, center.y - outerRadius + strokeW / 2)
                    )

                    // Draw Segment Text Radially
                    val midAngle = startAngle + sweepAngle / 2f
                    val r = innerRadius + strokeW / 2f
                    val tx = center.x + cos(Math.toRadians(midAngle)).toFloat() * r
                    val ty = center.y + sin(Math.toRadians(midAngle)).toFloat() * r

                    drawContext.canvas.save()
                    drawContext.canvas.translate(tx, ty)
                    val rotateAngle = if ((midAngle % 360 + 360) % 360 in 90f..270f) midAngle + 180f else midAngle
                    drawContext.canvas.rotate(rotateAngle.toFloat())
                    
                    val textLayout = textMeasurer.measure(seg.name.uppercase(), TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif))
                    drawText(textLayout, topLeft = Offset(-textLayout.size.width / 2f, -textLayout.size.height / 2f))
                    drawContext.canvas.restore()
                }

                // Tahajjud Marker (Last 1/3rd of Isha)
                val ishaSeg = segments.find { it.name == "Isha" }
                if (ishaSeg != null) {
                    val ishaDur = ishaSeg.end - ishaSeg.start
                    val tahajjudStart = ishaSeg.start + (2.0 / 3.0) * ishaDur
                    val tAngle = Math.toRadians((tahajjudStart / 24) * 360 - 90)
                    drawLine(
                        color = Color.White.copy(alpha = 0.6f),
                        start = Offset(center.x + cos(tAngle).toFloat() * innerRadius, center.y + sin(tAngle).toFloat() * innerRadius),
                        end = Offset(center.x + cos(tAngle).toFloat() * outerRadius, center.y + sin(tAngle).toFloat() * outerRadius),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                }

                // 24 Hour Outer Ticks
                for (i in 1..24) {
                    val angle = (i / 24f) * 360f - 90f
                    val rad = Math.toRadians(angle.toDouble())
                    val isMajor = i % 2 == 0
                    val tickStart = outerRadius + 5f
                    val tickEnd = tickStart + if (isMajor) 15f else 8f
                    
                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = Offset(center.x + cos(rad).toFloat() * tickStart, center.y + sin(rad).toFloat() * tickStart),
                        end = Offset(center.x + cos(rad).toFloat() * tickEnd, center.y + sin(rad).toFloat() * tickEnd),
                        strokeWidth = if (isMajor) 3f else 1.5f
                    )
                    
                    if (isMajor || i == 24) {
                        val textR = tickEnd + 15f
                        val num = if (i == 24) "24" else i.toString()
                        val tl = textMeasurer.measure(num, TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        drawText(
                            textLayoutResult = tl,
                            topLeft = Offset(center.x + cos(rad).toFloat() * textR - tl.size.width / 2f, center.y + sin(rad).toFloat() * textR - tl.size.height / 2f)
                        )
                    }
                }

                // Center Inner Dark Circle
                drawCircle(color = AppBg, radius = innerRadius, center = center)
                drawCircle(color = Color.White.copy(alpha = 0.1f), radius = innerRadius, center = center, style = Stroke(width = 2f))

                // The Needle
                val needleAngle = (currentTimeDec / 24) * 360 * (Math.PI / 180) - (Math.PI / 2)
                val nx = center.x + cos(needleAngle).toFloat() * (innerRadius - 5)
                val ny = center.y + sin(needleAngle).toFloat() * (innerRadius - 5)
                drawLine(color = Color.White, start = center, end = Offset(nx, ny), strokeWidth = 4f, cap = StrokeCap.Round)
                drawCircle(color = Color.White, radius = 6f, center = Offset(nx, ny))
                drawCircle(color = Color.Red, radius = 4f, center = Offset(nx, ny))
                drawCircle(color = Color.White, radius = 6f, center = center)
            }

            // CENTER TEXT CONTENT (Timer shifted here)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(SimpleDateFormat("EEE, dd MMM", Locale.US).format(now), color = Color.LightGray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(currentSegment?.name?.uppercase() ?: "--", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                Text("${decToTimeStr(currentSegment?.start ?: 0.0)} - ${decToTimeStr(currentSegment?.end ?: 0.0)}", color = Color(0xFFFFB199), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("REMAINING", color = Color.Gray, fontSize = 9.sp, letterSpacing = 2.sp)
                Text(timerStr, color = Color(0xFFFF4E50), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // BOTTOM DATA PANEL
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PanelBg,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = Color.White.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp)) {
                    Text("HUBBALLI (FALLBACK)", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), letterSpacing = 1.sp)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DataColumn("SEHRI", sehriTime, Color(0xFF84FAB0))
                    DataColumn("IFTAR", iftarTime, Color(0xFF84FAB0))
                    DataColumn("HIJRI", hijriDate, Color(0xFFF9D423))
                }

                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DataColumn("DAY\nPLANET", planets.first, Color(0xFFF9D423))
                    DataColumn("HOUR\nPLANET", planets.second, Color(0xFFF9D423))
                    DataColumn("MINUTE\nPLANET", planets.third, Color(0xFFFF4E50))
                    DataColumn("SUNSET\nARC", "${String.format("%.2f", degMagr)}°", Color(0xFFF9D423))
                    DataColumn("SUNRISE\nARC", "${String.format("%.2f", degSunr)}°", Color(0xFFF9D423))
                }
            }
        }
    }
}

@Composable
fun DataColumn(title: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.Gray, fontSize = 8.sp, letterSpacing = 1.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// --- MATH & LOGIC UTILITIES ---
data class Segment(val name: String, val start: Double, val end: Double)

val PLANETS = listOf("Sun", "Venus", "Mercury", "Moon", "Saturn", "Jupiter", "Mars")
val DAY_RULERS = listOf(0, 3, 6, 2, 5, 1, 4) 

fun getPlanetaryRulers(decTime: Double, dSunr: Double, dMagr: Double): Triple<String, String, String> {
    val d = Calendar.getInstance()
    var dayOfWeek = d.get(Calendar.DAY_OF_WEEK) - 1 
    if (decTime < dSunr) dayOfWeek = (dayOfWeek + 6) % 7 

    val rulerStartIndex = DAY_RULERS[dayOfWeek]
    var timeOffset = decTime - dSunr
    if (timeOffset < 0) timeOffset += 24.0

    val dayLength = dMagr - dSunr
    val nightLength = 24.0 - dayLength

    val currentHourIndex = if (decTime in dSunr..dMagr) {
        ((timeOffset / dayLength) * 12).toInt()
    } else {
        var offsetNight = decTime - dMagr
        if (offsetNight < 0) offsetNight += 24.0
        12 + ((offsetNight / nightLength) * 12).toInt()
    }

    val hourPlanetIdx = (rulerStartIndex + currentHourIndex) % 7
    val minFraction = (timeOffset * 60) % 60
    val minPlanetIdx = (hourPlanetIdx + minFraction.toInt()) % 7

    return Triple(PLANETS[rulerStartIndex].uppercase(), PLANETS[hourPlanetIdx].uppercase(), PLANETS[minPlanetIdx].uppercase())
}

fun getDecTime(d: Date): Double {
    val cal = Calendar.getInstance().apply { time = d }
    return cal.get(Calendar.HOUR_OF_DAY) + (cal.get(Calendar.MINUTE) / 60.0) + (cal.get(Calendar.SECOND) / 3600.0)
}

fun decToTimeStr(dec: Double): String {
    var d = dec
    if (d >= 24) d -= 24
    var h = d.toInt()
    var m = Math.round((d - h) * 60).toInt()
    if (m == 60) { h++; m = 0 }
    return String.format("%02d:%02d", h % 24, m)
}

fun timeStrToDec(str: String): Double {
    val parts = str.split(":")
    return parts[0].toDouble() + (parts[1].toDouble() / 60.0)
}

fun getFallbackTimings(): Map<String, String> {
    val cal = Calendar.getInstance()
    val key = String.format("%02d-%02d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    
    val rawJson = """{
        {"01-01":["05:38","06:55","12:37","16:39","18:15","19:31"],"01-02":["05:38","06:55","12:37","16:39","18:15","19:31"],"01-03":["05:39","06:55","12:38","16:40","18:16","19:32"],"01-04":["05:39","06:55","12:38","16:40","18:16","19:32"],"01-05":["05:40","06:56","12:39","16:41","18:17","19:33"],"01-06":["05:40","06:56","12:39","16:41","18:17","19:33"],"01-07":["05:40","06:57","12:40","16:42","18:18","19:34"],"01-08":["05:40","06:57","12:40","16:42","18:18","19:34"],"01-09":["05:41","06:57","12:41","16:43","18:20","19:35"],"01-10":["05:41","06:57","12:41","16:43","18:20","19:35"],"01-11":["05:42","06:58","12:42","16:45","18:21","19:37"],"01-12":["05:42","06:58","12:42","16:45","18:21","19:37"],"01-13":["05:42","06:58","12:42","16:46","18:22","19:38"],"01-14":["05:42","06:58","12:42","16:46","18:22","19:38"],"01-15":["05:43","06:58","12:43","16:47","18:23","19:39"],"01-16":["05:43","06:58","12:43","16:47","18:23","19:39"],"01-17":["05:43","06:58","12:44","16:48","18:24","19:40"],"01-18":["05:43","06:58","12:44","16:48","18:24","19:40"],"01-19":["05:44","06:59","12:44","16:49","18:25","19:40"],"01-20":["05:44","06:59","12:44","16:49","18:25","19:40"],"01-21":["05:44","06:59","12:45","16:50","18:26","19:41"],"01-22":["05:44","06:59","12:45","16:50","18:26","19:41"],"01-23":["05:44","06:59","12:46","16:51","18:28","19:42"],"01-24":["05:44","06:59","12:46","16:51","18:28","19:42"],"01-25":["05:44","06:59","12:46","16:53","18:29","19:43"],"01-26":["05:44","06:59","12:46","16:53","18:29","19:43"],"01-27":["05:44","06:59","12:47","16:54","18:30","19:44"],"01-28":["05:44","06:59","12:47","16:54","18:30","19:44"],"01-29":["05:44","06:58","12:47","16:55","18:31","19:45"],"01-30":["05:44","06:58","12:47","16:55","18:31","19:45"],"01-31":["05:44","06:58","12:47","16:55","18:32","19:46"],"02-01":["05:44","06:58","12:48","16:56","18:32","19:46"],"02-02":["05:44","06:58","12:48","16:56","18:32","19:46"],"02-03":["05:44","06:57","12:48","16:57","18:33","19:47"],"02-04":["05:44","06:57","12:48","16:57","18:33","19:47"],"02-05":["05:44","06:57","12:48","16:58","18:34","19:47"],"02-06":["05:44","06:57","12:48","16:58","18:34","19:47"],"02-07":["05:43","06:56","12:48","16:58","18:35","19:48"],"02-08":["05:43","06:56","12:48","16:58","18:35","19:48"],"02-09":["05:43","06:56","12:48","16:59","18:36","19:49"],"02-10":["05:43","06:56","12:48","16:59","18:36","19:49"],"02-11":["05:42","06:55","12:48","17:00","18:36","19:49"],"02-12":["05:42","06:55","12:48","17:00","18:36","19:49"],"02-13":["05:42","06:54","12:48","17:00","18:37","19:50"],"02-14":["05:42","06:54","12:48","17:00","18:37","19:50"],"02-15":["05:41","06:54","12:48","17:01","18:38","19:50"],"02-16":["05:41","06:54","12:48","17:01","18:38","19:50"],"02-17":["05:40","06:53","12:48","17:01","18:39","19:51"],"02-18":["05:40","06:53","12:48","17:01","18:39","19:51"],"02-19":["05:40","06:52","12:48","17:02","18:39","19:51"],"02-20":["05:40","06:52","12:48","17:02","18:39","19:51"],"02-21":["05:39","06:51","12:48","17:02","18:40","19:52"],"02-22":["05:39","06:51","12:48","17:02","18:40","19:52"],"02-23":["05:38","06:50","12:48","17:03","18:40","19:52"],"02-24":["05:38","06:50","12:48","17:03","18:40","19:52"],"02-25":["05:37","06:49","12:47","17:03","18:41","19:52"],"02-26":["05:37","06:49","12:47","17:03","18:41","19:52"],"02-27":["05:36","06:48","12:47","17:03","18:41","19:53"],"02-28":["05:36","06:48","12:47","17:03","18:41","19:53"],"02-29":["05:35","06:47","12:47","17:03","18:42","19:53"],"03-01":["05:35","06:46","12:47","17:03","18:42","19:53"],"03-02":["05:35","06:46","12:47","17:03","18:42","19:53"],"03-03":["05:34","06:45","12:46","17:04","18:42","19:54"],"03-04":["05:34","06:45","12:46","17:04","18:42","19:54"],"03-05":["05:33","06:44","12:46","17:04","18:43","19:54"],"03-06":["05:33","06:44","12:46","17:04","18:43","19:54"],"03-07":["05:31","06:43","12:45","17:04","18:43","19:54"],"03-08":["05:31","06:43","12:45","17:04","18:43","19:54"],"03-09":["05:30","06:41","12:45","17:04","18:43","19:55"],"03-10":["05:30","06:41","12:45","17:04","18:43","19:55"],"03-11":["05:29","06:40","12:44","17:04","18:44","19:55"],"03-12":["05:29","06:40","12:44","17:04","18:44","19:55"],"03-13":["05:27","06:39","12:44","17:04","18:44","19:55"],"03-14":["05:27","06:39","12:44","17:04","18:44","19:55"],"03-15":["05:26","06:37","12:43","17:03","18:44","19:55"],"03-16":["05:26","06:37","12:43","17:03","18:44","19:55"],"03-17":["05:25","06:36","12:43","17:03","18:45","19:56"],"03-18":["05:25","06:36","12:43","17:03","18:45","19:56"],"03-19":["05:23","06:34","12:42","17:03","18:45","19:56"],"03-20":["05:23","06:34","12:42","17:03","18:45","19:56"],"03-21":["05:22","06:33","12:42","17:03","18:45","19:56"],"03-22":["05:22","06:33","12:42","17:03","18:45","19:56"],"03-23":["05:20","06:31","12:41","17:03","18:45","19:57"],"03-24":["05:20","06:31","12:41","17:03","18:45","19:57"],"03-25":["05:19","06:30","12:40","17:02","18:46","19:57"],"03-26":["05:19","06:30","12:40","17:02","18:46","19:57"],"03-27":["05:17","06:29","12:40","17:02","18:46","19:57"],"03-28":["05:17","06:29","12:40","17:02","18:46","19:57"],"03-29":["05:16","06:27","12:39","17:02","18:46","19:58"],"03-30":["05:16","06:27","12:39","17:02","18:46","19:58"],"03-31":["05:14","06:26","12:38","17:01","18:46","19:58"],"04-01":["05:13","06:25","12:38","17:01","18:46","19:58"],"04-02":["05:13","06:25","12:38","17:01","18:46","19:58"],"04-03":["05:12","06:23","12:38","17:01","18:47","19:59"],"04-04":["05:12","06:23","12:38","17:01","18:47","19:59"],"04-05":["05:10","06:22","12:37","17:00","18:47","19:59"],"04-06":["05:10","06:22","12:37","17:00","18:47","19:59"],"04-07":["05:09","06:21","12:36","17:00","18:47","19:59"],"04-08":["05:09","06:21","12:36","17:00","18:47","19:59"],"04-09":["05:07","06:19","12:36","17:00","18:47","20:00"],"04-10":["05:07","06:19","12:36","17:00","18:47","20:00"],"04-11":["05:05","06:18","12:35","16:59","18:48","20:00"],"04-12":["05:05","06:18","12:35","16:59","18:48","20:00"],"04-13":["05:04","06:17","12:35","16:59","18:48","20:01"],"04-14":["05:04","06:17","12:35","16:59","18:48","20:01"],"04-15":["05:02","06:15","12:34","16:58","18:48","20:01"],"04-16":["05:02","06:15","12:34","16:58","18:48","20:01"],"04-17":["05:01","06:14","12:34","16:58","18:49","20:02"],"04-18":["05:01","06:14","12:34","16:58","18:49","20:02"],"04-19":["04:59","06:13","12:33","16:57","18:49","20:02"],"04-20":["04:59","06:13","12:33","16:57","18:49","20:02"],"04-21":["04:58","06:12","12:33","16:57","18:49","20:03"],"04-22":["04:58","06:12","12:33","16:57","18:49","20:03"],"04-23":["04:56","06:10","12:33","16:57","18:50","20:04"],"04-24":["04:56","06:10","12:33","16:57","18:50","20:04"],"04-25":["04:55","06:09","12:32","16:56","18:50","20:04"],"04-26":["04:55","06:09","12:32","16:56","18:50","20:04"],"04-27":["04:54","06:08","12:32","16:56","18:50","20:05"],"04-28":["04:54","06:08","12:32","16:56","18:50","20:05"],"04-29":["04:52","06:07","12:31","16:55","18:51","20:06"],"04-30":["04:52","06:07","12:31","16:55","18:51","20:06"],"05-01":["04:51","06:06","12:31","16:55","18:51","20:06"],"05-02":["04:51","06:06","12:31","16:55","18:51","20:06"],"05-03":["04:50","06:05","12:31","16:56","18:52","20:07"],"05-04":["04:50","06:05","12:31","16:56","18:52","20:07"],"05-05":["04:49","06:04","12:31","16:56","18:52","20:08"],"05-06":["04:49","06:04","12:31","16:56","18:52","20:08"],"05-07":["04:48","06:03","12:31","16:57","18:53","20:09"],"05-08":["04:48","06:03","12:31","16:57","18:53","20:09"],"05-09":["04:46","06:03","12:30","16:58","18:53","20:09"],"05-10":["04:46","06:03","12:30","16:58","18:53","20:09"],"05-11":["04:45","06:02","12:30","16:58","18:54","20:10"],"05-12":["04:45","06:02","12:30","16:58","18:54","20:10"],"05-13":["04:44","06:01","12:30","16:59","18:54","20:11"],"05-14":["04:44","06:01","12:30","16:59","18:54","20:11"],"05-15":["04:44","06:01","12:30","17:00","18:55","20:12"],"05-16":["04:44","06:01","12:30","17:00","18:55","20:12"],"05-17":["04:43","06:00","12:30","17:00","18:56","20:13"],"05-18":["04:43","06:00","12:30","17:00","18:56","20:13"],"05-19":["04:42","06:00","12:30","17:01","18:56","20:14"],"05-20":["04:42","06:00","12:30","17:01","18:56","20:14"],"05-21":["04:41","05:59","12:30","17:02","18:57","20:15"],"05-22":["04:41","05:59","12:30","17:02","18:57","20:15"],"05-23":["04:41","05:59","12:31","17:02","18:57","20:16"],"05-24":["04:41","05:59","12:31","17:02","18:57","20:16"],"05-25":["04:40","05:59","12:31","17:03","18:58","20:16"],"05-26":["04:40","05:59","12:31","17:03","18:58","20:16"],"05-27":["04:40","05:58","12:31","17:04","18:59","20:17"],"05-28":["04:40","05:58","12:31","17:04","18:59","20:17"],"05-29":["04:39","05:58","12:31","17:04","18:59","20:18"],"05-30":["04:39","05:58","12:31","17:04","18:59","20:18"],"05-31":["04:39","05:58","12:31","17:05","19:00","20:19"],"06-01":["04:39","05:58","12:32","17:05","19:00","20:19"],"06-02":["04:39","05:58","12:32","17:05","19:00","20:19"],"06-03":["04:39","05:58","12:32","17:06","19:01","20:20"],"06-04":["04:39","05:58","12:32","17:06","19:01","20:20"],"06-05":["04:38","05:58","12:32","17:07","19:02","20:21"],"06-06":["04:38","05:58","12:32","17:07","19:02","20:21"],"06-07":["04:38","05:58","12:33","17:07","19:02","20:22"],"06-08":["04:38","05:58","12:33","17:07","19:02","20:22"],"06-09":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-10":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-11":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-12":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-13":["04:38","05:59","12:34","17:09","19:04","20:24"],"06-14":["04:38","05:59","12:34","17:09","19:04","20:24"],"06-15":["04:39","05:59","12:34","17:10","19:04","20:25"],"06-16":["04:39","05:59","12:34","17:10","19:04","20:25"],"06-17":["04:39","05:59","12:35","17:10","19:05","20:25"],"06-18":["04:39","05:59","12:35","17:10","19:05","20:25"],"06-19":["04:39","06:00","12:35","17:11","19:06","20:26"],"06-20":["04:39","06:00","12:35","17:11","19:06","20:26"],"06-21":["04:40","06:00","12:35","17:11","19:06","20:26"],"06-22":["04:40","06:00","12:35","17:11","19:06","20:26"],"06-23":["04:40","06:00","12:36","17:12","19:06","20:27"],"06-24":["04:40","06:00","12:36","17:12","19:06","20:27"],"06-25":["04:41","06:01","12:36","17:12","19:07","20:27"],"06-26":["04:41","06:01","12:36","17:12","19:07","20:27"],"06-27":["04:41","06:01","12:37","17:12","19:07","20:27"],"06-28":["04:41","06:01","12:37","17:12","19:07","20:27"],"06-29":["04:42","06:02","12:37","17:13","19:07","20:28"],"06-30":["04:42","06:02","12:37","17:13","19:08","20:28"],"07-01":["04:42","06:03","12:38","17:13","19:08","20:28"],"07-02":["04:42","06:03","12:38","17:13","19:08","20:28"],"07-03":["04:43","06:03","12:38","17:13","19:08","20:28"],"07-04":["04:43","06:03","12:38","17:13","19:08","20:28"],"07-05":["04:44","06:04","12:38","17:13","19:08","20:28"],"07-06":["04:44","06:04","12:38","17:13","19:08","20:28"],"07-07":["04:45","06:04","12:39","17:13","19:08","20:28"],"07-08":["04:45","06:04","12:39","17:13","19:08","20:28"],"07-09":["04:45","06:05","12:39","17:13","19:08","20:28"],"07-10":["04:45","06:05","12:39","17:13","19:08","20:28"],"07-11":["04:46","06:06","12:39","17:13","19:08","20:27"],"07-12":["04:46","06:06","12:39","17:13","19:08","20:27"],"07-13":["04:47","06:06","12:40","17:13","19:08","20:27"],"07-14":["04:47","06:06","12:40","17:13","19:08","20:27"],"07-15":["04:48","06:07","12:40","17:13","19:08","20:27"],"07-16":["04:48","06:07","12:40","17:13","19:08","20:27"],"07-17":["04:49","06:07","12:40","17:13","19:08","20:26"],"07-18":["04:49","06:07","12:40","17:13","19:08","20:26"],"07-19":["04:50","06:08","12:40","17:12","19:07","20:26"],"07-20":["04:50","06:08","12:40","17:12","19:07","20:26"],"07-21":["04:50","06:09","12:40","17:12","19:07","20:25"],"07-22":["04:50","06:09","12:40","17:12","19:07","20:25"],"07-23":["04:51","06:09","12:40","17:12","19:07","20:25"],"07-24":["04:51","06:09","12:40","17:12","19:07","20:25"],"07-25":["04:52","06:10","12:40","17:11","19:06","20:24"],"07-26":["04:52","06:10","12:40","17:11","19:06","20:24"],"07-27":["04:53","06:10","12:40","17:10","19:06","20:23"],"07-28":["04:53","06:10","12:40","17:10","19:06","20:23"],"07-29":["04:54","06:11","12:40","17:10","19:05","20:22"],"07-30":["04:54","06:11","12:40","17:10","19:05","20:22"],"07-31":["04:55","06:11","12:40","17:09","19:04","20:21"],"08-01":["04:55","06:12","12:40","17:09","19:04","20:21"],"08-02":["04:55","06:12","12:40","17:09","19:04","20:21"],"08-03":["04:56","06:12","12:40","17:08","19:03","20:20"],"08-04":["04:56","06:12","12:40","17:08","19:03","20:20"],"08-05":["04:57","06:13","12:40","17:07","19:02","20:19"],"08-06":["04:57","06:13","12:40","17:07","19:02","20:19"],"08-07":["04:57","06:13","12:40","17:06","19:02","20:17"],"08-08":["04:57","06:13","12:40","17:06","19:02","20:17"],"08-09":["04:58","06:13","12:40","17:05","19:01","20:16"],"08-10":["04:58","06:13","12:40","17:05","19:01","20:16"],"08-11":["04:59","06:14","12:39","17:04","19:00","20:15"],"08-12":["04:59","06:14","12:39","17:04","19:00","20:15"],"08-13":["04:59","06:14","12:39","17:03","18:59","20:14"],"08-14":["04:59","06:14","12:39","17:03","18:59","20:14"],"08-15":["05:00","06:15","12:39","17:03","18:58","20:12"],"08-16":["05:00","06:15","12:39","17:03","18:58","20:12"],"08-17":["05:01","06:15","12:38","17:02","18:57","20:11"],"08-18":["05:01","06:15","12:38","17:02","18:57","20:11"],"08-19":["05:01","06:15","12:38","17:02","18:55","20:09"],"08-20":["05:01","06:15","12:38","17:02","18:55","20:09"],"08-21":["05:02","06:16","12:37","17:01","18:54","20:08"],"08-22":["05:02","06:16","12:37","17:01","18:54","20:08"],"08-23":["05:02","06:16","12:37","17:01","18:53","20:06"],"08-24":["05:02","06:16","12:37","17:01","18:53","20:06"],"08-25":["05:03","06:16","12:36","17:00","18:52","20:05"],"08-26":["05:03","06:16","12:36","17:00","18:52","20:05"],"08-27":["05:03","06:16","12:36","17:00","18:50","20:03"],"08-28":["05:03","06:16","12:36","17:00","18:50","20:03"],"08-29":["05:04","06:17","12:35","16:59","18:49","20:02"],"08-30":["05:04","06:17","12:35","16:59","18:49","20:02"],"08-31":["05:04","06:17","12:35","16:58","18:48","20:00"],"09-01":["05:04","06:17","12:34","16:58","18:47","19:59"],"09-02":["05:04","06:17","12:34","16:58","18:47","19:59"],"09-03":["05:05","06:17","12:34","16:57","18:45","19:58"],"09-04":["05:05","06:17","12:34","16:57","18:45","19:58"],"09-05":["05:05","06:17","12:33","16:57","18:44","19:56"],"09-06":["05:05","06:17","12:33","16:57","18:44","19:56"],"09-07":["05:05","06:17","12:32","16:56","18:42","19:54"],"09-08":["05:05","06:17","12:32","16:56","18:42","19:54"],"09-09":["05:06","06:17","12:32","16:55","18:41","19:53"],"09-10":["05:06","06:17","12:32","16:55","18:41","19:53"],"09-11":["05:06","06:18","12:31","16:54","18:39","19:51"],"09-12":["05:06","06:18","12:31","16:54","18:39","19:51"],"09-13":["05:06","06:18","12:30","16:53","18:38","19:49"],"09-14":["05:06","06:18","12:30","16:53","18:38","19:49"],"09-15":["05:06","06:18","12:30","16:52","18:36","19:48"],"09-16":["05:06","06:18","12:30","16:52","18:36","19:48"],"09-17":["05:07","06:18","12:29","16:51","18:35","19:46"],"09-18":["05:07","06:18","12:29","16:51","18:35","19:46"],"09-19":["05:07","06:18","12:28","16:50","18:33","19:45"],"09-20":["05:07","06:18","12:28","16:50","18:33","19:45"],"09-21":["05:07","06:18","12:27","16:49","18:32","19:43"],"09-22":["05:07","06:18","12:27","16:49","18:32","19:43"],"09-23":["05:07","06:18","12:27","16:48","18:30","19:41"],"09-24":["05:07","06:18","12:27","16:48","18:30","19:41"],"09-25":["05:07","06:18","12:26","16:47","18:29","19:40"],"09-26":["05:07","06:18","12:26","16:47","18:29","19:40"],"09-27":["05:07","06:19","12:25","16:46","18:27","19:38"],"09-28":["05:07","06:19","12:25","16:46","18:27","19:38"],"09-29":["05:08","06:19","12:25","16:45","18:26","19:37"],"09-30":["05:08","06:19","12:24","16:44","18:25","19:36"],"10-01":["05:08","06:19","12:24","16:44","18:24","19:35"],"10-02":["05:08","06:19","12:24","16:44","18:24","19:35"],"10-03":["05:08","06:19","12:23","16:43","18:23","19:34"],"10-04":["05:08","06:19","12:23","16:43","18:23","19:34"],"10-05":["05:08","06:19","12:23","16:41","18:21","19:32"],"10-06":["05:08","06:19","12:23","16:41","18:21","19:32"],"10-07":["05:08","06:19","12:22","16:40","18:20","19:31"],"10-08":["05:08","06:19","12:22","16:40","18:20","19:31"],"10-09":["05:08","06:20","12:21","16:39","18:18","19:30"],"10-10":["05:08","06:20","12:21","16:39","18:18","19:30"],"10-11":["05:09","06:20","12:20","16:38","18:17","19:29"],"10-12":["05:09","06:20","12:20","16:38","18:17","19:29"],"10-13":["05:09","06:20","12:20","16:37","18:16","19:27"],"10-14":["05:09","06:20","12:20","16:37","18:16","19:27"],"10-15":["05:09","06:21","12:20","16:36","18:14","19:26"],"10-16":["05:09","06:21","12:20","16:36","18:14","19:26"],"10-17":["05:09","06:21","12:20","16:35","18:13","19:25"],"10-18":["05:09","06:21","12:20","16:35","18:13","19:25"],"10-19":["05:10","06:21","12:19","16:34","18:12","19:24"],"10-20":["05:10","06:21","12:19","16:34","18:12","19:24"],"10-21":["05:10","06:22","12:19","16:33","18:11","19:23"],"10-22":["05:10","06:22","12:19","16:33","18:11","19:23"],"10-23":["05:10","06:22","12:18","16:32","18:10","19:22"],"10-24":["05:10","06:22","12:18","16:32","18:10","19:22"],"10-25":["05:11","06:23","12:18","16:32","18:09","19:21"],"10-26":["05:11","06:23","12:18","16:32","18:09","19:21"],"10-27":["05:11","06:23","12:18","16:31","18:08","19:20"],"10-28":["05:11","06:23","12:18","16:31","18:08","19:20"],"10-29":["05:11","06:24","12:18","16:30","18:07","19:19"],"10-30":["05:11","06:24","12:18","16:30","18:07","19:19"],"10-31":["05:12","06:24","12:18","16:29","18:06","19:19"],"11-01":["05:12","06:25","12:18","16:29","18:05","19:18"],"11-02":["05:12","06:25","12:18","16:29","18:05","19:18"],"11-03":["05:12","06:25","12:18","16:28","18:05","19:18"],"11-04":["05:12","06:25","12:18","16:28","18:05","19:18"],"11-05":["05:13","06:26","12:18","16:28","18:04","19:17"],"11-06":["05:13","06:26","12:18","16:28","18:04","19:17"],"11-07":["05:14","06:27","12:18","16:27","18:03","19:17"],"11-08":["05:14","06:27","12:18","16:27","18:03","19:17"],"11-09":["05:14","06:28","12:18","16:27","18:03","19:16"],"11-10":["05:14","06:28","12:18","16:27","18:03","19:16"],"11-11":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-12":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-13":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-14":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-15":["05:16","06:30","12:18","16:25","18:02","19:16"],"11-16":["05:16","06:30","12:18","16:25","18:02","19:16"],"11-17":["05:17","06:31","12:19","16:25","18:01","19:16"],"11-18":["05:17","06:31","12:19","16:25","18:01","19:16"],"11-19":["05:18","06:32","12:19","16:25","18:01","19:16"],"11-20":["05:18","06:32","12:19","16:25","18:01","19:16"],"11-21":["05:18","06:33","12:20","16:25","18:01","19:16"],"11-22":["05:18","06:33","12:20","16:25","18:01","19:16"],"11-23":["05:19","06:34","12:20","16:25","18:01","19:16"],"11-24":["05:19","06:34","12:20","16:25","18:01","19:16"],"11-25":["05:20","06:35","12:21","16:25","18:01","19:16"],"11-26":["05:20","06:35","12:21","16:25","18:01","19:16"],"11-27":["05:21","06:36","12:21","16:25","18:01","19:17"],"11-28":["05:21","06:36","12:21","16:25","18:01","19:17"],"11-29":["05:22","06:37","12:22","16:25","18:02","19:17"],"11-30":["05:22","06:38","12:22","16:26","18:02","19:17"],"12-01":["05:23","06:39","12:23","16:26","18:02","19:18"],"12-02":["05:23","06:39","12:23","16:26","18:02","19:18"],"12-03":["05:24","06:40","12:23","16:26","18:02","19:18"],"12-04":["05:24","06:40","12:23","16:26","18:02","19:18"],"12-05":["05:25","06:41","12:24","16:27","18:03","19:19"],"12-06":["05:25","06:41","12:24","16:27","18:03","19:19"],"12-07":["05:26","06:42","12:25","16:27","18:03","19:19"],"12-08":["05:26","06:42","12:25","16:27","18:03","19:19"],"12-09":["05:27","06:43","12:26","16:28","18:04","19:20"],"12-10":["05:27","06:43","12:26","16:28","18:04","19:20"],"12-11":["05:28","06:44","12:27","16:28","18:04","19:21"],"12-12":["05:28","06:44","12:27","16:28","18:04","19:21"],"12-13":["05:29","06:45","12:28","16:29","18:05","19:22"],"12-14":["05:29","06:45","12:28","16:29","18:05","19:22"],"12-15":["05:30","06:46","12:29","16:30","18:06","19:23"],"12-16":["05:30","06:46","12:29","16:30","18:06","19:23"],"12-17":["05:31","06:47","12:30","16:31","18:07","19:24"],"12-18":["05:31","06:47","12:30","16:31","18:07","19:24"],"12-19":["05:32","06:49","12:31","16:32","18:08","19:24"],"12-20":["05:32","06:49","12:31","16:32","18:08","19:24"],"12-21":["05:33","06:50","12:32","16:33","18:09","19:25"],"12-22":["05:33","06:50","12:32","16:33","18:09","19:25"],"12-23":["05:34","06:51","12:33","16:34","18:10","19:26"],"12-24":["05:34","06:51","12:33","16:34","18:10","19:26"],"12-25":["05:35","06:51","12:34","16:35","18:11","19:27"],"12-26":["05:35","06:51","12:34","16:35","18:11","19:27"],"12-27":["05:36","06:52","12:35","16:36","18:12","19:28"],"12-28":["05:36","06:52","12:35","16:36","18:12","19:28"],"12-29":["05:37","06:53","12:36","16:37","18:13","19:30"],"12-30":["05:37","06:53","12:36","16:37","18:13","19:30"],"12-31":["05:38","06:54","12:37","16:38","18:14","19:31"]
}"""
    return try {
        val json = JSONObject(rawJson)
        val arr = json.optJSONArray(key) ?: json.getJSONArray("01-01")
        mapOf("Fajr" to arr.getString(0), "Sunrise" to arr.getString(1), "Dhuhr" to arr.getString(2), "Asr" to arr.getString(3), "Maghrib" to arr.getString(4), "Isha" to arr.getString(5))
    } catch (e: Exception) {
        mapOf("Fajr" to "05:00", "Sunrise" to "06:15", "Dhuhr" to "12:30", "Asr" to "16:45", "Maghrib" to "18:30", "Isha" to "19:45")
    }
}
    
    // Condensed JSON string. Paste your full hubliData JSON array inside this raw string block.
    val rawJson = """{
{"01-01":["05:38","06:55","12:37","16:39","18:15","19:31"],"01-02":["05:38","06:55","12:37","16:39","18:15","19:31"],"01-03":["05:39","06:55","12:38","16:40","18:16","19:32"],"01-04":["05:39","06:55","12:38","16:40","18:16","19:32"],"01-05":["05:40","06:56","12:39","16:41","18:17","19:33"],"01-06":["05:40","06:56","12:39","16:41","18:17","19:33"],"01-07":["05:40","06:57","12:40","16:42","18:18","19:34"],"01-08":["05:40","06:57","12:40","16:42","18:18","19:34"],"01-09":["05:41","06:57","12:41","16:43","18:20","19:35"],"01-10":["05:41","06:57","12:41","16:43","18:20","19:35"],"01-11":["05:42","06:58","12:42","16:45","18:21","19:37"],"01-12":["05:42","06:58","12:42","16:45","18:21","19:37"],"01-13":["05:42","06:58","12:42","16:46","18:22","19:38"],"01-14":["05:42","06:58","12:42","16:46","18:22","19:38"],"01-15":["05:43","06:58","12:43","16:47","18:23","19:39"],"01-16":["05:43","06:58","12:43","16:47","18:23","19:39"],"01-17":["05:43","06:58","12:44","16:48","18:24","19:40"],"01-18":["05:43","06:58","12:44","16:48","18:24","19:40"],"01-19":["05:44","06:59","12:44","16:49","18:25","19:40"],"01-20":["05:44","06:59","12:44","16:49","18:25","19:40"],"01-21":["05:44","06:59","12:45","16:50","18:26","19:41"],"01-22":["05:44","06:59","12:45","16:50","18:26","19:41"],"01-23":["05:44","06:59","12:46","16:51","18:28","19:42"],"01-24":["05:44","06:59","12:46","16:51","18:28","19:42"],"01-25":["05:44","06:59","12:46","16:53","18:29","19:43"],"01-26":["05:44","06:59","12:46","16:53","18:29","19:43"],"01-27":["05:44","06:59","12:47","16:54","18:30","19:44"],"01-28":["05:44","06:59","12:47","16:54","18:30","19:44"],"01-29":["05:44","06:58","12:47","16:55","18:31","19:45"],"01-30":["05:44","06:58","12:47","16:55","18:31","19:45"],"01-31":["05:44","06:58","12:47","16:55","18:32","19:46"],"02-01":["05:44","06:58","12:48","16:56","18:32","19:46"],"02-02":["05:44","06:58","12:48","16:56","18:32","19:46"],"02-03":["05:44","06:57","12:48","16:57","18:33","19:47"],"02-04":["05:44","06:57","12:48","16:57","18:33","19:47"],"02-05":["05:44","06:57","12:48","16:58","18:34","19:47"],"02-06":["05:44","06:57","12:48","16:58","18:34","19:47"],"02-07":["05:43","06:56","12:48","16:58","18:35","19:48"],"02-08":["05:43","06:56","12:48","16:58","18:35","19:48"],"02-09":["05:43","06:56","12:48","16:59","18:36","19:49"],"02-10":["05:43","06:56","12:48","16:59","18:36","19:49"],"02-11":["05:42","06:55","12:48","17:00","18:36","19:49"],"02-12":["05:42","06:55","12:48","17:00","18:36","19:49"],"02-13":["05:42","06:54","12:48","17:00","18:37","19:50"],"02-14":["05:42","06:54","12:48","17:00","18:37","19:50"],"02-15":["05:41","06:54","12:48","17:01","18:38","19:50"],"02-16":["05:41","06:54","12:48","17:01","18:38","19:50"],"02-17":["05:40","06:53","12:48","17:01","18:39","19:51"],"02-18":["05:40","06:53","12:48","17:01","18:39","19:51"],"02-19":["05:40","06:52","12:48","17:02","18:39","19:51"],"02-20":["05:40","06:52","12:48","17:02","18:39","19:51"],"02-21":["05:39","06:51","12:48","17:02","18:40","19:52"],"02-22":["05:39","06:51","12:48","17:02","18:40","19:52"],"02-23":["05:38","06:50","12:48","17:03","18:40","19:52"],"02-24":["05:38","06:50","12:48","17:03","18:40","19:52"],"02-25":["05:37","06:49","12:47","17:03","18:41","19:52"],"02-26":["05:37","06:49","12:47","17:03","18:41","19:52"],"02-27":["05:36","06:48","12:47","17:03","18:41","19:53"],"02-28":["05:36","06:48","12:47","17:03","18:41","19:53"],"02-29":["05:35","06:47","12:47","17:03","18:42","19:53"],"03-01":["05:35","06:46","12:47","17:03","18:42","19:53"],"03-02":["05:35","06:46","12:47","17:03","18:42","19:53"],"03-03":["05:34","06:45","12:46","17:04","18:42","19:54"],"03-04":["05:34","06:45","12:46","17:04","18:42","19:54"],"03-05":["05:33","06:44","12:46","17:04","18:43","19:54"],"03-06":["05:33","06:44","12:46","17:04","18:43","19:54"],"03-07":["05:31","06:43","12:45","17:04","18:43","19:54"],"03-08":["05:31","06:43","12:45","17:04","18:43","19:54"],"03-09":["05:30","06:41","12:45","17:04","18:43","19:55"],"03-10":["05:30","06:41","12:45","17:04","18:43","19:55"],"03-11":["05:29","06:40","12:44","17:04","18:44","19:55"],"03-12":["05:29","06:40","12:44","17:04","18:44","19:55"],"03-13":["05:27","06:39","12:44","17:04","18:44","19:55"],"03-14":["05:27","06:39","12:44","17:04","18:44","19:55"],"03-15":["05:26","06:37","12:43","17:03","18:44","19:55"],"03-16":["05:26","06:37","12:43","17:03","18:44","19:55"],"03-17":["05:25","06:36","12:43","17:03","18:45","19:56"],"03-18":["05:25","06:36","12:43","17:03","18:45","19:56"],"03-19":["05:23","06:34","12:42","17:03","18:45","19:56"],"03-20":["05:23","06:34","12:42","17:03","18:45","19:56"],"03-21":["05:22","06:33","12:42","17:03","18:45","19:56"],"03-22":["05:22","06:33","12:42","17:03","18:45","19:56"],"03-23":["05:20","06:31","12:41","17:03","18:45","19:57"],"03-24":["05:20","06:31","12:41","17:03","18:45","19:57"],"03-25":["05:19","06:30","12:40","17:02","18:46","19:57"],"03-26":["05:19","06:30","12:40","17:02","18:46","19:57"],"03-27":["05:17","06:29","12:40","17:02","18:46","19:57"],"03-28":["05:17","06:29","12:40","17:02","18:46","19:57"],"03-29":["05:16","06:27","12:39","17:02","18:46","19:58"],"03-30":["05:16","06:27","12:39","17:02","18:46","19:58"],"03-31":["05:14","06:26","12:38","17:01","18:46","19:58"],"04-01":["05:13","06:25","12:38","17:01","18:46","19:58"],"04-02":["05:13","06:25","12:38","17:01","18:46","19:58"],"04-03":["05:12","06:23","12:38","17:01","18:47","19:59"],"04-04":["05:12","06:23","12:38","17:01","18:47","19:59"],"04-05":["05:10","06:22","12:37","17:00","18:47","19:59"],"04-06":["05:10","06:22","12:37","17:00","18:47","19:59"],"04-07":["05:09","06:21","12:36","17:00","18:47","19:59"],"04-08":["05:09","06:21","12:36","17:00","18:47","19:59"],"04-09":["05:07","06:19","12:36","17:00","18:47","20:00"],"04-10":["05:07","06:19","12:36","17:00","18:47","20:00"],"04-11":["05:05","06:18","12:35","16:59","18:48","20:00"],"04-12":["05:05","06:18","12:35","16:59","18:48","20:00"],"04-13":["05:04","06:17","12:35","16:59","18:48","20:01"],"04-14":["05:04","06:17","12:35","16:59","18:48","20:01"],"04-15":["05:02","06:15","12:34","16:58","18:48","20:01"],"04-16":["05:02","06:15","12:34","16:58","18:48","20:01"],"04-17":["05:01","06:14","12:34","16:58","18:49","20:02"],"04-18":["05:01","06:14","12:34","16:58","18:49","20:02"],"04-19":["04:59","06:13","12:33","16:57","18:49","20:02"],"04-20":["04:59","06:13","12:33","16:57","18:49","20:02"],"04-21":["04:58","06:12","12:33","16:57","18:49","20:03"],"04-22":["04:58","06:12","12:33","16:57","18:49","20:03"],"04-23":["04:56","06:10","12:33","16:57","18:50","20:04"],"04-24":["04:56","06:10","12:33","16:57","18:50","20:04"],"04-25":["04:55","06:09","12:32","16:56","18:50","20:04"],"04-26":["04:55","06:09","12:32","16:56","18:50","20:04"],"04-27":["04:54","06:08","12:32","16:56","18:50","20:05"],"04-28":["04:54","06:08","12:32","16:56","18:50","20:05"],"04-29":["04:52","06:07","12:31","16:55","18:51","20:06"],"04-30":["04:52","06:07","12:31","16:55","18:51","20:06"],"05-01":["04:51","06:06","12:31","16:55","18:51","20:06"],"05-02":["04:51","06:06","12:31","16:55","18:51","20:06"],"05-03":["04:50","06:05","12:31","16:56","18:52","20:07"],"05-04":["04:50","06:05","12:31","16:56","18:52","20:07"],"05-05":["04:49","06:04","12:31","16:56","18:52","20:08"],"05-06":["04:49","06:04","12:31","16:56","18:52","20:08"],"05-07":["04:48","06:03","12:31","16:57","18:53","20:09"],"05-08":["04:48","06:03","12:31","16:57","18:53","20:09"],"05-09":["04:46","06:03","12:30","16:58","18:53","20:09"],"05-10":["04:46","06:03","12:30","16:58","18:53","20:09"],"05-11":["04:45","06:02","12:30","16:58","18:54","20:10"],"05-12":["04:45","06:02","12:30","16:58","18:54","20:10"],"05-13":["04:44","06:01","12:30","16:59","18:54","20:11"],"05-14":["04:44","06:01","12:30","16:59","18:54","20:11"],"05-15":["04:44","06:01","12:30","17:00","18:55","20:12"],"05-16":["04:44","06:01","12:30","17:00","18:55","20:12"],"05-17":["04:43","06:00","12:30","17:00","18:56","20:13"],"05-18":["04:43","06:00","12:30","17:00","18:56","20:13"],"05-19":["04:42","06:00","12:30","17:01","18:56","20:14"],"05-20":["04:42","06:00","12:30","17:01","18:56","20:14"],"05-21":["04:41","05:59","12:30","17:02","18:57","20:15"],"05-22":["04:41","05:59","12:30","17:02","18:57","20:15"],"05-23":["04:41","05:59","12:31","17:02","18:57","20:16"],"05-24":["04:41","05:59","12:31","17:02","18:57","20:16"],"05-25":["04:40","05:59","12:31","17:03","18:58","20:16"],"05-26":["04:40","05:59","12:31","17:03","18:58","20:16"],"05-27":["04:40","05:58","12:31","17:04","18:59","20:17"],"05-28":["04:40","05:58","12:31","17:04","18:59","20:17"],"05-29":["04:39","05:58","12:31","17:04","18:59","20:18"],"05-30":["04:39","05:58","12:31","17:04","18:59","20:18"],"05-31":["04:39","05:58","12:31","17:05","19:00","20:19"],"06-01":["04:39","05:58","12:32","17:05","19:00","20:19"],"06-02":["04:39","05:58","12:32","17:05","19:00","20:19"],"06-03":["04:39","05:58","12:32","17:06","19:01","20:20"],"06-04":["04:39","05:58","12:32","17:06","19:01","20:20"],"06-05":["04:38","05:58","12:32","17:07","19:02","20:21"],"06-06":["04:38","05:58","12:32","17:07","19:02","20:21"],"06-07":["04:38","05:58","12:33","17:07","19:02","20:22"],"06-08":["04:38","05:58","12:33","17:07","19:02","20:22"],"06-09":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-10":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-11":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-12":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-13":["04:38","05:59","12:34","17:09","19:04","20:24"],"06-14":["04:38","05:59","12:34","17:09","19:04","20:24"],"06-15":["04:39","05:59","12:34","17:10","19:04","20:25"],"06-16":["04:39","05:59","12:34","17:10","19:04","20:25"],"06-17":["04:39","05:59","12:35","17:10","19:05","20:25"],"06-18":["04:39","05:59","12:35","17:10","19:05","20:25"],"06-19":["04:39","06:00","12:35","17:11","19:06","20:26"],"06-20":["04:39","06:00","12:35","17:11","19:06","20:26"],"06-21":["04:40","06:00","12:35","17:11","19:06","20:26"],"06-22":["04:40","06:00","12:35","17:11","19:06","20:26"],"06-23":["04:40","06:00","12:36","17:12","19:06","20:27"],"06-24":["04:40","06:00","12:36","17:12","19:06","20:27"],"06-25":["04:41","06:01","12:36","17:12","19:07","20:27"],"06-26":["04:41","06:01","12:36","17:12","19:07","20:27"],"06-27":["04:41","06:01","12:37","17:12","19:07","20:27"],"06-28":["04:41","06:01","12:37","17:12","19:07","20:27"],"06-29":["04:42","06:02","12:37","17:13","19:07","20:28"],"06-30":["04:42","06:02","12:37","17:13","19:08","20:28"],"07-01":["04:42","06:03","12:38","17:13","19:08","20:28"],"07-02":["04:42","06:03","12:38","17:13","19:08","20:28"],"07-03":["04:43","06:03","12:38","17:13","19:08","20:28"],"07-04":["04:43","06:03","12:38","17:13","19:08","20:28"],"07-05":["04:44","06:04","12:38","17:13","19:08","20:28"],"07-06":["04:44","06:04","12:38","17:13","19:08","20:28"],"07-07":["04:45","06:04","12:39","17:13","19:08","20:28"],"07-08":["04:45","06:04","12:39","17:13","19:08","20:28"],"07-09":["04:45","06:05","12:39","17:13","19:08","20:28"],"07-10":["04:45","06:05","12:39","17:13","19:08","20:28"],"07-11":["04:46","06:06","12:39","17:13","19:08","20:27"],"07-12":["04:46","06:06","12:39","17:13","19:08","20:27"],"07-13":["04:47","06:06","12:40","17:13","19:08","20:27"],"07-14":["04:47","06:06","12:40","17:13","19:08","20:27"],"07-15":["04:48","06:07","12:40","17:13","19:08","20:27"],"07-16":["04:48","06:07","12:40","17:13","19:08","20:27"],"07-17":["04:49","06:07","12:40","17:13","19:08","20:26"],"07-18":["04:49","06:07","12:40","17:13","19:08","20:26"],"07-19":["04:50","06:08","12:40","17:12","19:07","20:26"],"07-20":["04:50","06:08","12:40","17:12","19:07","20:26"],"07-21":["04:50","06:09","12:40","17:12","19:07","20:25"],"07-22":["04:50","06:09","12:40","17:12","19:07","20:25"],"07-23":["04:51","06:09","12:40","17:12","19:07","20:25"],"07-24":["04:51","06:09","12:40","17:12","19:07","20:25"],"07-25":["04:52","06:10","12:40","17:11","19:06","20:24"],"07-26":["04:52","06:10","12:40","17:11","19:06","20:24"],"07-27":["04:53","06:10","12:40","17:10","19:06","20:23"],"07-28":["04:53","06:10","12:40","17:10","19:06","20:23"],"07-29":["04:54","06:11","12:40","17:10","19:05","20:22"],"07-30":["04:54","06:11","12:40","17:10","19:05","20:22"],"07-31":["04:55","06:11","12:40","17:09","19:04","20:21"],"08-01":["04:55","06:12","12:40","17:09","19:04","20:21"],"08-02":["04:55","06:12","12:40","17:09","19:04","20:21"],"08-03":["04:56","06:12","12:40","17:08","19:03","20:20"],"08-04":["04:56","06:12","12:40","17:08","19:03","20:20"],"08-05":["04:57","06:13","12:40","17:07","19:02","20:19"],"08-06":["04:57","06:13","12:40","17:07","19:02","20:19"],"08-07":["04:57","06:13","12:40","17:06","19:02","20:17"],"08-08":["04:57","06:13","12:40","17:06","19:02","20:17"],"08-09":["04:58","06:13","12:40","17:05","19:01","20:16"],"08-10":["04:58","06:13","12:40","17:05","19:01","20:16"],"08-11":["04:59","06:14","12:39","17:04","19:00","20:15"],"08-12":["04:59","06:14","12:39","17:04","19:00","20:15"],"08-13":["04:59","06:14","12:39","17:03","18:59","20:14"],"08-14":["04:59","06:14","12:39","17:03","18:59","20:14"],"08-15":["05:00","06:15","12:39","17:03","18:58","20:12"],"08-16":["05:00","06:15","12:39","17:03","18:58","20:12"],"08-17":["05:01","06:15","12:38","17:02","18:57","20:11"],"08-18":["05:01","06:15","12:38","17:02","18:57","20:11"],"08-19":["05:01","06:15","12:38","17:02","18:55","20:09"],"08-20":["05:01","06:15","12:38","17:02","18:55","20:09"],"08-21":["05:02","06:16","12:37","17:01","18:54","20:08"],"08-22":["05:02","06:16","12:37","17:01","18:54","20:08"],"08-23":["05:02","06:16","12:37","17:01","18:53","20:06"],"08-24":["05:02","06:16","12:37","17:01","18:53","20:06"],"08-25":["05:03","06:16","12:36","17:00","18:52","20:05"],"08-26":["05:03","06:16","12:36","17:00","18:52","20:05"],"08-27":["05:03","06:16","12:36","17:00","18:50","20:03"],"08-28":["05:03","06:16","12:36","17:00","18:50","20:03"],"08-29":["05:04","06:17","12:35","16:59","18:49","20:02"],"08-30":["05:04","06:17","12:35","16:59","18:49","20:02"],"08-31":["05:04","06:17","12:35","16:58","18:48","20:00"],"09-01":["05:04","06:17","12:34","16:58","18:47","19:59"],"09-02":["05:04","06:17","12:34","16:58","18:47","19:59"],"09-03":["05:05","06:17","12:34","16:57","18:45","19:58"],"09-04":["05:05","06:17","12:34","16:57","18:45","19:58"],"09-05":["05:05","06:17","12:33","16:57","18:44","19:56"],"09-06":["05:05","06:17","12:33","16:57","18:44","19:56"],"09-07":["05:05","06:17","12:32","16:56","18:42","19:54"],"09-08":["05:05","06:17","12:32","16:56","18:42","19:54"],"09-09":["05:06","06:17","12:32","16:55","18:41","19:53"],"09-10":["05:06","06:17","12:32","16:55","18:41","19:53"],"09-11":["05:06","06:18","12:31","16:54","18:39","19:51"],"09-12":["05:06","06:18","12:31","16:54","18:39","19:51"],"09-13":["05:06","06:18","12:30","16:53","18:38","19:49"],"09-14":["05:06","06:18","12:30","16:53","18:38","19:49"],"09-15":["05:06","06:18","12:30","16:52","18:36","19:48"],"09-16":["05:06","06:18","12:30","16:52","18:36","19:48"],"09-17":["05:07","06:18","12:29","16:51","18:35","19:46"],"09-18":["05:07","06:18","12:29","16:51","18:35","19:46"],"09-19":["05:07","06:18","12:28","16:50","18:33","19:45"],"09-20":["05:07","06:18","12:28","16:50","18:33","19:45"],"09-21":["05:07","06:18","12:27","16:49","18:32","19:43"],"09-22":["05:07","06:18","12:27","16:49","18:32","19:43"],"09-23":["05:07","06:18","12:27","16:48","18:30","19:41"],"09-24":["05:07","06:18","12:27","16:48","18:30","19:41"],"09-25":["05:07","06:18","12:26","16:47","18:29","19:40"],"09-26":["05:07","06:18","12:26","16:47","18:29","19:40"],"09-27":["05:07","06:19","12:25","16:46","18:27","19:38"],"09-28":["05:07","06:19","12:25","16:46","18:27","19:38"],"09-29":["05:08","06:19","12:25","16:45","18:26","19:37"],"09-30":["05:08","06:19","12:24","16:44","18:25","19:36"],"10-01":["05:08","06:19","12:24","16:44","18:24","19:35"],"10-02":["05:08","06:19","12:24","16:44","18:24","19:35"],"10-03":["05:08","06:19","12:23","16:43","18:23","19:34"],"10-04":["05:08","06:19","12:23","16:43","18:23","19:34"],"10-05":["05:08","06:19","12:23","16:41","18:21","19:32"],"10-06":["05:08","06:19","12:23","16:41","18:21","19:32"],"10-07":["05:08","06:19","12:22","16:40","18:20","19:31"],"10-08":["05:08","06:19","12:22","16:40","18:20","19:31"],"10-09":["05:08","06:20","12:21","16:39","18:18","19:30"],"10-10":["05:08","06:20","12:21","16:39","18:18","19:30"],"10-11":["05:09","06:20","12:20","16:38","18:17","19:29"],"10-12":["05:09","06:20","12:20","16:38","18:17","19:29"],"10-13":["05:09","06:20","12:20","16:37","18:16","19:27"],"10-14":["05:09","06:20","12:20","16:37","18:16","19:27"],"10-15":["05:09","06:21","12:20","16:36","18:14","19:26"],"10-16":["05:09","06:21","12:20","16:36","18:14","19:26"],"10-17":["05:09","06:21","12:20","16:35","18:13","19:25"],"10-18":["05:09","06:21","12:20","16:35","18:13","19:25"],"10-19":["05:10","06:21","12:19","16:34","18:12","19:24"],"10-20":["05:10","06:21","12:19","16:34","18:12","19:24"],"10-21":["05:10","06:22","12:19","16:33","18:11","19:23"],"10-22":["05:10","06:22","12:19","16:33","18:11","19:23"],"10-23":["05:10","06:22","12:18","16:32","18:10","19:22"],"10-24":["05:10","06:22","12:18","16:32","18:10","19:22"],"10-25":["05:11","06:23","12:18","16:32","18:09","19:21"],"10-26":["05:11","06:23","12:18","16:32","18:09","19:21"],"10-27":["05:11","06:23","12:18","16:31","18:08","19:20"],"10-28":["05:11","06:23","12:18","16:31","18:08","19:20"],"10-29":["05:11","06:24","12:18","16:30","18:07","19:19"],"10-30":["05:11","06:24","12:18","16:30","18:07","19:19"],"10-31":["05:12","06:24","12:18","16:29","18:06","19:19"],"11-01":["05:12","06:25","12:18","16:29","18:05","19:18"],"11-02":["05:12","06:25","12:18","16:29","18:05","19:18"],"11-03":["05:12","06:25","12:18","16:28","18:05","19:18"],"11-04":["05:12","06:25","12:18","16:28","18:05","19:18"],"11-05":["05:13","06:26","12:18","16:28","18:04","19:17"],"11-06":["05:13","06:26","12:18","16:28","18:04","19:17"],"11-07":["05:14","06:27","12:18","16:27","18:03","19:17"],"11-08":["05:14","06:27","12:18","16:27","18:03","19:17"],"11-09":["05:14","06:28","12:18","16:27","18:03","19:16"],"11-10":["05:14","06:28","12:18","16:27","18:03","19:16"],"11-11":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-12":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-13":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-14":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-15":["05:16","06:30","12:18","16:25","18:02","19:16"],"11-16":["05:16","06:30","12:18","16:25","18:02","19:16"],"11-17":["05:17","06:31","12:19","16:25","18:01","19:16"],"11-18":["05:17","06:31","12:19","16:25","18:01","19:16"],"11-19":["05:18","06:32","12:19","16:25","18:01","19:16"],"11-20":["05:18","06:32","12:19","16:25","18:01","19:16"],"11-21":["05:18","06:33","12:20","16:25","18:01","19:16"],"11-22":["05:18","06:33","12:20","16:25","18:01","19:16"],"11-23":["05:19","06:34","12:20","16:25","18:01","19:16"],"11-24":["05:19","06:34","12:20","16:25","18:01","19:16"],"11-25":["05:20","06:35","12:21","16:25","18:01","19:16"],"11-26":["05:20","06:35","12:21","16:25","18:01","19:16"],"11-27":["05:21","06:36","12:21","16:25","18:01","19:17"],"11-28":["05:21","06:36","12:21","16:25","18:01","19:17"],"11-29":["05:22","06:37","12:22","16:25","18:02","19:17"],"11-30":["05:22","06:38","12:22","16:26","18:02","19:17"],"12-01":["05:23","06:39","12:23","16:26","18:02","19:18"],"12-02":["05:23","06:39","12:23","16:26","18:02","19:18"],"12-03":["05:24","06:40","12:23","16:26","18:02","19:18"],"12-04":["05:24","06:40","12:23","16:26","18:02","19:18"],"12-05":["05:25","06:41","12:24","16:27","18:03","19:19"],"12-06":["05:25","06:41","12:24","16:27","18:03","19:19"],"12-07":["05:26","06:42","12:25","16:27","18:03","19:19"],"12-08":["05:26","06:42","12:25","16:27","18:03","19:19"],"12-09":["05:27","06:43","12:26","16:28","18:04","19:20"],"12-10":["05:27","06:43","12:26","16:28","18:04","19:20"],"12-11":["05:28","06:44","12:27","16:28","18:04","19:21"],"12-12":["05:28","06:44","12:27","16:28","18:04","19:21"],"12-13":["05:29","06:45","12:28","16:29","18:05","19:22"],"12-14":["05:29","06:45","12:28","16:29","18:05","19:22"],"12-15":["05:30","06:46","12:29","16:30","18:06","19:23"],"12-16":["05:30","06:46","12:29","16:30","18:06","19:23"],"12-17":["05:31","06:47","12:30","16:31","18:07","19:24"],"12-18":["05:31","06:47","12:30","16:31","18:07","19:24"],"12-19":["05:32","06:49","12:31","16:32","18:08","19:24"],"12-20":["05:32","06:49","12:31","16:32","18:08","19:24"],"12-21":["05:33","06:50","12:32","16:33","18:09","19:25"],"12-22":["05:33","06:50","12:32","16:33","18:09","19:25"],"12-23":["05:34","06:51","12:33","16:34","18:10","19:26"],"12-24":["05:34","06:51","12:33","16:34","18:10","19:26"],"12-25":["05:35","06:51","12:34","16:35","18:11","19:27"],"12-26":["05:35","06:51","12:34","16:35","18:11","19:27"],"12-27":["05:36","06:52","12:35","16:36","18:12","19:28"],"12-28":["05:36","06:52","12:35","16:36","18:12","19:28"],"12-29":["05:37","06:53","12:36","16:37","18:13","19:30"],"12-30":["05:37","06:53","12:36","16:37","18:13","19:30"],"12-31":["05:38","06:54","12:37","16:38","18:14","19:31"]
}"""
