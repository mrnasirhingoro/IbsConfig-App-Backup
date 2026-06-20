package com.ibs.configapp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

object CallBlockManager {
    private const val TAG = "CallBlockManager"
    private val EMERGENCY_NUMBERS = setOf(
        "112", "911", "999", "000", "110", "118", "15", "16", "115", "117"
    )
    private val blocked = AtomicBoolean(false)
    private val incomingBlocked = AtomicBoolean(false)
    private val outgoingBlocked = AtomicBoolean(false)
    private var appContext: Context? = null
    private var phoneStateListener: Any? = null

    fun setBlocked(context: Context, value: Boolean) {
        blocked.set(value)
        PrefsHelper.setCallsBlocked(context, value)
        if (value) {
            startMonitoring(context.applicationContext)
        } else {
            incomingBlocked.set(false)
            outgoingBlocked.set(false)
            if (!needsMonitoring(context)) {
                stopMonitoring()
            }
        }
        Log.i(TAG, "Call blocking ${if (value) "enabled" else "disabled"}")
    }

    fun setIncomingBlocked(context: Context, value: Boolean) {
        incomingBlocked.set(value)
        PrefsHelper.setIncomingCallsBlocked(context, value)
        if (value) {
            startMonitoring(context.applicationContext)
        } else if (!outgoingBlocked.get() && !needsMonitoring(context)) {
            stopMonitoring()
        }
        Log.i(TAG, "Incoming call blocking ${if (value) "enabled" else "disabled"}")
    }

    fun setOutgoingBlocked(context: Context, value: Boolean) {
        outgoingBlocked.set(value)
        PrefsHelper.setOutgoingCallsBlocked(context, value)
        if (value) {
            startMonitoring(context.applicationContext)
        } else if (!incomingBlocked.get() && !needsMonitoring(context)) {
            stopMonitoring()
        }
        Log.i(TAG, "Outgoing call blocking ${if (value) "enabled" else "disabled"}")
    }

    fun isBlocked(context: Context): Boolean {
        val prefsBlocked = PrefsHelper.isCallsBlocked(context)
        if (prefsBlocked) {
            blocked.set(true)
            startMonitoring(context.applicationContext)
        }
        return blocked.get() || prefsBlocked
    }

    fun shouldBlockIncoming(context: Context): Boolean {
        if (PrefsHelper.isLocked(context)) {
            startMonitoring(context.applicationContext)
            return true
        }
        return PrefsHelper.isIncomingCallsBlocked(context) ||
            incomingBlocked.get() ||
            PrefsHelper.isCallsBlocked(context)
    }

    fun shouldBlockOutgoing(context: Context): Boolean {
        if (PrefsHelper.isLocked(context)) {
            startMonitoring(context.applicationContext)
            return true
        }
        return PrefsHelper.isOutgoingCallsBlocked(context) ||
            outgoingBlocked.get() ||
            PrefsHelper.isCallsBlocked(context)
    }

    fun shouldBlockCall(context: Context): Boolean {
        return shouldBlockIncoming(context) || shouldBlockOutgoing(context)
    }

    fun onLockScreenActive(context: Context) {
        startMonitoring(context.applicationContext)
    }

    fun isEmergencyNumber(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        val digits = number.replace(Regex("[^0-9+]"), "")
        if (digits.isBlank()) return false
        return EMERGENCY_NUMBERS.any { emergency ->
            digits == emergency || digits.endsWith(emergency)
        }
    }

    fun restoreFromPrefs(context: Context) {
        val legacyBlocked = PrefsHelper.isCallsBlocked(context)
        val incoming = PrefsHelper.isIncomingCallsBlocked(context) || legacyBlocked
        val outgoing = PrefsHelper.isOutgoingCallsBlocked(context) || legacyBlocked
        blocked.set(legacyBlocked)
        incomingBlocked.set(incoming)
        outgoingBlocked.set(outgoing)
        if (incoming || outgoing || PrefsHelper.isLocked(context)) {
            startMonitoring(context.applicationContext)
        } else {
            stopMonitoring()
        }
    }

    fun rejectIncomingCall(context: Context) {
        if (!shouldBlockIncoming(context)) return
        if (endCallViaTelecom(context)) {
            Log.i(TAG, "Incoming call rejected via TelecomManager")
            return
        }
        if (endCall(context)) {
            Log.i(TAG, "Incoming call rejected via ITelephony")
        }
    }

    fun blockOutgoingCall(context: Context) {
        if (!shouldBlockOutgoing(context)) return
        endCallViaTelecom(context)
    }

    private fun needsMonitoring(context: Context): Boolean {
        return PrefsHelper.isLocked(context) ||
            PrefsHelper.isCallsBlocked(context) ||
            PrefsHelper.isIncomingCallsBlocked(context) ||
            PrefsHelper.isOutgoingCallsBlocked(context)
    }

    private fun startMonitoring(context: Context) {
        if (!hasPhoneStatePermission(context)) return
        if (phoneStateListener != null) return
        appContext = context.applicationContext
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(context, state)
                    }
                }
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
                phoneStateListener = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(context, state)
                    }
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
            Log.i(TAG, "Phone state monitoring started")
        } catch (e: Exception) {
            Log.w(TAG, "startMonitoring failed", e)
            phoneStateListener = null
        }
    }

    private fun stopMonitoring() {
        val context = appContext ?: return
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            when (val listener = phoneStateListener) {
                null -> Unit
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        listener is TelephonyCallback
                    ) {
                        telephony.unregisterTelephonyCallback(listener)
                    } else {
                        @Suppress("DEPRECATION")
                        telephony.listen(listener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopMonitoring failed", e)
        } finally {
            phoneStateListener = null
        }
    }

    private fun handleCallState(context: Context, state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (shouldBlockIncoming(context)) {
                    rejectIncomingCall(context)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (shouldBlockOutgoing(context)) {
                    blockOutgoingCall(context)
                }
            }
        }
    }

    private fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun endCallViaTelecom(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !hasAnswerPhoneCallsPermission(context)
        ) {
            return false
        }
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.endCall()
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.endCall failed", e)
            false
        }
    }

    private fun hasAnswerPhoneCallsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    fun endCall(context: Context): Boolean {
        return try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val telephonyClass = Class.forName(telephony.javaClass.name)
            val getITelephony = telephonyClass.getDeclaredMethod("getITelephony")
            getITelephony.isAccessible = true
            val iTelephony = getITelephony.invoke(telephony)
            val endCallMethod = iTelephony.javaClass.getDeclaredMethod("endCall")
            endCallMethod.isAccessible = true
            endCallMethod.invoke(iTelephony)
            true
        } catch (e: Exception) {
            Log.w(TAG, "endCall reflection failed", e)
            false
        }
    }
}
