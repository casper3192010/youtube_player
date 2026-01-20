package com.example.ytnovelplayer

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
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
    
    private lateinit var btnPlayPause: ImageButton
    private lateinit var etVideoId: EditText
    private lateinit var controlsContainer: LinearLayout
    private lateinit var inputContainer: LinearLayout
    private lateinit var btnPip: Button
    private lateinit var rvRecommendations: RecyclerView
    private lateinit var btnLoginProfile: ImageView
    private lateinit var btnActionSave: LinearLayout
    private lateinit var tvVideoTitle: TextView

    enum class AuthMode { NONE, LOCAL, GOOGLE }
    private var authMode = AuthMode.NONE
    private val gson = Gson()
    
    private val speeds = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f)
    private var currentSpeedIndex = 2

    private lateinit var googleSignInClient: GoogleSignInClient

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task.result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        initWebView()
        initGoogleAuth()
        initRecyclerView()
    }

    private fun initWebView() {
        webView = findViewById(R.id.youtube_webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val css = """
                    javascript:(function() {
                        var style = document.createElement('style');
                        style.innerHTML = 'header, #header-bar, .ytd-masthead, #masthead-container, #related, #comments, .item-video-recommendation, .action-panel-container, .player-controls-bottom { display: none !important; } .video-player-container { padding-top: 0 !important; }';
                        document.head.appendChild(style);
                    })()
                """.trimIndent()
                webView.loadUrl(css)
            }
        }
        webView.webChromeClient = WebChromeClient()
        
        loadWebVideo(currentVideoId)
    }

    private fun loadWebVideo(videoId: String) {
        currentVideoId = videoId
        val url = "https://m.youtube.com/watch?v=$videoId"
        val extraHeaders = HashMap<String, String>()
        extraHeaders["Referer"] = "https://www.google.com"
        webView.loadUrl(url, extraHeaders)
        
        isPlaying = true
        // 安全地更新 UI
        webView.post {
            if (::btnPlayPause.isInitialized) {
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }
    }

    private fun initGoogleAuth() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(YouTubeScopes.YOUTUBE_READONLY))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) authMode = AuthMode.GOOGLE
    }

    private fun initUI() {
        etVideoId = findViewById(R.id.et_video_id)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        controlsContainer = findViewById(R.id.controls_container)
        inputContainer = findViewById(R.id.input_container)
        btnPip = findViewById(R.id.btn_pip)
        btnLoginProfile = findViewById(R.id.btn_login_profile)
        btnActionSave = findViewById(R.id.btn_action_save)
        tvVideoTitle = findViewById(R.id.tv_video_title)

        loadPlaylist()
        updateLoginIcon()

        btnLoginProfile.setOnClickListener {
            if (authMode != AuthMode.NONE) showLoggedInMenu() else showLoginDialog()
        }

        findViewById<Button>(R.id.btn_load).setOnClickListener {
            val input = etVideoId.text.toString().trim()
            if (input.isNotEmpty()) {
                val videoId = extractVideoId(input)
                if (videoId != null) loadWebVideo(videoId)
                else Toast.makeText(this, "Invalid YouTube URL or ID", Toast.LENGTH_SHORT).show()
            }
        }
        
        findViewById<ImageButton>(R.id.btn_rewind).setOnClickListener {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime -= 15", null)
        }
        
        findViewById<ImageButton>(R.id.btn_forward).setOnClickListener {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime += 30", null)
        }
        
        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                webView.evaluateJavascript("document.getElementsByTagName('video')[0].pause()", null)
                btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
            } else {
                webView.evaluateJavascript("document.getElementsByTagName('video')[0].play()", null)
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
            isPlaying = !isPlaying
        }
        
        val btnSpeed = findViewById<Button>(R.id.btn_speed)
        btnSpeed.setOnClickListener {
            currentSpeedIndex = (currentSpeedIndex + 1) % speeds.size
            val speed = speeds[currentSpeedIndex]
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].playbackRate = $speed", null)
            btnSpeed.text = String.format(Locale.getDefault(), "Speed: %.2fx", speed)
        }
        
        btnPip.setOnClickListener { enterPiP() }
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
            tvVideoTitle.text = video.title
            etVideoId.setText(video.id)
        }.show()
    }

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    private fun initRecyclerView() {
        rvRecommendations = findViewById(R.id.rv_recommendations)
        rvRecommendations.layoutManager = LinearLayoutManager(this)
        val videos = listOf(VideoItem("Web Player Optimized", "System", "1M views", "Just now", "10:00"))
        rvRecommendations.adapter = VideoAdapter(videos) {}
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

    private fun addToPlaylist() {
        if (mySavedVideos.none { it.id == currentVideoId }) {
            mySavedVideos.add(SavedVideo(tvVideoTitle.text.toString(), currentVideoId))
            getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit { putString("my_playlist", gson.toJson(mySavedVideos)) }
        }
    }

    data class SavedVideo(val title: String, val id: String)
    private val mySavedVideos = mutableListOf<SavedVideo>()

    private fun loadPlaylist() {
        val json = getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).getString("my_playlist", null)
        if (json != null) mySavedVideos.addAll(gson.fromJson(json, object : TypeToken<List<SavedVideo>>() {}.type))
    }

    private fun showPlaylist() {
        val titles = mySavedVideos.map { it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("My Playlist").setItems(titles) { _, which ->
            val video = mySavedVideos[which]
            loadWebVideo(video.id)
            tvVideoTitle.text = video.title
        }.show()
    }
}
