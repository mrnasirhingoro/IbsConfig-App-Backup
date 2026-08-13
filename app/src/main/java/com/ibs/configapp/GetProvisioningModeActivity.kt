package com.ibs.configapp

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.ibs.configapp.databinding.ActivityGetProvisioningModeBinding

class GetProvisioningModeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val binding = ActivityGetProvisioningModeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            val dealerName = getString(R.string.device_management_dealer_fallback)
            binding.tvProvisioningMessage.text = getString(
                R.string.device_management_notification_body,
                dealerName
            ) + "\n\n" + getString(R.string.device_owner_setup_message)

            binding.btnContinueProvisioning.setOnClickListener {
                try {
                    returnProvisioningModeResult()
                } catch (e: Exception) {
                    Log.e(TAG, "GetProvisioningModeActivity continue failed", e)
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GetProvisioningModeActivity failed", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun returnProvisioningModeResult() {
        val resultIntent = Intent().apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            )
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        private const val TAG = "GetProvisioningModeActivity"
    }
}
