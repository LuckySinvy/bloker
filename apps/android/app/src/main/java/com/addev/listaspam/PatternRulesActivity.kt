package com.addev.listaspam

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.addev.listaspam.util.getEffectiveBlockedPatterns
import com.addev.listaspam.util.getBlockedPatterns
import com.addev.listaspam.util.getSyncedBlockedPatterns
import com.addev.listaspam.util.getPatternNotes
import com.addev.listaspam.util.setBlockedPatterns
import com.addev.listaspam.util.setPatternNote
import com.google.android.material.appbar.MaterialToolbar

class PatternRulesActivity : AppCompatActivity() {
    companion object {
        private const val FILTER_ALL = "all"
        private const val FILTER_LOCAL = "local"
        private const val FILTER_SYNCED = "synced"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PatternRuleAdapter
    private var sourceFilter: String = FILTER_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattern_rules)
        applySystemBarInsets(R.id.patternRulesRoot)

        val toolbar: MaterialToolbar = findViewById(R.id.patternToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val addButton: Button = findViewById(R.id.btnOpenBatchAddPage)
        recyclerView = findViewById(R.id.patternRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        findViewById<RadioGroup>(R.id.patternSourceFilterGroup).setOnCheckedChangeListener { _, checkedId ->
            sourceFilter = when (checkedId) {
                R.id.filterLocalPatterns -> FILTER_LOCAL
                R.id.filterSyncedPatterns -> FILTER_SYNCED
                else -> FILTER_ALL
            }
            reloadPatterns()
        }

        adapter = PatternRuleAdapter(
            onEditNote = { pattern -> showEditNoteDialog(pattern) },
            onDelete = { pattern -> deletePattern(pattern) }
        )
        recyclerView.adapter = adapter

        addButton.setOnClickListener {
            startActivity(android.content.Intent(this, PatternBatchAddActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reloadPatterns()
    }

    private fun reloadPatterns() {
        val notes = getPatternNotes(this)
        val syncedPatterns = getSyncedBlockedPatterns(this)
        val items = getEffectiveBlockedPatterns(this)
            .filter { pattern ->
                when (sourceFilter) {
                    FILTER_LOCAL -> !syncedPatterns.contains(pattern)
                    FILTER_SYNCED -> syncedPatterns.contains(pattern)
                    else -> true
                }
            }
            .sorted()
            .map { PatternRuleItem(it, notes[it].orEmpty(), syncedPatterns.contains(it)) }
        adapter.submit(items)
    }

    private fun deletePattern(pattern: String) {
        if (getSyncedBlockedPatterns(this).contains(pattern)) {
            Toast.makeText(this, getString(R.string.synced_entry_readonly_message), Toast.LENGTH_SHORT).show()
            reloadPatterns()
            return
        }
        val next = getBlockedPatterns(this).toMutableSet()
        next.remove(pattern)
        setBlockedPatterns(this, next)
        setPatternNote(this, pattern, "")
        Toast.makeText(this, getString(R.string.pattern_delete_success), Toast.LENGTH_SHORT).show()
        reloadPatterns()
    }

    private fun showEditNoteDialog(pattern: String) {
        if (getSyncedBlockedPatterns(this).contains(pattern)) {
            Toast.makeText(this, getString(R.string.synced_entry_readonly_message), Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            setText(getPatternNotes(this@PatternRulesActivity)[pattern].orEmpty())
            hint = getString(R.string.pattern_note_label)
        }

        AlertDialog.Builder(this)
            .setTitle(pattern)
            .setView(input)
            .setPositiveButton(R.string.pattern_save_done) { dialog, _ ->
                setPatternNote(this, pattern, input.text.toString().trim())
                Toast.makeText(this, getString(R.string.pattern_note_saved), Toast.LENGTH_SHORT).show()
                reloadPatterns()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun validatePatternInput(input: String): Boolean {
        if (TextUtils.isEmpty(input)) return true
        val patterns = input.split("\n")
        for (pattern in patterns) {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length < 2 || trimmed.length > 20) return false
            if (!trimmed.matches(Regex("^[0-9+*]+$"))) return false
            if (trimmed.contains("**") || trimmed.contains("++")) return false
        }
        return true
    }

}

class PatternBatchAddActivity : AppCompatActivity() {
    private lateinit var preview: TextView
    private val buffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattern_batch_add)
        applySystemBarInsets(R.id.patternBatchRoot)

        val toolbar: MaterialToolbar = findViewById(R.id.patternBatchToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        preview = findViewById(R.id.patternInputPreview)
        bindKeyboard()
    }

    private fun bindKeyboard() {
        val keyMap = mapOf(
            R.id.key1 to "1",
            R.id.key2 to "2",
            R.id.key3 to "3",
            R.id.key4 to "4",
            R.id.key5 to "5",
            R.id.key6 to "6",
            R.id.key7 to "7",
            R.id.key8 to "8",
            R.id.key9 to "9",
            R.id.keyPlus to "+",
            R.id.key0 to "0",
            R.id.keyStar to "*"
        )
        keyMap.forEach { (id, value) ->
            findViewById<Button>(id).setOnClickListener { appendText(value) }
        }
        findViewById<Button>(R.id.keyNewline).setOnClickListener { appendText("\n") }
        findViewById<Button>(R.id.keyDelete).setOnClickListener { deleteChar() }
        findViewById<Button>(R.id.keyDone).setOnClickListener { commitInput() }
    }

    private fun appendText(text: String) {
        buffer.append(text)
        preview.text = buffer.toString().ifEmpty { " " }
    }

    private fun deleteChar() {
        if (buffer.isNotEmpty()) {
            buffer.deleteCharAt(buffer.length - 1)
            preview.text = buffer.toString().ifEmpty { " " }
        }
    }

    private fun commitInput() {
        val input = buffer.toString().trim()
        if (!validatePatternInput(input)) {
            Toast.makeText(this, getString(R.string.pref_pattern_list_error), Toast.LENGTH_LONG).show()
            return
        }
        if (input.isBlank()) {
            Toast.makeText(this, getString(R.string.pattern_no_input), Toast.LENGTH_SHORT).show()
            return
        }
        val merged = getBlockedPatterns(this).toMutableSet()
        input.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { merged.add(it) }
        setBlockedPatterns(this, merged)
        Toast.makeText(this, getString(R.string.pattern_add_success), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun validatePatternInput(input: String): Boolean {
        if (TextUtils.isEmpty(input)) return true
        val patterns = input.split("\n")
        for (pattern in patterns) {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length < 2 || trimmed.length > 20) return false
            if (!trimmed.matches(Regex("^[0-9+*]+$"))) return false
            if (trimmed.contains("**") || trimmed.contains("++")) return false
        }
        return true
    }
}

data class PatternRuleItem(
    val pattern: String,
    val note: String,
    val synced: Boolean
)

private class PatternRuleAdapter(
    private val onEditNote: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<PatternRuleViewHolder>() {
    private val items = mutableListOf<PatternRuleItem>()

    fun submit(newItems: List<PatternRuleItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatternRuleViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pattern_rule, parent, false)
        return PatternRuleViewHolder(view, onEditNote, onDelete)
    }

    override fun onBindViewHolder(holder: PatternRuleViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

private class PatternRuleViewHolder(
    itemView: android.view.View,
    private val onEditNote: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val patternValueTextView: TextView = itemView.findViewById(R.id.patternValueTextView)
    private val patternNoteTextView: TextView = itemView.findViewById(R.id.patternNoteTextView)
    private val patternSourceBadgeTextView: TextView = itemView.findViewById(R.id.patternSourceBadgeTextView)
    private val overflowMenuButton: ImageButton = itemView.findViewById(R.id.patternOverflowMenuButton)
    private var boundPattern: String = ""
    private var isSynced: Boolean = false

    init {
        overflowMenuButton.setOnClickListener { showMenu() }
    }

    fun bind(item: PatternRuleItem) {
        boundPattern = item.pattern
        isSynced = item.synced
        patternValueTextView.text = item.pattern
        patternNoteTextView.text = if (item.synced) {
            if (item.note.isBlank()) itemView.context.getString(R.string.pattern_note_synced) else item.note
        } else {
            item.note
        }
        patternNoteTextView.visibility =
            if (item.synced || item.note.isNotBlank()) View.VISIBLE else View.GONE
        patternSourceBadgeTextView.visibility = View.VISIBLE
        patternSourceBadgeTextView.text = itemView.context.getString(
            if (item.synced) R.string.source_badge_synced else R.string.source_badge_local
        )
    }

    private fun showMenu() {
        val popupMenu = PopupMenu(itemView.context, overflowMenuButton)
        if (isSynced) {
            popupMenu.menu.add(0, 9, 0, itemView.context.getString(R.string.synced_entry_readonly_message))
        } else {
            popupMenu.menu.add(0, 1, 0, itemView.context.getString(R.string.pattern_edit_note))
            popupMenu.menu.add(0, 2, 1, itemView.context.getString(R.string.pattern_delete))
        }
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    onEditNote(boundPattern)
                    true
                }

                2 -> {
                    onDelete(boundPattern)
                    true
                }

                9 -> true
                else -> false
            }
        }
        popupMenu.show()
    }
}

private fun AppCompatActivity.applySystemBarInsets(rootId: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(rootId)) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
        insets
    }
}
