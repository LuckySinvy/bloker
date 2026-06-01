package com.addev.listaspam

import android.content.Context
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
import com.addev.listaspam.util.removeSpamNumber
import com.addev.listaspam.util.removeWhitelistNumber
import com.addev.listaspam.util.saveSpamNumber

class NumberListAdapter(
    private val context: Context,
    private val listType: String
) : RecyclerView.Adapter<NumberListAdapter.NumberViewHolder>() {
    interface OnNumberChangedListener {
        fun onNumberChanged()
    }

    private val items = mutableListOf<String>()
    private var listener: OnNumberChangedListener? = null

    fun submit(numbers: List<String>) {
        items.clear()
        items.addAll(numbers)
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
        private val overflowMenuButton: ImageButton = itemView.findViewById(R.id.overflowMenuButton)

        fun bind(number: String) {
            titleTextView.text = number
            subtitleTextView.text = if (listType == NumberListActivity.TYPE_WHITELIST) {
                context.getString(R.string.whitelisted_text_format, number)
            } else {
                context.getString(R.string.blocked_text_format, number)
            }

            overflowMenuButton.setOnClickListener {
                val popupMenu = PopupMenu(context, overflowMenuButton, Gravity.END)
                popupMenu.menu.add(0, 1, 0, if (listType == NumberListActivity.TYPE_WHITELIST) context.getString(R.string.remove_from_whitelist) else context.getString(R.string.unblock))
                popupMenu.menu.add(0, 2, 1, if (listType == NumberListActivity.TYPE_WHITELIST) context.getString(R.string.block) else context.getString(R.string.add_to_whitelist))
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

                        else -> false
                    }
                }
                popupMenu.show()
            }
        }

        private fun confirmPrimaryAction(number: String) {
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
    }
}
