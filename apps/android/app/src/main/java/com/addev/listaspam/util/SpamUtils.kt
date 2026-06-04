package com.addev.listaspam.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.addev.listaspam.R
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Utility class for handling spam number checks and notifications.
 */
class SpamUtils {
    enum class TestDecisionPath {
        BLOCKING_DISABLED,
        HIDDEN_NUMBER,
        WHITELIST,
        PATTERN,
        BLOCKLIST,
        CONTACT,
        NON_CONTACT,
        INTERNATIONAL,
        SELF_HOSTED_SPAM,
        TELLOWS_SPAM,
        NOT_SPAM
    }

    data class TestResult(
        val number: String,
        val normalizedNumber: String,
        val isSpam: Boolean,
        val path: TestDecisionPath,
        val matchedPattern: String? = null,
        val isInContacts: Boolean = false
    )

    companion object {
        private const val SPAM_PREFS = "SPAM_PREFS"
    }

    /**
     * Extracts the raw phone number from the call details.
     * @param details Details of the incoming call.
     * @return Raw phone number as a String.
     */
    private fun getRawPhoneNumber(details: Call.Details): String? {
        return when {
            details.handle != null -> details.handle.schemeSpecificPart
            details.gatewayInfo?.originalAddress != null -> details.gatewayInfo.originalAddress.schemeSpecificPart
            details.intentExtras != null -> {
                val uri =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        details.intentExtras.getParcelable(
                            TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                            Uri::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        details.intentExtras.getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
                    }
                uri?.schemeSpecificPart
            }

            else -> null
        }
    }

    /**
     * Checks if a given phone number is spam by checking local blocklist and online databases.
     *
     * @param context The application context.
     * @param phoneNumber The phone number to check.
     * @param details Call details
     * @param callback A function to be called with the result (true if spam, false otherwise).
     */
    fun checkSpamNumber(
        context: Context,
        phoneNumber: String?,
        details: Call.Details?,
        callback: (isSpam: Boolean) -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!isBlockingEnabled(context)) {
                showToast(context, context.getString(R.string.blocking_disabled), Toast.LENGTH_LONG)
                callback(false)
                return@launch
            }

            val number = if (details != null) getRawPhoneNumber(details) else phoneNumber;

            if (number.isNullOrBlank()) {
                if (shouldBlockHiddenNumbers(context)) {
                    handleSpamNumber(
                        context,
                        "",
                        false,
                        context.getString(R.string.block_hidden_number),
                        callback
                    )
                    return@launch
                } else {
                    callback(false)
                    return@launch
                }
            }

            // Check whitelist first - if whitelisted, always allow
            if (isNumberWhitelisted(context, number)) {
                callback(false)
                return@launch
            }

            if (isPatternBlockingEnabled(context) && findMatchedPatternForNumber(context, number) != null) {
                handleSpamNumber(
                    context,
                    number,
                    false,
                    context.getString(R.string.block_pattern_match),
                    callback
                )
                return@launch
            }

            // End call if the number is already blocked
            if (isNumberBlocked(context, number)) {
                handleSpamNumber(
                    context,
                    number,
                    false,
                    context.getString(R.string.block_already_blocked_number),
                    callback
                )
                return@launch
            }

            // Don't check number if is in contacts
            val isNumberInAgenda = isNumberInAgenda(context, number)
            if (isNumberInAgenda) {
                callback(false)
                return@launch
            }

            if (shouldBlockNonContacts(context)) {
                handleSpamNumber(
                    context,
                    number,
                    false,
                    context.getString(R.string.block_non_contact),
                    callback
                )
                return@launch
            }

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
                && shouldBlockInternationalNumbers(context)
                && isInternationalCall(context, number)
            ) {
                handleSpamNumber(
                    context,
                    number,
                    false,
                    context.getString(R.string.block_international_call),
                    callback
                )
                return@launch
            }

            val spamCheckers: List<suspend (String) -> Boolean> = buildSpamCheckers(context)
            val isSpam = runBlocking {
                isSpamRace(spamCheckers, number)
            }

            if (isSpam) {
                handleSpamNumber(
                    context,
                    number,
                    context.getString(R.string.block_spam_number),
                    callback
                )
            } else {
                // handleNonSpamNumber(context, number)
                callback(false)
                return@launch
            }
        }
    }

    fun testSpamNumber(
        context: Context,
        phoneNumber: String,
        callback: (TestResult) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!isBlockingEnabled(context)) {
                callback(
                    TestResult(
                        number = phoneNumber,
                        normalizedNumber = normalizePhoneNumber(phoneNumber),
                        isSpam = false,
                        path = TestDecisionPath.BLOCKING_DISABLED
                    )
                )
                return@launch
            }

            val number = phoneNumber.trim()
            val normalizedNumber = normalizePhoneNumber(number)
            if (number.isBlank()) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = shouldBlockHiddenNumbers(context),
                        path = if (shouldBlockHiddenNumbers(context)) {
                            TestDecisionPath.HIDDEN_NUMBER
                        } else {
                            TestDecisionPath.NOT_SPAM
                        }
                    )
                )
                return@launch
            }

            if (isNumberWhitelisted(context, number)) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = false,
                        path = TestDecisionPath.WHITELIST
                    )
                )
                return@launch
            }

            val matchedPattern =
                if (isPatternBlockingEnabled(context)) findMatchedPatternForNumber(context, number) else null
            if (matchedPattern != null) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = true,
                        path = TestDecisionPath.PATTERN,
                        matchedPattern = matchedPattern
                    )
                )
                return@launch
            }

            if (isNumberBlocked(context, number)) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = true,
                        path = TestDecisionPath.BLOCKLIST
                    )
                )
                return@launch
            }

            val isInContacts = isNumberInAgenda(context, number)
            if (isInContacts) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = false,
                        path = TestDecisionPath.CONTACT,
                        isInContacts = true
                    )
                )
                return@launch
            }

            if (shouldBlockNonContacts(context)) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = true,
                        path = TestDecisionPath.NON_CONTACT,
                        isInContacts = false
                    )
                )
                return@launch
            }

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED &&
                shouldBlockInternationalNumbers(context) &&
                isInternationalCall(context, number)
            ) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = true,
                        path = TestDecisionPath.INTERNATIONAL,
                        isInContacts = false
                    )
                )
                return@launch
            }

            if (ApiUtils.checkSelfHostedSpamApi(getSpamBackendBaseUrl(context), number)) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = true,
                        path = TestDecisionPath.SELF_HOSTED_SPAM,
                        isInContacts = false
                    )
                )
                return@launch
            }

            if (shouldFilterWithTellowsApi(context) &&
                ApiUtils.checkTellowsSpamApi(number, getTellowsApiCountry(context) ?: "us")
            ) {
                callback(
                    TestResult(
                        number = number,
                        normalizedNumber = normalizedNumber,
                        isSpam = true,
                        path = TestDecisionPath.TELLOWS_SPAM,
                        isInContacts = false
                    )
                )
                return@launch
            }

            callback(
                TestResult(
                    number = number,
                    normalizedNumber = normalizedNumber,
                    isSpam = false,
                    path = TestDecisionPath.NOT_SPAM,
                    isInContacts = false
                )
            )
        }
    }

    /**
     * Performs a "race" among multiple spam checkers to determine if a phone number is spam.
     *
     * Each checker is a suspend function that returns `true` if the number is spam.
     * The function returns `true` as soon as the first checker reports spam.
     * If all checkers finish and none report spam, it returns `false`.
     * A timeout can be provided to handle long-running or stuck checkers.
     *
     * This function launches all checkers concurrently and cancels remaining jobs
     * as soon as a result is determined, to save resources.
     *
     * @param spamCheckers A list of suspend functions that each take a phone number
     *                     and return `true` if it is spam.
     * @param number The phone number to check for spam.
     * @param timeoutMs Maximum time in milliseconds to wait for a result before returning `false`.
     *                  Default is 5000ms.
     *
     * @return `true` if any checker reports spam, `false` if none report spam or timeout occurs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun isSpamRace(
        spamCheckers: List<suspend (String) -> Boolean>,
        number: String,
        timeoutMs: Long = 5000
    ): Boolean = coroutineScope {
        if (spamCheckers.isEmpty()) return@coroutineScope false

        val resultChannel = Channel<Boolean>(capacity = Channel.UNLIMITED)
        val remaining = AtomicInteger(spamCheckers.size)

        val jobs = spamCheckers.map { checker ->
            launch {
                val result = runCatching { checker(number) }.getOrDefault(false)

                if (result) {
                    resultChannel.send(true)
                } else if (remaining.decrementAndGet() == 0) {
                    resultChannel.close()
                }
            }
        }

        val isSpam = try {
            select {
                resultChannel.onReceiveCatching { result ->
                    result.getOrNull() ?: false
                }
                onTimeout(timeoutMs) {
                    false
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
            resultChannel.cancel()
        }

        isSpam
    }

    private fun buildSpamCheckers(context: Context): List<suspend (String) -> Boolean> {
        val spamCheckers = mutableListOf<suspend (String) -> Boolean>()

        spamCheckers.add { number ->
            ApiUtils.checkSelfHostedSpamApi(getSpamBackendBaseUrl(context), number)
        }

        val tellowsApi = shouldFilterWithTellowsApi(context)
        if (tellowsApi) {
            spamCheckers.add { number ->
                ApiUtils.checkTellowsSpamApi(number, getTellowsApiCountry(context) ?: "us")
            }
        }
        return spamCheckers
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun isInternationalCall(context: Context, phoneNumber: String): Boolean {
        val phoneNumberUtil = PhoneNumberUtil.getInstance()

        return try {
            val parsedNumber = phoneNumberUtil.parse(phoneNumber, null) // Safe parsing

            val simCountry = CountryLanguageUtils.getSimCountry(context).uppercase()

            val countryCode = phoneNumberUtil.getCountryCodeForRegion(simCountry)
            parsedNumber.countryCode != countryCode // True if international

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Normalizes a phone number by removing all non-digit characters.
     *
     * @param number The phone number to normalize.
     * @return The normalized phone number.
     */
    private fun normalizePhoneNumber(number: String): String {
        return number.replace("\\D".toRegex(), "")
    }

    /**
     * Checks if a phone number exists in the device's contact agenda.
     *
     * This function determines whether a given phone number is associated with
     * a contact stored in the user's address book by querying the contacts database.
     *
     * @param context Context for accessing content resolver
     * @param phoneNumber The phone number to check
     * @return true if the number is found in the contacts, false otherwise
     */
    private fun isNumberInAgenda(context: Context, phoneNumber: String): Boolean {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            return cursor != null && cursor.moveToFirst()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            cursor?.close()
        }
    }

    // ...scraper logic removed...

    /**
     * Handles the scenario when a phone number is identified as spam.
     * @param context Context for accessing resources.
     * @param number Phone number identified as spam.
     * @param callback Callback function to handle the result.
     */
    private fun handleSpamNumber(
        context: Context,
        number: String,
        reason: String,
        callback: (isSpam: Boolean) -> Unit
    ) {
        handleSpamNumber(context, number, true, reason, callback)
    }

    /**
     * Handles the scenario when a phone number is identified as spam.
     * @param context Context for accessing resources.
     * @param number Phone number identified as spam.
     * @param callback Callback function to handle the result.
     */
    private fun handleSpamNumber(
        context: Context,
        number: String,
        saveNumber: Boolean,
        reason: String,
        callback: (isSpam: Boolean) -> Unit
    ) {
        showToast(
            context,
            context.getString(R.string.block_reason_long) + " " + reason,
            Toast.LENGTH_LONG
        )

        if (saveNumber) {
            saveSpamNumber(context, number)
        }
        sendBlockedCallNotification(context, number, reason)
        callback(true)
    }

    /**
     * Handles the scenario when a phone number is not identified as spam.
     * @param context Context for accessing resources.
     * @param number Phone number identified as not spam.
     */
    private fun handleNonSpamNumber(
        context: Context,
        @Suppress("UNUSED_PARAMETER") number: String
    ) {
        showToast(context, context.getString(R.string.incoming_call_not_spam))

        CoroutineScope(Dispatchers.Main).launch {
            sendNotification(
                context,
                context.getString(R.string.call_incoming),
                context.getString(R.string.incoming_call_not_spam),
                10000
            )
        }
    }

    /**
     * Displays a toast message.
     * @param context Context for displaying the toast.
     * @param message Message to display.
     * @param duration Duration of the toast display.
     */
    private fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_LONG) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, duration).show()
        }
    }

}
