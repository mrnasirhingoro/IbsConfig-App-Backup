package com.ibs.configapp

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.ibs.configapp.firebase.FirebaseAuthHelper
import com.ibs.configapp.service.BackgroundService
import com.ibs.configapp.service.RestartJobService
import com.ibs.configapp.util.PrefsHelper

class IbsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }
        FirebaseAuthHelper.verifyProjectConfig(this)
        Log.i(TAG, "IBS Config App started Firebase project ibs-system-cb7dc")
        if (PrefsHelper.isActivated(this)) {
            BackgroundService.start(this)
            RestartJobService.schedule(this)
        }
    }

    companion object {
        private const val TAG = "IbsApplication"
    }
}
