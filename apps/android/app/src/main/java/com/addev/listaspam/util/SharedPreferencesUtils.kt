package com.addev.listaspam.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import com.google.i18n.phonenumbers.PhoneNumberUtil

const val SPAM_PREFS = "SPAM_PREFS"
const val BLOCK_NUMBERS_KEY = "BLOCK_NUMBERS"
const val WHITELIST_NUMBERS_KEY = "WHITELIST_NUMBERS"
const val PATTERN_NOTES_KEY = "PATTERN_NOTES"
private const val BLOCK_NUMBER_NOTES_KEY = "BLOCK_NUMBER_NOTES"
private const val WHITELIST_NUMBER_NOTES_KEY = "WHITELIST_NUMBER_NOTES"
private const val DEFAULT_BACKEND_BASE_URL = "https://block.gadrel.top"
private const val SYNCED_BLOCK_NUMBERS_KEY = "pref_synced_block_numbers"
private const val SYNCED_BLOCK_NUMBER_NOTES_KEY = "pref_synced_block_number_notes"
private const val SYNCED_WHITELIST_NUMBERS_KEY = "pref_synced_whitelist_numbers"
private const val SYNCED_WHITELIST_NUMBER_NOTES_KEY = "pref_synced_whitelist_number_notes"
private const val SYNCED_PATTERN_RULES_KEY = "pref_synced_pattern_rules"
private const val SYNCED_PATTERN_NOTES_KEY = "pref_synced_pattern_notes"
private const val SYNCED_SPAM_COUNT_KEY = "pref_synced_spam_count"
private const val SYNCED_WHITELIST_COUNT_KEY = "pref_synced_whitelist_count"
private const val SYNCED_PATTERN_COUNT_KEY = "pref_synced_pattern_count"
private const val LAST_SYNC_AT_KEY = "pref_last_self_hosted_sync_at"
private const val LAST_SYNC_VERSION_KEY = "pref_last_self_hosted_sync_version"
private const val LAST_SYNC_ETAG_KEY = "pref_last_self_hosted_sync_etag"
private const val LAST_SYNC_CURSOR_KEY = "pref_last_self_hosted_sync_cursor"
private const val AUTO_SYNC_ENABLED_KEY = "pref_auto_sync_self_hosted"
private const val LEGACY_DEFAULT_PREFS_KEY = "com.addev.listaspam_preferences"
private const val LEGACY_DEFAULT_PREFS_MIGRATION_KEY = "pref_legacy_default_prefs_migrated_v1"
private const val PREFIX_SUFFIX_SEPARATOR = '\u0000'

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
    invalidateCompiledLookupBundle()
}

private fun getPrefs(context: Context): SharedPreferences {
    migrateLegacyDefaultPreferences(context)
    return getDefaultPrefs(context)
}

private data class ExactNumberMatcher(
    val exactNumbers: Set<String>,
    val autoPrefixedNumbers: Set<String>
) {
    fun matches(number: String): Boolean =
        exactNumbers.contains(number) || autoPrefixedNumbers.contains(number)
}

private data class OrderedWildcardPattern(
    val original: String,
    val normalizedPattern: String
) {
    fun matches(number: String): Boolean = wildcardMatches(number, normalizedPattern)
}

private data class PrefixSuffixBucket(
    val prefixLength: Int,
    val suffixLength: Int,
    val patterns: Map<String, String>
) {
    fun findMatch(number: String): String? {
        if (number.length < prefixLength + suffixLength) {
            return null
        }

        val key = buildString(prefixLength + suffixLength + 1) {
            append(number, 0, prefixLength)
            append(PREFIX_SUFFIX_SEPARATOR)
            append(number, number.length - suffixLength, number.length)
        }

        return patterns[key]
    }
}

private data class CompiledPatternMatcher(
    val matchAllPattern: String?,
    val exactPatterns: Map<String, String>,
    val prefixBuckets: Map<Int, Map<String, String>>,
    val prefixLengthsDesc: List<Int>,
    val suffixBuckets: Map<Int, Map<String, String>>,
    val suffixLengthsDesc: List<Int>,
    val containsBuckets: Map<Int, Map<String, String>>,
    val containsLengthsDesc: List<Int>,
    val prefixSuffixBuckets: List<PrefixSuffixBucket>,
    val fallbackPatterns: List<OrderedWildcardPattern>
) {
    fun findMatch(number: String): String? {
        if (number.isEmpty()) {
            return null
        }

        matchAllPattern?.let { return it }
        exactPatterns[number]?.let { return it }

        for (bucket in prefixSuffixBuckets) {
            bucket.findMatch(number)?.let { return it }
        }

        for (length in prefixLengthsDesc) {
            if (number.length < length) {
                continue
            }
            prefixBuckets[length]?.get(number.substring(0, length))?.let { return it }
        }

        for (length in suffixLengthsDesc) {
            if (number.length < length) {
                continue
            }
            suffixBuckets[length]?.get(number.substring(number.length - length))?.let { return it }
        }

        for (length in containsLengthsDesc) {
            if (number.length < length) {
                continue
            }
            val bucket = containsBuckets[length] ?: continue
            for (start in 0..number.length - length) {
                bucket[number.substring(start, start + length)]?.let { return it }
            }
        }

        return fallbackPatterns.firstOrNull { it.matches(number) }?.original
    }
}

private data class BlockedNumberMatcher(
    val exactMatcher: ExactNumberMatcher,
    val wildcardMatcher: CompiledPatternMatcher
) {
    fun matches(number: String): Boolean =
        exactMatcher.matches(number) || wildcardMatcher.findMatch(number) != null
}

private data class CompiledLookupBundle(
    val userCountryPrefixDigits: String?,
    val whitelistMatcher: ExactNumberMatcher,
    val blockedNumberMatcher: BlockedNumberMatcher,
    val patternRuleMatcher: CompiledPatternMatcher
)

data class NumberClassification(
    val isBlocked: Boolean,
    val isWhitelisted: Boolean,
    val matchedPattern: String?
)

@Volatile
private var compiledLookupBundle: CompiledLookupBundle? = null

private val compiledLookupLock = Any()

private fun invalidateCompiledLookupBundle() {
    synchronized(compiledLookupLock) {
        compiledLookupBundle = null
    }
}

private fun normalizePhoneNumber(value: String): String = buildString(value.length) {
    value.forEach { char ->
        if (char.isDigit()) {
            append(char)
        }
    }
}

private fun normalizePatternValue(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when {
            char.isDigit() -> append(char)
            char == '*' -> append(char)
        }
    }
}

private fun wildcardMatches(number: String, pattern: String): Boolean {
    if (pattern.isEmpty() || number.isEmpty()) {
        return false
    }

    val parts = pattern.split("*")
    if (parts.size == 1) {
        return number == pattern
    }

    var currentIndex = 0
    if (parts.first().isNotEmpty()) {
        if (!number.startsWith(parts.first())) {
            return false
        }
        currentIndex = parts.first().length
    }

    if (parts.last().isNotEmpty() && !number.endsWith(parts.last())) {
        return false
    }

    for (index in 1 until parts.lastIndex) {
        val part = parts[index]
        if (part.isEmpty()) {
            continue
        }
        val foundIndex = number.indexOf(part, currentIndex)
        if (foundIndex == -1) {
            return false
        }
        currentIndex = foundIndex + part.length
    }

    return true
}

private fun getUserCountryPrefixDigits(context: Context): String? {
    return try {
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val countryIso = telephonyManager?.networkCountryIso?.uppercase()?.takeIf { it.isNotBlank() }
        if (countryIso == null) {
            null
        } else {
            val code = PhoneNumberUtil.getInstance().getCountryCodeForRegion(countryIso)
            if (code > 0) code.toString() else null
        }
    } catch (_: Exception) {
        null
    }
}

private fun compileExactNumbers(
    numbers: Set<String>,
    userCountryPrefixDigits: String?
): ExactNumberMatcher {
    val exactNumbers = LinkedHashSet<String>(numbers.size)
    val autoPrefixedNumbers = LinkedHashSet<String>()

    for (candidate in numbers) {
        if (candidate.isBlank()) {
            continue
        }

        val normalized = normalizePhoneNumber(candidate)
        if (normalized.isEmpty()) {
            continue
        }

        exactNumbers.add(normalized)
        if (!candidate.startsWith("+") && !userCountryPrefixDigits.isNullOrEmpty()) {
            autoPrefixedNumbers.add(userCountryPrefixDigits + normalized)
        }
    }

    return ExactNumberMatcher(
        exactNumbers = exactNumbers,
        autoPrefixedNumbers = autoPrefixedNumbers
    )
}

private fun compilePatternMatcher(
    patterns: Sequence<Pair<String, String>>
): CompiledPatternMatcher {
    val exactPatterns = LinkedHashMap<String, String>()
    val prefixBuckets = LinkedHashMap<Int, LinkedHashMap<String, String>>()
    val suffixBuckets = LinkedHashMap<Int, LinkedHashMap<String, String>>()
    val containsBuckets = LinkedHashMap<Int, LinkedHashMap<String, String>>()
    val prefixSuffixBuckets = LinkedHashMap<Pair<Int, Int>, LinkedHashMap<String, String>>()
    val fallbackPatterns = mutableListOf<OrderedWildcardPattern>()
    var matchAllPattern: String? = null

    patterns.forEach { (original, normalizedPattern) ->
        if (normalizedPattern.isBlank()) {
            return@forEach
        }

        val starCount = normalizedPattern.count { it == '*' }
        when {
            normalizedPattern == "*" -> {
                if (matchAllPattern == null) {
                    matchAllPattern = original
                }
            }

            starCount == 0 -> {
                exactPatterns.putIfAbsent(normalizedPattern, original)
            }

            starCount == 1 && normalizedPattern.endsWith("*") -> {
                val prefix = normalizedPattern.dropLast(1)
                if (prefix.isNotEmpty()) {
                    prefixBuckets
                        .getOrPut(prefix.length) { LinkedHashMap() }
                        .putIfAbsent(prefix, original)
                }
            }

            starCount == 1 && normalizedPattern.startsWith("*") -> {
                val suffix = normalizedPattern.drop(1)
                if (suffix.isNotEmpty()) {
                    suffixBuckets
                        .getOrPut(suffix.length) { LinkedHashMap() }
                        .putIfAbsent(suffix, original)
                }
            }

            starCount == 1 -> {
                val segments = normalizedPattern.split("*", limit = 2)
                val prefix = segments[0]
                val suffix = segments[1]
                if (prefix.isNotEmpty() && suffix.isNotEmpty()) {
                    val key = prefix.length to suffix.length
                    val joinedKey = buildString(prefix.length + suffix.length + 1) {
                        append(prefix)
                        append(PREFIX_SUFFIX_SEPARATOR)
                        append(suffix)
                    }
                    prefixSuffixBuckets
                        .getOrPut(key) { LinkedHashMap() }
                        .putIfAbsent(joinedKey, original)
                } else {
                    fallbackPatterns += OrderedWildcardPattern(original, normalizedPattern)
                }
            }

            normalizedPattern.startsWith("*") &&
                normalizedPattern.endsWith("*") &&
                normalizedPattern.drop(1).dropLast(1).all { it != '*' } -> {
                val contains = normalizedPattern.substring(1, normalizedPattern.length - 1)
                if (contains.isNotEmpty()) {
                    containsBuckets
                        .getOrPut(contains.length) { LinkedHashMap() }
                        .putIfAbsent(contains, original)
                }
            }

            else -> fallbackPatterns += OrderedWildcardPattern(original, normalizedPattern)
        }
    }

    return CompiledPatternMatcher(
        matchAllPattern = matchAllPattern,
        exactPatterns = exactPatterns,
        prefixBuckets = prefixBuckets,
        prefixLengthsDesc = prefixBuckets.keys.sortedDescending(),
        suffixBuckets = suffixBuckets,
        suffixLengthsDesc = suffixBuckets.keys.sortedDescending(),
        containsBuckets = containsBuckets,
        containsLengthsDesc = containsBuckets.keys.sortedDescending(),
        prefixSuffixBuckets = prefixSuffixBuckets.entries
            .sortedByDescending { (lengths, _) -> lengths.first + lengths.second }
            .map { (lengths, patternsByKey) ->
                PrefixSuffixBucket(lengths.first, lengths.second, patternsByKey)
            },
        fallbackPatterns = fallbackPatterns
    )
}

private fun compileBlockedNumberMatcher(
    numbers: Set<String>,
    userCountryPrefixDigits: String?
): BlockedNumberMatcher {
    val exactNumberInputs = LinkedHashSet<String>()
    val wildcardPatterns = mutableListOf<Pair<String, String>>()

    for (candidate in numbers) {
        if (candidate.isBlank()) {
            continue
        }

        val normalizedPattern = normalizePatternValue(candidate)
        if (normalizedPattern.isEmpty()) {
            continue
        }

        if (normalizedPattern.contains('*')) {
            wildcardPatterns += candidate to normalizedPattern
            if (!candidate.startsWith("+") && !userCountryPrefixDigits.isNullOrEmpty()) {
                wildcardPatterns += candidate to (userCountryPrefixDigits + normalizedPattern)
            }
        } else {
            exactNumberInputs.add(candidate)
        }
    }

    return BlockedNumberMatcher(
        exactMatcher = compileExactNumbers(exactNumberInputs, userCountryPrefixDigits),
        wildcardMatcher = compilePatternMatcher(wildcardPatterns.asSequence())
    )
}

private fun buildCompiledLookupBundle(
    context: Context,
    userCountryPrefixDigits: String?
): CompiledLookupBundle {
    val effectiveWhitelistNumbers = getEffectiveWhitelistNumbers(context)
    val effectiveBlockedNumbers =
        getBlockedNumbers(context) + (getSyncedBlockedNumbers(context) - effectiveWhitelistNumbers)
    val effectivePatternRules = getBlockedPatterns(context) + getSyncedBlockedPatterns(context)

    return CompiledLookupBundle(
        userCountryPrefixDigits = userCountryPrefixDigits,
        whitelistMatcher = compileExactNumbers(effectiveWhitelistNumbers, userCountryPrefixDigits),
        blockedNumberMatcher = compileBlockedNumberMatcher(
            effectiveBlockedNumbers,
            userCountryPrefixDigits
        ),
        patternRuleMatcher = compilePatternMatcher(
            effectivePatternRules.asSequence().flatMap { pattern ->
                val normalizedPattern = normalizePatternValue(pattern)
                if (normalizedPattern.isEmpty()) {
                    emptySequence()
                } else if (!pattern.startsWith("+") && !userCountryPrefixDigits.isNullOrEmpty()) {
                    sequenceOf(
                        pattern to normalizedPattern,
                        pattern to (userCountryPrefixDigits + normalizedPattern)
                    )
                } else {
                    sequenceOf(pattern to normalizedPattern)
                }
            }
        )
    )
}

private fun getCompiledLookupBundle(context: Context): CompiledLookupBundle {
    val userCountryPrefixDigits = getUserCountryPrefixDigits(context)
    compiledLookupBundle
        ?.takeIf { it.userCountryPrefixDigits == userCountryPrefixDigits }
        ?.let { return it }

    synchronized(compiledLookupLock) {
        compiledLookupBundle
            ?.takeIf { it.userCountryPrefixDigits == userCountryPrefixDigits }
            ?.let { return it }

        return buildCompiledLookupBundle(context, userCountryPrefixDigits).also {
            compiledLookupBundle = it
        }
    }
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

private fun getLongPref(context: Context, key: String, defaultValue: Long): Long =
    getPrefs(context).getLong(key, defaultValue)

private fun setStringPref(context: Context, key: String, value: String) {
    getPrefs(context).edit { putString(key, value) }
    if (key == "pref_pattern_list") {
        invalidateCompiledLookupBundle()
    }
}

private fun setLongPref(context: Context, key: String, value: Long) {
    getPrefs(context).edit { putLong(key, value) }
}

fun isBlockingEnabled(context: Context): Boolean =
    getBooleanPref(context, "pref_enable_blocking", true)

fun shouldBlockHiddenNumbers(context: Context): Boolean =
    getBooleanPref(context, "pref_block_hidden_numbers", true)

fun shouldBlockInternationalNumbers(context: Context): Boolean =
    getBooleanPref(context, "pref_block_international_numbers", false)

fun shouldFilterWithTellowsApi(context: Context): Boolean =
    getBooleanPref(context, "pref_tellows_api", true)

fun shouldReportToTellows(context: Context): Boolean =
    getBooleanPref(context, "pref_report_to_tellows", false)

fun getTellowsApiCountry(context: Context): String? =
    getStringPref(context, "pref_tellows_country")?.uppercase()

fun setTellowsApiCountry(context: Context, countryCode: String) =
    setStringPref(context, "pref_tellows_country", countryCode.lowercase())

fun getSpamBackendBaseUrl(context: Context): String =
    getStringPref(context, "pref_spam_backend_base_url")
        ?.trim()
        ?.removeSuffix("/")
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_BACKEND_BASE_URL

fun setSpamBackendBaseUrl(context: Context, baseUrl: String) =
    setStringPref(context, "pref_spam_backend_base_url", baseUrl.trim().removeSuffix("/"))

fun shouldAutoSyncSelfHosted(context: Context): Boolean =
    getBooleanPref(context, AUTO_SYNC_ENABLED_KEY, true)

fun setAutoSyncSelfHosted(context: Context, enabled: Boolean) {
    getPrefs(context).edit { putBoolean(AUTO_SYNC_ENABLED_KEY, enabled) }
}

fun getLastSelfHostedSyncAt(context: Context): Long =
    getLongPref(context, LAST_SYNC_AT_KEY, 0L)

fun setLastSelfHostedSyncAt(context: Context, timestamp: Long) =
    setLongPref(context, LAST_SYNC_AT_KEY, timestamp)

fun getLastSelfHostedSyncVersion(context: Context): String? =
    getStringPref(context, LAST_SYNC_VERSION_KEY)?.takeIf { it.isNotBlank() }

fun setLastSelfHostedSyncVersion(context: Context, version: String) =
    setStringPref(context, LAST_SYNC_VERSION_KEY, version)

fun getLastSelfHostedSyncEtag(context: Context): String? =
    getStringPref(context, LAST_SYNC_ETAG_KEY)?.takeIf { it.isNotBlank() }

fun setLastSelfHostedSyncEtag(context: Context, etag: String) =
    setStringPref(context, LAST_SYNC_ETAG_KEY, etag)

fun getLastSelfHostedSyncCursor(context: Context): Long? {
    val value = getLongPref(context, LAST_SYNC_CURSOR_KEY, -1L)
    return if (value >= 0L) value else null
}

fun setLastSelfHostedSyncCursor(context: Context, cursor: Long) =
    setLongPref(context, LAST_SYNC_CURSOR_KEY, cursor)

fun getSyncedSpamCount(context: Context): Int =
    getPrefs(context).getInt(SYNCED_SPAM_COUNT_KEY, 0)

fun setSyncedSpamCount(context: Context, count: Int) {
    getPrefs(context).edit { putInt(SYNCED_SPAM_COUNT_KEY, count) }
}

fun getSyncedPatternCount(context: Context): Int =
    getPrefs(context).getInt(SYNCED_PATTERN_COUNT_KEY, 0)

fun setSyncedPatternCount(context: Context, count: Int) {
    getPrefs(context).edit { putInt(SYNCED_PATTERN_COUNT_KEY, count) }
}

fun getSyncedWhitelistCount(context: Context): Int =
    getPrefs(context).getInt(SYNCED_WHITELIST_COUNT_KEY, 0)

fun setSyncedWhitelistCount(context: Context, count: Int) {
    getPrefs(context).edit { putInt(SYNCED_WHITELIST_COUNT_KEY, count) }
}

fun getSyncedBlockedNumbers(context: Context): Set<String> =
    NumberStore.getSyncedBlockedNumbers(context)

fun setSyncedBlockedNumbers(context: Context, numbers: Set<String>) {
    NumberStore.syncSyncedBlockedNumbers(context, numbers)
    invalidateCompiledLookupBundle()
}

fun getSyncedBlockedNumberNotes(context: Context): Map<String, String> =
    getSerializedMapPref(context, SYNCED_BLOCK_NUMBER_NOTES_KEY)

fun setSyncedBlockedNumberNotes(context: Context, notes: Map<String, String>) {
    setSerializedMapPref(context, SYNCED_BLOCK_NUMBER_NOTES_KEY, notes)
}

fun getSyncedWhitelistNumbers(context: Context): Set<String> =
    NumberStore.getSyncedWhitelistNumbers(context)

fun setSyncedWhitelistNumbers(context: Context, numbers: Set<String>) {
    NumberStore.syncSyncedWhitelistNumbers(context, numbers)
    invalidateCompiledLookupBundle()
}

fun getSyncedWhitelistNumberNotes(context: Context): Map<String, String> =
    getSerializedMapPref(context, SYNCED_WHITELIST_NUMBER_NOTES_KEY)

fun setSyncedWhitelistNumberNotes(context: Context, notes: Map<String, String>) {
    setSerializedMapPref(context, SYNCED_WHITELIST_NUMBER_NOTES_KEY, notes)
}

fun getSyncedBlockedPatterns(context: Context): Set<String> =
    getPrefs(context).getStringSet(SYNCED_PATTERN_RULES_KEY, emptySet()) ?: emptySet()

fun setSyncedBlockedPatterns(context: Context, patterns: Set<String>) {
    getPrefs(context).edit { putStringSet(SYNCED_PATTERN_RULES_KEY, patterns) }
    invalidateCompiledLookupBundle()
}

fun getSyncedPatternNotes(context: Context): Map<String, String> =
    getSerializedMapPref(context, SYNCED_PATTERN_NOTES_KEY)

fun setSyncedPatternNotes(context: Context, notes: Map<String, String>) {
    setSerializedMapPref(context, SYNCED_PATTERN_NOTES_KEY, notes)
}

// ...scraper-related preferences removed...

fun shouldBlockNonContacts(context: Context): Boolean =
    getBooleanPref(context, "pref_block_non_contacts", false)

fun shouldShowNotification(context: Context): Boolean =
    getBooleanPref(context, "pref_show_notification", true)

fun shouldMuteInsteadOfBlocking(context: Context): Boolean =
    getBooleanPref(context, "pref_mute_instead_of_block", false)

fun isPatternBlockingEnabled(context: Context): Boolean =
    getBooleanPref(context, "pref_enable_pattern_blocking", false)

fun getBlockedPatterns(context: Context): Set<String> {
    return parsePatternList(getStringPref(context, "pref_pattern_list"))
}

fun getEffectiveBlockedPatterns(context: Context): Set<String> {
    return getBlockedPatterns(context) + getSyncedBlockedPatterns(context)
}

fun setBlockedPatterns(context: Context, patterns: Set<String>) {
    setStringPref(context, "pref_pattern_list", patterns.joinToString("\n"))
}

private fun parseSerializedMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
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

private fun serializeMap(notes: Map<String, String>): String =
    notes.entries.joinToString("\n") { "${it.key}\t${it.value}" }

private fun getSerializedMapPref(context: Context, key: String): Map<String, String> =
    parseSerializedMap(getStringPref(context, key))

private fun setSerializedMapPref(context: Context, key: String, notes: Map<String, String>) {
    setStringPref(context, key, serializeMap(notes))
}

fun getPatternNotes(context: Context): Map<String, String> {
    val local = getSerializedMapPref(context, PATTERN_NOTES_KEY)
    return local + getSyncedPatternNotes(context)
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

fun getBlockedNumberNotes(context: Context): Map<String, String> =
    getSerializedMapPref(context, BLOCK_NUMBER_NOTES_KEY) + getSyncedBlockedNumberNotes(context)

fun setBlockedNumberNotes(context: Context, notes: Map<String, String>) {
    setSerializedMapPref(context, BLOCK_NUMBER_NOTES_KEY, notes)
}

fun setBlockedNumberNote(context: Context, number: String, note: String) {
    val current = getSerializedMapPref(context, BLOCK_NUMBER_NOTES_KEY).toMutableMap()
    if (note.isBlank()) {
        current.remove(number)
    } else {
        current[number] = note
    }
    setBlockedNumberNotes(context, current)
}

fun getWhitelistNumberNotes(context: Context): Map<String, String> =
    getSerializedMapPref(context, WHITELIST_NUMBER_NOTES_KEY) + getSyncedWhitelistNumberNotes(context)

fun setWhitelistNumberNotes(context: Context, notes: Map<String, String>) {
    setSerializedMapPref(context, WHITELIST_NUMBER_NOTES_KEY, notes)
}

fun setWhitelistNumberNote(context: Context, number: String, note: String) {
    val current = getSerializedMapPref(context, WHITELIST_NUMBER_NOTES_KEY).toMutableMap()
    if (note.isBlank()) {
        current.remove(number)
    } else {
        current[number] = note
    }
    setWhitelistNumberNotes(context, current)
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
    NumberStore.addLocalBlockedNumber(context, number)
    invalidateCompiledLookupBundle()
}

fun saveSpamNumber(context: Context, number: String, note: String) {
    saveSpamNumber(context, number)
    if (note.isNotBlank()) {
        setBlockedNumberNote(context, number, note)
    }
}

fun setBlockedNumbers(context: Context, numbers: Set<String>) {
    NumberStore.replaceLocalBlockedNumbers(context, numbers)
    invalidateCompiledLookupBundle()
}

/**
 * Removes a phone number from the spam list in SharedPreferences by removing it from the blocked numbers set.
 *
 * @param context The context for accessing resources.
 * @param number The phone number to be removed from the spam list.
 */
fun removeSpamNumber(context: Context, number: String) {
    NumberStore.removeLocalBlockedNumber(context, number)
    invalidateCompiledLookupBundle()
    setBlockedNumberNote(context, number, "")
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
    NumberStore.addLocalWhitelistNumber(context, number)
    invalidateCompiledLookupBundle()
}

fun addNumberToWhitelist(context: Context, number: String, note: String) {
    addNumberToWhitelist(context, number)
    if (note.isNotBlank()) {
        setWhitelistNumberNote(context, number, note)
    }
}

/**
 * Removes a phone number from the whitelist in SharedPreferences by removing it from the whitelist numbers set.
 *
 * @param context The context for accessing resources.
 * @param number The phone number to be removed from the whitelist.
 */
fun removeWhitelistNumber(context: Context, number: String) {
    NumberStore.removeLocalWhitelistNumber(context, number)
    invalidateCompiledLookupBundle()
    setWhitelistNumberNote(context, number, "")
}

fun getBlockedNumbers(context: Context): Set<String> {
    return NumberStore.getLocalBlockedNumbers(context)
}

fun getEffectiveBlockedNumbers(context: Context): Set<String> {
    return NumberStore.getEffectiveBlockedNumbers(context)
}

fun getWhitelistNumbers(context: Context): Set<String> {
    return NumberStore.getLocalWhitelistNumbers(context)
}

fun getEffectiveWhitelistNumbers(context: Context): Set<String> {
    return NumberStore.getEffectiveWhitelistNumbers(context)
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
    val normalizedNumber = normalizePhoneNumber(number)
    if (normalizedNumber.isEmpty()) {
        return false
    }

    return getCompiledLookupBundle(context).blockedNumberMatcher.matches(normalizedNumber)
}

fun isNumberWhitelisted(context: Context, number: String): Boolean {
    val normalizedNumber = normalizePhoneNumber(number)
    if (normalizedNumber.isEmpty()) {
        return false
    }

    return getCompiledLookupBundle(context).whitelistMatcher.matches(normalizedNumber)
}

fun findMatchedPatternForNumber(context: Context, number: String): String? {
    val normalizedNumber = normalizePhoneNumber(number)
    if (normalizedNumber.isEmpty()) {
        return null
    }

    return getCompiledLookupBundle(context).patternRuleMatcher.findMatch(normalizedNumber)
}

fun classifyNumbers(context: Context, numbers: Collection<String>): Map<String, NumberClassification> {
    if (numbers.isEmpty()) {
        return emptyMap()
    }

    val bundle = getCompiledLookupBundle(context)
    val result = LinkedHashMap<String, NumberClassification>(numbers.size)
    numbers.forEach { number ->
        if (result.containsKey(number)) {
            return@forEach
        }

        val normalizedNumber = normalizePhoneNumber(number)
        if (normalizedNumber.isEmpty()) {
            result[number] = NumberClassification(
                isBlocked = false,
                isWhitelisted = false,
                matchedPattern = null
            )
            return@forEach
        }

        val isWhitelisted = bundle.whitelistMatcher.matches(normalizedNumber)
        val isBlocked = !isWhitelisted && bundle.blockedNumberMatcher.matches(normalizedNumber)
        val matchedPattern =
            if (isWhitelisted || isBlocked) {
                null
            } else {
                bundle.patternRuleMatcher.findMatch(normalizedNumber)
            }

        result[number] = NumberClassification(
            isBlocked = isBlocked,
            isWhitelisted = isWhitelisted,
            matchedPattern = matchedPattern
        )
    }
    return result
}

fun isUpdateCheckEnabled(context: Context): Boolean =
    getBooleanPref(context, "pref_enable_update_check", true)
