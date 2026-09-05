package com.guxplus.smsnotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Bat tin ngay luc no toi may, chay ca khi app dang dong. Chi luu vao kho roi
 * danh thuc worker - onReceive khong duoc phep lam viec dai.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // Tin dai bi cha thanh nhieu manh, ghep lai thanh mot chuoi.
        val sender = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val receivedAt = parts[0].timestampMillis
        val slot = intent.getIntExtra("subscription", -1)
        val sim = if (slot >= 0) slot.toString() else ""

        Db.init(context)
        Db.saveSms(sender, body, receivedAt, sim, "push")
        RelayWorker.kick(context, scan = false)
    }
}
