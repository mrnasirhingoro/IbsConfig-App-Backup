package com.ibs.configapp.util

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.ibs.configapp.firebase.FirestoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Handles remote silent app updates for Device Owner mode.
 * Triggered only by the "update_app" command (Master Admin only).
 * Does not touch any other app functionality.
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val ACTION_INSTALL_COMPLETE = "com.ibs.configapp.action.APP_UPDATE_INSTALL_COMPLETE"

    suspend fun performUpdate(
        context: Context,
        apkUrl: String,
        expectedChecksum: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            reportStatus(context, "downloading", null)

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.e(TAG, "performUpdate aborted: app is not Device Owner")
                reportStatus(context, "failed", "not_device_owner")
                return@withContext false
            }

            if (apkUrl.isBlank()) {
                Log.e(TAG, "performUpdate aborted: empty apkUrl")
                reportStatus(context, "failed", "empty_apk_url")
                return@withContext false
            }

            val apkFile = downloadApk(context, apkUrl)
            if (apkFile == null) {
                reportStatus(context, "failed", "download_failed")
                return@withContext false
            }

            if (!expectedChecksum.isNullOrBlank()) {
                reportStatus(context, "verifying", null)
                val actualChecksum = computeSha256(apkFile)
                if (!actualChecksum.equals(expectedChecksum.trim(), ignoreCase = true)) {
                    Log.e(TAG, "Checksum mismatch expected=$expectedChecksum actual=$actualChecksum")
                    apkFile.delete()
                    reportStatus(context, "failed", "checksum_mismatch")
                    return@withContext false
                }
                Log.i(TAG, "Checksum verified successfully")
            } else {
                Log.w(TAG, "No checksum provided; refusing to install unverified APK")
                apkFile.delete()
                reportStatus(context, "failed", "no_checksum_provided")
                return@withContext false
            }

            reportStatus(context, "installing", null)
            val installSuccess = silentInstall(context, apkFile)
            apkFile.delete()

            if (installSuccess) {
                reportStatus(context, "completed", null)
                Log.i(TAG, "App update completed successfully")
            } else {
                reportStatus(context, "failed", "install_failed")
            }
            installSuccess
        } catch (e: Exception) {
            Log.e(TAG, "performUpdate failed", e)
            try {
                reportStatus(context, "failed", e.message ?: "unknown_error")
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun downloadApk(context: Context, apkUrl: String): File? {
        return try {
            val connection = URL(apkUrl).openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            val outFile = File(context.cacheDir, "ibs_update_${System.currentTimeMillis()}.apk")
            connection.getInputStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.exists() && outFile.length() > 0) {
                Log.i(TAG, "APK downloaded size=${outFile.length()}")
                outFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk failed", e)
            null
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun silentInstall(context: Context, apkFile: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            try {
                val packageInstaller = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                session.openWrite("ibs_update", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                    session.fsync(out)
                }

                val receiverIntent = Intent(ACTION_INSTALL_COMPLETE).apply {
                    setPackage(context.packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, receiverIntent, flags
                )

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val status = intent?.getIntExtra(
                            PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE
                        ) ?: PackageInstaller.STATUS_FAILURE
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {
                        }
                        if (status == PackageInstaller.STATUS_SUCCESS) {
                            Log.i(TAG, "Silent install succeeded")
                            if (continuation.isActive) continuation.resume(true)
                        } else {
                            val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            Log.e(TAG, "Silent install failed status=$status message=$message")
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                }
                val filter = IntentFilter(ACTION_INSTALL_COMPLETE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }

                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (e: Exception) {
                Log.e(TAG, "silentInstall failed", e)
                if (continuation.isActive) continuation.resume(false)
            }
        }

    private suspend fun reportStatus(context: Context, status: String, error: String?) {
        try {
            FirestoreManager.reportAppUpdateStatus(context, status, error)
        } catch (e: Exception) {
            Log.w(TAG, "reportStatus failed status=$status", e)
        }
    }
}
