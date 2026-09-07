package com.guxplus.smsnotifications

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.guxplus.smsnotifications.databinding.ActivityTargetBinding
import com.guxplus.smsnotifications.databinding.RowMessageBinding

/** Mot project: cau hinh o tren, tin da di len project do o duoi. */
class TargetActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTargetBinding
    private var id = 0L
    private var target: Target? = null

    private val handler = Handler(Looper.getMainLooper())
    private var snapshot = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (state() != snapshot) render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        id = intent.getLongExtra(EXTRA_ID, 0)

        binding = ActivityTargetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEdit.setOnClickListener { startActivity(TargetEditActivity.intent(this, id)) }
        binding.btnMore.setOnClickListener { showMenu() }
        binding.swEnabled.setOnCheckedChangeListener { _, checked ->
            val current = target ?: return@setOnCheckedChangeListener
            if (current.enabled == checked) return@setOnCheckedChangeListener
            Db.saveTarget(
                current.id, current.name, current.url, current.token,
                current.senders, current.keywords, checked,
            )
            render()
        }
        binding.btnTest.setOnClickListener { test() }
        binding.btnFlushFailed.setOnClickListener {
            Db.requeueFailed(id)
            RelayWorker.kick(this, scan = false)
            Ui.toast(this, getString(R.string.queued_flush_failed))
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.postDelayed(ticker, REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun state(): String {
        val stats = Db.stats(id)
        return Db.lastReceivedAt().toString() + "/" + Db.lastSentAt() + "/" +
            stats.sent + "/" + stats.pending + "/" + stats.failed
    }

    private fun render() {
        snapshot = state()
        val current = Db.target(id)
        if (current == null) {
            finish()
            return
        }
        target = current

        binding.tvTitle.text = current.name

        val stats = Db.stats(id)
        binding.tvSent.text = stats.sent.toString()
        binding.tvPending.text = stats.pending.toString()
        binding.tvFailed.text = stats.failed.toString()

        binding.tvEnabled.text = getString(if (current.enabled) R.string.project_on else R.string.project_off)
        binding.tvEnabled.setTextColor(Ui.color(this, if (current.enabled) R.color.ok else R.color.muted))
        binding.tvEnabled.backgroundTintList =
            ColorStateList.valueOf(Ui.color(this, if (current.enabled) R.color.ok_soft else R.color.screen))
        binding.swEnabled.isChecked = current.enabled

        binding.tvUrl.text = current.url
        binding.tvToken.text = Ui.maskToken(current.token)
        binding.tvSenders.text = current.senders.ifBlank { getString(R.string.all_senders) }
        binding.tvKeywords.text = current.keywords.ifBlank { getString(R.string.all_keywords) }
        binding.btnFlushFailed.isEnabled = stats.failed > 0

        drawMessages()
    }

    private fun drawMessages() {
        binding.listMessages.removeAllViews()
        val rows = Db.messagesFor(id, 50)

        if (rows.isEmpty()) {
            binding.listMessages.addView(hint(getString(R.string.no_message_yet)))
            return
        }

        for (item in rows) {
            val row = RowMessageBinding.inflate(layoutInflater, binding.listMessages, false)
            // Da gui thi khong can nhan, mau xanh o icon la du.
            val badge = if (item.status == Db.SENT) "" else Ui.deliveryLine(this, item)
            Ui.bindMessage(this, row, item.sms, item.status, badge)
            row.root.setOnClickListener {
                Ui.showMessage(this, item.sms, item.lastError) {
                    Db.requeue(item.sms.id)
                    RelayWorker.kick(this, scan = false)
                    render()
                }
            }
            binding.listMessages.addView(row.root)
        }
    }

    private fun hint(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ui.color(context, R.color.muted))
        setPadding(4, 24, 0, 8)
    }

    private fun showMenu() {
        PopupMenu(this, binding.btnMore).apply {
            menu.add(R.string.delete)
            setOnMenuItemClickListener {
                confirmDelete()
                true
            }
            show()
        }
    }

    private fun confirmDelete() {
        val current = target ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_target, current.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Db.deleteTarget(current.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun test() {
        val current = target ?: return
        Ui.toast(this, getString(R.string.testing))
        Thread {
            val result = Sender.test(current.url, current.token)
            val text = if (result.http.code > 0) {
                getString(R.string.test_detail, result.http.code, result.http.body.take(120), result.ms)
            } else {
                getString(R.string.test_error, result.http.error, result.ms)
            }
            runOnUiThread { Ui.toast(this, text) }
        }.start()
    }

    companion object {
        private const val REFRESH_MS = 2000L
        private const val EXTRA_ID = "target_id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, TargetActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
