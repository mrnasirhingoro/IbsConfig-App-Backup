package com.ibs.configapp

import android.app.admin.DevicePolicyManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.ibs.configapp.databinding.ActivityLockScreenBinding
import com.ibs.configapp.firebase.FirestoreManager
import com.ibs.configapp.firebase.LockScreenDealerContactInfo
import com.ibs.configapp.service.BackgroundService
import com.ibs.configapp.util.DeviceProtectionManager
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
    private var lockScreenDealerContactInfo: LockScreenDealerContactInfo? = null

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
        loadLockScreenContactsAndAccountInfo()
        setupLockScreenAddonControls()
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
                    BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_background)
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
                    BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_background)
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

    private fun setupLockScreenAddonControls() {
        binding.btnLockScreenContacts.setOnClickListener { showLockScreenContactsDialog() }
        binding.btnLockScreenPaymentApps.setOnClickListener { showLockScreenPaymentAppsDialog() }
        binding.btnCopyBankAccount.setOnClickListener { copyBankAccountNumberToClipboard() }
    }

    private fun loadLockScreenContactsAndAccountInfo() {
        hideLockScreenAddonUi()
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    FirestoreManager.fetchLockScreenDealerContactAndBankInfo(this@LockScreenActivity)
                } ?: return@launch
                lockScreenDealerContactInfo = info
                applyLockScreenAddonUi(info)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "loadLockScreenContactsAndAccountInfo failed", e)
                hideLockScreenAddonUi()
            }
        }
    }

    private fun hideLockScreenAddonUi() {
        binding.btnLockScreenContacts.visibility = View.GONE
        binding.btnLockScreenPaymentApps.visibility = View.GONE
        binding.layoutLockScreenBankInfo.visibility = View.GONE
    }

    private fun applyLockScreenAddonUi(info: LockScreenDealerContactInfo) {
        val hasContact = !info.dealerNumber.isNullOrBlank() ||
            !info.wasooliNumber.isNullOrBlank() ||
            !info.managerNumber.isNullOrBlank()
        binding.btnLockScreenContacts.visibility = if (hasContact) View.VISIBLE else View.GONE

        binding.btnLockScreenPaymentApps.visibility = View.VISIBLE

        val hasBankInfo = !info.bankAccountName.isNullOrBlank() ||
            !info.bankAccountNumber.isNullOrBlank()
        if (!hasBankInfo) {
            binding.layoutLockScreenBankInfo.visibility = View.GONE
            return
        }
        binding.layoutLockScreenBankInfo.visibility = View.VISIBLE
        if (info.bankAccountName.isNullOrBlank()) {
            binding.tvLockScreenBankName.visibility = View.GONE
        } else {
            binding.tvLockScreenBankName.visibility = View.VISIBLE
            binding.tvLockScreenBankName.text = info.bankAccountName
        }
        if (info.bankAccountNumber.isNullOrBlank()) {
            binding.tvLockScreenBankNumber.visibility = View.GONE
            binding.btnCopyBankAccount.visibility = View.GONE
        } else {
            binding.tvLockScreenBankNumber.visibility = View.VISIBLE
            binding.tvLockScreenBankNumber.text = info.bankAccountNumber
            binding.btnCopyBankAccount.visibility = View.VISIBLE
        }
        applyBankPanelBranding(info)
    }

    private fun resolveBrandColorInt(info: LockScreenDealerContactInfo?): Int =
        parseColorOrDefault(info?.brandColor, DEFAULT_BRAND_COLOR_HEX)

    private fun resolveSecondaryColorInt(info: LockScreenDealerContactInfo?): Int =
        parseColorOrDefault(info?.secondaryColor, DEFAULT_SECONDARY_COLOR_HEX)

    private fun parseColorOrDefault(hex: String?, defaultHex: String): Int {
        return try {
            Color.parseColor(hex?.trim()?.takeIf { it.isNotEmpty() } ?: defaultHex)
        } catch (_: Exception) {
            Color.parseColor(defaultHex)
        }
    }

    private fun applyBankPanelBranding(info: LockScreenDealerContactInfo) {
        val brand = resolveBrandColorInt(info)
        binding.layoutLockScreenBankInfo.background = GradientDrawable().apply {
            cornerRadius = dpToPx(8).toFloat()
            setColor(Color.argb(30, Color.red(brand), Color.green(brand), Color.blue(brand)))
        }
        ImageViewCompat.setImageTintList(
            binding.btnCopyBankAccount,
            ColorStateList.valueOf(resolveSecondaryColorInt(info))
        )
    }

    private fun showProfessionalLockScreenDialog(title: CharSequence, content: View?) {
        val info = lockScreenDealerContactInfo
        val brandColor = resolveBrandColorInt(info)
        val secondaryColor = resolveSecondaryColorInt(info)

        val root = layoutInflater.inflate(R.layout.dialog_lock_screen_professional, null)
        root.findViewById<View>(R.id.dialogLockScreenHeader).setBackgroundColor(brandColor)
        root.findViewById<TextView>(R.id.tvDialogLockScreenTitle).text = title
        val contentContainer = root.findViewById<LinearLayout>(R.id.dialogLockScreenContent)
        if (content != null) {
            contentContainer.addView(content)
        }
        val closeBtn = root.findViewById<MaterialButton>(R.id.btnDialogLockScreenClose)
        closeBtn.backgroundTintList = ColorStateList.valueOf(secondaryColor)

        val dialog = AlertDialog.Builder(this).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showLockScreenContactsDialog() {
        val info = lockScreenDealerContactInfo ?: return
        val secondaryColor = resolveSecondaryColorInt(info)
        val rows = listOf(
            ContactDialogRow(R.string.lock_screen_contact_dealer, info.dealerName, info.dealerNumber),
            ContactDialogRow(R.string.lock_screen_contact_wasooli, info.wasooliName, info.wasooliNumber),
            ContactDialogRow(R.string.lock_screen_contact_manager, info.managerName, info.managerNumber)
        ).filter { !it.number.isNullOrBlank() }
        if (rows.isEmpty()) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rows.forEach { row ->
            val number = row.number!!
            container.addView(
                buildContactDialogRow(
                    formatContactDialogLabel(row.displayName, number, row.fallbackLabelRes),
                    number,
                    secondaryColor
                )
            )
        }

        showProfessionalLockScreenDialog(getString(R.string.lock_screen_contacts_dialog_title), container)
    }

    private fun formatContactDialogLabel(displayName: String?, phoneNumber: String, fallbackLabelRes: Int): String {
        val name = displayName?.trim().orEmpty()
        return if (name.isNotEmpty()) {
            "$name: $phoneNumber"
        } else {
            getString(fallbackLabelRes, phoneNumber)
        }
    }

    private data class ContactDialogRow(
        val fallbackLabelRes: Int,
        val displayName: String?,
        val number: String?
    )

    private fun buildContactDialogRow(label: String, phoneNumber: String, callAccentColor: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val bottom = dpToPx(8)
            setPadding(0, bottom, 0, bottom)
        }
        val labelView = TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#212121"))
            textSize = 15f
        }
        val callButton = TextView(this).apply {
            text = getString(R.string.lock_screen_call)
            setTextColor(callAccentColor)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            setOnClickListener { dialPhoneNumber(phoneNumber) }
        }
        row.addView(labelView)
        row.addView(callButton)
        return row
    }

    private fun showLockScreenPaymentAppsDialog() {
        val installed = PAYMENT_APP_PACKAGES.mapNotNull { packageName ->
            if (!isPackageInstalled(packageName)) return@mapNotNull null
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                PaymentAppEntry(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(appInfo).toString(),
                    icon = packageManager.getApplicationIcon(appInfo)
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Payment app info failed pkg=$packageName", e)
                null
            }
        }

        if (installed.isEmpty()) {
            val messageView = TextView(this).apply {
                text = getString(R.string.lock_screen_no_payment_apps)
                setTextColor(Color.parseColor("#616161"))
                textSize = 15f
            }
            showProfessionalLockScreenDialog(
                getString(R.string.lock_screen_payment_apps_dialog_title),
                messageView
            )
            return
        }

        val grid = GridLayout(this).apply {
            columnCount = 3
        }
        installed.forEach { entry ->
            grid.addView(buildPaymentAppCell(entry))
        }

        showProfessionalLockScreenDialog(
            getString(R.string.lock_screen_payment_apps_dialog_title),
            grid
        )
    }

    private fun buildPaymentAppCell(entry: PaymentAppEntry): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val margin = dpToPx(8)
            layoutParams = GridLayout.LayoutParams().apply {
                width = dpToPx(96)
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener { launchPaymentApp(entry.packageName) }
        }
        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48))
            setImageDrawable(entry.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val labelView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4) }
            text = entry.label
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.parseColor("#212121"))
            textSize = 11f
            maxLines = 2
        }
        cell.addView(iconView)
        cell.addView(labelView)
        return cell
    }

    private fun copyBankAccountNumberToClipboard() {
        val number = lockScreenDealerContactInfo?.bankAccountNumber?.trim().orEmpty()
        if (number.isEmpty()) return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("bank_account_number", number))
            Toast.makeText(this, R.string.lock_screen_account_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "copyBankAccountNumberToClipboard failed", e)
        }
    }

    private fun dialPhoneNumber(number: String) {
        try {
            val sanitized = number.trim()
            if (sanitized.isEmpty()) return
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dialPhoneNumber failed", e)
            Toast.makeText(this, R.string.lock_screen_dial_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPaymentApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Toast.makeText(this, R.string.lock_screen_no_payment_apps, Toast.LENGTH_SHORT).show()
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "launchPaymentApp failed pkg=$packageName", e)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "isPackageInstalled failed pkg=$packageName", e)
            false
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private data class PaymentAppEntry(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

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
                val admin = ComponentName(this, IbsDeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setLockTaskPackages(
                        admin,
                        DeviceProtectionManager.buildLockTaskAllowedPackages(
                            this,
                            PAYMENT_APP_PACKAGES
                        )
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        dpm.setLockTaskFeatures(admin, 0)
                    }
                }
                startLockTask()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val lockTaskState = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .lockTaskModeState
                    android.util.Log.i(
                        TAG,
                        "startLockTask invoked: lockTaskModeState=$lockTaskState " +
                            "(LOCK_TASK_MODE_LOCKED=${ActivityManager.LOCK_TASK_MODE_LOCKED})"
                    )
                }
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
            if (isTouchOnEmergencyButton(ev) || isTouchOnLockScreenAddon(ev)) {
                super.dispatchTouchEvent(ev)
            } else {
                true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dispatchTouchEvent failed", e)
            true
        }
    }

    private fun isTouchOnLockScreenAddon(ev: MotionEvent): Boolean {
        val targets = listOf(
            binding.btnLockScreenContacts,
            binding.btnLockScreenPaymentApps,
            binding.btnCopyBankAccount,
            binding.layoutLockScreenBankInfo
        )
        val x = ev.rawX
        val y = ev.rawY
        return targets.any { view ->
            if (view.visibility != View.VISIBLE) return@any false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            x >= location[0] &&
                x <= location[0] + view.width &&
                y >= location[1] &&
                y <= location[1] + view.height
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
        private const val DEFAULT_BRAND_COLOR_HEX = "#0C447C"
        private const val DEFAULT_SECONDARY_COLOR_HEX = "#1D9E75"

        /** Package names to verify on real devices if an app does not appear. */
        private val PAYMENT_APP_PACKAGES = listOf(
            "com.techlogix.mobilinkcustomer",
            "pk.com.telenor.phoenix",
            "com.nayapay.nayapay",
            "com.sadapay.app",
            "com.hbl.android",
            "com.mbl.mib",
            "com.ubldigital.omni",
            "com.mcb.live"
        )

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
