package formatfa.reflectmaster.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import formatfa.reflectmaster.R
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.core.LogStore
import formatfa.reflectmaster.core.ModuleBridge
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.overlay.BubbleView
import formatfa.reflectmaster.overlay.OUi
import formatfa.reflectmaster.util.Ui
import java.io.File
import java.util.concurrent.Callable

/**
 * Dashboard: module status, targets, console behaviour, scripts, hooks, logs.
 *
 * The 1.x UI was a single ListView of installed apps with everything else hidden
 * behind an options menu and a `WindowDialog` built from a hard-coded 800x800
 * pixel layout. Settings now live on the front page, grouped, with the module
 * status - the one thing users actually need - at the top.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var hintText: TextView
    private lateinit var targetsSummary: TextView
    private lateinit var scriptSummary: TextView
    private lateinit var hookSummary: TextView
    private lateinit var inboxSummary: TextView
    private lateinit var versionText: TextView

    private lateinit var swConsole: SwitchMaterial
    private lateinit var rgTrigger: RadioGroup
    private lateinit var sbSize: SeekBar
    private lateinit var tvSize: TextView
    private lateinit var sbAlpha: SeekBar
    private lateinit var tvAlpha: TextView
    private lateinit var swDark: SwitchMaterial
    private lateinit var swRemember: SwitchMaterial
    private lateinit var swOverlay: SwitchMaterial

    /** Guards against saving while the widgets are being populated. */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Ui.toolbar(this, findViewById<MaterialToolbar>(R.id.toolbar), false)

        statusText = findViewById(R.id.tv_module_status)
        detailText = findViewById(R.id.tv_module_detail)
        hintText = findViewById(R.id.tv_module_hint)
        targetsSummary = findViewById(R.id.tv_targets_summary)
        scriptSummary = findViewById(R.id.tv_script_summary)
        hookSummary = findViewById(R.id.tv_hook_summary)
        inboxSummary = findViewById(R.id.tv_inbox_summary)
        versionText = findViewById(R.id.tv_version)

        swConsole = findViewById(R.id.sw_console)
        rgTrigger = findViewById(R.id.rg_trigger)
        sbSize = findViewById(R.id.sb_size)
        tvSize = findViewById(R.id.tv_size_value)
        sbAlpha = findViewById(R.id.sb_alpha)
        tvAlpha = findViewById(R.id.tv_alpha_value)
        swDark = findViewById(R.id.sw_dark)
        swRemember = findViewById(R.id.sw_remember)
        swOverlay = findViewById(R.id.sw_overlay)

        findViewById<MaterialButton>(R.id.btn_manager).setOnClickListener {
            if (!Ui.openFrameworkManager(this)) {
                Ui.toast(this, getString(R.string.manager_not_found))
            }
        }
        findViewById<MaterialButton>(R.id.btn_targets).setOnClickListener {
            startActivity(Intent(this, TargetsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_scripts).setOnClickListener {
            startActivity(Intent(this, ScriptsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_hooks).setOnClickListener {
            startActivity(Intent(this, HooksActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_log_clear).setOnClickListener {
            Ui.confirm(this, getString(R.string.log_clear), getString(R.string.confirm_clear_log)) {
                LogStore.clear(this)
                RmLog.clearMemory()
                refreshInbox()
                Ui.toast(this, getString(R.string.log_cleared))
            }
        }
        findViewById<MaterialButton>(R.id.btn_inbox).setOnClickListener { showInbox() }
        findViewById<MaterialButton>(R.id.btn_preview).setOnClickListener { showPreview() }

        findViewById<MaterialButton>(R.id.btn_usage).setOnClickListener {
            Ui.alert(this, getString(R.string.about_usage), getString(R.string.about_usage_text))
        }
        findViewById<MaterialButton>(R.id.btn_script_api).setOnClickListener {
            Ui.alert(this, getString(R.string.about_script_api), getString(R.string.about_script_api_text))
        }
        findViewById<MaterialButton>(R.id.btn_opensource).setOnClickListener {
            Ui.alert(this, getString(R.string.about_opensource), getString(R.string.about_opensource_text))
        }
        findViewById<MaterialButton>(R.id.btn_disclaimer).setOnClickListener {
            Ui.alert(this, getString(R.string.about_disclaimer), getString(R.string.about_disclaimer_text))
        }
        findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener {
            Ui.confirm(this, getString(R.string.reset_all), getString(R.string.confirm_reset)) {
                ConfigStore.prefs(this).edit().clear().apply()
                LogStore.clear(this)
                LogStore.inboxClear(this)
                RmLog.clearMemory()
                Ui.toast(this, getString(R.string.reset_done))
                refresh()
            }
        }

        versionText.text = getString(
            R.string.about_version, Ui.versionName(this), Ui.versionCode(this)
        )

        bindConsoleListeners()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- refresh

    private fun refresh() {
        val cfg = ConfigStore.load(this)

        val active = try {
            ModuleBridge.isModuleActive()
        } catch (t: Throwable) {
            false
        }
        statusText.setText(if (active) R.string.module_active else R.string.module_inactive)
        statusText.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this, if (active) R.color.rm_ok else R.color.rm_err
            )
        )
        hintText.setText(if (active) R.string.module_active_hint else R.string.module_inactive_hint)
        if (active) {
            detailText.visibility = View.VISIBLE
            detailText.text = getString(
                R.string.module_detail,
                ModuleBridge.frameworkName(),
                ModuleBridge.frameworkVersion(),
                ModuleBridge.installedHookCount(),
                ModuleBridge.loadedConfigVersion()
            )
        } else {
            detailText.visibility = View.GONE
        }

        targetsSummary.text = if (cfg.targets.isEmpty()) {
            getString(R.string.target_summary_none)
        } else {
            getString(R.string.target_summary, cfg.targets.size)
        }
        scriptSummary.text = getString(R.string.script_summary, cfg.scripts.size)

        var enabled = 0
        for (h in cfg.hooks) if (h.enabled) enabled++
        hookSummary.text = getString(R.string.hook_summary, cfg.hooks.size, enabled)

        bindConsole(cfg)
        refreshInbox()
    }

    private fun refreshInbox() {
        Ui.async(Callable { LogStore.inboxList(this).size }) { count ->
            inboxSummary.text = if (count == 0) {
                getString(R.string.inbox_empty)
            } else {
                getString(R.string.inbox) + ": " + count
            }
        }
    }

    private fun bindConsole(cfg: formatfa.reflectmaster.core.RmConfig) {
        loading = true
        try {
            swConsole.isChecked = cfg.consoleEnabled
            when (cfg.trigger) {
                Config.TRIGGER_VOLUME_LONG -> rgTrigger.check(R.id.rb_volume)
                Config.TRIGGER_BUBBLE -> rgTrigger.check(R.id.rb_bubble)
                else -> rgTrigger.check(R.id.rb_none)
            }
            sbSize.progress = cfg.panelSizeDp - Config.MIN_PANEL_SIZE_DP
            sbAlpha.progress = cfg.panelAlphaPct - 30
            swDark.isChecked = cfg.darkPanel
            swRemember.isChecked = cfg.rememberPos
            swOverlay.isChecked = cfg.systemOverlay
            tvSize.text = cfg.panelSizeDp.toString() + " dp"
            tvAlpha.text = cfg.panelAlphaPct.toString() + "%"
        } finally {
            loading = false
        }
    }

    private fun bindConsoleListeners() {
        val save = {
            if (!loading) {
                ConfigStore.saveConsole(
                    this,
                    swConsole.isChecked,
                    triggerOf(rgTrigger.checkedRadioButtonId),
                    Config.MIN_PANEL_SIZE_DP + sbSize.progress,
                    30 + sbAlpha.progress,
                    swDark.isChecked,
                    swRemember.isChecked,
                    swOverlay.isChecked
                )
            }
        }

        swConsole.setOnCheckedChangeListener { _, _ -> save() }
        swDark.setOnCheckedChangeListener { _, _ -> save() }
        swRemember.setOnCheckedChangeListener { _, _ -> save() }
        swOverlay.setOnCheckedChangeListener { _, _ -> save() }
        rgTrigger.setOnCheckedChangeListener { _, _ -> save() }

        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSize.text = (Config.MIN_PANEL_SIZE_DP + progress).toString() + " dp"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                save()
            }
        })

        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvAlpha.text = (30 + progress).toString() + "%"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                save()
            }
        })
    }

    private fun triggerOf(id: Int): Int = when (id) {
        R.id.rb_volume -> Config.TRIGGER_VOLUME_LONG
        R.id.rb_bubble -> Config.TRIGGER_BUBBLE
        else -> Config.TRIGGER_NONE
    }

    // ---------------------------------------------------------------- dialogs

    private fun showPreview() {
        val dark = swDark.isChecked
        val pal = OUi.palette(dark)
        val size = Ui.dp(this, 46f)
        val container = FrameLayout(this)
        container.setBackgroundColor(if (dark) 0xFF20222E.toInt() else 0xFFEDEFF6.toInt())
        val bubble = BubbleView(this, pal)
        val lp = FrameLayout.LayoutParams(size, size)
        lp.gravity = android.view.Gravity.CENTER
        container.addView(bubble, lp)
        val pad = Ui.dp(this, 20f)
        container.setPadding(pad, pad * 2, pad, pad * 2)

        AlertDialog.Builder(this)
            .setTitle(R.string.console_preview)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showInbox() {
        Ui.async(Callable { LogStore.inboxList(this) }) { files ->
            if (files.isEmpty()) {
                Ui.alert(this, getString(R.string.inbox), getString(R.string.inbox_empty))
                return@async
            }
            val names = ArrayList<String>()
            for (f in files) names.add(f.name + "  (" + f.length() + " B)")
            AlertDialog.Builder(this)
                .setTitle(R.string.inbox)
                .setItems(names.toTypedArray()) { _, which -> exportInbox(files[which]) }
                .setNeutralButton(R.string.log_clear) { _, _ ->
                    LogStore.inboxClear(this)
                    refreshInbox()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /**
     * Copies an artefact out of the module's private storage.
     *
     * Uses `getExternalFilesDir` on purpose: on Android 11 that path needs no
     * permission, survives scoped storage, and is reachable from adb and from
     * rooted file managers - unlike the 1.x habit of writing to `/sdcard`.
     */
    private fun exportInbox(file: File) {
        Ui.async(Callable {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val out = File(dir, "rm_export")
                if (!out.exists()) out.mkdirs()
                val target = File(out, file.name)
                file.copyTo(target, overwrite = true)
                target.absolutePath
            } catch (t: Throwable) {
                null
            }
        }) { path ->
            if (path == null) {
                Ui.toast(this, getString(R.string.import_failed, "copy"))
            } else {
                Ui.alert(this, getString(R.string.export_done), path)
                refreshInbox()
            }
        }
    }
}
