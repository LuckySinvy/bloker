package com.addev.listaspam

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.addev.listaspam.util.addNumberToWhitelist
import com.addev.listaspam.util.getBlockedNumberNotes
import com.addev.listaspam.util.getWhitelistNumberNotes
import com.addev.listaspam.util.removeSpamNumber
import com.addev.listaspam.util.removeWhitelistNumber
import com.addev.listaspam.util.saveSpamNumber
import com.addev.listaspam.util.setBlockedNumberNote
import com.addev.listaspam.util.setWhitelistNumberNote

class NumberListAdapter(
    private val context: Context,
    private val listType: String
) : RecyclerView.Adapter<NumberListAdapter.NumberViewHolder>() {
    interface OnNumberChangedListener {
        fun onNumberChanged()
    }

    private val items = mutableListOf<NumberListItem>()
    private var listener: OnNumberChangedListener? = null
    private var searchKeyword: String = ""

    fun submit(numbers: List<NumberListItem>) {
        items.clear()
        items.addAll(numbers)
        notifyDataSetChanged()
    }

    fun append(numbers: List<NumberListItem>) {
        if (numbers.isEmpty()) {
            return
        }
        val start = items.size
        items.addAll(numbers)
        notifyItemRangeInserted(start, numbers.size)
    }

    fun setSearchKeyword(keyword: String) {
        if (searchKeyword == keyword) {
            return
        }
        searchKeyword = keyword
        notifyDataSetChanged()
    }

    fun setOnNumberChangedListener(listener: OnNumberChangedListener) {
        this.listener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NumberViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_number_list, parent, false)
        return NumberViewHolder(view)
    }

    override fun onBindViewHolder(holder: NumberViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class NumberViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        private val subtitleTextView: TextView = itemView.findViewById(R.id.subtitleTextView)
        private val sourceBadgeTextView: TextView = itemView.findViewById(R.id.sourceBadgeTextView)
        private val overflowMenuButton: ImageButton = itemView.findViewById(R.id.overflowMenuButton)

        fun bind(item: NumberListItem) {
            val number = item.number
            titleTextView.text = highlightText(number)
            subtitleTextView.text = highlightText(item.note)
            subtitleTextView.visibility = if (item.note.isBlank()) View.GONE else View.VISIBLE
            val isSynced = item.source == NumberSource.SYNCED
            if (listType == NumberListActivity.TYPE_WHITELIST) {
                sourceBadgeTextView.visibility = View.GONE
            } else {
                sourceBadgeTextView.visibility = View.VISIBLE
                sourceBadgeTextView.text = context.getString(
                    if (isSynced) R.string.source_badge_synced else R.string.source_badge_local
                )
            }

            overflowMenuButton.setOnClickListener {
                val popupMenu = PopupMenu(context, overflowMenuButton, Gravity.END)
                if (listType != NumberListActivity.TYPE_SPAM_LIBRARY) {
                    popupMenu.menu.add(0, 1, 0, if (listType == NumberListActivity.TYPE_WHITELIST) context.getString(R.string.remove_from_whitelist) else context.getString(R.string.unblock))
                }
                if (listType != NumberListActivity.TYPE_SPAM_LIBRARY) {
                    popupMenu.menu.add(0, 2, 1, if (listType == NumberListActivity.TYPE_WHITELIST) context.getString(R.string.block) else context.getString(R.string.add_to_whitelist))
                }
                if (!isSynced) {
                    popupMenu.menu.add(0, 3, 2, context.getString(R.string.pattern_edit_note))
                }
                if (popupMenu.menu.size() == 0) {
                    popupMenu.menu.add(0, 9, 0, context.getString(R.string.synced_entry_readonly_message))
                }
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> {
                            confirmPrimaryAction(number)
                            true
                        }

                        2 -> {
                            confirmSecondaryAction(number)
                            true
                        }

                        3 -> {
                            editNote(number)
                            true
                        }

                        9 -> true

                        else -> false
                    }
                }
                popupMenu.show()
            }
        }

        private fun confirmPrimaryAction(number: String) {
            val item = items.getOrNull(bindingAdapterPosition)
            if (item?.source == NumberSource.SYNCED) {
                AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.synced_entry_readonly_message))
                    .setPositiveButton(R.string.aceptar, null)
                    .show()
                return
            }
            AlertDialog.Builder(context)
                .setMessage(number)
                .setPositiveButton(R.string.aceptar) { _, _ ->
                    if (listType == NumberListActivity.TYPE_WHITELIST) {
                        removeWhitelistNumber(context, number)
                    } else {
                        removeSpamNumber(context, number)
                    }
                    listener?.onNumberChanged()
                }
                .setNegativeButton(R.string.cancelar, null)
                .show()
        }

        private fun confirmSecondaryAction(number: String) {
            AlertDialog.Builder(context)
                .setMessage(number)
                .setPositiveButton(R.string.aceptar) { _, _ ->
                    if (listType == NumberListActivity.TYPE_WHITELIST) {
                        saveSpamNumber(context, number)
                    } else {
                        addNumberToWhitelist(context, number)
                    }
                    listener?.onNumberChanged()
                }
                .setNegativeButton(R.string.cancelar, null)
                .show()
        }

        private fun editNote(number: String) {
            val input = EditText(context).apply {
                setText(
                    if (listType == NumberListActivity.TYPE_WHITELIST) {
                        getWhitelistNumberNotes(context)[number].orEmpty()
                    } else {
                        getBlockedNumberNotes(context)[number].orEmpty()
                    }
                )
                hint = context.getString(R.string.pattern_note_label)
            }
            AlertDialog.Builder(context)
                .setTitle(number)
                .setView(input)
                .setPositiveButton(R.string.pattern_save_done) { _, _ ->
                    if (listType == NumberListActivity.TYPE_WHITELIST) {
                        setWhitelistNumberNote(context, number, input.text.toString().trim())
                    } else {
                        setBlockedNumberNote(context, number, input.text.toString().trim())
                    }
                    listener?.onNumberChanged()
                }
                .setNegativeButton(R.string.cancelar, null)
                .show()
        }

        private fun highlightText(text: String): CharSequence {
            if (searchKeyword.isBlank() || text.isBlank()) {
                return text
            }
            val index = text.indexOf(searchKeyword)
            if (index == -1) {
                return text
            }

            return SpannableString(text).apply {
                setSpan(
                    ForegroundColorSpan(context.getColor(R.color.primaryColorDark)),
                    index,
                    index + searchKeyword.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
