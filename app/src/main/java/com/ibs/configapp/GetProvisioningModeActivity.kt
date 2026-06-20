package com.ibs.configapp

import android.app.admin.DevicePolicyManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class GetProvisioningModeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resultIntent = intent.also {
            it.putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            )
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
