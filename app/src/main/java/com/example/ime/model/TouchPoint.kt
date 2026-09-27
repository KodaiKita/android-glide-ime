package com.example.ime.model

import android.graphics.PointF

data class TouchPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toPointF(): PointF = PointF(x, y)

    fun distanceTo(other: TouchPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
