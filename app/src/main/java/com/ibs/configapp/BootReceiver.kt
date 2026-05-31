package com.ibs.configapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ibs.configapp.service.BackgroundService
import com.ibs.configapp.service.RestartJobService
import com.ibs.configapp.util.PrefsHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        if (!PrefsHelper.isActivated(context)) return
        BackgroundService.start(context)
        RestartJobService.schedule(context)
        if (PrefsHelper.isLocked(context)) {
            val lockIntent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(lockIntent)
        }
    }
}
