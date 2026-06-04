package com.addev.listaspam.adapter

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.addev.listaspam.R
import com.addev.listaspam.util.CallLogEntry
import com.addev.listaspam.util.ReportDialogManager
import com.addev.listaspam.util.addNumberToWhitelist
import com.addev.listaspam.util.removeSpamNumber
import com.addev.listaspam.util.removeWhitelistNumber
import com.addev.listaspam.util.saveSpamNumber
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.MaterialShapeDrawable

class CallLogAdapter(
    private val context: Context,
    var callLogs: List<CallLogEntry>
) : RecyclerView.Adapter<CallLogAdapter.CallLogViewHolder>() {

    interface OnItemChangedListener {
        fun onItemChanged(number: String)
    }

    private val formatter: DateTimeFormatter = getSystemLocalizedFormatter()

    private var onItemChangedListener: OnItemChangedListener? = null

    private fun getSystemLocalizedFormatter(): DateTimeFormatter {
        val locale = Locale.getDefault()
        return DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallLogViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_call_log, parent, false)
        return CallLogViewHolder(view)
    }

    override fun onBindViewHolder(holder: CallLogViewHolder, position: Int) {
        val callLog = callLogs[position]
        holder.bind(
            callLog,
            callLog.isBlocked,
            callLog.isWhitelisted
        )
    }

    override fun getItemCount(): Int = callLogs.size

    inner class CallLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val numberTextView: TextView = itemView.findViewById(R.id.numberTextView)
        private val dateTextView: TextView = itemView.findViewById(R.id.dateTextView)
        private val noteTextView: TextView = itemView.findViewById(R.id.noteTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.durationTextView)
        private val actionTextView: TextView = itemView.findViewById(R.id.actionTextView)
        private val overflowMenuButton = itemView.findViewById<ImageButton>(R.id.overflowMenuButton)

        fun bind(callLog: CallLogEntry, isBlocked: Boolean, isWhitelisted: Boolean = false) {
            val number = callLog.number
            val contactName = callLog.contactName
            val textToShow = if (isBlocked) {
                val displayText = when {
                    contactName != null -> contactName
                    number.isNotBlank() -> number
                    else -> context.getString(R.string.unknown_value)
                }
                context.getString(R.string.blocked_text_format, displayText)
            } else if (isWhitelisted) {
                context.getString(R.string.whitelisted_text_format, contactName ?: number)
            } else {
                contactName ?: number
            }
            numberTextView.text = textToShow
            dateTextView.text = formatter.format(
                Instant.ofEpochMilli(callLog.date.time).atZone(ZoneId.systemDefault())
            )
            durationTextView.text = context.getString(R.string.duration_label, callLog.duration)

            val action = when (callLog.type) {
                CallLog.Calls.INCOMING_TYPE -> context.getString(R.string.call_incoming)
                CallLog.Calls.MISSED_TYPE -> context.getString(R.string.call_missed)
                CallLog.Calls.REJECTED_TYPE -> context.getString(R.string.call_rejected)
                CallLog.Calls.BLOCKED_TYPE -> context.getString(R.string.call_blocked)
                else -> context.getString(R.string.call_unknown)
            }

            actionTextView.text = action
            val note = callLog.note
            noteTextView.text = note
            noteTextView.visibility = if (note.isBlank()) View.GONE else View.VISIBLE

            if (callLog.type == CallLog.Calls.BLOCKED_TYPE) {
                styleActionBadge(
                    textColor = ContextCompat.getColor(context, R.color.dangerColor),
                    backgroundColor = applyAlpha(ContextCompat.getColor(context, R.color.dangerColor), 0.14f)
                )
            } else {
                styleActionBadge(
                    textColor = ContextCompat.getColor(context, R.color.infoColor),
                    backgroundColor = applyAlpha(ContextCompat.getColor(context, R.color.infoColor), 0.12f)
                )
            }

            when {
                isBlocked -> numberTextView.setTextColor(
                    ContextCompat.getColor(
                        context,
                        android.R.color.holo_red_light
                    )
                )

                isWhitelisted -> numberTextView.setTextColor(
                    ContextCompat.getColor(
                        context,
                        android.R.color.holo_blue_dark
                    )
                )

                else -> {
                    numberTextView.setTextColor(ContextCompat.getColor(context, R.color.textColor))
                }
            }

            if (number.isBlank()) {
                overflowMenuButton.visibility = View.GONE
                return
            }

            overflowMenuButton.visibility = View.VISIBLE
            overflowMenuButton.setOnClickListener {
                runCatching {
                    val popupMenu = PopupMenu(
                        itemView.context,
                        overflowMenuButton,
                        Gravity.END
                    )
                    popupMenu.inflate(R.menu.item_actions)

                    setDynamicTitles(popupMenu, isBlocked, isWhitelisted)

                    popupMenu.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            R.id.open_report_alert -> {
                                openReportAlert(number)
                                true
                            }

                            R.id.add_to_contacts_action -> {
                                addToContacts(number)
                                true
                            }

                            R.id.whitelist_action -> {
                                if (isWhitelisted) {
                                    removeWhitelistNumber(context, number)
                                } else {
                                    addNumberToWhitelist(context, number)
                                }
                                onItemChangedListener?.onItemChanged(number)
                                true
                            }

                            R.id.block_action -> {
                                if (isBlocked) {
                                    removeSpamNumber(context, number)
                                } else {
                                    saveSpamNumber(context, number)
                                }
                                onItemChangedListener?.onItemChanged(number)
                                true
                            }

                            else -> false
                        }
                    }

                    popupMenu.show()
                }.onFailure {
                    Toast.makeText(context, R.string.context_menu_failed, Toast.LENGTH_SHORT).show()
                }
            }

            // Copy number to clipboard
            itemView.setOnLongClickListener {
                clipboardAction(number)
                true
            }
        }

        private fun setDynamicTitles(
            popupMenu: PopupMenu,
            isBlocked: Boolean,
            isWhitelisted: Boolean
        ) {
            val blockMenuItem = popupMenu.menu.findItem(R.id.block_action)
            val whitelistMenuItem = popupMenu.menu.findItem(R.id.whitelist_action)
            if (isBlocked) {
                blockMenuItem.setTitle(R.string.unblock)
            } else {
                blockMenuItem.setTitle(R.string.block)
            }

            if (isWhitelisted) {
                whitelistMenuItem.setTitle(R.string.remove_from_whitelist)
            } else {
                whitelistMenuItem.setTitle(R.string.add_to_whitelist)
            }
        }

        private fun styleActionBadge(textColor: Int, backgroundColor: Int) {
            actionTextView.setTextColor(textColor)
            val badgeShape = MaterialShapeDrawable(
                ShapeAppearanceModel.builder()
                    .setAllCornerSizes(999f)
                    .build()
            ).apply {
                fillColor = ColorStateList.valueOf(backgroundColor)
            }
            actionTextView.background = badgeShape
        }
    }

    private fun clipboardAction(number: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.phone_clipboard_label), number)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            context,
            context.getString(R.string.number_copied_to_clipboard),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun addToContacts(number: String) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
        }
        context.startActivity(intent)
    }

    fun setOnItemChangedListener(listener: OnItemChangedListener) {
        this.onItemChangedListener = listener
    }

    private fun openReportAlert(number: String) {
        val reportDialogManager = ReportDialogManager(context)
        reportDialogManager.show(number)
    }

    private fun openExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, R.string.no_supported_app_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (255 * alphaFraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
