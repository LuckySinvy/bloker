package com.addev.listaspam.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SelfHostedSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return SyncUtils.syncSelfHostedData(applicationContext)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() }
            )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "self_hosted_sync_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SelfHostedSyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(2, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
