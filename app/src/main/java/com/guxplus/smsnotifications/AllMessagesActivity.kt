package com.guxplus.smsnotifications

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.guxplus.smsnotifications.databinding.ActivityAllMessagesBinding
import com.guxplus.smsnotifications.databinding.RowMessageBinding

/** Moi tin may nhan duoc, ke ca tin khong khop project nao, de soi khi loc sai. */
class AllMessagesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllMessagesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)

        binding = ActivityAllMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        binding.tvSub.text = getString(R.string.all_messages_sub, Db.countMessages())
        binding.listMessages.removeAllViews()
        val rows = Db.recent(100)

        if (rows.isEmpty()) {
            binding.listMessages.addView(hint(getString(R.string.no_message)))
            return
        }

        for (item in rows) {
            val row = RowMessageBinding.inflate(layoutInflater, binding.listMessages, false)
            val status = Ui.overallStatus(item.sent, item.pending, item.failed)
            val badge = if (status == null) {
                getString(R.string.not_matched)
            } else {
                getString(R.string.badge, item.sent, item.pending, item.failed)
            }
            Ui.bindMessage(this, row, item.sms, status, badge)
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
}
