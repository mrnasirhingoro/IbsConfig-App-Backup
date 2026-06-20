package com.ibs.configapp

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ibs.configapp.databinding.ActivityLockScreenBinding
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.service.BackgroundService
import com.ibs.configapp.util.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.net.URL

class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private var lockTaskActive = false

    private var receiversRegistered = false
    private var lastRelaunchTime = 0L

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UNLOCK_DEVICE, ACTION_UNLOCK, ACTION_DISMISS_LOCK -> dismissLockScreen()
            }
        }
    }

    private val systemUiVisibilityListener = View.OnSystemUiVisibilityChangeListener {
        if (PrefsHelper.isLocked(this@LockScreenActivity)) {
            hideSystemUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstanceRef = WeakReference(this)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!PrefsHelper.isLocked(this) ||
            intent?.action == ACTION_UNLOCK_DEVICE ||
            intent?.action == ACTION_UNLOCK ||
            intent?.action == ACTION_DISMISS_LOCK
        ) {
            dismissLockScreen()
            overridePendingTransition(0, 0)
            return
        }

        applyLockdownWindowFlags()

        // Block incoming call UI from showing over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        hideSystemUi()
        applyTouchLockdown()
        loadDealerWallpaper()
        binding.btnEmergencyCall.visibility = View.GONE

        binding.btnEmergencyCall.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.emergency_call, Toast.LENGTH_SHORT).show()
            }
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
        receiversRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (!PrefsHelper.isLocked(this) || BackgroundService.isUnlockInProgress) {
            dismissLockScreen()
            return
        }
        try {
            applyLockdownWindowFlags()
            hideSystemUi()
            tryStartLockTask()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.setOnSystemUiVisibilityChangeListener(systemUiVisibilityListener)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "onResume lockdown refresh failed", e)
        }
    }

    override fun onPause() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.setOnSystemUiVisibilityChangeListener(null)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "onPause cleanup failed", e)
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && PrefsHelper.isLocked(this)) {
            hideSystemUi()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!PrefsHelper.isLocked(this) || isFinishing || BackgroundService.isUnlockInProgress) return
        val now = System.currentTimeMillis()
        if (now - lastRelaunchTime < 300) return
        lastRelaunchTime = now
        try {
            val intent = Intent(this, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "onUserLeaveHint relaunch failed", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_UNLOCK_DEVICE, ACTION_UNLOCK, ACTION_DISMISS_LOCK -> dismissLockScreen()
        }
    }

    private fun applyLockdownWindowFlags() {
        try {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            @Suppress("DEPRECATION")
            window.addFlags(0x80000000.toInt())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "applyLockdownWindowFlags failed", e)
        }
    }

    private fun applyTouchLockdown() {
        try {
            binding.root.isVerticalScrollBarEnabled = false
            binding.root.overScrollMode = View.OVER_SCROLL_NEVER
            binding.root.setOnTouchListener { _, _ -> true }
            binding.layoutWhatsApp.setOnClickListener(null)
            binding.layoutWhatsApp.isClickable = false
            binding.layoutWhatsApp.isFocusable = false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "applyTouchLockdown failed", e)
        }
    }

    private fun dismissLockScreen() {
        if (isFinishing) return
        unregisterUnlockReceivers()
        try {
            tryStopLockTask()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dismissLockScreen tryStopLockTask failed", e)
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
        if (activeInstanceRef?.get() == this) {
            activeInstanceRef = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    private fun unregisterUnlockReceivers() {
        if (!receiversRegistered) return
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {
        }
        receiversRegistered = false
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
            loadDealerWallpaper()
        }
    }

    private fun loadDealerWallpaper() {
        hideLockScreenTextOverlays()
        lifecycleScope.launch {
            try {
                val firestoreUrl = withContext(Dispatchers.IO) {
                    FirestoreManager.fetchDealerWallpaperUrlFromDevice(
                        this@LockScreenActivity
                    )
                }
                val url = firestoreUrl?.takeIf { it.isNotBlank() }
                    ?: PrefsHelper.getDealerWallpaperUrl(this@LockScreenActivity)
                applyWallpaperBackground(url)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "loadDealerWallpaper failed", e)
                applyWallpaperBackground(null)
            }
        }
    }

    private fun hideLockScreenTextOverlays() {
        binding.tvDealerShopName.visibility = View.GONE
        binding.tvNoticeBanner.visibility = View.GONE
        binding.tvUrduOwner.visibility = View.GONE
        binding.layoutLockedBox.visibility = View.GONE
        binding.layoutWhatsApp.visibility = View.GONE
        binding.tvPaymentContact.visibility = View.GONE
        binding.tvSecureCode.visibility = View.GONE
        binding.layoutBottomIcons.visibility = View.GONE
        binding.btnEmergencyCall.visibility = View.GONE
    }

    private suspend fun applyWallpaperBackground(url: String?) {
        withContext(Dispatchers.Main) {
            if (url.isNullOrBlank()) {
                binding.root.background = BitmapDrawable(
                    resources,
                    BitmapFactory.decodeResource(resources, R.drawable.lock_wallpaper)
                )
                return@withContext
            }
        }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.getInputStream().use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Wallpaper download failed", e)
                null
            }
        }
        withContext(Dispatchers.Main) {
            if (bitmap != null) {
                binding.root.background = BitmapDrawable(resources, bitmap)
            } else {
                binding.root.background = BitmapDrawable(
                    resources,
                    BitmapFactory.decodeResource(resources, R.drawable.lock_wallpaper)
                )
            }
        }
    }

    private fun applyDealerUi(dealerName: String, dealerPhone: String, secureCode: String) {
        binding.tvDealerShopName.text = dealerName
        binding.tvUrduOwner.text = getString(R.string.lock_urdu_owner, dealerName)
        binding.tvDealerWhatsApp.text = dealerPhone
        binding.tvPaymentContact.text = getString(R.string.lock_payment_contact, dealerPhone)
        binding.tvSecureCode.text = getString(R.string.secure_code_label) + ": " + secureCode
    }

    private fun hideSystemUi() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "hideSystemUi failed", e)
        }
    }

    private fun tryStartLockTask() {
        if (lockTaskActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setLockTaskPackages(
                        ComponentName(this, IbsDeviceAdminReceiver::class.java),
                        arrayOf(packageName)
                    )
                }
                startLockTask()
                lockTaskActive = true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "startLockTask failed", e)
        }
    }

    private fun tryStopLockTask() {
        if (!lockTaskActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                stopLockTask()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "stopLockTask failed", e)
        } finally {
            lockTaskActive = false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            if (isTouchOnEmergencyButton(ev)) {
                super.dispatchTouchEvent(ev)
            } else {
                true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dispatchTouchEvent failed", e)
            true
        }
    }

    private fun isTouchOnEmergencyButton(ev: MotionEvent): Boolean {
        val button = binding.btnEmergencyCall
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val x = ev.rawX
        val y = ev.rawY
        return x >= location[0] &&
            x <= location[0] + button.width &&
            y >= location[1] &&
            y <= location[1] + button.height
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Disabled during lockdown
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return try {
            if (isAllowedEmergencyKey(event)) {
                super.dispatchKeyEvent(event)
            } else {
                true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dispatchKeyEvent failed", e)
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> true
        }
    }

    private fun isAllowedEmergencyKey(event: KeyEvent): Boolean {
        return false
    }

    override fun onDestroy() {
        try {
            tryStopLockTask()
        } catch (_: Exception) {
        }
        unregisterUnlockReceivers()
        if (activeInstanceRef?.get() == this) {
            activeInstanceRef = null
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockScreenActivity"

        const val ACTION_UNLOCK_DEVICE = "com.ibs.configapp.UNLOCK_DEVICE"
        const val ACTION_UNLOCK = "com.ibs.configapp.ACTION_UNLOCK"
        const val ACTION_DISMISS_LOCK = "com.ibs.configapp.ACTION_DISMISS_LOCK"

        @Volatile
        private var activeInstanceRef: WeakReference<LockScreenActivity>? = null

        fun dismissIfActive() {
            val activity = activeInstanceRef?.get() ?: return
            activity.runOnUiThread {
                activeInstanceRef?.get()?.dismissLockScreen()
            }
        }
    }
}
