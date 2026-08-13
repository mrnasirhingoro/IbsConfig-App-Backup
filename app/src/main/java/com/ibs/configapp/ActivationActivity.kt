package com.ibs.configapp

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.messaging.FirebaseMessaging
import com.ibs.configapp.databinding.ActivityActivationBinding
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.service.BackgroundService
import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.ibs.configapp.util.DeviceOwnerHelper
import com.ibs.configapp.util.DeviceProtectionManager
import com.ibs.configapp.util.PrefsHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.net.UnknownHostException

class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding
    private var dealerId: String = ""
    private var activationCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dealerId = intent.getStringExtra(EXTRA_DEALER_ID) ?: ""
        activationCode = intent.getStringExtra(EXTRA_ACTIVATION_CODE) ?: ""

        if (dealerId.isBlank() || activationCode.isBlank()) {
            Toast.makeText(this, R.string.error_invalid_qr, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvDealerInfo.text = getString(R.string.scan_qr_title) + ": " + dealerId
        Log.i(TAG, "Activation QR parsed dealerId=$dealerId customerId/activationCode=$activationCode")

        binding.rgSimType.setOnCheckedChangeListener { _, checkedId ->
            binding.tilImei2.visibility = if (checkedId == R.id.rbDualSim) View.VISIBLE else View.GONE
        }
        binding.tilImei2.visibility = View.GONE

        binding.btnActivate.setOnClickListener { activate() }
    }

    private fun activate() {
        val imei1 = binding.etImei1.text?.toString()?.trim().orEmpty()
        val imei2 = binding.etImei2.text?.toString()?.trim().orEmpty()
        val isDual = binding.rbDualSim.isChecked
        val simType = if (isDual) "dual" else "single"

        if (imei1.isBlank()) {
            Toast.makeText(this, R.string.error_imei_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (isDual && imei2.isBlank()) {
            Toast.makeText(this, R.string.error_imei2_required, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnActivate.isEnabled = false
        binding.progressActivation.visibility = View.VISIBLE

        lifecycleScope.launch {
            val token = fetchFcmTokenSafely()

            val resolvedCustomerId = try {
                // Only Firestore save failure should block activation.
                FirestoreManager.activateDevice(
                    context = this@ActivationActivity,
                    dealerId = dealerId,
                    imei1 = imei1,
                    imei2 = imei2,
                    simType = simType,
                    fcmToken = token,
                    activationCode = activationCode
                )
            } catch (e: Exception) {
                Log.e(TAG, "Firestore activation save failed", e)
                binding.btnActivate.isEnabled = true
                binding.progressActivation.visibility = View.GONE
                Toast.makeText(
                    this@ActivationActivity,
                    resolveFirebaseError(e),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            try {
                FirestoreManager.syncSmsAuthorizationData(this@ActivationActivity)
            } catch (e: Exception) {
                Log.w(TAG, "syncSmsAuthorizationData after activation failed", e)
            }

            completeActivationLocally(imei1, imei2, simType, token, resolvedCustomerId)
        }
    }

    private suspend fun fetchFcmTokenSafely(): String? {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            token?.also { PrefsHelper.setFcmToken(this, it) }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token fetch failed, continuing without token", e)
            PrefsHelper.getFcmToken(this)
        }
    }

    /**
     * Post-Firestore steps must never fail activation for the user.
     * Device Owner / DPM work runs in BackgroundService with its own try-catch.
     */
    private fun completeActivationLocally(
        imei1: String,
        imei2: String,
        simType: String,
        token: String?,
        customerId: String
    ) {
        try {
            PrefsHelper.saveActivation(
                this,
                dealerId,
                activationCode,
                customerId,
                imei1,
                imei2,
                simType
            )
        } catch (e: Exception) {
            Log.e(TAG, "saveActivation failed (continuing)", e)
        }

        try {
            DeviceOwnerHelper.syncDeviceOwnerState(this)
        } catch (e: Exception) {
            Log.w(TAG, "syncDeviceOwnerState failed (continuing)", e)
        }

        try {
            DeviceProtectionManager.applyAllUserRestrictions(this)
        } catch (e: Exception) {
            Log.w(TAG, "Factory reset restrictions failed (continuing)", e)
        }

        try {
            // No DevicePolicyManager here — PackageManager only, already wrapped internally.
            DeviceProtectionManager.hideLauncherIcon(this)
        } catch (e: Exception) {
            Log.w(TAG, "hideLauncherIcon failed (continuing)", e)
        }

        try {
            // Service may call applyDeviceOwnerPolicies; failures are caught inside the service.
            BackgroundService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "BackgroundService.start failed (continuing)", e)
        }

        Toast.makeText(this, R.string.activation_success, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, BrandBackgroundSetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun resolveFirebaseError(error: Exception): String {
        return when (error) {
            is UnknownHostException, is IOException ->
                getString(R.string.error_activation_network)
            is FirebaseFirestoreException -> when (error.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    getString(R.string.error_activation_permission)
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    getString(R.string.error_activation_network)
                else ->
                    getString(R.string.error_activation_detail, error.message ?: error.code.name)
            }
            is FirebaseAuthException -> when (error.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" ->
                    getString(R.string.error_activation_auth_disabled)
                else ->
                    getString(R.string.error_activation_detail, error.message ?: error.errorCode)
            }
            else ->
                getString(R.string.error_activation_detail, error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "ActivationActivity"
        const val EXTRA_DEALER_ID = "extra_dealer_id"
        const val EXTRA_ACTIVATION_CODE = "extra_activation_code"
    }
}
