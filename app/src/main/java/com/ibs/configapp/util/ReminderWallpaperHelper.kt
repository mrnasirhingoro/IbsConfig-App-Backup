package com.ibs.configapp.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.net.URL

object ReminderWallpaperHelper {

    private const val TAG = "ReminderWallpaperHelper"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val FALLBACK_WALLPAPER_COLOR = 0xFF2B2B2B.toInt()

    fun setFromUrl(context: Context, imageUrl: String?): Boolean {
        if (imageUrl.isNullOrBlank()) {
            Log.w(TAG, "setFromUrl: imageUrl missing")
            return false
        }
        val bitmap = downloadBitmap(imageUrl) ?: return false
        return applyWallpaper(context, bitmap)
    }

    fun clearReminderWallpaper(context: Context): Boolean {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                wallpaperManager.clear(
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
            } else {
                val fallback = createSolidColorBitmap(FALLBACK_WALLPAPER_COLOR)
                try {
                    applyWallpaper(context, fallback)
                } finally {
                    if (!fallback.isRecycled) {
                        fallback.recycle()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "clearReminderWallpaper failed", e)
            false
        }
    }

    private fun downloadBitmap(imageUrl: String): Bitmap? {
        return try {
            val connection = URL(imageUrl).openConnection()
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.getInputStream().use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadBitmap failed url=$imageUrl", e)
            null
        }
    }

    private fun applyWallpaper(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
            } else {
                @Suppress("DEPRECATION")
                wallpaperManager.setBitmap(bitmap)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyWallpaper failed", e)
            false
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun createSolidColorBitmap(color: Int): Bitmap {
        return Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565).apply {
            eraseColor(color)
        }
    }
}
