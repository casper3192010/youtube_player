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

class MainActivity : AppCompatActivity(), PlaybackController.Callback {

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
    private lateinit var btnRestoreProgress: ImageButton // New Button

    enum class AuthMode { NONE, LOCAL, GOOGLE }
    private var authMode = AuthMode.NONE
    private val gson = Gson()
    private var isAdSkipEnabled = false
    
    private lateinit var googleSignInClient: GoogleSignInClient
    private var isPendingAddToFavorites = false
    
    // Auto-save timer for playback state
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            savePlaybackState()
            saveHandler.postDelayed(this, 5000) // Save every 5 seconds
        }
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            handleSignInResult(account)
        } catch (e: com.google.android.gms.common.api.ApiException) {
            // Log specific error code for debugging
            val msg = when (e.statusCode) {
                10 -> "Sign-in Failed: 10 (Developer Error). SHA-1 fingerprint mismatch?"
                12500 -> "Sign-in Failed: 12500. Updates required?"
                7 -> "Sign-in Failed: 7. Network error?"
                else -> "Google Sign-In Failed: ${e.statusCode}"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
    
    // Export/Import Launchers
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportPlaylistData(uri)
    }
    
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPlaylistData(uri)
    }

    // Removed dynamic BroadcastReceiver in favor of direct Controller
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkOverlayPermission()
        
        // Register Controller...
        PlaybackController.listener = this

        initUI()
        
        // Load Ad Skip preference
        val prefs = getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE)
        isAdSkipEnabled = prefs.getBoolean("ad_skip_enabled", false)
        
        initWebView()
        initGoogleAuth()
        
        // Handle Shared Intent
        handleIntent(intent)
        if (!isPendingAddToFavorites) {
            // Restore last playback state only if not loading a shared video
            restorePlaybackState()
        }
        
        // Start auto-save timer
        saveHandler.postDelayed(autoSaveRunnable, 5000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val videoId = extractVideoId(sharedText)
            if (videoId != null) {
                isPendingAddToFavorites = true
                
                // If initializing, currentVideoId will be picked up by initWebView -> loadWebVideo
                currentVideoId = videoId
                
                if (::webView.isInitialized) {
                     loadWebVideo(videoId)
                     Toast.makeText(this, "正在載入影片...", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // PlaybackController Implementation
    override fun onControllerPlay() {
        runOnUiThread { playVideo() }
    }

    override fun onControllerPause() {
        runOnUiThread { pauseVideo() }
    }

    override fun onControllerSeekForward() {
        runOnUiThread { 
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime += 30", null)
        }
    }

    override fun onControllerSeekBackward() {
        runOnUiThread {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime -= 15", null)
        }
    }
    
    // Lifecycle Methods
    
    override fun onPause() {
        super.onPause()
        // DO NOT PAUSE VIDEO HERE.
        // Screen off or background triggers this. We want playback to continue.
        
        // Android WebView Hack: Try to keep it running
        webView.onResume() 
        webView.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }


    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要最上層顯示權限")
                    .setMessage("為了確保背景播放和 PiP 模式在關閉螢幕時不被中斷，請授權應用程式顯示在其他應用程式上層。")
                    .setPositiveButton("去設定") { _, _ ->
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton("稍後再說", null)
                    .show()
            }
        }
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
            
            // Use desktop User-Agent (Generic) to trick YouTube
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
        
        // Increase WebView priority to prevent background killing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
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
                updatePlaybackService(isPlaying)
                
                // Inject Ad Skip script if enabled
                if (isAdSkipEnabled) {
                    injectAdSkipScript()
                }
                
                // Persistent Visibility Hack
                val js = """
                    (function() {
                        var style = document.createElement('style');
                        style.textContent = 'header, #header-bar, .ytd-masthead, #masthead-container, #related, #comments { display: none !important; }';
                        document.head.appendChild(style);
                        
                        function keepAlive() {
                            Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                            Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                            window.dispatchEvent(new Event('visibilitychange'));
                            window.dispatchEvent(new Event('focus'));
                        }
                        
                        keepAlive();
                        setInterval(keepAlive, 1000); // Re-apply every second
                    })()
                """.trimIndent()
                webView.evaluateJavascript(js, null)
                
                // Start checking for video title
                checkForVideoTitle()
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
        
        // Save state immediately so we don't lose the target position if app crashes during load
        savePlaybackState()

        val url = "https://www.youtube.com/watch?v=$videoId"
        val extraHeaders = HashMap<String, String>()
        extraHeaders["Referer"] = "https://www.youtube.com"
        
        webView.post {
            webView.loadUrl(url, extraHeaders)
            
            // Robust seeking
            if (startPosition > 0) {
                // We use a stronger seek script
                val jsSeek = """
                    (function() {
                        var targetTime = $startPosition;
                        var attempts = 0;
                        var maxAttempts = 120; // 60 seconds
                        
                        var checkVideo = setInterval(function() {
                            var v = document.querySelector('video');
                            if (v && v.readyState >= 1) { 
                                if (Math.abs(v.currentTime - targetTime) > 5) {
                                     console.log("Seeking to " + targetTime);
                                     v.currentTime = targetTime;
                                     v.play(); // Force play
                                } else {
                                     // Success
                                     clearInterval(checkVideo);
                                }
                            }
                            
                            attempts++;
                            if (attempts > maxAttempts) clearInterval(checkVideo);
                        }, 500);
                    })();
                """.trimIndent()
                
                // Delay injection slightly to allow page load
                webView.postDelayed({
                    webView.evaluateJavascript(jsSeek, null)
                }, 1000)
            }
            
            updatePlaybackService(true)
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
        btnRestoreProgress = findViewById(R.id.btn_restore_progress)

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

        // Login Profile button: Show account menu
        btnLoginProfile.setOnClickListener {
            if (authMode == AuthMode.NONE) {
                showLoginDialog()
            } else {
                showLoggedInMenu()
            }
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

        findViewById<Button>(R.id.btn_load_wl).setOnClickListener {
            loadWatchLater()
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
        
        btnRestoreProgress.setOnClickListener {
            manualRestoreState()
        }
    }

    private fun changeSpeed(delta: Float) {
        currentPlaybackRate += delta
        if (currentPlaybackRate < 0.25f) currentPlaybackRate = 0.25f
        if (currentPlaybackRate > 4.0f) currentPlaybackRate = 4.0f
        
        // Improve playback speed injection
        val js = """
            (function() {
                var v = document.querySelector('video');
                if (v) { 
                    v.playbackRate = $currentPlaybackRate; 
                    v.defaultPlaybackRate = $currentPlaybackRate;
                    return v.playbackRate;
                }
                return -1;
            })()
        """.trimIndent()
        
        webView.evaluateJavascript(js) { res ->
            // Use evaluate callback to confirm (res is the returned value)
             Toast.makeText(this, String.format(Locale.getDefault(), "Speed: %.3fx", currentPlaybackRate), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun manualRestoreState() {
        // Get up to 4 recent videos from history with valid progress
        val recentSaves = myHistoryVideos
            .filter { it.lastPosition > 5 } // Only show if played > 5 seconds
            .distinctBy { it.id }
            .take(4)
            
        if (recentSaves.isEmpty()) {
            Toast.makeText(this, "沒有最近的播放存檔", Toast.LENGTH_SHORT).show()
            return
        }
        
        val titles = recentSaves.map { 
            val cleanTitle = it.title.replace(Regex(" \\[.*?\\]$"), "")
            "$cleanTitle\n進度: ${formatTime(it.lastPosition)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("載入最近進度 (Top 4)")
            .setItems(titles) { _, which ->
                val video = recentSaves[which]
                
                // If it's the current video, just seek
                if (video.id == currentVideoId) {
                    webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime = ${video.lastPosition}", null)
                    // Also try to restore speed if possible? Assuming speed is global preference or we need to save it in HistoryVideo too.
                    // For now, keep current speed or use global.
                    Toast.makeText(this, "已跳轉至 ${formatTime(video.lastPosition)}", Toast.LENGTH_SHORT).show()
                } else {
                    // Load different video
                    if (::tvVideoTitle.isInitialized) tvVideoTitle.text = video.title.replace(Regex(" \\[.*?\\]$"), "")
                    if (::etVideoId.isInitialized) etVideoId.setText(video.id)
                    loadWebVideo(video.id, video.lastPosition)
                    Toast.makeText(this, "正在載入存檔...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFavoriteMenu() {
        // Get existing categories
        val categories = mySavedVideos.map { getVideoCategory(it.title) }.distinct().toMutableList()
        if (!categories.contains("Novel")) categories.add("Novel") // Default
        categories.sort()
        categories.add(0, "+ 新增目錄") // Add "New" option at top

        val items = categories.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("加入最愛 - 選擇目錄")
            .setItems(items) { _, which ->
                if (which == 0) {
                    // Create New Category
                    val input = EditText(this)
                    input.hint = "輸入目錄名稱"
                    AlertDialog.Builder(this)
                        .setTitle("建立新目錄")
                        .setView(input)
                        .setPositiveButton("確定") { _, _ ->
                            val newCategory = input.text.toString().trim()
                            if (newCategory.isNotEmpty()) {
                                addToPlaylist(newCategory)
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    addToPlaylist(items[which])
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getVideoCategory(titleString: String): String {
        val match = Regex(" \\[([^\\]]+)\\]$").find(titleString)
        return match?.groupValues?.get(1) ?: "Uncategorized"
    }
    
    private fun getCleanTitle(titleString: String): String {
        return titleString.replace(Regex(" \\[([^\\]]+)\\]$"), "")
    }

    private fun addToPlaylist(category: String) {
        if (mySavedVideos.none { it.id == currentVideoId && getVideoCategory(it.title) == category }) {
            val title = if (::tvVideoTitle.isInitialized) tvVideoTitle.text.toString() else "Unknown Title"
            // Store category in title for backward compatibility
            mySavedVideos.add(SavedVideo("$title [$category]", currentVideoId))
            savePlaylist()
            Toast.makeText(this, "已加入目錄 [$category]", Toast.LENGTH_SHORT).show()
        } else {
             Toast.makeText(this, "此影片已在目錄 [$category] 中", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun savePlaylist() {
        getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit { putString("my_playlist", gson.toJson(mySavedVideos)) }
    }

    // Old showPlaylist removed, moved to bottom with management features
    
    private fun showCategoryVideos(category: String) {
        val videosInCat = mySavedVideos.filter { getVideoCategory(it.title) == category }
        
        val titles = videosInCat.map { getCleanTitle(it.title) }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("目錄: $category")
            .setItems(titles) { _, which ->
                val video = videosInCat[which]
                loadWebVideo(video.id)
                if (::tvVideoTitle.isInitialized) tvVideoTitle.text = getCleanTitle(video.title)
                if (::etVideoId.isInitialized) etVideoId.setText(video.id)
                Toast.makeText(this, "正在載入: ${getCleanTitle(video.title)}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("刪除此目錄") { _, _ ->
                 AlertDialog.Builder(this)
                    .setTitle("刪除目錄")
                    .setMessage("確定要刪除目錄 [$category] 及其中所有影片嗎？")
                    .setPositiveButton("刪除") { _, _ ->
                        mySavedVideos.removeAll { getVideoCategory(it.title) == category }
                        savePlaylist()
                        Toast.makeText(this, "目錄已刪除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("返回", null)
            .show()
    }

    // Removed showPlaylistManager as it's replaced by directory management

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

    private fun checkForVideoTitle() {
        // Try multiple ways to get the title
        val jsCode = """
            (function() {
                // Method 1: Get from video-title element (common in playlists/feeds)
                var el = document.querySelector('#video-title');
                if (el) return el.textContent.trim();
                
                // Method 2: Get from h1.title (desktop video page)
                el = document.querySelector('h1.title');
                if (el) return el.textContent.trim();
                
                // Method 3: Get from .slim-video-information-title (mobile)
                el = document.querySelector('.slim-video-information-title');
                if (el) return el.textContent.trim();
                
                // Method 4: Fallback to document title
                return document.title.replace(' - YouTube', '');
            })()
        """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val rawTitle = result?.trim('"')?.replace("\\\"", "\"")
            if (!rawTitle.isNullOrEmpty() && rawTitle != "null" && rawTitle != "YouTube") {
                val titleChanged = ::tvVideoTitle.isInitialized && tvVideoTitle.text.toString() != rawTitle
                
                if (titleChanged) {
                    tvVideoTitle.text = rawTitle
                    // Update Service as well
                    updatePlaybackService(isPlaying)
                    // Also update History title if playing
                    addToHistory(currentVideoId)
                }
                
                // Handle pending favorite add from Share Intent
                if (isPendingAddToFavorites) {
                    if (::tvVideoTitle.isInitialized) {
                        // Ensure title is set before showing menu (in case titleChanged was false)
                        tvVideoTitle.text = rawTitle 
                        isPendingAddToFavorites = false
                        showFavoriteMenu()
                    }
                }
            } else {
                // Retry if title not found (SPA loading)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    checkForVideoTitle()
                }, 1000)
            }
        }
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

    private fun loadWatchLater() {
        val url = "https://www.youtube.com/playlist?list=WL"
        webView.loadUrl(url)
        Toast.makeText(this, "Loading Watch Later...", Toast.LENGTH_SHORT).show()
        
        // Start polling for content
        checkPlaylistContent(0)
    }

    private fun checkPlaylistContent(attempt: Int) {
        if (attempt > 20) { // 10 seconds timeout
            Toast.makeText(this, "Failed to extract playlist (Timeout)", Toast.LENGTH_SHORT).show()
            return
        }

        val js = """
            (function() {
                var videos = [];
                var elements = document.querySelectorAll('ytd-playlist-video-renderer');
                if (elements.length === 0) return '[]';
                
                for (var i = 0; i < elements.length; i++) {
                    var el = elements[i];
                    var titleEl = el.querySelector('#video-title');
                    var linkEl = el.querySelector('a#thumbnail');
                    if (titleEl && linkEl) {
                        var title = titleEl.textContent.trim();
                        var href = linkEl.getAttribute('href');
                        var idMatch = href.match(/[?&]v=([^&]+)/);
                        if (idMatch) {
                            videos.push({title: title, id: idMatch[1]});
                        }
                    }
                }
                return JSON.stringify(videos);
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            val json = result?.trim('"')?.replace("\\\"", "\"")
            
            if (json == "[]" || json == "null" || json.isNullOrEmpty()) {
                // Retry
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    checkPlaylistContent(attempt + 1)
                }, 1000) // Check every 1s
            } else {
                try {
                    val type = object : TypeToken<List<Map<String, String>>>() {}.type
                    val videos: List<Map<String, String>> = gson.fromJson(json, type)
                    
                    if (videos.isNotEmpty()) {
                        val savedVideos = videos.map { SavedVideo(it["title"] ?: "Unknown", it["id"] ?: "") }
                        showPlaylistResults("Watch Later", savedVideos)
                    } else {
                         // Empty list but valid JSON? Retry just in case
                         android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            checkPlaylistContent(attempt + 1)
                        }, 1000)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun showPlaylistResults(title: String, videos: List<SavedVideo>) {
        val items = videos.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                val video = videos[which]
                loadWebVideo(video.id)
            }
            .setPositiveButton("Close", null)
            .show()
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
        val adSkipStatus = if (isAdSkipEnabled) "ON" else "OFF"
        val options = arrayOf(
            "Sign in with Google", 
            "Local Guest",
            "🔇 自動音量調整: $adSkipStatus"
        )
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when (which) {
                0 -> signInLauncher.launch(googleSignInClient.signInIntent)
                1 -> {
                    authMode = AuthMode.LOCAL
                    updateLoginIcon()
                }
                2 -> toggleAdSkip()
            }
        }.show()
    }

    private fun showLoggedInMenu() {
        val adSkipStatus = if (isAdSkipEnabled) "ON" else "OFF"
        val options = arrayOf(
            "My YouTube Videos",
            "📺 Watch Later (WebView)",
            "🔇 自動音量調整: $adSkipStatus",
            "Sign Out"
        )
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when (which) {
                0 -> fetchUserVideos()
                1 -> fetchWatchLaterWebView()
                2 -> toggleAdSkip()
                3 -> googleSignInClient.signOut().addOnCompleteListener {
                    authMode = AuthMode.NONE
                    updateLoginIcon()
                }
            }
        }.show()
    }
    
    private fun toggleAdSkip() {
        isAdSkipEnabled = !isAdSkipEnabled
        getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit {
            putBoolean("ad_skip_enabled", isAdSkipEnabled)
        }
        
        val status = if (isAdSkipEnabled) "已開啟" else "已關閉"
        val msg = if (isAdSkipEnabled) "偵測到異常音量時將自動降低音量" else "自動音量調整已停用"
        
        Toast.makeText(this, "自動音量調整 $status\n$msg", Toast.LENGTH_SHORT).show()
        
        // Apply immediately if enabled
        if (isAdSkipEnabled) {
            injectAdSkipScript()
        } else {
            // Reload to clear script if disabled
            webView.reload()
        }
    }
    
    private fun injectAdSkipScript() {
        val jsCode = """
            (function() {
                if (window.adSkipperInterval) return; // Prevent multiple injections
                
                console.log("Volume Control Started");
                window.adSkipperInterval = setInterval(function() {
                    try {
                        var video = document.querySelector('video');
                        var skipBtn = document.querySelector('.ytp-ad-skip-button') || 
                                     document.querySelector('.ytp-ad-skip-button-modern') || 
                                     document.querySelector('.ytp-skip-ad-button');
                        var adOverlay = document.querySelector('.ad-interrupting') || 
                                       document.querySelector('.video-ads .ad-container');
                        
                        // Check if overlay is present (implying loud promotional content)
                        if (adOverlay && adOverlay.children.length > 0) {
                            // High volume content detected
                            if (video && !video.muted) {
                                video.muted = true;
                                console.log("High Volume: Muted");
                            }
                            
                            if (skipBtn) {
                                skipBtn.click();
                                console.log("Volume Normalized");
                            }
                        } else {
                            // Normal content
                            if (video && video.muted) {
                                video.muted = false; // Unmute
                                console.log("Normal Volume: Unmuted");
                            }
                        }
                    } catch (e) {
                        console.error("Volume Control Error", e);
                    }
                }, 1000);
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(jsCode, null)
    }
    
    // ========== WebView Playlist Functions ==========
    
    data class PlaylistVideo(
        val title: String,
        val id: String
    )
    
    private var isExtractingPlaylist = false
    
    private fun fetchWatchLaterWebView() {
        if (isExtractingPlaylist) {
            Toast.makeText(this, "正在讀取播放清單,請稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        
        isExtractingPlaylist = true
        
        // Show loading dialog
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("讀取 YouTube 稍後觀看")
            .setMessage("正在載入播放清單,請稍候...")
            .setCancelable(false)
            .create()
        loadingDialog.show()
        
        // Load Watch Later playlist
        webView.loadUrl("https://www.youtube.com/playlist?list=WL")
        
        // Wait for page to load then extract data
        webView.postDelayed({
            extractPlaylistData { videos ->
                loadingDialog.dismiss()
                isExtractingPlaylist = false
                
                if (videos.isEmpty()) {
                    Toast.makeText(this, "未找到影片或需要登入 YouTube", Toast.LENGTH_LONG).show()
                    // Optionally show YouTube login page
                    showYouTubeLoginPrompt()
                } else {
                    showPlaylistDialog(videos, "YouTube 稍後觀看")
                }
            }
        }, 3000) // Wait 3 seconds for page to load
    }
    
    private fun extractPlaylistData(callback: (List<PlaylistVideo>) -> Unit) {
        val jsCode = """
            (function() {
                const videos = [];
                
                // Try multiple selectors for compatibility
                const selectors = [
                    'ytd-playlist-video-renderer',
                    'ytd-video-renderer',
                    'ytd-compact-video-renderer'
                ];
                
                for (const selector of selectors) {
                    const items = document.querySelectorAll(selector);
                    if (items.length > 0) {
                        items.forEach(item => {
                            try {
                                // Try different title selectors
                                const titleEl = item.querySelector('#video-title') || 
                                               item.querySelector('a#video-title-link') ||
                                               item.querySelector('.title');
                                
                                // Try different link selectors  
                                const linkEl = item.querySelector('a#thumbnail') ||
                                              item.querySelector('a.ytd-thumbnail') ||
                                              item.querySelector('a[href*="watch"]');
                                
                                if (titleEl && linkEl) {
                                    const title = titleEl.textContent.trim();
                                    const href = linkEl.href;
                                    
                                    // Extract video ID from URL
                                    const match = href.match(/[?&]v=([^&]+)/);
                                    const videoId = match ? match[1] : null;
                                    
                                    if (videoId && title) {
                                        videos.push({
                                            title: title,
                                            id: videoId
                                        });
                                    }
                                }
                            } catch(e) {
                                console.error('Error extracting video:', e);
                            }
                        });
                        
                        if (videos.length > 0) break; // Found videos, stop trying
                    }
                }
                
                return JSON.stringify(videos);
            })()
        """.trimIndent()
        
        webView.evaluateJavascript(jsCode) { result ->
            try {
                // Remove quotes and unescape
                val json = result?.trim('"')?.replace("\\\"", "\"")?.replace("\\n", "") ?: "[]"
                
                // Parse JSON
                val type = object : TypeToken<List<PlaylistVideo>>() {}.type
                val videos: List<PlaylistVideo> = gson.fromJson(json, type)
                
                callback(videos)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(emptyList())
            }
        }
    }
    
    private fun showYouTubeLoginPrompt() {
        AlertDialog.Builder(this)
            .setTitle("需要登入 YouTube")
            .setMessage("請先在應用中登入 YouTube 帳號以訪問播放清單。\n\n點擊確定將開啟 YouTube 首頁供您登入。")
            .setPositiveButton("確定") { _, _ ->
                webView.loadUrl("https://www.youtube.com")
                Toast.makeText(this, "請在 YouTube 首頁完成登入", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showPlaylistDialog(videos: List<PlaylistVideo>, title: String) {
        val titles = videos.map { "${it.title}" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("$title (${videos.size} 個影片)")
            .setItems(titles) { _, which ->
                val video = videos[which]
                loadWebVideo(video.id)
                if (::tvVideoTitle.isInitialized) tvVideoTitle.text = video.title
                if (::etVideoId.isInitialized) etVideoId.setText(video.id)
                Toast.makeText(this, "正在載入: ${video.title}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("全部導入到收藏") { _, _ ->
                importPlaylistToFavorites(videos)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun importPlaylistToFavorites(videos: List<PlaylistVideo>) {
        var imported = 0
        videos.forEach { video ->
            if (mySavedVideos.none { it.id == video.id }) {
                mySavedVideos.add(SavedVideo("${video.title} [YouTube Playlist]", video.id))
                imported++
            }
        }
        
        if (imported > 0) {
            getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit {
                putString("my_playlist", gson.toJson(mySavedVideos))
            }
            Toast.makeText(this, "已導入 $imported 個影片到收藏", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "所有影片都已在收藏中", Toast.LENGTH_SHORT).show()
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

    private fun loadPipeline() { 
        // Renamed/Placeholder for old loadPlaylist caller if exists, but we use explicit calls now
        // Keeping name safe or removing if unused. 
        // Based on logic, I will restart the implementation below cleanly.
    }
    
    private fun showPlaylist() {
        if (mySavedVideos.isEmpty()) {
            Toast.makeText(this, "收藏清單是空的", Toast.LENGTH_SHORT).show()
             // Allow import even if empty
             AlertDialog.Builder(this)
                .setTitle("收藏清單")
                .setMessage("目前沒有收藏影片")
                .setPositiveButton("匯入備份") { _, _ -> importLauncher.launch(arrayOf("application/json")) }
                .setNegativeButton("關閉", null)
                .show()
            return
        }
        
        // Group by Category
        val categories = mySavedVideos.map { getVideoCategory(it.title) }.distinct().sorted()
        val items = categories.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("我的收藏 - 目錄")
            .setItems(items) { _, which ->
                showCategoryVideos(items[which])
            }
            .setPositiveButton("管理/備份") { _, _ ->
                 showManageMenu()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showManageMenu() {
        val options = arrayOf("📤 匯出收藏 (備份)", "📥 匯入收藏 (還原)")
        AlertDialog.Builder(this)
            .setTitle("管理收藏")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportLauncher.launch("yt_playlist_backup_${System.currentTimeMillis()}.json")
                    1 -> importLauncher.launch(arrayOf("application/json"))
                }
            }
            .show()
    }
    
    private fun exportPlaylistData(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val json = gson.toJson(mySavedVideos)
                outputStream.write(json.toByteArray())
            }
            Toast.makeText(this, "匯出成功！", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "匯出失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun importPlaylistData(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val json = inputStream.bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<SavedVideo>>() {}.type
                val imported: List<SavedVideo> = gson.fromJson(json, type)
                
                AlertDialog.Builder(this)
                    .setTitle("匯入確認")
                    .setMessage("找到 ${imported.size} 個影片。\n\n選擇匯入方式：")
                    .setPositiveButton("合併 (Merge)") { _, _ ->
                        var count = 0
                        imported.forEach { video ->
                            if (mySavedVideos.none { it.id == video.id }) {
                                mySavedVideos.add(video)
                                count++
                            }
                        }
                        savePlaylist()
                        Toast.makeText(this, "已合併 $count 個新影片", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("覆蓋 (Replace)") { _, _ ->
                         AlertDialog.Builder(this)
                            .setTitle("警告")
                            .setMessage("這將清空您目前的所有收藏並替換為備份檔案。\n確定嗎？")
                            .setPositiveButton("確定覆蓋") { _, _ ->
                                mySavedVideos.clear()
                                mySavedVideos.addAll(imported)
                                savePlaylist()
                                Toast.makeText(this, "已還原收藏清單", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .setNeutralButton("取消", null)
                    .show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "匯入失敗: 檔案格式錯誤", Toast.LENGTH_SHORT).show()
        }
    }

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


    
    private fun showHistory() {
        if (myHistoryVideos.isEmpty()) {
            Toast.makeText(this, "播放歷史是空的", Toast.LENGTH_SHORT).show()
            return
        }
        
        val titles = myHistoryVideos.map { 
            val timeAgo = getTimeAgo(it.timestamp)
            val cleanTitle = it.title.replace(Regex(" \\[.*?\\]$"), "")
            "$cleanTitle\n$timeAgo"
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
            val cleanTitle = video.title.replace(Regex(" \\[.*?\\]$"), "")
            "${index + 1}. $cleanTitle\n   $timeAgo"
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
                    
                    // Update History with progress
                    val historyIndex = myHistoryVideos.indexOfFirst { it.id == currentVideoId }
                    if (historyIndex != -1) {
                        try {
                            val oldItem = myHistoryVideos[historyIndex]
                            myHistoryVideos[historyIndex] = oldItem.copy(
                                lastPosition = position,
                                timestamp = System.currentTimeMillis(),
                                title = if (::tvVideoTitle.isInitialized) tvVideoTitle.text.toString() else oldItem.title
                            )
                            saveHistory()
                        } catch (e: Exception) { e.printStackTrace() }
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
                            // Speed display removed
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
    

    
    override fun onStop() {
        super.onStop()
        savePlaybackState()
        if (isPlaying) {
             // Redundant but safe - ensure timers are running even if stoppped
             webView.resumeTimers()
        }
    }

    override fun onDestroy() {
        // Save state before destroying
        savePlaybackState()
        
        // Stop auto-save timer
        saveHandler.removeCallbacks(autoSaveRunnable)
        
        // Unregister controller
        PlaybackController.listener = null
        
        stopService(Intent(this, MediaPlaybackService::class.java))
        super.onDestroy()
    }
}
