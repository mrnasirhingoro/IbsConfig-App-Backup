package com.ibs.configapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun fetchAndSave(onComplete: ((Boolean) -> Unit)? = null) {
        try {
            if (!hasLocationPermission()) {
                Log.w(TAG, "Location permission missing")
                onComplete?.invoke(false)
                return
            }

            scheduleLocationTimeout(onComplete)

            fusedClient.lastLocation
                .addOnSuccessListener { lastLocation ->
                    if (lastLocation != null) {
                        persistLocation(lastLocation, onComplete)
                    } else {
                        requestFreshLocation(onComplete)
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "lastLocation failed, requesting fresh location", it)
                    requestFreshLocation(onComplete)
                }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndSave failed", e)
            clearLocationTimeout()
            onComplete?.invoke(false)
        }
    }

    private fun scheduleLocationTimeout(onComplete: ((Boolean) -> Unit)?) {
        clearLocationTimeout()
        locationTimeoutRunnable = Runnable {
            Log.w(TAG, "Location fetch timed out")
            stop()
            onComplete?.invoke(false)
        }
        mainHandler.postDelayed(locationTimeoutRunnable!!, LOCATION_TIMEOUT_MS)
    }

    private fun clearLocationTimeout() {
        locationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        locationTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(onComplete: ((Boolean) -> Unit)?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val tokenSource = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            persistLocation(location, onComplete)
                        } else {
                            requestLocationUpdatesFallback(onComplete)
                        }
                    }
                    .addOnFailureListener {
                        requestLocationUpdatesFallback(onComplete)
                    }
                return
            }
            requestLocationUpdatesFallback(onComplete)
        } catch (e: Exception) {
            Log.e(TAG, "requestFreshLocation failed", e)
            onComplete?.invoke(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdatesFallback(onComplete: ((Boolean) -> Unit)?) {
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
                .setMinUpdateIntervalMillis(5_000L)
                .setMaxUpdates(1)
                .build()

            callback?.let { fusedClient.removeLocationUpdates(it) }
            callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation
                    callback?.let { fusedClient.removeLocationUpdates(it) }
                    callback = null
                    if (loc != null) {
                        persistLocation(loc, onComplete)
                    } else {
                        onComplete?.invoke(false)
                    }
                }
            }
            fusedClient.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdatesFallback failed", e)
            onComplete?.invoke(false)
        }
    }

    private fun persistLocation(location: Location, onComplete: ((Boolean) -> Unit)?) {
        clearLocationTimeout()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirestoreManager.saveLocation(context, location)
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "persistLocation failed", e)
                onComplete?.invoke(false)
            }
        }
    }

    fun stop() {
        clearLocationTimeout()
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
    }

    companion object {
        private const val TAG = "LocationTracker"
        private const val LOCATION_TIMEOUT_MS = 45_000L
    }
}
