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
    private lateinit var selectedPodcastArtworkImageView: ImageView
    private lateinit var selectedPodcastInitialTextView: TextView
    private lateinit var episodeTitleTextView: TextView
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
    private var userSeeking = false
    private var searchResults = listOf<Podcast>()
    private var showingSubscribed = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.duration > 0) {
                    val position = p.currentPosition.coerceAtMost(p.duration)
                    if (!userSeeking) playbackSeekBar.progress = ((position * 1000) / p.duration).toInt()
                    currentTimeTextView.text = formatTime(position)
                    durationTextView.text = formatTime(p.duration)
                }
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        searchEditText = findViewById(R.id.searchEditText); searchButton = findViewById(R.id.searchButton)
        longTitleCheckBox = findViewById(R.id.longTitleCheckBox); progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView); podcastListView = findViewById(R.id.podcastListView)
        selectedPodcastTextView = findViewById(R.id.selectedPodcastTextView)
        selectedPodcastArtworkImageView = findViewById(R.id.selectedPodcastArtworkImageView)
        selectedPodcastInitialTextView = findViewById(R.id.selectedPodcastInitialTextView)
        episodeTitleTextView = findViewById(R.id.episodeTitleTextView)
        subscribeButton = findViewById(R.id.subscribeButton); playButton = findViewById(R.id.playButton)
        playbackSeekBar = findViewById(R.id.playbackSeekBar); currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView); discoverTabButton = findViewById(R.id.discoverTabButton)
        subscribedTabButton = findViewById(R.id.subscribedTabButton); listSectionTitleTextView = findViewById(R.id.listSectionTitleTextView)

        searchButton.setOnClickListener { val term = searchEditText.text.toString().trim(); if (term.isEmpty()) statusTextView.text = "Please enter a podcast name or topic." else searchPodcasts(term) }
        discoverTabButton.setOnClickListener { showDiscover() }; subscribedTabButton.setOnClickListener { showSubscribedPodcasts() }
        podcastListView.setOnItemClickListener { parent, _, position, _ -> selectPodcast(parent.getItemAtPosition(position) as Podcast) }
        subscribeButton.setOnClickListener { selectedPodcast?.let { toggleSubscription(it) } }
        playButton.setOnClickListener {
            val p = player
            if (p == null) selectedPodcast?.let { loadLatestEpisode(it) }
            else if (p.isPlaying) { p.pause(); playButton.text = "Resume"; statusTextView.text = "Playback paused." }
            else { p.play(); playButton.text = "Pause"; statusTextView.text = "Playback resumed." }
        }
        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) player?.let { if (it.duration > 0) currentTimeTextView.text = formatTime((it.duration * progress) / 1000) } }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { player?.let { if (it.duration > 0) it.seekTo((it.duration * playbackSeekBar.progress) / 1000) }; userSeeking = false }
        })
        progressHandler.post(progressUpdater)
    }

    /** Updates the Now Playing card when the user chooses a podcast. */
    private fun selectPodcast(podcast: Podcast) {
        stopPlayback(false); selectedPodcast = podcast
        selectedPodcastTextView.text = podcast.title
        episodeTitleTextView.text = "Tap Play Latest to load an episode"
        selectedPodcastInitialTextView.text = podcast.title.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
        selectedPodcastInitialTextView.visibility = View.VISIBLE
        selectedPodcastArtworkImageView.load(podcast.artworkUrl) {
            crossfade(true)
            listener(onSuccess = { _, _ -> selectedPodcastInitialTextView.visibility = View.INVISIBLE }, onError = { _, _ -> selectedPodcastInitialTextView.visibility = View.VISIBLE })
        }
        statusTextView.text = "Selected ${podcast.title}"; subscribeButton.isEnabled = true; playButton.isEnabled = true; updateSubscribeButton(podcast)
    }

    private fun searchPodcasts(term: String) {
        stopPlayback(false); setLoading(true); val filter = longTitleCheckBox.isChecked
        executor.execute {
            try {
                val url = URL("https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&media=podcast&entity=podcast&limit=25")
                val podcasts = parsePodcastResults(downloadText(url), filter)
                runOnUiThread { searchResults = podcasts; showingSubscribed = false; updateTabs(); showResults(podcasts, "Discover"); statusTextView.text = "${podcasts.size} podcasts found - tap one to select it."; setLoading(false) }
            } catch (_: Exception) { runOnUiThread { statusTextView.text = "Could not load podcasts. Check your internet connection."; podcastListView.adapter = null; setLoading(false) } }
        }
    }

    private fun parsePodcastResults(text: String, filter: Boolean): List<Podcast> {
        val results = JSONObject(text).getJSONArray("results"); val podcasts = mutableListOf<Podcast>()
        for (i in 0 until results.length()) { val item = results.getJSONObject(i); val title = item.optString("collectionName", "Unknown Podcast"); val id = item.optLong("collectionId", 0L); val words = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size; if (id != 0L && (!filter || words >= 4)) podcasts.add(Podcast(title, item.optString("artistName", "Unknown Artist"), id, item.optString("artworkUrl100", ""))) }
        return podcasts
    }

    private fun showDiscover() { showingSubscribed = false; updateTabs(); showResults(searchResults, "Discover"); statusTextView.text = if (searchResults.isEmpty()) "Search for podcasts to discover something new." else "${searchResults.size} search results." }
    private fun showSubscribedPodcasts() { showingSubscribed = true; updateTabs(); val list = loadSubscriptions(); showResults(list, "Subscribed"); statusTextView.text = if (list.isEmpty()) "You have not subscribed to any podcasts yet." else "${list.size} subscribed podcasts." }
    private fun updateTabs() {
        discoverTabButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (showingSubscribed) "#151C23" else "#123447")); subscribedTabButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (showingSubscribed) "#123447" else "#151C23"))
        discoverTabButton.setTextColor(android.graphics.Color.parseColor(if (showingSubscribed) "#94A3B8" else "#7DD3FC")); subscribedTabButton.setTextColor(android.graphics.Color.parseColor(if (showingSubscribed) "#7DD3FC" else "#94A3B8"))
    }

    private fun showResults(podcasts: List<Podcast>, title: String) {
        selectedPodcast = null; selectedPodcastTextView.text = "No podcast selected"; episodeTitleTextView.text = "Select a podcast to start listening"; selectedPodcastArtworkImageView.setImageDrawable(null); selectedPodcastInitialTextView.text = "S"; selectedPodcastInitialTextView.visibility = View.VISIBLE
        subscribeButton.isEnabled = false; playButton.isEnabled = false; resetProgress(); listSectionTitleTextView.text = title; podcastListView.adapter = PodcastAdapter(podcasts)
    }

    /** Shows a cyan badge beside podcasts already saved in the user's library. */
    private inner class PodcastAdapter(podcasts: List<Podcast>) : ArrayAdapter<Podcast>(this, R.layout.podcast_list_item, podcasts) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.podcast_list_item, parent, false); val podcast = getItem(position) ?: return row
            row.findViewById<TextView>(R.id.podcastTitleTextView).text = podcast.title; row.findViewById<TextView>(R.id.podcastArtistTextView).text = podcast.artist
            val initial = row.findViewById<TextView>(R.id.podcastInitialTextView); val artwork = row.findViewById<ImageView>(R.id.podcastArtworkImageView); val badge = row.findViewById<TextView>(R.id.subscribedBadgeTextView)
            initial.text = podcast.title.firstOrNull()?.uppercaseChar()?.toString() ?: "P"; initial.visibility = View.VISIBLE
            badge.visibility = if (loadSubscriptions().any { it.collectionId == podcast.collectionId }) View.VISIBLE else View.GONE
            artwork.load(podcast.artworkUrl) { crossfade(true); listener(onSuccess = { _, _ -> initial.visibility = View.INVISIBLE }, onError = { _, _ -> initial.visibility = View.VISIBLE }) }
            return row
        }
    }

    private fun toggleSubscription(podcast: Podcast) {
        val list = loadSubscriptions().toMutableList(); val index = list.indexOfFirst { it.collectionId == podcast.collectionId }; val subscribing = index == -1
        if (subscribing) list.add(podcast) else list.removeAt(index); saveSubscriptions(list); updateSubscribeButton(podcast)
        statusTextView.text = if (subscribing) "Subscribed to ${podcast.title}" else "Unsubscribed from ${podcast.title}"
        if (showingSubscribed) showSubscribedPodcasts() else podcastListView.adapter = PodcastAdapter(searchResults)
    }
    private fun saveSubscriptions(podcasts: List<Podcast>) { val json = JSONArray(); podcasts.forEach { json.put(JSONObject().apply { put("title", it.title); put("artist", it.artist); put("collectionId", it.collectionId); put("artworkUrl", it.artworkUrl) }) }; getSharedPreferences("subscriptions", MODE_PRIVATE).edit().putString("saved_podcasts", json.toString()).apply() }
    private fun loadSubscriptions(): List<Podcast> { val text = getSharedPreferences("subscriptions", MODE_PRIVATE).getString("saved_podcasts", "[]") ?: "[]"; return try { val json = JSONArray(text); (0 until json.length()).map { val x = json.getJSONObject(it); Podcast(x.optString("title"), x.optString("artist"), x.optLong("collectionId"), x.optString("artworkUrl")) }.filter { it.collectionId != 0L } } catch (_: Exception) { emptyList() } }
    private fun updateSubscribeButton(podcast: Podcast) { subscribeButton.text = if (loadSubscriptions().any { it.collectionId == podcast.collectionId }) "Unsubscribe" else "Subscribe" }

    private fun loadLatestEpisode(podcast: Podcast) {
        setLoading(true); playButton.isEnabled = false; episodeTitleTextView.text = "Loading latest episode..."; statusTextView.text = "Loading latest episode from ${podcast.title}..."
        executor.execute {
            try {
                val results = JSONObject(downloadText(URL("https://itunes.apple.com/lookup?id=${podcast.collectionId}&entity=podcastEpisode&limit=5"))).getJSONArray("results"); var audioUrl: String? = null; var episodeTitle = "Latest episode"
                for (i in 0 until results.length()) { val item = results.getJSONObject(i); if (item.optString("wrapperType") == "podcastEpisode" && item.optString("episodeUrl").isNotBlank()) { audioUrl = item.optString("episodeUrl"); episodeTitle = item.optString("trackName", episodeTitle); break } }
                val url = audioUrl; val title = episodeTitle
                runOnUiThread { setLoading(false); playButton.isEnabled = true; if (selectedPodcast?.collectionId != podcast.collectionId) return@runOnUiThread; if (url == null) { episodeTitleTextView.text = "No playable episode available"; statusTextView.text = "No playable episode was returned for ${podcast.title}." } else { episodeTitleTextView.text = title; playAudio(url, podcast.title, title) } }
            } catch (_: Exception) { runOnUiThread { setLoading(false); playButton.isEnabled = true; episodeTitleTextView.text = "Could not load episode"; statusTextView.text = "Could not load an episode for ${podcast.title}." } }
        }
    }

    private fun playAudio(audioUrl: String, podcastTitle: String, episodeTitle: String) {
        stopPlayback(false); episodeTitleTextView.text = episodeTitle; statusTextView.text = "Preparing: $episodeTitle"; playButton.isEnabled = false
        player = ExoPlayer.Builder(this).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { when (state) { Player.STATE_READY -> if (p.playWhenReady) { playButton.isEnabled = true; playButton.text = "Pause"; durationTextView.text = formatTime(p.duration); statusTextView.text = "Playing $podcastTitle: $episodeTitle" }; Player.STATE_ENDED -> { p.seekTo(0); p.pause(); playbackSeekBar.progress = 0; currentTimeTextView.text = "0:00"; playButton.text = "Replay"; statusTextView.text = "Episode finished: $episodeTitle" } } }
                override fun onPlayerError(error: PlaybackException) { playButton.isEnabled = true; playButton.text = "Play Latest"; statusTextView.text = "This episode could not be played." }
            }); p.setMediaItem(MediaItem.fromUri(audioUrl)); p.prepare(); p.playWhenReady = true
        }
    }

    private fun formatTime(ms: Long): String { if (ms <= 0) return "0:00"; val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60; return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec) }
    private fun resetProgress() { if (::playbackSeekBar.isInitialized) playbackSeekBar.progress = 0; if (::currentTimeTextView.isInitialized) currentTimeTextView.text = "0:00"; if (::durationTextView.isInitialized) durationTextView.text = "0:00" }
    private fun stopPlayback(showMessage: Boolean) { player?.stop(); player?.release(); player = null; resetProgress(); if (::playButton.isInitialized) { playButton.text = "Play Latest"; playButton.isEnabled = selectedPodcast != null }; if (showMessage && ::statusTextView.isInitialized) statusTextView.text = "Playback stopped." }
    private fun downloadText(url: URL): String { val connection = url.openConnection() as HttpURLConnection; connection.requestMethod = "GET"; connection.connectTimeout = 10000; connection.readTimeout = 10000; return try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() } }
    private fun setLoading(loading: Boolean) { progressBar.visibility = if (loading) View.VISIBLE else View.GONE; searchButton.isEnabled = !loading }
    override fun onDestroy() { progressHandler.removeCallbacks(progressUpdater); stopPlayback(false); executor.shutdown(); super.onDestroy() }
}