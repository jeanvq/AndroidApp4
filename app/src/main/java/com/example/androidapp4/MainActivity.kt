package com.example.androidapp4

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Main screen for the SuperPodcast networking assignment. */
class MainActivity : AppCompatActivity() {
    data class Podcast(val title: String, val artist: String, val collectionId: Long, val artworkUrl: String)

    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var longTitleCheckBox: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var podcastListView: ListView
    private lateinit var selectedPodcastTextView: TextView
    private lateinit var subscribeButton: Button
    private lateinit var playButton: Button
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView
    private lateinit var discoverTabButton: Button
    private lateinit var subscribedTabButton: Button
    private lateinit var listSectionTitleTextView: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val progressHandler = Handler(Looper.getMainLooper())
    private var selectedPodcast: Podcast? = null
    private var player: ExoPlayer? = null
    private var isPlaying = false
    private var userSeeking = false
    private var searchResults = listOf<Podcast>()
    private var showingSubscribed = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            player?.let { currentPlayer ->
                if (currentPlayer.duration > 0) {
                    val duration = currentPlayer.duration
                    val position = currentPlayer.currentPosition.coerceAtMost(duration)
                    if (!userSeeking) playbackSeekBar.progress = ((position * 1000) / duration).toInt()
                    currentTimeTextView.text = formatTime(position)
                    durationTextView.text = formatTime(duration)
                }
            }
            progressHandler.postDelayed(this, 500)
        }
    }

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
        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)
        discoverTabButton = findViewById(R.id.discoverTabButton)
        subscribedTabButton = findViewById(R.id.subscribedTabButton)
        listSectionTitleTextView = findViewById(R.id.listSectionTitleTextView)

        searchButton.setOnClickListener {
            val term = searchEditText.text.toString().trim()
            if (term.isEmpty()) statusTextView.text = "Please enter a podcast name or topic."
            else searchPodcasts(term)
        }
        discoverTabButton.setOnClickListener { showDiscover() }
        subscribedTabButton.setOnClickListener { showSubscribedPodcasts() }

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
        playButton.setOnClickListener {
            val currentPlayer = player
            if (currentPlayer == null) selectedPodcast?.let { loadLatestEpisode(it) }
            else if (currentPlayer.isPlaying) {
                currentPlayer.pause(); isPlaying = false; playButton.text = "Resume"; statusTextView.text = "Playback paused."
            } else {
                currentPlayer.play(); isPlaying = true; playButton.text = "Pause"; statusTextView.text = "Playback resumed."
            }
        }

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0) currentTimeTextView.text = formatTime((duration * progress) / 1000)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                player?.let { if (it.duration > 0) it.seekTo((it.duration * playbackSeekBar.progress) / 1000) }
                userSeeking = false
            }
        })
        progressHandler.post(progressUpdater)
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
                    searchResults = podcasts
                    showingSubscribed = false
                    updateTabs()
                    showResults(podcasts, "Discover")
                    setLoading(false)
                }
            } catch (exception: Exception) {
                runOnUiThread { statusTextView.text = "Could not load podcasts. Check your internet connection."; podcastListView.adapter = null; setLoading(false) }
            }
        }
    }

    private fun parsePodcastResults(jsonText: String, useLongTitleFilter: Boolean): List<Podcast> {
        val results = JSONObject(jsonText).getJSONArray("results")
        val podcasts = mutableListOf<Podcast>()
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val title = item.optString("collectionName", "Unknown Podcast")
            val artist = item.optString("artistName", "Unknown Artist")
            val collectionId = item.optLong("collectionId", 0L)
            val artworkUrl = item.optString("artworkUrl100", "")
            val wordCount = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
            if (collectionId != 0L && (!useLongTitleFilter || wordCount >= 4)) podcasts.add(Podcast(title, artist, collectionId, artworkUrl))
        }
        return podcasts
    }

    /** Shows the latest search results again without making another network request. */
    private fun showDiscover() {
        showingSubscribed = false
        updateTabs()
        showResults(searchResults, "Discover")
        statusTextView.text = if (searchResults.isEmpty()) "Search for podcasts to discover something new." else "${searchResults.size} search results."
    }

    /** Loads the user's saved podcast library from SharedPreferences. */
    private fun showSubscribedPodcasts() {
        showingSubscribed = true
        updateTabs()
        val subscriptions = loadSubscriptions()
        showResults(subscriptions, "Subscribed")
        statusTextView.text = if (subscriptions.isEmpty()) "You have not subscribed to any podcasts yet." else "${subscriptions.size} subscribed podcasts."
    }

    private fun updateTabs() {
        discoverTabButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (showingSubscribed) "#151C23" else "#123447"))
        subscribedTabButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (showingSubscribed) "#123447" else "#151C23"))
        discoverTabButton.setTextColor(android.graphics.Color.parseColor(if (showingSubscribed) "#94A3B8" else "#7DD3FC"))
        subscribedTabButton.setTextColor(android.graphics.Color.parseColor(if (showingSubscribed) "#7DD3FC" else "#94A3B8"))
    }

    private fun showResults(podcasts: List<Podcast>, sectionTitle: String) {
        selectedPodcast = null
        selectedPodcastTextView.text = "No podcast selected"
        subscribeButton.isEnabled = false
        playButton.isEnabled = false
        resetProgress()
        listSectionTitleTextView.text = sectionTitle
        podcastListView.adapter = PodcastAdapter(podcasts)
    }

    private inner class PodcastAdapter(podcasts: List<Podcast>) : ArrayAdapter<Podcast>(this, R.layout.podcast_list_item, podcasts) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.podcast_list_item, parent, false)
            val podcast = getItem(position) ?: return row
            val titleView = row.findViewById<TextView>(R.id.podcastTitleTextView)
            val artistView = row.findViewById<TextView>(R.id.podcastArtistTextView)
            val initialView = row.findViewById<TextView>(R.id.podcastInitialTextView)
            val artworkView = row.findViewById<ImageView>(R.id.podcastArtworkImageView)
            titleView.text = podcast.title
            artistView.text = podcast.artist
            initialView.text = podcast.title.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
            initialView.visibility = View.VISIBLE
            artworkView.load(podcast.artworkUrl) {
                crossfade(true)
                listener(onSuccess = { _, _ -> initialView.visibility = View.INVISIBLE }, onError = { _, _ -> initialView.visibility = View.VISIBLE })
            }
            return row
        }
    }

    /** Saves complete podcast information so subscriptions survive app restarts. */
    private fun toggleSubscription(podcast: Podcast) {
        val subscriptions = loadSubscriptions().toMutableList()
        val existingIndex = subscriptions.indexOfFirst { it.collectionId == podcast.collectionId }
        val nowSubscribed = existingIndex == -1
        if (nowSubscribed) subscriptions.add(podcast) else subscriptions.removeAt(existingIndex)
        saveSubscriptions(subscriptions)
        updateSubscribeButton(podcast)
        statusTextView.text = if (nowSubscribed) "Subscribed to ${podcast.title}" else "Unsubscribed from ${podcast.title}"
        if (showingSubscribed) showSubscribedPodcasts()
    }

    private fun saveSubscriptions(podcasts: List<Podcast>) {
        val json = JSONArray()
        podcasts.forEach { podcast ->
            json.put(JSONObject().apply {
                put("title", podcast.title); put("artist", podcast.artist); put("collectionId", podcast.collectionId); put("artworkUrl", podcast.artworkUrl)
            })
        }
        getSharedPreferences("subscriptions", MODE_PRIVATE).edit().putString("saved_podcasts", json.toString()).apply()
    }

    private fun loadSubscriptions(): List<Podcast> {
        val text = getSharedPreferences("subscriptions", MODE_PRIVATE).getString("saved_podcasts", "[]") ?: "[]"
        return try {
            val json = JSONArray(text)
            (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                Podcast(item.optString("title"), item.optString("artist"), item.optLong("collectionId"), item.optString("artworkUrl"))
            }.filter { it.collectionId != 0L }
        } catch (_: Exception) { emptyList() }
    }

    private fun updateSubscribeButton(podcast: Podcast) {
        val subscribed = loadSubscriptions().any { it.collectionId == podcast.collectionId }
        subscribeButton.text = if (subscribed) "Unsubscribe" else "Subscribe"
    }

    private fun loadLatestEpisode(podcast: Podcast) {
        setLoading(true); playButton.isEnabled = false; statusTextView.text = "Loading latest episode from ${podcast.title}..."
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
                        if (candidate.isNotBlank()) { audioUrl = candidate; episodeTitle = item.optString("trackName", episodeTitle); break }
                    }
                }
                val finalAudioUrl = audioUrl; val finalTitle = episodeTitle
                runOnUiThread {
                    setLoading(false); playButton.isEnabled = true
                    if (selectedPodcast?.collectionId != podcast.collectionId) return@runOnUiThread
                    if (finalAudioUrl == null) statusTextView.text = "No playable episode was returned for ${podcast.title}." else playAudio(finalAudioUrl, podcast.title, finalTitle)
                }
            } catch (exception: Exception) {
                runOnUiThread { setLoading(false); playButton.isEnabled = true; statusTextView.text = "Could not load an episode for ${podcast.title}." }
            }
        }
    }

    private fun playAudio(audioUrl: String, podcastTitle: String, episodeTitle: String) {
        stopPlayback(false); statusTextView.text = "Preparing: $episodeTitle"; playButton.isEnabled = false
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> if (exoPlayer.playWhenReady) { isPlaying = true; playButton.isEnabled = true; playButton.text = "Pause"; durationTextView.text = formatTime(exoPlayer.duration); statusTextView.text = "Playing $podcastTitle: $episodeTitle" }
                        Player.STATE_ENDED -> { isPlaying = false; exoPlayer.seekTo(0); exoPlayer.pause(); playbackSeekBar.progress = 0; currentTimeTextView.text = "0:00"; playButton.text = "Replay"; statusTextView.text = "Episode finished: $episodeTitle" }
                    }
                }
                override fun onPlayerError(error: PlaybackException) { isPlaying = false; playButton.isEnabled = true; playButton.text = "Play Latest"; statusTextView.text = "This episode could not be played." }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(audioUrl)); exoPlayer.prepare(); exoPlayer.playWhenReady = true
        }
    }

    private fun formatTime(milliseconds: Long): String {
        if (milliseconds <= 0) return "0:00"
        val totalSeconds = milliseconds / 1000; val hours = totalSeconds / 3600; val minutes = (totalSeconds % 3600) / 60; val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
    }

    private fun resetProgress() {
        if (::playbackSeekBar.isInitialized) playbackSeekBar.progress = 0
        if (::currentTimeTextView.isInitialized) currentTimeTextView.text = "0:00"
        if (::durationTextView.isInitialized) durationTextView.text = "0:00"
    }

    private fun stopPlayback(showMessage: Boolean) {
        player?.stop(); player?.release(); player = null; isPlaying = false; resetProgress()
        if (::playButton.isInitialized) { playButton.text = "Play Latest"; playButton.isEnabled = selectedPodcast != null }
        if (showMessage && ::statusTextView.isInitialized) statusTextView.text = "Playback stopped."
    }

    private fun downloadText(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"; connection.connectTimeout = 10000; connection.readTimeout = 10000
        return try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
    }

    private fun setLoading(loading: Boolean) { progressBar.visibility = if (loading) View.VISIBLE else View.GONE; searchButton.isEnabled = !loading }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressUpdater); stopPlayback(false); executor.shutdown(); super.onDestroy()
    }
}
