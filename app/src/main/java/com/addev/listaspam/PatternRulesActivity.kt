package com.addev.listaspam

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
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
    private var openedPosition: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattern_rules)

        val toolbar: MaterialToolbar = findViewById(R.id.patternToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val addButton: Button = findViewById(R.id.btnOpenBatchAddPage)
        recyclerView = findViewById(R.id.patternRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = PatternRuleAdapter(
            onRowTapped = { position -> toggleRow(position) },
            onEditNote = { pattern -> showEditNoteDialog(pattern) },
            onDelete = { pattern -> deletePattern(pattern) }
        )
        recyclerView.adapter = adapter
        ItemTouchHelper(PatternSwipeCallback(adapter)).attachToRecyclerView(recyclerView)

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
        openedPosition = openedPosition?.takeIf { it < items.size }
        adapter.setOpenedPosition(openedPosition)
    }

    private fun toggleRow(position: Int) {
        val next = if (openedPosition == position) null else position
        val previous = openedPosition
        openedPosition = next
        adapter.setOpenedPosition(next, previous)
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
    private val onRowTapped: (Int) -> Unit,
    private val onEditNote: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<PatternRuleViewHolder>() {
    private val items = mutableListOf<PatternRuleItem>()
    private var openedPosition: Int? = null

    fun submit(newItems: List<PatternRuleItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setOpenedPosition(position: Int?, previousPosition: Int? = openedPosition) {
        val previous = previousPosition
        openedPosition = position
        previous?.takeIf { it in items.indices }?.let { notifyItemChanged(it) }
        position?.takeIf { it in items.indices && it != previous }?.let { notifyItemChanged(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatternRuleViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pattern_rule, parent, false)
        return PatternRuleViewHolder(view, onRowTapped, onEditNote, onDelete)
    }

    override fun onBindViewHolder(holder: PatternRuleViewHolder, position: Int) {
        holder.bind(items[position], position == openedPosition)
    }

    override fun getItemCount(): Int = items.size
}

private class PatternRuleViewHolder(
    itemView: android.view.View,
    private val onRowTapped: (Int) -> Unit,
    private val onEditNote: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val swipeLayout: SwipeRevealLayout = itemView as SwipeRevealLayout
    private val patternValueTextView: TextView = itemView.findViewById(R.id.patternValueTextView)
    private val patternNoteTextView: TextView = itemView.findViewById(R.id.patternNoteTextView)
    private val editButton: View = itemView.findViewById(R.id.actionEditPattern)
    private val deleteButton: View = itemView.findViewById(R.id.actionDeletePattern)
    private var boundPattern: String = ""

    init {
        itemView.setOnClickListener {
            if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onRowTapped(bindingAdapterPosition)
            }
        }
        editButton.setOnClickListener { onEditNote(boundPattern) }
        deleteButton.setOnClickListener { onDelete(boundPattern) }
    }

    fun bind(item: PatternRuleItem, isOpened: Boolean) {
        boundPattern = item.pattern
        patternValueTextView.text = item.pattern
        patternNoteTextView.text = item.note
        patternNoteTextView.visibility = if (item.note.isBlank()) View.GONE else View.VISIBLE
        swipeLayout.setOpened(isOpened, animate = false)
    }
}

private class PatternSwipeCallback(
    private val adapter: PatternRuleAdapter
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.32f

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 4

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        adapter.setOpenedPosition(if (direction == ItemTouchHelper.LEFT) position else null)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        ViewCompat.setTranslationX(viewHolder.itemView, 0f)
    }

    override fun onChildDraw(
        c: android.graphics.Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val swipeLayout = viewHolder.itemView as? SwipeRevealLayout ?: return
        val clampedDx = dX.coerceIn(-swipeLayout.actionWidth.toFloat(), 0f)
        swipeLayout.dragTo(clampedDx)
    }
}

class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private lateinit var actionContainer: View
    private lateinit var foregroundView: View
    val actionWidth: Int
        get() = actionContainer.measuredWidth

    override fun onFinishInflate() {
        super.onFinishInflate()
        require(childCount == 2) { "SwipeRevealLayout requires exactly 2 children" }
        actionContainer = getChildAt(0)
        foregroundView = getChildAt(1)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun dragTo(offset: Float) {
        foregroundView.translationX = offset
    }

    fun setOpened(opened: Boolean, animate: Boolean) {
        val target = if (opened) -actionWidth.toFloat() else 0f
        if (animate) {
            foregroundView.animate().translationX(target).setDuration(180L).start()
        } else {
            foregroundView.translationX = target
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildWithMargins(foregroundView, widthMeasureSpec, 0, heightMeasureSpec, 0)

        val foregroundWidth = foregroundView.measuredWidth
        val foregroundHeight = foregroundView.measuredHeight
        val actionWidthSpec = getChildMeasureSpec(widthMeasureSpec, 0, actionContainer.layoutParams.width)
        val actionHeightSpec = MeasureSpec.makeMeasureSpec(foregroundHeight, MeasureSpec.EXACTLY)
        actionContainer.measure(actionWidthSpec, actionHeightSpec)

        val width = foregroundWidth
        val height = foregroundHeight
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val foregroundHeight = foregroundView.measuredHeight

        val actionLeft = width - actionContainer.measuredWidth
        actionContainer.layout(actionLeft, 0, width, foregroundHeight)

        foregroundView.layout(0, 0, width, foregroundHeight)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean {
        return p is MarginLayoutParams
    }
}
