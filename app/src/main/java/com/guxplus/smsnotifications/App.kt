package com.guxplus.smsnotifications

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Db.init(this)
        schedule(this)
    }

    companion object {

        private const val PERIODIC = "relay-periodic"

        /**
         * Nhip nen 15 phut mot lan: quet bu hop tin, gui lai hang doi con ket, va
         * bao cho server biet may van con song. WorkManager tu dat lai sau khi
         * khoi dong may nen khong can nghe BOOT_COMPLETED.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RelayWorker>(15, TimeUnit.MINUTES)
                .setInputData(RelayWorker.data(scan = true))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
