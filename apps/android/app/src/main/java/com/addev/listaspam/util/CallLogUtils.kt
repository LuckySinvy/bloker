// CallLogUtils.kt
package com.addev.listaspam.util

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.*

data class CallLogEntry(
    val number: String,
    val type: Int,
    val date: Date,
    val duration: Long,
    val contactName: String? = null,
    val isBlocked: Boolean = false,
    val isWhitelisted: Boolean = false,
    val note: String = ""
)

private const val MAX_CALL_LOGS = 80
private val contactNameCache = mutableMapOf<String, String>()

fun getCallLogs(context: Context): List<CallLogEntry> {
    val callLogs = mutableListOf<CallLogEntry>()
    val selection = "${CallLog.Calls.TYPE} IN (${CallLog.Calls.INCOMING_TYPE}, ${CallLog.Calls.REJECTED_TYPE}, ${CallLog.Calls.BLOCKED_TYPE}, ${CallLog.Calls.MISSED_TYPE})"
    val projection = arrayOf(
        CallLog.Calls.NUMBER,
        CallLog.Calls.TYPE,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION
    )

    val cursor: Cursor? = context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        projection,
        selection,
        null,
        CallLog.Calls.DATE + " DESC"
    )

    cursor?.use {
        val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
        val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
        val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
        val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)

        while (it.moveToNext()) {
            val number = it.getString(numberIndex)
            val type = it.getInt(typeIndex)
            val date = Date(it.getLong(dateIndex))
            val duration = it.getLong(durationIndex)
            callLogs.add(CallLogEntry(number, type, date, duration))
            if (callLogs.size >= MAX_CALL_LOGS) {
                break
            }
        }
    }

    return callLogs
}

fun getCallLogsWithContacts(context: Context): List<CallLogEntry> {
    val callLogs = getCallLogs(context)
    if (callLogs.isEmpty()) {
        return callLogs
    }

    val contactNames = resolveContactNames(
        context,
        callLogs.map { it.number }
    )

    return callLogs.map { entry ->
        entry.copy(contactName = contactNames[entry.number])
    }
}

fun hydrateCallLogStatuses(
    context: Context,
    callLogs: List<CallLogEntry>
): List<CallLogEntry> {
    if (callLogs.isEmpty()) {
        return callLogs
    }

    val uniqueNumbers = callLogs.asSequence()
        .map { it.number.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    val blockedNotes = getBlockedNumberNotes(context)
    val syncedBlockedNotes = getSyncedBlockedNumberNotes(context)
    val whitelistNotes = getWhitelistNumberNotes(context)
    val patternNotes = getPatternNotes(context)
    val statusMap = mutableMapOf<String, Triple<Boolean, Boolean, String>>()
    val classifications = classifyNumbers(context, uniqueNumbers)

    uniqueNumbers.forEach { number ->
        val classification = classifications[number]
        val isWhitelisted = classification?.isWhitelisted == true
        val isBlocked = classification?.isBlocked == true
        val note = when {
            isWhitelisted -> whitelistNotes[number].orEmpty()
            isBlocked -> blockedNotes[number]
                ?.takeIf { it.isNotBlank() }
                ?: syncedBlockedNotes[number].orEmpty()
            else -> classification?.matchedPattern
                ?.let { patternNotes[it].orEmpty() }
                .orEmpty()
        }
        statusMap[number] = Triple(isBlocked, isWhitelisted, note)
    }

    return callLogs.map { entry ->
        val status = statusMap[entry.number]
        if (status == null) {
            entry
        } else {
            entry.copy(
                isBlocked = status.first,
                isWhitelisted = status.second,
                note = status.third
            )
        }
    }
}

private fun resolveContactNames(
    context: Context,
    numbers: Collection<String>
): Map<String, String> {
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return emptyMap()
    }

    val result = mutableMapOf<String, String>()
    val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

    numbers.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .forEach { number ->
            contactNameCache[number]?.let { cached ->
                result[number] = cached
                return@forEach
            }
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    )
                    result[number] = name
                    contactNameCache[number] = name
                }
            }
        }

    return result
}
