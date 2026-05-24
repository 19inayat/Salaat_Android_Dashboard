package com.optimizer.namaz

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

import androidx.core.content.ContextCompat
import androidx.work.*

import kotlinx.coroutines.*

import org.json.JSONObject

import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import kotlin.math.cos
import kotlin.math.sin

// --- 1. BACKGROUND GPS WORKER ---
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

// --- 2. SHARED DATA ENGINE ---
object DataEngine {
    @Volatile var state: NamazState? = null
    private var job: Job? = null
    private var cachedHijriDate = "--"
    private var lastHijriCheckDay = -1

    fun start(context: Context) {
        if (job != null) return
        val workReq = PeriodicWorkRequestBuilder<LocationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("LocTracker", ExistingPeriodicWorkPolicy.KEEP, workReq)

        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                state = calculateState()
                delay(1000)
            }
        }
    }

    private suspend fun updateHijriDate() {

    val cal = Calendar.getInstance()
    val day = cal.get(Calendar.DAY_OF_MONTH)

    if (day != lastHijriCheckDay) {

        lastHijriCheckDay = day

        try {

            val dStr = SimpleDateFormat(
                "dd-MM-yyyy",
                Locale.US
            ).format(Date())

            val res = URL(
                "https://api.aladhan.com/v1/gToH?date=$dStr"
            ).readText()

            val json = JSONObject(res)
                .getJSONObject("data")
                .getJSONObject("hijri")

            cachedHijriDate =
                "${json.getString("day")} " +
                "${json.getJSONObject("month").getString("en")}\n" +
                json.getString("year")

        } catch (e: Exception) {

            cachedHijriDate =
                "$day/${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.YEAR)}"
        }
    }
}

    private fun calculateState(): NamazState {
        val now = Date()
        val cal = Calendar.getInstance().apply { time = now }
        val currentDec = cal.get(Calendar.HOUR_OF_DAY) + (cal.get(Calendar.MINUTE) / 60.0) + (cal.get(Calendar.SECOND) / 3600.0)
        
        updateHijriDate()

        val timings = getFallbackTimings()
        val dFajr = timeStrToDec(timings["Fajr"]!!)
        val dSunr = timeStrToDec(timings["Sunrise"]!!)
        val dZuhr = timeStrToDec(timings["Dhuhr"]!!)
        val dAsr = timeStrToDec(timings["Asr"]!!)
        val dMagr = timeStrToDec(timings["Maghrib"]!!)
        val dIsha = timeStrToDec(timings["Isha"]!!)

        val segments = listOf(
            Segment("FAJR", dFajr, dSunr, Color.parseColor("#FFD54F")),
            Segment("TULU", dSunr, dSunr + (20.0/60.0), Color.parseColor("#FFB74D")),
            Segment("ISHRAQ", dSunr + (20.0/60.0), dSunr + ((dFajr + (dMagr - dFajr)/2) - dSunr)/2, Color.parseColor("#FFCC80")),
            Segment("CHASHT", dSunr + ((dFajr + (dMagr - dFajr)/2) - dSunr)/2, dFajr + (dMagr - dFajr)/2, Color.parseColor("#CE93D8")),
            Segment("ZAWAL", dFajr + (dMagr - dFajr)/2, dZuhr, Color.parseColor("#69F0AE")),
            Segment("ZUHR", dZuhr, dAsr, Color.parseColor("#40C4FF")),
            Segment("ASR", dAsr, dMagr, Color.parseColor("#FF8A65")),
            Segment("MAGHRIB", dMagr, dIsha, Color.parseColor("#D81B60")),
            Segment("ISHA", dIsha, dFajr + 24, Color.parseColor("#37474F"))
        )

        var checkTime = currentDec
        if (currentDec < dFajr) checkTime += 24.0
        val currentSeg = segments.firstOrNull { checkTime >= it.start && checkTime < it.end }

        val remDec = (currentSeg?.end ?: 0.0) - checkTime
        val h = remDec.toInt()
        val m = ((remDec - h) * 60).toInt()
        val s = ((((remDec - h) * 60) - m) * 60).toInt()
        val timerStr = String.format("%02d:%02d:%02d", h, m, s)

        val planets = getPlanetaryRulers(currentDec, dSunr, dMagr)

        return NamazState(
            now = now, currentDec = currentDec, segments = segments, currentSegment = currentSeg,
            timerStr = timerStr, sehriStr = decToTimeStr(dFajr - (5.0/60.0)), iftarStr = decToTimeStr(dAsr),
            hijriDate = cachedHijriDate, degSunr = ((currentDec - dSunr + 24) % 24) * 15f, degMagr = ((currentDec - dMagr + 24) % 24) * 15f,
            dayPlanet = planets.first, hourPlanet = planets.second, minPlanet = planets.third
        )
    }

    private fun getPlanetaryRulers(decTime: Double, dSunr: Double, dMagr: Double): Triple<String, String, String> {
        val PLANETS = listOf("SUN", "VENUS", "MERCURY", "MOON", "SATURN", "JUPITER", "MARS")
        val DAY_RULERS = listOf(0, 3, 6, 2, 5, 1, 4)
        val d = Calendar.getInstance()
        var dayOfWeek = d.get(Calendar.DAY_OF_WEEK) - 1
        if (decTime < dSunr) dayOfWeek = (dayOfWeek + 6) % 7
        val rulerStartIndex = DAY_RULERS[dayOfWeek]
        var timeOffset = decTime - dSunr
        if (timeOffset < 0) timeOffset += 24.0
        val dayLength = dMagr - dSunr
        val nightLength = 24.0 - dayLength
        val currentHourIndex = if (decTime in dSunr..dMagr) ((timeOffset / dayLength) * 12).toInt() else 12 + (((decTime - dMagr + if (decTime - dMagr < 0) 24.0 else 0.0) / nightLength) * 12).toInt()
        val hourPlanetIdx = (rulerStartIndex + currentHourIndex) % 7
        val minPlanetIdx = (hourPlanetIdx + ((timeOffset * 60) % 60).toInt()) % 7
        return Triple(PLANETS[rulerStartIndex], PLANETS[hourPlanetIdx], PLANETS[minPlanetIdx])
    }

    private fun timeStrToDec(s: String): Double { val p = s.split(":"); return p[0].toDouble() + (p[1].toDouble()/60.0) }
    private fun decToTimeStr(d: Double): String { var v = d; if(v>=24) v-=24; var h=v.toInt(); var m=Math.round((v-h)*60).toInt(); if(m==60){h++; m=0}; return String.format("%02d:%02d", h%24, m) }

    private fun getFallbackTimings(): Map<String, String> {
        val cal = Calendar.getInstance()
        val key = String.format("%02d-%02d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        
        val rawJson = """{"01-01":["05:38","06:55","12:37","16:39","18:15","19:31"],"01-02":["05:38","06:55","12:37","16:39","18:15","19:31"],"01-03":["05:39","06:55","12:38","16:40","18:16","19:32"],"01-04":["05:39","06:55","12:38","16:40","18:16","19:32"],"01-05":["05:40","06:56","12:39","16:41","18:17","19:33"],"01-06":["05:40","06:56","12:39","16:41","18:17","19:33"],"01-07":["05:40","06:57","12:40","16:42","18:18","19:34"],"01-08":["05:40","06:57","12:40","16:42","18:18","19:34"],"01-09":["05:41","06:57","12:41","16:43","18:20","19:35"],"01-10":["05:41","06:57","12:41","16:43","18:20","19:35"],"01-11":["05:42","06:58","12:42","16:45","18:21","19:37"],"01-12":["05:42","06:58","12:42","16:45","18:21","19:37"],"01-13":["05:42","06:58","12:42","16:46","18:22","19:38"],"01-14":["05:42","06:58","12:42","16:46","18:22","19:38"],"01-15":["05:43","06:58","12:43","16:47","18:23","19:39"],"01-16":["05:43","06:58","12:43","16:47","18:23","19:39"],"01-17":["05:43","06:58","12:44","16:48","18:24","19:40"],"01-18":["05:43","06:58","12:44","16:48","18:24","19:40"],"01-19":["05:44","06:59","12:44","16:49","18:25","19:40"],"01-20":["05:44","06:59","12:44","16:49","18:25","19:40"],"01-21":["05:44","06:59","12:45","16:50","18:26","19:41"],"01-22":["05:44","06:59","12:45","16:50","18:26","19:41"],"01-23":["05:44","06:59","12:46","16:51","18:28","19:42"],"01-24":["05:44","06:59","12:46","16:51","18:28","19:42"],"01-25":["05:44","06:59","12:46","16:53","18:29","19:43"],"01-26":["05:44","06:59","12:46","16:53","18:29","19:43"],"01-27":["05:44","06:59","12:47","16:54","18:30","19:44"],"01-28":["05:44","06:59","12:47","16:54","18:30","19:44"],"01-29":["05:44","06:58","12:47","16:55","18:31","19:45"],"01-30":["05:44","06:58","12:47","16:55","18:31","19:45"],"01-31":["05:44","06:58","12:47","16:55","18:32","19:46"],"02-01":["05:44","06:58","12:48","16:56","18:32","19:46"],"02-02":["05:44","06:58","12:48","16:56","18:32","19:46"],"02-03":["05:44","06:57","12:48","16:57","18:33","19:47"],"02-04":["05:44","06:57","12:48","16:57","18:33","19:47"],"02-05":["05:44","06:57","12:48","16:58","18:34","19:47"],"02-06":["05:44","06:57","12:48","16:58","18:34","19:47"],"02-07":["05:43","06:56","12:48","16:58","18:35","19:48"],"02-08":["05:43","06:56","12:48","16:58","18:35","19:48"],"02-09":["05:43","06:56","12:48","16:59","18:36","19:49"],"02-10":["05:43","06:56","12:48","16:59","18:36","19:49"],"02-11":["05:42","06:55","12:48","17:00","18:36","19:49"],"02-12":["05:42","06:55","12:48","17:00","18:36","19:49"],"02-13":["05:42","06:54","12:48","17:00","18:37","19:50"],"02-14":["05:42","06:54","12:48","17:00","18:37","19:50"],"02-15":["05:41","06:54","12:48","17:01","18:38","19:50"],"02-16":["05:41","06:54","12:48","17:01","18:38","19:50"],"02-17":["05:40","06:53","12:48","17:01","18:39","19:51"],"02-18":["05:40","06:53","12:48","17:01","18:39","19:51"],"02-19":["05:40","06:52","12:48","17:02","18:39","19:51"],"02-20":["05:40","06:52","12:48","17:02","18:39","19:51"],"02-21":["05:39","06:51","12:48","17:02","18:40","19:52"],"02-22":["05:39","06:51","12:48","17:02","18:40","19:52"],"02-23":["05:38","06:50","12:48","17:03","18:40","19:52"],"02-24":["05:38","06:50","12:48","17:03","18:40","19:52"],"02-25":["05:37","06:49","12:47","17:03","18:41","19:52"],"02-26":["05:37","06:49","12:47","17:03","18:41","19:52"],"02-27":["05:36","06:48","12:47","17:03","18:41","19:53"],"02-28":["05:36","06:48","12:47","17:03","18:41","19:53"],"02-29":["05:35","06:47","12:47","17:03","18:42","19:53"],"03-01":["05:35","06:46","12:47","17:03","18:42","19:53"],"03-02":["05:35","06:46","12:47","17:03","18:42","19:53"],"03-03":["05:34","06:45","12:46","17:04","18:42","19:54"],"03-04":["05:34","06:45","12:46","17:04","18:42","19:54"],"03-05":["05:33","06:44","12:46","17:04","18:43","19:54"],"03-06":["05:33","06:44","12:46","17:04","18:43","19:54"],"03-07":["05:31","06:43","12:45","17:04","18:43","19:54"],"03-08":["05:31","06:43","12:45","17:04","18:43","19:54"],"03-09":["05:30","06:41","12:45","17:04","18:43","19:55"],"03-10":["05:30","06:41","12:45","17:04","18:43","19:55"],"03-11":["05:29","06:40","12:44","17:04","18:44","19:55"],"03-12":["05:29","06:40","12:44","17:04","18:44","19:55"],"03-13":["05:27","06:39","12:44","17:04","18:44","19:55"],"03-14":["05:27","06:39","12:44","17:04","18:44","19:55"],"03-15":["05:26","06:37","12:43","17:03","18:44","19:55"],"03-16":["05:26","06:37","12:43","17:03","18:44","19:55"],"03-17":["05:25","06:36","12:43","17:03","18:45","19:56"],"03-18":["05:25","06:36","12:43","17:03","18:45","19:56"],"03-19":["05:23","06:34","12:42","17:03","18:45","19:56"],"03-20":["05:23","06:34","12:42","17:03","18:45","19:56"],"03-21":["05:22","06:33","12:42","17:03","18:45","19:56"],"03-22":["05:22","06:33","12:42","17:03","18:45","19:56"],"03-23":["05:20","06:31","12:41","17:03","18:45","19:57"],"03-24":["05:20","06:31","12:41","17:03","18:45","19:57"],"03-25":["05:19","06:30","12:40","17:02","18:46","19:57"],"03-26":["05:19","06:30","12:40","17:02","18:46","19:57"],"03-27":["05:17","06:29","12:40","17:02","18:46","19:57"],"03-28":["05:17","06:29","12:40","17:02","18:46","19:57"],"03-29":["05:16","06:27","12:39","17:02","18:46","19:58"],"03-30":["05:16","06:27","12:39","17:02","18:46","19:58"],"03-31":["05:14","06:26","12:38","17:01","18:46","19:58"],"04-01":["05:13","06:25","12:38","17:01","18:46","19:58"],"04-02":["05:13","06:25","12:38","17:01","18:46","19:58"],"04-03":["05:12","06:23","12:38","17:01","18:47","19:59"],"04-04":["05:12","06:23","12:38","17:01","18:47","19:59"],"04-05":["05:10","06:22","12:37","17:00","18:47","19:59"],"04-06":["05:10","06:22","12:37","17:00","18:47","19:59"],"04-07":["05:09","06:21","12:36","17:00","18:47","19:59"],"04-08":["05:09","06:21","12:36","17:00","18:47","19:59"],"04-09":["05:07","06:19","12:36","17:00","18:47","20:00"],"04-10":["05:07","06:19","12:36","17:00","18:47","20:00"],"04-11":["05:05","06:18","12:35","16:59","18:48","20:00"],"04-12":["05:05","06:18","12:35","16:59","18:48","20:00"],"04-13":["05:04","06:17","12:35","16:59","18:48","20:01"],"04-14":["05:04","06:17","12:35","16:59","18:48","20:01"],"04-15":["05:02","06:15","12:34","16:58","18:48","20:01"],"04-16":["05:02","06:15","12:34","16:58","18:48","20:01"],"04-17":["05:01","06:14","12:34","16:58","18:49","20:02"],"04-18":["05:01","06:14","12:34","16:58","18:49","20:02"],"04-19":["04:59","06:13","12:33","16:57","18:49","20:02"],"04-20":["04:59","06:13","12:33","16:57","18:49","20:02"],"04-21":["04:58","06:12","12:33","16:57","18:49","20:03"],"04-22":["04:58","06:12","12:33","16:57","18:49","20:03"],"04-23":["04:56","06:10","12:33","16:57","18:50","20:04"],"04-24":["04:56","06:10","12:33","16:57","18:50","20:04"],"04-25":["04:55","06:09","12:32","16:56","18:50","20:04"],"04-26":["04:55","06:09","12:32","16:56","18:50","20:04"],"04-27":["04:54","06:08","12:32","16:56","18:50","20:05"],"04-28":["04:54","06:08","12:32","16:56","18:50","20:05"],"04-29":["04:52","06:07","12:31","16:55","18:51","20:06"],"04-30":["04:52","06:07","12:31","16:55","18:51","20:06"],"05-01":["04:51","06:06","12:31","16:55","18:51","20:06"],"05-02":["04:51","06:06","12:31","16:55","18:51","20:06"],"05-03":["04:50","06:05","12:31","16:56","18:52","20:07"],"05-04":["04:50","06:05","12:31","16:56","18:52","20:07"],"05-05":["04:49","06:04","12:31","16:56","18:52","20:08"],"05-06":["04:49","06:04","12:31","16:56","18:52","20:08"],"05-07":["04:48","06:03","12:31","16:57","18:53","20:09"],"05-08":["04:48","06:03","12:31","16:57","18:53","20:09"],"05-09":["04:46","06:03","12:30","16:58","18:53","20:09"],"05-10":["04:46","06:03","12:30","16:58","18:53","20:09"],"05-11":["04:45","06:02","12:30","16:58","18:54","20:10"],"05-12":["04:45","06:02","12:30","16:58","18:54","20:10"],"05-13":["04:44","06:01","12:30","16:59","18:54","20:11"],"05-14":["04:44","06:01","12:30","16:59","18:54","20:11"],"05-15":["04:44","06:01","12:30","17:00","18:55","20:12"],"05-16":["04:44","06:01","12:30","17:00","18:55","20:12"],"05-17":["04:43","06:00","12:30","17:00","18:56","20:13"],"05-18":["04:43","06:00","12:30","17:00","18:56","20:13"],"05-19":["04:42","06:00","12:30","17:01","18:56","20:14"],"05-20":["04:42","06:00","12:30","17:01","18:56","20:14"],"05-21":["04:41","05:59","12:30","17:02","18:57","20:15"],"05-22":["04:41","05:59","12:30","17:02","18:57","20:15"],"05-23":["04:41","05:59","12:31","17:02","18:57","20:16"],"05-24":["04:41","05:59","12:31","17:02","18:57","20:16"],"05-25":["04:40","05:59","12:31","17:03","18:58","20:16"],"05-26":["04:40","05:59","12:31","17:03","18:58","20:16"],"05-27":["04:40","05:58","12:31","17:04","18:59","20:17"],"05-28":["04:40","05:58","12:31","17:04","18:59","20:17"],"05-29":["04:39","05:58","12:31","17:04","18:59","20:18"],"05-30":["04:39","05:58","12:31","17:04","18:59","20:18"],"05-31":["04:39","05:58","12:31","17:05","19:00","20:19"],"06-01":["04:39","05:58","12:32","17:05","19:00","20:19"],"06-02":["04:39","05:58","12:32","17:05","19:00","20:19"],"06-03":["04:39","05:58","12:32","17:06","19:01","20:20"],"06-04":["04:39","05:58","12:32","17:06","19:01","20:20"],"06-05":["04:38","05:58","12:32","17:07","19:02","20:21"],"06-06":["04:38","05:58","12:32","17:07","19:02","20:21"],"06-07":["04:38","05:58","12:33","17:07","19:02","20:22"],"06-08":["04:38","05:58","12:33","17:07","19:02","20:22"],"06-09":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-10":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-11":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-12":["04:38","05:58","12:33","17:08","19:03","20:23"],"06-13":["04:38","05:59","12:34","17:09","19:04","20:24"],"06-14":["04:38","05:59","12:34","17:09","19:04","20:24"],"06-15":["04:39","05:59","12:34","17:10","19:04","20:25"],"06-16":["04:39","05:59","12:34","17:10","19:04","20:25"],"06-17":["04:39","05:59","12:35","17:10","19:05","20:25"],"06-18":["04:39","05:59","12:35","17:10","19:05","20:25"],"06-19":["04:39","06:00","12:35","17:11","19:06","20:26"],"06-20":["04:39","06:00","12:35","17:11","19:06","20:26"],"06-21":["04:40","06:00","12:35","17:11","19:06","20:26"],"06-22":["04:40","06:00","12:35","17:11","19:06","20:26"],"06-23":["04:40","06:00","12:36","17:12","19:06","20:27"],"06-24":["04:40","06:00","12:36","17:12","19:06","20:27"],"06-25":["04:41","06:01","12:36","17:12","19:07","20:27"],"06-26":["04:41","06:01","12:36","17:12","19:07","20:27"],"06-27":["04:41","06:01","12:37","17:12","19:07","20:27"],"06-28":["04:41","06:01","12:37","17:12","19:07","20:27"],"06-29":["04:42","06:02","12:37","17:13","19:07","20:28"],"06-30":["04:42","06:02","12:37","17:13","19:08","20:28"],"07-01":["04:42","06:03","12:38","17:13","19:08","20:28"],"07-02":["04:42","06:03","12:38","17:13","19:08","20:28"],"07-03":["04:43","06:03","12:38","17:13","19:08","20:28"],"07-04":["04:43","06:03","12:38","17:13","19:08","20:28"],"07-05":["04:44","06:04","12:38","17:13","19:08","20:28"],"07-06":["04:44","06:04","12:38","17:13","19:08","20:28"],"07-07":["04:45","06:04","12:39","17:13","19:08","20:28"],"07-08":["04:45","06:04","12:39","17:13","19:08","20:28"],"07-09":["04:45","06:05","12:39","17:13","19:08","20:28"],"07-10":["04:45","06:05","12:39","17:13","19:08","20:28"],"07-11":["04:46","06:06","12:39","17:13","19:08","20:27"],"07-12":["04:46","06:06","12:39","17:13","19:08","20:27"],"07-13":["04:47","06:06","12:40","17:13","19:08","20:27"],"07-14":["04:47","06:06","12:40","17:13","19:08","20:27"],"07-15":["04:48","06:07","12:40","17:13","19:08","20:27"],"07-16":["04:48","06:07","12:40","17:13","19:08","20:27"],"07-17":["04:49","06:07","12:40","17:13","19:08","20:26"],"07-18":["04:49","06:07","12:40","17:13","19:08","20:26"],"07-19":["04:50","06:08","12:40","17:12","19:07","20:26"],"07-20":["04:50","06:08","12:40","17:12","19:07","20:26"],"07-21":["04:50","06:09","12:40","17:12","19:07","20:25"],"07-22":["04:50","06:09","12:40","17:12","19:07","20:25"],"07-23":["04:51","06:09","12:40","17:12","19:07","20:25"],"07-24":["04:51","06:09","12:40","17:12","19:07","20:25"],"07-25":["04:52","06:10","12:40","17:11","19:06","20:24"],"07-26":["04:52","06:10","12:40","17:11","19:06","20:24"],"07-27":["04:53","06:10","12:40","17:10","19:06","20:23"],"07-28":["04:53","06:10","12:40","17:10","19:06","20:23"],"07-29":["04:54","06:11","12:40","17:10","19:05","20:22"],"07-30":["04:54","06:11","12:40","17:10","19:05","20:22"],"07-31":["04:55","06:11","12:40","17:09","19:04","20:21"],"08-01":["04:55","06:12","12:40","17:09","19:04","20:21"],"08-02":["04:55","06:12","12:40","17:09","19:04","20:21"],"08-03":["04:56","06:12","12:40","17:08","19:03","20:20"],"08-04":["04:56","06:12","12:40","17:08","19:03","20:20"],"08-05":["04:57","06:13","12:40","17:07","19:02","20:19"],"08-06":["04:57","06:13","12:40","17:07","19:02","20:19"],"08-07":["04:57","06:13","12:40","17:06","19:02","20:17"],"08-08":["04:57","06:13","12:40","17:06","19:02","20:17"],"08-09":["04:58","06:13","12:40","17:05","19:01","20:16"],"08-10":["04:58","06:13","12:40","17:05","19:01","20:16"],"08-11":["04:59","06:14","12:39","17:04","19:00","20:15"],"08-12":["04:59","06:14","12:39","17:04","19:00","20:15"],"08-13":["04:59","06:14","12:39","17:03","18:59","20:14"],"08-14":["04:59","06:14","12:39","17:03","18:59","20:14"],"08-15":["05:00","06:15","12:39","17:03","18:58","20:12"],"08-16":["05:00","06:15","12:39","17:03","18:58","20:12"],"08-17":["05:01","06:15","12:38","17:02","18:57","20:11"],"08-18":["05:01","06:15","12:38","17:02","18:57","20:11"],"08-19":["05:01","06:15","12:38","17:02","18:55","20:09"],"08-20":["05:01","06:15","12:38","17:02","18:55","20:09"],"08-21":["05:02","06:16","12:37","17:01","18:54","20:08"],"08-22":["05:02","06:16","12:37","17:01","18:54","20:08"],"08-23":["05:02","06:16","12:37","17:01","18:53","20:06"],"08-24":["05:02","06:16","12:37","17:01","18:53","20:06"],"08-25":["05:03","06:16","12:36","17:00","18:52","20:05"],"08-26":["05:03","06:16","12:36","17:00","18:52","20:05"],"08-27":["05:03","06:16","12:36","17:00","18:50","20:03"],"08-28":["05:03","06:16","12:36","17:00","18:50","20:03"],"08-29":["05:04","06:17","12:35","16:59","18:49","20:02"],"08-30":["05:04","06:17","12:35","16:59","18:49","20:02"],"08-31":["05:04","06:17","12:35","16:58","18:48","20:00"],"09-01":["05:04","06:17","12:34","16:58","18:47","19:59"],"09-02":["05:04","06:17","12:34","16:58","18:47","19:59"],"09-03":["05:05","06:17","12:34","16:57","18:45","19:58"],"09-04":["05:05","06:17","12:34","16:57","18:45","19:58"],"09-05":["05:05","06:17","12:33","16:57","18:44","19:56"],"09-06":["05:05","06:17","12:33","16:57","18:44","19:56"],"09-07":["05:05","06:17","12:32","16:56","18:42","19:54"],"09-08":["05:05","06:17","12:32","16:56","18:42","19:54"],"09-09":["05:06","06:17","12:32","16:55","18:41","19:53"],"09-10":["05:06","06:17","12:32","16:55","18:41","19:53"],"09-11":["05:06","06:18","12:31","16:54","18:39","19:51"],"09-12":["05:06","06:18","12:31","16:54","18:39","19:51"],"09-13":["05:06","06:18","12:30","16:53","18:38","19:49"],"09-14":["05:06","06:18","12:30","16:53","18:38","19:49"],"09-15":["05:06","06:18","12:30","16:52","18:36","19:48"],"09-16":["05:06","06:18","12:30","16:52","18:36","19:48"],"09-17":["05:07","06:18","12:29","16:51","18:35","19:46"],"09-18":["05:07","06:18","12:29","16:51","18:35","19:46"],"09-19":["05:07","06:18","12:28","16:50","18:33","19:45"],"09-20":["05:07","06:18","12:28","16:50","18:33","19:45"],"09-21":["05:07","06:18","12:27","16:49","18:32","19:43"],"09-22":["05:07","06:18","12:27","16:49","18:32","19:43"],"09-23":["05:07","06:18","12:27","16:48","18:30","19:41"],"09-24":["05:07","06:18","12:27","16:48","18:30","19:41"],"09-25":["05:07","06:18","12:26","16:47","18:29","19:40"],"09-26":["05:07","06:18","12:26","16:47","18:29","19:40"],"09-27":["05:07","06:19","12:25","16:46","18:27","19:38"],"09-28":["05:07","06:19","12:25","16:46","18:27","19:38"],"09-29":["05:08","06:19","12:25","16:45","18:26","19:37"],"09-30":["05:08","06:19","12:24","16:44","18:25","19:36"],"10-01":["05:08","06:19","12:24","16:44","18:24","19:35"],"10-02":["05:08","06:19","12:24","16:44","18:24","19:35"],"10-03":["05:08","06:19","12:23","16:43","18:23","19:34"],"10-04":["05:08","06:19","12:23","16:43","18:23","19:34"],"10-05":["05:08","06:19","12:23","16:41","18:21","19:32"],"10-06":["05:08","06:19","12:23","16:41","18:21","19:32"],"10-07":["05:08","06:19","12:22","16:40","18:20","19:31"],"10-08":["05:08","06:19","12:22","16:40","18:20","19:31"],"10-09":["05:08","06:20","12:21","16:39","18:18","19:30"],"10-10":["05:08","06:20","12:21","16:39","18:18","19:30"],"10-11":["05:09","06:20","12:20","16:38","18:17","19:29"],"10-12":["05:09","06:20","12:20","16:38","18:17","19:29"],"10-13":["05:09","06:20","12:20","16:37","18:16","19:27"],"10-14":["05:09","06:20","12:20","16:37","18:16","19:27"],"10-15":["05:09","06:21","12:20","16:36","18:14","19:26"],"10-16":["05:09","06:21","12:20","16:36","18:14","19:26"],"10-17":["05:09","06:21","12:20","16:35","18:13","19:25"],"10-18":["05:09","06:21","12:20","16:35","18:13","19:25"],"10-19":["05:10","06:21","12:19","16:34","18:12","19:24"],"10-20":["05:10","06:21","12:19","16:34","18:12","19:24"],"10-21":["05:10","06:22","12:19","16:33","18:11","19:23"],"10-22":["05:10","06:22","12:19","16:33","18:11","19:23"],"10-23":["05:10","06:22","12:18","16:32","18:10","19:22"],"10-24":["05:10","06:22","12:18","16:32","18:10","19:22"],"10-25":["05:11","06:23","12:18","16:32","18:09","19:21"],"10-26":["05:11","06:23","12:18","16:32","18:09","19:21"],"10-27":["05:11","06:23","12:18","16:31","18:08","19:20"],"10-28":["05:11","06:23","12:18","16:31","18:08","19:20"],"10-29":["05:11","06:24","12:18","16:30","18:07","19:19"],"10-30":["05:11","06:24","12:18","16:30","18:07","19:19"],"10-31":["05:12","06:24","12:18","16:29","18:06","19:19"],"11-01":["05:12","06:25","12:18","16:29","18:05","19:18"],"11-02":["05:12","06:25","12:18","16:29","18:05","19:18"],"11-03":["05:12","06:25","12:18","16:28","18:05","19:18"],"11-04":["05:12","06:25","12:18","16:28","18:05","19:18"],"11-05":["05:13","06:26","12:18","16:28","18:04","19:17"],"11-06":["05:13","06:26","12:18","16:28","18:04","19:17"],"11-07":["05:14","06:27","12:18","16:27","18:03","19:17"],"11-08":["05:14","06:27","12:18","16:27","18:03","19:17"],"11-09":["05:14","06:28","12:18","16:27","18:03","19:16"],"11-10":["05:14","06:28","12:18","16:27","18:03","19:16"],"11-11":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-12":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-13":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-14":["05:15","06:29","12:18","16:26","18:02","19:16"],"11-15":["05:16","06:30","12:18","16:25","18:02","19:16"],"11-16":["05:16","06:30","12:18","16:25","18:02","19:16"],"11-17":["05:17","06:31","12:19","16:25","18:01","19:16"],"11-18":["05:17","06:31","12:19","16:25","18:01","19:16"],"11-19":["05:18","06:32","12:19","16:25","18:01","19:16"],"11-20":["05:18","06:32","12:19","16:25","18:01","19:16"],"11-21":["05:18","06:33","12:20","16:25","18:01","19:16"],"11-22":["05:18","06:33","12:20","16:25","18:01","19:16"],"11-23":["05:19","06:34","12:20","16:25","18:01","19:16"],"11-24":["05:19","06:34","12:20","16:25","18:01","19:16"],"11-25":["05:20","06:35","12:21","16:25","18:01","19:16"],"11-26":["05:20","06:35","12:21","16:25","18:01","19:16"],"11-27":["05:21","06:36","12:21","16:25","18:01","19:17"],"11-28":["05:21","06:36","12:21","16:25","18:01","19:17"],"11-29":["05:22","06:37","12:22","16:25","18:02","19:17"],"11-30":["05:22","06:38","12:22","16:26","18:02","19:17"],"12-01":["05:23","06:39","12:23","16:26","18:02","19:18"],"12-02":["05:23","06:39","12:23","16:26","18:02","19:18"],"12-03":["05:24","06:40","12:23","16:26","18:02","19:18"],"12-04":["05:24","06:40","12:23","16:26","18:02","19:18"],"12-05":["05:25","06:41","12:24","16:27","18:03","19:19"],"12-06":["05:25","06:41","12:24","16:27","18:03","19:19"],"12-07":["05:26","06:42","12:25","16:27","18:03","19:19"],"12-08":["05:26","06:42","12:25","16:27","18:03","19:19"],"12-09":["05:27","06:43","12:26","16:28","18:04","19:20"],"12-10":["05:27","06:43","12:26","16:28","18:04","19:20"],"12-11":["05:28","06:44","12:27","16:28","18:04","19:21"],"12-12":["05:28","06:44","12:27","16:28","18:04","19:21"],"12-13":["05:29","06:45","12:28","16:29","18:05","19:22"],"12-14":["05:29","06:45","12:28","16:29","18:05","19:22"],"12-15":["05:30","06:46","12:29","16:30","18:06","19:23"],"12-16":["05:30","06:46","12:29","16:30","18:06","19:23"],"12-17":["05:31","06:47","12:30","16:31","18:07","19:24"],"12-18":["05:31","06:47","12:30","16:31","18:07","19:24"],"12-19":["05:32","06:49","12:31","16:32","18:08","19:24"],"12-20":["05:32","06:49","12:31","16:32","18:08","19:24"],"12-21":["05:33","06:50","12:32","16:33","18:09","19:25"],"12-22":["05:33","06:50","12:32","16:33","18:09","19:25"],"12-23":["05:34","06:51","12:33","16:34","18:10","19:26"],"12-24":["05:34","06:51","12:33","16:34","18:10","19:26"],"12-25":["05:35","06:51","12:34","16:35","18:11","19:27"],"12-26":["05:35","06:51","12:34","16:35","18:11","19:27"],"12-27":["05:36","06:52","12:35","16:36","18:12","19:28"],"12-28":["05:36","06:52","12:35","16:36","18:12","19:28"],"12-29":["05:37","06:53","12:36","16:37","18:13","19:30"],"12-30":["05:37","06:53","12:36","16:37","18:13","19:30"],"12-31":["05:38","06:54","12:37","16:38","18:14","19:31"]}"""
        
        return try {
            val json = JSONObject(rawJson)
            val arr = json.optJSONArray(key) ?: json.getJSONArray("01-01")
            mapOf("Fajr" to arr.getString(0), "Sunrise" to arr.getString(1), "Dhuhr" to arr.getString(2), "Asr" to arr.getString(3), "Maghrib" to arr.getString(4), "Isha" to arr.getString(5))
        } catch (e: Exception) {
            mapOf("Fajr" to "05:00", "Sunrise" to "06:15", "Dhuhr" to "12:30", "Asr" to "16:45", "Maghrib" to "18:30", "Isha" to "19:45")
        }
    }
}

// --- 3. DATA MODELS ---
data class Segment(val name: String, val start: Double, val end: Double, val color: Int)
data class NamazState(val now: Date, val currentDec: Double, val segments: List<Segment>, val currentSegment: Segment?, val timerStr: String, val sehriStr: String, val iftarStr: String, val hijriDate: String, val degSunr: Float, val degMagr: Float, val dayPlanet: String, val hourPlanet: String, val minPlanet: String)

// --- 4. THE UNIFIED CANVAS RENDERER ---
object DashboardRenderer {
    fun draw(canvas: Canvas, width: Int, height: Int, state: NamazState) {
        val bgPaint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.parseColor("#465287"), Color.parseColor("#2D325A"), Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val timeStr = SimpleDateFormat("h:mm", Locale.US).format(state.now)
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = width * 0.3f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
        canvas.drawText(timeStr, width / 2f, height * 0.22f, timePaint)

        val cx = width / 2f
        val cy = height * 0.45f
        val radius = width * 0.4f
        val innerRadius = radius * 0.5f
        val rectF = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = radius - innerRadius }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = radius * 0.12f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        
        state.segments.forEach { seg ->
            val startAngle = ((seg.start / 24.0) * 360.0 - 90.0).toFloat()
            val sweepAngle = (((seg.end - seg.start) / 24.0) * 360.0).toFloat()
            arcPaint.color = seg.color
            canvas.drawArc(rectF, startAngle, sweepAngle, false, arcPaint)

            val path = Path()
            var normMid = (startAngle + sweepAngle / 2f) % 360f
            if (normMid < 0) normMid += 360f
            if (normMid in 0f..180f) path.addArc(rectF, startAngle + sweepAngle, -sweepAngle) else path.addArc(rectF, startAngle, sweepAngle)
            val arcLen = (Math.abs(sweepAngle) / 360f) * 2 * Math.PI * (radius - (radius - innerRadius)/2f)
            canvas.drawTextOnPath(seg.name, path, arcLen.toFloat() / 2f, textPaint.textSize / 3f, textPaint)
        }

        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D2036"); style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, innerRadius, innerPaint)
        
        val centerSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = radius * 0.12f; textAlign = Paint.Align.CENTER }
        val centerBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = radius * 0.25f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val centerHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFCC80"); textSize = radius * 0.1f; textAlign = Paint.Align.CENTER }
        val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF4E50"); textSize = radius * 0.18f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        
        canvas.drawText(SimpleDateFormat("EEE, dd MMM", Locale.US).format(state.now), cx, cy - radius * 0.2f, centerSmall)
        canvas.drawText(state.currentSegment?.name ?: "--", cx, cy + radius * 0.02f, centerBig)
        canvas.drawText("${decToTimeStr(state.currentSegment?.start ?: 0.0)} - ${decToTimeStr(state.currentSegment?.end ?: 0.0)}", cx, cy + radius * 0.15f, centerHighlight)
        canvas.drawText("REMAINING", cx, cy + radius * 0.25f, Paint(centerSmall).apply { textSize = radius * 0.07f; letterSpacing = 0.1f })
        canvas.drawText(state.timerStr, cx, cy + radius * 0.4f, timerPaint)

        val needleAngle = ((state.currentDec / 24.0) * 360.0 - 90.0) * (Math.PI / 180.0)
        val nx = cx + cos(needleAngle).toFloat() * (innerRadius - 10f)
        val ny = cy + sin(needleAngle).toFloat() * (innerRadius - 10f)
        canvas.drawLine(cx, cy, nx, ny, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND })
        canvas.drawCircle(nx, ny, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply{ color = Color.WHITE })
        canvas.drawCircle(nx, ny, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply{ color = Color.RED })

        val panelTop = height * 0.72f
        val panelBottom = height * 0.95f
        val pWidth = width * 0.9f
        val px = width * 0.05f
        canvas.drawRoundRect(px, panelTop, px + pWidth, panelBottom, 50f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#232742") })

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = width * 0.025f; textAlign = Paint.Align.CENTER; letterSpacing = 0.1f }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = width * 0.035f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }

        canvas.drawRoundRect(width/2f - 150f, panelTop + 30f, width/2f + 150f, panelTop + 80f, 25f, 25f, Paint(Paint.ANTI_ALIAS_FLAG).apply{ color = Color.parseColor("#353A55") })
        canvas.drawText("HUBBALLI (FALLBACK)", width/2f, panelTop + 62f, Paint(titlePaint).apply { color = Color.WHITE })

        val y1 = panelTop + 140f
        canvas.drawText("SEHRI", width*0.25f, y1 - 20f, titlePaint)
        valPaint.color = Color.parseColor("#80CBC4"); canvas.drawText(state.sehriStr, width*0.25f, y1 + 10f, valPaint)
        canvas.drawText("IFTAR", width*0.5f, y1 - 20f, titlePaint)
        valPaint.color = Color.parseColor("#80CBC4"); canvas.drawText(state.iftarStr, width*0.5f, y1 + 10f, valPaint)
        canvas.drawText("HIJRI", width*0.75f, y1 - 20f, titlePaint)
        valPaint.color = Color.parseColor("#FFCC80"); 
        val hLines = state.hijriDate.split("\n")
        if(hLines.size > 1) { canvas.drawText(hLines[0], width*0.75f, y1 + 5f, valPaint); canvas.drawText(hLines[1], width*0.75f, y1 + 35f, valPaint) } 
        else canvas.drawText(state.hijriDate, width*0.75f, y1 + 10f, valPaint)

        canvas.drawLine(px + 40f, y1 + 45f, px + pWidth - 40f, y1 + 45f, Paint().apply{ color = Color.parseColor("#465287"); strokeWidth = 2f })

        val y2 = panelTop + 210f
        canvas.drawText("DAY PLANET", width*0.18f, y2 - 20f, titlePaint)
        valPaint.color = Color.parseColor("#FFCC80"); canvas.drawText(state.dayPlanet, width*0.18f, y2 + 10f, valPaint)
        canvas.drawText("HOUR PLANET", width*0.38f, y2 - 20f, titlePaint)
        canvas.drawText(state.hourPlanet, width*0.38f, y2 + 10f, valPaint)
        canvas.drawText("MIN PLANET", width*0.62f, y2 - 20f, titlePaint)
        valPaint.color = Color.parseColor("#FF4E50"); canvas.drawText(state.minPlanet, width*0.62f, y2 + 10f, valPaint)
        canvas.drawText("SUNSET / RISE", width*0.82f, y2 - 20f, titlePaint)
        valPaint.color = Color.parseColor("#FFCC80"); canvas.drawText("${String.format("%.1f", state.degMagr)}° / ${String.format("%.1f", state.degSunr)}°", width*0.82f, y2 + 10f, valPaint)
    }
}

// --- 5. LIVE WALLPAPER SERVICE ---
class NamazWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = NamazEngine()

    inner class NamazEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var isVisible = false
        private val drawRunner = object : Runnable {
            override fun run() {
                drawFrame()
                if (isVisible) handler.postDelayed(this, 1000)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            DataEngine.start(applicationContext)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) handler.post(drawRunner) else handler.removeCallbacks(drawRunner)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            handler.removeCallbacks(drawRunner)
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) DataEngine.state?.let { DashboardRenderer.draw(canvas, canvas.width, canvas.height, it) }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}

// --- 6. MAIN ACTIVITY UI ---
class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        DataEngine.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        DataEngine.start(this) 

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                
                // Pure Native AndroidView Engine
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        object : View(context) {
                            private val handler = Handler(Looper.getMainLooper())
                            private val drawRunnable = object : Runnable {
                                override fun run() {
                                    invalidate()
                                    handler.postDelayed(this, 1000)
                                }
                            }

                            override fun onAttachedToWindow() {
                                super.onAttachedToWindow()
                                handler.post(drawRunnable)
                            }

                            override fun onDetachedFromWindow() {
                                super.onDetachedFromWindow()
                                handler.removeCallbacks(drawRunnable)
                            }

                            override fun onDraw(canvas: Canvas) {
                                super.onDraw(canvas)
                                DataEngine.state?.let { DashboardRenderer.draw(canvas, width, height, it) }
                            }
                        }
                    }
                )

                Button(
                    onClick = {
                        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, NamazWallpaperService::class.java))
                        }
                        startActivity(intent)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
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
