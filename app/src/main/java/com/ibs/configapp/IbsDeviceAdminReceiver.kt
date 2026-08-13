package com.ibs.configapp

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.ibs.configapp.util.DeviceOwnerHelper
import com.ibs.configapp.util.PrefsHelper

class IbsDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Device administrator enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "Device administrator disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        DeviceOwnerHelper.syncDeviceOwnerState(context)
        PrefsHelper.setManualDeviceOwnerSetup(context, false)

        val extras = intent.getBundleExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        val dealerId = extras?.getString("dealerId") ?: ""
        val activationCode = extras?.getString("activationCode") ?: ""

        if (dealerId.isNotEmpty() && activationCode.isNotEmpty()) {
            PrefsHelper.savePendingProvisioningData(context, dealerId, activationCode)
        }

        val launchIntent = Intent(context, PermissionSetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            "android.app.action.DEVICE_OWNER_CHANGED" -> {
                DeviceOwnerHelper.syncDeviceOwnerState(context)
            }
        }
    }
}
