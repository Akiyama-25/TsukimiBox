package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import io.nekohasekai.sagernet.R
import java.text.DecimalFormat
import kotlin.math.max

class TrafficBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class BarItem(
        val label: String,        // Bottom label (e.g. "09:15", "03:00", "周三", "10-08")
        val rx: Long,             // Download bytes
        val tx: Long,             // Upload bytes
        val total: Long = rx + tx,// Total bytes
        val payload: Any? = null  // Custom payload for drill-down (e.g. timestamp, date string, slot)
    )

    private val density = resources.displayMetrics.density

    // Fixed bar width and spacing in pixels
    var barWidthPx: Float = 36f * density
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var barSpacingPx: Float = 16f * density
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private var items: List<BarItem> = emptyList()
    var selectedIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var onBarClickListener: ((item: BarItem, index: Int) -> Unit)? = null

    // Colors
    private var colorPrimary: Int
    private var colorAccent: Int
    private var colorBarTrack: Int
    private var colorText: Int
    private var colorSelected: Int

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val barRect = RectF()
    private val trackRect = RectF()
    private val cornerRadius = 4f * density

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val index = findBarIndexAt(e.x, e.y)
            if (index in items.indices) {
                selectedIndex = index
                onBarClickListener?.invoke(items[index], index)
                return true
            }
            return false
        }
    })

    init {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
        colorPrimary = typedValue.data

        context.theme.resolveAttribute(R.attr.colorAccent, typedValue, true)
        colorAccent = typedValue.data

        context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
        colorText = typedValue.data

        // Track background: translucent gray
        colorBarTrack = Color.argb(25, 128, 128, 128)
        colorSelected = colorAccent

        trackPaint.color = colorBarTrack
        trackPaint.style = Paint.Style.FILL

        barPaint.color = colorPrimary
        barPaint.style = Paint.Style.FILL

        selectedPaint.color = colorSelected
        selectedPaint.style = Paint.Style.FILL

        textPaint.color = colorText
        textPaint.textSize = 11f * density
        textPaint.textAlign = Paint.Align.CENTER

        valueTextPaint.color = colorText
        valueTextPaint.textSize = 10f * density
        valueTextPaint.textAlign = Paint.Align.CENTER

        linePaint.color = Color.argb(40, 128, 128, 128)
        linePaint.strokeWidth = 1f * density
    }

    fun setItems(newItems: List<BarItem>) {
        this.items = newItems
        this.selectedIndex = -1
        requestLayout()
        invalidate()
    }

    fun getItems(): List<BarItem> = items

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = if (items.isEmpty()) {
            paddingLeft + paddingRight + (100 * density).toInt()
        } else {
            val contentWidth = items.size * barWidthPx + (items.size - 1) * barSpacingPx
            (paddingLeft + paddingRight + contentWidth).toInt()
        }

        val minHeight = (180f * density).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val resolvedHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> heightSize.coerceAtMost(minHeight)
            else -> minHeight
        }

        setMeasuredDimension(
            resolveSize(totalWidth, widthMeasureSpec),
            resolvedHeight
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val contentTop = paddingTop.toFloat() + 20f * density // Space for top value text
        val contentBottom = height - paddingBottom.toFloat() - 24f * density // Space for bottom label text
        val barMaxHeight = max(10f, contentBottom - contentTop)

        // Find max total to scale bars proportionally
        val maxTotal = items.maxOfOrNull { it.total }?.coerceAtLeast(1L) ?: 1L

        // Draw baseline
        canvas.drawLine(
            paddingLeft.toFloat(),
            contentBottom,
            width - paddingRight.toFloat(),
            contentBottom,
            linePaint
        )

        var startX = paddingLeft.toFloat()

        for (i in items.indices) {
            val item = items[i]
            val endX = startX + barWidthPx
            val isSelected = (i == selectedIndex)

            // Draw track
            trackRect.set(startX, contentTop, endX, contentBottom)
            canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

            // Calculate bar height
            val barHeight = if (item.total > 0) {
                ((item.total.toFloat() / maxTotal.toFloat()) * barMaxHeight).coerceAtLeast(4f * density)
            } else {
                0f
            }

            if (barHeight > 0) {
                val barTop = contentBottom - barHeight
                barRect.set(startX, barTop, endX, contentBottom)
                val paintToUse = if (isSelected) selectedPaint else barPaint
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paintToUse)
            }

            // Draw top value text
            if (item.total > 0) {
                val valueStr = formatTrafficCompact(item.total)
                val textX = (startX + endX) / 2f
                val textY = if (barHeight > 0) {
                    (contentBottom - barHeight - 4f * density).coerceAtLeast(paddingTop.toFloat() + 12f * density)
                } else {
                    contentBottom - 4f * density
                }
                valueTextPaint.color = if (isSelected) colorSelected else colorText
                canvas.drawText(valueStr, textX, textY, valueTextPaint)
            }

            // Draw bottom label text
            val labelX = (startX + endX) / 2f
            val labelY = height - paddingBottom.toFloat() - 6f * density
            textPaint.color = if (isSelected) colorSelected else colorText
            textPaint.isFakeBoldText = isSelected
            canvas.drawText(item.label, labelX, labelY, textPaint)

            startX += barWidthPx + barSpacingPx
        }
    }

    private fun findBarIndexAt(x: Float, y: Float): Int {
        var startX = paddingLeft.toFloat()
        for (i in items.indices) {
            val endX = startX + barWidthPx
            if (x in (startX - barSpacingPx / 2f)..(endX + barSpacingPx / 2f)) {
                return i
            }
            startX += barWidthPx + barSpacingPx
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        private val df1 = DecimalFormat("#.#")
        private val df0 = DecimalFormat("#")

        fun formatTrafficCompact(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            val tb = gb / 1024.0

            return when {
                tb >= 1.0 -> "${df1.format(tb)}T"
                gb >= 1.0 -> "${df1.format(gb)}G"
                mb >= 1.0 -> "${df1.format(mb)}M"
                kb >= 1.0 -> "${df0.format(kb)}K"
                else -> "${bytes}B"
            }
        }
    }
}
