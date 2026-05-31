package com.ibs.configapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.ibs.configapp.util.CallBlockManager
import com.ibs.configapp.util.SimMonitor

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED,
            "android.intent.action.SIM_STATE_CHANGED" -> {
                SimMonitor.checkSimChange(context)
                if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    handleIncomingCallState(context, intent)
                }
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                if (CallBlockManager.shouldBlockCall(context)) {
                    abortBroadcast()
                }
            }
        }
    }

    private fun handleIncomingCallState(context: Context, intent: Intent) {
        if (!CallBlockManager.shouldBlockCall(context)) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            CallBlockManager.rejectIncomingCall(context)
        }
    }
}
