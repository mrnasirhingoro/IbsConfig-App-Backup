package com.ibs.configapp.util

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val dealerName = PrefsHelper.getDealerName(context)
                    .ifBlank { "your dealer" }
                val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dpm.setOrganizationName(
                        admin,
                        dealerName
                    )
                }
            }
            blockUninstallIfPossible(context)
            applyAllUserRestrictions(context)
            grantDeviceOwnerRuntimePermissions(context)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dpm.setLocationEnabled(admin, true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyDeviceOwnerPolicies failed", e)
        }
    }

    /**
     * Canonical place for Device Owner / Profile Owner user restrictions.
     */
    fun applyAllUserRestrictions(context: Context) {
        if (!isDeviceOwnerOrProfileOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return

        addUserRestrictionSafely(dpm, admin, UserManager.DISALLOW_FACTORY_RESET, "DISALLOW_FACTORY_RESET")
        addUserRestrictionSafely(dpm, admin, UserManager.DISALLOW_SAFE_BOOT, "DISALLOW_SAFE_BOOT")
        addUserRestrictionSafely(
            dpm,
            admin,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            "DISALLOW_DEBUGGING_FEATURES"
        )

        if (dpm.isDeviceOwnerApp(context.packageName) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            addUserRestrictionSafely(dpm, admin, UserManager.DISALLOW_CONFIG_LOCATION, "DISALLOW_CONFIG_LOCATION")
        }
    }

    private fun addUserRestrictionSafely(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        restriction: String,
        label: String
    ) {
        try {
            dpm.addUserRestriction(admin, restriction)
            Log.d(TAG, "User restriction applied: $label")
        } catch (e: Exception) {
            Log.w(TAG, "User restriction failed: $label", e)
        }
    }

    /**
     * Packages allowed during lock task: this app, default dialer, and installed payment apps.
     */
    fun buildLockTaskAllowedPackages(context: Context, extraPackages: Collection<String>): Array<String> {
        val allowed = linkedSetOf(context.packageName)
        val packageManager = context.packageManager
        extraPackages.forEach { packageName ->
            if (isPackageInstalled(packageManager, packageName) &&
                isTrustedPaymentPackageForLockTask(context, packageName)
            ) {
                allowed.add(packageName)
            }
        }
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL)
            val dialer = packageManager.resolveActivity(
                dialIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            dialer?.activityInfo?.packageName?.let { allowed.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "resolve dialer for lock task failed", e)
        }
        Log.d(TAG, "Lock task allowed packages: ${allowed.joinToString()}")
        return allowed.toTypedArray()
    }

    private fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "isPackageInstalled failed pkg=$packageName", e)
            false
        }
    }

    /**
     * Best-effort trust check for payment apps in lock task.
     * API 30+: require Google Play as installer. Older APIs: name-only (logged).
     */
    private fun isTrustedPaymentPackageForLockTask(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val sourceInfo = context.packageManager.getInstallSourceInfo(packageName)
                val installer = sourceInfo.installingPackageName
                    ?: sourceInfo.initiatingPackageName
                val trusted = installer == GOOGLE_PLAY_INSTALLER_PACKAGE
                if (!trusted) {
                    Log.w(
                        TAG,
                        "Lock task: payment package rejected (installer=$installer): $packageName"
                    )
                }
                trusted
            } catch (e: Exception) {
                Log.w(TAG, "Lock task: install source check failed for $packageName", e)
                false
            }
        }
        Log.w(
            TAG,
            "Lock task: payment package $packageName allowed by name only (API<30); sideload risk remains"
        )
        return true
    }

    private const val GOOGLE_PLAY_INSTALLER_PACKAGE = "com.android.vending"

    fun enforceLocationPolicy(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dpm.setLocationEnabled(admin, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "enforceLocationPolicy failed", e)
        }
    }

    fun grantSystemAlertWindowPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.i(TAG, "Permission grant state set GRANTED: ${Manifest.permission.SYSTEM_ALERT_WINDOW}")
        } catch (e: Exception) {
            Log.w(TAG, "grantSystemAlertWindowPermission failed", e)
        }
    }

    fun grantDeviceOwnerRuntimePermissions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
            deviceOwnerGrantablePermissions(context).forEach { permission ->
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        context.packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    Log.i(TAG, "Permission grant state set GRANTED: $permission")
                } catch (e: Exception) {
                    Log.w(TAG, "Permission grant state failed: $permission", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "grantDeviceOwnerRuntimePermissions failed", e)
        }
    }

    private fun deviceOwnerGrantablePermissions(context: Context): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.SYSTEM_ALERT_WINDOW
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        @Suppress("DEPRECATION")
        permissions.add(Manifest.permission.PROCESS_OUTGOING_CALLS)
        return permissions.distinct()
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
