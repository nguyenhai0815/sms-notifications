package com.guxplus.smsnotifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guxplus.smsnotifications.databinding.ActivityMainBinding
import com.guxplus.smsnotifications.databinding.DialogImportBinding
import com.guxplus.smsnotifications.databinding.DialogTargetBinding
import com.guxplus.smsnotifications.databinding.RowMessageBinding
import com.guxplus.smsnotifications.databinding.RowTargetBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val needed = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    private val clock = SimpleDateFormat("dd/MM HH:mm", Locale("vi", "VN"))

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

        binding.btnPermission.setOnClickListener { ask() }
        binding.btnScan.setOnClickListener {
            RelayWorker.kick(this, scan = true)
            toast(getString(R.string.queued_scan))
        }
        binding.btnFlush.setOnClickListener {
            RelayWorker.kick(this, scan = false)
            toast(getString(R.string.queued_flush))
        }
        binding.btnAddTarget.setOnClickListener { editTarget(null) }
        binding.btnImport.setOnClickListener { importTargets() }

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

    // ----- ve man hinh -----

    /** Dau van tay cua du lieu, doi thi moi ve lai man hinh. */
    private fun state(): String =
        Db.lastReceivedAt().toString() + "/" + Db.lastSentAt() + "/" + Db.countPending()

    private fun render() {
        snapshot = state()

        val granted = if (missing().isEmpty()) getString(R.string.granted) else getString(R.string.not_granted)
        binding.tvStatus.text = listOf(
            getString(R.string.status_permission, granted),
            getString(R.string.status_pending, Db.countPending()),
            getString(R.string.status_last_received, stamp(Db.lastReceivedAt())),
            getString(R.string.status_last_sent, stamp(Db.lastSentAt())),
        ).joinToString("\n")

        drawTargets()
        drawMessages()
    }

    private fun drawTargets() {
        binding.listTargets.removeAllViews()
        val targets = Db.targets()

        if (targets.isEmpty()) {
            binding.listTargets.addView(hint(getString(R.string.no_target)))
            return
        }

        for (target in targets) {
            val row = RowTargetBinding.inflate(layoutInflater, binding.listTargets, false)
            row.tvName.text = target.name
            row.tvUrl.text = target.url
            row.tvSenders.text =
                if (target.senders.isBlank()) getString(R.string.all_senders) else target.senders
            row.swEnabled.isChecked = target.enabled
            row.swEnabled.setOnCheckedChangeListener { _, checked ->
                Db.saveTarget(target.id, target.name, target.url, target.token, target.senders, checked)
            }
            row.root.setOnClickListener { editTarget(target) }
            row.root.setOnLongClickListener {
                confirmDelete(target)
                true
            }
            binding.listTargets.addView(row.root)
        }
    }

    private fun drawMessages() {
        binding.listMessages.removeAllViews()
        val rows = Db.recent(30)

        if (rows.isEmpty()) {
            binding.listMessages.addView(hint(getString(R.string.no_message)))
            return
        }

        for (item in rows) {
            val row = RowMessageBinding.inflate(layoutInflater, binding.listMessages, false)
            val badge = getString(R.string.badge, item.sent, item.pending, item.failed)
            row.tvHead.text = getString(R.string.message_head, item.sms.sender, stamp(item.sms.receivedAt), badge)
            row.tvBody.text = item.sms.body
            row.root.setOnClickListener { showMessage(item) }
            binding.listMessages.addView(row.root)
        }
    }

    private fun hint(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 12, 0, 0)
    }

    private fun stamp(value: Long): String = if (value <= 0) "-" else clock.format(Date(value))

    // ----- dich den -----

    private fun editTarget(target: Target?) {
        val view = DialogTargetBinding.inflate(layoutInflater)
        view.etName.setText(target?.name ?: "")
        view.etUrl.setText(target?.url ?: "")
        view.etToken.setText(target?.token ?: "")
        view.etSenders.setText(target?.senders ?: "")

        AlertDialog.Builder(this)
            .setTitle(if (target == null) R.string.add_target else R.string.edit_target)
            .setView(view.root)
            .setPositiveButton(R.string.save) { _, _ ->
                Db.saveTarget(
                    id = target?.id ?: 0,
                    name = view.etName.text.toString().trim(),
                    url = view.etUrl.text.toString().trim(),
                    token = view.etToken.text.toString().trim(),
                    senders = view.etSenders.text.toString().trim(),
                    enabled = target?.enabled ?: true,
                )
                render()
            }
            .setNeutralButton(R.string.test) { _, _ ->
                test(view.etUrl.text.toString().trim(), view.etToken.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(target: Target) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_target, target.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Db.deleteTarget(target.id)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Ban thu mot goi rong de biet URL va token co thong hay khong. */
    private fun test(url: String, token: String) {
        if (url.isEmpty()) {
            toast(getString(R.string.need_url))
            return
        }

        val target = Target(0, "test", url, token, "", true)
        Thread {
            val payload = JSONObject().apply {
                put("type", "test")
                put("device", Sender.device())
                put("sent_at", System.currentTimeMillis())
            }
            val result = Sender.post(target, payload.toString())
            val text = if (result.code > 0) {
                "HTTP " + result.code + " " + result.body.take(120)
            } else {
                result.error
            }
            runOnUiThread { toast(text) }
        }.start()
    }

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
                toast(if (count < 0) getString(R.string.bad_json) else getString(R.string.imported, count))
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
                enabled = item.optBoolean("enabled", true),
            )
        }
        return array.length()
    }

    // ----- mot tin -----

    private fun showMessage(item: SmsRow) {
        val detail = StringBuilder()
            .append(item.sms.sender)
            .append("\n")
            .append(stamp(item.sms.receivedAt))
            .append("\n\n")
            .append(item.sms.body)

        if (item.lastError.isNotEmpty()) detail.append("\n\n").append(item.lastError)

        AlertDialog.Builder(this)
            .setMessage(detail.toString())
            .setPositiveButton(R.string.resend) { _, _ ->
                Db.requeue(item.sms.id)
                RelayWorker.kick(this, scan = false)
                render()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REFRESH_MS = 2000L
    }
}
