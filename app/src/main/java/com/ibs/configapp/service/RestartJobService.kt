package com.ibs.configapp.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.ibs.configapp.util.PrefsHelper

class RestartJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (PrefsHelper.isActivated(applicationContext)) {
            BackgroundService.start(applicationContext)
        }
        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 9001

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, RestartJobService::class.java)
            val job = JobInfo.Builder(JOB_ID, component)
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            try {
                scheduler.schedule(job)
            } catch (_: Exception) {
                val fallback = JobInfo.Builder(JOB_ID, component)
                    .setPeriodic(15 * 60 * 1000L)
                    .build()
                scheduler.schedule(fallback)
            }
        }
    }
}
