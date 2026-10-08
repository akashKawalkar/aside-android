package com.akash.aside.overlay

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
 * The floating dot: tap to write, drag to put it anywhere (it stays exactly where it is dropped), drag it onto the X to
 * hide it. It fades when left alone. Its size and picture come from the settings.
 */
class DotController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
    private val onHide: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var size = Ui.dp(ctx, Prefs.SIZE_DEFAULT)

    private var view: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var trash: TextView? = null

    private val fade = Runnable { view?.animate()?.alpha(IDLE_ALPHA)?.setDuration(400)?.start() }

    private fun screen() = wm.currentWindowMetrics.bounds

    val isShowing get() = view != null

    fun show() {
        if (view != null) return
        size = Ui.dp(ctx, Prefs.dotSizeDp(ctx))
        val v = buildView()
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

    private fun buildView(): FrameLayout = FrameLayout(ctx).apply {
        contentDescription = "Quick capture"
        val custom = DotIcon.load(ctx)
        if (custom != null) {
            // The user's own picture, drawn as it is (no circle around it).
            addView(
                ImageView(ctx).apply { setImageBitmap(custom); scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(size, size),
            )
        } else {
            background = Ui.circle(Ui.ACCENT)
            addView(
                ImageView(ctx).apply { setImageResource(R.drawable.ic_dot_plus) },
                FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER),
            )
        }
    }

    fun remove() {
        handler.removeCallbacks(fade)
        hideTrash()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    /** Size or picture changed in the settings: draw it again in place. */
    fun refresh() {
        if (view == null) return
        remove()
        show()
    }

    /** After rotation the saved position is applied to the new screen size. */
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
        val freeW = (s.width() - size).coerceAtLeast(1)
        val freeH = (s.height() - size).coerceAtLeast(1)
        p.x = (Prefs.dotX(ctx) * freeW).toInt().coerceIn(0, freeW)
        p.y = (Prefs.dotY(ctx) * freeH).toInt().coerceIn(0, freeH)
    }

    private fun savePosition() {
        val p = params ?: return
        val s = screen()
        val freeW = (s.width() - size).coerceAtLeast(1)
        val freeH = (s.height() - size).coerceAtLeast(1)
        Prefs.setDotPos(ctx, p.x.toFloat() / freeW, p.y.toFloat() / freeH)
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
                    if (hide) onHide() else savePosition()
                }
                scheduleFade()
            }
            MotionEvent.ACTION_CANCEL -> {
                hideTrash()
                if (dragging) savePosition()
                scheduleFade()
            }
        }
        return true
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
    }

    private fun hideTrash() {
        trash?.let { runCatching { wm.removeView(it) } }
        trash = null
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
