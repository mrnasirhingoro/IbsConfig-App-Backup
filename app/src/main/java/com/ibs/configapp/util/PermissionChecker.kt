package com.ibs.configapp.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ibs.configapp.IbsDeviceAdminReceiver
import com.ibs.configapp.service.AppBlockAccessibilityService

enum class PermissionType {
    DEVICE_ADMIN,
    ACCESSIBILITY,
    OVERLAY,
    LOCATION,
    PHONE,
    CALL_LOGS,
    NOTIFICATIONS,
    BATTERY,
    AUTO_START,
    PLAY_PROTECT,
    MULTIPLE_USERS
}

object PermissionChecker {

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, IbsDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(admin)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val target = ComponentName(context, AppBlockAccessibilityService::class.java)
        return enabled.any {
            it.resolveInfo.serviceInfo.let { s ->
                s.packageName == target.packageName && s.name == target.className
            }
        }
    }

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun hasLocationPermissions(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun hasPhonePermissions(context: Context): Boolean {
        val phoneState = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val callPhone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        return phoneState && callPhone
    }

    fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

    fun areNotificationsEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isAutoStartAcknowledged(context: Context): Boolean =
        PrefsHelper.isAutoStartAcknowledged(context)

    fun isPlayProtectAcknowledged(context: Context): Boolean =
        PrefsHelper.isPlayProtectAcknowledged(context)

    fun isMultipleUsersAcknowledged(context: Context): Boolean =
        PrefsHelper.isMultipleUsersAcknowledged(context)

    fun isGranted(context: Context, type: PermissionType): Boolean = when (type) {
        PermissionType.DEVICE_ADMIN -> isDeviceAdminActive(context)
        PermissionType.ACCESSIBILITY -> isAccessibilityEnabled(context)
        PermissionType.OVERLAY -> canDrawOverlays(context)
        PermissionType.LOCATION -> hasLocationPermissions(context)
        PermissionType.PHONE -> hasPhonePermissions(context)
        PermissionType.CALL_LOGS -> hasCallLogPermission(context)
        PermissionType.NOTIFICATIONS -> areNotificationsEnabled(context)
        PermissionType.BATTERY -> isBatteryOptimizationDisabled(context)
        PermissionType.AUTO_START -> isAutoStartAcknowledged(context)
        PermissionType.PLAY_PROTECT -> isPlayProtectAcknowledged(context)
        PermissionType.MULTIPLE_USERS -> isMultipleUsersAcknowledged(context)
    }

    fun allGranted(context: Context): Boolean =
        PermissionType.entries.all { isGranted(context, it) }

    fun getBatterySettingsIntent(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else null

    fun getOverlaySettingsIntent(context: Context) =
        android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    fun getAccessibilitySettingsIntent() =
        android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
