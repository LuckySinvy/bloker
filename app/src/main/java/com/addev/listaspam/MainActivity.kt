package com.addev.listaspam

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.addev.listaspam.service.UpdateChecker
import com.addev.listaspam.util.SpamUtils
import com.addev.listaspam.util.getBlockedNumbers
import com.addev.listaspam.util.getCallLogs
import com.addev.listaspam.util.getTellowsApiCountry
import com.addev.listaspam.util.getWhitelistNumbers
import com.addev.listaspam.util.setTellowsApiCountry
import com.addev.listaspam.util.isUpdateCheckEnabled
import com.addev.listaspam.util.CountryLanguageUtils

class MainActivity : AppCompatActivity(), CallLogAdapter.OnItemChangedListener {

    private lateinit var intentLauncher: ActivityResultLauncher<Intent>
    private var permissionDeniedDialog: AlertDialog? = null
    private var callLogAdapter: CallLogAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var blockedCountTextView: TextView? = null
    private var whitelistCountTextView: TextView? = null
    private var callCountTextView: TextView? = null
    private var blockedCardView: View? = null
    private var whitelistCardView: View? = null
    private var callCardView: View? = null

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
        whitelistCountTextView = findViewById(R.id.whitelistCountTextView)
        callCountTextView = findViewById(R.id.callCountTextView)
        blockedCardView = findViewById(R.id.blockedCardView)
        whitelistCardView = findViewById(R.id.whitelistCardView)
        callCardView = findViewById(R.id.callCardView)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView?.layoutManager = LinearLayoutManager(this)

        blockedCardView?.setOnClickListener {
            startActivity(Intent(this, NumberListActivity::class.java).putExtra(NumberListActivity.EXTRA_LIST_TYPE, NumberListActivity.TYPE_BLOCKED))
        }
        whitelistCardView?.setOnClickListener {
            startActivity(Intent(this, NumberListActivity::class.java).putExtra(NumberListActivity.EXTRA_LIST_TYPE, NumberListActivity.TYPE_WHITELIST))
        }
        callCardView?.setOnClickListener {
            recyclerView?.smoothScrollToPosition(0)
            Toast.makeText(this, getString(R.string.call_list_scrolled_to_top), Toast.LENGTH_SHORT).show()
        }

        CountryLanguageUtils.setTellowsCountry(this)
        if (isUpdateCheckEnabled(this)) {
            checkUpdates()
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

    private fun checkUpdates() {
        Thread {
            val checker = UpdateChecker(
                context = this,
                githubUser = "sinvy",
                githubRepo = "spam-call-blocker-cn"
            )
            checker.checkForUpdateSync()
        }.start()
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
                spamUtils.checkSpamNumber(this, number, null)
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

    override fun onResume() {
        super.onResume()
        init()
    }

    private fun refreshCallLogs(positions: List<Int> = listOf()) {
        val blockedNumbers = getBlockedNumbers(this)
        val whitelistNumbers = getWhitelistNumbers(this)

        val callLogs = getCallLogs(this)

        blockedCountTextView?.text = blockedNumbers.size.toString()
        whitelistCountTextView?.text = whitelistNumbers.size.toString()
        callCountTextView?.text = callLogs.size.toString()

        if (callLogAdapter == null) {
            callLogAdapter = CallLogAdapter(this, callLogs, blockedNumbers, whitelistNumbers)
            recyclerView?.adapter = callLogAdapter
            callLogAdapter?.setOnItemChangedListener(this)
        } else {
            callLogAdapter?.callLogs = callLogs
            callLogAdapter?.blockedNumbers = blockedNumbers
            callLogAdapter?.whitelistNumbers = whitelistNumbers
            callLogAdapter?.notifyDataSetChanged()
        }

        if (positions.isNotEmpty()) {
            positions.forEach { position ->
                callLogAdapter?.notifyItemChanged(position)
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
        PermissionUtils.checkPermissionsAndRequest(this) { deniedPermissions ->
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
        if (!roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            intentLauncher.launch(intent)
            showToast(this, getString(R.string.call_screening_role_prompt), Toast.LENGTH_LONG)
        }
    }
}
