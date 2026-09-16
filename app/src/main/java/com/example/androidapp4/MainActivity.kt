package com.example.androidapp4

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    data class Podcast(val title: String, val artist: String, val collectionId: Long)

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
            if (term.isEmpty()) statusTextView.text = "Please enter a podcast name or topic."
            else searchPodcasts(term)
        }

        podcastListView.setOnItemClickListener { parent, _, position, _ ->
            stopPlayback(false)
            selectedPodcast = parent.getItemAtPosition(position) as Podcast
            val podcast = selectedPodcast ?: return@setOnItemClickListener
            selectedPodcastTextView.text = podcast.title
            statusTextView.text = "Selected ${podcast.title}"
            subscribeButton.isEnabled = true
            playButton.isEnabled = true
            updateSubscribeButton(podcast)
        }

        subscribeButton.setOnClickListener { selectedPodcast?.let { toggleSubscription(it) } }

        // Play the latest episode, or pause/resume one that is already loaded.
        playButton.setOnClickListener {
            val currentPlayer = player
            if (currentPlayer == null) {
                selectedPodcast?.let { loadLatestEpisode(it) }
            } else if (currentPlayer.isPlaying) {
                currentPlayer.pause()
                isPlaying = false
                playButton.text = "Resume"
                statusTextView.text = "Playback paused."
            } else {
                currentPlayer.play()
                isPlaying = true
                playButton.text = "Pause"
                statusTextView.text = "Playback resumed."
            }
        }
    }

    /** Searches iTunes for podcasts on a background thread. */
    private fun searchPodcasts(term: String) {
        stopPlayback(false)
        setLoading(true)
        val useLongTitleFilter = longTitleCheckBox.isChecked

        executor.execute {
            try {
                val encodedTerm = URLEncoder.encode(term, "UTF-8")
                val url = URL("https://itunes.apple.com/search?term=$encodedTerm&media=podcast&entity=podcast&limit=25")
                val podcasts = parsePodcastResults(downloadText(url), useLongTitleFilter)
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

    /** Converts JSON results and optionally keeps titles with four or more words. */
    private fun parsePodcastResults(jsonText: String, useLongTitleFilter: Boolean): List<Podcast> {
        val results = JSONObject(jsonText).getJSONArray("results")
        val podcasts = mutableListOf<Podcast>()

        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val title = item.optString("collectionName", "Unknown Podcast")
            val artist = item.optString("artistName", "Unknown Artist")
            val collectionId = item.optLong("collectionId", 0L)
            val wordCount = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

            if (collectionId != 0L && (!useLongTitleFilter || wordCount >= 4)) {
                podcasts.add(Podcast(title, artist, collectionId))
            }
        }
        return podcasts
    }

    /** Displays results with a custom row so text stays readable in dark mode. */
    private fun showResults(podcasts: List<Podcast>) {
        selectedPodcast = null
        selectedPodcastTextView.text = "No podcast selected"
        subscribeButton.isEnabled = false
        playButton.isEnabled = false
        podcastListView.adapter = PodcastAdapter(podcasts)
        statusTextView.text = if (podcasts.isEmpty()) {
            "No podcasts matched this search/filter."
        } else {
            "${podcasts.size} podcasts found - tap one to select it."
        }
    }

    /** Simple custom adapter used for the polished podcast result rows. */
    private inner class PodcastAdapter(podcasts: List<Podcast>) :
        ArrayAdapter<Podcast>(this, R.layout.podcast_list_item, podcasts) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.podcast_list_item, parent, false)
            val podcast = getItem(position) ?: return row

            row.findViewById<TextView>(R.id.podcastTitleTextView).text = podcast.title
            row.findViewById<TextView>(R.id.podcastArtistTextView).text = podcast.artist
            row.findViewById<TextView>(R.id.podcastInitialTextView).text =
                podcast.title.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
            return row
        }
    }

    /** Saves subscription status locally using SharedPreferences. */
    private fun toggleSubscription(podcast: Podcast) {
        val preferences = getSharedPreferences("subscriptions", MODE_PRIVATE)
        val key = podcast.collectionId.toString()
        val subscribed = preferences.getBoolean(key, false)
        preferences.edit().putBoolean(key, !subscribed).apply()
        updateSubscribeButton(podcast)
        statusTextView.text = if (!subscribed) "Subscribed to ${podcast.title}"
        else "Unsubscribed from ${podcast.title}"
    }

    private fun updateSubscribeButton(podcast: Podcast) {
        val subscribed = getSharedPreferences("subscriptions", MODE_PRIVATE)
            .getBoolean(podcast.collectionId.toString(), false)
        subscribeButton.text = if (subscribed) "Unsubscribe" else "Subscribe"
    }

    /** Loads recent episodes for the selected podcast and gets an audio URL. */
    private fun loadLatestEpisode(podcast: Podcast) {
        setLoading(true)
        playButton.isEnabled = false
        statusTextView.text = "Loading latest episode from ${podcast.title}..."

        executor.execute {
            try {
                val url = URL("https://itunes.apple.com/lookup?id=${podcast.collectionId}&entity=podcastEpisode&limit=5")
                val results = JSONObject(downloadText(url)).getJSONArray("results")
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
                    if (finalAudioUrl == null) {
                        statusTextView.text = "No playable episode was returned for ${podcast.title}."
                    } else playAudio(finalAudioUrl, podcast.title, finalTitle)
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

    /** Streams a podcast episode using Media3 ExoPlayer. */
    private fun playAudio(audioUrl: String, podcastTitle: String, episodeTitle: String) {
        stopPlayback(false)
        statusTextView.text = "Preparing: $episodeTitle"
        playButton.isEnabled = false

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> if (exoPlayer.playWhenReady) {
                            isPlaying = true
                            playButton.isEnabled = true
                            playButton.text = "Pause"
                            statusTextView.text = "Playing $podcastTitle: $episodeTitle"
                        }
                        Player.STATE_ENDED -> {
                            isPlaying = false
                            exoPlayer.seekTo(0)
                            exoPlayer.pause()
                            playButton.text = "Replay"
                            statusTextView.text = "Episode finished: $episodeTitle"
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isPlaying = false
                    playButton.isEnabled = true
                    playButton.text = "Play Latest"
                    statusTextView.text = "This episode could not be played."
                }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(audioUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    /** Stops and releases the current audio stream. */
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

    /** Downloads text used by the iTunes search and episode requests. */
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
        stopPlayback(false)
        executor.shutdown()
        super.onDestroy()
    }
}
