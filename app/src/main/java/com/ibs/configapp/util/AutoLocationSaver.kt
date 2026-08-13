package com.ibs.configapp.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.ibs.configapp.firebase.FirestoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

object AutoLocationSaver {

    private const val TAG = "AutoLocationSaver"
    private val locationFetchInProgress = AtomicBoolean(false)

    fun saveLocationIfPossible(context: Context) {
        if (!locationFetchInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping auto location save: previous fetch still in progress")
            return
        }
        try {
            if (!PermissionChecker.hasLocationPermissions(context)) {
                Log.d(TAG, "Skipping auto location save: permissions not granted")
                clearInFlight()
                return
            }

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    try {
                        if (location != null) {
                            persistLocation(context, location)
                        } else {
                            requestOneShotLocation(context, fusedLocationClient)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "lastLocation success handler failed", e)
                        clearInFlight()
                    }
                }
                .addOnFailureListener { error ->
                    try {
                        Log.w(TAG, "lastLocation failed, requesting one-shot location", error)
                        requestOneShotLocation(context, fusedLocationClient)
                    } catch (e: Exception) {
                        Log.w(TAG, "lastLocation failure handler failed", e)
                        clearInFlight()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "saveLocationIfPossible failed", e)
            clearInFlight()
        }
    }

    private fun clearInFlight() {
        locationFetchInProgress.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun requestOneShotLocation(
        context: Context,
        fusedLocationClient: FusedLocationProviderClient
    ) {
        try {
            val locationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                numUpdates = 1
                interval = 1000
            }
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    try {
                        fusedLocationClient.removeLocationUpdates(this)
                        val location = result.lastLocation
                        if (location != null) {
                            persistLocation(context, location)
                        } else {
                            Log.w(TAG, "One-shot location result was null")
                            clearInFlight()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "One-shot location callback failed", e)
                        clearInFlight()
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestOneShotLocation failed", e)
            clearInFlight()
        }
    }

    private fun persistLocation(context: Context, location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
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
                Log.i(
                    TAG,
                    "Auto location saved lat=${location.latitude} lng=${location.longitude}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Auto location Firestore write failed", e)
            } finally {
                clearInFlight()
            }
        }
    }
}
