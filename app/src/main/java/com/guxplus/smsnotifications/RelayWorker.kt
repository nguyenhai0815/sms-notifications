package com.guxplus.smsnotifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class RelayWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Db.init(applicationContext)

        val scan = inputData.getBoolean(SCAN, false)
        if (scan) InboxScanner.scan(applicationContext)

        val clean = Sender.flush(applicationContext)
        if (scan) Sender.heartbeat(applicationContext)

        // Con tin chua gui duoc thi de WorkManager gian gio thu lai, khong bo.
        return if (clean) Result.success() else Result.retry()
    }

    companion object {

        private const val SCAN = "scan"
        private const val NOW = "relay-now"

        fun data(scan: Boolean): Data = Data.Builder().putBoolean(SCAN, scan).build()

        fun kick(context: Context, scan: Boolean) {
            val request = OneTimeWorkRequestBuilder<RelayWorker>()
                .setInputData(data(scan))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
