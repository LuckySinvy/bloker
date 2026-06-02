package com.addev.listaspam.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.core.content.edit

const val SPAM_PREFS = "SPAM_PREFS"
const val BLOCK_NUMBERS_KEY = "BLOCK_NUMBERS"
const val WHITELIST_NUMBERS_KEY = "WHITELIST_NUMBERS"
const val PATTERN_NOTES_KEY = "PATTERN_NOTES"
private const val LEGACY_DEFAULT_PREFS_KEY = "com.addev.listaspam_preferences"
private const val LEGACY_DEFAULT_PREFS_MIGRATION_KEY = "pref_legacy_default_prefs_migrated_v1"

private fun getDefaultPrefs(context: Context): SharedPreferences =
    PreferenceManager.getDefaultSharedPreferences(context)

fun getCurrentDefaultPrefsName(context: Context): String = "${context.packageName}_preferences"

fun resolveImportedPrefsName(context: Context, importedPrefsName: String): String =
    when (importedPrefsName) {
        LEGACY_DEFAULT_PREFS_KEY -> getCurrentDefaultPrefsName(context)
        else -> importedPrefsName
    }

fun migrateLegacyDefaultPreferences(context: Context) {
    val currentPrefs = getDefaultPrefs(context)
    if (currentPrefs.getBoolean(LEGACY_DEFAULT_PREFS_MIGRATION_KEY, false)) {
        return
    }

    val legacyPrefs = context.getSharedPreferences(LEGACY_DEFAULT_PREFS_KEY, Context.MODE_PRIVATE)
    val legacyEntries = legacyPrefs.all
    if (legacyEntries.isEmpty()) {
        currentPrefs.edit { putBoolean(LEGACY_DEFAULT_PREFS_MIGRATION_KEY, true) }
        return
    }

    val mergedPatterns = buildSet {
        addAll(parsePatternList(legacyEntries["pref_pattern_list"] as? String))
        addAll(parsePatternList(currentPrefs.getString("pref_pattern_list", null)))
    }

    currentPrefs.edit {
        for ((key, value) in legacyEntries) {
            if (key == "pref_pattern_list") {
                continue
            }
            if (currentPrefs.contains(key)) {
                continue
            }
            putAny(key, value)
        }

        if (mergedPatterns.isNotEmpty()) {
            putString("pref_pattern_list", mergedPatterns.joinToString("\n"))
        }
        putBoolean(LEGACY_DEFAULT_PREFS_MIGRATION_KEY, true)
    }

    legacyPrefs.edit { clear() }
}

private fun getPrefs(context: Context): SharedPreferences {
    migrateLegacyDefaultPreferences(context)
    return getDefaultPrefs(context)
}

private fun SharedPreferences.Editor.putAny(key: String, value: Any?) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Float -> putFloat(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        null -> remove(key)
    }
}

private fun parsePatternList(patterns: String?): Set<String> {
    if (patterns.isNullOrBlank()) {
        return emptySet()
    }

    return patterns.split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

private fun getBooleanPref(context: Context, key: String, defaultValue: Boolean): Boolean =
    getPrefs(context).getBoolean(key, defaultValue)

private fun getStringPref(context: Context, key: String): String? =
    getPrefs(context).getString(key, null)

private fun setStringPref(context: Context, key: String, value: String) {
    getPrefs(context).edit { putString(key, value) }
}

fun isBlockingEnabled(context: Context): Boolean =
    getBooleanPref(context, "pref_enable_blocking", true)

fun shouldBlockHiddenNumbers(context: Context): Boolean =
    getBooleanPref(context, "pref_block_hidden_numbers", true)

fun shouldBlockInternationalNumbers(context: Context): Boolean =
    getBooleanPref(context, "pref_block_international_numbers", false)

fun shouldFilterWithTellowsApi(context: Context): Boolean =
    getBooleanPref(context, "pref_tellows_api", true)

fun getTellowsApiCountry(context: Context): String? =
    getStringPref(context, "pref_tellows_country")?.uppercase()

fun setTellowsApiCountry(context: Context, countryCode: String) =
    setStringPref(context, "pref_tellows_country", countryCode.lowercase())

// ...scraper-related preferences removed...

fun shouldBlockNonContacts(context: Context): Boolean =
    getBooleanPref(context, "pref_block_non_contacts", false)

fun shouldShowNotification(context: Context): Boolean =
    getBooleanPref(context, "pref_show_notification", true)

fun shouldFilterWithStirShaken(context: Context): Boolean =
    getBooleanPref(context, "pref_block_stir_shaken_risk", false)

fun shouldMuteInsteadOfBlocking(context: Context): Boolean =
    getBooleanPref(context, "pref_mute_instead_of_block", false)

fun isPatternBlockingEnabled(context: Context): Boolean =
    getBooleanPref(context, "pref_enable_pattern_blocking", false)

fun getBlockedPatterns(context: Context): Set<String> {
    return parsePatternList(getStringPref(context, "pref_pattern_list"))
}

fun setBlockedPatterns(context: Context, patterns: Set<String>) {
    setStringPref(context, "pref_pattern_list", patterns.joinToString("\n"))
}

fun getPatternNotes(context: Context): Map<String, String> {
    val raw = getStringPref(context, PATTERN_NOTES_KEY) ?: return emptyMap()
    return raw.split("\n")
        .mapNotNull { line ->
            val index = line.indexOf('\t')
            if (index <= 0) return@mapNotNull null
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1)
            if (key.isBlank()) null else key to value
        }
        .toMap()
}

fun setPatternNotes(context: Context, notes: Map<String, String>) {
    val serialized = notes.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    setStringPref(context, PATTERN_NOTES_KEY, serialized)
}

fun setPatternNote(context: Context, pattern: String, note: String) {
    val current = getPatternNotes(context).toMutableMap()
    if (note.isBlank()) {
        current.remove(pattern)
    } else {
        current[pattern] = note
    }
    setPatternNotes(context, current)
}

/**
 * Saves a phone number as spam in SharedPreferences by adding it to the blocked numbers set.
 * Also removes the number from the whitelist if present.
 *
 * @param context The context for accessing resources.
 * @param number The phone number to be saved as spam.
 */
fun saveSpamNumber(context: Context, number: String) {
    // Remove the number from the whitelist before adding it to the spam list
    removeWhitelistNumber(context, number)

    // Get the SharedPreferences and update the blocked numbers set
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    val blockedNumbers =
        sharedPreferences.getStringSet(BLOCK_NUMBERS_KEY, mutableSetOf())?.toMutableSet()
    blockedNumbers?.add(number)

    // Save the updated blocked numbers set to SharedPreferences
    sharedPreferences.edit {
        putStringSet(BLOCK_NUMBERS_KEY, blockedNumbers)
    }
}

/**
 * Removes a phone number from the spam list in SharedPreferences by removing it from the blocked numbers set.
 *
 * @param context The context for accessing resources.
 * @param number The phone number to be removed from the spam list.
 */
fun removeSpamNumber(context: Context, number: String) {
    // Get the SharedPreferences and update the blocked numbers set
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    val blockedNumbers =
        sharedPreferences.getStringSet(BLOCK_NUMBERS_KEY, mutableSetOf())?.toMutableSet()
    blockedNumbers?.remove(number)

    // Save the updated blocked numbers set to SharedPreferences
    sharedPreferences.edit {
        putStringSet(BLOCK_NUMBERS_KEY, blockedNumbers)
    }
}

/**
 * Adds a phone number to the whitelist in SharedPreferences by adding it to the whitelist numbers set.
 * Also removes the number from the spam list if present.
 *
 * @param context The context for accessing resources.
 * @param number The phone number to be added to the whitelist.
 */
fun addNumberToWhitelist(context: Context, number: String) {
    // Remove the number from the spam list before adding it to the whitelist
    removeSpamNumber(context, number)

    // Get the SharedPreferences and update the whitelist numbers set
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    val whitelistNumbers =
        sharedPreferences.getStringSet(WHITELIST_NUMBERS_KEY, mutableSetOf())?.toMutableSet()
    whitelistNumbers?.add(number)

    // Save the updated whitelist numbers set to SharedPreferences
    sharedPreferences.edit {
        putStringSet(WHITELIST_NUMBERS_KEY, whitelistNumbers)
    }
}

/**
 * Removes a phone number from the whitelist in SharedPreferences by removing it from the whitelist numbers set.
 *
 * @param context The context for accessing resources.
 * @param number The phone number to be removed from the whitelist.
 */
fun removeWhitelistNumber(context: Context, number: String) {
    // Get the SharedPreferences and update the whitelist numbers set
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    val whitelistNumbers =
        sharedPreferences.getStringSet(WHITELIST_NUMBERS_KEY, mutableSetOf())?.toMutableSet()
    whitelistNumbers?.remove(number)

    // Save the updated whitelist numbers set to SharedPreferences
    sharedPreferences.edit {
        putStringSet(WHITELIST_NUMBERS_KEY, whitelistNumbers)
    }
}

fun getBlockedNumbers(context: Context): Set<String> {
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    return sharedPreferences.getStringSet(BLOCK_NUMBERS_KEY, emptySet()) ?: emptySet()
}

fun getWhitelistNumbers(context: Context): Set<String> {
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    return sharedPreferences.getStringSet(WHITELIST_NUMBERS_KEY, emptySet()) ?: emptySet()
}

/**
 * Checks if a number is blocked locally in shared preferences.
 *
 * @param context The application context.
 * @param number The phone number to check.
 * @return True if the number is blocked locally, false otherwise.
 */

/**
 * Checks if a number is blocked locally in shared preferences, supporting wildcards.
 *
 * Wildcard rules:
 *   - '*' at the end: matches prefix (e.g., +33162*)
 *   - '*' at the start: matches suffix (e.g., *98)
 *   - '*' in the middle: matches infix (e.g., 213*134)
 *   - No '*': exact match
 *   - If pattern does not start with '+', also check with user's country prefix
 *
 * @param context The application context.
 * @param number The phone number to check.
 * @return True if the number is blocked locally, false otherwise.
 */
fun isNumberBlocked(context: Context, number: String): Boolean {
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    val blockedNumbers = sharedPreferences.getStringSet(BLOCK_NUMBERS_KEY, emptySet()) ?: emptySet()
    val normalizedNumber = number.replace("\\D".toRegex(), "")

    // Helper to get user's country prefix (e.g., "+33")
    fun getUserCountryPrefix(): String? {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val countryIso = telephonyManager?.networkCountryIso?.uppercase()
            if (countryIso != null) {
                val phoneUtil = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
                val code = phoneUtil.getCountryCodeForRegion(countryIso)
                if (code > 0) "+$code" else null
            } else null
        } catch (e: Exception) { null }
    }
    val userPrefix = getUserCountryPrefix()

    fun matchesPattern(pattern: String, num: String): Boolean {
        return when {
            pattern == "*" -> true
            pattern.startsWith("*") && pattern.endsWith("*") && pattern.length > 2 -> num.contains(pattern.substring(1, pattern.length-1))
            pattern.startsWith("*") -> num.endsWith(pattern.substring(1))
            pattern.endsWith("*") -> num.startsWith(pattern.substring(0, pattern.length-1))
            pattern.contains("*") -> {
                val parts = pattern.split("*")
                if (parts.size == 2) num.startsWith(parts[0]) && num.endsWith(parts[1])
                else false
            }
            else -> num == pattern
        }
    }

    for (pattern in blockedNumbers) {
        if (pattern.isNullOrBlank()) continue
        val normalizedPattern = pattern.replace("\\D".toRegex(), "")
        // Try direct match
        if (matchesPattern(normalizedPattern, normalizedNumber)) return true
        // If pattern does not start with '+', try with user prefix
        if (!pattern.startsWith("+") && userPrefix != null) {
            val withPrefix = (userPrefix + normalizedPattern).replace("\\D".toRegex(), "")
            if (matchesPattern(withPrefix, normalizedNumber)) return true
        }
    }
    return false
}

fun isNumberWhitelisted(context: Context, number: String): Boolean {
    val sharedPreferences = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
    val whitelistedNumbers = sharedPreferences.getStringSet(WHITELIST_NUMBERS_KEY, emptySet())
    if (whitelistedNumbers != null) {
        return whitelistedNumbers.contains(number)
    }
    return false
}

fun isUpdateCheckEnabled(context: Context): Boolean =
    getBooleanPref(context, "pref_enable_update_check", true)
