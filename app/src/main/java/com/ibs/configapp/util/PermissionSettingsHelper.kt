package com.ibs.configapp.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object PermissionSettingsHelper {

    fun openAutoStartSettings(context: Context): Boolean {
        val guide = DeviceBrandHelper.getSetupGuide(context)
        return when (guide.brand) {
            PhoneBrand.XIAOMI,
            PhoneBrand.OPPO,
            PhoneBrand.REALME,
            PhoneBrand.VIVO,
            PhoneBrand.HUAWEI -> DeviceBrandHelper.openBrandSettings(context, guide)
            else -> openLegacyAutoStartSettings(context)
        }
    }

    private fun openLegacyAutoStartSettings(context: Context): Boolean {
        val intents = listOf(
            // MIUI / Redmi / POCO
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            ),
            // Oppo / Realme / ColorOS
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            ),
            // Samsung Device Care / auto-start
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.ram.AutoRunActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.ram.AutoRunActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.sm_cn",
                    "com.samsung.android.sm.ui.ram.AutoRunActivity"
                )
            ),
            // Vivo
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            ),
            // Huawei
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
        )
        for (intent in intents) {
            if (launchIfAvailable(context, intent)) return true
        }
        return openAppDetails(context)
    }

    fun openPlayProtectSettings(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.google.android.gms",
                    "com.google.android.gms.security.settings.VerifyAppsSettingsActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.vending",
                    "com.google.android.finsky.playprotect.view.PlayProtectSettingsActivity"
                )
            ),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_PRIVACY_SETTINGS)
        )
        for (intent in intents) {
            if (launchIfAvailable(context, intent)) return true
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps"))
            )
            true
        } catch (_: Exception) {
            openAppDetails(context)
        }
    }

    fun openUsersSettings(context: Context): Boolean {
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            intents.add(Intent("android.settings.USER_SETTINGS"))
        }
        intents.add(Intent(Settings.ACTION_SETTINGS))
        intents.add(Intent(Settings.ACTION_SYNC_SETTINGS))
        for (intent in intents) {
            if (launchIfAvailable(context, intent)) return true
        }
        return openAppDetails(context)
    }

    fun openNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        if (launchIfAvailable(context, intent)) return true
        return openAppDetails(context)
    }

    fun needsNotificationSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        return !NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        return launchIfAvailable(context, intent)
    }

    private fun launchIfAvailable(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pm = context.packageManager
            if (intent.resolveActivity(pm) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
