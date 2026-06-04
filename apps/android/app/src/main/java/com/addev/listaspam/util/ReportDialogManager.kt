package com.addev.listaspam.util

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Toast
import com.addev.listaspam.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportDialogManager(private val context: Context) {

    fun show(number: String) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_report, null)
        val dialog = createDialog(dialogView)
        setupDialogView(dialogView, number)
        setupDialogButtons(dialog, dialogView, number)
        dialog.show()
    }

    private fun createDialog(dialogView: View): AlertDialog {
        return AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
    }

    private fun setupDialogView(dialogView: View, number: String) {
        val messageEditText = dialogView.findViewById<EditText>(R.id.messageEditText)
        val spamRadio = dialogView.findViewById<RadioButton>(R.id.radioSpam)
        val noSpamRadio = dialogView.findViewById<RadioButton>(R.id.radioNoSpam)

        messageEditText.hint = context.getString(R.string.report_hint)
        spamRadio.text = context.getString(R.string.report_spam)
        noSpamRadio.text = context.getString(R.string.report_not_spam)
    }

    private fun setupDialogButtons(dialog: AlertDialog, dialogView: View, number: String) {
        val submitButton = dialogView.findViewById<Button>(R.id.btnSubmitReport)
        val cancelButton = dialogView.findViewById<Button>(R.id.btnCancelReport)

        cancelButton.setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            if (!validateInput(dialogView)) return@setOnClickListener

            val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
            progressBar.visibility = View.VISIBLE
            submitButton.isEnabled = false
            cancelButton.isEnabled = false

            submitReport(dialog, dialogView, number, progressBar, submitButton, cancelButton)
        }
    }

    private fun validateInput(dialogView: View): Boolean {
        val spamRadio = dialogView.findViewById<RadioButton>(R.id.radioSpam)
        val noSpamRadio = dialogView.findViewById<RadioButton>(R.id.radioNoSpam)

        if (!spamRadio.isChecked && !noSpamRadio.isChecked) {
            Toast.makeText(
                context,
                context.getString(R.string.report_radio_validation),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        return true
    }

    private fun submitReport(
        dialog: AlertDialog,
        dialogView: View,
        number: String,
        progressBar: ProgressBar,
        submitButton: Button,
        cancelButton: Button
    ) {
        val messageEditText = dialogView.findViewById<EditText>(R.id.messageEditText)
        val spamRadio = dialogView.findViewById<RadioButton>(R.id.radioSpam)

        val message = messageEditText.text.toString().trim()
        val isSpam = spamRadio.isChecked

        CoroutineScope(Dispatchers.IO).launch {
            val reportedTo = mutableListOf<String>()

            val baseUrl = getSpamBackendBaseUrl(context)
            if (ApiUtils.reportToSelfHostedBackend(baseUrl, number, message, isSpam)) {
                reportedTo.add(context.getString(R.string.reported_to_self_hosted))
            }

            val reportMessage = buildReportMessage(reportedTo)

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                submitButton.isEnabled = true
                cancelButton.isEnabled = true
                Toast.makeText(context, reportMessage, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    private fun buildReportMessage(reportedTo: List<String>): String {
        return if (reportedTo.isNotEmpty()) {
            context.getString(R.string.report_submitted)
        } else {
            context.getString(R.string.report_failure)
        }
    }

}
