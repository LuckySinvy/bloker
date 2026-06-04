package com.addev.listaspam.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.preference.PreferenceManager

private const val STORE_DB_NAME = "number_store.db"
private const val STORE_DB_VERSION = 2
private const val STORE_TABLE_NUMBERS = "numbers"
private const val STORE_SCOPE_LOCAL = "local"
private const val STORE_SCOPE_SYNCED = "synced"
private const val STORE_LIST_BLOCKED = "blocked"
private const val STORE_LIST_WHITELIST = "whitelist"
private const val STORE_MIGRATION_KEY = "pref_number_store_migrated_v1"
private const val STORE_SYNCED_BLOCK_NUMBERS_KEY = "pref_synced_block_numbers"
private const val STORE_SYNCED_WHITELIST_NUMBERS_KEY = "pref_synced_whitelist_numbers"

private data class StoredNumberRow(
    val originalNumber: String,
    val normalizedNumber: String,
    val scope: String
)

data class NumberStoreListItem(
    val number: String,
    val scope: String
)

data class NumberStorePage(
    val totalCount: Int,
    val items: List<NumberStoreListItem>
)

object NumberStore {
    private val migrationLock = Any()

    fun getLocalBlockedNumbers(context: Context): Set<String> =
        getNumbers(context, STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL)

    fun getSyncedBlockedNumbers(context: Context): Set<String> =
        getNumbers(context, STORE_LIST_BLOCKED, STORE_SCOPE_SYNCED)

    fun getLocalWhitelistNumbers(context: Context): Set<String> =
        getNumbers(context, STORE_LIST_WHITELIST, STORE_SCOPE_LOCAL)

    fun getSyncedWhitelistNumbers(context: Context): Set<String> =
        getNumbers(context, STORE_LIST_WHITELIST, STORE_SCOPE_SYNCED)

    fun getEffectiveBlockedNumbers(context: Context): Set<String> {
        val localBlocked = LinkedHashMap<String, String>()
        loadRows(context, STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL).forEach { row ->
            localBlocked.putIfAbsent(row.normalizedNumber, row.originalNumber)
        }

        val effectiveWhitelist = getEffectiveWhitelistMap(context)
        val result = LinkedHashMap(localBlocked)
        loadRows(context, STORE_LIST_BLOCKED, STORE_SCOPE_SYNCED).forEach { row ->
            if (!effectiveWhitelist.containsKey(row.normalizedNumber)) {
                result.putIfAbsent(row.normalizedNumber, row.originalNumber)
            }
        }
        return result.values.toSet()
    }

    fun getEffectiveWhitelistNumbers(context: Context): Set<String> =
        getEffectiveWhitelistMap(context).values.toSet()

    fun getEffectiveBlockedCount(context: Context): Int =
        queryForInt(
            context,
            """
            WITH effective_whitelist AS (
                SELECT DISTINCT w.normalized_number
                FROM $STORE_TABLE_NUMBERS w
                WHERE w.list_type = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM $STORE_TABLE_NUMBERS b
                      WHERE b.list_type = ?
                        AND b.scope = ?
                        AND b.normalized_number = w.normalized_number
                  )
            )
            SELECT COUNT(*) FROM (
                SELECT normalized_number
                FROM $STORE_TABLE_NUMBERS
                WHERE list_type = ?
                  AND scope = ?
                UNION
                SELECT normalized_number
                FROM $STORE_TABLE_NUMBERS
                WHERE list_type = ?
                  AND scope = ?
                  AND normalized_number NOT IN (SELECT normalized_number FROM effective_whitelist)
            )
            """.trimIndent(),
            arrayOf(
                STORE_LIST_WHITELIST,
                STORE_LIST_BLOCKED,
                STORE_SCOPE_LOCAL,
                STORE_LIST_BLOCKED,
                STORE_SCOPE_LOCAL,
                STORE_LIST_BLOCKED,
                STORE_SCOPE_SYNCED
            )
        )

    fun getEffectiveWhitelistCount(context: Context): Int =
        queryForInt(
            context,
            """
            SELECT COUNT(DISTINCT w.normalized_number)
            FROM $STORE_TABLE_NUMBERS w
            WHERE w.list_type = ?
              AND NOT EXISTS (
                  SELECT 1
                  FROM $STORE_TABLE_NUMBERS b
                  WHERE b.list_type = ?
                    AND b.scope = ?
                    AND b.normalized_number = w.normalized_number
              )
            """.trimIndent(),
            arrayOf(STORE_LIST_WHITELIST, STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL)
        )

    fun getPagedNumbers(
        context: Context,
        listType: String,
        sourceFilter: String,
        keyword: String,
        limit: Int,
        offset: Int
    ): NumberStorePage {
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val normalizedKeyword = normalizeNumberForStore(keyword.trim())

        val totalCount = getPagedNumberCount(context, listType, sourceFilter, normalizedKeyword)
        if (totalCount == 0 || safeOffset >= totalCount) {
            return NumberStorePage(totalCount = totalCount, items = emptyList())
        }

        val query = buildPagedNumberQuery(
            listType,
            sourceFilter,
            normalizedKeyword,
            safeLimit,
            safeOffset
        )
        val items = mutableListOf<NumberStoreListItem>()
        getHelper(context).readableDatabase.rawQuery(query.sql, query.args).use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow("original_number")
            val scopeIndex = cursor.getColumnIndexOrThrow("scope")
            while (cursor.moveToNext()) {
                items += NumberStoreListItem(
                    number = cursor.getString(numberIndex),
                    scope = cursor.getString(scopeIndex)
                )
            }
        }

        return NumberStorePage(totalCount = totalCount, items = items)
    }

    fun replaceLocalBlockedNumbers(context: Context, numbers: Set<String>) {
        replaceNumbers(context, STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL, numbers)
    }

    fun replaceSyncedBlockedNumbers(context: Context, numbers: Set<String>) {
        replaceNumbers(context, STORE_LIST_BLOCKED, STORE_SCOPE_SYNCED, numbers)
    }

    fun replaceSyncedWhitelistNumbers(context: Context, numbers: Set<String>) {
        replaceNumbers(context, STORE_LIST_WHITELIST, STORE_SCOPE_SYNCED, numbers)
    }

    fun syncSyncedBlockedNumbers(context: Context, numbers: Set<String>) {
        syncNumbers(context, STORE_LIST_BLOCKED, STORE_SCOPE_SYNCED, numbers)
    }

    fun syncSyncedWhitelistNumbers(context: Context, numbers: Set<String>) {
        syncNumbers(context, STORE_LIST_WHITELIST, STORE_SCOPE_SYNCED, numbers)
    }

    fun addLocalBlockedNumber(context: Context, number: String) {
        upsertNumber(context, STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL, number)
    }

    fun removeLocalBlockedNumber(context: Context, number: String) {
        deleteNumber(context, STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL, number)
    }

    fun addLocalWhitelistNumber(context: Context, number: String) {
        upsertNumber(context, STORE_LIST_WHITELIST, STORE_SCOPE_LOCAL, number)
    }

    fun removeLocalWhitelistNumber(context: Context, number: String) {
        deleteNumber(context, STORE_LIST_WHITELIST, STORE_SCOPE_LOCAL, number)
    }

    private fun getEffectiveWhitelistMap(context: Context): LinkedHashMap<String, String> {
        val localBlockedNormalized = loadRows(context, STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL)
            .mapTo(HashSet()) { it.normalizedNumber }

        val whitelist = LinkedHashMap<String, String>()
        loadRows(context, STORE_LIST_WHITELIST, null).forEach { row ->
            if (!localBlockedNormalized.contains(row.normalizedNumber)) {
                whitelist.putIfAbsent(row.normalizedNumber, row.originalNumber)
            }
        }
        return whitelist
    }

    private data class SqlQuery(
        val sql: String,
        val args: Array<String>
    )

    private fun getPagedNumberCount(
        context: Context,
        listType: String,
        sourceFilter: String,
        normalizedKeyword: String
    ): Int {
        val query = buildPagedNumberCountQuery(listType, sourceFilter, normalizedKeyword)
        return queryForInt(context, query.sql, query.args)
    }

    private fun buildPagedNumberQuery(
        listType: String,
        sourceFilter: String,
        normalizedKeyword: String,
        limit: Int,
        offset: Int
    ): SqlQuery {
        val pagingArgs = arrayOf(limit.toString(), offset.toString())
        val base = buildPagedNumberBaseQuery(listType, sourceFilter, normalizedKeyword)
        return SqlQuery(
            sql = "${base.sql} ORDER BY original_number LIMIT ? OFFSET ?",
            args = base.args + pagingArgs
        )
    }

    private fun buildPagedNumberCountQuery(
        listType: String,
        sourceFilter: String,
        normalizedKeyword: String
    ): SqlQuery {
        val base = buildPagedNumberBaseQuery(listType, sourceFilter, normalizedKeyword)
        return SqlQuery(
            sql = "SELECT COUNT(*) FROM (${base.sql})",
            args = base.args
        )
    }

    private fun buildPagedNumberBaseQuery(
        listType: String,
        sourceFilter: String,
        normalizedKeyword: String
    ): SqlQuery {
        val keywordFilter = if (normalizedKeyword.isEmpty()) {
            ""
        } else {
            " AND normalized_number LIKE ?"
        }
        val keywordArgs = if (normalizedKeyword.isEmpty()) {
            emptyArray()
        } else {
            arrayOf("%$normalizedKeyword%")
        }

        return when (listType) {
            "whitelist" -> when (sourceFilter) {
                "local" -> SqlQuery(
                    sql =
                        """
                        WITH local_blocked AS (
                            SELECT normalized_number
                            FROM $STORE_TABLE_NUMBERS
                            WHERE list_type = ? AND scope = ?
                        )
                        SELECT original_number, scope, normalized_number
                        FROM $STORE_TABLE_NUMBERS
                        WHERE list_type = ?
                          AND scope = ?
                          AND normalized_number NOT IN (SELECT normalized_number FROM local_blocked)
                          $keywordFilter
                        """.trimIndent(),
                    args = arrayOf(
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_LOCAL,
                        STORE_LIST_WHITELIST,
                        STORE_SCOPE_LOCAL
                    ) + keywordArgs
                )

                "synced" -> SqlQuery(
                    sql =
                        """
                        WITH local_blocked AS (
                            SELECT normalized_number
                            FROM $STORE_TABLE_NUMBERS
                            WHERE list_type = ? AND scope = ?
                        )
                        SELECT original_number, scope, normalized_number
                        FROM $STORE_TABLE_NUMBERS
                        WHERE list_type = ?
                          AND scope = ?
                          AND normalized_number NOT IN (SELECT normalized_number FROM local_blocked)
                          $keywordFilter
                        """.trimIndent(),
                    args = arrayOf(
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_LOCAL,
                        STORE_LIST_WHITELIST,
                        STORE_SCOPE_SYNCED
                    ) + keywordArgs
                )

                else -> SqlQuery(
                    sql =
                        """
                        WITH local_blocked AS (
                            SELECT normalized_number
                            FROM $STORE_TABLE_NUMBERS
                            WHERE list_type = ? AND scope = ?
                        ),
                        local_whitelist AS (
                            SELECT normalized_number, original_number, scope
                            FROM $STORE_TABLE_NUMBERS
                            WHERE list_type = ?
                              AND scope = ?
                              AND normalized_number NOT IN (SELECT normalized_number FROM local_blocked)
                              $keywordFilter
                        ),
                        synced_whitelist AS (
                            SELECT normalized_number, original_number, scope
                            FROM $STORE_TABLE_NUMBERS
                            WHERE list_type = ?
                              AND scope = ?
                              AND normalized_number NOT IN (SELECT normalized_number FROM local_blocked)
                              AND normalized_number NOT IN (SELECT normalized_number FROM local_whitelist)
                              $keywordFilter
                        )
                        SELECT original_number, scope, normalized_number
                        FROM (
                            SELECT original_number, scope, normalized_number FROM local_whitelist
                            UNION ALL
                            SELECT original_number, scope, normalized_number FROM synced_whitelist
                        )
                        """.trimIndent(),
                    args = arrayOf(
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_LOCAL,
                        STORE_LIST_WHITELIST,
                        STORE_SCOPE_LOCAL
                    ) + keywordArgs + arrayOf(
                        STORE_LIST_WHITELIST,
                        STORE_SCOPE_SYNCED
                    ) + keywordArgs
                )
            }

            "spam_library" -> SqlQuery(
                sql =
                    """
                    SELECT original_number, scope, normalized_number
                    FROM $STORE_TABLE_NUMBERS
                    WHERE list_type = ?
                      AND scope = ?
                      $keywordFilter
                    """.trimIndent(),
                args = arrayOf(STORE_LIST_BLOCKED, STORE_SCOPE_SYNCED) + keywordArgs
            )

            else -> when (sourceFilter) {
                "local" -> SqlQuery(
                    sql =
                        """
                        SELECT original_number, scope, normalized_number
                        FROM $STORE_TABLE_NUMBERS
                        WHERE list_type = ?
                          AND scope = ?
                          $keywordFilter
                        """.trimIndent(),
                    args = arrayOf(STORE_LIST_BLOCKED, STORE_SCOPE_LOCAL) + keywordArgs
                )

                "synced" -> SqlQuery(
                    sql =
                        """
                        WITH effective_whitelist AS (
                            SELECT DISTINCT w.normalized_number
                            FROM $STORE_TABLE_NUMBERS w
                            WHERE w.list_type = ?
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM $STORE_TABLE_NUMBERS b
                                  WHERE b.list_type = ?
                                    AND b.scope = ?
                                    AND b.normalized_number = w.normalized_number
                              )
                        )
                        SELECT original_number, scope, normalized_number
                        FROM $STORE_TABLE_NUMBERS
                        WHERE list_type = ?
                          AND scope = ?
                          AND normalized_number NOT IN (SELECT normalized_number FROM effective_whitelist)
                          $keywordFilter
                        """.trimIndent(),
                    args = arrayOf(
                        STORE_LIST_WHITELIST,
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_LOCAL,
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_SYNCED
                    ) + keywordArgs
                )

                else -> SqlQuery(
                    sql =
                        """
                        WITH local_blocked AS (
                            SELECT normalized_number, original_number, scope
                            FROM $STORE_TABLE_NUMBERS
                            WHERE list_type = ?
                              AND scope = ?
                              $keywordFilter
                        ),
                        effective_whitelist AS (
                            SELECT DISTINCT w.normalized_number
                            FROM $STORE_TABLE_NUMBERS w
                            WHERE w.list_type = ?
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM $STORE_TABLE_NUMBERS b
                                  WHERE b.list_type = ?
                                    AND b.scope = ?
                                    AND b.normalized_number = w.normalized_number
                              )
                        ),
                        synced_blocked AS (
                            SELECT normalized_number, original_number, scope
                            FROM $STORE_TABLE_NUMBERS
                            WHERE list_type = ?
                              AND scope = ?
                              AND normalized_number NOT IN (SELECT normalized_number FROM effective_whitelist)
                              AND normalized_number NOT IN (SELECT normalized_number FROM local_blocked)
                              $keywordFilter
                        )
                        SELECT original_number, scope, normalized_number
                        FROM (
                            SELECT original_number, scope, normalized_number FROM local_blocked
                            UNION ALL
                            SELECT original_number, scope, normalized_number FROM synced_blocked
                        )
                        """.trimIndent(),
                    args = arrayOf(
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_LOCAL
                    ) + keywordArgs + arrayOf(
                        STORE_LIST_WHITELIST,
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_LOCAL,
                        STORE_LIST_BLOCKED,
                        STORE_SCOPE_SYNCED
                    ) + keywordArgs
                )
            }
        }
    }

    private fun getNumbers(context: Context, listType: String, scope: String): Set<String> =
        loadRows(context, listType, scope)
            .mapTo(LinkedHashSet()) { it.originalNumber }

    private fun replaceNumbers(
        context: Context,
        listType: String,
        scope: String,
        numbers: Set<String>
    ) {
        ensureMigrated(context)
        val database = getHelper(context).writableDatabase
        database.beginTransaction()
        try {
            database.delete(
                STORE_TABLE_NUMBERS,
                "list_type = ? AND scope = ?",
                arrayOf(listType, scope)
            )
            numbers.forEach { number ->
                insertNumber(database, listType, scope, number)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun syncNumbers(
        context: Context,
        listType: String,
        scope: String,
        numbers: Set<String>
    ) {
        ensureMigrated(context)
        val targetRows = numbers.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { number ->
                val normalized = normalizeNumberForStore(number)
                if (normalized.isEmpty()) {
                    null
                } else {
                    normalized to number
                }
            }
            .distinctBy { it.first }
            .toList()

        val targetNormalized = targetRows.mapTo(HashSet()) { it.first }
        val database = getHelper(context).writableDatabase
        val existingRows = loadRows(context, listType, scope)
        val existingByNormalized = existingRows.associateBy { it.normalizedNumber }
        val existingNormalized = existingRows.mapTo(HashSet()) { it.normalizedNumber }

        val toDelete = existingNormalized - targetNormalized
        val toUpsert = targetRows.filter { (normalized, original) ->
            val existing = existingByNormalized[normalized]
            existing == null || existing.originalNumber != original
        }

        if (toDelete.isEmpty() && toUpsert.isEmpty()) {
            return
        }

        database.beginTransaction()
        try {
            toDelete.forEach { normalized ->
                database.delete(
                    STORE_TABLE_NUMBERS,
                    "list_type = ? AND scope = ? AND normalized_number = ?",
                    arrayOf(listType, scope, normalized)
                )
            }
            toUpsert.forEach { (_, original) ->
                insertNumber(database, listType, scope, original)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun upsertNumber(context: Context, listType: String, scope: String, number: String) {
        ensureMigrated(context)
        val database = getHelper(context).writableDatabase
        insertNumber(database, listType, scope, number)
    }

    private fun deleteNumber(context: Context, listType: String, scope: String, number: String) {
        ensureMigrated(context)
        val normalized = normalizeNumberForStore(number)
        if (normalized.isEmpty()) {
            return
        }

        getHelper(context).writableDatabase.delete(
            STORE_TABLE_NUMBERS,
            "list_type = ? AND scope = ? AND normalized_number = ?",
            arrayOf(listType, scope, normalized)
        )
    }

    private fun insertNumber(
        database: SQLiteDatabase,
        listType: String,
        scope: String,
        number: String
    ) {
        val trimmed = number.trim()
        val normalized = normalizeNumberForStore(trimmed)
        if (trimmed.isEmpty() || normalized.isEmpty()) {
            return
        }

        val values = ContentValues().apply {
            put("list_type", listType)
            put("scope", scope)
            put("normalized_number", normalized)
            put("original_number", trimmed)
            put("updated_at", System.currentTimeMillis())
        }
        database.insertWithOnConflict(
            STORE_TABLE_NUMBERS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun loadRows(
        context: Context,
        listType: String,
        scope: String?
    ): List<StoredNumberRow> {
        ensureMigrated(context)

        val selection = if (scope == null) {
            "list_type = ?"
        } else {
            "list_type = ? AND scope = ?"
        }
        val selectionArgs = if (scope == null) {
            arrayOf(listType)
        } else {
            arrayOf(listType, scope)
        }

        val rows = mutableListOf<StoredNumberRow>()
        getHelper(context).readableDatabase.query(
            STORE_TABLE_NUMBERS,
            arrayOf("original_number", "normalized_number", "scope"),
            selection,
            selectionArgs,
            null,
            null,
            "CASE scope WHEN '$STORE_SCOPE_LOCAL' THEN 0 ELSE 1 END, updated_at DESC"
        ).use { cursor ->
            val originalIndex = cursor.getColumnIndexOrThrow("original_number")
            val normalizedIndex = cursor.getColumnIndexOrThrow("normalized_number")
            val scopeIndex = cursor.getColumnIndexOrThrow("scope")
            while (cursor.moveToNext()) {
                rows += StoredNumberRow(
                    originalNumber = cursor.getString(originalIndex),
                    normalizedNumber = cursor.getString(normalizedIndex),
                    scope = cursor.getString(scopeIndex)
                )
            }
        }
        return rows
    }

    private fun queryForInt(context: Context, sql: String, args: Array<String>): Int {
        ensureMigrated(context)
        getHelper(context).readableDatabase.rawQuery(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun ensureMigrated(context: Context) {
        migrateLegacyDefaultPreferences(context)
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (defaultPrefs.getBoolean(STORE_MIGRATION_KEY, false)) {
            return
        }

        synchronized(migrationLock) {
            if (defaultPrefs.getBoolean(STORE_MIGRATION_KEY, false)) {
                return
            }

            val localPrefs = context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE)
            val database = getHelper(context).writableDatabase
            database.beginTransaction()
            try {
                replaceNumbersInTransaction(
                    database,
                    STORE_LIST_BLOCKED,
                    STORE_SCOPE_LOCAL,
                    localPrefs.getStringSet(BLOCK_NUMBERS_KEY, emptySet()) ?: emptySet()
                )
                replaceNumbersInTransaction(
                    database,
                    STORE_LIST_WHITELIST,
                    STORE_SCOPE_LOCAL,
                    localPrefs.getStringSet(WHITELIST_NUMBERS_KEY, emptySet()) ?: emptySet()
                )
                replaceNumbersInTransaction(
                    database,
                    STORE_LIST_BLOCKED,
                    STORE_SCOPE_SYNCED,
                    defaultPrefs.getStringSet(STORE_SYNCED_BLOCK_NUMBERS_KEY, emptySet()) ?: emptySet()
                )
                replaceNumbersInTransaction(
                    database,
                    STORE_LIST_WHITELIST,
                    STORE_SCOPE_SYNCED,
                    defaultPrefs.getStringSet(STORE_SYNCED_WHITELIST_NUMBERS_KEY, emptySet()) ?: emptySet()
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }

            localPrefs.edit()
                .remove(BLOCK_NUMBERS_KEY)
                .remove(WHITELIST_NUMBERS_KEY)
                .apply()
            defaultPrefs.edit()
                .remove(STORE_SYNCED_BLOCK_NUMBERS_KEY)
                .remove(STORE_SYNCED_WHITELIST_NUMBERS_KEY)
                .putBoolean(STORE_MIGRATION_KEY, true)
                .apply()
        }
    }

    private fun replaceNumbersInTransaction(
        database: SQLiteDatabase,
        listType: String,
        scope: String,
        numbers: Set<String>
    ) {
        database.delete(
            STORE_TABLE_NUMBERS,
            "list_type = ? AND scope = ?",
            arrayOf(listType, scope)
        )
        numbers.forEach { number ->
            insertNumber(database, listType, scope, number)
        }
    }

    private fun normalizeNumberForStore(number: String): String = buildString(number.length) {
        number.forEach { char ->
            if (char.isDigit()) {
                append(char)
            }
        }
    }

    private fun getHelper(context: Context): NumberStoreDatabaseHelper =
        NumberStoreDatabaseHelper.getInstance(context.applicationContext)
}

private class NumberStoreDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, STORE_DB_NAME, null, STORE_DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $STORE_TABLE_NUMBERS (
                list_type TEXT NOT NULL,
                scope TEXT NOT NULL,
                normalized_number TEXT NOT NULL,
                original_number TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (list_type, scope, normalized_number)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_numbers_lookup
            ON $STORE_TABLE_NUMBERS (list_type, scope, normalized_number)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_numbers_order
            ON $STORE_TABLE_NUMBERS (list_type, scope, original_number)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_numbers_keyword
            ON $STORE_TABLE_NUMBERS (list_type, normalized_number)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_numbers_order
                ON $STORE_TABLE_NUMBERS (list_type, scope, original_number)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_numbers_keyword
                ON $STORE_TABLE_NUMBERS (list_type, normalized_number)
                """.trimIndent()
            )
        }
    }

    companion object {
        @Volatile
        private var instance: NumberStoreDatabaseHelper? = null

        fun getInstance(context: Context): NumberStoreDatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: NumberStoreDatabaseHelper(context).also { instance = it }
            }
    }
}
