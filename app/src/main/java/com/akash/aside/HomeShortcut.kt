package com.akash.aside

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon

/**
 * Android does not let an app swap its own launcher icon for a picture. What it can do is ask the launcher to put a
 * shortcut with any picture on the home screen. That is the closest thing to "my own app icon".
 */
object HomeShortcut {
    fun supported(ctx: Context) = ctx.getSystemService(ShortcutManager::class.java).isRequestPinShortcutSupported

    fun pin(ctx: Context, picture: Bitmap): Boolean {
        val sm = ctx.getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) return false
        val info = ShortcutInfo.Builder(ctx, "home_${System.currentTimeMillis()}")
            .setShortLabel("Aside")
            .setIcon(Icon.createWithBitmap(picture))
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
        return sm.requestPinShortcut(info, null)
    }
}
