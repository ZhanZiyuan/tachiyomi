package eu.kanade.tachiyomi.ui.reader.narration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/** An in-reader overlay: no draw-over-other-apps permission or background service is needed. */
class NarrationFloatingControl(
    private val activity: ReaderActivity,
    private val controller: NarrationController,
    private val container: FrameLayout,
) : View(activity) {
    private val preferences = context.getSharedPreferences("narration_floating_control", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var current = controller.state.value
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var positioned = false
    private var lastWidth = 0
    private var lastHeight = 0

    init {
        visibility = if (current.enabled) VISIBLE else GONE
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setOnClickListener { controller.toggleScrollPlayback() }
        container.addView(this, FrameLayout.LayoutParams((76 * density).toInt(), (76 * density).toInt()))
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!positioned || container.width != lastWidth || container.height != lastHeight) restorePosition()
        }
        post { restorePosition() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { state ->
                    current = state
                    visibility = if (state.enabled) VISIBLE else GONE
                    val action = if (!state.preparingChapter && !state.chapterReady) "Retry chapter preparation" else if (state.paused) "Resume narration" else "Pause narration"
                    contentDescription = "AI narration, " + state.chapterProgress + " percent prepared. " + action + ". Drag to move. " + state.message
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = width / 2f
        val radius = width / 2f - 7 * density
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(30, 40, 59)
        paint.setShadowLayer(4 * density, 0f, 2 * density, 0x66000000)
        canvas.drawCircle(center, height / 2f, radius, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * density
        paint.strokeCap = Paint.Cap.ROUND
        val ring = RectF(center - radius, height / 2f - radius, center + radius, height / 2f + radius)
        paint.color = 0x446ED2FF
        canvas.drawArc(ring, -90f, 360f, false, paint)
        paint.color = if (current.chapterReady) Color.rgb(113, 225, 171) else Color.rgb(110, 210, 255)
        canvas.drawArc(ring, -90f, current.chapterProgress * 3.6f, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val iconY = height * 0.40f
        if (!current.preparingChapter && !current.chapterReady) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 24 * density
            paint.isFakeBoldText = true
            canvas.drawText("!", center, iconY + 7 * density, paint)
        } else if (current.paused) {
            val triangle = Path().apply {
                moveTo(center - 5 * density, iconY - 8 * density)
                lineTo(center + 8 * density, iconY)
                lineTo(center - 5 * density, iconY + 8 * density)
                close()
            }
            canvas.drawPath(triangle, paint)
        } else {
            canvas.drawRoundRect(center - 7 * density, iconY - 8 * density, center - 2 * density, iconY + 8 * density, density, density, paint)
            canvas.drawRoundRect(center + 2 * density, iconY - 8 * density, center + 7 * density, iconY + 8 * density, density, density, paint)
        }
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 13 * density
        paint.isFakeBoldText = true
        val label = if (!current.preparingChapter && !current.chapterReady) "Retry" else current.chapterProgress.toString() + "%"
        canvas.drawText(label, center, height * 0.72f, paint)
        paint.isFakeBoldText = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = x
                startY = y
                dragging = false
                parent.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (dragging || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    dragging = true
                    val bounds = movementBounds()
                    x = (startX + dx).coerceIn(bounds.left, bounds.right)
                    y = (startY + dy).coerceIn(bounds.top, bounds.bottom)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (dragging) savePosition() else performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (dragging) savePosition()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) post { restorePosition() }
    }

    private fun movementBounds(): RectF {
        val insets = ViewCompat.getRootWindowInsets(container)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val margin = 4 * density
        val left = (insets?.left ?: 0) + margin
        val top = (insets?.top ?: 0) + margin
        val right = (container.width - width - (insets?.right ?: 0) - margin).coerceAtLeast(left)
        val bottom = (container.height - height - (insets?.bottom ?: 0) - margin).coerceAtLeast(top)
        return RectF(left, top, right, bottom)
    }

    private fun restorePosition() {
        if (container.width <= 0 || container.height <= 0 || width <= 0) return
        val bounds = movementBounds()
        x = bounds.left + preferences.getFloat("x", 0.95f).coerceIn(0f, 1f) * bounds.width()
        y = bounds.top + preferences.getFloat("y", 0.65f).coerceIn(0f, 1f) * bounds.height()
        lastWidth = container.width
        lastHeight = container.height
        positioned = true
    }

    private fun savePosition() {
        val bounds = movementBounds()
        val fractionX = if (bounds.width() > 0) (x - bounds.left) / bounds.width() else 0f
        val fractionY = if (bounds.height() > 0) (y - bounds.top) / bounds.height() else 0f
        preferences.edit().putFloat("x", fractionX.coerceIn(0f, 1f)).putFloat("y", fractionY.coerceIn(0f, 1f)).apply()
    }
}
