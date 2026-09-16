package com.example.androidapp4

import android.media.AudioAttributes
import android.media.MediaPlayer
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

/** Main screen for the SuperPodcast networking assignment. */
class MainActivity : AppCompatActivity() {

    data class Podcast(
        val title: String,
        val artist: String,
        val collectionId: Long
    ) {
        override fun toString(): String = "$title\n$artist"
    }

    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var longTitleCheckBox: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var podcastListView: ListView
    private lateinit var selectedPodcastTextView: TextView
    private lateinit var subscribeButton: Button
    private lateinit var playButton: Button

    private val executor = Executors.newSingleThreadExecutor()
    private var selectedPodcast: Podcast? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchEditText = findViewById(R.id.searchEditText)
        searchButton = findViewById(R.id.searchButton)
        longTitleCheckBox = findViewById(R.id.longTitleCheckBox)
        progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView)
        podcastListView = findViewById(R.id.podcastListView)
        selectedPodcastTextView = findViewById(R.id.selectedPodcastTextView)
        subscribeButton = findViewById(R.id.subscribeButton)
        playButton = findViewById(R.id.playButton)

        searchButton.setOnClickListener {
            val term = searchEditText.text.toString().trim()
            if (term.isEmpty()) {
                statusTextView.text = "Please enter a podcast name or topic."
            } else {
                searchPodcasts(term)
            }
        }

        // A result must be selected before the subscription/playback actions are enabled.
        podcastListView.setOnItemClickListener { parent, _, position, _ ->
            selectedPodcast = parent.getItemAtPosition(position) as Podcast
            val podcast = selectedPodcast ?: return@setOnItemClickListener
            selectedPodcastTextView.text = "Selected: ${podcast.title}"
            subscribeButton.isEnabled = true
            playButton.isEnabled = true
            updateSubscribeButton(podcast)
        }

        subscribeButton.setOnClickListener {
            selectedPodcast?.let { toggleSubscription(it) }
        }

        playButton.setOnClickListener {
            selectedPodcast?.let { loadLatestEpisode(it) }
        }
    }

    /** Requests podcast search results from the iTunes Search API on a background thread. */
    private fun searchPodcasts(term: String) {
        setLoading(true)
        executor.execute {
            try {
                val encodedTerm = URLEncoder.encode(term, "UTF-8")
                val url = URL("https://itunes.apple.com/search?term=$encodedTerm&media=podcast&entity=podcast&limit=25")
                val jsonText = downloadText(url)
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

    /** Converts the JSON response into Podcast objects and applies the unusual word-count filter. */
    private fun parsePodcastResults(jsonText: String): List<Podcast> {
        val results = JSONObject(jsonText).getJSONArray("results")
        val podcasts = mutableListOf<Podcast>()

        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val title = item.optString("collectionName", "Unknown Podcast")
            val artist = item.optString("artistName", "Unknown Artist")
            val collectionId = item.optLong("collectionId", 0L)
            val wordCount = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

            if (collectionId != 0L && (!longTitleCheckBox.isChecked || wordCount >= 4)) {
                podcasts.add(Podcast(title, artist, collectionId))
            }
        }
        return podcasts
    }

    private fun showResults(podcasts: List<Podcast>) {
        podcastListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, podcasts)
        statusTextView.text = if (podcasts.isEmpty()) "No podcasts matched this search/filter." else "${podcasts.size} podcasts found - tap one to select it."
    }

    /** Saves subscriptions locally so they are still available after the app is restarted. */
    private fun toggleSubscription(podcast: Podcast) {
        val preferences = getSharedPreferences("subscriptions", MODE_PRIVATE)
        val key = podcast.collectionId.toString()
        val subscribed = preferences.getBoolean(key, false)
        preferences.edit().putBoolean(key, !subscribed).apply()
        updateSubscribeButton(podcast)
        statusTextView.text = if (!subscribed) "Subscribed to ${podcast.title}" else "Unsubscribed from ${podcast.title}"
    }

    private fun updateSubscribeButton(podcast: Podcast) {
        val subscribed = getSharedPreferences("subscriptions", MODE_PRIVATE)
            .getBoolean(podcast.collectionId.toString(), false)
        subscribeButton.text = if (subscribed) "Unsubscribe" else "Subscribe"
    }

    /** Looks up recent episodes for the selected podcast and attempts to play the first audio preview returned. */
    private fun loadLatestEpisode(podcast: Podcast) {
        setLoading(true)
        statusTextView.text = "Loading latest episode..."

        executor.execute {
            try {
                val url = URL("https://itunes.apple.com/lookup?id=${podcast.collectionId}&entity=podcastEpisode&limit=5")
                val jsonText = downloadText(url)
                val results = JSONObject(jsonText).getJSONArray("results")
                var audioUrl: String? = null
                var episodeTitle = "Latest episode"

                for (index in 0 until results.length()) {
                    val item = results.getJSONObject(index)
                    if (item.optString("wrapperType") == "podcastEpisode") {
                        val candidate = item.optString("episodeUrl")
                        if (candidate.isNotBlank()) {
                            audioUrl = candidate
                            episodeTitle = item.optString("trackName", episodeTitle)
                            break
                        }
                    }
                }

                val finalAudioUrl = audioUrl
                val finalTitle = episodeTitle
                runOnUiThread {
                    setLoading(false)
                    if (finalAudioUrl == null) {
                        statusTextView.text = "No playable episode was returned for this podcast."
                    } else {
                        playAudio(finalAudioUrl, finalTitle)
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    setLoading(false)
                    statusTextView.text = "Could not load an episode for this podcast."
                }
            }
        }
    }

    /** Uses Android MediaPlayer for simple streaming playback. */
    private fun playAudio(audioUrl: String, episodeTitle: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(audioUrl)
            setOnPreparedListener {
                it.start()
                statusTextView.text = "Playing: $episodeTitle"
                playButton.text = "Playing..."
            }
            setOnCompletionListener {
                playButton.text = "Play Latest"
                statusTextView.text = "Episode finished."
            }
            setOnErrorListener { _, _, _ ->
                playButton.text = "Play Latest"
                statusTextView.text = "This episode could not be played."
                true
            }
            prepareAsync()
        }
    }

    /** Small helper used by both iTunes network requests. */
    private fun downloadText(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        searchButton.isEnabled = !loading
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        executor.shutdown()
        super.onDestroy()
    }
}
