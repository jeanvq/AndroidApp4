package com.example.androidapp4

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
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

    data class Podcast(val title: String, val artist: String, val collectionId: Long) {
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
    private var isPlaying = false
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
            if (term.isEmpty()) statusTextView.text = "Please enter a podcast name or topic."
            else searchPodcasts(term)
        }

        // Changing the selected podcast stops any episode that belonged to the previous selection.
        podcastListView.setOnItemClickListener { parent, _, position, _ ->
            stopPlayback(showMessage = false)
            selectedPodcast = parent.getItemAtPosition(position) as Podcast
            val podcast = selectedPodcast ?: return@setOnItemClickListener
            selectedPodcastTextView.text = "Selected: ${podcast.title}"
            statusTextView.text = "Selected ${podcast.title}"
            subscribeButton.isEnabled = true
            playButton.isEnabled = true
            updateSubscribeButton(podcast)
        }

        subscribeButton.setOnClickListener { selectedPodcast?.let { toggleSubscription(it) } }

        // The same button starts playback and can also stop the current episode.
        playButton.setOnClickListener {
            if (isPlaying) stopPlayback(showMessage = true)
            else selectedPodcast?.let { loadLatestEpisode(it) }
        }
    }

    /** Requests podcast search results from iTunes on a background thread. */
    private fun searchPodcasts(term: String) {
        stopPlayback(showMessage = false)
        setLoading(true)
        executor.execute {
            try {
                val encodedTerm = URLEncoder.encode(term, "UTF-8")
                val jsonText = downloadText(URL("https://itunes.apple.com/search?term=$encodedTerm&media=podcast&entity=podcast&limit=25"))
                val podcasts = parsePodcastResults(jsonText)
                runOnUiThread { showResults(podcasts); setLoading(false) }
            } catch (exception: Exception) {
                runOnUiThread {
                    statusTextView.text = "Could not load podcasts. Check your internet connection."
                    podcastListView.adapter = null
                    setLoading(false)
                }
            }
        }
    }

    /** Converts JSON into Podcast objects and applies the unusual title word-count filter. */
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
        selectedPodcast = null
        selectedPodcastTextView.text = "No podcast selected"
        subscribeButton.isEnabled = false
        playButton.isEnabled = false
        podcastListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, podcasts)
        statusTextView.text = if (podcasts.isEmpty()) "No podcasts matched this search/filter." else "${podcasts.size} podcasts found - tap one to select it."
    }

    /** Stores a simple subscription value locally with SharedPreferences. */
    private fun toggleSubscription(podcast: Podcast) {
        val preferences = getSharedPreferences("subscriptions", MODE_PRIVATE)
        val key = podcast.collectionId.toString()
        val subscribed = preferences.getBoolean(key, false)
        preferences.edit().putBoolean(key, !subscribed).apply()
        updateSubscribeButton(podcast)
        statusTextView.text = if (!subscribed) "Subscribed to ${podcast.title}" else "Unsubscribed from ${podcast.title}"
    }

    private fun updateSubscribeButton(podcast: Podcast) {
        val subscribed = getSharedPreferences("subscriptions", MODE_PRIVATE).getBoolean(podcast.collectionId.toString(), false)
        subscribeButton.text = if (subscribed) "Unsubscribe" else "Subscribe"
    }

    /** Gets recent episodes for the selected podcast and chooses the first audio URL. */
    private fun loadLatestEpisode(podcast: Podcast) {
        setLoading(true)
        playButton.isEnabled = false
        statusTextView.text = "Loading latest episode from ${podcast.title}..."
        executor.execute {
            try {
                val jsonText = downloadText(URL("https://itunes.apple.com/lookup?id=${podcast.collectionId}&entity=podcastEpisode&limit=5"))
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
                    playButton.isEnabled = true
                    if (selectedPodcast?.collectionId != podcast.collectionId) return@runOnUiThread
                    if (finalAudioUrl == null) statusTextView.text = "No playable episode was returned for ${podcast.title}."
                    else playAudio(finalAudioUrl, podcast.title, finalTitle)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    setLoading(false)
                    playButton.isEnabled = true
                    statusTextView.text = "Could not load an episode for ${podcast.title}."
                }
            }
        }
    }

    /** Streams podcast audio with MediaPlayer and keeps the button/status synchronized. */
    private fun playAudio(audioUrl: String, podcastTitle: String, episodeTitle: String) {
        stopPlayback(showMessage = false)

        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        requestAudioFocus(attributes)
        statusTextView.text = "Preparing: $episodeTitle"
        playButton.isEnabled = false

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setVolume(1.0f, 1.0f)
            setDataSource(audioUrl)
            setOnPreparedListener {
                it.start()
                isPlaying = true
                playButton.isEnabled = true
                playButton.text = "Stop"
                statusTextView.text = "Playing $podcastTitle: $episodeTitle"
            }
            setOnCompletionListener {
                isPlaying = false
                playButton.text = "Play Latest"
                statusTextView.text = "Episode finished: $episodeTitle"
                it.release()
                if (mediaPlayer === it) mediaPlayer = null
            }
            setOnErrorListener { player, _, _ ->
                isPlaying = false
                playButton.isEnabled = true
                playButton.text = "Play Latest"
                statusTextView.text = "This episode could not be played."
                player.reset()
                true
            }
            prepareAsync()
        }
    }

    /** Stops and releases the current stream so old playback cannot remain attached to the UI. */
    private fun stopPlayback(showMessage: Boolean) {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
                // Player may still be preparing; releasing it is enough.
            }
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
        if (::playButton.isInitialized) {
            playButton.text = "Play Latest"
            playButton.isEnabled = selectedPodcast != null
        }
        if (showMessage && ::statusTextView.isInitialized) statusTextView.text = "Playback stopped."
    }

    /** Audio focus makes sure podcast sound is routed as normal media audio. */
    private fun requestAudioFocus(attributes: AudioAttributes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    /** Helper used by both iTunes network requests. */
    private fun downloadText(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        return try { connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        searchButton.isEnabled = !loading
    }

    override fun onDestroy() {
        stopPlayback(showMessage = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        executor.shutdown()
        super.onDestroy()
    }
}
