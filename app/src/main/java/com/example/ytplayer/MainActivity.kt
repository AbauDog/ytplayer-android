package com.example.ytplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首頁：預設顯示繼續觀看清單，也可以搜尋、看收藏。
 */
class MainActivity : AppCompatActivity() {

    private enum class ListMode { SEARCH, FAVORITES, CONTINUE_WATCHING }

    private lateinit var adapter: VideoListAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var listTitle: TextView
    private lateinit var repository: PlayerRepository

    private var currentMode = ListMode.CONTINUE_WATCHING
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = PlayerRepository(this)

        val searchInput = findViewById<EditText>(R.id.searchInput)
        val searchButton = findViewById<Button>(R.id.searchButton)
        val menuButton = findViewById<ImageButton>(R.id.menuButton)
        val videoList = findViewById<RecyclerView>(R.id.videoList)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        listTitle = findViewById(R.id.listTitle)

        adapter = VideoListAdapter(
            onClick = { item -> openPlayer(item) },
            onDelete = { item -> deleteCurrentModeItem(item) }
        )
        videoList.layoutManager = LinearLayoutManager(this)
        videoList.adapter = adapter

        searchButton.setOnClickListener { runSearch(searchInput.text.toString()) }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(searchInput.text.toString())
                true
            } else {
                false
            }
        }
        menuButton.setOnClickListener { showMenu(menuButton) }
        swipeRefresh.setOnRefreshListener { reload() }

        loadContinueWatching()
    }

    override fun onResume() {
        super.onResume()
        // 從播放器回來時，續看清單的進度可能已經變了，重新整理
        if (currentMode == ListMode.CONTINUE_WATCHING) {
            loadContinueWatching()
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.menu_continue_watching))
        popup.menu.add(0, 2, 1, getString(R.string.menu_favorites))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { loadContinueWatching(); true }
                2 -> { loadFavorites(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun openPlayer(item: VideoCardData) {
        startActivity(
            Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, item.url)
        )
    }

    private fun reload() {
        when (currentMode) {
            ListMode.SEARCH -> runSearch(lastQuery)
            ListMode.FAVORITES -> loadFavorites()
            ListMode.CONTINUE_WATCHING -> loadContinueWatching()
        }
    }

    private fun runSearch(query: String) {
        if (query.isBlank()) {
            Toast.makeText(this, R.string.toast_empty_search, Toast.LENGTH_SHORT).show()
            return
        }
        currentMode = ListMode.SEARCH
        lastQuery = query
        listTitle.text = getString(R.string.title_search_result)
        showLoading(true)
        lifecycleScope.launch {
            try {
                val results = YoutubeRepository.search(query)
                adapter.submitList(results.map { it.toCardData() }, showDelete = false)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_load_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadFavorites() {
        currentMode = ListMode.FAVORITES
        listTitle.text = getString(R.string.title_favorites)
        showLoading(true)
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { repository.listFavorites() }
            adapter.submitList(results.map { it.toCardData() }, showDelete = true)
            showLoading(false)
        }
    }

    private fun loadContinueWatching() {
        currentMode = ListMode.CONTINUE_WATCHING
        listTitle.text = getString(R.string.title_continue_watching)
        showLoading(true)
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { repository.listWatchHistory() }
            adapter.submitList(results.map { it.toCardData() }, showDelete = true)
            showLoading(false)
        }
    }

    private fun deleteCurrentModeItem(item: VideoCardData) {
        when (currentMode) {
            ListMode.FAVORITES -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { repository.removeFavorite(item.url) }
                loadFavorites()
            }
            ListMode.CONTINUE_WATCHING -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { repository.deleteWatchHistory(item.url) }
                loadContinueWatching()
            }
            ListMode.SEARCH -> Unit
        }
    }

    private fun showLoading(loading: Boolean) {
        swipeRefresh.isRefreshing = false
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
