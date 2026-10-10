package io.github.haohaoo3o.tracklab.ui.matrix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import io.github.haohaoo3o.tracklab.R
import java.util.Random

/**
 * Matrix 数字雨（极客风装饰，不影响回放；默认关闭）。
 *
 * - 仅 View/Canvas/Handler 实现，不新增 Gradle 依赖，不引入
 *   ConstraintLayout/Material/Compose（docs/CONTRACTS.md §2）；
 * - 字段复用：Paint/Random/yOffsets/Handler/Ticker 均为字段，onDraw 零分配；
 * - Canvas 逐列画 01（列宽 32px、30fps/33ms、颜色 #6E9B8A 低饱和，01 交替低耗电）；
 * - isClickable=false + isFocusable=false（不吞 map_container 触摸）；
 * - 仅主界面 STARTED 且用户显式开启才投递帧；STOPPED/熄屏/DESTROYED/onDetachedFromWindow
 *   一律 removeCallbacks；contentDescription 走 matrix_rain_toggle_on/off。
 */
class MatrixRainView : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private val paint = Paint().apply {
        color = 0xFF6E9B8A.toInt()
        textSize = 28f
        isAntiAlias = false
    }
    private val rnd = Random(0x5EEDL)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var yOffsets = IntArray(0)

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            advance()
            invalidate()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    init {
        isClickable = false
        isFocusable = false
    }

    /** 开关（默认关）。开→投递帧；关→removeCallbacks 停帧。调用方在 STARTED 开、STOPPED/DESTROYED 关。 */
    fun setRainEnabled(enabled: Boolean) {
        if (enabled == running) return
        running = enabled
        contentDescription = context.getString(
            if (enabled) R.string.matrix_rain_toggle_on else R.string.matrix_rain_toggle_off,
        )
        handler.removeCallbacks(ticker)
        if (running) handler.post(ticker)
    }

    private fun advance() {
        val height = height.coerceAtLeast(1)
        val cols = (width / COLUMN_PX).coerceAtLeast(1)
        if (yOffsets.size != cols) yOffsets = IntArray(cols) { rnd.nextInt(height) }
        for (i in yOffsets.indices) {
            yOffsets[i] = (yOffsets[i] + COLUMN_PX) % height
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 字段复用：无 new 分配；字符取 01 交替（低耗电，不画片假名大表）。
        var x = 0f
        var i = 0
        while (x < width && i < yOffsets.size) {
            canvas.drawText(if ((i + yOffsets[i]) % 2 == 0) "1" else "0", x, yOffsets[i].toFloat(), paint)
            x += COLUMN_PX.toFloat()
            i++
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    companion object {
        /** 列宽（px）。 */
        private const val COLUMN_PX = 32

        /** 帧间隔（ms，30fps）。 */
        private const val FRAME_MS = 33L
    }
}
