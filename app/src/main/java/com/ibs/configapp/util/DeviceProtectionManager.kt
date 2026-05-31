package com.ibs.configapp.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import com.ibs.configapp.IbsDeviceAdminReceiver
import com.ibs.configapp.PermissionSetupActivity

object DeviceProtectionManager {
    private const val TAG = "DeviceProtectionManager"

    /** Safe during activation — only hides the launcher icon. */
    fun hideLauncherIcon(context: Context) {
        try {
            val pm = context.packageManager
            val launcher = ComponentName(context, PermissionSetupActivity::class.java)
            pm.setComponentEnabledSetting(
                launcher,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.w(TAG, "hideLauncherIcon failed", e)
        }
    }

    fun showLauncherIcon(context: Context) {
        try {
            val pm = context.packageManager
            val launcher = ComponentName(context, PermissionSetupActivity::class.java)
            pm.setComponentEnabledSetting(
                launcher,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.w(TAG, "showLauncherIcon failed", e)
        }
    }

    /**
     * Device Owner / Profile Owner policies only.
     * Call from BackgroundService after activation, not during activation.
     */
    fun applyDeviceOwnerPolicies(context: Context) {
        try {
            if (!isDeviceOwnerOrProfileOwner(context)) {
                Log.d(TAG, "Skipping device-owner policies — not device/profile owner")
                return
            }
            blockUninstallIfPossible(context)
            tryBlockFactoryReset(context)
        } catch (e: Exception) {
            Log.w(TAG, "applyDeviceOwnerPolicies failed", e)
        }
    }

    private fun isDeviceOwnerOrProfileOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName) ||
                dpm.isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "isDeviceOwnerOrProfileOwner check failed", e)
            false
        }
    }

    private fun blockUninstallIfPossible(context: Context) {
        if (!isDeviceOwnerOrProfileOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                dpm.setUninstallBlocked(admin, context.packageName, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setUninstallBlocked failed", e)
        }
    }

    fun unblockUninstall(context: Context) {
        if (!isDeviceOwnerOrProfileOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                dpm.setUninstallBlocked(admin, context.packageName, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "unblockUninstall failed", e)
        }
    }

    private fun tryBlockFactoryReset(context: Context) {
        if (!isDeviceOwnerOrProfileOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        } catch (e: Exception) {
            Log.w(TAG, "tryBlockFactoryReset failed", e)
        }
    }

    fun releaseDevice(context: Context) {
        showLauncherIcon(context)
        unblockUninstall(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            try {
                dpm.removeActiveAdmin(admin)
            } catch (e: Exception) {
                Log.w(TAG, "removeActiveAdmin failed", e)
            }
        }
        PrefsHelper.clearAll(context)
    }
}
