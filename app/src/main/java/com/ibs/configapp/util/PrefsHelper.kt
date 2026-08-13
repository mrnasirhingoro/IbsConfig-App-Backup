package com.ibs.configapp.util

import android.content.Context
import android.content.SharedPreferences

object PrefsHelper {
    private const val PREFS_NAME = "ibs_config_prefs"

    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_SECRET_CODE = "device_secret_code"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_DEALER_ID = "dealer_id"
    private const val KEY_ACTIVATION_CODE = "activation_code"
    private const val KEY_CUSTOMER_ID = "customer_id"
    private const val KEY_IMEI1 = "imei1"
    private const val KEY_IMEI2 = "imei2"
    private const val KEY_SIM_TYPE = "sim_type"
    private const val KEY_DEALER_NAME = "dealer_name"
    private const val KEY_DEALER_PHONE = "dealer_phone"
    private const val KEY_SECURE_CODE = "secure_code"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_LOCKED = "is_locked"
    private const val KEY_CALLS_BLOCKED = "calls_blocked"
    private const val KEY_INCOMING_CALLS_BLOCKED = "incoming_calls_blocked"
    private const val KEY_OUTGOING_CALLS_BLOCKED = "outgoing_calls_blocked"
    private const val KEY_APPS_BLOCKED = "apps_blocked"
    private const val KEY_AUTO_START_ACK = "auto_start_ack"
    private const val KEY_PLAY_PROTECT_ACK = "play_protect_ack"
    private const val KEY_MULTIPLE_USERS_ACK = "multiple_users_ack"
    private const val KEY_LAST_SIM_SERIAL = "last_sim_serial"
    private const val KEY_BRAND_SETUP_COMPLETE = "brand_setup_complete"
    private const val KEY_BRAND_SETUP_ACK = "brand_setup_ack"
    private const val KEY_DEALER_WALLPAPER_URL = "dealer_wallpaper_url"
    private const val KEY_IS_DEVICE_OWNER = "is_device_owner"
    private const val KEY_DEVICE_OWNER_SETUP_COMPLETE = "device_owner_setup_complete"
    private const val KEY_MANUAL_DEVICE_OWNER_SETUP = "manual_device_owner_setup"
    private const val KEY_PENDING_DEALER_ID = "pending_dealer_id"
    private const val KEY_PENDING_ACTIVATION_CODE = "pending_activation_code"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateDeviceId(context: Context): String {
        val p = prefs(context)
        var id = p.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString()
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun ensureDeviceSecretCode(context: Context, deviceId: String) {
        val p = prefs(context)
        if (!p.getString(KEY_DEVICE_SECRET_CODE, null).isNullOrBlank()) {
            return
        }
        val secretCode = SecretCodeGenerator.fromDeviceId(deviceId)
        p.edit().putString(KEY_DEVICE_SECRET_CODE, secretCode).apply()
    }

    fun getDeviceSecretCode(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_DEVICE_SECRET_CODE, null)?.trim()
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val deviceId = getOrCreateDeviceId(context)
        ensureDeviceSecretCode(context, deviceId)
        return p.getString(KEY_DEVICE_SECRET_CODE, null)?.trim()
            ?: SecretCodeGenerator.fromDeviceId(deviceId)
    }

    fun isActivated(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVATED, false)

    fun setActivated(context: Context, activated: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVATED, activated).apply()
    }

    fun saveActivation(
        context: Context,
        dealerId: String,
        activationCode: String,
        customerId: String,
        imei1: String,
        imei2: String,
        simType: String
    ) {
        prefs(context).edit()
            .putString(KEY_DEALER_ID, dealerId)
            .putString(KEY_ACTIVATION_CODE, activationCode)
            .putString(KEY_CUSTOMER_ID, customerId)
            .putString(KEY_IMEI1, imei1)
            .putString(KEY_IMEI2, imei2)
            .putString(KEY_SIM_TYPE, simType)
            .putBoolean(KEY_ACTIVATED, true)
            .apply()
    }

    fun getDealerId(context: Context): String? = prefs(context).getString(KEY_DEALER_ID, null)
    fun getActivationCode(context: Context): String? = prefs(context).getString(KEY_ACTIVATION_CODE, null)
    fun getCustomerId(context: Context): String? = prefs(context).getString(KEY_CUSTOMER_ID, null)
    fun getImei1(context: Context): String? = prefs(context).getString(KEY_IMEI1, null)
    fun getImei2(context: Context): String? = prefs(context).getString(KEY_IMEI2, null)
    fun getSimType(context: Context): String? = prefs(context).getString(KEY_SIM_TYPE, "single")

    fun setDealerInfo(context: Context, name: String?, phone: String?, secureCode: String?) {
        prefs(context).edit()
            .putString(KEY_DEALER_NAME, name)
            .putString(KEY_DEALER_PHONE, phone)
            .putString(KEY_SECURE_CODE, secureCode)
            .apply()
    }

    fun getDealerName(context: Context): String =
        prefs(context).getString(KEY_DEALER_NAME, "") ?: ""

    fun getDealerPhone(context: Context): String =
        prefs(context).getString(KEY_DEALER_PHONE, "") ?: ""

    fun getSecureCode(context: Context): String =
        prefs(context).getString(KEY_SECURE_CODE, "----") ?: "----"

    fun setDealerWallpaperUrl(context: Context, url: String?) {
        prefs(context).edit().putString(KEY_DEALER_WALLPAPER_URL, url).apply()
    }

    fun getDealerWallpaperUrl(context: Context): String? =
        prefs(context).getString(KEY_DEALER_WALLPAPER_URL, null)

    fun setDeviceOwner(context: Context, isDeviceOwner: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_DEVICE_OWNER, isDeviceOwner).apply()
    }

    fun isDeviceOwner(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_DEVICE_OWNER, false)

    fun setDeviceOwnerSetupComplete(context: Context, complete: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEVICE_OWNER_SETUP_COMPLETE, complete).apply()
    }

    fun isDeviceOwnerSetupComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVICE_OWNER_SETUP_COMPLETE, false)

    fun setManualDeviceOwnerSetup(context: Context, manual: Boolean) {
        prefs(context).edit().putBoolean(KEY_MANUAL_DEVICE_OWNER_SETUP, manual).apply()
    }

    fun isManualDeviceOwnerSetup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MANUAL_DEVICE_OWNER_SETUP, false)

    fun savePendingProvisioningData(context: Context, dealerId: String, activationCode: String) {
        prefs(context).edit()
            .putString(KEY_PENDING_DEALER_ID, dealerId)
            .putString(KEY_PENDING_ACTIVATION_CODE, activationCode)
            .apply()
    }

    fun getPendingDealerId(context: Context): String =
        prefs(context).getString(KEY_PENDING_DEALER_ID, "") ?: ""

    fun getPendingActivationCode(context: Context): String =
        prefs(context).getString(KEY_PENDING_ACTIVATION_CODE, "") ?: ""

    fun clearPendingProvisioningData(context: Context) {
        prefs(context).edit()
            .remove(KEY_PENDING_DEALER_ID)
            .remove(KEY_PENDING_ACTIVATION_CODE)
            .apply()
    }

    fun setFcmToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(context: Context): String? = prefs(context).getString(KEY_FCM_TOKEN, null)

    fun setLocked(context: Context, locked: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    fun isLocked(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCKED, false)

    fun setCallsBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_CALLS_BLOCKED, blocked).apply()
    }

    fun isCallsBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CALLS_BLOCKED, false)

    fun setIncomingCallsBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCOMING_CALLS_BLOCKED, blocked).apply()
    }

    fun isIncomingCallsBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCOMING_CALLS_BLOCKED, false)

    fun setOutgoingCallsBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_OUTGOING_CALLS_BLOCKED, blocked).apply()
    }

    fun isOutgoingCallsBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OUTGOING_CALLS_BLOCKED, false)

    fun setAppsBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_APPS_BLOCKED, blocked).apply()
    }

    fun isAppsBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APPS_BLOCKED, false)

    fun setAutoStartAcknowledged(context: Context, ack: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ACK, ack).apply()
    }

    fun isAutoStartAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ACK, false)

    fun setPlayProtectAcknowledged(context: Context, ack: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLAY_PROTECT_ACK, ack).apply()
    }

    fun isPlayProtectAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLAY_PROTECT_ACK, false)

    fun setMultipleUsersAcknowledged(context: Context, ack: Boolean) {
        prefs(context).edit().putBoolean(KEY_MULTIPLE_USERS_ACK, ack).apply()
    }

    fun isMultipleUsersAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MULTIPLE_USERS_ACK, false)

    fun getLastSimSerial(context: Context): String? =
        prefs(context).getString(KEY_LAST_SIM_SERIAL, null)

    fun setLastSimSerial(context: Context, serial: String?) {
        prefs(context).edit().putString(KEY_LAST_SIM_SERIAL, serial).apply()
    }

    fun isBrandBackgroundSetupComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BRAND_SETUP_COMPLETE, false)

    fun setBrandBackgroundSetupComplete(context: Context, complete: Boolean) {
        prefs(context).edit().putBoolean(KEY_BRAND_SETUP_COMPLETE, complete).apply()
    }

    fun isBrandBackgroundSetupAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BRAND_SETUP_ACK, false)

    fun setBrandBackgroundSetupAcknowledged(context: Context, ack: Boolean) {
        prefs(context).edit().putBoolean(KEY_BRAND_SETUP_ACK, ack).apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
