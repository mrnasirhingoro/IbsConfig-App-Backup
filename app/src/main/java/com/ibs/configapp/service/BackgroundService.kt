package com.ibs.configapp.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.util.CallBlockManager
import com.ibs.configapp.util.CommandHandler
import com.ibs.configapp.util.DeviceProtectionManager
import com.ibs.configapp.util.LocationTracker
import com.ibs.configapp.util.NotificationHelper
import com.ibs.configapp.util.PrefsHelper
import com.ibs.configapp.util.SimMonitor
import com.ibs.configapp.util.WakeLockHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BackgroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var firestoreListener: ListenerRegistration? = null
    private var locationTracker: LocationTracker? = null
    private var lastProcessedCommandId: String? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            serviceScope.launch {
                try {
                    FirestoreManager.sendHeartbeat(this@BackgroundService)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed", e)
                }
            }
            ensureFirestoreListener()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val locationRunnable = object : Runnable {
        override fun run() {
            try {
                locationTracker?.fetchAndSave()
                SimMonitor.checkSimChange(this@BackgroundService)
            } catch (e: Exception) {
                Log.w(TAG, "Location/SIM check failed", e)
            }
            handler.postDelayed(this, LOCATION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotificationImmediately()
        try {
            RestartJobService.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "RestartJob schedule on create failed", e)
        }
        try {
            initializeServiceSafely()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed on MIUI/restricted device", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotificationImmediately()
        ensureFirestoreListener()
        serviceScope.launch {
            try {
                FirestoreManager.setOnlineStatus(this@BackgroundService, true)
            } catch (e: Exception) {
                Log.w(TAG, "setOnline on startCommand failed", e)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(locationRunnable)
        try {
            firestoreListener?.remove()
            firestoreListener = null
        } catch (e: Exception) {
            Log.w(TAG, "Firestore listener remove failed", e)
        }
        try {
            locationTracker?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Location tracker stop failed", e)
        }
        try {
            runBlocking {
                withTimeout(8_000) {
                    FirestoreManager.setOnlineStatus(this@BackgroundService, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setOffline on destroy failed", e)
        }
        serviceScope.cancel()
        try {
            RestartJobService.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "RestartJob schedule failed", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            RestartJobService.schedule(this)
            start(this)
        } catch (e: Exception) {
            Log.w(TAG, "onTaskRemoved recovery failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundNotificationImmediately() {
        try {
            NotificationHelper.createChannel(this)
            resolveDealerNameForNotification()
            val notification = NotificationHelper.buildProtectionNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.FOREGROUND_ID,
                    notification,
                    buildForegroundServiceTypes()
                )
            } else {
                startForeground(NotificationHelper.FOREGROUND_ID, notification)
            }
            Log.i(
                TAG,
                "Foreground notification started channel=${NotificationHelper.CHANNEL_ID} " +
                    "dealer=${NotificationHelper.getDisplayDealerName(this)}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Primary startForeground failed, using minimal notification", e)
            try {
                val fallback = NotificationHelper.buildMinimalNotification(this)
                startForeground(NotificationHelper.FOREGROUND_ID, fallback)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback foreground start failed", e2)
            }
        }
    }

    private fun resolveDealerNameForNotification() {
        if (!PrefsHelper.isActivated(this)) return
        try {
            runBlocking {
                withTimeout(5_000) {
                    FirestoreManager.fetchAndCacheDealerName(this@BackgroundService)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dealer name Firestore lookup failed, using prefs fallback", e)
        }
    }

    private fun refreshProtectionNotification() {
        try {
            val notification = NotificationHelper.buildProtectionNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.FOREGROUND_ID,
                    notification,
                    buildForegroundServiceTypes()
                )
            } else {
                startForeground(NotificationHelper.FOREGROUND_ID, notification)
            }
            getSystemService(NotificationManager::class.java)
                .notify(NotificationHelper.FOREGROUND_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "refreshProtectionNotification failed", e)
        }
    }

    private fun ensureFirestoreListener() {
        if (!PrefsHelper.isActivated(this)) return
        if (firestoreListener != null) return
        attachFirestoreListener()
    }

    private fun attachFirestoreListener() {
        if (!PrefsHelper.isActivated(this)) return
        serviceScope.launch {
            try {
                FirestoreManager.ensureAuthenticated(this@BackgroundService)
            } catch (e: Exception) {
                Log.w(TAG, "Firestore auth before listener failed", e)
            }

            handler.post {
                try {
                    firestoreListener?.remove()
                    firestoreListener = FirestoreManager.listenDeviceCommands(
                        this@BackgroundService,
                        onCommand = { command, data -> executeDeviceCommand(command, data) },
                        onDeviceUpdate = { map ->
                            try {
                                CommandHandler.handleFromFirestoreField(this@BackgroundService, map)
                                refreshProtectionNotification()
                            } catch (e: Exception) {
                                Log.w(TAG, "Device update handler failed", e)
                            }
                        }
                    )
                    Log.i(TAG, "Firestore command listener attached")
                } catch (e: Exception) {
                    Log.e(TAG, "Firestore listener setup failed", e)
                    firestoreListener = null
                }
            }
        }
    }

    private fun executeDeviceCommand(command: String, data: Map<String, Any?>) {
        val commandId = data["commandId"] as? String
        if (!commandId.isNullOrBlank() && commandId == lastProcessedCommandId) {
            Log.d(TAG, "Skipping duplicate commandId=$commandId")
            return
        }

        WakeLockHelper.withWakeLock(this, "ibs:device-command", 120_000L) {
            val normalized = CommandHandler.normalizeCommand(command)
            if (normalized == "unlock") {
                CommandHandler.unlockDevice(this)
                serviceScope.launch {
                    try {
                        FirestoreManager.markCommandExecuted(
                            this@BackgroundService,
                            data["commandId"] as? String,
                            true
                        )
                        val commandId = data["commandId"] as? String
                        if (!commandId.isNullOrBlank()) lastProcessedCommandId = commandId
                    } catch (e: Exception) {
                        Log.e(TAG, "markCommandExecuted failed for unlock", e)
                    }
                }
                return@withWakeLock
            }

            val enriched = CommandHandler.enrichCommandData(this, data)
            CommandHandler.handle(this, command, enriched) { success ->
                serviceScope.launch {
                    try {
                        FirestoreManager.markCommandExecuted(
                            this@BackgroundService,
                            commandId,
                            success
                        )
                        if (success && !commandId.isNullOrBlank()) {
                            lastProcessedCommandId = commandId
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "markCommandExecuted failed command=$command", e)
                    }
                }
            }
        }
    }

    private fun initializeServiceSafely() {
        try {
            DeviceProtectionManager.applyDeviceOwnerPolicies(this)
        } catch (e: Exception) {
            Log.w(TAG, "applyDeviceOwnerPolicies failed", e)
        }

        try {
            locationTracker = LocationTracker(this)
        } catch (e: Exception) {
            Log.w(TAG, "LocationTracker init failed", e)
        }

        try {
            CallBlockManager.restoreFromPrefs(this)
        } catch (e: Exception) {
            Log.w(TAG, "CallBlockManager restore failed", e)
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                try {
                    if (task.isSuccessful) {
                        task.result?.let { token ->
                            PrefsHelper.setFcmToken(this, token)
                            serviceScope.launch {
                                try {
                                    FirestoreManager.updateFcmToken(this@BackgroundService, token)
                                } catch (e: Exception) {
                                    Log.w(TAG, "FCM token Firestore update failed", e)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "FCM token listener failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token fetch failed", e)
        }

        attachFirestoreListener()

        serviceScope.launch {
            try {
                FirestoreManager.fetchAndCacheDealerName(this@BackgroundService)
                refreshProtectionNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Dealer name fetch failed", e)
            }
        }

        serviceScope.launch {
            try {
                FirestoreManager.setOnlineStatus(this@BackgroundService, true)
            } catch (e: Exception) {
                Log.w(TAG, "setOnline on create failed", e)
            }
        }

        try {
            handler.post(heartbeatRunnable)
            handler.post(locationRunnable)
        } catch (e: Exception) {
            Log.w(TAG, "Handler post failed", e)
        }

        try {
            if (PrefsHelper.isLocked(this)) {
                CommandHandler.handle(this, "lock", emptyMap())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lock screen trigger failed", e)
        }
    }

    private fun buildForegroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }

    companion object {
        private const val TAG = "BackgroundService"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        private const val LOCATION_INTERVAL_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            if (!PrefsHelper.isActivated(context)) return
            try {
                RestartJobService.schedule(context)
            } catch (e: Exception) {
                Log.w(TAG, "RestartJob schedule from start() failed", e)
            }
            try {
                val intent = Intent(context, BackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed (MIUI?), trying startService", e)
                try {
                    context.startService(Intent(context, BackgroundService::class.java))
                } catch (e2: Exception) {
                    Log.e(TAG, "startService fallback failed", e2)
                }
            }
        }
    }
}
