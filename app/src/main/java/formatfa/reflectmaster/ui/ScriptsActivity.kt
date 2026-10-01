package formatfa.reflectmaster.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import formatfa.reflectmaster.R
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.core.ScriptItem
import formatfa.reflectmaster.util.Ui
import org.json.JSONArray
import java.util.concurrent.Callable

/**
 * FakeScript snippet list.
 *
 * The 1.x `ScriptManager` extended `ListActivity`, mixed "test scripts" and
 * "custom hooks" behind a magic `mode` int and encoded hook targets into the
 * script *name* field. Scripts and hooks are separate entities with separate
 * screens now.
 */
class ScriptsActivity : AppCompatActivity() {

    private lateinit var adapter: ScriptAdapter
    private lateinit var emptyView: TextView

    private val items = ArrayList<ScriptItem>()

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importFrom(uri)
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) exportTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scripts)
        Ui.toolbar(this, findViewById<MaterialToolbar>(R.id.toolbar), true)

        emptyView = findViewById(R.id.tv_empty)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ScriptAdapter()
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.btn_add).setOnClickListener {
            startActivity(editIntent(-1))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_import)
            .setOnClickListener {
                try {
                    openDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
                } catch (t: Throwable) {
                    Ui.toast(this, getString(R.string.import_failed, t.message ?: ""))
                }
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_export)
            .setOnClickListener {
                try {
                    createDocument.launch("reflectmaster_scripts.json")
                } catch (t: Throwable) {
                    Ui.toast(this, getString(R.string.import_failed, t.message ?: ""))
                }
            }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(ConfigStore.load(this).scripts)
        adapter.notifyDataSetChanged()
        Ui.setVisible(emptyView, items.isEmpty())
    }

    private fun editIntent(index: Int): Intent =
        Intent(this, ScriptEditActivity::class.java)
            .putExtra(ScriptEditActivity.EXTRA_INDEX, index)

    private fun persist() {
        ConfigStore.saveScripts(this, ArrayList(items))
    }

    fun deleteAt(index: Int) {
        if (index < 0 || index >= items.size) return
        items.removeAt(index)
        persist()
        reload()
        Ui.toast(this, getString(R.string.deleted))
    }

    fun duplicateAt(index: Int) {
        if (index < 0 || index >= items.size) return
        val src = items[index]
        items.add(index + 1, ScriptItem(src.name + " (2)", src.code))
        persist()
        reload()
    }

    // ------------------------------------------------------------- import/export

    private fun importFrom(uri: Uri) {
        val task = Callable<ArrayList<ScriptItem>?> {
            var parsed: ArrayList<ScriptItem>? = null
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                val text = bytes?.toString(Charsets.UTF_8)
                if (!text.isNullOrEmpty()) {
                    val arr = JSONArray(text)
                    val out = ArrayList<ScriptItem>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name", "")
                        if (name.isEmpty()) continue
                        out.add(ScriptItem(name, o.optString("code", "")))
                    }
                    parsed = out
                }
            } catch (t: Throwable) {
                parsed = null
            }
            parsed
        }
        Ui.async(task) { result ->
            if (result == null) {
                Ui.toast(this, getString(R.string.import_failed, "json"))
            } else {
                items.addAll(result)
                persist()
                reload()
                Ui.toast(this, getString(R.string.import_done, result.size))
            }
        }
    }

    private fun exportTo(uri: Uri) {
        val payload = Config.writeScripts(ArrayList(items))
        val task = Callable<Boolean> {
            var ok = false
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }
                ok = true
            } catch (t: Throwable) {
                ok = false
            }
            ok
        }
        Ui.async(task) { ok ->
            Ui.toast(
                this,
                if (ok) getString(R.string.export_done) else getString(R.string.import_failed, "write")
            )
        }
    }

    // ---------------------------------------------------------------- adapter

    private inner class ScriptAdapter : RecyclerView.Adapter<ScriptHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_script, parent, false)
            return ScriptHolder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ScriptHolder, position: Int) {
            holder.bind(position)
        }
    }

    private inner class ScriptHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.tv_name)
        private val meta: TextView = itemView.findViewById(R.id.tv_meta)

        fun bind(index: Int) {
            val item = items[index]
            name.text = item.name
            val lines = item.code.split('\n').size
            val preview = item.code.replace('\n', ' ').take(90)
            meta.text = getString(R.string.lines, lines) + " · " + preview
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) startActivity(editIntent(pos))
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showMenu(pos)
                true
            }
        }
    }

    private fun showMenu(index: Int) {
        val labels = listOf(
            getString(R.string.title_script_edit),
            getString(R.string.duplicate),
            getString(R.string.copy),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(items[index].name)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(editIntent(index))
                    1 -> duplicateAt(index)
                    2 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("script", items[index].code)
                        )
                        Ui.toast(this, getString(R.string.copied))
                    }
                    3 -> Ui.confirm(
                        this, getString(R.string.delete),
                        getString(R.string.delete_confirm, items[index].name)
                    ) { deleteAt(index) }
                }
            }
            .show()
    }
}
