package com.guxplus.smsnotifications

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Luoi an toan cho SmsReceiver. May tat nguon hay bi he thong giet dung luc tin
 * toi thi receiver khong chay - quet lai hop tin se vot duoc phan do. Trung lap
 * khong sao vi kho da co khoa chong trung.
 */
object InboxScanner {

    private const val OVERLAP_MS = 5 * 60 * 1000L

    fun scan(context: Context) {
        Db.init(context)
        val from = Db.lastScan()
        val now = System.currentTimeMillis()

        try {
            val columns = arrayOf("address", "body", "date")
            context.contentResolver
                .query(Uri.parse("content://sms/inbox"), columns, "date > ?", arrayOf(from.toString()), "date ASC")
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val sender = cursor.getString(0) ?: continue
                        val body = cursor.getString(1) ?: ""
                        Db.saveSms(sender, body, cursor.getLong(2), "", "inbox")
                    }
                }
            // Lui moc lai mot chut de tin roi dung ranh gioi khong bi bo sot.
            Db.setLastScan(now - OVERLAP_MS)
        } catch (error: Exception) {
            Log.w("InboxScanner", "khong doc duoc hop tin", error)
        }
    }
}
