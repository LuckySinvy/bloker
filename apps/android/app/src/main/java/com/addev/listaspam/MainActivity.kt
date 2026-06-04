package com.addev.listaspam

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.addev.listaspam.util.PermissionUtils
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.addev.listaspam.adapter.CallLogAdapter
import com.addev.listaspam.util.NumberStore
import com.addev.listaspam.util.SpamUtils
import com.addev.listaspam.util.getCallLogsWithContacts
import com.addev.listaspam.util.hydrateCallLogStatuses
import com.addev.listaspam.util.getSyncedPatternCount
import com.addev.listaspam.util.getSyncedSpamCount
import com.addev.listaspam.util.CountryLanguageUtils
import com.addev.listaspam.util.SelfHostedSyncWorker
import com.addev.listaspam.util.shouldAutoSyncSelfHosted
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), CallLogAdapter.OnItemChangedListener {

    private lateinit var intentLauncher: ActivityResultLauncher<Intent>
    private var permissionDeniedDialog: AlertDialog? = null
    private var callLogAdapter: CallLogAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var blockedCountTextView: TextView? = null
    private var cloudSpamCountTextView: TextView? = null
    private var whitelistCountTextView: TextView? = null
    private var callCountTextView: TextView? = null
    private var blockedCardView: View? = null
    private var cloudSpamCardView: View? = null
    private var whitelistCardView: View? = null
    private var callCardView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var refreshGeneration: Int = 0
    private var runtimePermissionRequestInFlight = false
    private var roleRequestInFlight = false
    private var hasRequestedRuntimePermissionsThisSession = false
    private var hasRequestedRoleThisSession = false

    private val spamUtils = SpamUtils()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupWindowInsets()
        setupIntentLauncher()

        val toolbar: MaterialToolbar = findViewById(R.id.topToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""
        toolbar.title = ""

        blockedCountTextView = findViewById(R.id.blockedCountTextView)
        cloudSpamCountTextView = findViewById(R.id.cloudSpamCountTextView)
        whitelistCountTextView = findViewById(R.id.whitelistCountTextView)
        callCountTextView = findViewById(R.id.callCountTextView)
        blockedCardView = findViewById(R.id.blockedCardView)
        cloudSpamCardView = findViewById(R.id.cloudSpamCardView)
        whitelistCardView = findViewById(R.id.whitelistCardView)
        callCardView = findViewById(R.id.callCardView)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView?.layoutManager = LinearLayoutManager(this)

        blockedCardView?.setOnClickListener {
            startActivity(Intent(this, NumberListActivity::class.java).putExtra(NumberListActivity.EXTRA_LIST_TYPE, NumberListActivity.TYPE_BLOCKED))
        }
        cloudSpamCardView?.setOnClickListener {
            startActivity(Intent(this, NumberListActivity::class.java).putExtra(NumberListActivity.EXTRA_LIST_TYPE, NumberListActivity.TYPE_SPAM_LIBRARY))
        }
        whitelistCardView?.setOnClickListener {
            startActivity(Intent(this, NumberListActivity::class.java).putExtra(NumberListActivity.EXTRA_LIST_TYPE, NumberListActivity.TYPE_WHITELIST))
        }
        callCardView?.setOnClickListener {
            startActivity(Intent(this, PatternRulesActivity::class.java))
        }

        CountryLanguageUtils.setTellowsCountry(this)
        if (shouldAutoSyncSelfHosted(this)) {
            SelfHostedSyncWorker.schedule(this)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showNumberInputDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.test_number))

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_PHONE
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

    override fun onItemChanged(number: String) {
        val positions = mutableListOf<Int>()
        callLogAdapter?.callLogs?.forEachIndexed { index, callLog ->
            if (callLog.number == number) {
                positions.add(index)
            }
        }
        refreshCallLogs(positions)
    }

    private fun init() {
        checkPermissionsAndRequest()

        requestCallScreeningRole()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            refreshCallLogs()
        }
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

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.test_result_title))
            .setMessage(message)
            .setPositiveButton(R.string.aceptar, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        init()
    }

    private fun refreshCallLogs(positions: List<Int> = listOf()) {
        val generation = ++refreshGeneration
        thread(name = "call-log-refresh") {
            val blockedCount = NumberStore.getEffectiveBlockedCount(this)
            val whitelistCount = NumberStore.getEffectiveWhitelistCount(this)
            val callLogs = hydrateCallLogStatuses(this, getCallLogsWithContacts(this))

            mainHandler.post {
                if (isFinishing || isDestroyed || generation != refreshGeneration) {
                    return@post
                }

                blockedCountTextView?.text = blockedCount.toString()
                cloudSpamCountTextView?.text = getSyncedSpamCount(this).toString()
                whitelistCountTextView?.text = whitelistCount.toString()
                callCountTextView?.text = getSyncedPatternCount(this).toString()

                if (callLogAdapter == null) {
                    callLogAdapter = CallLogAdapter(this, callLogs)
                    recyclerView?.adapter = callLogAdapter
                    callLogAdapter?.setOnItemChangedListener(this)
                } else {
                    callLogAdapter?.callLogs = callLogs
                    callLogAdapter?.notifyDataSetChanged()
                }

                if (positions.isNotEmpty()) {
                    positions.forEach { position ->
                        callLogAdapter?.notifyItemChanged(position)
                    }
                }
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupIntentLauncher() {
        intentLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                roleRequestInFlight = false
                if (it.resultCode == RESULT_OK) {
                    showToast(this, getString(R.string.success_call_screening_role))
                } else {
                    showToast(this, getString(R.string.failed_call_screening_role))
                }
            }
    }

    private fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
    }

    private fun checkPermissionsAndRequest() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGrantedPermissions.isEmpty()) {
            runtimePermissionRequestInFlight = false
            return
        }

        val missingPermissions = notGrantedPermissions.filter {
            !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        val deniedPermissions = notGrantedPermissions.filter {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }

        if (
            missingPermissions.isNotEmpty() &&
            !runtimePermissionRequestInFlight &&
            !hasRequestedRuntimePermissionsThisSession
        ) {
            runtimePermissionRequestInFlight = true
            hasRequestedRuntimePermissionsThisSession = true
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PermissionUtils.REQUEST_CODE_PERMISSIONS
            )
        }

        if (deniedPermissions.isNotEmpty()) {
            permissionDeniedDialog = PermissionUtils.showPermissionDialog(
                this,
                deniedPermissions,
                permissionDeniedDialog
            )
            permissionDeniedDialog?.setOnDismissListener {
                permissionDeniedDialog = null
            }
            permissionDeniedDialog?.show()
        }
    }

    /**
     * Requests the call screening role.
     */
    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            roleRequestInFlight = false
            return
        }
        if (!roleRequestInFlight && !hasRequestedRoleThisSession) {
            roleRequestInFlight = true
            hasRequestedRoleThisSession = true
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            intentLauncher.launch(intent)
            showToast(this, getString(R.string.call_screening_role_prompt), Toast.LENGTH_LONG)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.REQUEST_CODE_PERMISSIONS) {
            runtimePermissionRequestInFlight = false
            if (
                grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            ) {
                refreshCallLogs()
            }
        }
    }
}
