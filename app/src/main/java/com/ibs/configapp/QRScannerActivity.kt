package com.ibs.configapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.ResultPoint
import com.ibs.configapp.databinding.ActivityQrScannerBinding
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import org.json.JSONObject

class QRScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private var scanned = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) binding.barcodeScanner.resume() else finish()
    }

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            if (scanned || result == null) return
            val text = result.text ?: return
            val parsed = parseQr(text) ?: run {
                Toast.makeText(this@QRScannerActivity, R.string.error_invalid_qr, Toast.LENGTH_SHORT).show()
                return
            }
            scanned = true
            binding.barcodeScanner.pause()
            val intent = Intent(this@QRScannerActivity, ActivationActivity::class.java).apply {
                putExtra(ActivationActivity.EXTRA_DEALER_ID, parsed.first)
                putExtra(ActivationActivity.EXTRA_ACTIVATION_CODE, parsed.second)
            }
            startActivity(intent)
            finish()
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.barcodeScanner.decodeContinuous(callback)
        ensureCameraPermission()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.barcodeScanner.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScanner.pause()
    }

    private fun parseQr(text: String): Pair<String, String>? {
        val trimmed = text.trim().trim('\uFEFF')
        if (trimmed.isBlank()) return null

        parseActivationJson(trimmed)?.let { return it }

        val pipeParts = trimmed.split("|").map { it.trim() }
        if (pipeParts.size >= 2 && pipeParts[1].isNotBlank()) {
            return Pair(pipeParts[0], pipeParts[1])
        }

        return null
    }

    private fun parseActivationJson(text: String): Pair<String, String>? {
        val jsonText = when {
            text.startsWith("{") -> text
            text.contains("{") && text.contains("}") -> {
                text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
            }
            else -> return null
        }

        return try {
            val json = JSONObject(jsonText)
            val activationCode = readJsonField(
                json,
                "activationCode",
                "activation_code",
                "serialKey",
                "serial_key"
            )
            if (activationCode.isNullOrBlank()) return null

            val dealerId = readJsonField(
                json,
                "dealerId",
                "dealer_id",
                "clientId"
            ).orEmpty()

            Pair(dealerId, activationCode)
        } catch (_: Exception) {
            null
        }
    }

    private fun readJsonField(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.opt(key)?.toString()?.trim().orEmpty()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }
}
