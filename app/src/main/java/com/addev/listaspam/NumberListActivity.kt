package com.addev.listaspam

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.addev.listaspam.util.getBlockedNumbers
import com.addev.listaspam.util.getWhitelistNumbers
import com.google.android.material.appbar.MaterialToolbar

class NumberListActivity : AppCompatActivity(), NumberListAdapter.OnNumberChangedListener {
    companion object {
        const val EXTRA_LIST_TYPE = "list_type"
        const val TYPE_BLOCKED = "blocked"
        const val TYPE_WHITELIST = "whitelist"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyTextView: TextView
    private lateinit var adapter: NumberListAdapter
    private lateinit var listType: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_number_list)

        listType = intent.getStringExtra(EXTRA_LIST_TYPE) ?: TYPE_BLOCKED
        val toolbar: MaterialToolbar = findViewById(R.id.numberListToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.numberRecyclerView)
        emptyTextView = findViewById(R.id.emptyTextView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = NumberListAdapter(this, listType)
        adapter.setOnNumberChangedListener(this)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    private fun reloadData() {
        val numbers = when (listType) {
            TYPE_WHITELIST -> getWhitelistNumbers(this).sorted()
            else -> getBlockedNumbers(this).sorted()
        }
        adapter.submit(numbers)

        val title = if (listType == TYPE_WHITELIST) {
            getString(R.string.whitelist_numbers_page_title)
        } else {
            getString(R.string.blocked_numbers_page_title)
        }
        val subtitle = if (listType == TYPE_WHITELIST) {
            getString(R.string.number_list_subtitle_whitelist, numbers.size)
        } else {
            getString(R.string.number_list_subtitle_blocked, numbers.size)
        }
        supportActionBar?.title = title
        findViewById<MaterialToolbar>(R.id.numberListToolbar).title = title
        findViewById<MaterialToolbar>(R.id.numberListToolbar).subtitle = subtitle

        if (numbers.isEmpty()) {
            emptyTextView.visibility = android.view.View.VISIBLE
            recyclerView.visibility = android.view.View.GONE
            emptyTextView.text = if (listType == TYPE_WHITELIST) {
                getString(R.string.number_list_empty_whitelist)
            } else {
                getString(R.string.number_list_empty_blocked)
            }
        } else {
            emptyTextView.visibility = android.view.View.GONE
            recyclerView.visibility = android.view.View.VISIBLE
        }
    }

    override fun onNumberChanged() {
        reloadData()
    }
}

