package com.addev.listaspam.util

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.addev.listaspam.R

object PermissionUtils {
    const val REQUEST_CODE_PERMISSIONS = 1

    /**
     * Checks for necessary permissions and requests them if not granted.
     *
     * This function identifies which of the required permissions (READ_CALL_LOG,
     * READ_PHONE_STATE, READ_CONTACTS, ANSWER_PHONE_CALLS, and POST_NOTIFICATIONS
     * for Android Tiramisu and above) are not granted.
     *
     * It then differentiates between permissions that haven't been requested yet
     * (missingPermissions) and those that have been denied by the user previously
     * (deniedPermissions).
     *
     * For missing permissions, it directly requests them using `ActivityCompat.requestPermissions`.
     * For denied permissions, it calls `showPermissionToastAndRequest` to inform the user
     * and guide them to settings.
     */
    fun checkPermissionsAndRequest(activity: Activity, onShowDialog: (List<String>) -> Unit) {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        val missingPermissions = notGrantedPermissions.filter {
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        val deniedPermissions = notGrantedPermissions.filter {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        }
        if (deniedPermissions.isNotEmpty()) {
            onShowDialog(deniedPermissions)
        }
    }

    /**
     * Shows a dialog to the user when permissions are required but not granted, and requests the missing permissions.
     * If the dialog is already visible, it will not be shown again.
     *
     * @param activity The activity context
     * @param missingPermissions a list of permissions that are missing.
     * @param currentDialog The current permission dialog if any
     * @return The newly created dialog
     */
    fun showPermissionDialog(
        activity: Activity,
        missingPermissions: List<String>,
        currentDialog: AlertDialog?
    ): AlertDialog? {
        if (currentDialog?.isShowing == true) {
            return currentDialog
        }

        val permissionNames = missingPermissions.map { "• " + getPermissionName(activity, it) }
        val message = activity.getString(
            R.string.permissions_required_message,
            permissionNames.joinToString("\n")
        )

        return AlertDialog.Builder(activity)
            .setCancelable(false)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.permissions_required_title)
            .setMessage(message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", activity.packageName, null)
                activity.startActivity(intent)
            }
            .create()
    }

    /**
     * Returns the human-readable name for a given permission string.
     *
     * @param activity The activity context
     * @param permission The permission string (e.g., Manifest.permission.READ_CALL_LOG).
     * @return The human-readable name of the permission, or the original permission string if no mapping is found.
     */
    private fun getPermissionName(activity: Activity, permission: String): String {
        return when (permission) {
            Manifest.permission.READ_CALL_LOG -> activity.getString(R.string.permission_read_call_log)
            Manifest.permission.READ_PHONE_STATE -> activity.getString(R.string.permission_read_phone_state)
            Manifest.permission.READ_CONTACTS -> activity.getString(R.string.permission_read_contacts)
            Manifest.permission.ANSWER_PHONE_CALLS -> activity.getString(R.string.permission_answer_phone_calls)
            Manifest.permission.POST_NOTIFICATIONS -> activity.getString(R.string.permission_post_notifications)
            else -> permission
        }
    }
}
