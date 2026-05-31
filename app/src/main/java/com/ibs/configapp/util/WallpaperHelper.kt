package com.ibs.configapp.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

object WallpaperHelper {

    fun setFromUrl(context: Context, url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.getInputStream().use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream) ?: return@launch
                    withContext(Dispatchers.Main) {
                        val wm = WallpaperManager.getInstance(context.applicationContext)
                        wm.setBitmap(bitmap)
                    }
                }
            } catch (_: Exception) { }
        }
    }
}
