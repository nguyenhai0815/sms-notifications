package com.guxplus.smsnotifications

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val code: Int, val body: String, val error: String)

object Sender {

    private const val BATCH = 50

    fun device(): String = Build.MANUFACTURER + " " + Build.MODEL

    /**
     * Day het hang doi. Tra ve true khi khong con gi phai thu lai, de worker biet
     * co can hen gio chay lai hay khong.
     */
    fun flush(context: Context): Boolean {
        Db.init(context)
        var clean = true

        for (delivery in Db.pending(BATCH)) {
            val payload = JSONObject().apply {
                put("type", "sms")
                put("message_id", delivery.sms.msgId)
                put("sender", delivery.sms.sender)
                put("body", delivery.sms.body)
                put("received_at", delivery.sms.receivedAt)
                put("sim", delivery.sms.sim)
                put("source", delivery.sms.source)
                put("device", device())
            }

            val result = post(delivery.target, payload.toString())
            val permanent = result.code in 400..499 && result.code != 408 && result.code != 429

            when {
                result.code in 200..299 -> Db.markSent(delivery.id)
                // Server che han thi thu lai bao nhieu lan cung the.
                permanent -> Db.markFailed(delivery.id, "HTTP " + result.code + " " + result.body.take(200))
                else -> {
                    Db.markRetry(delivery.id, if (result.code > 0) "HTTP " + result.code else result.error)
                    clean = false
                }
            }
        }

        return clean
    }

    /** Bao con song. Server im lau qua thi biet may doc tin da chet. */
    fun heartbeat(context: Context) {
        Db.init(context)
        val payload = JSONObject().apply {
            put("type", "heartbeat")
            put("device", device())
            put("sent_at", System.currentTimeMillis())
            put("pending", Db.countPending())
        }
        for (target in Db.targets(onlyEnabled = true)) post(target, payload.toString())
    }

    fun post(target: Target, json: String): HttpResult {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(target.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (target.token.isNotEmpty()) setRequestProperty("Authorization", "Bearer " + target.token)
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(json) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { raw ->
                BufferedReader(InputStreamReader(raw, Charsets.UTF_8)).use { it.readText() }
            } ?: ""

            return HttpResult(code, body, "")
        } catch (error: Exception) {
            return HttpResult(0, "", error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
