package com.guxplus.smsnotifications

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class Target(
    val id: Long,
    val name: String,
    val url: String,
    val token: String,
    val senders: String,
    val keywords: String,
    val enabled: Boolean,
)

data class Sms(
    val id: Long,
    val msgId: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val sim: String,
    val source: String,
)

data class Delivery(
    val id: Long,
    val attempts: Int,
    val target: Target,
    val sms: Sms,
)

data class SmsRow(
    val sms: Sms,
    val pending: Int,
    val sent: Int,
    val failed: Int,
    val lastError: String,
)

/**
 * Kho cuc bo. Tin nhan duoc luu truoc roi moi gui, nen mat mang hay server chet
 * cung khong mat tin - lan chay sau cu the gui tiep.
 */
object Db {

    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"

    private const val LAST_SCAN = "last_scan_at"

    private var helper: Helper? = null

    fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    private fun db(): SQLiteDatabase = helper!!.writableDatabase

    // ----- dich den -----

    fun targets(onlyEnabled: Boolean = false): List<Target> {
        val filter = if (onlyEnabled) "WHERE enabled = 1" else ""
        val rows = ArrayList<Target>()
        db().rawQuery("SELECT id, name, url, token, senders, keywords, enabled FROM targets $filter ORDER BY id", null)
            .use { cursor ->
                while (cursor.moveToNext()) rows.add(target(cursor, 0))
            }
        return rows
    }

    private fun target(cursor: Cursor, offset: Int) = Target(
        id = cursor.getLong(offset),
        name = cursor.getString(offset + 1),
        url = cursor.getString(offset + 2),
        token = cursor.getString(offset + 3),
        senders = cursor.getString(offset + 4),
        keywords = cursor.getString(offset + 5),
        enabled = cursor.getInt(offset + 6) == 1,
    )

    fun saveTarget(
        id: Long,
        name: String,
        url: String,
        token: String,
        senders: String,
        keywords: String,
        enabled: Boolean,
    ) {
        val values = ContentValues().apply {
            put("name", name)
            put("url", url)
            put("token", token)
            put("senders", senders)
            put("keywords", keywords)
            put("enabled", if (enabled) 1 else 0)
        }
        if (id > 0) {
            db().update("targets", values, "id = ?", arrayOf(id.toString()))
        } else {
            db().insert("targets", null, values)
        }
    }

    fun deleteTarget(id: Long) {
        db().delete("deliveries", "target_id = ?", arrayOf(id.toString()))
        db().delete("targets", "id = ?", arrayOf(id.toString()))
    }

    // ----- tin nhan -----

    /** Tra ve true neu day la tin moi. Tin cu nhan lai lan nua thi bo qua. */
    fun saveSms(sender: String, body: String, receivedAt: Long, sim: String, source: String): Boolean {
        val values = ContentValues().apply {
            put("msg_id", msgId(sender, body))
            put("sender", sender)
            put("body", body)
            put("received_at", receivedAt)
            put("sim", sim)
            put("source", source)
        }
        val id = db().insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) return false
        queue(id, sender, body)
        return true
    }

    /** Xep tin vao hang doi cua moi dich dang bat va qua duoc bo loc cua dich do. */
    private fun queue(smsId: Long, sender: String, body: String) {
        val now = System.currentTimeMillis()
        for (item in targets(onlyEnabled = true)) {
            if (!accepts(item, sender, body)) continue
            val values = ContentValues().apply {
                put("message_id", smsId)
                put("target_id", item.id)
                put("status", PENDING)
                put("attempts", 0)
                put("last_error", "")
                put("updated_at", now)
            }
            db().insertWithOnConflict("deliveries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /**
     * Hai lop loc, lop nao rong thi bo qua lop do:
     *  - nguoi gui phai chua mot trong cac ten da khai
     *  - noi dung phai chua mot trong cac tu khoa da khai (vi du "GD:,SD:" de chi
     *    lay tin bien dong so du, bo OTP va quang cao cung dau so)
     */
    fun accepts(target: Target, sender: String, body: String): Boolean {
        return matches(target.senders, sender) && matches(target.keywords, body)
    }

    private fun matches(filter: String, text: String): Boolean {
        val wanted = filter.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return true
        val haystack = text.lowercase()
        return wanted.any { haystack.contains(it) }
    }

    /**
     * Khoa chong trung. Cung nguoi gui va cung noi dung thi coi la mot tin, nho
     * vay ban bat duoc luc tin toi va ban quet lai tu hop tin khong thanh hai.
     */
    fun msgId(sender: String, body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((sender + "|" + body).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ----- hang doi gui -----

    fun pending(limit: Int): List<Delivery> {
        val sql = """
            SELECT d.id, d.attempts,
                   t.id, t.name, t.url, t.token, t.senders, t.keywords, t.enabled,
                   m.id, m.msg_id, m.sender, m.body, m.received_at, m.sim, m.source
            FROM deliveries d
            JOIN targets t ON t.id = d.target_id
            JOIN messages m ON m.id = d.message_id
            WHERE d.status = ? AND t.enabled = 1
            ORDER BY d.id
            LIMIT ?
        """.trimIndent()
        val rows = ArrayList<Delivery>()
        db().rawQuery(sql, arrayOf(PENDING, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    Delivery(
                        id = cursor.getLong(0),
                        attempts = cursor.getInt(1),
                        target = target(cursor, 2),
                        sms = Sms(
                            id = cursor.getLong(9),
                            msgId = cursor.getString(10),
                            sender = cursor.getString(11),
                            body = cursor.getString(12),
                            receivedAt = cursor.getLong(13),
                            sim = cursor.getString(14),
                            source = cursor.getString(15),
                        ),
                    )
                )
            }
        }
        return rows
    }

    fun markSent(id: Long) = mark(id, SENT, "")

    fun markFailed(id: Long, error: String) = mark(id, FAILED, error)

    fun markRetry(id: Long, error: String) = mark(id, PENDING, error)

    private fun mark(id: Long, status: String, error: String) {
        db().execSQL(
            "UPDATE deliveries SET status = ?, last_error = ?, attempts = attempts + 1, updated_at = ? WHERE id = ?",
            arrayOf<Any>(status, error, System.currentTimeMillis(), id),
        )
    }

    /** Bat gui lai mot tin: cho cac dong hong ve hang doi, va bu dich moi them. */
    fun requeue(smsId: Long) {
        db().execSQL(
            "UPDATE deliveries SET status = ?, last_error = '', updated_at = ? WHERE message_id = ? AND status = ?",
            arrayOf<Any>(PENDING, System.currentTimeMillis(), smsId, FAILED),
        )
        val sender = one("SELECT sender FROM messages WHERE id = ?", arrayOf(smsId.toString())) ?: return
        val body = one("SELECT body FROM messages WHERE id = ?", arrayOf(smsId.toString())) ?: ""
        queue(smsId, sender, body)
    }

    // ----- so lieu cho man hinh -----

    fun countPending(): Int =
        one("SELECT COUNT(*) FROM deliveries WHERE status = ?", arrayOf(PENDING))?.toIntOrNull() ?: 0

    fun lastReceivedAt(): Long =
        one("SELECT MAX(received_at) FROM messages", arrayOf())?.toLongOrNull() ?: 0

    fun lastSentAt(): Long =
        one("SELECT MAX(updated_at) FROM deliveries WHERE status = ?", arrayOf(SENT))?.toLongOrNull() ?: 0

    fun recent(limit: Int): List<SmsRow> {
        val sql = """
            SELECT m.id, m.msg_id, m.sender, m.body, m.received_at, m.sim, m.source,
                   (SELECT COUNT(*) FROM deliveries d WHERE d.message_id = m.id AND d.status = 'PENDING'),
                   (SELECT COUNT(*) FROM deliveries d WHERE d.message_id = m.id AND d.status = 'SENT'),
                   (SELECT COUNT(*) FROM deliveries d WHERE d.message_id = m.id AND d.status = 'FAILED'),
                   (SELECT d.last_error FROM deliveries d WHERE d.message_id = m.id AND d.last_error <> ''
                    ORDER BY d.updated_at DESC LIMIT 1)
            FROM messages m
            ORDER BY m.received_at DESC
            LIMIT ?
        """.trimIndent()
        val rows = ArrayList<SmsRow>()
        db().rawQuery(sql, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    SmsRow(
                        sms = Sms(
                            id = cursor.getLong(0),
                            msgId = cursor.getString(1),
                            sender = cursor.getString(2),
                            body = cursor.getString(3),
                            receivedAt = cursor.getLong(4),
                            sim = cursor.getString(5),
                            source = cursor.getString(6),
                        ),
                        pending = cursor.getInt(7),
                        sent = cursor.getInt(8),
                        failed = cursor.getInt(9),
                        lastError = if (cursor.isNull(10)) "" else cursor.getString(10),
                    )
                )
            }
        }
        return rows
    }

    // ----- moc quet hop tin -----

    fun lastScan(): Long =
        one("SELECT value FROM settings WHERE name = ?", arrayOf(LAST_SCAN))?.toLongOrNull() ?: 0

    fun setLastScan(value: Long) {
        db().execSQL(
            "INSERT OR REPLACE INTO settings(name, value) VALUES (?, ?)",
            arrayOf<Any>(LAST_SCAN, value.toString()),
        )
    }

    private fun one(sql: String, args: Array<String>): String? {
        db().rawQuery(sql, args).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "relay.db", null, 2) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE targets (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "token TEXT NOT NULL DEFAULT '', " +
                    "senders TEXT NOT NULL DEFAULT '', " +
                    "keywords TEXT NOT NULL DEFAULT '', " +
                    "enabled INTEGER NOT NULL DEFAULT 1)"
            )
            db.execSQL(
                "CREATE TABLE messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "msg_id TEXT NOT NULL UNIQUE, " +
                    "sender TEXT NOT NULL, " +
                    "body TEXT NOT NULL, " +
                    "received_at INTEGER NOT NULL, " +
                    "sim TEXT NOT NULL DEFAULT '', " +
                    "source TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL(
                "CREATE TABLE deliveries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "message_id INTEGER NOT NULL, " +
                    "target_id INTEGER NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "attempts INTEGER NOT NULL DEFAULT 0, " +
                    "last_error TEXT NOT NULL DEFAULT '', " +
                    "updated_at INTEGER NOT NULL, " +
                    "UNIQUE(message_id, target_id))"
            )
            db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")

            // Moc quet dat bang luc cai, de lan dau chay khong nap ca hop tin cu.
            db.execSQL(
                "INSERT INTO settings(name, value) VALUES (?, ?)",
                arrayOf<Any>(LAST_SCAN, System.currentTimeMillis().toString()),
            )
        }

        // Ban da cai tren may thi chi them cot, khong dung vao du lieu cu.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE targets ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
