package com.addev.listaspam

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.RadioGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.addev.listaspam.util.NumberStore
import com.addev.listaspam.util.NumberStorePage
import com.addev.listaspam.util.getBlockedNumberNotes
import com.addev.listaspam.util.getSyncedBlockedNumberNotes
import com.addev.listaspam.util.getSyncedWhitelistNumberNotes
import com.addev.listaspam.util.getWhitelistNumberNotes
import com.google.android.material.appbar.MaterialToolbar
import kotlin.concurrent.thread

class NumberListActivity : AppCompatActivity(), NumberListAdapter.OnNumberChangedListener {
    companion object {
        const val EXTRA_LIST_TYPE = "list_type"
        const val TYPE_BLOCKED = "blocked"
        const val TYPE_SPAM_LIBRARY = "spam_library"
        const val TYPE_WHITELIST = "whitelist"
        private const val FILTER_ALL = "all"
        private const val FILTER_LOCAL = "local"
        private const val FILTER_SYNCED = "synced"
        private const val PAGE_SIZE = 200
        private const val SEARCH_DEBOUNCE_MS = 300L
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyTextView: TextView
    private lateinit var searchInput: EditText
    private lateinit var clearSearchButton: ImageButton
    private lateinit var adapter: NumberListAdapter
    private lateinit var listType: String
    private var sourceFilter: String = FILTER_ALL
    private var searchKeyword: String = ""
    private lateinit var layoutManager: LinearLayoutManager
    private var totalCount: Int = 0
    private var currentOffset: Int = 0
    private var isLoadingPage: Boolean = false
    private var reachedEnd: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null
    @Volatile
    private var loadGeneration: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_number_list)
        setupWindowInsets()

        listType = intent.getStringExtra(EXTRA_LIST_TYPE) ?: TYPE_BLOCKED
        val toolbar: MaterialToolbar = findViewById(R.id.numberListToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.numberRecyclerView)
        emptyTextView = findViewById(R.id.emptyTextView)
        searchInput = findViewById(R.id.numberSearchInput)
        clearSearchButton = findViewById(R.id.clearSearchButton)
        layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        searchInput.inputType = InputType.TYPE_CLASS_PHONE

        findViewById<RadioGroup>(R.id.numberSourceFilterGroup).setOnCheckedChangeListener { _, checkedId ->
            sourceFilter = when (checkedId) {
                R.id.filterLocalNumbers -> FILTER_LOCAL
                R.id.filterSyncedNumbers -> FILTER_SYNCED
                else -> FILTER_ALL
            }
            resetAndReload()
        }

        adapter = NumberListAdapter(this, listType)
        adapter.setOnNumberChangedListener(this)
        recyclerView.adapter = adapter
        clearSearchButton.visibility = android.view.View.GONE
        clearSearchButton.setOnClickListener {
            searchInput.setText("")
        }
        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val nextKeyword = s?.toString().orEmpty().trim()
                    clearSearchButton.visibility = if (nextKeyword.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                    if (nextKeyword == searchKeyword) {
                        return
                    }
                    searchDebounceRunnable?.let(mainHandler::removeCallbacks)
                    searchDebounceRunnable = Runnable {
                        searchKeyword = nextKeyword
                        resetAndReload()
                    }.also { runnable ->
                        mainHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
                    }
                }
            }
        )
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0 || isLoadingPage || reachedEnd) {
                        return
                    }
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= adapter.itemCount - 20) {
                        loadNextPage()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        resetAndReload()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.numberListRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun resetAndReload() {
        loadGeneration += 1
        currentOffset = 0
        totalCount = 0
        reachedEnd = false
        isLoadingPage = false
        adapter.submit(emptyList())
        renderLoadingState()
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoadingPage || reachedEnd) {
            return
        }

        isLoadingPage = true
        val generation = loadGeneration
        val offset = currentOffset
        val listTypeSnapshot = listType
        val sourceFilterSnapshot = sourceFilter
        val searchKeywordSnapshot = searchKeyword
        thread(name = "number-list-page-load") {
            val page = NumberStore.getPagedNumbers(
                this,
                listTypeSnapshot,
                sourceFilterSnapshot,
                searchKeywordSnapshot,
                PAGE_SIZE,
                offset
            )
            val mappedItems = mapPageToItems(page, listTypeSnapshot)

            mainHandler.post {
                if (isFinishing || isDestroyed || generation != loadGeneration) {
                    return@post
                }

                totalCount = page.totalCount
                adapter.setSearchKeyword(searchKeywordSnapshot)
                if (offset == 0) {
                    adapter.submit(mappedItems)
                } else {
                    adapter.append(mappedItems)
                }

                currentOffset = offset + mappedItems.size
                reachedEnd = currentOffset >= totalCount || mappedItems.isEmpty()
                isLoadingPage = false
                renderListState(adapter.itemCount)
            }
        }
    }

    private fun mapPageToItems(page: NumberStorePage, listTypeSnapshot: String): List<NumberListItem> {
        val localBlockedNotes = getBlockedNumberNotes(this)
        val syncedBlockedNotes = getSyncedBlockedNumberNotes(this)
        val localWhitelistNotes = getWhitelistNumberNotes(this)
        val syncedWhitelistNotes = getSyncedWhitelistNumberNotes(this)

        return page.items.map { item ->
            val source = if (item.scope == "synced") NumberSource.SYNCED else NumberSource.LOCAL
            val note = when (listTypeSnapshot) {
                TYPE_WHITELIST -> {
                    if (source == NumberSource.SYNCED) {
                        syncedWhitelistNotes[item.number].orEmpty()
                    } else {
                        localWhitelistNotes[item.number].orEmpty()
                    }
                }
                TYPE_SPAM_LIBRARY -> syncedBlockedNotes[item.number].orEmpty()
                else -> {
                    if (source == NumberSource.SYNCED) {
                        syncedBlockedNotes[item.number].orEmpty()
                    } else {
                        localBlockedNotes[item.number].orEmpty()
                    }
                }
            }
            NumberListItem(
                number = item.number,
                note = note,
                source = source
            )
        }
    }

    private fun renderListState(visibleCount: Int) {
        val title = if (listType == TYPE_WHITELIST) {
            getString(R.string.whitelist_numbers_page_title)
        } else if (listType == TYPE_SPAM_LIBRARY) {
            getString(R.string.spam_library_page_title)
        } else {
            getString(R.string.blocked_numbers_page_title)
        }
        val subtitle = if (listType == TYPE_WHITELIST) {
            getString(R.string.number_list_subtitle_whitelist, totalCount)
        } else if (listType == TYPE_SPAM_LIBRARY) {
            getString(R.string.number_list_subtitle_spam_library, totalCount)
        } else {
            getString(R.string.number_list_subtitle_blocked, totalCount)
        }
        supportActionBar?.title = title
        findViewById<MaterialToolbar>(R.id.numberListToolbar).title = title
        findViewById<MaterialToolbar>(R.id.numberListToolbar).subtitle = subtitle
        findViewById<RadioGroup>(R.id.numberSourceFilterGroup).visibility =
            if (listType == TYPE_SPAM_LIBRARY) android.view.View.GONE else android.view.View.VISIBLE
        searchInput.visibility = android.view.View.VISIBLE

        if (visibleCount == 0) {
            emptyTextView.visibility = android.view.View.VISIBLE
            recyclerView.visibility = android.view.View.GONE
            emptyTextView.text = if (searchKeyword.isNotBlank()) {
                getString(R.string.number_list_empty_search, searchKeyword)
            } else {
                if (listType == TYPE_WHITELIST) {
                    getString(R.string.number_list_empty_whitelist)
                } else if (listType == TYPE_SPAM_LIBRARY) {
                    getString(R.string.number_list_empty_spam_library)
                } else {
                    getString(R.string.number_list_empty_blocked)
                }
            }
        } else {
            emptyTextView.visibility = android.view.View.GONE
            recyclerView.visibility = android.view.View.VISIBLE
        }
    }

    private fun renderLoadingState() {
        emptyTextView.visibility = android.view.View.VISIBLE
        recyclerView.visibility = android.view.View.GONE
        emptyTextView.text = getString(R.string.number_list_loading)
    }

    override fun onNumberChanged() {
        resetAndReload()
    }

    override fun onDestroy() {
        searchDebounceRunnable?.let(mainHandler::removeCallbacks)
        super.onDestroy()
    }
}

data class NumberListItem(
    val number: String,
    val note: String,
    val source: NumberSource
)

enum class NumberSource {
    LOCAL,
    SYNCED
}
