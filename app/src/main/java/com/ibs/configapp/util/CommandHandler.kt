package com.ibs.configapp.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ibs.configapp.LockScreenActivity
import com.ibs.configapp.UnlockDismissReceiver
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.service.BackgroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object CommandHandler {
    private const val TAG = "CommandHandler"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun normalizeCommand(raw: String): String {
        val compact = raw.trim().lowercase().replace("-", "").replace("_", "")
        return when (compact) {
            "blockapps" -> "block_apps"
            "unblockapps" -> "unblock_apps"
            "blockincoming" -> "block_calls"
            "blockoutgoing" -> "block_calls"
            "unblockcalls" -> "unblock_calls"
            "updatewallpaper", "wallpaper" -> "update_wallpaper"
            else -> raw.trim().lowercase().replace("-", "_")
        }
    }

    fun handle(
        context: Context,
        command: String,
        data: Map<String, Any?>,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val normalized = normalizeCommand(command)
        Log.i(TAG, "Executing command raw=$command normalized=$normalized")

        val runBlock = {
            var success = true
            var asyncCompletion = false
            try {
                when (normalized) {
                    "lock" -> showLockScreen(context)
                    "unlock" -> unlockDevice(context)
                    "release" -> releaseDevice(context)
                    "location" -> {
                        asyncCompletion = true
                        LocationTracker(context).fetchAndSave { locationSaved ->
                            mainHandler.post { onComplete?.invoke(locationSaved) }
                        }
                    }
                    "alert" -> showAlert(context, data)
                    "block_calls" -> {
                        PrefsHelper.setCallsBlocked(context, true)
                        CallBlockManager.setBlocked(context, true)
                    }
                    "unblock_calls" -> {
                        PrefsHelper.setCallsBlocked(context, false)
                        CallBlockManager.setBlocked(context, false)
                    }
                    "block_apps" -> PrefsHelper.setAppsBlocked(context, true)
                    "unblock_apps" -> PrefsHelper.setAppsBlocked(context, false)
                    "update_wallpaper" -> {
                        val url = data["wallpaperUrl"] as? String
                            ?: data["imageUrl"] as? String
                            ?: data["url"] as? String
                        if (!url.isNullOrBlank()) {
                            WallpaperHelper.setFromUrl(context, url)
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unknown command: $command")
                        success = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed command=$normalized", e)
                success = false
            }
            if (!asyncCompletion) {
                onComplete?.invoke(success)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runBlock()
        } else {
            mainHandler.post { runBlock() }
        }
    }

    fun handleFromFirestoreField(context: Context, map: Map<String, Any?>) {
        val isLocked = map["isLocked"] as? Boolean ?: false
        if (isLocked) showLockScreen(context) else unlockDevice(context)
    }

    fun enrichCommandData(context: Context, data: Map<String, Any?>): Map<String, Any?> {
        val commandId = data["commandId"] as? String ?: return data
        if (data["message"] != null) return data
        return try {
            val details = kotlinx.coroutines.runBlocking {
                FirestoreManager.fetchCommandDetails(commandId)
            }
            if (details.isEmpty()) data else data + details
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load command details for $commandId", e)
            data
        }
    }

    private fun showLockScreen(context: Context) {
        PrefsHelper.setLocked(context, true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirestoreManager.updateLockStatus(context, true)
            } catch (_: Exception) {
            }
        }
        val intent = Intent(context, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    fun unlockDevice(context: Context) {
        try {
            PrefsHelper.setLocked(context, false)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    FirestoreManager.updateLockStatus(context, false)
                } catch (_: Exception) {
                }
            }
            LockScreenActivity.dismissIfActive()
            sendUnlockBroadcast(context)
            val dismissIntent = Intent(context, LockScreenActivity::class.java).apply {
                action = LockScreenActivity.ACTION_UNLOCK_DEVICE
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(dismissIntent)
        } catch (e: Exception) {
            Log.e(TAG, "unlockDevice failed", e)
        }
    }

    private fun sendUnlockBroadcast(context: Context) {
        try {
            val unlockIntent = Intent(LockScreenActivity.ACTION_UNLOCK_DEVICE).apply {
                setClass(context, UnlockDismissReceiver::class.java)
            }
            context.sendBroadcast(unlockIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Explicit unlock broadcast failed", e)
        }
        try {
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent(LockScreenActivity.ACTION_UNLOCK_DEVICE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "LocalBroadcastManager unlock failed", e)
        }
        try {
            context.sendBroadcast(
                Intent(LockScreenActivity.ACTION_UNLOCK).apply {
                    setPackage(context.packageName)
                }
            )
            context.sendBroadcast(
                Intent(LockScreenActivity.ACTION_DISMISS_LOCK).apply {
                    setPackage(context.packageName)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Legacy unlock broadcast failed", e)
        }
    }

    private fun releaseDevice(context: Context) {
        PrefsHelper.setLocked(context, false)
        DeviceProtectionManager.releaseDevice(context)
        context.stopService(Intent(context, BackgroundService::class.java))
        val packageUri = android.net.Uri.parse("package:${context.packageName}")
        val uninstall = Intent(Intent.ACTION_DELETE, packageUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(uninstall)
    }

    private fun showAlert(context: Context, data: Map<String, Any?>) {
        val message = data["message"] as? String
            ?: context.getString(com.ibs.configapp.R.string.alert_default_message)
        val title = data["title"] as? String
            ?: context.getString(com.ibs.configapp.R.string.alert_default_title)
        NotificationHelper.showAlertNotification(context, title, message)
    }
}
