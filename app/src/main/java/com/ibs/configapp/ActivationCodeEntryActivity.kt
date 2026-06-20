package com.ibs.configapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ibs.configapp.databinding.ActivityActivationCodeEntryBinding

class ActivationCodeEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationCodeEntryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationCodeEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnContinueActivation.setOnClickListener {
            val dealerId = binding.etDealerId.text?.toString()?.trim().orEmpty()
            val activationCode = binding.etActivationCode.text?.toString()?.trim().orEmpty()
            if (dealerId.isBlank() || activationCode.isBlank()) {
                Toast.makeText(this, R.string.activation_code_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, ActivationActivity::class.java).apply {
                    putExtra(ActivationActivity.EXTRA_DEALER_ID, dealerId)
                    putExtra(ActivationActivity.EXTRA_ACTIVATION_CODE, activationCode)
                }
            )
            finish()
        }
    }
}
