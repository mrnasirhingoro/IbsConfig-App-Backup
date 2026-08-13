package com.ibs.configapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.ibs.configapp.util.DeviceOwnerHelper
import com.ibs.configapp.util.PrefsHelper

class ProvisioningSuccessActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            DeviceOwnerHelper.syncDeviceOwnerState(this)
            val extras = intent.getBundleExtra(
                "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
            )
            val dealerId = extras?.getString("dealerId") ?: ""
            val activationCode = extras?.getString("activationCode") ?: ""
            if (dealerId.isNotEmpty() && activationCode.isNotEmpty()) {
                PrefsHelper.savePendingProvisioningData(this, dealerId, activationCode)
            }
            val launchIntent = Intent(this, PermissionSetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "ProvisioningSuccessActivity failed", e)
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "ProvisioningSuccessActivity"
    }
}
