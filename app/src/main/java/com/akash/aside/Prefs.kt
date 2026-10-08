package com.akash.aside

import android.content.Context

/** Tiny settings that outlive the process. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("aside", Context.MODE_PRIVATE)

    /** The user wants the floating dot (set on the setup screen; a drag-to-hide does not change it). */
    fun wantDot(c: Context) = sp(c).getBoolean("want_dot", false)
    fun setWantDot(c: Context, v: Boolean) = sp(c).edit().putBoolean("want_dot", v).apply()

    fun dotRight(c: Context) = sp(c).getBoolean("dot_right", true)
    fun dotY(c: Context) = sp(c).getFloat("dot_y", 0.4f)
    fun setDot(c: Context, right: Boolean, yFraction: Float) =
        sp(c).edit().putBoolean("dot_right", right).putFloat("dot_y", yFraction).apply()
}
