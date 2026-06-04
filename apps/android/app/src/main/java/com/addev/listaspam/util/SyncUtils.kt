package com.addev.listaspam.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class SyncResult(
    val spamCount: Int,
    val whitelistCount: Int,
    val patternCount: Int,
    val spamAdded: Int,
    val spamRemoved: Int,
    val whitelistAdded: Int,
    val whitelistRemoved: Int,
    val patternAdded: Int,
    val patternRemoved: Int,
    val syncMode: String,
    val payloadBytes: Int,
    val durationMs: Long,
    val previousCursor: Long?,
    val currentCursor: Long?,
    val syncedAt: Long,
    val changed: Boolean
)

data class SyncProgress(
    val stage: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val percent: Int? = null,
    val estimatedRemainingMs: Long? = null
)

object SyncUtils {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    fun syncSelfHostedData(
        context: Context,
        onProgress: ((SyncProgress) -> Unit)? = null
    ): Result<SyncResult> {
        val baseUrl = getSpamBackendBaseUrl(context)
        val lastCursor = getLastSelfHostedSyncCursor(context)
        val requestStartedAt = System.currentTimeMillis()
        val syncUrl = buildString {
            append(baseUrl.trim().removeSuffix("/"))
            append("/api/v1/app-sync?compact=1")
            lastCursor?.let {
                append("&cursor=")
                append(it)
            }
        }
        val requestBuilder = Request.Builder()
            .url(syncUrl)
            .get()

        getLastSelfHostedSyncEtag(context)?.let { etag ->
            requestBuilder.header("If-None-Match", etag)
        }

        val request = requestBuilder.build()

        return try {
            onProgress?.invoke(SyncProgress(stage = "starting"))
            client.newCall(request).execute().use { response ->
                val syncedAt = System.currentTimeMillis()
                response.header("X-App-Sync-Cursor")
                    ?.toLongOrNull()
                    ?.let { setLastSelfHostedSyncCursor(context, it) }
                if (response.code == 304) {
                    setLastSelfHostedSyncAt(context, syncedAt)
                    return Result.success(
                        SyncResult(
                            spamCount = getSyncedSpamCount(context),
                            whitelistCount = getSyncedWhitelistCount(context),
                            patternCount = getSyncedPatternCount(context),
                            spamAdded = 0,
                            spamRemoved = 0,
                            whitelistAdded = 0,
                            whitelistRemoved = 0,
                            patternAdded = 0,
                            patternRemoved = 0,
                            syncMode = "304",
                            payloadBytes = 0,
                            durationMs = syncedAt - requestStartedAt,
                            previousCursor = lastCursor,
                            currentCursor = getLastSelfHostedSyncCursor(context),
                            syncedAt = syncedAt,
                            changed = false
                        )
                    )
                }
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        401, 403 -> "后端拒绝访问，请检查控制台认证配置"
                        404 -> "同步接口不存在，请确认后端版本"
                        500 -> "后端内部错误，请查看服务日志"
                        else -> "同步失败：HTTP ${response.code}"
                    }
                    return Result.failure(IllegalStateException(message))
                }

                val body = readResponseBodyWithProgress(response, requestStartedAt, onProgress)
                onProgress?.invoke(SyncProgress(stage = "applying", percent = 100))
                val json = JSONObject(body)
                val mode = json.optString("mode", "full")
                val version = json.optString("version", "")
                val nextCursor = json.optLong("cursor", -1L).takeIf { it >= 0L }
                response.header("ETag")?.takeIf { it.isNotBlank() }?.let {
                    setLastSelfHostedSyncEtag(context, it)
                }

                if (version.isNotBlank() && version == getLastSelfHostedSyncVersion(context)) {
                    setLastSelfHostedSyncAt(context, syncedAt)
                    return Result.success(
                        SyncResult(
                            spamCount = getSyncedSpamCount(context),
                            whitelistCount = getSyncedWhitelistCount(context),
                            patternCount = getSyncedPatternCount(context),
                            spamAdded = 0,
                            spamRemoved = 0,
                            whitelistAdded = 0,
                            whitelistRemoved = 0,
                            patternAdded = 0,
                            patternRemoved = 0,
                            syncMode = mode,
                            payloadBytes = body.toByteArray(Charsets.UTF_8).size,
                            durationMs = syncedAt - requestStartedAt,
                            previousCursor = lastCursor,
                            currentCursor = getLastSelfHostedSyncCursor(context),
                            syncedAt = syncedAt,
                            changed = false
                        )
                    )
                }

                val previousSpamNumbers = getSyncedBlockedNumbers(context)
                val previousWhitelistNumbers = getSyncedWhitelistNumbers(context)
                val previousPatternRules = getSyncedBlockedPatterns(context)
                val previousSpamNotes = getSyncedBlockedNumberNotes(context)
                val previousWhitelistNotes = getSyncedWhitelistNumberNotes(context)
                val previousPatternNotes = getSyncedPatternNotes(context)

                val spamNumbers = if (mode == "delta") previousSpamNumbers.toMutableSet() else mutableSetOf()
                val whitelistNumbers = if (mode == "delta") previousWhitelistNumbers.toMutableSet() else mutableSetOf()
                val patternRules = if (mode == "delta") previousPatternRules.toMutableSet() else mutableSetOf()
                val spamNotes = if (mode == "delta") previousSpamNotes.toMutableMap() else mutableMapOf()
                val whitelistNotes = if (mode == "delta") previousWhitelistNotes.toMutableMap() else mutableMapOf()
                val patternNotes = if (mode == "delta") previousPatternNotes.toMutableMap() else mutableMapOf()
                val patternArray = json.optJSONArray("pattern_rules")
                if (patternArray != null) {
                    parsePatternRules(patternArray, patternRules, patternNotes)
                }
                val blacklistArray = json.optJSONArray("blacklist_numbers")
                if (blacklistArray != null) {
                    parseSpamNumbers(blacklistArray, spamNumbers, spamNotes)
                } else {
                    val spamArray = json.optJSONArray("spam_numbers")
                    if (spamArray != null) {
                        parseSpamNumbers(spamArray, spamNumbers, spamNotes)
                    }
                }
                val whitelistArray = json.optJSONArray("whitelist_numbers")
                if (whitelistArray != null) {
                    parseSpamNumbers(whitelistArray, whitelistNumbers, whitelistNotes)
                }
                applyDeletedNumbers(json.optJSONArray("deleted_blacklist_numbers"), spamNumbers, spamNotes)
                applyDeletedNumbers(json.optJSONArray("deleted_whitelist_numbers"), whitelistNumbers, whitelistNotes)
                applyDeletedPatterns(json.optJSONArray("deleted_pattern_rules"), patternRules, patternNotes)
                val cleanSpamNumbers = spamNumbers.filter { it.isNotEmpty() }.toSet()
                val cleanWhitelistNumbers = whitelistNumbers.filter { it.isNotEmpty() }.toSet()
                val cleanPatternRules = patternRules.filter { it.isNotEmpty() }.toSet()
                val nextBlockedNumbers = cleanSpamNumbers - cleanWhitelistNumbers

                val spamAdded = (nextBlockedNumbers - previousSpamNumbers).size
                val spamRemoved = (previousSpamNumbers - nextBlockedNumbers).size
                val whitelistAdded = (cleanWhitelistNumbers - previousWhitelistNumbers).size
                val whitelistRemoved = (previousWhitelistNumbers - cleanWhitelistNumbers).size
                val patternAdded = (cleanPatternRules - previousPatternRules).size
                val patternRemoved = (previousPatternRules - cleanPatternRules).size

                setSyncedBlockedNumbers(context, nextBlockedNumbers)
                setSyncedBlockedNumberNotes(context, spamNotes.filterKeys { nextBlockedNumbers.contains(it) })
                setSyncedWhitelistNumbers(context, cleanWhitelistNumbers)
                setSyncedWhitelistNumberNotes(context, whitelistNotes.filterKeys { cleanWhitelistNumbers.contains(it) })
                setSyncedBlockedPatterns(context, cleanPatternRules)
                setSyncedPatternNotes(context, patternNotes.filterKeys { cleanPatternRules.contains(it) })
                setSyncedSpamCount(context, json.optInt("spam_count", cleanSpamNumbers.size))
                setSyncedWhitelistCount(context, json.optInt("whitelist_count", cleanWhitelistNumbers.size))
                setSyncedPatternCount(context, json.optInt("pattern_count", cleanPatternRules.size))
                setLastSelfHostedSyncAt(context, syncedAt)
                nextCursor?.let { setLastSelfHostedSyncCursor(context, it) }
                if (version.isNotBlank()) {
                    setLastSelfHostedSyncVersion(context, version)
                }

                Result.success(
                    SyncResult(
                        spamCount = getSyncedSpamCount(context),
                        whitelistCount = getSyncedWhitelistCount(context),
                        patternCount = getSyncedPatternCount(context),
                        spamAdded = spamAdded,
                        spamRemoved = spamRemoved,
                        whitelistAdded = whitelistAdded,
                        whitelistRemoved = whitelistRemoved,
                        patternAdded = patternAdded,
                        patternRemoved = patternRemoved,
                        syncMode = mode,
                        payloadBytes = body.toByteArray(Charsets.UTF_8).size,
                        durationMs = syncedAt - requestStartedAt,
                        previousCursor = lastCursor,
                        currentCursor = getLastSelfHostedSyncCursor(context),
                        syncedAt = syncedAt,
                        changed = spamAdded > 0 ||
                            spamRemoved > 0 ||
                            whitelistAdded > 0 ||
                            whitelistRemoved > 0 ||
                            patternAdded > 0 ||
                            patternRemoved > 0
                    )
                )
            }
        } catch (error: Exception) {
            Result.failure(
                IllegalStateException(
                    error.message ?: "无法连接后端，请检查地址、端口和网络可达性"
                )
            )
        }
    }

    private fun readResponseBodyWithProgress(
        response: okhttp3.Response,
        requestStartedAt: Long,
        onProgress: ((SyncProgress) -> Unit)?
    ): String {
        val body = response.body
        val totalBytes = response.header("Content-Length")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: body.contentLength().takeIf { it >= 0L }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloadedBytes = 0L
        var lastReportedPercent = -1

        body.byteStream().use { input ->
            ByteArrayOutputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    output.write(buffer, 0, read)
                    downloadedBytes += read

                    val percent = totalBytes?.let {
                        ((downloadedBytes * 100) / it).toInt().coerceIn(0, 100)
                    }
                    if (percent != null) {
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress?.invoke(
                                SyncProgress(
                                    stage = "downloading",
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    percent = percent,
                                    estimatedRemainingMs = run {
                                        val elapsedMs = System.currentTimeMillis() - requestStartedAt
                                        if (downloadedBytes > 0L && elapsedMs > 0L) {
                                            (((totalBytes - downloadedBytes).coerceAtLeast(0L)) * elapsedMs) / downloadedBytes
                                        } else {
                                            null
                                        }
                                    }
                                )
                            )
                        }
                    } else {
                        onProgress?.invoke(
                            SyncProgress(
                                stage = "downloading",
                                downloadedBytes = downloadedBytes,
                                totalBytes = null,
                                percent = null
                            )
                        )
                    }
                }
                return output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun parseSpamNumbers(
        spamArray: JSONArray,
        spamNumbers: MutableSet<String>,
        spamNotes: MutableMap<String, String>
    ) {
        for (index in 0 until spamArray.length()) {
            when (val value = spamArray.opt(index)) {
                is JSONObject -> {
                    val number = value.optString("number").trim()
                    if (number.isNotEmpty()) {
                        spamNumbers.add(number)
                        val notes = value.optString("notes").trim()
                        if (notes.isNotEmpty()) {
                            spamNotes[number] = notes
                        } else {
                            spamNotes.remove(number)
                        }
                    }
                }
                else -> {
                    val number = spamArray.optString(index).trim()
                    if (number.isNotEmpty()) {
                        spamNumbers.add(number)
                    }
                }
            }
        }
    }

    private fun parsePatternRules(
        patternArray: JSONArray,
        patternRules: MutableSet<String>,
        patternNotes: MutableMap<String, String>
    ) {
        for (index in 0 until patternArray.length()) {
            when (val value = patternArray.opt(index)) {
                is JSONObject -> {
                    val pattern = value.optString("pattern").trim()
                    if (pattern.isNotEmpty()) {
                        patternRules.add(pattern)
                        val notes = value.optString("notes").trim()
                        if (notes.isNotEmpty()) {
                            patternNotes[pattern] = notes
                        } else {
                            patternNotes.remove(pattern)
                        }
                    }
                }
                else -> {
                    val pattern = patternArray.optString(index).trim()
                    if (pattern.isNotEmpty()) {
                        patternRules.add(pattern)
                    }
                }
            }
        }
    }

    private fun applyDeletedNumbers(
        deletedArray: JSONArray?,
        numbers: MutableSet<String>,
        notes: MutableMap<String, String>
    ) {
        if (deletedArray == null) return
        for (index in 0 until deletedArray.length()) {
            val number = deletedArray.optString(index).trim()
            if (number.isNotEmpty()) {
                numbers.remove(number)
                notes.remove(number)
            }
        }
    }

    private fun applyDeletedPatterns(
        deletedArray: JSONArray?,
        patterns: MutableSet<String>,
        notes: MutableMap<String, String>
    ) {
        if (deletedArray == null) return
        for (index in 0 until deletedArray.length()) {
            val pattern = deletedArray.optString(index).trim()
            if (pattern.isNotEmpty()) {
                patterns.remove(pattern)
                notes.remove(pattern)
            }
        }
    }
}
