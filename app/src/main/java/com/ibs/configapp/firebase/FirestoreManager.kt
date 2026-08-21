package com.ibs.configapp.firebase

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import com.ibs.configapp.util.PrefsHelper
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

object FirestoreManager {
    private const val TAG = "FirestoreManager"
    private const val COL_DEVICES = "devices"
    private const val COL_SIM_CHANGE = "simChangeLogs"
    private const val COL_DEVICE_LOCATIONS = "deviceLocations"
    private const val COL_SYSTEM_CONFIG = "systemConfig"
    private const val DOC_MASTER_NUMBER = "masterNumber"
    private const val PREFS_SMS_AUTH = "ibs_config_prefs"
    private const val PREF_KEY_MASTER_NUMBER = "master_number"
    private const val PREF_KEY_MASTER_NUMBERS = "master_numbers"
    private const val PREF_KEY_AUTHORIZED_NUMBERS = "authorized_numbers"

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build()
        }
    }

    /**
     * Signs in anonymously and waits for completion before any Firestore write.
     */
    private suspend fun ensureAnonymousAuth(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { uid ->
            Log.i(TAG, "Firebase Auth already signed in uid=$uid anonymous=${auth.currentUser?.isAnonymous}")
            return uid
        }

        Log.i(TAG, "Starting FirebaseAuth.signInAnonymously()...")
        return try {
            val result = FirebaseAuth.getInstance().signInAnonymously().await()
            val user = result.user
                ?: throw IllegalStateException("Anonymous sign-in completed but user is null")
            Log.i(TAG, "Anonymous sign-in successful uid=${user.uid}")
            user.uid
        } catch (e: FirebaseAuthException) {
            Log.e(TAG, "Anonymous sign-in failed errorCode=${e.errorCode} message=${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed: ${e.message}", e)
            throw e
        }
    }

    private suspend fun prepareWrite(context: Context): String {
        FirebaseAuthHelper.verifyProjectConfig(context)
        return ensureAnonymousAuth()
    }

    /**
     * QR activationCode is the serial key. Resolve the Firestore customer document ID
     * from serialKeys/customers when assigned; otherwise use activationCode as customerId.
     */
    private suspend fun resolveCustomerId(dealerId: String, activationCode: String): String {
        if (activationCode.isBlank()) return ""

        val customersDoc = db.collection("customers").document(activationCode).get().await()
        if (customersDoc.exists()) {
            val data = customersDoc.data ?: emptyMap()
            val docDealer = data["dealerId"] as? String ?: data["clientId"] as? String
            if (docDealer.isNullOrBlank() || docDealer == dealerId) {
                return activationCode
            }
        }

        for (field in listOf("key", "serialKey")) {
            var query = db.collection("serialKeys")
                .whereEqualTo(field, activationCode)
                .whereEqualTo("dealerId", dealerId)
                .limit(1)
                .get()
                .await()
            if (query.isEmpty) {
                query = db.collection("serialKeys")
                    .whereEqualTo(field, activationCode)
                    .whereEqualTo("clientId", dealerId)
                    .limit(1)
                    .get()
                    .await()
            }
            if (!query.isEmpty) {
                val resolved = query.documents[0].getString("customerId")
                if (!resolved.isNullOrBlank()) {
                    Log.i(TAG, "Resolved customerId=$resolved from serialKeys for activationCode=$activationCode")
                    return resolved
                }
            }
        }

        var customerQuery = db.collection("customers")
            .whereEqualTo("serialKey", activationCode)
            .whereEqualTo("clientId", dealerId)
            .limit(1)
            .get()
            .await()
        if (customerQuery.isEmpty) {
            customerQuery = db.collection("customers")
                .whereEqualTo("serialKey", activationCode)
                .whereEqualTo("dealerId", dealerId)
                .limit(1)
                .get()
                .await()
        }
        if (!customerQuery.isEmpty) {
            val resolved = customerQuery.documents[0].id
            Log.i(TAG, "Resolved customerId=$resolved from customers.serialKey for activationCode=$activationCode")
            return resolved
        }

        Log.i(TAG, "Using activationCode as customerId: $activationCode")
        return activationCode
    }

    suspend fun activateDevice(
        context: Context,
        dealerId: String,
        imei1: String,
        imei2: String,
        simType: String,
        fcmToken: String?,
        activationCode: String?
    ): String {
        val authUid = ensureAnonymousAuth()
        FirebaseAuthHelper.verifyProjectConfig(context)

        val code = activationCode?.trim().orEmpty()
        val customerId = resolveCustomerId(dealerId, code)
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        try {
            PrefsHelper.ensureDeviceSecretCode(context, deviceId)
        } catch (e: Exception) {
            Log.w(TAG, "ensureDeviceSecretCode failed", e)
        }
        val deviceSecretCode = PrefsHelper.getDeviceSecretCode(context)
        val data = hashMapOf<String, Any>(
            "dealerId" to dealerId,
            "customerId" to customerId,
            "imei1" to imei1,
            "imei2" to imei2,
            "simType" to simType,
            "fcmToken" to (fcmToken ?: ""),
            "activationCode" to code,
            "serialKey" to code,
            "authUid" to authUid,
            "status" to "active",
            "isLocked" to false,
            "isOnline" to true,
            "lastSeen" to FieldValue.serverTimestamp(),
            "dealerName" to "",
            "dealerPhone" to "",
            "secureCode" to deviceSecretCode
        )
        try {
            Log.i(TAG, "Writing device document devices/$deviceId ...")
            db.collection(COL_DEVICES).document(deviceId).set(data, SetOptions.merge()).await()
            Log.i(TAG, "Device activated in Firestore deviceId=$deviceId customerId=$customerId dealerId=$dealerId authUid=$authUid")
            return customerId
        } catch (e: FirebaseFirestoreException) {
            Log.e(TAG, "activateDevice Firestore error code=${e.code} message=${e.message}", e)
            throw e
        }
    }

    suspend fun updateFcmToken(context: Context, token: String) {
        ensureAnonymousAuth()
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        db.collection(COL_DEVICES).document(deviceId)
            .update(
                mapOf(
                    "fcmToken" to token,
                    "lastSeen" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun ensureAuthenticated(context: Context): String {
        FirebaseAuthHelper.verifyProjectConfig(context)
        return ensureAnonymousAuth()
    }

    suspend fun fetchCommandDetails(commandId: String): Map<String, Any?> {
        ensureAnonymousAuth()
        val snap = db.collection("deviceCommands").document(commandId).get().await()
        return snap.data ?: emptyMap()
    }

    suspend fun markCommandExecuted(
        context: Context,
        commandId: String?,
        success: Boolean,
        successStatus: String? = null
    ) {
        ensureAnonymousAuth()
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        val status = when {
            !success -> "failed"
            !successStatus.isNullOrBlank() -> successStatus
            else -> "executed"
        }
        val updates = hashMapOf<String, Any>(
            "commandStatus" to "",
            "commandExecutedAt" to FieldValue.serverTimestamp(),
            "command" to ""
        )
        db.collection(COL_DEVICES).document(deviceId).update(updates).await()
        Log.i(TAG, "Command marked $status on deviceId=$deviceId commandId=$commandId")

        if (!commandId.isNullOrBlank()) {
            try {
                db.collection("deviceCommands").document(commandId).update(
                    mapOf(
                        "status" to status,
                        "executedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "deviceCommands status update failed commandId=$commandId", e)
            }
        }
    }

    fun listenDeviceCommands(
        context: Context,
        onCommand: (command: String, data: Map<String, Any?>) -> Unit,
        onDeviceUpdate: (data: Map<String, Any?>) -> Unit
    ): ListenerRegistration {
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        Log.i(TAG, "Attaching Firestore listener on devices/$deviceId")
        return db.collection(COL_DEVICES).document(deviceId)
            .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                Log.i(TAG, "=== SNAPSHOT CALLBACK FIRED ===")
                if (error != null) {
                    Log.e(TAG, "Snapshot error: ${error.message}")
                    return@addSnapshotListener
                }
                Log.i(
                    TAG,
                    "Snapshot exists=${snapshot?.exists()} fromCache=${snapshot?.metadata?.isFromCache}"
                )
                if (snapshot == null || !snapshot.exists()) {
                    Log.w(TAG, "Device document missing for listener deviceId=$deviceId")
                    return@addSnapshotListener
                }
                val map = snapshot.data ?: return@addSnapshotListener
                onDeviceUpdate(map)

                val commandId = map["commandId"] as? String
                if (commandId.isNullOrBlank()) return@addSnapshotListener

                val commandStatus = map["commandStatus"] as? String
                if (commandStatus == "processing") {
                    Log.d(TAG, "Ignoring command status=processing commandId=$commandId")
                    return@addSnapshotListener
                }

                val command = map["command"] as? String
                if (command.isNullOrBlank()) {
                    Log.d(TAG, "Ignoring devices listener snapshot with no command commandId=$commandId")
                    return@addSnapshotListener
                }

                Log.i(TAG, "Firestore command received: $command commandId=$commandId")
                onCommand(command, map)

                val isLocked = map["isLocked"] as? Boolean ?: false
                if (!com.ibs.configapp.service.BackgroundService.isUnlockInProgress) {
                    if (!isLocked) {
                        PrefsHelper.setLocked(context, false)
                    } else if (PrefsHelper.isLocked(context)) {
                        PrefsHelper.setLocked(context, true)
                    }
                }
                val dealerName = map["dealerName"] as? String
                val dealerPhone = map["dealerPhone"] as? String
                val secureCode = map["secureCode"] as? String
                PrefsHelper.setDealerInfo(context, dealerName, dealerPhone, secureCode)
            }
    }

    suspend fun setOnlineStatus(context: Context, online: Boolean) {
        if (!PrefsHelper.isActivated(context)) return
        try {
            ensureAnonymousAuth()
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)
            db.collection(COL_DEVICES).document(deviceId)
                .update(
                    mapOf(
                        "isOnline" to online,
                        "lastSeen" to FieldValue.serverTimestamp()
                    )
                ).await()
            Log.d(TAG, "setOnlineStatus online=$online deviceId=$deviceId")
        } catch (e: Exception) {
            Log.w(TAG, "setOnlineStatus failed online=$online", e)
        }
    }

    /** Heartbeat every 5 minutes — keeps isOnline true and lastSeen fresh. */
    suspend fun sendHeartbeat(context: Context) {
        if (!PrefsHelper.isActivated(context)) return
        try {
            ensureAnonymousAuth()
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)
            db.collection(COL_DEVICES).document(deviceId)
                .update(
                    mapOf(
                        "isOnline" to true,
                        "lastSeen" to FieldValue.serverTimestamp()
                    )
                ).await()
        } catch (e: Exception) {
            Log.w(TAG, "sendHeartbeat failed", e)
        }
    }

    suspend fun updateLastSeen(context: Context) {
        sendHeartbeat(context)
    }

    /** Lock-screen wallpaper URL stored on the device doc by the dealer app. */
    suspend fun fetchDealerWallpaperUrlFromDevice(context: Context): String? {
        return try {
            ensureAnonymousAuth()
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)
            val snap = db.collection(COL_DEVICES).document(deviceId).get().await()
            snap.getString("dealerWallpaperUrl")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchDealerWallpaperUrlFromDevice failed", e)
            null
        }
    }

    /** Load dealer + secure code for lock screen display and cache locally. */
    suspend fun fetchLockScreenData(context: Context) {
        val dealerId = PrefsHelper.getDealerId(context)
        if (dealerId.isNullOrBlank()) return

        try {
            ensureAnonymousAuth()
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)

            var dealerName = ""
            var dealerPhone: String? = null
            var secureCode: String? = null

            val dealerSnap = db.collection("dealers").document(dealerId).get().await()
            if (dealerSnap.exists()) {
                dealerName = dealerSnap.getString("name")
                    ?: dealerSnap.getString("dealerName")
                    ?: dealerSnap.getString("shopName")
                    ?: dealerSnap.getString("businessName")
                    ?: dealerId
                dealerPhone = dealerSnap.getString("phone")
                    ?: dealerSnap.getString("dealerPhone")
                    ?: dealerSnap.getString("mobile")
                    ?: dealerSnap.getString("whatsapp")
                val wallpaperUrl = dealerSnap.getString("wallpaper")
                PrefsHelper.setDealerWallpaperUrl(context, wallpaperUrl)
            }

            val deviceSnap = db.collection(COL_DEVICES).document(deviceId).get().await()
            if (deviceSnap.exists()) {
                secureCode = deviceSnap.getString("secureCode")
                if (dealerName.isBlank()) {
                    dealerName = deviceSnap.getString("dealerName") ?: dealerId
                }
                if (dealerPhone.isNullOrBlank()) {
                    dealerPhone = deviceSnap.getString("dealerPhone")
                }
            }

            PrefsHelper.setDealerInfo(
                context,
                dealerName.ifBlank { dealerId },
                dealerPhone,
                secureCode ?: PrefsHelper.getSecureCode(context)
            )
            Log.i(TAG, "Lock screen data loaded dealer=$dealerName phone=$dealerPhone")
        } catch (e: Exception) {
            Log.w(TAG, "fetchLockScreenData failed for dealerId=$dealerId", e)
        }
    }

    suspend fun fetchLockScreenDealerContactAndBankInfo(
        context: Context
    ): LockScreenDealerContactInfo? {
        val dealerId = PrefsHelper.getDealerId(context)?.trim().orEmpty()
        if (dealerId.isEmpty()) return null
        return try {
            ensureAnonymousAuth()
            val snap = db.collection("dealers").document(dealerId).get().await()
            if (!snap.exists()) return null
            LockScreenDealerContactInfo(
                dealerNumber = snap.getString("lockScreenDealerNumber")?.trim()?.takeIf { it.isNotEmpty() },
                wasooliNumber = snap.getString("lockScreenWasooliNumber")?.trim()?.takeIf { it.isNotEmpty() },
                managerNumber = snap.getString("lockScreenManagerNumber")?.trim()?.takeIf { it.isNotEmpty() },
                dealerName = snap.getString("lockScreenDealerName")?.trim()?.takeIf { it.isNotEmpty() },
                wasooliName = snap.getString("lockScreenWasooliName")?.trim()?.takeIf { it.isNotEmpty() },
                managerName = snap.getString("lockScreenManagerName")?.trim()?.takeIf { it.isNotEmpty() },
                bankAccountName = snap.getString("bankAccountName")?.trim()?.takeIf { it.isNotEmpty() },
                bankAccountNumber = snap.getString("bankAccountNumber")?.trim()?.takeIf { it.isNotEmpty() },
                brandColor = snap.getString("brandColor")?.trim()?.takeIf { it.isNotEmpty() },
                secondaryColor = snap.getString("secondaryColor")?.trim()?.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchLockScreenDealerContactAndBankInfo failed dealerId=$dealerId", e)
            null
        }
    }

    /** Load dealer display name from Firestore dealers/{dealerId} and cache locally. */
    suspend fun fetchAndCacheDealerName(context: Context): String {
        val dealerId = PrefsHelper.getDealerId(context)
        if (dealerId.isNullOrBlank()) {
            return PrefsHelper.getDealerName(context).ifBlank {
                context.getString(com.ibs.configapp.R.string.device_management_dealer_fallback)
            }
        }
        try {
            ensureAnonymousAuth()
            val snapshot = db.collection("dealers").document(dealerId).get().await()
            if (snapshot.exists()) {
                val name = snapshot.getString("name")
                    ?: snapshot.getString("dealerName")
                    ?: snapshot.getString("shopName")
                    ?: snapshot.getString("businessName")
                    ?: dealerId
                val phone = snapshot.getString("phone")
                    ?: snapshot.getString("dealerPhone")
                    ?: snapshot.getString("mobile")
                PrefsHelper.setDealerInfo(
                    context,
                    name,
                    phone,
                    PrefsHelper.getSecureCode(context)
                )
                Log.i(TAG, "Dealer name loaded from Firestore: $name")
                return name
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAndCacheDealerName failed for dealerId=$dealerId", e)
        }
        val cached = PrefsHelper.getDealerName(context)
        return cached.ifBlank { dealerId }
    }

    suspend fun updateLockStatus(context: Context, locked: Boolean) {
        ensureAnonymousAuth()
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        db.collection(COL_DEVICES).document(deviceId)
            .update("isLocked", locked)
            .await()
    }

    suspend fun updateCallBlockStatus(
        context: Context,
        incomingBlocked: Boolean,
        outgoingBlocked: Boolean
    ) {
        ensureAnonymousAuth()
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        db.collection(COL_DEVICES).document(deviceId)
            .update(
                mapOf(
                    "incomingBlocked" to incomingBlocked,
                    "outgoingBlocked" to outgoingBlocked
                )
            )
            .await()
    }

    suspend fun logSimChange(
        context: Context,
        oldSim: Map<String, String?>,
        newSim: Map<String, String?>
    ) {
        ensureAnonymousAuth()
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        val dealerId = PrefsHelper.getDealerId(context) ?: ""
        val entry = hashMapOf(
            "deviceId" to deviceId,
            "dealerId" to dealerId,
            "oldSim" to oldSim,
            "newSim" to newSim,
            "timestamp" to FieldValue.serverTimestamp()
        )
        db.collection(COL_SIM_CHANGE).add(entry).await()
        db.collection(COL_DEVICES).document(deviceId)
            .update(
                mapOf(
                    "lastSimChange" to FieldValue.serverTimestamp(),
                    "simAlert" to true
                )
            ).await()
    }

    /**
     * Caches SMS command authorization from Firestore into [PREFS_SMS_AUTH] for [SmsCommandReceiver].
     * Dealer numbers: lockScreenDealerNumber, lockScreenWasooliNumber, lockScreenManagerNumber on dealers/{dealerId}.
     * System master override: systemConfig/masterNumber field [masterNumber].
     */
    suspend fun syncSmsAuthorizationData(context: Context) {
        if (!PrefsHelper.isActivated(context)) return
        val dealerId = PrefsHelper.getDealerId(context)?.trim().orEmpty()
        if (dealerId.isEmpty()) {
            Log.d(TAG, "syncSmsAuthorizationData skipped: no dealerId")
            return
        }
        try {
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)
            val localSecretCode = PrefsHelper.getDeviceSecretCode(context)
            val deviceSnap = db.collection(COL_DEVICES).document(deviceId).get().await()
            val firestoreSecureCode = deviceSnap.getString("secureCode")
            if (firestoreSecureCode.isNullOrBlank() && localSecretCode.isNotBlank()) {
                db.collection(COL_DEVICES).document(deviceId)
                    .update("secureCode", localSecretCode)
                    .await()
                Log.i(TAG, "Backfilled missing secureCode in Firestore for deviceId=$deviceId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "secureCode backfill check failed", e)
        }
        try {
            ensureAnonymousAuth()
            val editor = context.getSharedPreferences(PREFS_SMS_AUTH, Context.MODE_PRIVATE).edit()

            try {
                val dealerSnap = db.collection("dealers").document(dealerId).get().await()
                if (dealerSnap.exists()) {
                    val numbers = lockScreenContactNumbersForSms(dealerSnap)
                    if (numbers.isNotEmpty()) {
                        editor.putString(PREF_KEY_AUTHORIZED_NUMBERS, JSONArray(numbers).toString())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncSmsAuthorizationData dealer fetch failed", e)
            }

            try {
                val masterSnap = db.collection(COL_SYSTEM_CONFIG).document(DOC_MASTER_NUMBER).get().await()
                if (masterSnap.exists()) {
                    val legacyMaster = masterSnap.getString("masterNumber")
                    val master1 = masterSnap.getString("masterNumber1") ?: legacyMaster
                    val master2 = masterSnap.getString("masterNumber2")
                    val master3 = masterSnap.getString("masterNumber3")
                    val masters = listOfNotNull(master1, master2, master3)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    if (masters.isNotEmpty()) {
                        editor.putString(PREF_KEY_MASTER_NUMBER, masters.first())
                        editor.putString(PREF_KEY_MASTER_NUMBERS, JSONArray(masters).toString())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncSmsAuthorizationData masterNumber fetch failed", e)
            }

            editor.apply()
            Log.d(TAG, "syncSmsAuthorizationData completed dealerId=$dealerId")
        } catch (e: Exception) {
            Log.w(TAG, "syncSmsAuthorizationData failed", e)
        }
    }

    private fun lockScreenContactNumbersForSms(snapshot: DocumentSnapshot): List<String> {
        val fields = listOf(
            "lockScreenDealerNumber",
            "lockScreenWasooliNumber",
            "lockScreenManagerNumber"
        )
        return fields.mapNotNull { field ->
            snapshot.getString(field)?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    suspend fun syncSimNumberIfChanged(context: Context, detectedNumber: String) {
        val normalized = detectedNumber.trim()
        if (normalized.isEmpty()) return

        try {
            ensureAnonymousAuth()
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)
            val snapshot = db.collection(COL_DEVICES).document(deviceId).get().await()
            val storedNumber = snapshot.getString("currentSimNumber")

            if (storedNumber == normalized) {
                Log.d(TAG, "SIM number unchanged for deviceId=$deviceId")
                return
            }

            val updates = mutableMapOf<String, Any>(
                "currentSimNumber" to normalized,
                "simNumberUpdatedAt" to FieldValue.serverTimestamp(),
                "simChangeAlertPending" to true
            )
            if (!storedNumber.isNullOrBlank()) {
                updates["previousSimNumber"] = storedNumber
            }

            db.collection(COL_DEVICES).document(deviceId)
                .set(updates, SetOptions.merge())
                .await()
            Log.i(
                TAG,
                "SIM number synced for deviceId=$deviceId previous=${storedNumber ?: "none"} current=$normalized"
            )
        } catch (e: Exception) {
            Log.e(TAG, "syncSimNumberIfChanged failed", e)
        }
    }

    suspend fun saveLocation(context: Context, location: Location) {
        try {
            ensureAnonymousAuth()
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)
            db.collection(COL_DEVICE_LOCATIONS).add(
                hashMapOf(
                    "deviceId" to deviceId,
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "timestamp" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            Log.e(TAG, "saveLocation failed", e)
            throw e
        }
    }

    suspend fun reportAppUpdateStatus(context: Context, status: String, error: String? = null) {
        ensureAnonymousAuth()
        val deviceId = PrefsHelper.getOrCreateDeviceId(context)
        val updates = mutableMapOf<String, Any>(
            "appUpdateStatus" to status,
            "appUpdateAt" to FieldValue.serverTimestamp()
        )
        if (!error.isNullOrBlank()) {
            updates["appUpdateError"] = error
        }
        db.collection(COL_DEVICES).document(deviceId)
            .update(updates)
            .await()
    }

    suspend fun reportSmsDebug(context: Context, message: String) {
        try {
            ensureAnonymousAuth()
            val deviceId = PrefsHelper.getOrCreateDeviceId(context)
            db.collection(COL_DEVICES).document(deviceId)
                .update(
                    mapOf(
                        "smsDebugLog" to message,
                        "smsDebugAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "reportSmsDebug failed", e)
        }
    }
}
