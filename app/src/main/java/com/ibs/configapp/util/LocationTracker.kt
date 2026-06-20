package com.ibs.configapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.ibs.configapp.firebase.FirestoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocationTracker(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: LocationCallback? = null
    private var locationTimeoutRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun fetchAndSave(onComplete: ((Boolean) -> Unit)? = null) {
        try {
            if (!hasLocationPermission()) {
                Log.w(TAG, "Location permission missing")
                onComplete?.invoke(false)
                return
            }

            acquireWakeLock()
            scheduleLocationTimeout(onComplete)
            requestGpsLocation(onComplete)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndSave failed", e)
            finish(false, onComplete)
        }
    }

    private fun acquireWakeLock() {
        try {
            releaseWakeLock()
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ibs:location").apply {
                setReferenceCounted(false)
                acquire(LOCATION_TIMEOUT_MS + 15_000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed", e)
        } finally {
            wakeLock = null
        }
    }

    private fun scheduleLocationTimeout(onComplete: ((Boolean) -> Unit)?) {
        clearLocationTimeout()
        locationTimeoutRunnable = Runnable {
            Log.w(TAG, "Location fetch timed out, trying last known location")
            tryLastKnownLocation(onComplete)
        }
        mainHandler.postDelayed(locationTimeoutRunnable!!, LOCATION_TIMEOUT_MS)
    }

    private fun clearLocationTimeout() {
        locationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        locationTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun requestGpsLocation(onComplete: ((Boolean) -> Unit)?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val tokenSource = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        try {
                            if (location != null) {
                                persistLocation(location, onComplete)
                            } else {
                                Log.w(TAG, "GPS location null, trying network fallback")
                                requestNetworkLocation(onComplete)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "GPS success handler failed", e)
                            requestNetworkLocation(onComplete)
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "GPS location failed, trying network fallback", error)
                        requestNetworkLocation(onComplete)
                    }
                return
            }
            requestLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, onComplete) {
                requestNetworkLocation(onComplete)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestGpsLocation failed", e)
            requestNetworkLocation(onComplete)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNetworkLocation(onComplete: ((Boolean) -> Unit)?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val tokenSource = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        try {
                            if (location != null) {
                                persistLocation(location, onComplete)
                            } else {
                                Log.w(TAG, "Network location null, trying last known location")
                                tryLastKnownLocation(onComplete)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Network success handler failed", e)
                            tryLastKnownLocation(onComplete)
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Network location failed, trying last known location", error)
                        tryLastKnownLocation(onComplete)
                    }
                return
            }
            requestLocationUpdates(Priority.PRIORITY_BALANCED_POWER_ACCURACY, onComplete) {
                tryLastKnownLocation(onComplete)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestNetworkLocation failed", e)
            tryLastKnownLocation(onComplete)
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryLastKnownLocation(onComplete: ((Boolean) -> Unit)?) {
        try {
            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        persistLocation(location, onComplete)
                    } else {
                        finish(false, onComplete)
                    }
                }
                .addOnFailureListener {
                    finish(false, onComplete)
                }
        } catch (e: Exception) {
            Log.e(TAG, "tryLastKnownLocation failed", e)
            finish(false, onComplete)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(
        priority: Int,
        onComplete: ((Boolean) -> Unit)?,
        onFailure: () -> Unit
    ) {
        try {
            val request = LocationRequest.Builder(priority, 10_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .setMaxUpdates(1)
                .build()

            callback?.let { fusedClient.removeLocationUpdates(it) }
            callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    try {
                        val loc = result.lastLocation
                        callback?.let { fusedClient.removeLocationUpdates(it) }
                        callback = null
                        if (loc != null) {
                            persistLocation(loc, onComplete)
                        } else {
                            onFailure()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Location callback failed", e)
                        onFailure()
                    }
                }
            }
            fusedClient.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed priority=$priority", e)
            onFailure()
        }
    }

    private fun persistLocation(location: Location, onComplete: ((Boolean) -> Unit)?) {
        clearLocationTimeout()
        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            try {
                FirestoreManager.saveLocation(context, location)
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "persistLocation failed", e)
            }
            mainHandler.post { finish(success, onComplete) }
        }
    }

    private fun finish(success: Boolean, onComplete: ((Boolean) -> Unit)?) {
        try {
            clearLocationTimeout()
            callback?.let { fusedClient.removeLocationUpdates(it) }
            callback = null
            releaseWakeLock()
            onComplete?.invoke(success)
        } catch (e: Exception) {
            Log.e(TAG, "finish failed", e)
            try {
                onComplete?.invoke(false)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            clearLocationTimeout()
            callback?.let { fusedClient.removeLocationUpdates(it) }
            callback = null
            releaseWakeLock()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
    }

    companion object {
        private const val TAG = "LocationTracker"
        private const val LOCATION_TIMEOUT_MS = 30_000L
    }
}
