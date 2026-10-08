package com.akash.aside.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.akash.aside.Prefs
import java.io.File
import kotlin.math.max

/** The user's own picture for the dot, stored as a small PNG inside the app. */
object DotIcon {
    private const val MAX_PX = 256

    private fun file(ctx: Context) = File(ctx.filesDir, "dot_icon.png")

    fun load(ctx: Context): Bitmap? = file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Reads any picture the phone can decode and shrinks it. Null if it could not be read. */
    fun decode(ctx: Context, uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            val big = max(info.size.width, info.size.height)
            if (big > MAX_PX) {
                val scale = MAX_PX.toFloat() / big
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }

    /** Keeps the picture as the dot's icon. Returns false if it could not be read. */
    fun import(ctx: Context, uri: Uri): Boolean {
        val bitmap = decode(ctx, uri) ?: return false
        file(ctx).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Prefs.bumpIconVersion(ctx)
        return true
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        Prefs.bumpIconVersion(ctx)
    }
}
