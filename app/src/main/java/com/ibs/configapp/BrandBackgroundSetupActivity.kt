package com.ibs.configapp

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ibs.configapp.databinding.ActivityBrandBackgroundSetupBinding
import com.ibs.configapp.util.BrandSetupAction
import com.ibs.configapp.util.BrandSetupGuide
import com.ibs.configapp.util.DeviceBrandHelper
import com.ibs.configapp.util.PrefsHelper

class BrandBackgroundSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrandBackgroundSetupBinding
    private lateinit var guide: BrandSetupGuide

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrandBackgroundSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guide = DeviceBrandHelper.getSetupGuide(this)
        bindGuideUi()
        updateStatusUi()

        binding.btnOpenSettings.setOnClickListener { openBrandSettings() }
        binding.cbConfirmed.setOnCheckedChangeListener { _, checked ->
            binding.btnContinue.isEnabled = checked
        }
        binding.btnContinue.setOnClickListener { finishSetup() }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    private fun bindGuideUi() {
        binding.tvDetectedDevice.text = getString(
            R.string.brand_setup_detected_device,
            guide.manufacturerLabel,
            guide.osLabel,
            android.os.Build.MANUFACTURER
        )
        binding.tvBrandTitle.setText(guide.titleRes)
        binding.tvBrandDescription.setText(guide.descriptionRes)
        binding.tvBrandSteps.setText(guide.stepsRes)
        binding.btnOpenSettings.setText(guide.openSettingsRes)
    }

    private fun openBrandSettings() {
        val opened = DeviceBrandHelper.openBrandSettings(this, guide)
        if (!opened) {
            Toast.makeText(this, R.string.brand_setup_settings_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatusUi() {
        val done = DeviceBrandHelper.isRecommendedSetupDone(this, guide)
        if (done) {
            binding.tvSettingsStatus.visibility = View.VISIBLE
            binding.tvSettingsStatus.setText(
                when (guide.action) {
                    BrandSetupAction.BATTERY_OPTIMIZATION -> R.string.brand_setup_status_battery
                    BrandSetupAction.AUTOSTART -> R.string.brand_setup_status_autostart
                    BrandSetupAction.PROTECTED_APPS -> R.string.brand_setup_status_protected
                }
            )
            if (guide.action == BrandSetupAction.BATTERY_OPTIMIZATION) {
                binding.cbConfirmed.isChecked = true
                binding.btnContinue.isEnabled = true
            }
        } else {
            binding.tvSettingsStatus.visibility = View.GONE
        }
    }

    private fun finishSetup() {
        PrefsHelper.setBrandBackgroundSetupAcknowledged(this, true)
        PrefsHelper.setBrandBackgroundSetupComplete(this, true)
        Toast.makeText(this, R.string.brand_setup_complete, Toast.LENGTH_SHORT).show()
        finishAffinity()
    }
}
