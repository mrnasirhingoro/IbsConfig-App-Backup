package com.ibs.configapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.ibs.configapp.util.CallBlockManager
import com.ibs.configapp.util.SimMonitor
import com.ibs.configapp.util.SimNumberMonitor

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED,
            "android.intent.action.SIM_STATE_CHANGED" -> {
                SimMonitor.checkSimChange(context)
                SimNumberMonitor.checkSimNumberChange(context)
                if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    handleIncomingCallState(context, intent)
                }
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Deprecated/ignored on Android 10+; outgoing blocking uses Accessibility.
                    return
                }
                if (CallBlockManager.shouldBlockOutgoing(context)) {
                    val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                    if (!CallBlockManager.isEmergencyNumber(number)) {
                        abortBroadcast()
                        CallBlockManager.blockOutgoingCall(context)
                    }
                }
            }
        }
    }

    private fun handleIncomingCallState(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state == TelephonyManager.EXTRA_STATE_RINGING &&
            CallBlockManager.shouldBlockIncoming(context)
        ) {
            @Suppress("DEPRECATION")
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (CallBlockManager.isEmergencyNumber(incomingNumber)) {
                return
            }
            CallBlockManager.rejectIncomingCall(context)
        }
    }
}
