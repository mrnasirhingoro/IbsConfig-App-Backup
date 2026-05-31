package com.ibs.configapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ibs.configapp.service.BackgroundService
import com.ibs.configapp.util.PrefsHelper

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrefsHelper.isActivated(this)) {
            BackgroundService.start(this)
            if (PrefsHelper.isLocked(this)) {
                startActivity(
                    Intent(this, LockScreenActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
            finish()
        } else {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            finish()
        }
    }
}
