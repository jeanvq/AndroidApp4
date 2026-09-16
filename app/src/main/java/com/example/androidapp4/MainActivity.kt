package com.example.androidapp4

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Main screen for SuperPodcast.
 * This first version focuses on the networking concepts from PodPlay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var longTitleCheckBox: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var podcastListView: ListView

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchEditText = findViewById(R.id.searchEditText)
        searchButton = findViewById(R.id.searchButton)
        longTitleCheckBox = findViewById(R.id.longTitleCheckBox)
        progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView)
        podcastListView = findViewById(R.id.podcastListView)

        searchButton.setOnClickListener {
            val term = searchEditText.text.toString().trim()
            if (term.isEmpty()) {
                statusTextView.text = "Please enter a podcast name or topic."
            } else {
                searchPodcasts(term)
            }
        }
    }

    /**
     * Performs the web request on a background thread so the UI remains responsive.
     * iTunes returns JSON containing podcast search results.
     */
    private fun searchPodcasts(term: String) {
        setLoading(true)

        executor.execute {
            try {
                val encodedTerm = URLEncoder.encode(term, "UTF-8")
                val requestUrl = URL(
                    "https://itunes.apple.com/search?term=$encodedTerm&media=podcast&entity=podcast&limit=25"
                )

                val connection = requestUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val podcasts = parsePodcastResults(jsonText)

                runOnUiThread {
                    showResults(podcasts)
                    setLoading(false)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    statusTextView.text = "Could not load podcasts. Check your internet connection."
                    podcastListView.adapter = null
                    setLoading(false)
                }
            }
        }
    }

    /** Converts the JSON response into simple display strings for this assignment. */
    private fun parsePodcastResults(jsonText: String): List<String> {
        val results = JSONObject(jsonText).getJSONArray("results")
        val podcasts = mutableListOf<String>()

        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val title = item.optString("collectionName", "Unknown Podcast")
            val artist = item.optString("artistName", "Unknown Artist")

            // Unusual search criterion requested by the assignment: title word count.
            val wordCount = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
            if (!longTitleCheckBox.isChecked || wordCount >= 4) {
                podcasts.add("$title\n$artist")
            }
        }

        return podcasts
    }

    private fun showResults(podcasts: List<String>) {
        podcastListView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            podcasts
        )
        statusTextView.text = if (podcasts.isEmpty()) {
            "No podcasts matched this search/filter."
        } else {
            "${podcasts.size} podcasts found"
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        searchButton.isEnabled = !loading
        statusTextView.text = if (loading) "Searching iTunes..." else statusTextView.text
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
