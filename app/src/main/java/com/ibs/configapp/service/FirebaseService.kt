package com.ibs.configapp.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.util.CommandHandler
import com.ibs.configapp.util.NotificationHelper
import com.ibs.configapp.util.PrefsHelper
import com.ibs.configapp.util.WakeLockHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FirebaseService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        try {
            NotificationHelper.createChannel(this)
        } catch (e: Exception) {
            Log.w(TAG, "FCM channel create failed", e)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            PrefsHelper.setFcmToken(this, token)
            if (PrefsHelper.isActivated(this)) {
                serviceScope.launch {
                    try {
                        FirestoreManager.updateFcmToken(this@FirebaseService, token)
                    } catch (e: Exception) {
                        Log.e(TAG, "FCM token update failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNewToken failed", e)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val commandForLog = message.data["command"] ?: message.data["action"]
        Log.i(
            TAG,
            "FCM received priority=${message.priority} command=$commandForLog from=${message.from}"
        )
        try {
            WakeLockHelper.withWakeLock(this, "ibs:fcm", 120_000L) {
                processMessage(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onMessageReceived failed", e)
        }
    }

    private fun processMessage(message: RemoteMessage) {
        if (!PrefsHelper.isActivated(this)) {
            Log.w(TAG, "Device not activated, ignoring FCM")
            return
        }

        try {
            BackgroundService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "BackgroundService start from FCM failed", e)
        }

        val command = message.data["command"]
            ?: message.data["action"]
            ?: message.notification?.body?.let { parseCommandFromBody(it) }

        if (command.isNullOrBlank()) {
            Log.w(TAG, "FCM message has no command, ignoring")
            return
        }

        val data = message.data.mapValues { it.value as Any? }.toMutableMap()
        val commandId = data["commandId"] as? String

        if (CommandHandler.normalizeCommand(command) == "unlock") {
            BackgroundService.beginUnlock()
            CommandHandler.unlockDevice(this, data, onComplete = { success ->
                serviceScope.launch {
                    try {
                        FirestoreManager.markCommandExecuted(
                            this@FirebaseService,
                            commandId,
                            success,
                            if (success) "completed" else null
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark unlock command executed", e)
                    }
                }
            })
            return
        }

        if (CommandHandler.normalizeCommand(command) == "release") {
            Log.i(TAG, "Release command ignored in FCM; handled by deviceCommands listener")
            BackgroundService.start(this)
            return
        }

        val normalizedCommand = when (command.trim().lowercase()) {
            "location", "getlocation", "get_location" -> "get_location"
            "blockincoming", "block_incoming" -> "block_incoming"
            "blockoutgoing", "block_outgoing" -> "block_outgoing"
            "unblockcalls", "unblock_calls" -> "unblock_calls"
            "blockapps", "block_apps" -> "block_apps"
            "unblockapps", "unblock_apps" -> "unblock_apps"
            else -> command
        }

        try {
            CommandHandler.handle(this, normalizedCommand, data, onComplete = { success ->
                serviceScope.launch {
                    try {
                        FirestoreManager.markCommandExecuted(
                            this@FirebaseService,
                            commandId,
                            success
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark FCM command executed", e)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "CommandHandler failed for command=$command", e)
            serviceScope.launch {
                try {
                    FirestoreManager.markCommandExecuted(this@FirebaseService, commandId, false)
                } catch (markError: Exception) {
                    Log.e(TAG, "Failed to mark FCM command failed", markError)
                }
            }
        }
    }

    private fun parseCommandFromBody(body: String): String? {
        val normalized = body.trim().lowercase()
        return when {
            normalized.contains("lock") && !normalized.contains("unlock") -> "lock"
            normalized.contains("unlock") -> "unlock"
            normalized.contains("release") -> "release"
            normalized.contains("location") -> "location"
            normalized.contains("alert") -> "alert"
            normalized.contains("block") && normalized.contains("app") -> "blockApps"
            normalized.contains("unblock") && normalized.contains("app") -> "unblockApps"
            else -> null
        }
    }

    companion object {
        private const val TAG = "FirebaseService"
    }
}
