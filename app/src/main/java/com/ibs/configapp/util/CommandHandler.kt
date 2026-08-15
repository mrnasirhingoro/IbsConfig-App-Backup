package com.ibs.configapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.ibs.configapp.IbsDeviceAdminReceiver
import com.ibs.configapp.LockScreenActivity
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.service.BackgroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object CommandHandler {
    private const val TAG = "CommandHandler"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun normalizeCommand(raw: String): String {
        val compact = raw.trim().lowercase().replace("-", "").replace("_", "")
        return when (compact) {
            "getlocation", "location" -> "get_location"
            "releasedevice" -> "release"
            "blockincoming", "blockcalls" -> "block_incoming"
            "blockoutgoing" -> "block_outgoing"
            "unblockcalls" -> "unblock_calls"
            "blockapps", "blocksocialapps" -> "block_apps"
            "unblockapps", "unblocksocialapps" -> "unblock_apps"
            else -> raw.trim().lowercase().replace("-", "_")
        }
    }

    fun handle(
        context: Context,
        command: String,
        data: Map<String, Any?>,
        onComplete: ((Boolean) -> Unit)? = null,
        reportLocalSuccessOnly: Boolean = false
    ) {
        val normalized = normalizeCommand(command)
        Log.i(TAG, "Executing command raw=$command normalized=$normalized")

        val runBlock = {
            var success = true
            var asyncCompletion = false
            try {
                when (normalized) {
                    "lock" -> {
                        asyncCompletion = true
                        lockDevice(context, data, { lockSuccess ->
                            mainHandler.post { onComplete?.invoke(lockSuccess) }
                        }, reportLocalSuccessOnly)
                    }
                    "unlock" -> {
                        asyncCompletion = true
                        unlockDevice(context, data, { unlockSuccess ->
                            mainHandler.post { onComplete?.invoke(unlockSuccess) }
                        }, reportLocalSuccessOnly)
                    }
                    "get_location" -> {
                        asyncCompletion = true
                        handleGetLocationCommand(context, data) { locationSuccess ->
                            mainHandler.post { onComplete?.invoke(locationSuccess) }
                        }
                    }
                    "alert" -> showAlert(context, data)
                    "block_incoming" -> {
                        CallBlockManager.setIncomingBlocked(context, true)
                        PrefsHelper.setIncomingCallsBlocked(context, true)
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = try {
                                FirestoreManager.updateCallBlockStatus(
                                    context,
                                    incomingBlocked = true,
                                    outgoingBlocked = PrefsHelper.isOutgoingCallsBlocked(context)
                                )
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "updateCallBlockStatus failed for block_incoming", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(success) }
                        }
                    }
                    "block_outgoing" -> {
                        CallBlockManager.setOutgoingBlocked(context, true)
                        PrefsHelper.setOutgoingCallsBlocked(context, true)
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = try {
                                FirestoreManager.updateCallBlockStatus(
                                    context,
                                    incomingBlocked = PrefsHelper.isIncomingCallsBlocked(context),
                                    outgoingBlocked = true
                                )
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "updateCallBlockStatus failed for block_outgoing", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(success) }
                        }
                    }
                    "unblock_calls" -> {
                        CallBlockManager.setBlocked(context, false)
                        PrefsHelper.setIncomingCallsBlocked(context, false)
                        PrefsHelper.setOutgoingCallsBlocked(context, false)
                        PrefsHelper.setCallsBlocked(context, false)
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = try {
                                FirestoreManager.updateCallBlockStatus(
                                    context,
                                    incomingBlocked = false,
                                    outgoingBlocked = false
                                )
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "updateCallBlockStatus failed for unblock_calls", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(success) }
                        }
                    }
                    "block_apps" -> {
                        PrefsHelper.setAppsBlocked(context, true)
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = try {
                                val deviceId = PrefsHelper.getOrCreateDeviceId(context)
                                FirebaseFirestore.getInstance()
                                    .collection("devices")
                                    .document(deviceId)
                                    .update(
                                        mapOf(
                                            "appsBlocked" to true
                                        )
                                    )
                                    .await()
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "blockApps Firestore update failed", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(success) }
                        }
                    }
                    "unblock_apps" -> {
                        PrefsHelper.setAppsBlocked(context, false)
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = try {
                                val deviceId = PrefsHelper.getOrCreateDeviceId(context)
                                FirebaseFirestore.getInstance()
                                    .collection("devices")
                                    .document(deviceId)
                                    .update(
                                        mapOf(
                                            "appsBlocked" to false
                                        )
                                    )
                                    .await()
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "unblockApps Firestore update failed", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(success) }
                        }
                    }
                    "set_reminder_wallpaper" -> {
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val imageUrl = data["imageUrl"] as? String
                            val wallpaperSuccess = try {
                                ReminderWallpaperHelper.setFromUrl(context, imageUrl)
                            } catch (e: Exception) {
                                Log.e(TAG, "set_reminder_wallpaper failed", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(wallpaperSuccess) }
                        }
                    }
                    "remove_reminder_wallpaper" -> {
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val clearSuccess = try {
                                ReminderWallpaperHelper.clearReminderWallpaper(context)
                            } catch (e: Exception) {
                                Log.e(TAG, "remove_reminder_wallpaper failed", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(clearSuccess) }
                        }
                    }
                    "payment_reminder_notification" -> {
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val notifySuccess = try {
                                val message = data["message"] as? String
                                if (message.isNullOrBlank()) {
                                    Log.w(TAG, "payment_reminder_notification missing message")
                                    false
                                } else if (!PermissionChecker.areNotificationsEnabled(context)) {
                                    Log.w(
                                        TAG,
                                        "payment_reminder_notification skipped: notifications disabled"
                                    )
                                    false
                                } else {
                                    NotificationHelper.showPaymentReminderNotification(context, message)
                                    true
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "payment_reminder_notification failed", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(notifySuccess) }
                        }
                    }
                    "update_app" -> {
                        asyncCompletion = true
                        CoroutineScope(Dispatchers.IO).launch {
                            val apkUrl = data["apkUrl"] as? String ?: ""
                            val checksum = data["checksum"] as? String
                            val updateSuccess = try {
                                AppUpdateManager.performUpdate(context, apkUrl, checksum)
                            } catch (e: Exception) {
                                Log.e(TAG, "update_app command failed", e)
                                false
                            }
                            mainHandler.post { onComplete?.invoke(updateSuccess) }
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unknown command: $command")
                        success = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed command=$normalized", e)
                success = false
            }
            if (!asyncCompletion) {
                onComplete?.invoke(success)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runBlock()
        } else {
            mainHandler.post { runBlock() }
        }
    }

    fun handleFromFirestoreField(context: Context, map: Map<String, Any?>) {
        if (BackgroundService.isUnlockInProgress) return
        val firestoreLocked = map["isLocked"] as? Boolean ?: false
        when {
            firestoreLocked && !PrefsHelper.isLocked(context) -> lockDevice(context)
            !firestoreLocked && PrefsHelper.isLocked(context) -> unlockDevice(context)
        }
    }

    suspend fun enrichCommandData(context: Context, data: Map<String, Any?>): Map<String, Any?> {
        val commandId = data["commandId"] as? String ?: return data
        if (data["message"] != null) return data
        return try {
            val details = FirestoreManager.fetchCommandDetails(commandId)
            if (details.isEmpty()) data else data + details
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load command details for $commandId", e)
            data
        }
    }

    private fun lockDevice(
        context: Context,
        data: Map<String, Any?> = emptyMap(),
        onComplete: ((Boolean) -> Unit)? = null,
        reportLocalSuccessOnly: Boolean = false
    ) {
        Log.i(TAG, "Lock command received")
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, IbsDeviceAdminReceiver::class.java)

        mainHandler.post {
            var lockSuccess = false
            try {
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    Log.e(
                        TAG,
                        "Lock command failed: app is not Device Owner " +
                            "(isAdminActive=${dpm.isAdminActive(componentName)})"
                    )
                    onComplete?.invoke(false)
                    return@post
                }

                try {
                    Log.i(TAG, "Device Owner verified, calling lockNow()")
                    dpm.lockNow()
                    Log.i(TAG, "lockNow() called successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "lockNow() failed, continuing with custom lock screen", e)
                }

                PrefsHelper.setLocked(context, true)
                launchLockScreenActivity(context)
                lockSuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "lockDevice failed", e)
            }

            if (!lockSuccess) {
                onComplete?.invoke(false)
                return@post
            }

            CoroutineScope(Dispatchers.IO).launch {
                var firestoreSuccess = false
                try {
                    FirestoreManager.updateLockStatus(context, true)
                    firestoreSuccess = true
                    Log.i(TAG, "Lock applied in Firestore: isLocked=true")
                } catch (e: Exception) {
                    Log.e(TAG, "Firestore lock update failed", e)
                }
                mainHandler.post {
                    onComplete?.invoke(
                        if (reportLocalSuccessOnly) lockSuccess else firestoreSuccess
                    )
                }
            }
        }
    }

    private fun launchLockScreenActivity(context: Context) {
        val lockIntent = Intent(context, LockScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        context.startActivity(lockIntent)
    }

    fun unlockDevice(
        context: Context,
        data: Map<String, Any?> = emptyMap(),
        onComplete: ((Boolean) -> Unit)? = null,
        reportLocalSuccessOnly: Boolean = false
    ) {
        Log.i(TAG, "Unlock command received")
        BackgroundService.beginUnlock()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, IbsDeviceAdminReceiver::class.java)

        mainHandler.post {
            var unlockSuccess = false
            try {
                val canUnlock = dpm.isDeviceOwnerApp(context.packageName) ||
                    dpm.isAdminActive(componentName)
                if (canUnlock) {
                    // Finance unlock is app-level: clear lock state and dismiss custom lock UI.
                    // Device Owner cannot reliably clear keyguard via resetPassword on Android 10+.
                    PrefsHelper.setLocked(context, false)
                    dismissUnlockUi(context)
                    unlockSuccess = true
                    Log.i(TAG, "Unlock applied: isLocked=false, lock screen dismissed")
                } else {
                    Log.e(TAG, "Unlock failed: device admin not active")
                }
            } catch (e: Exception) {
                Log.e(TAG, "unlockDevice failed", e)
            }

            CoroutineScope(Dispatchers.IO).launch {
                var success = unlockSuccess
                try {
                    if (unlockSuccess) {
                        FirestoreManager.updateLockStatus(context, false)
                        Log.i(TAG, "Unlock applied in Firestore: isLocked=false")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Firestore unlock update failed", e)
                    success = false
                }
                mainHandler.post {
                    onComplete?.invoke(
                        if (reportLocalSuccessOnly) unlockSuccess else success
                    )
                }
            }
        }
    }

    private fun dismissUnlockUi(context: Context) {
        LockScreenActivity.dismissIfActive()
        val unlockIntent = Intent(LockScreenActivity.ACTION_UNLOCK_DEVICE).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(unlockIntent)
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(LockScreenActivity.ACTION_UNLOCK_DEVICE))
    }

    private fun showAlert(context: Context, data: Map<String, Any?>) {
        val message = data["message"] as? String
            ?: context.getString(com.ibs.configapp.R.string.alert_default_message)
        val title = data["title"] as? String
            ?: context.getString(com.ibs.configapp.R.string.alert_default_title)
        NotificationHelper.showAlertNotification(context, title, message)
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private fun handleGetLocationCommand(
        context: Context,
        data: Map<String, Any?>,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        Log.i(TAG, "get_location command received")
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Location permission missing")
            onComplete?.invoke(false)
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    sendLocationToFirestore(context, location, data, onComplete)
                } else {
                    requestFreshLocation(context, fusedLocationClient, data, onComplete)
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "lastLocation failed, requesting fresh location", error)
                requestFreshLocation(context, fusedLocationClient, data, onComplete)
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(
        context: Context,
        fusedLocationClient: FusedLocationProviderClient,
        data: Map<String, Any?>,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
            interval = 1000
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedLocationClient.removeLocationUpdates(this)
                val location = result.lastLocation
                if (location != null) {
                    sendLocationToFirestore(context, location, data, onComplete)
                } else {
                    Log.w(TAG, "Fresh location result was null")
                    onComplete?.invoke(false)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )
    }

    private fun sendLocationToFirestore(
        context: Context,
        location: Location,
        data: Map<String, Any?>,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            try {
                FirestoreManager.ensureAuthenticated(context)
                val deviceId = PrefsHelper.getOrCreateDeviceId(context)
                val db = FirebaseFirestore.getInstance()
                db.collection("deviceLocations").add(
                    hashMapOf(
                        "deviceId" to deviceId,
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "timestamp" to FieldValue.serverTimestamp(),
                        "accuracy" to location.accuracy
                    )
                ).await()
                db.collection("devices").document(deviceId).update(
                    mapOf(
                        "lastLatitude" to location.latitude,
                        "lastLongitude" to location.longitude,
                        "lastLocationTime" to FieldValue.serverTimestamp()
                    )
                ).await()
                Log.i(TAG, "Location sent lat=${location.latitude} lng=${location.longitude}")
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "sendLocationToFirestore failed", e)
            }
            mainHandler.post { onComplete?.invoke(success) }
        }
    }
}
