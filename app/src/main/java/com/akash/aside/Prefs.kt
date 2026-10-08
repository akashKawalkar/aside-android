package com.akash.aside

import android.content.Context
import android.content.SharedPreferences

/** Tiny settings that outlive the process. */
object Prefs {
    const val KEY_DOT_SIZE = "dot_size_dp"
    const val KEY_ICON_VERSION = "icon_version"
    const val SIZE_MIN = 32
    const val SIZE_MAX = 96
    const val SIZE_DEFAULT = 52

    fun raw(c: Context): SharedPreferences = c.getSharedPreferences("aside", Context.MODE_PRIVATE)
    private fun sp(c: Context) = raw(c)

    /** The user wants the floating dot (set on the setup screen; a drag-to-hide does not change it). */
    fun wantDot(c: Context) = sp(c).getBoolean("want_dot", false)
    fun setWantDot(c: Context, v: Boolean) = sp(c).edit().putBoolean("want_dot", v).apply()

    /** Where the dot sits, as a fraction of the free screen space (so it stays put when the screen rotates). */
    fun dotX(c: Context) = sp(c).getFloat("dot_x", 1f)
    fun dotY(c: Context) = sp(c).getFloat("dot_y", 0.4f)
    fun setDotPos(c: Context, x: Float, y: Float) = sp(c).edit().putFloat("dot_x", x).putFloat("dot_y", y).apply()

    fun dotSizeDp(c: Context) = sp(c).getInt(KEY_DOT_SIZE, SIZE_DEFAULT).coerceIn(SIZE_MIN, SIZE_MAX)
    fun setDotSizeDp(c: Context, v: Int) = sp(c).edit().putInt(KEY_DOT_SIZE, v.coerceIn(SIZE_MIN, SIZE_MAX)).apply()

    /** Bumped whenever the custom icon file changes, so a running service knows to redraw. */
    fun iconVersion(c: Context) = sp(c).getInt(KEY_ICON_VERSION, 0)
    fun bumpIconVersion(c: Context) = sp(c).edit().putInt(KEY_ICON_VERSION, iconVersion(c) + 1).apply()
}
