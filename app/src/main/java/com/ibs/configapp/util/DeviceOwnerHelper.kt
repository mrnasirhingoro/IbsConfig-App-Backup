package com.ibs.configapp.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.ibs.configapp.IbsDeviceAdminReceiver

object DeviceOwnerHelper {
    private const val TAG = "DeviceOwnerHelper"

    const val DEVICE_OWNER_COMPONENT =
        "com.ibs.configapp/com.ibs.configapp.IbsDeviceAdminReceiver"

    const val ADB_SET_DEVICE_OWNER_COMMAND =
        "adb shell dpm set-device-owner $DEVICE_OWNER_COMPONENT"

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, IbsDeviceAdminReceiver::class.java)

    fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(adminComponent(context))
        } catch (e: Exception) {
            Log.w(TAG, "isDeviceAdminActive failed", e)
            false
        }
    }

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "isDeviceOwner failed", e)
            false
        }
    }

    fun syncDeviceOwnerState(context: Context) {
        val isOwner = isDeviceOwner(context)
        PrefsHelper.setDeviceOwner(context, isOwner)
        if (isOwner) {
            PrefsHelper.setDeviceOwnerSetupComplete(context, true)
        }
    }

    fun needsDeviceOwnerSetup(context: Context): Boolean {
        if (isDeviceOwner(context)) {
            syncDeviceOwnerState(context)
            return false
        }
        return !PrefsHelper.isDeviceOwnerSetupComplete(context)
    }

    fun trySetDeviceOwnerViaShell(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "dpm set-device-owner $DEVICE_OWNER_COMPONENT")
            )
            val exitCode = process.waitFor()
            Log.i(TAG, "dpm set-device-owner shell exitCode=$exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "trySetDeviceOwnerViaShell failed", e)
            false
        }
    }
}
