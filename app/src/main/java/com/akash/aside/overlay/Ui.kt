package com.akash.aside.overlay

import android.content.Context
import android.graphics.drawable.GradientDrawable

/** Small drawing helpers for the overlay, which is plain Views (Compose inside a service is not worth the trouble). */
object Ui {
    val CARD = 0xFF1E1F24.toInt()
    val CHIP = 0xFF2E3036.toInt()
    val TEXT = 0xFFE8E8EA.toInt()
    val MUTED = 0xFFA0A3A8.toInt()
    val ACCENT = 0xFF8AB4F8.toInt()
    val ON_ACCENT = 0xFF0B1B33.toInt()
    val SCRIM = 0x99000000.toInt()
    val DANGER = 0xFFF28B82.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

    fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(ctx, 24)
    }
}
