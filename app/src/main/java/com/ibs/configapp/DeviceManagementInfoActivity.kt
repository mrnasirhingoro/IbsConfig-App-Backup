package com.ibs.configapp

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ibs.configapp.util.NotificationHelper
import com.ibs.configapp.util.PrefsHelper

class DeviceManagementInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PrefsHelper.isActivated(this)) {
            finish()
            return
        }
        showManagementDialog()
    }

    private fun showManagementDialog() {
        val dealerName = NotificationHelper.getDisplayDealerName(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.device_management_title)
            .setMessage(getString(R.string.device_management_dialog_message, dealerName))
            .setCancelable(true)
            .setPositiveButton(R.string.view_policies) { _, _ ->
                showPoliciesDialog(dealerName)
            }
            .setNegativeButton(R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showPoliciesDialog(dealerName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.device_management_policies_title)
            .setMessage(getString(R.string.device_management_policies_body, dealerName))
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
