package com.example.ytnovelplayer

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentVideoId = "M7lc1UVf-VE" 
    private var isPlaying = false
    private var currentPlaybackRate = 1.0f
    private var currentPosition = 0 // Current playback position in seconds
    
    private lateinit var btnPlayPause: ImageButton
    private lateinit var etVideoId: EditText
    private lateinit var controlsContainer: LinearLayout
    private lateinit var inputContainer: LinearLayout
    private lateinit var btnLoginProfile: ImageView
    private lateinit var btnActionSave: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnHistory: ImageButton
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvSpeedDisplay: TextView

    enum class AuthMode { NONE, LOCAL, GOOGLE }
    private var authMode = AuthMode.NONE
    private val gson = Gson()
    
    private lateinit var googleSignInClient: GoogleSignInClient
    
    // Auto-save timer for playback state
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            savePlaybackState()
            saveHandler.postDelayed(this, 5000) // Save every 5 seconds
        }
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task.result)
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_PLAY" -> playVideo()
                "ACTION_PAUSE" -> pauseVideo()
                "ACTION_NEXT" -> webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime += 30", null)
                "ACTION_PREV" -> webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime -= 15", null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        initWebView()
        initGoogleAuth()
        
        val filter = IntentFilter().apply {
            addAction("ACTION_PLAY")
            addAction("ACTION_PAUSE")
            addAction("ACTION_NEXT")
            addAction("ACTION_PREV")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaReceiver, filter)
        }
        
        // Restore last playback state
        restorePlaybackState()
        
        // Start auto-save timer
        saveHandler.postDelayed(autoSaveRunnable, 5000)
    }

    private fun initWebView() {
        webView = findViewById(R.id.youtube_webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            
            // Use desktop User-Agent for better compatibility
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            
            // Enable additional features
            allowContentAccess = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            
            // Enable cookies and caching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        
        // Enable cookies
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val js = """
                    (function() {
                        var style = document.createElement('style');
                        style.textContent = 'header, #header-bar, .ytd-masthead, #masthead-container, #related, #comments { display: none !important; }';
                        document.head.appendChild(style);
                    })()
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Toast.makeText(this@MainActivity, "Error loading video: $description", Toast.LENGTH_LONG).show()
            }
        }
        webView.webChromeClient = WebChromeClient()
        loadWebVideo(currentVideoId)
    }

    private fun loadWebVideo(videoId: String, startPosition: Int = 0) {
        currentVideoId = videoId
        currentPosition = startPosition
        // Use desktop YouTube URL for better compatibility
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extraHeaders = HashMap<String, String>()
        extraHeaders["Referer"] = "https://www.youtube.com"
        extraHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        extraHeaders["Accept-Language"] = "en-US,en;q=0.9"
        
        webView.post {
            webView.loadUrl(url, extraHeaders)
            
            // Seek to saved position after video loads
            if (startPosition > 0) {
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "document.getElementsByTagName('video')[0].currentTime = $startPosition",
                        null
                    )
                }, 2000) // Wait 2 seconds for video to load
            }
            
            updatePlaybackService(true)
            // Add to history when loading a video
            addToHistory(videoId)
        }
    }

    private fun updatePlaybackService(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        val serviceIntent = Intent(this, MediaPlaybackService::class.java).apply {
            putExtra("VIDEO_TITLE", if (::tvVideoTitle.isInitialized) tvVideoTitle.text.toString() else "YouTube Novel Player")
            putExtra("IS_PLAYING", isPlaying)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        }
    }

    private fun playVideo() {
        webView.evaluateJavascript("document.getElementsByTagName('video')[0].play()", null)
        updatePlaybackService(true)
    }

    private fun pauseVideo() {
        webView.evaluateJavascript("document.getElementsByTagName('video')[0].pause()", null)
        updatePlaybackService(false)
    }

    private fun initGoogleAuth() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(YouTubeScopes.YOUTUBE_READONLY))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            authMode = AuthMode.GOOGLE
            updateLoginIcon()
        }
    }

    private fun initUI() {
        etVideoId = findViewById(R.id.et_video_id)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        controlsContainer = findViewById(R.id.controls_container)
        inputContainer = findViewById(R.id.input_container)
        btnLoginProfile = findViewById(R.id.btn_login_profile)
        btnActionSave = findViewById(R.id.btn_action_save)
        btnFavorite = findViewById(R.id.btn_favorite)
        btnHistory = findViewById(R.id.btn_history)
        tvVideoTitle = findViewById(R.id.tv_video_title)
        tvSpeedDisplay = findViewById(R.id.tv_speed_display)

        loadPlaylist()
        loadHistory()
        updateLoginIcon()

        btnPlayPause.setOnClickListener {
            if (isPlaying) pauseVideo() else playVideo()
        }

        // Save button: Show category selection to add to favorites
        btnActionSave.setOnClickListener { showFavoriteMenu() }
        
        // Favorite button: Click to view saved videos, Long-press to quick add
        btnFavorite.setOnClickListener { 
            if (mySavedVideos.isEmpty()) {
                Toast.makeText(this, "收藏清單是空的", Toast.LENGTH_SHORT).show()
            } else {
                showPlaylist()
            }
        }
        btnFavorite.setOnLongClickListener {
            addToPlaylist("Quick Save")
            true
        }
        
        // History button: Show playback history
        btnHistory.setOnClickListener {
            if (myHistoryVideos.isEmpty()) {
                Toast.makeText(this, "播放歷史是空的", Toast.LENGTH_SHORT).show()
            } else {
                showHistory()
            }
        }

        findViewById<Button>(R.id.btn_load).setOnClickListener {
            val input = etVideoId.text.toString().trim()
            if (input.isNotEmpty()) {
                val videoId = extractVideoId(input)
                if (videoId != null) loadWebVideo(videoId)
            }
        }
        
        findViewById<ImageButton>(R.id.btn_rewind).setOnClickListener {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime -= 15", null)
        }
        
        findViewById<ImageButton>(R.id.btn_forward).setOnClickListener {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime += 30", null)
        }
        
        findViewById<ImageButton>(R.id.btn_pip_small).setOnClickListener { enterPiP() }

        findViewById<ImageButton>(R.id.btn_speed_up).setOnClickListener { changeSpeed(0.025f) }
        findViewById<ImageButton>(R.id.btn_speed_down).setOnClickListener { changeSpeed(-0.025f) }
    }

    private fun changeSpeed(delta: Float) {
        currentPlaybackRate += delta
        if (currentPlaybackRate < 0.25f) currentPlaybackRate = 0.25f
        if (currentPlaybackRate > 4.0f) currentPlaybackRate = 4.0f
        
        webView.evaluateJavascript("document.getElementsByTagName('video')[0].playbackRate = ${"$"}currentPlaybackRate", null)
        tvSpeedDisplay.text = String.format(Locale.getDefault(), "%.3fx", currentPlaybackRate)
    }

    private fun showFavoriteMenu() {
        val categories = arrayOf("Novel", "Music", "Learning", "Others")
        AlertDialog.Builder(this)
            .setTitle("Add to Favorites")
            .setItems(categories) { _, which ->
                val category = categories[which]
                addToPlaylist(category)
            }
            .show()
    }

    private fun extractVideoId(input: String): String? {
        if (input.length == 11) return input
        return try {
            val uri = Uri.parse(input)
            when {
                uri.host?.contains("youtube.com") == true -> uri.getQueryParameter("v")
                uri.host?.contains("youtu.be") == true -> uri.path?.substring(1)
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun handleSignInResult(account: GoogleSignInAccount?) {
        if (account != null) {
            authMode = AuthMode.GOOGLE
            updateLoginIcon()
            fetchUserVideos()
        }
    }

    private fun fetchUserVideos() {
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        val credential = GoogleAccountCredential.usingOAuth2(this, Collections.singleton(YouTubeScopes.YOUTUBE_READONLY))
        credential.selectedAccount = account.account
        val youtube = YouTube.Builder(NetHttpTransport(), GsonFactory(), credential).setApplicationName("YouTubeNovelPlayer").build()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val channelsResponse = youtube.channels().list(Collections.singletonList("contentDetails")).setMine(true).execute()
                val uploadsPlaylistId = channelsResponse.items?.firstOrNull()?.contentDetails?.relatedPlaylists?.uploads ?: return@launch
                val playlistResponse = youtube.playlistItems().list(Collections.singletonList("snippet,contentDetails"))
                    .setPlaylistId(uploadsPlaylistId).setMaxResults(10L).execute()
                val videos = playlistResponse.items?.map { SavedVideo(it.snippet.title, it.contentDetails.videoId) } ?: emptyList()
                withContext(Dispatchers.Main) { if (videos.isNotEmpty()) showPlaylistSelector(videos) }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showPlaylistSelector(videos: List<SavedVideo>) {
        val titles = videos.map { it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Select Video").setItems(titles) { _, which ->
            val video = videos[which]
            loadWebVideo(video.id)
            if (::tvVideoTitle.isInitialized) tvVideoTitle.text = video.title
            if (::etVideoId.isInitialized) etVideoId.setText(video.id)
        }.show()
    }

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            
            // Add custom actions for PIP mode (Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val actions = ArrayList<android.app.RemoteAction>()
                
                // Previous action (Rewind 15s)
                val prevIntent = PendingIntent.getBroadcast(
                    this, 0, Intent("ACTION_PREV"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val prevIcon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_replay_10)
                val prevAction = android.app.RemoteAction(
                    prevIcon, "Previous", "Rewind 15s", prevIntent
                )
                actions.add(prevAction)
                
                // Play/Pause action
                val playPauseIntent = PendingIntent.getBroadcast(
                    this, 1, Intent(if (isPlaying) "ACTION_PAUSE" else "ACTION_PLAY"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val playPauseIcon = android.graphics.drawable.Icon.createWithResource(
                    this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )
                val playPauseAction = android.app.RemoteAction(
                    playPauseIcon, 
                    if (isPlaying) "Pause" else "Play",
                    if (isPlaying) "Pause playback" else "Resume playback",
                    playPauseIntent
                )
                actions.add(playPauseAction)
                
                // Next action (Forward 30s)
                val nextIntent = PendingIntent.getBroadcast(
                    this, 2, Intent("ACTION_NEXT"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val nextIcon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_forward_30)
                val nextAction = android.app.RemoteAction(
                    nextIcon, "Next", "Forward 30s", nextIntent
                )
                actions.add(nextAction)
                
                params.setActions(actions)
            }
            
            enterPictureInPictureMode(params.build())
        }
    }
    
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        if (isInPictureInPictureMode) {
            // Hide controls when in PIP mode
            controlsContainer.visibility = android.view.View.GONE
            inputContainer.visibility = android.view.View.GONE
        } else {
            // Show controls when exiting PIP mode
            controlsContainer.visibility = android.view.View.VISIBLE
            inputContainer.visibility = android.view.View.VISIBLE
        }
    }


    private fun updateLoginIcon() {
        if (!::btnLoginProfile.isInitialized) return
        val color = when(authMode) {
            AuthMode.GOOGLE -> android.graphics.Color.RED
            AuthMode.LOCAL -> android.graphics.Color.GREEN
            else -> android.graphics.Color.WHITE
        }
        btnLoginProfile.setColorFilter(color)
    }

    private fun showLoginDialog() {
        val options = arrayOf("Sign in with Google", "Local Guest")
        AlertDialog.Builder(this).setItems(options) { _, which ->
            if (which == 0) signInLauncher.launch(googleSignInClient.signInIntent) else {
                authMode = AuthMode.LOCAL
                updateLoginIcon()
            }
        }.show()
    }

    private fun showLoggedInMenu() {
        val options = arrayOf("My YouTube Videos", "Sign Out")
        AlertDialog.Builder(this).setItems(options) { _, which ->
            if (which == 0) fetchUserVideos() else googleSignInClient.signOut().addOnCompleteListener {
                authMode = AuthMode.NONE
                updateLoginIcon()
            }
        }.show()
    }

    private fun addToPlaylist(category: String = "General") {
        if (mySavedVideos.none { it.id == currentVideoId }) {
            val title = if (::tvVideoTitle.isInitialized) tvVideoTitle.text.toString() else "Unknown Title"
            mySavedVideos.add(SavedVideo("${"$"}title [${"$"}category]", currentVideoId))
            getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit { putString("my_playlist", gson.toJson(mySavedVideos)) }
            Toast.makeText(this, "Added to ${"$"}category favorites", Toast.LENGTH_SHORT).show()
        }
    }

    data class SavedVideo(val title: String, val id: String)
    private val mySavedVideos = mutableListOf<SavedVideo>()
    
    // History tracking
    data class HistoryVideo(
        val title: String, 
        val id: String, 
        val timestamp: Long,
        val lastPosition: Int = 0 // in seconds
    )
    private val myHistoryVideos = mutableListOf<HistoryVideo>()

    private fun loadPlaylist() {
        val json = getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).getString("my_playlist", null)
        if (json != null) {
            val type = object : TypeToken<List<SavedVideo>>() {}.type
            val loaded: List<SavedVideo> = gson.fromJson(json, type)
            mySavedVideos.clear()
            mySavedVideos.addAll(loaded)
        }
    }
    
    private fun loadHistory() {
        val json = getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).getString("my_history", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<HistoryVideo>>() {}.type
                val loaded: List<HistoryVideo> = gson.fromJson(json, type)
                myHistoryVideos.clear()
                myHistoryVideos.addAll(loaded)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun saveHistory() {
        getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit {
            putString("my_history", gson.toJson(myHistoryVideos))
        }
    }
    
    private fun addToHistory(videoId: String) {
        val title = if (::tvVideoTitle.isInitialized) tvVideoTitle.text.toString() else "Unknown Title"
        
        // Remove existing entry for this video if it exists
        myHistoryVideos.removeAll { it.id == videoId }
        
        // Add new entry at the beginning (most recent first)
        myHistoryVideos.add(0, HistoryVideo(title, videoId, System.currentTimeMillis()))
        
        // Keep only last 100 videos
        if (myHistoryVideos.size > 100) {
            myHistoryVideos.removeAt(myHistoryVideos.size - 1)
        }
        
        saveHistory()
    }

    private fun showPlaylist() {
        if (mySavedVideos.isEmpty()) {
            Toast.makeText(this, "收藏清單是空的", Toast.LENGTH_SHORT).show()
            return
        }
        
        val titles = mySavedVideos.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("我的收藏 (${mySavedVideos.size} 個影片)")
            .setItems(titles) { _, which ->
                val video = mySavedVideos[which]
                loadWebVideo(video.id)
                if (::tvVideoTitle.isInitialized) tvVideoTitle.text = video.title
                if (::etVideoId.isInitialized) etVideoId.setText(video.id)
                Toast.makeText(this, "正在載入: ${video.title}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("管理收藏") { _, _ ->
                showPlaylistManager()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showPlaylistManager() {
        if (mySavedVideos.isEmpty()) {
            Toast.makeText(this, "收藏清單是空的", Toast.LENGTH_SHORT).show()
            return
        }
        
        val titles = mySavedVideos.mapIndexed { index, video -> 
            "${index + 1}. ${video.title}" 
        }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("管理收藏 - 點擊刪除")
            .setItems(titles) { dialog, which ->
                val video = mySavedVideos[which]
                // Confirm deletion
                AlertDialog.Builder(this)
                    .setTitle("確認刪除")
                    .setMessage("確定要刪除「${video.title}」嗎？")
                    .setPositiveButton("刪除") { _, _ ->
                        mySavedVideos.removeAt(which)
                        getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit { 
                            putString("my_playlist", gson.toJson(mySavedVideos)) 
                        }
                        Toast.makeText(this, "已刪除", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        // Reopen manager if there are still videos
                        if (mySavedVideos.isNotEmpty()) {
                            showPlaylistManager()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("清空全部") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("確認清空")
                    .setMessage("確定要刪除所有 ${mySavedVideos.size} 個收藏影片嗎？")
                    .setPositiveButton("清空") { _, _ ->
                        mySavedVideos.clear()
                        getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit { 
                            putString("my_playlist", gson.toJson(mySavedVideos)) 
                        }
                        Toast.makeText(this, "已清空收藏清單", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("返回", null)
            .show()
    }
    
    private fun showHistory() {
        if (myHistoryVideos.isEmpty()) {
            Toast.makeText(this, "播放歷史是空的", Toast.LENGTH_SHORT).show()
            return
        }
        
        val titles = myHistoryVideos.map { 
            val timeAgo = getTimeAgo(it.timestamp)
            "${it.title}\n$timeAgo"
        }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("播放歷史 (${myHistoryVideos.size} 個影片)")
            .setItems(titles) { _, which ->
                val video = myHistoryVideos[which]
                loadWebVideo(video.id)
                if (::tvVideoTitle.isInitialized) tvVideoTitle.text = video.title
                if (::etVideoId.isInitialized) etVideoId.setText(video.id)
                Toast.makeText(this, "正在載入: ${video.title}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("管理歷史") { _, _ ->
                showHistoryManager()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showHistoryManager() {
        if (myHistoryVideos.isEmpty()) {
            Toast.makeText(this, "播放歷史是空的", Toast.LENGTH_SHORT).show()
            return
        }
        
        val titles = myHistoryVideos.mapIndexed { index, video ->
            val timeAgo = getTimeAgo(video.timestamp)
            "${index + 1}. ${video.title}\n   $timeAgo"
        }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("管理歷史 - 點擊刪除")
            .setItems(titles) { dialog, which ->
                val video = myHistoryVideos[which]
                // Confirm deletion
                AlertDialog.Builder(this)
                    .setTitle("確認刪除")
                    .setMessage("確定要從歷史中刪除「${video.title}」嗎？")
                    .setPositiveButton("刪除") { _, _ ->
                        myHistoryVideos.removeAt(which)
                        saveHistory()
                        Toast.makeText(this, "已刪除", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        // Reopen manager if there are still videos
                        if (myHistoryVideos.isNotEmpty()) {
                            showHistoryManager()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("清空全部") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("確認清空")
                    .setMessage("確定要刪除所有 ${myHistoryVideos.size} 個歷史紀錄嗎？")
                    .setPositiveButton("清空") { _, _ ->
                        myHistoryVideos.clear()
                        saveHistory()
                        Toast.makeText(this, "已清空播放歷史", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("返回", null)
            .show()
    }
    
    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "${days}天前"
            hours > 0 -> "${hours}小時前"
            minutes > 0 -> "${minutes}分鐘前"
            else -> "剛剛"
        }
    }
    
    // ========== Playback State Persistence ==========
    
    data class PlaybackState(
        val videoId: String,
        val videoTitle: String,
        val position: Int,
        val playbackRate: Float,
        val timestamp: Long
    )
    
    private fun savePlaybackState() {
        // Get current position from WebView
        webView.evaluateJavascript(
            "document.getElementsByTagName('video')[0].currentTime",
            { result ->
                try {
                    val position = result?.toFloatOrNull()?.toInt() ?: currentPosition
                    currentPosition = position
                    
                    val state = PlaybackState(
                        videoId = currentVideoId,
                        videoTitle = if (::tvVideoTitle.isInitialized) tvVideoTitle.text.toString() else "Unknown",
                        position = position,
                        playbackRate = currentPlaybackRate,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit {
                        putString("last_playback_state", gson.toJson(state))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        )
    }
    
    private fun restorePlaybackState() {
        val json = getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE)
            .getString("last_playback_state", null)
        
        if (json != null) {
            try {
                val state = gson.fromJson(json, PlaybackState::class.java)
                
                // Only restore if the state is less than 24 hours old
                val hoursSinceLastPlay = (System.currentTimeMillis() - state.timestamp) / (1000 * 60 * 60)
                if (hoursSinceLastPlay < 24) {
                    // Show restore dialog
                    AlertDialog.Builder(this)
                        .setTitle("恢復播放")
                        .setMessage("是否繼續播放「${state.videoTitle}」?\n上次播放位置: ${formatTime(state.position)}")
                        .setPositiveButton("繼續播放") { _, _ ->
                            currentVideoId = state.videoId
                            currentPlaybackRate = state.playbackRate
                            if (::tvVideoTitle.isInitialized) tvVideoTitle.text = state.videoTitle
                            if (::etVideoId.isInitialized) etVideoId.setText(state.videoId)
                            if (::tvSpeedDisplay.isInitialized) {
                                tvSpeedDisplay.text = String.format(Locale.getDefault(), "%.3fx", state.playbackRate)
                            }
                            loadWebVideo(state.videoId, state.position)
                        }
                        .setNegativeButton("從頭開始") { _, _ ->
                            currentVideoId = state.videoId
                            if (::tvVideoTitle.isInitialized) tvVideoTitle.text = state.videoTitle
                            if (::etVideoId.isInitialized) etVideoId.setText(state.videoId)
                            loadWebVideo(state.videoId, 0)
                        }
                        .setNeutralButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }
    
    override fun onPause() {
        super.onPause()
        savePlaybackState()
    }
    
    override fun onStop() {
        super.onStop()
        savePlaybackState()
    }

    override fun onDestroy() {
        // Save state before destroying
        savePlaybackState()
        
        // Stop auto-save timer
        saveHandler.removeCallbacks(autoSaveRunnable)
        
        try { unregisterReceiver(mediaReceiver) } catch (e: Exception) {}
        stopService(Intent(this, MediaPlaybackService::class.java))
        super.onDestroy()
    }
}
