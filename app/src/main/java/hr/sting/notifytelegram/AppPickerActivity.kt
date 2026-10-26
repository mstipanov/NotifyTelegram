package hr.sting.notifytelegram

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lists launchable apps with a checkbox each. The checked set is exactly what
 * [NotificationForwarder] will forward. A package that has no launcher icon can
 * still be added by name.
 */
class AppPickerActivity : Activity() {

    private data class Entry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    private val all = ArrayList<Entry>()
    private val shown = ArrayList<Entry>()
    private lateinit var selected: MutableSet<String>

    private lateinit var adapter: AppAdapter
    private lateinit var listView: ListView
    private lateinit var search: EditText
    private lateinit var pickerStatus: TextView
    private lateinit var emptyView: TextView

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-picker").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        applyContentInsets(findViewById(R.id.root))
        actionBar?.setDisplayHomeAsUpEnabled(true)

        selected = Prefs.packages(this).toMutableSet()

        listView = findViewById(R.id.appList)
        search = findViewById(R.id.searchApps)
        pickerStatus = findViewById(R.id.pickerStatus)
        emptyView = findViewById(R.id.emptyView)

        adapter = AppAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = shown.getOrNull(position) ?: return@setOnItemClickListener
            toggle(entry.packageName)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter()
        })

        findViewById<Button>(R.id.btnAddPackage).setOnClickListener { addByPackageName() }
        findViewById<Button>(R.id.btnClearApps).setOnClickListener {
            selected.clear()
            Prefs.setPackages(this, selected)
            adapter.notifyDataSetChanged()
            updateStatus()
        }

        updateStatus()
        loadApps()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---- loading -----------------------------------------------------------

    private fun loadApps() {
        loader.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(launcher, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .map { info ->
                    Entry(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    )
                }
                .toMutableList()

            // Keep already-selected non-launcher packages visible/checkable.
            selected.filter { pkg -> found.none { it.packageName == pkg } }.forEach { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                found.add(
                    Entry(
                        packageName = pkg,
                        label = info?.loadLabel(pm)?.toString() ?: pkg,
                        icon = info?.let { runCatching { it.loadIcon(pm) }.getOrNull() },
                    )
                )
            }

            found.sortBy { it.label.lowercase(Locale.getDefault()) }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                all.clear()
                all.addAll(found)
                filter()
            }
        }
    }

    private fun filter() {
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        shown.clear()
        shown.addAll(
            all.filter { entry ->
                query.isEmpty() ||
                    entry.label.lowercase(Locale.getDefault()).contains(query) ||
                    entry.packageName.lowercase(Locale.getDefault()).contains(query)
            }
        )
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- selection ---------------------------------------------------------

    private fun toggle(packageName: String) {
        if (!selected.add(packageName)) selected.remove(packageName)
        Prefs.setPackages(this, selected)
        adapter.notifyDataSetChanged()
        updateStatus()
    }

    private fun updateStatus() {
        val count = selected.size
        pickerStatus.text = when (count) {
            0 -> "No apps selected"
            1 -> "1 app selected"
            else -> "$count apps selected"
        }
    }

    private fun addByPackageName() {
        val input = EditText(this).apply {
            hint = "com.example.app"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Add package by name")
            .setMessage("Use this for an app that has no launcher icon.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isEmpty()) return@setPositiveButton
                val pm = packageManager
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                if (all.none { it.packageName == pkg }) {
                    all.add(
                        Entry(
                            packageName = pkg,
                            label = info?.loadLabel(pm)?.toString() ?: pkg,
                            icon = info?.let { runCatching { it.loadIcon(pm) }.getOrNull() },
                        )
                    )
                    all.sortBy { it.label.lowercase(Locale.getDefault()) }
                }
                selected.add(pkg)
                Prefs.setPackages(this, selected)
                filter()
                updateStatus()
                if (info == null) {
                    Toast.makeText(
                        this,
                        "Package $pkg is not visible to the app; it will be matched if the listener sees it.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- adapter -----------------------------------------------------------

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Entry = shown[position]
        override fun getItemId(position: Int): Long = shown[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.row_app, parent, false)
            val entry = shown[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
                entry.icon ?: getDrawable(R.drawable.ic_launcher)
            )
            view.findViewById<TextView>(R.id.appLabel).text = entry.label
            view.findViewById<TextView>(R.id.appPackage).text = entry.packageName
            view.findViewById<CheckBox>(R.id.appCheck).isChecked = entry.packageName in selected
            return view
        }
    }
}
