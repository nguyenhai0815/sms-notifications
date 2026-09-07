package com.guxplus.smsnotifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guxplus.smsnotifications.databinding.ActivityMainBinding
import com.guxplus.smsnotifications.databinding.DialogImportBinding
import com.guxplus.smsnotifications.databinding.RowTargetBinding
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val needed = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

    /**
     * Tin toi tay receiver chu khong tay man hinh, nen man hinh phai tu ngo lai.
     * Chi ve lai khi so lieu doi that, de khong giat danh sach dang cuon.
     */
    private val handler = Handler(Looper.getMainLooper())
    private var snapshot = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (state() != snapshot) render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rowState.setOnClickListener { ask() }
        binding.btnImport.setOnClickListener { importTargets() }
        binding.btnMore.setOnClickListener { showMenu() }
        binding.btnScan.setOnClickListener {
            RelayWorker.kick(this, scan = true)
            Ui.toast(this, getString(R.string.queued_scan))
        }
        binding.btnFlush.setOnClickListener {
            RelayWorker.kick(this, scan = false)
            Ui.toast(this, getString(R.string.queued_flush))
        }
        binding.fabAdd.setOnClickListener { startActivity(TargetEditActivity.intent(this, 0)) }
        binding.tileAll.setOnClickListener { startActivity(Intent(this, AllMessagesActivity::class.java)) }

        ask()
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

    // ----- quyen -----

    private fun missing(): List<String> = needed.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun ask() {
        val missing = missing()
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    private fun showMenu() {
        PopupMenu(this, binding.btnMore).apply {
            menu.add(R.string.permission)
            setOnMenuItemClickListener {
                ask()
                true
            }
            show()
        }
    }

    // ----- ve man hinh -----

    /** Dau van tay cua du lieu, doi thi moi ve lai man hinh. */
    private fun state(): String =
        Db.lastReceivedAt().toString() + "/" + Db.lastSentAt() + "/" + Db.countPending() + "/" + Db.targets().size

    private fun render() {
        snapshot = state()

        val granted = missing().isEmpty()
        binding.tvState.text = getString(if (granted) R.string.state_running else R.string.state_no_permission)
        binding.tvStateSub.text =
            getString(if (granted) R.string.state_sub else R.string.state_sub_no_permission, Sender.device())
        binding.ivState.setImageResource(if (granted) R.drawable.ic_sms else R.drawable.ic_shield)

        binding.tvPending.text = Db.countPending().toString()
        binding.tvLastReceived.text = Ui.stamp(Db.lastReceivedAt())
        binding.tvLastSent.text = Ui.stamp(Db.lastSentAt())

        drawTargets()

        val count = Db.countMessages()
        val last = Db.recent(1).firstOrNull()
        binding.tvAllSub.text = if (last == null) {
            getString(R.string.no_message)
        } else {
            getString(R.string.all_messages_sub, count) + " · " + last.sms.sender + " · " + last.sms.body
        }
    }

    private fun drawTargets() {
        binding.listTargets.removeAllViews()
        val targets = Db.targets()

        if (targets.isEmpty()) {
            binding.listTargets.addView(hint(getString(R.string.no_project)))
            return
        }

        targets.forEachIndexed { index, target ->
            val row = RowTargetBinding.inflate(layoutInflater, binding.listTargets, false)
            val (bg, fg) = Ui.avatarColors(this, index)
            row.avatar.backgroundTintList = ColorStateList.valueOf(bg)
            row.tvInitial.setTextColor(fg)
            row.tvInitial.text = Ui.initial(target.name)
            row.tvName.text = target.name

            val last = Db.lastSms(target.id)
            val line = if (last == null) {
                getString(R.string.no_message_yet)
            } else {
                Ui.stamp(last.receivedAt) + " · " + last.sender + " · " + last.body
            }
            row.tvSub.text = if (target.enabled) line else getString(R.string.project_off) + " · " + line

            val stats = Db.stats(target.id)
            row.tvSent.text = getString(R.string.badge_sent, stats.sent)
            row.tvPending.text = getString(R.string.badge_pending, stats.pending)
            row.tvFailed.text = getString(R.string.badge_failed, stats.failed)
            row.tvPending.visibility = if (stats.pending > 0) View.VISIBLE else View.GONE
            row.tvFailed.visibility = if (stats.failed > 0) View.VISIBLE else View.GONE

            row.swEnabled.isChecked = target.enabled
            row.swEnabled.setOnCheckedChangeListener { _, checked ->
                Db.saveTarget(
                    target.id, target.name, target.url, target.token,
                    target.senders, target.keywords, checked,
                )
                row.tvSub.text = if (checked) line else getString(R.string.project_off) + " · " + line
            }
            row.root.setOnClickListener { startActivity(TargetActivity.intent(this, target.id)) }
            binding.listTargets.addView(row.root)
        }
    }

    private fun hint(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ui.color(context, R.color.muted))
        setPadding(4, 24, 0, 8)
    }

    // ----- nhap json -----

    private fun importTargets() {
        val view = DialogImportBinding.inflate(layoutInflater)

        AlertDialog.Builder(this)
            .setTitle(R.string.import_json)
            .setView(view.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val count = try {
                    applyJson(view.etJson.text.toString().trim())
                } catch (error: Exception) {
                    -1
                }
                Ui.toast(this, if (count < 0) getString(R.string.bad_json) else getString(R.string.imported, count))
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyJson(text: String): Int {
        val array = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            Db.saveTarget(
                id = 0,
                name = item.optString("name", item.optString("url")),
                url = item.optString("url"),
                token = item.optString("token"),
                senders = item.optString("senders"),
                keywords = item.optString("keywords"),
                enabled = item.optBoolean("enabled", true),
            )
        }
        return array.length()
    }

    companion object {
        private const val REFRESH_MS = 2000L
    }
}
