package com.ramboost.app

import android.app.job.JobParameters
import android.app.job.JobService

class AutoCleanJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Cleaner.clean(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
