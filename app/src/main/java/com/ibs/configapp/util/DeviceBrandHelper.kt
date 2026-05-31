package com.ibs.configapp.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ibs.configapp.R

enum class PhoneBrand {
    XIAOMI,
    OPPO,
    REALME,
    VIVO,
    SAMSUNG,
    HUAWEI,
    GENERIC
}

enum class BrandSetupAction {
    AUTOSTART,
    BATTERY_OPTIMIZATION,
    PROTECTED_APPS
}

data class BrandSetupGuide(
    val brand: PhoneBrand,
    val manufacturerLabel: String,
    val osLabel: String,
    val action: BrandSetupAction,
    val titleRes: Int,
    val descriptionRes: Int,
    val stepsRes: Int,
    val openSettingsRes: Int
)

object DeviceBrandHelper {

    fun detectBrand(): PhoneBrand {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return when {
            manufacturer in XIAOMI_NAMES || brand in XIAOMI_NAMES -> PhoneBrand.XIAOMI
            manufacturer in OPPO_NAMES || brand in OPPO_NAMES -> PhoneBrand.OPPO
            manufacturer in REALME_NAMES || brand in REALME_NAMES -> PhoneBrand.REALME
            manufacturer in VIVO_NAMES || brand in VIVO_NAMES -> PhoneBrand.VIVO
            manufacturer in SAMSUNG_NAMES || brand in SAMSUNG_NAMES -> PhoneBrand.SAMSUNG
            manufacturer in HUAWEI_NAMES || brand in HUAWEI_NAMES -> PhoneBrand.HUAWEI
            else -> PhoneBrand.GENERIC
        }
    }

    fun getSetupGuide(context: Context): BrandSetupGuide {
        return when (detectBrand()) {
            PhoneBrand.XIAOMI -> BrandSetupGuide(
                brand = PhoneBrand.XIAOMI,
                manufacturerLabel = "Xiaomi / Redmi / POCO",
                osLabel = "MIUI / HyperOS",
                action = BrandSetupAction.AUTOSTART,
                titleRes = R.string.brand_setup_title_xiaomi,
                descriptionRes = R.string.brand_setup_desc_xiaomi,
                stepsRes = R.string.brand_setup_steps_xiaomi,
                openSettingsRes = R.string.brand_setup_open_autostart
            )
            PhoneBrand.OPPO -> BrandSetupGuide(
                brand = PhoneBrand.OPPO,
                manufacturerLabel = "Oppo",
                osLabel = "ColorOS",
                action = BrandSetupAction.AUTOSTART,
                titleRes = R.string.brand_setup_title_oppo,
                descriptionRes = R.string.brand_setup_desc_oppo,
                stepsRes = R.string.brand_setup_steps_oppo,
                openSettingsRes = R.string.brand_setup_open_autostart
            )
            PhoneBrand.REALME -> BrandSetupGuide(
                brand = PhoneBrand.REALME,
                manufacturerLabel = "Realme",
                osLabel = "ColorOS / Realme UI",
                action = BrandSetupAction.AUTOSTART,
                titleRes = R.string.brand_setup_title_realme,
                descriptionRes = R.string.brand_setup_desc_realme,
                stepsRes = R.string.brand_setup_steps_realme,
                openSettingsRes = R.string.brand_setup_open_autostart
            )
            PhoneBrand.VIVO -> BrandSetupGuide(
                brand = PhoneBrand.VIVO,
                manufacturerLabel = "Vivo / iQOO",
                osLabel = "Funtouch OS / OriginOS",
                action = BrandSetupAction.AUTOSTART,
                titleRes = R.string.brand_setup_title_vivo,
                descriptionRes = R.string.brand_setup_desc_vivo,
                stepsRes = R.string.brand_setup_steps_vivo,
                openSettingsRes = R.string.brand_setup_open_autostart
            )
            PhoneBrand.SAMSUNG -> BrandSetupGuide(
                brand = PhoneBrand.SAMSUNG,
                manufacturerLabel = "Samsung",
                osLabel = "One UI",
                action = BrandSetupAction.BATTERY_OPTIMIZATION,
                titleRes = R.string.brand_setup_title_samsung,
                descriptionRes = R.string.brand_setup_desc_samsung,
                stepsRes = R.string.brand_setup_steps_samsung,
                openSettingsRes = R.string.brand_setup_open_battery
            )
            PhoneBrand.HUAWEI -> BrandSetupGuide(
                brand = PhoneBrand.HUAWEI,
                manufacturerLabel = "Huawei / Honor",
                osLabel = "EMUI / HarmonyOS",
                action = BrandSetupAction.PROTECTED_APPS,
                titleRes = R.string.brand_setup_title_huawei,
                descriptionRes = R.string.brand_setup_desc_huawei,
                stepsRes = R.string.brand_setup_steps_huawei,
                openSettingsRes = R.string.brand_setup_open_protected
            )
            PhoneBrand.GENERIC -> BrandSetupGuide(
                brand = PhoneBrand.GENERIC,
                manufacturerLabel = Build.MANUFACTURER.ifBlank { "Android" },
                osLabel = "Android ${Build.VERSION.RELEASE}",
                action = BrandSetupAction.BATTERY_OPTIMIZATION,
                titleRes = R.string.brand_setup_title_generic,
                descriptionRes = R.string.brand_setup_desc_generic,
                stepsRes = R.string.brand_setup_steps_generic,
                openSettingsRes = R.string.brand_setup_open_battery
            )
        }
    }

    fun openBrandSettings(context: Context, guide: BrandSetupGuide): Boolean {
        return when (guide.action) {
            BrandSetupAction.AUTOSTART -> openAutostartForBrand(context, guide.brand)
            BrandSetupAction.BATTERY_OPTIMIZATION -> openBatteryOptimization(context)
            BrandSetupAction.PROTECTED_APPS -> openProtectedAppsForBrand(context, guide.brand)
        }
    }

    fun isRecommendedSetupDone(context: Context, guide: BrandSetupGuide): Boolean {
        return when (guide.action) {
            BrandSetupAction.BATTERY_OPTIMIZATION ->
                PermissionChecker.isBatteryOptimizationDisabled(context)
            BrandSetupAction.AUTOSTART,
            BrandSetupAction.PROTECTED_APPS ->
                PrefsHelper.isBrandBackgroundSetupAcknowledged(context)
        }
    }

    private fun openAutostartForBrand(context: Context, brand: PhoneBrand): Boolean {
        val intents = when (brand) {
            PhoneBrand.XIAOMI -> listOf(
                componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            )
            PhoneBrand.OPPO -> listOf(
                componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                componentIntent(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                ),
                componentIntent(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            )
            PhoneBrand.REALME -> listOf(
                componentIntent(
                    "com.realme.security",
                    "com.realme.security.autostart.AutoStartManagementActivity"
                ),
                componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                componentIntent(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            )
            PhoneBrand.VIVO -> listOf(
                componentIntent(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                componentIntent(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
            else -> emptyList()
        }
        return launchFirstAvailable(context, intents) || openAppDetails(context)
    }

    private fun openProtectedAppsForBrand(context: Context, brand: PhoneBrand): Boolean {
        val intents = listOf(
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            componentIntent(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        )
        return launchFirstAvailable(context, intents) || openAppDetails(context)
    }

    private fun openBatteryOptimization(context: Context): Boolean {
        PermissionChecker.getBatterySettingsIntent(context)?.let { intent ->
            if (launchIfAvailable(context, intent)) return true
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return launchIfAvailable(context, fallback) || openAppDetails(context)
    }

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (launchIfAvailable(context, intent)) return true
        }
        return false
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
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private val XIAOMI_NAMES = setOf("xiaomi", "redmi", "poco", "blackshark")
    private val OPPO_NAMES = setOf("oppo", "oneplus")
    private val REALME_NAMES = setOf("realme")
    private val VIVO_NAMES = setOf("vivo", "iqoo")
    private val SAMSUNG_NAMES = setOf("samsung")
    private val HUAWEI_NAMES = setOf("huawei", "honor")
}
