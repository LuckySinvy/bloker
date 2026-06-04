package com.addev.listaspam

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.addev.listaspam.util.SpamUtils
import com.addev.listaspam.util.migrateLegacyDefaultPreferences
import com.addev.listaspam.util.SyncUtils

class SettingsActivity : AppCompatActivity() {
    private val spamUtils = SpamUtils()
    private var syncProgressDialog: androidx.appcompat.app.AlertDialog? = null
    private var syncProgressTextView: TextView? = null
    private var syncProgressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLegacyDefaultPreferences(this)
        setContentView(R.layout.activity_settings)
        setupWindowInsets()

        val toolbar: MaterialToolbar = findViewById(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        updateSettingsContainer()
    }

    private fun showNumberInputDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.test_number))

        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_PHONE
        builder.setView(input)

        builder.setPositiveButton(getString(R.string.aceptar)) { dialog, _ ->
            val number = input.text.toString().trim()
            if (number.isNotEmpty()) {
                spamUtils.testSpamNumber(this, number) { result ->
                    runOnUiThread {
                        showTestResultDialog(result)
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.type_number), Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton(getString(R.string.cancelar)) { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    private fun showTestResultDialog(result: SpamUtils.TestResult) {
        val pathLabel = when (result.path) {
            SpamUtils.TestDecisionPath.BLOCKING_DISABLED -> getString(R.string.test_result_path_blocking_disabled)
            SpamUtils.TestDecisionPath.HIDDEN_NUMBER -> getString(R.string.test_result_path_hidden)
            SpamUtils.TestDecisionPath.WHITELIST -> getString(R.string.test_result_path_whitelist)
            SpamUtils.TestDecisionPath.PATTERN -> getString(R.string.test_result_path_pattern)
            SpamUtils.TestDecisionPath.BLOCKLIST -> getString(R.string.test_result_path_blocklist)
            SpamUtils.TestDecisionPath.CONTACT -> getString(R.string.test_result_path_contact)
            SpamUtils.TestDecisionPath.NON_CONTACT -> getString(R.string.test_result_path_non_contact)
            SpamUtils.TestDecisionPath.INTERNATIONAL -> getString(R.string.test_result_path_international)
            SpamUtils.TestDecisionPath.SELF_HOSTED_SPAM -> getString(R.string.test_result_path_self_hosted)
            SpamUtils.TestDecisionPath.TELLOWS_SPAM -> getString(R.string.test_result_path_tellows)
            SpamUtils.TestDecisionPath.NOT_SPAM -> getString(R.string.test_result_path_not_spam)
        }
        val statusLabel = if (result.isSpam) {
            getString(R.string.test_result_status_blocked)
        } else {
            getString(R.string.test_result_status_allowed)
        }
        val message = buildString {
            append(getString(R.string.test_result_label_status))
            append("：")
            append(statusLabel)
            append('\n')
            append(getString(R.string.test_result_label_path))
            append("：")
            append(pathLabel)
            append('\n')
            append(getString(R.string.test_result_label_number))
            append("：")
            append(result.number)
            append('\n')
            append(getString(R.string.test_result_label_normalized))
            append("：")
            append(result.normalizedNumber.ifBlank { getString(R.string.unknown_value) })
            append('\n')
            append(getString(R.string.test_result_label_contact))
            append("：")
            append(
                if (result.isInContacts) {
                    getString(R.string.test_result_contact_yes)
                } else {
                    getString(R.string.test_result_contact_no)
                }
            )
            result.matchedPattern?.let { pattern ->
                append('\n')
                append(getString(R.string.test_result_label_pattern))
                append("：")
                append(pattern)
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.test_result_title))
            .setMessage(message)
            .setPositiveButton(R.string.aceptar, null)
            .show()
    }

    /**
     * Updates the settings container by replacing its content with a new instance of SettingsFragment.
     * This function is typically called when the activity is created or when settings need to be refreshed,
     * for example, after importing new settings.
     */
    private fun updateSettingsContainer() {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    /**
     * A [PreferenceFragmentCompat] that displays the application's settings.
     * It loads preferences from an XML resource file.
     */
    class SettingsFragment : PreferenceFragmentCompat() {
        private val settingsScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyCategoryCardStyling()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            bindToolPreferences()
        }

        private fun bindToolPreferences() {
            findPreference<Preference>("pref_test_number")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.showNumberInputDialog()
                true
            }

            findPreference<Preference>("pref_sync_self_hosted_now")?.setOnPreferenceClickListener {
                val host = activity as? SettingsActivity ?: return@setOnPreferenceClickListener true
                host.syncSelfHostedNow()
                true
            }

            findPreference<Preference>("pref_pattern_list")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), PatternRulesActivity::class.java))
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.addOnScrollListener(settingsScrollListener)
            view.post { applyCategoryCardStyling() }
        }

        override fun onStart() {
            super.onStart()
            applyCategoryCardStyling()
        }

        override fun onDestroyView() {
            listView.removeOnScrollListener(settingsScrollListener)
            super.onDestroyView()
        }

        private fun applyCategoryCardStyling() {
            val recyclerView = listView
            val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
            val preferenceScreen = preferenceScreen ?: return

            val rowMap = mutableMapOf<Preference, Int>()
            for (index in 0 until adapter.itemCount) {
                val preference = adapter.getItem(index)
                if (preference != null) {
                    rowMap[preference] = index
                }
            }

            for (i in 0 until preferenceScreen.preferenceCount) {
                val category = preferenceScreen.getPreference(i) as? PreferenceCategory ?: continue
                val items = mutableListOf<Preference>()
                for (j in 0 until category.preferenceCount) {
                    items.add(category.getPreference(j))
                }
                if (items.isEmpty()) continue

                items.forEachIndexed { itemIndex, preference ->
                    val rowIndex = rowMap[preference] ?: return@forEachIndexed
                    val holder = recyclerView.findViewHolderForAdapterPosition(rowIndex) ?: return@forEachIndexed
                    val backgroundRes = when {
                        items.size == 1 -> R.drawable.preference_card_single
                        itemIndex == 0 -> R.drawable.preference_card_top
                        itemIndex == items.lastIndex -> R.drawable.preference_card_bottom
                        else -> R.drawable.preference_card_middle
                    }
                    holder.itemView.setBackgroundResource(backgroundRes)

                    val params = holder.itemView.layoutParams as? androidx.recyclerview.widget.RecyclerView.LayoutParams
                    params?.apply {
                        width = androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT
                        leftMargin = 0
                        rightMargin = 0
                        topMargin = 0
                        bottomMargin = if (itemIndex == items.lastIndex) 16 else 0
                    }
                    holder.itemView.layoutParams = params

                    val summaryView = holder.itemView.findViewById<View>(android.R.id.summary)
                    summaryView?.visibility =
                        if (preference.summary.isNullOrBlank()) View.GONE else View.VISIBLE

                    val divider = holder.itemView.findViewById<View>(R.id.preferenceDivider)
                    divider?.visibility = if (itemIndex == items.lastIndex) View.GONE else View.VISIBLE

                    val widgetFrame = holder.itemView.findViewById<View>(android.R.id.widget_frame)
                    if (widgetFrame != null) {
                        widgetFrame.visibility = if (preference.isSelectable) View.VISIBLE else View.GONE
                    }
                }

                val categoryIndex = rowMap[category] ?: continue
                val categoryHolder = recyclerView.findViewHolderForAdapterPosition(categoryIndex)
                val categoryParams = categoryHolder?.itemView?.layoutParams as? androidx.recyclerview.widget.RecyclerView.LayoutParams
                categoryParams?.apply {
                    width = androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT
                    leftMargin = 0
                    rightMargin = 0
                    topMargin = if (i == 0) 0 else 12
                    bottomMargin = 8
                }
                categoryHolder?.itemView?.layoutParams = categoryParams
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun syncSelfHostedNow() {
        showSyncProgressDialog()
        Thread {
            val result = SyncUtils.syncSelfHostedData(this) { progress ->
                runOnUiThread {
                    updateSyncProgress(progress)
                }
            }
            runOnUiThread {
                dismissSyncProgressDialog()
                result.onSuccess { sync ->
                    showSyncResultDialog(sync)
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: getString(R.string.self_hosted_sync_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun showSyncProgressDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val messageView = TextView(this).apply {
            text = getString(R.string.self_hosted_sync_progress_starting)
            setPadding(0, 24, 0, 0)
        }
        container.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            messageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        syncProgressTextView = messageView
        syncProgressBar = progressBar
        syncProgressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.self_hosted_sync_progress_title))
            .setView(container)
            .setCancelable(false)
            .show()
    }

    private fun updateSyncProgress(progress: com.addev.listaspam.util.SyncProgress) {
        when (progress.stage) {
            "starting" -> {
                syncProgressBar?.isIndeterminate = true
                syncProgressTextView?.text = getString(R.string.self_hosted_sync_progress_starting)
            }
            "downloading" -> {
                val total = progress.totalBytes
                if (total != null && total > 0L && progress.percent != null) {
                    syncProgressBar?.isIndeterminate = false
                    syncProgressBar?.progress = progress.percent
                    syncProgressTextView?.text = getString(
                        R.string.self_hosted_sync_progress_downloading,
                        progress.percent,
                        progress.downloadedBytes / 1024,
                        total / 1024,
                        formatRemainingTime(progress.estimatedRemainingMs)
                    )
                } else {
                    syncProgressBar?.isIndeterminate = true
                    syncProgressTextView?.text = getString(
                        R.string.self_hosted_sync_progress_downloading_unknown,
                        progress.downloadedBytes / 1024
                    )
                }
            }
            "applying" -> {
                syncProgressBar?.isIndeterminate = true
                syncProgressTextView?.text = getString(R.string.self_hosted_sync_progress_applying)
            }
        }
    }

    private fun dismissSyncProgressDialog() {
        syncProgressDialog?.dismiss()
        syncProgressDialog = null
        syncProgressTextView = null
        syncProgressBar = null
    }

    private fun showSyncResultDialog(sync: com.addev.listaspam.util.SyncResult) {
        val previousCursorLabel = sync.previousCursor?.toString() ?: "-"
        val currentCursorLabel = sync.currentCursor?.toString() ?: "-"
        val debugLine = getString(
            R.string.self_hosted_sync_debug,
            sync.syncMode,
            sync.durationMs,
            sync.payloadBytes,
            previousCursorLabel,
            currentCursorLabel
        )
        val message = if (sync.changed) {
            getString(
                R.string.self_hosted_sync_success,
                sync.spamCount,
                sync.whitelistCount,
                sync.patternCount
            ) + "\n" + getString(
                R.string.self_hosted_sync_changes,
                sync.spamAdded,
                sync.spamRemoved,
                sync.whitelistAdded,
                sync.whitelistRemoved,
                sync.patternAdded,
                sync.patternRemoved
            ) + "\n" + debugLine
        } else {
            getString(R.string.self_hosted_sync_unchanged) + "\n" + debugLine
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.self_hosted_sync_result_title))
            .setMessage(message)
            .setPositiveButton(R.string.aceptar, null)
            .show()
    }

    private fun formatRemainingTime(estimatedRemainingMs: Long?): String {
        if (estimatedRemainingMs == null || estimatedRemainingMs <= 0L) {
            return "<1s"
        }
        val seconds = (estimatedRemainingMs / 1000).coerceAtLeast(1)
        return if (seconds < 60) {
            "${seconds}s"
        } else {
            "${seconds / 60}m${seconds % 60}s"
        }
    }

    override fun onDestroy() {
        dismissSyncProgressDialog()
        super.onDestroy()
    }
}
