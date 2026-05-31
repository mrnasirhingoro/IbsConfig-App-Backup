package com.ibs.configapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ibs.configapp.util.PrefsHelper

class UnlockDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LockScreenActivity.ACTION_UNLOCK_DEVICE) return
        PrefsHelper.setLocked(context, false)
        LockScreenActivity.dismissIfActive()
    }
}
