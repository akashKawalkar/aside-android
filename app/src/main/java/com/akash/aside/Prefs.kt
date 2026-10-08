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

    /** Opening the app asks for fingerprint / PIN. The dot and the input window are never locked. */
    fun lockEnabled(c: Context) = sp(c).getBoolean("lock_enabled", true)
    fun setLockEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("lock_enabled", v).apply()

    // --- app usage tracking ---
    /** On by default, but nothing is read until the phone's "Usage access" is granted. */
    fun usageEnabled(c: Context) = sp(c).getBoolean("usage_enabled", true)
    fun setUsageEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("usage_enabled", v).apply()

    /** Where the last read stopped; 0 means "start of today". */
    fun usageCursor(c: Context) = sp(c).getLong("usage_cursor", 0L)
    fun setUsageCursor(c: Context, v: Long) = sp(c).edit().putLong("usage_cursor", v).apply()

    /** Apps never counted (banking, by default). Null until the list is first set up. */
    fun ignoredApps(c: Context): Set<String>? = sp(c).getStringSet("ignored_apps", null)
    fun setIgnoredApps(c: Context, v: Set<String>) = sp(c).edit().putStringSet("ignored_apps", v).apply()

    // --- cloud sign-in (kept in the app's private storage) and the last sync result ---
    class Session(val access: String, val refresh: String, val expiresAt: Long, val email: String)

    fun session(c: Context): Session? {
        val p = sp(c)
        val refresh = p.getString("refresh_token", null) ?: return null
        return Session(p.getString("access_token", "").orEmpty(), refresh, p.getLong("expires_at", 0L), p.getString("email", "").orEmpty())
    }

    fun setSession(c: Context, s: Session?) {
        val e = sp(c).edit()
        if (s == null) e.remove("access_token").remove("refresh_token").remove("expires_at").remove("email")
        else e.putString("access_token", s.access).putString("refresh_token", s.refresh).putLong("expires_at", s.expiresAt).putString("email", s.email)
        e.apply()
    }

    fun lastSyncAt(c: Context) = sp(c).getLong("last_sync_at", 0L)
    fun lastSyncError(c: Context) = sp(c).getString("last_sync_error", null)
    fun setSyncResult(c: Context, at: Long, error: String?) =
        sp(c).edit().putLong("last_sync_at", at).apply { if (error == null) remove("last_sync_error") else putString("last_sync_error", error) }.apply()
}
