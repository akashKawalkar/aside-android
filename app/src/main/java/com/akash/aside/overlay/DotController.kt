package com.akash.aside.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.akash.aside.Prefs
import com.akash.aside.R
import kotlin.math.hypot

/**
 * The floating dot: tap to write, drag to move (it snaps to the nearest edge), drag onto the X to hide it,
 * and it fades when left alone.
 */
class DotController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
    private val onHide: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val size = Ui.dp(ctx, 52)
    private val margin = Ui.dp(ctx, 2)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var view: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var trash: TextView? = null
    private var trashParams: WindowManager.LayoutParams? = null

    private val fade = Runnable { view?.animate()?.alpha(IDLE_ALPHA)?.setDuration(400)?.start() }

    private fun screen() = wm.currentWindowMetrics.bounds

    val isShowing get() = view != null

    fun show() {
        if (view != null) return
        val v = FrameLayout(ctx).apply {
            background = Ui.circle(Ui.ACCENT)
            addView(
                ImageView(ctx).apply { setImageResource(R.drawable.ic_dot_plus) },
                FrameLayout.LayoutParams(Ui.dp(ctx, 26), Ui.dp(ctx, 26), Gravity.CENTER),
            )
            contentDescription = "Quick capture"
        }
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        place(p)
        v.setOnTouchListener(::onTouch)
        wm.addView(v, p)
        view = v
        params = p
        scheduleFade()
    }

    fun remove() {
        handler.removeCallbacks(fade)
        hideTrash()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    /** After rotation the saved edge and height are applied to the new screen size. */
    fun reposition() {
        val p = params ?: return
        place(p)
        runCatching { wm.updateViewLayout(view, p) }
    }

    /** A short pop and a tick: "saved". */
    fun pulse() {
        val v = view ?: return
        v.alpha = 1f
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        v.animate().scaleX(1.35f).scaleY(1.35f).setDuration(110).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
        scheduleFade()
    }

    private fun place(p: WindowManager.LayoutParams) {
        val s = screen()
        p.x = if (Prefs.dotRight(ctx)) s.width() - size - margin else margin
        p.y = (Prefs.dotY(ctx) * (s.height() - size)).toInt().coerceIn(0, s.height() - size)
    }

    private fun scheduleFade() {
        handler.removeCallbacks(fade)
        handler.postDelayed(fade, FADE_AFTER_MS)
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val p = params ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(fade)
                v.animate().cancel()
                v.alpha = 1f
                downX = e.rawX; downY = e.rawY
                startX = p.x; startY = p.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    showTrash()
                }
                if (dragging) {
                    val s = screen()
                    p.x = (startX + dx).toInt().coerceIn(0, s.width() - size)
                    p.y = (startY + dy).toInt().coerceIn(0, s.height() - size)
                    wm.updateViewLayout(v, p)
                    trash?.background = Ui.circle(if (overTrash()) Ui.DANGER else Ui.CHIP)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    onTap()
                } else {
                    val hide = overTrash()
                    hideTrash()
                    if (hide) onHide() else snap()
                }
                scheduleFade()
            }
            MotionEvent.ACTION_CANCEL -> {
                hideTrash()
                if (dragging) snap()
                scheduleFade()
            }
        }
        return true
    }

    private fun snap() {
        val v = view ?: return
        val p = params ?: return
        val s = screen()
        val right = p.x + size / 2 > s.width() / 2
        val targetX = if (right) s.width() - size - margin else margin
        ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 180
            addUpdateListener {
                p.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(v, p) }
            }
            start()
        }
        Prefs.setDot(ctx, right, p.y.toFloat() / (s.height() - size).coerceAtLeast(1))
    }

    // --- the X that appears while dragging ---

    private val trashSize get() = Ui.dp(ctx, 64)
    private val trashBottom get() = Ui.dp(ctx, 72)

    private fun showTrash() {
        if (trash != null) return
        val t = TextView(ctx).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.circle(Ui.CHIP)
        }
        val p = WindowManager.LayoutParams(
            trashSize, trashSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = trashBottom
        }
        wm.addView(t, p)
        trash = t
        trashParams = p
    }

    private fun hideTrash() {
        trash?.let { runCatching { wm.removeView(it) } }
        trash = null
        trashParams = null
    }

    private fun overTrash(): Boolean {
        val p = params ?: return false
        val s = screen()
        val dotCx = p.x + size / 2f
        val dotCy = p.y + size / 2f
        val trashCx = s.width() / 2f
        val trashCy = s.height() - trashBottom - trashSize / 2f
        return hypot(dotCx - trashCx, dotCy - trashCy) < trashSize
    }

    companion object {
        private const val IDLE_ALPHA = 0.4f
        private const val FADE_AFTER_MS = 2500L
    }
}
