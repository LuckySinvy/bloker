package com.addev.listaspam

import android.os.Bundle
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.addev.listaspam.util.getBlockedPatterns
import com.addev.listaspam.util.getPatternNotes
import com.addev.listaspam.util.setBlockedPatterns
import com.addev.listaspam.util.setPatternNote
import com.google.android.material.appbar.MaterialToolbar

class PatternRulesActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PatternRuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattern_rules)

        val toolbar: MaterialToolbar = findViewById(R.id.patternToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val addButton: Button = findViewById(R.id.btnOpenBatchAddPage)
        recyclerView = findViewById(R.id.patternRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

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
        val items = getBlockedPatterns(this)
            .sorted()
            .map { PatternRuleItem(it, notes[it].orEmpty()) }
        adapter.submit(items)
    }

    private fun deletePattern(pattern: String) {
        val next = getBlockedPatterns(this).toMutableSet()
        next.remove(pattern)
        setBlockedPatterns(this, next)
        setPatternNote(this, pattern, "")
        Toast.makeText(this, getString(R.string.pattern_delete_success), Toast.LENGTH_SHORT).show()
        reloadPatterns()
    }

    private fun showEditNoteDialog(pattern: String) {
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

        val toolbar: MaterialToolbar = findViewById(R.id.patternBatchToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        preview = findViewById(R.id.patternInputPreview)
        bindKeyboard()

        findViewById<Button>(R.id.btnCancelBatchAdd).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSaveBatchAdd).setOnClickListener { commitInput() }
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
    val note: String
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
    private val editButton: Button = itemView.findViewById(R.id.btnEditNote)
    private val deleteButton: Button = itemView.findViewById(R.id.btnDeletePattern)

    fun bind(item: PatternRuleItem) {
        patternValueTextView.text = item.pattern
        patternNoteTextView.text =
            if (item.note.isBlank()) itemView.context.getString(R.string.pattern_note_empty) else item.note
        editButton.setOnClickListener { onEditNote(item.pattern) }
        deleteButton.setOnClickListener { onDelete(item.pattern) }
    }
}
