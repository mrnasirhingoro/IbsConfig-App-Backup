package com.ibs.configapp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ibs.configapp.firebase.FirestoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SimNumberMonitor {

    private const val TAG = "SimNumberMonitor"

    fun checkSimNumberChange(context: Context) {
        try {
            val number = detectPhoneNumber(context) ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    FirestoreManager.syncSimNumberIfChanged(context, number)
                } catch (e: Exception) {
                    Log.w(TAG, "syncSimNumberIfChanged failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkSimNumberChange failed", e)
        }
    }

    private fun detectPhoneNumber(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? SubscriptionManager ?: return readLine1Number(context)

                val defaultSubId = SubscriptionManager.getDefaultSubscriptionId()
                if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    normalizeNumber(subscriptionManager.getPhoneNumber(defaultSubId))?.let { return it }
                }

                subscriptionManager.activeSubscriptionInfoList?.forEach { info ->
                    normalizeNumber(subscriptionManager.getPhoneNumber(info.subscriptionId))?.let { return it }
                }
            } catch (_: SecurityException) {
            } catch (e: Exception) {
                Log.w(TAG, "SubscriptionManager.getPhoneNumber failed", e)
            }
        }

        return readLine1Number(context)
    }

    private fun readLine1Number(context: Context): String? {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            normalizeNumber(telephonyManager.line1Number)
        } catch (_: SecurityException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "TelephonyManager.getLine1Number failed", e)
            null
        }
    }

    private fun normalizeNumber(raw: String?): String? {
        val normalized = raw?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }
    }
}
