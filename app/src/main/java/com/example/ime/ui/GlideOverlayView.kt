package com.example.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.ime.model.TouchPoint

/**
 * グライド入力中の軌跡を描画する透過オーバーレイビュー
 */
class GlideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val points = mutableListOf<TouchPoint>()

    private val trailPaint = Paint().apply {
        color = Color.parseColor("#4285F4") // Google Blue
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        alpha = 200
    }

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#1A73E8")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    init {
        // フォーカスを奪わないように設定
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun onTouchDown(x: Float, y: Float) {
        points.clear()
        path.reset()
        val p = TouchPoint(x, y)
        points.add(p)
        path.moveTo(x, y)
        invalidate()
    }

    fun onTouchMove(x: Float, y: Float) {
        val p = TouchPoint(x, y)
        points.add(p)
        path.lineTo(x, y)
        invalidate()
    }

    fun onTouchUp() {
        clearTrail()
    }

    fun clearTrail() {
        points.clear()
        path.reset()
        invalidate()
    }

    fun getStrokePoints(): List<TouchPoint> {
        return points.toList()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 親の制約に従い、親が wrap_content の場合でも巨大化しないようにサイズを決定
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        // 軌跡パスを描画
        canvas.drawPath(path, trailPaint)

        // 始点と最新点に丸を描画
        if (points.isNotEmpty()) {
            val start = points.first()
            canvas.drawCircle(start.x, start.y, 10f, pointPaint)
            val current = points.last()
            canvas.drawCircle(current.x, current.y, 10f, pointPaint)
        }
    }
}
