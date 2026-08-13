package com.ibs.configapp.service

import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.ibs.configapp.IbsDeviceAdminReceiver
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.util.CallBlockManager
import com.ibs.configapp.util.CommandHandler
import com.ibs.configapp.util.DeviceProtectionManager
import com.ibs.configapp.util.NotificationHelper
import com.ibs.configapp.util.PrefsHelper
import com.ibs.configapp.util.SimMonitor
import com.ibs.configapp.util.SimNumberMonitor
import com.ibs.configapp.util.AutoLocationSaver
import com.ibs.configapp.util.WakeLockHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections

class BackgroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var firestoreListener: ListenerRegistration? = null
    private var deviceCommandsListener: ListenerRegistration? = null
    private var releaseCommandListenerByDevice: ListenerRegistration? = null
    private var releaseCommandListenerByCustomer: ListenerRegistration? = null
    private val processedReleaseDocIds = mutableSetOf<String>()
    private val processedCommandIds =
        Collections.synchronizedSet(mutableSetOf<String>())
    private val recentlyProcessedCommands =
        Collections.synchronizedMap(mutableMapOf<String, Long>())
    private var notificationRefreshScheduled = false
    private var heartbeatStarted = false
    private var lastStartTime = 0L
    private var isAttachingListener = false
    private var isAttachingDeviceCommandsListener = false
    private var isAttachingReleaseListener = false

    private val refreshNotificationRunnable = Runnable {
        notificationRefreshScheduled = false
        refreshProtectionNotification()
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            serviceScope.launch {
                try {
                    FirestoreManager.sendHeartbeat(this@BackgroundService)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed", e)
                }
                try {
                    SimMonitor.checkSimChange(this@BackgroundService)
                } catch (e: Exception) {
                    Log.w(TAG, "SIM check failed", e)
                }
                try {
                    DeviceProtectionManager.enforceLocationPolicy(this@BackgroundService)
                } catch (e: Exception) {
                    Log.w(TAG, "enforceLocationPolicy failed", e)
                }
                try {
                    AutoLocationSaver.saveLocationIfPossible(this@BackgroundService)
                } catch (e: Exception) {
                    Log.w(TAG, "Auto location save failed", e)
                }
                try {
                    FirestoreManager.syncSmsAuthorizationData(this@BackgroundService)
                } catch (e: Exception) {
                    Log.w(TAG, "SMS authorization sync failed", e)
                }
            }
            ensureFirestoreListener()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            try {
                DeviceProtectionManager.applyAllUserRestrictions(this@BackgroundService)
            } catch (e: Exception) {
                Log.w(TAG, "Factory reset restrictions failed", e)
            }
        }
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
        if (intent?.action == ACTION_EXECUTE_RELEASE) {
            startForegroundNotificationImmediately()
            handler.post { executeRelease() }
            return START_STICKY
        }

        val now = System.currentTimeMillis()
        if (now - lastStartTime < 5000) {
            return START_STICKY
        }
        lastStartTime = now
        startForegroundNotificationImmediately()
        ensureFirestoreListener()
        serviceScope.launch {
            try {
                FirestoreManager.setOnlineStatus(this@BackgroundService, true)
                FirestoreManager.sendHeartbeat(this@BackgroundService)
            } catch (e: Exception) {
                Log.w(TAG, "setOnline on startCommand failed", e)
            }
        }
        if (!heartbeatStarted) {
            heartbeatStarted = true
            handler.post(heartbeatRunnable)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(refreshNotificationRunnable)
        try {
            firestoreListener?.remove()
            firestoreListener = null
            deviceCommandsListener?.remove()
            deviceCommandsListener = null
            releaseCommandListenerByDevice?.remove()
            releaseCommandListenerByDevice = null
            releaseCommandListenerByCustomer?.remove()
            releaseCommandListenerByCustomer = null
        } catch (e: Exception) {
            Log.w(TAG, "Firestore listener remove failed", e)
        }
        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    FirestoreManager.setOnlineStatus(appContext, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "setOffline on destroy failed", e)
            }
        }
        serviceScope.cancel()
        if (pendingReleaseOnDestroy) {
            pendingReleaseOnDestroy = false
            try {
                DeviceProtectionManager.releaseDevice(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "releaseDevice on destroy failed", e)
            }
        }
        if (PrefsHelper.isActivated(applicationContext)) {
            try {
                start(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "BackgroundService restart on destroy failed", e)
            }
        }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NotificationHelper.FOREGROUND_ID,
                        fallback,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NotificationHelper.FOREGROUND_ID, fallback)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback foreground start failed", e2)
            }
        }
    }

    private fun resolveDealerNameForNotification() {
        // Use cached prefs immediately; async refresh runs in initializeServiceSafely().
    }

    private fun scheduleRefreshProtectionNotification() {
        if (notificationRefreshScheduled) return
        notificationRefreshScheduled = true
        handler.removeCallbacks(refreshNotificationRunnable)
        handler.postDelayed(refreshNotificationRunnable, NOTIFICATION_REFRESH_DEBOUNCE_MS)
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
        if (firestoreListener != null) {
            Log.d(TAG, "Firestore listener already active, skipping")
            return
        }
        attachFirestoreListener()
    }

    private fun attachReleaseCommandListener() {
        if (isAttachingReleaseListener) {
            Log.w(TAG, "Already attaching release listener, skipping")
            return
        }
        if (releaseCommandListenerByDevice != null) {
            Log.d(TAG, "Release listener already active, skipping")
            return
        }
        isAttachingReleaseListener = true
        try {
            val deviceId = PrefsHelper.getOrCreateDeviceId(this)
            val customerId = PrefsHelper.getCustomerId(this)
            Log.i(TAG, "Release listener deviceId=$deviceId customerId=$customerId")

            releaseCommandListenerByDevice?.remove()
            releaseCommandListenerByDevice = null
            releaseCommandListenerByCustomer?.remove()
            releaseCommandListenerByCustomer = null

            releaseCommandListenerByDevice = FirebaseFirestore.getInstance()
            .collection("deviceCommands")
            .whereEqualTo("command", "release")
            .whereEqualTo("status", "pending")
            .whereEqualTo("deviceId", deviceId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Release listener (deviceId) error", error)
                    return@addSnapshotListener
                }
                snapshots?.documents?.forEach { doc ->
                    handleReleaseCommand(doc.id, doc.reference)
                }
            }

        if (!customerId.isNullOrBlank()) {
            releaseCommandListenerByCustomer = FirebaseFirestore.getInstance()
                .collection("deviceCommands")
                .whereEqualTo("command", "release")
                .whereEqualTo("status", "pending")
                .whereEqualTo("customerId", customerId)
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Release listener (customerId) error", error)
                        return@addSnapshotListener
                    }
                    snapshots?.documents?.forEach { doc ->
                        handleReleaseCommand(doc.id, doc.reference)
                    }
                }
        } else {
            Log.w(TAG, "Release listener (customerId) skipped: customerId is null")
        }

            Log.i(TAG, "Release command listeners attached")
        } finally {
            isAttachingReleaseListener = false
        }
    }

    private fun attachDeviceCommandsListener() {
        if (isAttachingDeviceCommandsListener) {
            Log.w(TAG, "Already attaching deviceCommands listener, skipping")
            return
        }
        if (deviceCommandsListener != null) {
            Log.d(TAG, "deviceCommands listener already active, skipping")
            return
        }
        isAttachingDeviceCommandsListener = true
        try {
            val customerId = PrefsHelper.getCustomerId(this)
            if (customerId.isNullOrBlank()) {
                Log.w(TAG, "deviceCommands listener skipped: customerId is null")
                return
            }
            deviceCommandsListener?.remove()
            deviceCommandsListener = null
            deviceCommandsListener = FirebaseFirestore.getInstance()
                .collection("deviceCommands")
                .whereEqualTo("customerId", customerId)
                .whereEqualTo("status", "pending")
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "deviceCommands listener error", error)
                        return@addSnapshotListener
                    }
                    snapshots?.documents?.forEach { doc ->
                        val command = doc.getString("command") ?: return@forEach
                        if (command == "release") return@forEach
                        val docId = doc.id
                        val now = System.currentTimeMillis()
                        val lastTime = recentlyProcessedCommands[docId]
                        if (lastTime != null && now - lastTime < 5000) {
                            Log.d(TAG, "Skipping duplicate docId=$docId")
                            return@forEach
                        }
                        recentlyProcessedCommands[docId] = now
                        recentlyProcessedCommands.entries.removeIf { now - it.value > 10000 }
                        Log.i(TAG, "deviceCommands pending command=$command docId=$docId")
                        val data = doc.data ?: return@forEach
                        val enrichedData = data.toMutableMap()
                        enrichedData["commandId"] = doc.id
                        doc.reference.update("status", "processing")
                            .addOnSuccessListener {
                                executeDeviceCommand(command, enrichedData)
                            }
                            .addOnFailureListener {
                                Log.w(TAG, "Could not mark processing, skipping")
                            }
                    }
                }
            Log.i(TAG, "deviceCommands listener attached customerId=$customerId")
        } finally {
            isAttachingDeviceCommandsListener = false
        }
    }

    private fun handleReleaseCommand(
        docId: String,
        docRef: com.google.firebase.firestore.DocumentReference
    ) {
        synchronized(processedReleaseDocIds) {
            if (!processedReleaseDocIds.add(docId)) {
                Log.d(TAG, "Release already processed docId=$docId")
                return
            }
        }

        Log.i(TAG, "Release command received docId=$docId")

        handler.post {
            executeReleaseOnDevice()
            docRef.update("status", "completed")
                .addOnFailureListener { error ->
                    Log.e(TAG, "Failed to mark release completed docId=$docId", error)
                    synchronized(processedReleaseDocIds) { processedReleaseDocIds.remove(docId) }
                }
        }
    }

    fun executeRelease() {
        executeReleaseOnDevice()
    }

    private fun executeReleaseOnDevice() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, IbsDeviceAdminReceiver::class.java)

            try {
                DeviceProtectionManager.unblockUninstall(this)
            } catch (e: Exception) {
                Log.w(TAG, "unblockUninstall before release failed", e)
            }

            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.clearDeviceOwnerApp(packageName)
                Log.i(TAG, "Device Owner cleared")
            }
            if (dpm.isAdminActive(componentName)) {
                dpm.removeActiveAdmin(componentName)
                Log.i(TAG, "Device Admin removed")
            }

            pendingReleaseOnDestroy = true
            stopSelf()

            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "executeReleaseOnDevice failed", e)
        }
    }

    private fun attachFirestoreListener() {
        if (!PrefsHelper.isActivated(this)) return
        if (isAttachingListener) {
            Log.w(TAG, "Already attaching listener, skipping")
            return
        }
        isAttachingListener = true
        serviceScope.launch {
            try {
                FirestoreManager.ensureAuthenticated(this@BackgroundService)
                Log.i(TAG, "Attaching Firestore listener - existing=$firestoreListener")
                if (firestoreListener != null) {
                    Log.w(TAG, "Unexpected existing Firestore listener before re-attach")
                }
                firestoreListener?.remove()
                firestoreListener = null
                delay(100)
                firestoreListener = FirestoreManager.listenDeviceCommands(
                    this@BackgroundService,
                    onCommand = { command, data ->
                        executeDeviceCommand(command, data)
                    },
                    onDeviceUpdate = { map ->
                        serviceScope.launch {
                            try {
                                withContext(Dispatchers.Main) {
                                    if (!isUnlockInProgress) {
                                        CommandHandler.handleFromFirestoreField(
                                            this@BackgroundService,
                                            map
                                        )
                                    }
                                }
                                scheduleRefreshProtectionNotification()
                            } catch (e: Exception) {
                                Log.w(TAG, "Device update handler failed", e)
                            }
                        }
                    }
                )
                Log.i(TAG, "Firestore command listener attached")

                attachDeviceCommandsListener()
                attachReleaseCommandListener()
            } catch (e: Exception) {
                Log.e(TAG, "Firestore listener setup failed", e)
                firestoreListener = null
            } finally {
                isAttachingListener = false
            }
        }
    }

    private fun clearProcessedCommandId(commandId: String?) {
        if (commandId.isNullOrBlank()) return
        synchronized(processedCommandIds) {
            processedCommandIds.remove(commandId)
        }
    }

    private fun refreshFcmTokenAfterCommand() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    task.result?.let { token ->
                        PrefsHelper.setFcmToken(this, token)
                        serviceScope.launch {
                            try {
                                FirestoreManager.updateFcmToken(this@BackgroundService, token)
                            } catch (e: Exception) {
                                Log.w(TAG, "FCM token refresh after command failed", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token fetch after command failed", e)
        }
    }

    private fun executeDeviceCommand(command: String, data: Map<String, Any?>) {
        serviceScope.launch {
            val commandId = data["commandId"] as? String
            if (!commandId.isNullOrBlank()) {
                synchronized(processedCommandIds) {
                    if (!processedCommandIds.add(commandId)) {
                        Log.d(TAG, "Skipping already processed commandId=$commandId")
                        return@launch
                    }
                }
            }

            try {
                FirebaseFirestore.getInstance()
                    .collection("devices")
                    .document(PrefsHelper.getOrCreateDeviceId(this@BackgroundService))
                    .update(
                        mapOf(
                            "commandStatus" to "processing",
                            "commandProcessingAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark command processing", e)
            }

            val normalized = CommandHandler.normalizeCommand(command)
            if (normalized == "release") {
                Log.d(TAG, "Release command handled by deviceCommands listener")
                clearProcessedCommandId(commandId)
                return@launch
            }
            val enriched = if (normalized == "unlock") {
                data
            } else {
                CommandHandler.enrichCommandData(this@BackgroundService, data)
            }

            WakeLockHelper.withWakeLock(this@BackgroundService, "ibs:device-command", 120_000L) {
                if (normalized == "unlock") {
                    handler.post {
                        beginUnlock()
                        CommandHandler.unlockDevice(this@BackgroundService, enriched, onComplete = { success ->
                            serviceScope.launch {
                                try {
                                    FirestoreManager.markCommandExecuted(
                                        this@BackgroundService,
                                        commandId,
                                        success,
                                        if (success) "completed" else null
                                    )
                                    if (success && !commandId.isNullOrBlank()) {
                                        lastProcessedCommandId = commandId
                                    }
                                    if (success) {
                                        refreshFcmTokenAfterCommand()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "markCommandExecuted failed for unlock", e)
                                } finally {
                                    clearProcessedCommandId(commandId)
                                }
                            }
                        })
                    }
                    return@withWakeLock
                }
                handler.post {
                    CommandHandler.handle(this@BackgroundService, command, enriched, onComplete = { success ->
                        serviceScope.launch {
                            try {
                                FirestoreManager.markCommandExecuted(
                                    this@BackgroundService,
                                    commandId,
                                    success,
                                    if (success && (
                                        normalized == "lock" ||
                                            normalized == "get_location"
                                        )) {
                                        "completed"
                                    } else {
                                        null
                                    }
                                )
                                if (success && !commandId.isNullOrBlank()) {
                                    lastProcessedCommandId = commandId
                                }
                                if (success) {
                                    refreshFcmTokenAfterCommand()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "markCommandExecuted failed command=$command", e)
                            } finally {
                                clearProcessedCommandId(commandId)
                            }
                        }
                    })
                }
            }
        }
    }

    private fun initializeServiceSafely() {
        processedCommandIds.clear()
        try {
            val pm = packageManager
            val dialerIntent = Intent(Intent.ACTION_DIAL)
            val resolveInfo = pm.resolveActivity(dialerIntent, 0)
            Log.i(TAG, "Default dialer package: ${resolveInfo?.activityInfo?.packageName}")
        } catch (e: Exception) {
            Log.w(TAG, "Default dialer resolve failed", e)
        }
        serviceScope.launch {
            try {
                DeviceProtectionManager.applyDeviceOwnerPolicies(this@BackgroundService)
            } catch (e: Exception) {
                Log.w(TAG, "applyDeviceOwnerPolicies failed", e)
            }
            try {
                DeviceProtectionManager.grantSystemAlertWindowPermission(this@BackgroundService)
            } catch (e: Exception) {
                Log.w(TAG, "grantSystemAlertWindowPermission failed", e)
            }
        }

        try {
            CallBlockManager.restoreFromPrefs(this)
        } catch (e: Exception) {
            Log.w(TAG, "CallBlockManager.restoreFromPrefs failed", e)
        }

        if (PrefsHelper.isAppsBlocked(this)) {
            PrefsHelper.setAppsBlocked(this, true)
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

        ensureFirestoreListener()

        serviceScope.launch {
            try {
                FirestoreManager.fetchAndCacheDealerName(this@BackgroundService)
                DeviceProtectionManager.applyDeviceOwnerPolicies(this@BackgroundService)
                refreshProtectionNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Dealer name fetch failed", e)
            }
        }

        serviceScope.launch {
            try {
                FirestoreManager.setOnlineStatus(this@BackgroundService, true)
                FirestoreManager.sendHeartbeat(this@BackgroundService)
            } catch (e: Exception) {
                Log.w(TAG, "setOnline on create failed", e)
            }
        }

        if (!heartbeatStarted) {
            heartbeatStarted = true
            handler.post(heartbeatRunnable)
        }

        try {
            SimNumberMonitor.checkSimNumberChange(this)
        } catch (e: Exception) {
            Log.w(TAG, "SIM number startup check failed", e)
        }

        try {
            if (!isUnlockInProgress && PrefsHelper.isLocked(this)) {
                CommandHandler.handle(this, "lock", emptyMap())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lock screen trigger failed", e)
        }
    }

    private fun buildForegroundServiceTypes(): Int {
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }

    companion object {
        private const val TAG = "BackgroundService"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        private const val NOTIFICATION_REFRESH_DEBOUNCE_MS = 3_000L
        private const val UNLOCK_GUARD_MS = 15_000L
        const val ACTION_EXECUTE_RELEASE = "com.ibs.configapp.action.EXECUTE_RELEASE"

        @Volatile
        var isUnlockInProgress = false

        @Volatile
        var lastProcessedCommandId: String? = null

        @Volatile
        var pendingReleaseOnDestroy = false

        private val unlockHandler = Handler(Looper.getMainLooper())
        private var unlockResetRunnable: Runnable? = null

        fun beginUnlock() {
            isUnlockInProgress = true
            unlockResetRunnable?.let { unlockHandler.removeCallbacks(it) }
            unlockResetRunnable = Runnable {
                isUnlockInProgress = false
                unlockResetRunnable = null
            }
            unlockHandler.postDelayed(unlockResetRunnable!!, UNLOCK_GUARD_MS)
            Log.i(TAG, "Unlock in progress — lock restart suppressed")
        }

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

        fun executeRelease(context: Context) {
            if (!PrefsHelper.isActivated(context)) return
            try {
                val intent = Intent(context, BackgroundService::class.java).apply {
                    action = ACTION_EXECUTE_RELEASE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeRelease startService failed", e)
                try {
                    context.startService(
                        Intent(context, BackgroundService::class.java).apply {
                            action = ACTION_EXECUTE_RELEASE
                        }
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "executeRelease fallback failed", e2)
                }
            }
        }
    }
}
