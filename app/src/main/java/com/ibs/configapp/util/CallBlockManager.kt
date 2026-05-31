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
    private val blocked = AtomicBoolean(false)
    private var appContext: Context? = null
    private var phoneStateListener: Any? = null

    fun setBlocked(context: Context, value: Boolean) {
        blocked.set(value)
        PrefsHelper.setCallsBlocked(context, value)
        if (value) {
            startMonitoring(context.applicationContext)
        } else {
            stopMonitoring()
        }
        Log.i(TAG, "Call blocking ${if (value) "enabled" else "disabled"}")
    }

    fun isBlocked(context: Context): Boolean {
        val prefsBlocked = PrefsHelper.isCallsBlocked(context)
        if (prefsBlocked) {
            blocked.set(true)
            startMonitoring(context.applicationContext)
        }
        return blocked.get() || prefsBlocked
    }

    fun shouldBlockCall(context: Context): Boolean = isBlocked(context)

    fun restoreFromPrefs(context: Context) {
        if (PrefsHelper.isCallsBlocked(context)) {
            blocked.set(true)
            startMonitoring(context.applicationContext)
        }
    }

    fun rejectIncomingCall(context: Context) {
        if (!isBlocked(context)) return
        if (endCallViaTelecom(context)) {
            Log.i(TAG, "Incoming call rejected via TelecomManager")
            return
        }
        if (endCall(context)) {
            Log.i(TAG, "Incoming call rejected via ITelephony")
        }
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
        if (!isBlocked(context)) return
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            rejectIncomingCall(context)
        }
    }

    private fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun endCallViaTelecom(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.endCall()
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.endCall failed", e)
            false
        }
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
