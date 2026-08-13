package com.ibs.configapp

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ibs.configapp.databinding.ActivityDeviceOwnerSetupBinding
import com.ibs.configapp.util.DeviceOwnerHelper
import com.ibs.configapp.util.PrefsHelper

class DeviceOwnerSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceOwnerSetupBinding
    private var triedShellCommand = false
    private var secretTapCount = 0

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
        if (DeviceOwnerHelper.isDeviceAdminActive(this)) {
            attemptSetDeviceOwner()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PrefsHelper.isActivated(this)) {
            finish()
            return
        }

        if (DeviceOwnerHelper.isDeviceOwner(this)) {
            completeDeviceOwnerSetup(manual = false)
            return
        }

        if (PrefsHelper.isDeviceOwnerSetupComplete(this)) {
            proceedToPermissionSetup()
            return
        }

        binding = ActivityDeviceOwnerSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantDeviceAdmin.setOnClickListener { requestDeviceAdmin() }
        binding.btnShowInstructions.setOnClickListener { showManualInstructionsDialog() }
        binding.btnContinueSetup.setOnClickListener {
            if (DeviceOwnerHelper.isDeviceOwner(this)) {
                completeDeviceOwnerSetup(manual = true)
            }
        }

        setupSecretExit()

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        refreshStatus()
        if (DeviceOwnerHelper.isDeviceOwner(this)) {
            completeDeviceOwnerSetup(manual = PrefsHelper.isManualDeviceOwnerSetup(this))
        }
    }

    private fun requestDeviceAdmin() {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceOwnerHelper.adminComponent(this@DeviceOwnerSetupActivity))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_description)
                )
            }
            deviceAdminLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.device_owner_admin_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun attemptSetDeviceOwner() {
        if (DeviceOwnerHelper.isDeviceOwner(this)) {
            completeDeviceOwnerSetup(manual = true)
            return
        }
        if (!triedShellCommand) {
            triedShellCommand = true
            val shellSuccess = DeviceOwnerHelper.trySetDeviceOwnerViaShell()
            DeviceOwnerHelper.syncDeviceOwnerState(this)
            if (shellSuccess && DeviceOwnerHelper.isDeviceOwner(this)) {
                completeDeviceOwnerSetup(manual = true)
                return
            }
        }
        refreshStatus()
        binding.btnShowInstructions.visibility = android.view.View.VISIBLE
        showManualInstructionsDialog()
    }

    private fun completeDeviceOwnerSetup(manual: Boolean) {
        PrefsHelper.setDeviceOwner(this, true)
        PrefsHelper.setDeviceOwnerSetupComplete(this, true)
        PrefsHelper.setManualDeviceOwnerSetup(this, manual)
        proceedToPermissionSetup()
    }

    private fun proceedToPermissionSetup() {
        startActivity(
            Intent(this, PermissionSetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        finish()
    }

    private fun refreshStatus() {
        if (!::binding.isInitialized) return

        val adminGranted = DeviceOwnerHelper.isDeviceAdminActive(this)
        val ownerGranted = DeviceOwnerHelper.isDeviceOwner(this)

        binding.tvStatusDeviceAdmin.text = getString(
            if (adminGranted) R.string.device_owner_status_admin_granted
            else R.string.device_owner_status_admin_pending
        )
        binding.tvStatusDeviceAdmin.setTextColor(
            ContextCompat.getColor(
                this,
                if (adminGranted) R.color.green_tick else R.color.lock_red
            )
        )

        binding.tvStatusDeviceOwner.text = getString(
            if (ownerGranted) R.string.device_owner_status_owner_granted
            else R.string.device_owner_status_owner_pending
        )
        binding.tvStatusDeviceOwner.setTextColor(
            ContextCompat.getColor(
                this,
                if (ownerGranted) R.color.green_tick else R.color.lock_red
            )
        )

        binding.btnGrantDeviceAdmin.isEnabled = !adminGranted
        binding.btnContinueSetup.visibility =
            if (ownerGranted) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnShowInstructions.visibility =
            if (adminGranted && !ownerGranted) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showManualInstructionsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.device_owner_instructions_title)
            .setMessage(
                getString(
                    R.string.device_owner_instructions_message,
                    DeviceOwnerHelper.ADB_SET_DEVICE_OWNER_COMMAND
                )
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun setupSecretExit() {
        val revealSecretExit = View.OnClickListener {
            secretTapCount++
            if (secretTapCount >= SECRET_TAP_THRESHOLD) {
                secretTapCount = 0
                binding.btnSecretExit.visibility = View.VISIBLE
            }
        }
        binding.tvSetupTitle.setOnClickListener(revealSecretExit)
        binding.tvVersion.setOnClickListener(revealSecretExit)
        binding.btnSecretExit.setOnClickListener { showSecretExitPinDialog() }
    }

    private fun showSecretExitPinDialog() {
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(pinInput)
            .setPositiveButton(R.string.ok) { _, _ ->
                val expectedPin = PrefsHelper.getDeviceSecretCode(this)
                if (pinInput.text.toString().equals(expectedPin, ignoreCase = true)) {
                    removeDeviceOwnerAndUninstall()
                } else {
                    Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeDeviceOwnerAndUninstall() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = DeviceOwnerHelper.adminComponent(this)
        try {
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.clearDeviceOwnerApp(packageName)
            }
            if (dpm.isAdminActive(componentName)) {
                dpm.removeActiveAdmin(componentName)
            }
        } catch (_: Exception) {
        }
        Toast.makeText(this, "Device Owner Removed", Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    companion object {
        private const val SECRET_TAP_THRESHOLD = 5
    }
}
