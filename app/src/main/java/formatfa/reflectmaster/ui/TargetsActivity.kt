package formatfa.reflectmaster.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import formatfa.reflectmaster.R
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.util.Ui
import java.util.concurrent.Callable

/** One row of the installed-app list. */
class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncher: Boolean
) {
    var selected: Boolean = false
    var icon: Drawable? = null
}

/**
 * Target picker.
 *
 * Replaces the 1.x `MainActivity` app list, which used `ListView.setFilterText`
 * with a `Filter` that was never implemented correctly, loaded every icon on the
 * UI thread and - worst of all - wrote the selection with
 * `putString(KEY, info.packageName)` (a single package, immediately overwritten
 * by the real comma-joined `save()` two lines later).
 *
 * Here the selection is a `Set<String>` persisted as a JSON array, filtering is
 * explicit, and icon loading happens off the main thread.
 */
class TargetsActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var countText: TextView

    private var all = ArrayList<AppEntry>()
    private var visible = ArrayList<AppEntry>()
    private var selected = LinkedHashSet<String>()

    private var filter = ""
    private var showSystem = false
    private var onlySelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_targets)
        Ui.toolbar(this, findViewById<MaterialToolbar>(R.id.toolbar), true)

        progress = findViewById(R.id.progress)
        emptyView = findViewById(R.id.tv_empty)
        countText = findViewById(R.id.tv_count)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter()
        list.adapter = adapter

        val cfg = ConfigStore.load(this)
        for (p in cfg.targets) selected.add(p)
        showSystem = ConfigStore.uiFlag(this, Config.KEY_SHOW_SYSTEM_APPS, false)
        onlySelected = ConfigStore.uiFlag(this, Config.KEY_ONLY_SELECTED, false)

        val search = findViewById<EditText>(R.id.et_search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })

        val swSystem = findViewById<SwitchMaterial>(R.id.sw_system)
        swSystem.isChecked = showSystem
        swSystem.setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            ConfigStore.saveUiFlag(this, Config.KEY_SHOW_SYSTEM_APPS, checked)
            applyFilter()
        }

        val swSelected = findViewById<SwitchMaterial>(R.id.sw_selected)
        swSelected.isChecked = onlySelected
        swSelected.setOnCheckedChangeListener { _, checked ->
            onlySelected = checked
            ConfigStore.saveUiFlag(this, Config.KEY_ONLY_SELECTED, checked)
            applyFilter()
        }

        findViewById<MaterialButton>(R.id.btn_select_all).setOnClickListener {
            for (e in visible) selected.add(e.packageName)
            save()
            applyFilter()
        }
        findViewById<MaterialButton>(R.id.btn_deselect_all).setOnClickListener {
            for (e in visible) selected.remove(e.packageName)
            save()
            applyFilter()
        }

        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        val pm = packageManager
        val self = packageName
        Ui.async(Callable {
            val packages: List<PackageInfo> = try {
                pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            } catch (t: Throwable) {
                emptyList()
            }
            val out = ArrayList<AppEntry>(packages.size)
            for (info in packages) {
                val pkg = info.packageName ?: continue
                if (pkg == self) continue
                val app = info.applicationInfo ?: continue
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = try {
                    app.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    pkg
                }
                val launcher = try {
                    pm.getLaunchIntentForPackage(pkg) != null
                } catch (t: Throwable) {
                    false
                }
                val entry = AppEntry(pkg, label, isSystem, launcher)
                entry.selected = selected.contains(pkg)
                entry.icon = try {
                    app.loadIcon(pm)
                } catch (t: Throwable) {
                    null
                }
                out.add(entry)
            }
            out.sortWith { a, b ->
                if (a.selected != b.selected) {
                    if (a.selected) -1 else 1
                } else {
                    a.label.compareTo(b.label, ignoreCase = true)
                }
            }
            out
        }) { result ->
            all.clear()
            all.addAll(result)
            progress.visibility = View.GONE
            applyFilter()
        }
    }

    private fun applyFilter() {
        visible.clear()
        for (e in all) {
            e.selected = selected.contains(e.packageName)
            if (!showSystem && e.isSystem) continue
            if (onlySelected && !e.selected) continue
            if (filter.isNotEmpty()) {
                val hit = e.label.contains(filter, ignoreCase = true) ||
                    e.packageName.contains(filter, ignoreCase = true)
                if (!hit) continue
            }
            visible.add(e)
        }
        adapter.notifyDataSetChanged()
        Ui.setVisible(emptyView, visible.isEmpty() && all.isNotEmpty())
        countText.text = getString(R.string.installed_count, visible.size)
    }

    private fun save() {
        ConfigStore.saveTargets(this, ArrayList(selected))
    }

    private fun toggle(entry: AppEntry) {
        if (selected.contains(entry.packageName)) {
            selected.remove(entry.packageName)
            Ui.toast(this, getString(R.string.target_saved))
        } else {
            selected.add(entry.packageName)
            Ui.toast(this, entry.label + " · " + getString(R.string.target_saved))
        }
        entry.selected = selected.contains(entry.packageName)
        save()
    }

    private fun showActions(entry: AppEntry) {
        val items = listOf(
            getString(R.string.open_app),
            getString(R.string.app_info),
            if (entry.selected) getString(R.string.deselect_all) else getString(R.string.select_all)
        )
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val intent = packageManager.getLaunchIntentForPackage(entry.packageName)
                        if (intent == null) {
                            Ui.toast(this, getString(R.string.open_app) + " ✕")
                        } else {
                            try {
                                startActivity(intent)
                            } catch (t: Throwable) {
                                Ui.toast(this, t.message ?: "")
                            }
                        }
                    }
                    1 -> {
                        try {
                            startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", entry.packageName, null)
                                )
                            )
                        } catch (t: Throwable) {
                            Ui.toast(this, t.message ?: "")
                        }
                    }
                    2 -> {
                        toggle(entry)
                        applyFilter()
                    }
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- adapter

    private inner class AppAdapter : RecyclerView.Adapter<AppHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return AppHolder(view)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val entry = visible[position]
            holder.bind(entry)
        }
    }

    private inner class AppHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val name: TextView = itemView.findViewById(R.id.tv_name)
        private val pkg: TextView = itemView.findViewById(R.id.tv_pkg)
        private val check: CheckBox = itemView.findViewById(R.id.cb_select)

        fun bind(entry: AppEntry) {
            name.text = entry.label
            pkg.text = entry.packageName
            check.isChecked = entry.selected
            icon.setImageDrawable(entry.icon)
            name.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    itemView.context,
                    if (entry.selected) R.color.rm_primary else R.color.rm_text_primary
                )
            )
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val target = visible[pos]
                toggle(target)
                if (onlySelected) {
                    applyFilter()
                } else {
                    adapter.notifyItemChanged(pos)
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showActions(visible[pos])
                true
            }
        }
    }
}
