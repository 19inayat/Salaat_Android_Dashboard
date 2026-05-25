package com.optimizer.namaz

import java.util.Date

data class Segment(

    val name: String,

    val start: Double,

    val end: Double,

    val color1: Int,

    val color2: Int
)

data class NamazState(

    val now: Date,

    val currentDec: Double,

    val currentSegment: Segment?,

    val segments: List<Segment>,

    val timerStr: String
)
