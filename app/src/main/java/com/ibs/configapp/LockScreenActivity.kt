package com.ibs.configapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ibs.configapp.databinding.ActivityLockScreenBinding
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.util.PrefsHelper
import kotlinx.coroutines.launch

class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UNLOCK_DEVICE, ACTION_UNLOCK, ACTION_DISMISS_LOCK -> dismissLockScreen()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent?.action == ACTION_UNLOCK_DEVICE ||
            intent?.action == ACTION_UNLOCK ||
            intent?.action == ACTION_DISMISS_LOCK
        ) {
            dismissLockScreen()
            return
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(0x80000000.toInt())

        hideSystemUi()
        bindDealerInfoFromCache()
        loadDealerInfoFromFirestore()

        binding.layoutWhatsApp.setOnClickListener { openDealerWhatsApp() }
        binding.btnEmergencyCall.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }
            }
        )

        val filter = IntentFilter().apply {
            addAction(ACTION_UNLOCK_DEVICE)
            addAction(ACTION_UNLOCK)
            addAction(ACTION_DISMISS_LOCK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(unlockReceiver, filter)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_UNLOCK_DEVICE, ACTION_UNLOCK, ACTION_DISMISS_LOCK -> dismissLockScreen()
        }
    }

    private fun dismissLockScreen() {
        if (isFinishing) return
        PrefsHelper.setLocked(this, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    private fun bindDealerInfoFromCache() {
        val dealerName = PrefsHelper.getDealerName(this).ifBlank { "Dealer Shop" }
        val dealerPhone = PrefsHelper.getDealerPhone(this).ifBlank { "—" }
        val secureCode = PrefsHelper.getSecureCode(this)
        applyDealerUi(dealerName, dealerPhone, secureCode)
    }

    private fun loadDealerInfoFromFirestore() {
        lifecycleScope.launch {
            try {
                FirestoreManager.fetchLockScreenData(this@LockScreenActivity)
            } catch (_: Exception) {
            }
            bindDealerInfoFromCache()
        }
    }

    private fun applyDealerUi(dealerName: String, dealerPhone: String, secureCode: String) {
        binding.tvDealerShopName.text = dealerName
        binding.tvUrduOwner.text = getString(R.string.lock_urdu_owner, dealerName)
        binding.tvDealerWhatsApp.text = dealerPhone
        binding.tvPaymentContact.text = getString(R.string.lock_payment_contact, dealerPhone)
        binding.tvSecureCode.text = getString(R.string.secure_code_label) + ": " + secureCode
    }

    private fun openDealerWhatsApp() {
        val phone = PrefsHelper.getDealerPhone(this).replace(Regex("[^0-9]"), "")
        if (phone.isBlank()) return
        val uri = Uri.parse("https://wa.me/$phone")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Disabled
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {
        }
        if (activeInstance == this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_UNLOCK_DEVICE = "com.ibs.configapp.UNLOCK_DEVICE"
        const val ACTION_UNLOCK = "com.ibs.configapp.ACTION_UNLOCK"
        const val ACTION_DISMISS_LOCK = "com.ibs.configapp.ACTION_DISMISS_LOCK"

        @Volatile
        private var activeInstance: LockScreenActivity? = null

        fun dismissIfActive() {
            activeInstance?.runOnUiThread {
                activeInstance?.dismissLockScreen()
            }
        }
    }
}
