package com.ibs.configapp

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ibs.configapp.adapter.PermissionAdapter
import com.ibs.configapp.adapter.PermissionRow
import com.ibs.configapp.databinding.ActivityPermissionSetupBinding
import com.ibs.configapp.util.DeviceOwnerHelper
import com.ibs.configapp.util.PermissionChecker
import com.ibs.configapp.util.PermissionSettingsHelper
import com.ibs.configapp.util.PermissionType
import com.ibs.configapp.util.PrefsHelper

class PermissionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionSetupBinding
    private lateinit var adapter: PermissionAdapter

    private var pendingConfirmation: PermissionType? = null
    private var secretTapCount = 0

    private val adminComponent by lazy {
        ComponentName(this, IbsDeviceAdminReceiver::class.java)
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissions() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    private val callLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissions() }

    private val batterySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshBatteryPermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrefsHelper.isActivated(this)) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        binding = ActivityPermissionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val items = listOf(
            PermissionRow(
                PermissionType.DEVICE_ADMIN,
                getString(R.string.perm_device_admin),
                getString(R.string.guide_device_admin)
            ),
            PermissionRow(
                PermissionType.ACCESSIBILITY,
                getString(R.string.perm_accessibility),
                getString(R.string.guide_accessibility)
            ),
            PermissionRow(
                PermissionType.OVERLAY,
                getString(R.string.perm_overlay),
                getString(R.string.guide_overlay)
            ),
            PermissionRow(
                PermissionType.LOCATION,
                getString(R.string.perm_location),
                getString(R.string.guide_location)
            ),
            PermissionRow(
                PermissionType.PHONE,
                getString(R.string.perm_phone),
                getString(R.string.guide_phone)
            ),
            PermissionRow(
                PermissionType.CALL_LOGS,
                getString(R.string.perm_call_logs),
                getString(R.string.guide_call_logs)
            ),
            PermissionRow(
                PermissionType.NOTIFICATIONS,
                getString(R.string.perm_notifications),
                getString(R.string.guide_notifications)
            ),
            PermissionRow(
                PermissionType.BATTERY,
                getString(R.string.perm_battery),
                getString(R.string.guide_battery)
            ),
            PermissionRow(
                PermissionType.AUTO_START,
                getString(R.string.perm_auto_start),
                getString(R.string.guide_auto_start)
            ),
            PermissionRow(
                PermissionType.PLAY_PROTECT,
                getString(R.string.perm_play_protect),
                getString(R.string.guide_play_protect)
            ),
            PermissionRow(
                PermissionType.MULTIPLE_USERS,
                getString(R.string.perm_multiple_users),
                getString(R.string.guide_multiple_users)
            )
        )

        adapter = PermissionAdapter(items) { type -> requestPermission(type) }
        adapter.onStatusChanged = { updateProceedState() }

        binding.rvPermissions.layoutManager = LinearLayoutManager(this)
        binding.rvPermissions.adapter = adapter

        binding.cbAccept.setOnCheckedChangeListener { _, _ -> updateProceedState() }

        binding.btnProceed.setOnClickListener {
            if (!binding.cbAccept.isChecked) {
                Toast.makeText(this, R.string.error_all_permissions, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!allRequiredPermissionsGranted()) {
                Toast.makeText(this, R.string.error_all_permissions, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isBatteryOptimizationGranted()) {
                Toast.makeText(
                    this,
                    "Battery optimization recommended but not required",
                    Toast.LENGTH_LONG
                ).show()
            }
            val dealerId = PrefsHelper.getPendingDealerId(this)
            val activationCode = PrefsHelper.getPendingActivationCode(this)
            if (dealerId.isNotEmpty() && activationCode.isNotEmpty()) {
                PrefsHelper.clearPendingProvisioningData(this)
                startActivity(
                    Intent(this, ActivationActivity::class.java).apply {
                        putExtra(ActivationActivity.EXTRA_DEALER_ID, dealerId)
                        putExtra(ActivationActivity.EXTRA_ACTIVATION_CODE, activationCode)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } else {
                startActivity(Intent(this, QRScannerActivity::class.java))
            }
        }

        setupSecretExit()

        updateProceedState()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryPermissionStatus()
        pendingConfirmation?.let { type ->
            if (!PermissionChecker.isGranted(this, type)) {
                showConfirmationDialog(type)
            }
            pendingConfirmation = null
        }
    }

    private fun refreshPermissions() {
        adapter.refreshAll()
        updateProceedState()
    }

    private fun refreshBatteryPermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        }
        refreshPermissions()
    }

    private fun allRequiredPermissionsGranted(): Boolean =
        PermissionType.entries
            .filter { it != PermissionType.BATTERY && it != PermissionType.OVERLAY }
            .all { PermissionChecker.isGranted(this, it) }

    private fun isBatteryOptimizationGranted(): Boolean =
        PermissionChecker.isGranted(this, PermissionType.BATTERY)

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        for (intent in buildBatterySettingsIntents()) {
            try {
                if (intent.resolveActivity(packageManager) == null) continue
                batterySettingsLauncher.launch(intent)
                return
            } catch (_: Exception) {
                // Try the next MIUI / system battery settings screen.
            }
        }
    }

    private fun buildBatterySettingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:com.ibs.configapp")
        },
        Intent().apply {
            setClassName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            )
            putExtra("package_name", "com.ibs.configapp")
            putExtra("package_label", "IBS Config App")
        },
        Intent().apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.securitycenter.MainActivity"
            )
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:com.ibs.configapp")
        }
    )

    private fun updateProceedState() {
        binding.btnProceed.isEnabled =
            allRequiredPermissionsGranted() && binding.cbAccept.isChecked
    }

    private fun requestPermission(type: PermissionType) {
        when (type) {
            PermissionType.DEVICE_ADMIN -> {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_description)
                    )
                }
                deviceAdminLauncher.launch(intent)
            }
            PermissionType.ACCESSIBILITY -> {
                startActivity(PermissionChecker.getAccessibilitySettingsIntent())
            }
            PermissionType.OVERLAY -> {
                overlaySettingsLauncher.launch(PermissionChecker.getOverlaySettingsIntent(this))
            }
            PermissionType.LOCATION -> requestLocation()
            PermissionType.PHONE -> requestPhonePermissions()
            PermissionType.CALL_LOGS -> requestCallLogPermission()
            PermissionType.NOTIFICATIONS -> requestNotifications()
            PermissionType.BATTERY -> openBatteryOptimizationSettings()
            PermissionType.AUTO_START -> {
                pendingConfirmation = PermissionType.AUTO_START
                PermissionSettingsHelper.openAutoStartSettings(this)
            }
            PermissionType.PLAY_PROTECT -> {
                pendingConfirmation = PermissionType.PLAY_PROTECT
                PermissionSettingsHelper.openPlayProtectSettings(this)
            }
            PermissionType.MULTIPLE_USERS -> {
                pendingConfirmation = PermissionType.MULTIPLE_USERS
                PermissionSettingsHelper.openUsersSettings(this)
            }
        }
    }

    private fun requestLocation() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                )
            }
        }
    }

    private fun requestPhonePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        val needsRequest = permissions.any {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            phonePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requestCallLogPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (PermissionSettingsHelper.needsNotificationSettings(this)) {
            PermissionSettingsHelper.openNotificationSettings(this)
        }
    }

    private fun showConfirmationDialog(type: PermissionType) {
        val message = when (type) {
            PermissionType.AUTO_START -> getString(R.string.confirm_auto_start_message)
            PermissionType.PLAY_PROTECT -> getString(R.string.confirm_play_protect_message)
            PermissionType.MULTIPLE_USERS -> getString(R.string.confirm_multiple_users_message)
            else -> return
        }
        val title = when (type) {
            PermissionType.AUTO_START -> getString(R.string.perm_auto_start)
            PermissionType.PLAY_PROTECT -> getString(R.string.perm_play_protect)
            PermissionType.MULTIPLE_USERS -> getString(R.string.perm_multiple_users)
            else -> ""
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ ->
                when (type) {
                    PermissionType.AUTO_START ->
                        PrefsHelper.setAutoStartAcknowledged(this, true)
                    PermissionType.PLAY_PROTECT ->
                        PrefsHelper.setPlayProtectAcknowledged(this, true)
                    PermissionType.MULTIPLE_USERS ->
                        PrefsHelper.setMultipleUsersAcknowledged(this, true)
                    else -> { }
                }
                refreshPermissions()
            }
            .setNegativeButton(R.string.no) { _, _ ->
                when (type) {
                    PermissionType.AUTO_START ->
                        PrefsHelper.setAutoStartAcknowledged(this, false)
                    PermissionType.PLAY_PROTECT ->
                        PrefsHelper.setPlayProtectAcknowledged(this, false)
                    PermissionType.MULTIPLE_USERS ->
                        PrefsHelper.setMultipleUsersAcknowledged(this, false)
                    else -> { }
                }
                refreshPermissions()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupSecretExit() {
        binding.tvVersion.setOnClickListener {
            secretTapCount++
            if (secretTapCount >= SECRET_TAP_THRESHOLD) {
                secretTapCount = 0
                showSecretExitPinDialog()
            }
        }
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
        Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
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
