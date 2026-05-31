package com.ibs.configapp.util

import android.content.Context
import android.os.PowerManager
import android.util.Log

object WakeLockHelper {
    private const val TAG = "WakeLockHelper"

    fun withWakeLock(context: Context, tag: String, timeoutMs: Long, block: () -> Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
        }
        try {
            wakeLock.acquire(timeoutMs)
            block()
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock block failed for $tag", e)
            throw e
        } finally {
            if (wakeLock.isHeld) {
                try {
                    wakeLock.release()
                } catch (e: Exception) {
                    Log.w(TAG, "WakeLock release failed for $tag", e)
                }
            }
        }
    }
}
