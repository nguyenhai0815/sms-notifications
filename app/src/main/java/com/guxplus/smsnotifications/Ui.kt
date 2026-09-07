package com.guxplus.smsnotifications

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.guxplus.smsnotifications.databinding.RowMessageBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Nhung mieng ve dung chung cho ca ba man hinh. */
object Ui {

    private val locale = Locale("vi", "VN")
    private val full = SimpleDateFormat("dd/MM HH:mm", locale)
    private val short = SimpleDateFormat("HH:mm", locale)

    /** Hom nay chi hien gio, ngay khac hien ca ngay. */
    fun stamp(value: Long): String {
        if (value <= 0) return "-"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = value }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return (if (sameDay) short else full).format(Date(value))
    }

    fun stampFull(value: Long): String = if (value <= 0) "-" else full.format(Date(value))

    fun maskToken(token: String): String = when {
        token.isEmpty() -> "-"
        token.length <= 8 -> "••••••••"
        else -> token.take(4) + "••••••••" + token.takeLast(4)
    }

    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun color(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

    /** Mau chu cai dau cua project, xoay vong theo thu tu. */
    fun avatarColors(context: Context, index: Int): Pair<Int, Int> {
        val pairs = listOf(
            R.color.primary_soft to R.color.primary,
            R.color.blue_soft to R.color.blue,
            R.color.purple_soft to R.color.purple,
            R.color.warn_soft to R.color.warn,
        )
        val (bg, fg) = pairs[index % pairs.size]
        return color(context, bg) to color(context, fg)
    }

    /**
     * Ve mot the tin. status la trang thai giao toi mot project; null la dang xem
     * chung moi tin, luc do tinh mau theo tong so gui/cho/loi.
     */
    fun bindMessage(
        context: Context,
        row: RowMessageBinding,
        sms: Sms,
        status: String?,
        badge: String,
    ) {
        row.tvSender.text = sms.sender
        row.tvTime.text = stamp(sms.receivedAt)
        row.tvBody.text = sms.body

        val (bg, fg, icon) = when (status) {
            Db.SENT -> Triple(R.color.ok_soft, R.color.ok, R.drawable.ic_check)
            Db.PENDING -> Triple(R.color.warn_soft, R.color.warn, R.drawable.ic_schedule)
            Db.FAILED -> Triple(R.color.err_soft, R.color.err, R.drawable.ic_error)
            else -> Triple(R.color.screen, R.color.muted, R.drawable.ic_inbox)
        }
        row.statusBg.backgroundTintList = ColorStateList.valueOf(color(context, bg))
        row.ivStatus.setImageResource(icon)
        row.ivStatus.imageTintList = ColorStateList.valueOf(color(context, fg))

        if (badge.isEmpty()) {
            row.tvBadge.visibility = android.view.View.GONE
        } else {
            row.tvBadge.visibility = android.view.View.VISIBLE
            row.tvBadge.text = badge
            row.tvBadge.backgroundTintList = ColorStateList.valueOf(color(context, bg))
            row.tvBadge.setTextColor(color(context, fg))
        }
    }

    /** Trang thai gop cua mot tin khi xem chung: loi noi bat hon cho, cho noi bat hon da gui. */
    fun overallStatus(sent: Int, pending: Int, failed: Int): String? = when {
        failed > 0 -> Db.FAILED
        pending > 0 -> Db.PENDING
        sent > 0 -> Db.SENT
        else -> null
    }

    fun showMessage(activity: Activity, sms: Sms, lastError: String, onResend: () -> Unit) {
        val detail = StringBuilder()
            .append(sms.sender)
            .append("\n")
            .append(stampFull(sms.receivedAt))
            .append("\n\n")
            .append(sms.body)
        if (lastError.isNotEmpty()) detail.append("\n\n").append(lastError)

        AlertDialog.Builder(activity)
            .setMessage(detail.toString())
            .setPositiveButton(R.string.resend) { _, _ -> onResend() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    fun deliveryLine(context: Context, item: TargetMessage): String = when (item.status) {
        Db.SENT -> context.getString(R.string.delivery_sent)
        Db.FAILED -> context.getString(R.string.delivery_failed, item.lastError.ifEmpty { "?" })
        else ->
            if (item.attempts == 0) context.getString(R.string.delivery_pending)
            else context.getString(R.string.delivery_pending_retry, item.attempts, item.lastError.ifEmpty { "?" })
    }
}
