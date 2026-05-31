package com.ibs.configapp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ibs.configapp.firebase.FirestoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SimMonitor {

    fun getCurrentSimInfo(context: Context): Map<String, String?> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyMap()
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val info = mutableMapOf<String, String?>()
        try {
            info["simSerial"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.simSerialNumber
            } else {
                @Suppress("DEPRECATION")
                tm.simSerialNumber
            }
            info["operator"] = tm.simOperatorName
            info["country"] = tm.simCountryIso
            info["phoneNumber"] = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    tm.line1Number
                } else {
                    @Suppress("DEPRECATION")
                    tm.line1Number
                }
            } catch (_: SecurityException) { null }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager
                info["subscriptionCount"] = sm?.activeSubscriptionInfoCount?.toString()
            }
        } catch (_: Exception) { }
        return info
    }

    fun checkSimChange(context: Context) {
        val current = getCurrentSimInfo(context)
        val currentSerial = current["simSerial"] ?: return
        val last = PrefsHelper.getLastSimSerial(context)
        if (last == null) {
            PrefsHelper.setLastSimSerial(context, currentSerial)
            return
        }
        if (last != currentSerial) {
            val oldSim = mapOf("simSerial" to last)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    FirestoreManager.logSimChange(context, oldSim, current)
                } catch (_: Exception) { }
            }
            PrefsHelper.setLastSimSerial(context, currentSerial)
        }
    }
}
