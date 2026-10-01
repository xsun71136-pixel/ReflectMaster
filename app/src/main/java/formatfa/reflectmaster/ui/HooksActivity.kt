package formatfa.reflectmaster.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import formatfa.reflectmaster.R
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.core.HookRule
import formatfa.reflectmaster.util.Ui

/**
 * User defined method hooks.
 *
 * The 1.x build stored these in the same preference blob as scripts and packed
 * class name, method name and parameter types into the *script name* field with
 * a `bf`/`af` prefix. Rules now have their own structured schema and their own
 * screen, and can be enabled or disabled without deleting them.
 */
class HooksActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
    }

    private lateinit var adapter: HookAdapter
    private lateinit var emptyView: TextView

    private val items = ArrayList<HookRule>()

    /** Set while a holder is being bound so the switch listener stays quiet. */
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hooks)
        Ui.toolbar(this, findViewById<MaterialToolbar>(R.id.toolbar), true)

        emptyView = findViewById(R.id.tv_empty)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HookAdapter()
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.btn_add).setOnClickListener {
            startActivity(Intent(this, HookEditActivity::class.java).putExtra(EXTRA_INDEX, -1))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(ConfigStore.load(this).hooks)
        adapter.notifyDataSetChanged()
        Ui.setVisible(emptyView, items.isEmpty())
    }

    private fun persist() {
        ConfigStore.saveHooks(this, ArrayList(items))
    }

    private fun setEnabled(index: Int, enabled: Boolean) {
        if (index !in items.indices) return
        val old = items[index]
        items[index] = old.copy(enabled = enabled)
        persist()
        Ui.toast(this, getString(if (enabled) R.string.enabled else R.string.disabled))
    }

    private fun deleteAt(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        persist()
        reload()
        Ui.toast(this, getString(R.string.deleted))
    }

    private fun duplicateAt(index: Int) {
        if (index !in items.indices) return
        val src = items[index]
        items.add(index + 1, src.copy(name = src.name + " (2)"))
        persist()
        reload()
    }

    private fun showMenu(index: Int) {
        if (index !in items.indices) return
        val labels = listOf(
            getString(R.string.title_hook_edit),
            getString(R.string.duplicate),
            getString(R.string.copy),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(items[index].name)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, HookEditActivity::class.java).putExtra(EXTRA_INDEX, index)
                    )
                    1 -> duplicateAt(index)
                    2 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("hook", items[index].code)
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

    // ---------------------------------------------------------------- adapter

    private inner class HookAdapter : RecyclerView.Adapter<HookHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HookHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hook, parent, false)
            return HookHolder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: HookHolder, position: Int) {
            holder.bind(position)
        }
    }

    private inner class HookHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.tv_name)
        private val signature: TextView = itemView.findViewById(R.id.tv_signature)
        private val swEnabled: SwitchMaterial = itemView.findViewById(R.id.sw_enabled)

        fun bind(index: Int) {
            val rule = items[index]
            binding = true
            name.text = rule.name
            signature.text = (if (rule.after) "after  " else "before ") + rule.signature
            swEnabled.isChecked = rule.enabled
            binding = false

            swEnabled.setOnCheckedChangeListener { _, checked ->
                if (binding) return@setOnCheckedChangeListener
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) setEnabled(pos, checked)
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    startActivity(
                        Intent(this@HooksActivity, HookEditActivity::class.java)
                            .putExtra(EXTRA_INDEX, pos)
                    )
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showMenu(pos)
                true
            }
        }
    }
}
