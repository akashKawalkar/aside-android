package com.akash.aside.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
    private val onLongPress: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var size = Ui.dp(ctx, Prefs.SIZE_DEFAULT)

    private var view: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var trash: TextView? = null

    /** The glow color, or null for no glow. The glow is a soft halo shaped like the dot's own picture, drawn behind it. */
    private var ring: Int? = null
    private var glowView: ImageView? = null
    private var breathing: ObjectAnimator? = null

    /** Room around the picture for the halo, only while there is one (the picture itself never moves). */
    private val pad get() = if (ring != null) (size * GLOW_PAD).toInt() else 0

    private val fade = Runnable { if (ring == null) view?.animate()?.alpha(IDLE_ALPHA)?.setDuration(400)?.start() }

    /** Makes the dot glow in this color, or stops the glow with null. While it glows the dot stays at full opacity. */
    fun setRing(color: Int?) {
        val hadGlow = ring != null
        ring = color
        if (view == null) return
        if (hadGlow != (color != null)) refresh() else applyGlow()
    }

    private fun applyGlow() {
        val v = view ?: return
        val c = ring
        breathing?.cancel()
        breathing = null
        if (c == null) {
            scheduleFade()
            return
        }
        glowView?.setImageBitmap(glowBitmap(c))
        handler.removeCallbacks(fade)
        v.animate().cancel()
        v.alpha = 1f
        glowView?.let {
            breathing = ObjectAnimator.ofFloat(it, View.ALPHA, 0.45f, 1f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    /** A blurred, tinted copy of the picture's outline (or of the plain circle), with room for the halo around it. */
    private fun glowBitmap(color: Int): Bitmap {
        val total = size + 2 * pad
        val shape = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sc = Canvas(shape)
        val custom = DotIcon.load(ctx)
        if (custom != null) {
            val k = minOf(size.toFloat() / custom.width, size.toFloat() / custom.height)
            val w = (custom.width * k).toInt()
            val h = (custom.height * k).toInt()
            sc.drawBitmap(custom, null, Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            sc.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.BLACK })
        }
        val offset = IntArray(2)
        val soft = shape.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(pad * 0.8f, BlurMaskFilter.Blur.NORMAL) }, offset)
        val out = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        // Twice, so the halo is strong enough to read on a busy screen yet still soft at its edge.
        repeat(2) { canvas.drawBitmap(soft, (pad + offset[0]).toFloat(), (pad + offset[1]).toFloat(), paint) }
        return out
    }

    /** One soft tick: a threshold was crossed. */
    fun tick() {
        view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun screen() = wm.currentWindowMetrics.bounds

    val isShowing get() = view != null

    fun show() {
        if (view != null) return
        size = Ui.dp(ctx, Prefs.dotSizeDp(ctx))
        val v = buildView()
        val p = WindowManager.LayoutParams(
            size + 2 * pad, size + 2 * pad,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        place(p)
        v.setOnTouchListener(::onTouch)
        wm.addView(v, p)
        view = v
        params = p
        scheduleFade()
        applyGlow()
    }

    private fun buildView(): FrameLayout = FrameLayout(ctx).apply {
        contentDescription = "Quick capture"
        glowView = null
        if (pad > 0) {
            val g = ImageView(ctx)
            glowView = g
            addView(g, FrameLayout.LayoutParams(size + 2 * pad, size + 2 * pad))
        }
        val custom = DotIcon.load(ctx)
        if (custom != null) {
            // The user's own picture, drawn as it is (no circle around it).
            addView(
                ImageView(ctx).apply { setImageBitmap(custom); scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(size, size, Gravity.CENTER),
            )
        } else {
            addView(
                FrameLayout(ctx).apply {
                    background = Ui.circle(Ui.ACCENT)
                    addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_dot_plus) }, FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER))
                },
                FrameLayout.LayoutParams(size, size, Gravity.CENTER),
            )
        }
    }

    fun remove() {
        breathing?.cancel()
        breathing = null
        handler.removeCallbacks(fade)
        handler.removeCallbacks(longPress)
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
        p.x = (Prefs.dotX(ctx) * freeW).toInt().coerceIn(0, freeW) - pad
        p.y = (Prefs.dotY(ctx) * freeH).toInt().coerceIn(0, freeH) - pad
    }

    private fun savePosition() {
        val p = params ?: return
        val s = screen()
        val freeW = (s.width() - size).coerceAtLeast(1)
        val freeH = (s.height() - size).coerceAtLeast(1)
        Prefs.setDotPos(ctx, (p.x + pad).toFloat() / freeW, (p.y + pad).toFloat() / freeH)
    }

    private fun scheduleFade() {
        handler.removeCallbacks(fade)
        if (ring == null) handler.postDelayed(fade, FADE_AFTER_MS)
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        if (!dragging) {
            longPressed = true
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
    }

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
                longPressed = false
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    showTrash()
                }
                if (dragging) {
                    val s = screen()
                    p.x = (startX + dx).toInt().coerceIn(-pad, s.width() - size - pad)
                    p.y = (startY + dy).toInt().coerceIn(-pad, s.height() - size - pad)
                    wm.updateViewLayout(v, p)
                    trash?.background = Ui.circle(if (overTrash()) Ui.DANGER else Ui.CHIP)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (longPressed) {
                    // the long press already opened the voice window
                } else if (!dragging) {
                    onTap()
                } else {
                    val hide = overTrash()
                    hideTrash()
                    if (hide) onHide() else savePosition()
                }
                scheduleFade()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
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
        val dotCx = p.x + pad + size / 2f
        val dotCy = p.y + pad + size / 2f
        val trashCx = s.width() / 2f
        val trashCy = s.height() - trashBottom - trashSize / 2f
        return hypot(dotCx - trashCx, dotCy - trashCy) < trashSize
    }

    companion object {
        private const val GLOW_PAD = 0.3f          // halo room, as a share of the dot size
        private const val IDLE_ALPHA = 0.4f
        private const val FADE_AFTER_MS = 2500L
    }
}
