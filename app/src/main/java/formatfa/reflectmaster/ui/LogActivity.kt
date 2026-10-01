package formatfa.reflectmaster.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import formatfa.reflectmaster.R
import formatfa.reflectmaster.core.LogStore
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.util.Ui
import java.util.concurrent.Callable

/**
 * Log viewer.
 *
 * Shows lines pushed out of hooked processes through
 * [formatfa.reflectmaster.provider.ConfigProvider] into [LogStore]. The 1.x
 * module had no in-app log at all - the only way to see what it was doing was
 * `adb logcat` or the Xposed installer's log page.
 */
class LogActivity : AppCompatActivity() {

    companion object {
        private const val MAX_LINES = 800
    }

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private var content = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        Ui.toolbar(this, findViewById<MaterialToolbar>(R.id.toolbar), true)

        logView = findViewById(R.id.tv_log)
        scroll = findViewById(R.id.scroll)

        findViewById<MaterialButton>(R.id.btn_refresh).setOnClickListener { reload() }
        findViewById<MaterialButton>(R.id.btn_copy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("rm-log", content))
            Ui.toast(this, getString(R.string.copied))
        }
        findViewById<MaterialButton>(R.id.btn_share).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener {
            Ui.confirm(this, getString(R.string.log_clear), getString(R.string.confirm_clear_log)) {
                LogStore.clear(this)
                RmLog.clearMemory()
                reload()
                Ui.toast(this, getString(R.string.log_cleared))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val ctx = applicationContext
        val task = Callable<String> {
            val remote = LogStore.readLines(ctx, MAX_LINES)
            val memory = RmLog.snapshot()
            val sb = StringBuilder()
            if (remote.isNotEmpty()) {
                sb.append("---- 来自目标进程 ----\n")
                for (l in remote) sb.append(l).append('\n')
            }
            if (memory.isNotEmpty()) {
                sb.append("---- 本进程内存缓冲 ----\n")
                for (l in memory) sb.append(l).append('\n')
            }
            if (sb.isEmpty()) sb.append(getString(R.string.log_empty))
            sb.toString()
        }
        Ui.async(task) { text ->
            content = text
            logView.text = text
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun share() {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.title_log))
                putExtra(Intent.EXTRA_TEXT, content)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (t: Throwable) {
            Ui.toast(this, t.message ?: "")
        }
    }
}
