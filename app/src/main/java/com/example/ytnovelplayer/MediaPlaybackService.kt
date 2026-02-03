package com.example.ytnovelplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

class MediaPlaybackService : Service() {



    private lateinit var mediaSession: MediaSessionCompat
    private val CHANNEL_ID = "playback_channel"
    private val NOTIFICATION_ID = 1
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private lateinit var audioManager: android.media.AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    
    // Audio Focus Listener
    private val audioFocusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss of audio focus, pause playback
                PlaybackController.triggerPause()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss of audio focus, pause playback
                PlaybackController.triggerPause()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lower the volume (optional, implementing pause for now)
                // In a real app we might lower volume, but for WebView video it's easier to just let it play or pause
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained focus, resume playback if we were playing
                PlaybackController.triggerPlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        audioManager = getSystemService(android.media.AudioManager::class.java)
        
        // Acquire WakeLock to keep CPU running during playback
        val powerManager = getSystemService(android.os.PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "YouTubeNovelPlayer:PlaybackWakeLock")
        
        mediaSession = MediaSessionCompat(this, "MediaPlaybackService")
        mediaSession.isActive = true 
        
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
        mediaSession.setPlaybackState(stateBuilder.build())
        
        
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { PlaybackController.triggerPlay() }
            override fun onPause() { PlaybackController.triggerPause() }
            override fun onSkipToNext() { PlaybackController.triggerSeekForward() }
            override fun onSkipToPrevious() { PlaybackController.triggerSeekBackward() }
        })


        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for YouTube Novel Player"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }


    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            val res = audioManager.requestAudioFocus(audioFocusRequest!!)
            res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
            res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.example.ytnovelplayer.action.UPDATE_PLAYBACK_STATE") {
            val position = intent.getLongExtra("POSITION", 0L)
            val duration = intent.getLongExtra("DURATION", 0L)
            val speed = intent.getFloatExtra("SPEED", 1.0f)
            val isPlaying = intent.getBooleanExtra("IS_PLAYING", false)
            
            // Update MediaSession State
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    position,
                    speed
                )
            
            mediaSession.setPlaybackState(stateBuilder.build())
            
            // Update Metadata (Duration)
            if (duration > 0) {
                 val metadata = android.support.v4.media.MediaMetadataCompat.Builder()
                    .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, "YouTube Novel Player") 
                    .build()
                 mediaSession.setMetadata(metadata)
            }
            
            return START_NOT_STICKY
        }
        
        val title = intent?.getStringExtra("VIDEO_TITLE") ?: "YouTube Novel Player"
        val isPlaying = intent?.getBooleanAsDefault("IS_PLAYING", false) ?: false
        
        // Ensure MediaSession is always active to receive button events
        mediaSession.isActive = true
        
        // Manage WakeLock and Audio Focus
        if (isPlaying) {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
            requestAudioFocus()
            
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
                    .build()
            )
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
                    .build()
            )
        }
        
        val notification = buildNotification(title, isPlaying)
        startForeground(NOTIFICATION_ID, notification)
        
        return START_NOT_STICKY
    }


    private fun buildNotification(title: String, isPlaying: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Use Explicit Broadcasts to trigger MainActivity actions directly (same as PiP)
        
        val playPendingIntent = PendingIntent.getBroadcast(
            this, 100, 
            Intent("com.example.ytnovelplayer.action.CONTROL_PLAY").setPackage(packageName), 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playAction = NotificationCompat.Action(
            R.drawable.ic_play_arrow, "Play", playPendingIntent
        )
        
        val pausePendingIntent = PendingIntent.getBroadcast(
            this, 101, 
            Intent("com.example.ytnovelplayer.action.CONTROL_PAUSE").setPackage(packageName), 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseAction = NotificationCompat.Action(
            R.drawable.ic_pause, "Pause", pausePendingIntent
        )
        
        val nextPendingIntent = PendingIntent.getBroadcast(
            this, 102, 
            Intent("com.example.ytnovelplayer.action.CONTROL_NEXT").setPackage(packageName), 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_forward_30, "Next", nextPendingIntent
        )
        
        val prevPendingIntent = PendingIntent.getBroadcast(
            this, 103, 
            Intent("com.example.ytnovelplayer.action.CONTROL_PREV").setPackage(packageName), 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevAction = NotificationCompat.Action(
            R.drawable.ic_replay_10, "Prev", prevPendingIntent
        )

        val playPauseAction = if (isPlaying) pauseAction else playAction

        // Truncate title if too long for compact view
        val displayTitle = if (title.length > 40) "${title.take(37)}..." else title

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(displayTitle)
            .setContentText("YouTube Novel Player")
            .setSmallIcon(R.drawable.ic_play_arrow)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Previous (Rewind 15s)
            .addAction(prevAction)
            // Play/Pause
            .addAction(playPauseAction)
            // Next (Forward 30s)
            .addAction(nextAction)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2) // Show all 3 buttons in compact view
                .setShowCancelButton(false))
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .build()
    }

    private fun Intent?.getBooleanAsDefault(key: String, def: Boolean): Boolean {
        return this?.getBooleanExtra(key, def) ?: def
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        mediaSession.release()
        super.onDestroy()
    }
}
