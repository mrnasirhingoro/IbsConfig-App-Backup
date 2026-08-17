package com.ibs.configapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.ibs.configapp.service.BackgroundService
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.util.CallBlockManager
import com.ibs.configapp.util.CommandHandler
import com.ibs.configapp.util.DeviceOwnerHelper
import com.ibs.configapp.util.PrefsHelper
import com.ibs.configapp.util.WakeLockHelper
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()
        Thread {
            try {
                WakeLockHelper.withWakeLock(context.applicationContext, "ibs:sms-command", 120_000L) {
                    processIncomingSms(context.applicationContext, intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS command receiver failed", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun processIncomingSms(context: Context, intent: Intent) {
        try {
            if (!DeviceOwnerHelper.isDeviceOwner(context)) return
            if (!PrefsHelper.isActivated(context)) return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val deviceSecretCode = prefs.getString(KEY_DEVICE_SECRET_CODE, null)?.trim()
            val masterNumber = prefs.getString(KEY_MASTER_NUMBER, null)?.trim()
            val authorizedNumbersRaw = prefs.getString(KEY_AUTHORIZED_NUMBERS, null)?.trim()

            if (deviceSecretCode.isNullOrBlank()) return
            if (masterNumber.isNullOrBlank() && authorizedNumbersRaw.isNullOrBlank()) return

            val authorizedNumbers = parseAuthorizedNumbers(authorizedNumbersRaw)
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (messages.isEmpty()) return

            val sender = messages[0].originatingAddress ?: return
            if (!isAuthorizedSender(sender, masterNumber, authorizedNumbers)) return

            val body = messages.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            if (body.isBlank()) return

            handleAuthorizedSms(context, sender, body, deviceSecretCode)
        } catch (e: Exception) {
            Log.e(TAG, "processIncomingSms failed", e)
        }
    }

    private fun handleAuthorizedSms(
        context: Context,
        sender: String,
        body: String,
        deviceSecretCode: String
    ) {
        try {
            val parts = body.trim().split("#", limit = 2)
            if (parts.size < 2) {
                sendSms(context, sender, "Invalid code")
                return
            }

            val command = parts[0].trim().uppercase()
            val code = parts[1].trim()
            if (code != deviceSecretCode) {
                sendSms(context, sender, "Invalid code")
                return
            }

            try {
                BackgroundService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "BackgroundService start from SMS failed", e)
            }

            when (val result = executeCommand(context, command)) {
                CommandExecutionResult.UNKNOWN -> sendSms(context, sender, "Unknown command")
                CommandExecutionResult.SUCCESS ->
                    sendSms(context, sender, "$command executed successfully")
                CommandExecutionResult.FAILED ->
                    Log.w(TAG, "SMS command failed command=$command")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAuthorizedSms failed", e)
        }
    }

    private fun executeCommand(context: Context, command: String): CommandExecutionResult {
        return try {
            when (command) {
                "LOCK" -> awaitCommandResult { callback ->
                    CommandHandler.handle(
                        context,
                        "lock",
                        emptyMap(),
                        callback,
                        reportLocalSuccessOnly = true
                    )
                }
                "UNLOCK" -> awaitCommandResult { callback ->
                    BackgroundService.beginUnlock()
                    CommandHandler.unlockDevice(
                        context,
                        emptyMap(),
                        callback,
                        reportLocalSuccessOnly = true
                    )
                }
                "LOCATION" -> awaitCommandResult { callback ->
                    CommandHandler.handle(context, "location", emptyMap(), callback)
                }
                "CALLBLOCKIN" -> awaitCommandResult { callback ->
                    CommandHandler.handle(context, "block_incoming", emptyMap(), callback)
                }
                "CALLUNBLOCKIN" -> {
                    CallBlockManager.setIncomingBlocked(context, false)
                    PrefsHelper.setIncomingCallsBlocked(context, false)
                    syncCallBlockStatusToFirestore(context)
                }
                "CALLBLOCKOUT" -> awaitCommandResult { callback ->
                    CommandHandler.handle(context, "block_outgoing", emptyMap(), callback)
                }
                "CALLUNBLOCKOUT" -> {
                    CallBlockManager.setOutgoingBlocked(context, false)
                    PrefsHelper.setOutgoingCallsBlocked(context, false)
                    syncCallBlockStatusToFirestore(context)
                }
                "SOCIALBLOCK" -> awaitCommandResult { callback ->
                    CommandHandler.handle(context, "block_apps", emptyMap(), callback)
                }
                "SOCIALUNBLOCK" -> awaitCommandResult { callback ->
                    CommandHandler.handle(context, "unblock_apps", emptyMap(), callback)
                }
                "RELEASE" -> {
                    BackgroundService.executeRelease(context)
                    CommandExecutionResult.SUCCESS
                }
                else -> CommandExecutionResult.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeCommand failed command=$command", e)
            CommandExecutionResult.FAILED
        }
    }

    private fun syncCallBlockStatusToFirestore(context: Context): CommandExecutionResult {
        return awaitCommandResult { callback ->
            CoroutineScope(Dispatchers.IO).launch {
                val success = try {
                    FirestoreManager.updateCallBlockStatus(
                        context,
                        incomingBlocked = PrefsHelper.isIncomingCallsBlocked(context),
                        outgoingBlocked = PrefsHelper.isOutgoingCallsBlocked(context)
                    )
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "SMS call-block Firestore sync failed", e)
                    false
                }
                callback(success)
            }
        }
    }

    private fun awaitCommandResult(
        invoke: (callback: (Boolean) -> Unit) -> Unit
    ): CommandExecutionResult {
        val latch = CountDownLatch(1)
        var success = false
        invoke { result ->
            success = result
            latch.countDown()
        }
        return try {
            if (!latch.await(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "SMS command timed out")
                CommandExecutionResult.FAILED
            } else if (success) {
                CommandExecutionResult.SUCCESS
            } else {
                CommandExecutionResult.FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "awaitCommandResult failed", e)
            CommandExecutionResult.FAILED
        }
    }

    private fun parseAuthorizedNumbers(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            try {
                val array = JSONArray(trimmed)
                return buildList {
                    for (index in 0 until array.length()) {
                        val value = array.optString(index)?.trim()
                        if (!value.isNullOrBlank()) add(value)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse authorized_numbers JSON", e)
            }
        }
        return trimmed.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun isAuthorizedSender(
        sender: String,
        masterNumber: String?,
        authorizedNumbers: List<String>
    ): Boolean {
        val normalizedSender = normalizePhoneNumber(sender)
        if (normalizedSender.isBlank()) return false

        if (!masterNumber.isNullOrBlank()) {
            if (normalizedSender == normalizePhoneNumber(masterNumber)) return true
        }

        return authorizedNumbers.any { number ->
            normalizedSender == normalizePhoneNumber(number)
        }
    }

    private fun normalizePhoneNumber(number: String): String {
        val digitsOnly = number.replace(Regex("\\D"), "")
        if (digitsOnly.isEmpty()) return ""
        return if (digitsOnly.length <= 10) {
            digitsOnly
        } else {
            digitsOnly.takeLast(10)
        }
    }

    private fun sendSms(context: Context, destination: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager?.sendTextMessage(destination, null, message, null, null)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    FirestoreManager.reportSmsDebug(context, "sendSms OK to $destination: $message")
                } catch (e: Exception) {
                    Log.w(TAG, "reportSmsDebug call failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS response to $destination", e)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    FirestoreManager.reportSmsDebug(context, "sendSms FAILED to $destination: ${e.javaClass.simpleName}: ${e.message}")
                } catch (e2: Exception) {
                    Log.w(TAG, "reportSmsDebug call failed", e2)
                }
            }
        }
    }

    private enum class CommandExecutionResult {
        SUCCESS,
        FAILED,
        UNKNOWN
    }

    companion object {
        private const val TAG = "SmsCommandReceiver"
        private const val PREFS_NAME = "ibs_config_prefs"
        private const val KEY_AUTHORIZED_NUMBERS = "authorized_numbers"
        private const val KEY_MASTER_NUMBER = "master_number"
        private const val KEY_DEVICE_SECRET_CODE = "device_secret_code"
        private const val COMMAND_TIMEOUT_SECONDS = 90L
    }
}
