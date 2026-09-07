package com.guxplus.smsnotifications

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.guxplus.smsnotifications.databinding.ActivityTargetEditBinding

/**
 * Ba buoc: ket noi, bo loc, kiem tra. Buoc cuoi tu ban goi thu roi hien ket qua
 * ngay tren man de biet URL va token co thong truoc khi luu.
 */
class TargetEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTargetEditBinding
    private var id = 0L
    private var enabled = true
    private var step = 0
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        id = intent.getLongExtra(EXTRA_ID, 0)

        binding = ActivityTargetEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val target = if (id > 0) Db.target(id) else null
        binding.tvTitle.text = getString(if (target == null) R.string.add_project else R.string.edit_project)
        target?.let {
            enabled = it.enabled
            binding.etName.setText(it.name)
            binding.etUrl.setText(it.url)
            binding.etToken.setText(it.token)
            binding.etSenders.setText(it.senders)
            binding.etKeywords.setText(it.keywords)
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.tilToken.setEndIconOnClickListener { pasteToken() }
        binding.btnBack.setOnClickListener { if (step == 0) finish() else go(step - 1) }
        binding.btnNext.setOnClickListener { next() }
        binding.btnTest.setOnClickListener { runTest() }

        go(0)
    }

    private fun pasteToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
        binding.etToken.setText(text)
    }

    // ----- chuyen buoc -----

    private fun next() {
        when (step) {
            0 -> {
                if (name().isEmpty()) {
                    binding.etName.error = getString(R.string.need_name)
                    binding.etName.requestFocus()
                    return
                }
                if (url().isEmpty()) {
                    binding.etUrl.error = getString(R.string.need_url)
                    binding.etUrl.requestFocus()
                    return
                }
                go(1)
            }
            1 -> {
                go(2)
                runTest()
            }
            else -> save()
        }
    }

    private fun go(target: Int) {
        step = target
        binding.flipper.displayedChild = step

        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        val labels = listOf(binding.label1, binding.label2, binding.label3)
        dots.forEachIndexed { index, dot ->
            val (bg, fg) = when {
                index == step -> R.color.primary to R.color.header_ink
                index < step -> R.color.primary_soft to R.color.primary
                else -> R.color.card to R.color.muted
            }
            dot.backgroundTintList = ColorStateList.valueOf(Ui.color(this, bg))
            dot.setTextColor(Ui.color(this, fg))
            dot.text = if (index < step) "✓" else (index + 1).toString()
            labels[index].setTextColor(Ui.color(this, if (index == step) R.color.primary else R.color.muted))
        }

        val last = step == 2
        binding.btnBack.text = getString(if (step == 0) R.string.cancel else R.string.back)
        binding.btnBack.setIconResource(if (step == 0) R.drawable.ic_close else R.drawable.ic_arrow_back)
        binding.btnTest.visibility = if (last) View.VISIBLE else View.GONE
        binding.btnNext.text = getString(if (last) R.string.save else R.string.next)
        binding.btnNext.setIconResource(if (last) R.drawable.ic_check else R.drawable.ic_arrow_forward)

        if (last) fillSummary()
    }

    private fun fillSummary() {
        binding.tvSumName.text = name()
        binding.tvSumUrl.text = url()
        binding.tvSumToken.text = Ui.maskToken(token())
        binding.tvSumSenders.text = senders().ifBlank { getString(R.string.all_senders) }
        binding.tvSumKeywords.text = keywords().ifBlank { getString(R.string.all_keywords) }
    }

    // ----- gui thu -----

    private fun runTest() {
        if (testing) return
        testing = true
        showResult(R.color.screen, R.color.muted, R.drawable.ic_schedule, getString(R.string.testing), "")

        val url = url()
        val token = token()
        Thread {
            val result = Sender.test(url, token)
            runOnUiThread {
                testing = false
                if (result.http.ok) {
                    showResult(
                        R.color.ok_soft, R.color.ok, R.drawable.ic_check,
                        getString(R.string.test_ok),
                        getString(R.string.test_detail, result.http.code, result.http.body.take(80), result.ms),
                    )
                } else {
                    val detail = if (result.http.code > 0) {
                        getString(R.string.test_detail, result.http.code, result.http.body.take(80), result.ms)
                    } else {
                        getString(R.string.test_error, result.http.error, result.ms)
                    }
                    showResult(R.color.err_soft, R.color.err, R.drawable.ic_error, getString(R.string.test_fail), detail)
                }
            }
        }.start()
    }

    private fun showResult(bg: Int, fg: Int, icon: Int, title: String, detail: String) {
        binding.resultBox.backgroundTintList = ColorStateList.valueOf(Ui.color(this, bg))
        binding.ivResult.setImageResource(icon)
        binding.ivResult.imageTintList = ColorStateList.valueOf(Ui.color(this, fg))
        binding.tvResultTitle.setTextColor(Ui.color(this, fg))
        binding.tvResultTitle.text = title
        binding.tvResultDetail.setTextColor(Ui.color(this, fg))
        binding.tvResultDetail.text = detail
        binding.tvResultDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    // ----- luu -----

    private fun save() {
        Db.saveTarget(
            id = id,
            name = name(),
            url = url(),
            token = token(),
            senders = senders(),
            keywords = keywords(),
            enabled = enabled,
        )
        Ui.toast(this, getString(R.string.saved))
        finish()
    }

    private fun name() = binding.etName.text.toString().trim()
    private fun url() = binding.etUrl.text.toString().trim()
    private fun token() = binding.etToken.text.toString().trim()
    private fun senders() = binding.etSenders.text.toString().trim()
    private fun keywords() = binding.etKeywords.text.toString().trim()

    companion object {
        private const val EXTRA_ID = "target_id"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, TargetEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
