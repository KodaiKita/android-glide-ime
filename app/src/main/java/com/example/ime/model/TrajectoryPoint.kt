package com.example.ime.model

/**
 * 時空間統一サンプル点
 * 幾何（位置・形状）と運動学（時間・速度・曲率）を完全に保持する
 */
data class TrajectoryPoint(
    val x: Float,          // 空間 X 座標 (px)
    val y: Float,          // 空間 Y 座標 (px)
    val t: Long,           // 補間された時刻 (ms)
    val dt: Float = 0f,    // この区間の所要時間・滞留時間 (ms)
    val speed: Float = 0f, // 局所速度 (px/ms)
    val curvature: Float = 0f // 曲率・方向変化 (rad)
)
