package com.optimizer.namaz

import android.graphics.Color
import java.util.*

object DataEngine {

    @Volatile
    var state: NamazState? = null

    fun update() {

        val now = Date()

        val cal = Calendar.getInstance()

        val currentDec =
            cal.get(Calendar.HOUR_OF_DAY) +
                    cal.get(Calendar.MINUTE) / 60.0 +
                    cal.get(Calendar.SECOND) / 3600.0

        val fajr = 5.0
        val sunrise = 6.3
        val dhuhr = 12.5
        val asr = 16.0
        val maghrib = 18.4
        val isha = 19.5

        val segments = listOf(

            Segment(
                "FAJR",
                fajr,
                sunrise,
                Color.parseColor("#FFE082"),
                Color.parseColor("#FFB300")
            ),

            Segment(
                "ZUHR",
                dhuhr,
                asr,
                Color.parseColor("#4FC3F7"),
                Color.parseColor("#0288D1")
            ),

            Segment(
                "ASR",
                asr,
                maghrib,
                Color.parseColor("#FF8A65"),
                Color.parseColor("#E64A19")
            ),

            Segment(
                "ISHA",
                isha,
                fajr + 24,
                Color.parseColor("#5C6BC0"),
                Color.parseColor("#1A237E")
            )
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

        val rem =
            (currentSeg?.end ?: 0.0) - checkTime

        val h = rem.toInt()

        val m = ((rem - h) * 60).toInt()

        val s =
            ((((rem - h) * 60) - m) * 60).toInt()

        val timer =
            String.format(
                "%02d:%02d:%02d",
                h,
                m,
                s
            )

        state =
            NamazState(
                now,
                currentDec,
                currentSeg,
                segments,
                timer
            )
    }
}
