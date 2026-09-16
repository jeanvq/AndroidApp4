package com.example.androidapp4

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    private var player: ExoPlayer? = null
    private var isPlaying = false

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
            if (term.isEmpty()) statusTextView.text = "Please enter a podcast name or topic." else searchPodcasts(term)
        }
        podcastListView.setOnItemClickListener { parent, _, position, _ ->
            stopPlayback(false)
            selectedPodcast = parent.getItemAtPosition(position) as Podcast
            val podcast = selectedPodcast ?: return@setOnItemClickListener
            selectedPodcastTextView.text = "Selected: ${podcast.title}"
            statusTextView.text = "Selected ${podcast.title}"
            subscribeButton.isEnabled = true
            playButton.isEnabled = true
            updateSubscribeButton(podcast)
        }
        subscribeButton.setOnClickListener { selectedPodcast?.let { toggleSubscription(it) } }
        playButton.setOnClickListener {
            if (isPlaying) stopPlayback(true) else selectedPodcast?.let { loadLatestEpisode(it) }
        }
    }

    /** Requests podcast search results from iTunes on a background thread. */
    private fun searchPodcasts(term: String) {
        stopPlayback(false)
        setLoading(true)
        val useLongTitleFilter = longTitleCheckBox.isChecked
        executor.execute {
            try {
                val encodedTerm = URLEncoder.encode(term, "UTF-8")
                val jsonText = downloadText(URL("https://itunes.apple.com/search?term=$encodedTerm&media=podcast&entity=podcast&limit=25"))
                val podcasts = parsePodcastResults(jsonText, useLongTitleFilter)
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
    private fun parsePodcastResults(jsonText: String, useLongTitleFilter: Boolean): List<Podcast> {
        val results = JSONObject(jsonText).getJSONArray("results")
        val podcasts = mutableListOf<Podcast>()
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val title = item.optString("collectionName", "Unknown Podcast")
            val artist = item.optString("artistName", "Unknown Artist")
            val collectionId = item.optLong("collectionId", 0L)
            val wordCount = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
            if (collectionId != 0L && (!useLongTitleFilter || wordCount >= 4)) podcasts.add(Podcast(title, artist, collectionId))
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

    /** Gets recent episodes and records the actual audio URL for troubleshooting. */
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
                Log.d("SuperPodcast", "Episode title: $finalTitle")
                Log.d("SuperPodcast", "Episode audio URL: $finalAudioUrl")
                runOnUiThread {
                    setLoading(false)
                    playButton.isEnabled = true
                    if (selectedPodcast?.collectionId != podcast.collectionId) return@runOnUiThread
                    if (finalAudioUrl == null) statusTextView.text = "No playable episode was returned for ${podcast.title}."
                    else playAudio(finalAudioUrl, podcast.title, finalTitle)
                }
            } catch (exception: Exception) {
                Log.e("SuperPodcast", "Episode lookup failed", exception)
                runOnUiThread {
                    setLoading(false)
                    playButton.isEnabled = true
                    statusTextView.text = "Could not load an episode for ${podcast.title}."
                }
            }
        }
    }

    /** Streams the remote episode and logs the real player/audio state. */
    private fun playAudio(audioUrl: String, podcastTitle: String, episodeTitle: String) {
        stopPlayback(false)
        statusTextView.text = "Preparing: $episodeTitle"
        playButton.isEnabled = false
        Log.d("SuperPodcast", "Starting ExoPlayer with: $audioUrl")

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            exoPlayer.volume = 1f
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d("SuperPodcast", "State=$playbackState playing=${exoPlayer.isPlaying} position=${exoPlayer.currentPosition} duration=${exoPlayer.duration}")
                    when (playbackState) {
                        Player.STATE_READY -> {
                            if (exoPlayer.playWhenReady) {
                                isPlaying = true
                                playButton.isEnabled = true
                                playButton.text = "Stop"
                                val audioGroups = exoPlayer.currentTracks.groups.count { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                                Log.d("SuperPodcast", "READY audioGroups=$audioGroups volume=${exoPlayer.volume}")
                                statusTextView.text = "Playing $podcastTitle: $episodeTitle | audio tracks: $audioGroups"
                            }
                        }
                        Player.STATE_ENDED -> {
                            isPlaying = false
                            playButton.text = "Play Latest"
                            statusTextView.text = "Episode finished: $episodeTitle"
                        }
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    Log.d("SuperPodcast", "onIsPlayingChanged=$playing position=${exoPlayer.currentPosition}")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("SuperPodcast", "Player error: ${error.errorCodeName}", error)
                    isPlaying = false
                    playButton.isEnabled = true
                    playButton.text = "Play Latest"
                    statusTextView.text = "Playback error: ${error.errorCodeName}"
                }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(audioUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    /** Stops and releases the current stream. */
    private fun stopPlayback(showMessage: Boolean) {
        player?.stop()
        player?.release()
        player = null
        isPlaying = false
        if (::playButton.isInitialized) {
            playButton.text = "Play Latest"
            playButton.isEnabled = selectedPodcast != null
        }
        if (showMessage && ::statusTextView.isInitialized) statusTextView.text = "Playback stopped."
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
        stopPlayback(false)
        executor.shutdown()
        super.onDestroy()
    }
}
