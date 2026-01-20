package com.example.ytnovelplayer

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentVideoId = "M7lc1UVf-VE" 
    private var isPlaying = false
    
    private lateinit var btnPlayPause: ImageButton
    private lateinit var etVideoId: EditText
    private lateinit var tvVideoTitle: TextView
    private lateinit var btnLoginProfile: ImageView
    private lateinit var btnPip: Button
    
    enum class AuthMode { NONE, LOCAL, GOOGLE }
    private var authMode = AuthMode.NONE
    private val gson = Gson()
    
    private val speeds = listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f)
    private var currentSpeedIndex = 2

    private lateinit var googleSignInClient: GoogleSignInClient
    private val mySavedVideos = mutableListOf<VideoRecord>()
    private val myFavorites = mutableListOf<VideoRecord>()

    data class VideoRecord(val title: String, val id: String, val timestamp: Long = 0)

    private val handler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        initWebView()
        initGoogleAuth()
        loadAllData()
        
        handler.post(autoSaveRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentProgress()
        handler.removeCallbacks(autoSaveRunnable)
    }

    private fun initWebView() {
        webView = findViewById(R.id.youtube_webview)
        webView.settings.apply {
            @Suppress("SetJavaScriptEnabled")
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectCSS()
                resumeProgressForCurrentVideo()
            }
        }
        webView.webChromeClient = WebChromeClient()
        loadWebVideo(currentVideoId)
    }

    private fun injectCSS() {
        val css = "javascript:(function() { " +
                "var style = document.createElement('style');" +
                "style.innerHTML = 'header, #header-bar, .ytd-masthead, #masthead-container, #related, #comments { display: none !important; }';" +
                "document.head.appendChild(style);" +
                "})()"
        webView.loadUrl(css)
    }

    private fun loadWebVideo(videoId: String) {
        currentVideoId = videoId
        val url = "https://m.youtube.com/watch?v=$videoId"
        webView.loadUrl(url)
        isPlaying = true
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        }
    }

    private fun resumeProgressForCurrentVideo() {
        val prefs = getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE)
        val savedTime = prefs.getFloat("pos_$currentVideoId", 0f)
        if (savedTime > 5f) {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime = $savedTime", null)
            Toast.makeText(this, "Resuming from ${savedTime.toInt()}s", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrentProgress() {
        webView.evaluateJavascript("(function() { return document.getElementsByTagName('video')[0].currentTime; })()") { value ->
            val cleanValue = value?.replace("\"", "") ?: "0"
            val time = if (cleanValue == "null") 0f else cleanValue.toFloatOrNull() ?: 0f
            if (time > 0) {
                getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit {
                    putFloat("pos_$currentVideoId", time)
                }
                updateHistory(time)
            }
        }
    }

    private fun updateHistory(time: Float) {
        val existing = mySavedVideos.find { it.id == currentVideoId }
        if (existing != null) mySavedVideos.remove(existing)
        
        mySavedVideos.add(0, VideoRecord(tvVideoTitle.text.toString().ifEmpty { "Video $currentVideoId" }, currentVideoId, System.currentTimeMillis()))
        if (mySavedVideos.size > 20) mySavedVideos.removeAt(mySavedVideos.size - 1)
        
        saveAllData()
    }

    private fun initUI() {
        etVideoId = findViewById<EditText>(R.id.et_video_id)
        btnPlayPause = findViewById<ImageButton>(R.id.btn_play_pause)
        btnLoginProfile = findViewById<ImageView>(R.id.btn_login_profile)
        tvVideoTitle = findViewById<TextView>(R.id.tv_video_title)
        btnPip = findViewById<Button>(R.id.btn_pip)

        findViewById<Button>(R.id.btn_load).setOnClickListener {
            val input = etVideoId.text.toString().trim()
            if (input.isNotEmpty()) {
                val videoId = extractVideoId(input)
                if (videoId != null) loadWebVideo(videoId)
            }
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

        findViewById<ImageButton>(R.id.btn_rewind).setOnClickListener {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime -= 15", null)
        }

        findViewById<ImageButton>(R.id.btn_forward).setOnClickListener {
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].currentTime += 30", null)
        }

        findViewById<ImageButton>(R.id.btn_favorite).setOnClickListener { toggleFavorite() }
        findViewById<ImageButton>(R.id.btn_history).setOnClickListener { showHistory() }
        findViewById<ImageButton>(R.id.btn_action_save).setOnClickListener { showFavorites() }
        
        btnPip.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }
        }
        
        val btnSpeed = findViewById<Button>(R.id.btn_speed)
        btnSpeed.setOnClickListener {
            currentSpeedIndex = (currentSpeedIndex + 1) % speeds.size
            val speed = speeds[currentSpeedIndex]
            webView.evaluateJavascript("document.getElementsByTagName('video')[0].playbackRate = $speed", null)
            btnSpeed.text = String.format(Locale.getDefault(), "Speed: %.2fx", speed)
        }
    }

    private fun toggleFavorite() {
        val existing = myFavorites.find { it.id == currentVideoId }
        if (existing == null) {
            myFavorites.add(0, VideoRecord(tvVideoTitle.text.toString().ifEmpty { "Video $currentVideoId" }, currentVideoId))
            Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show()
        } else {
            myFavorites.remove(existing)
            Toast.makeText(this, "Removed from Favorites", Toast.LENGTH_SHORT).show()
        }
        saveAllData()
    }

    private fun showHistory() {
        val titles = mySavedVideos.map { it.title }.toTypedArray()
        if (titles.isEmpty()) { Toast.makeText(this, "No history", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("History").setItems(titles) { _, which ->
            loadWebVideo(mySavedVideos[which].id)
        }.show()
    }

    private fun showFavorites() {
        val titles = myFavorites.map { it.title }.toTypedArray()
        if (titles.isEmpty()) { Toast.makeText(this, "No favorites", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Favorites").setItems(titles) { _, which ->
            loadWebVideo(myFavorites[which].id)
        }.show()
    }

    private fun loadAllData() {
        val prefs = getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE)
        val historyJson = prefs.getString("history", null)
        val favJson = prefs.getString("favorites", null)
        val type = object : TypeToken<List<VideoRecord>>() {}.type
        
        if (historyJson != null) {
            val history: List<VideoRecord> = gson.fromJson(historyJson, type)
            mySavedVideos.clear()
            mySavedVideos.addAll(history)
        }
        if (favJson != null) {
            val favorites: List<VideoRecord> = gson.fromJson(favJson, type)
            myFavorites.clear()
            myFavorites.addAll(favorites)
        }
    }

    private fun saveAllData() {
        getSharedPreferences("YTPlayerPrefs", MODE_PRIVATE).edit {
            putString("history", gson.toJson(mySavedVideos))
            putString("favorites", gson.toJson(myFavorites))
        }
    }

    private fun extractVideoId(input: String): String? {
        if (input.length == 11) return input
        val uri = Uri.parse(input)
        return if (uri.host?.contains("youtube.com") == true) uri.getQueryParameter("v")
        else if (uri.host?.contains("youtu.be") == true) uri.path?.substring(1)
        else null
    }

    private fun initGoogleAuth() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }
}
