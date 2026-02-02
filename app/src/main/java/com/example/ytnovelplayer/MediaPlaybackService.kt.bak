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

    override fun onCreate() {
        super.onCreate()
        
        mediaSession = MediaSessionCompat(this, "MediaPlaybackService")
        
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
        mediaSession.setPlaybackState(stateBuilder.build())
        
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                sendBroadcast(Intent("ACTION_PLAY"))
            }
            override fun onPause() {
                sendBroadcast(Intent("ACTION_PAUSE"))
            }
            override fun onSkipToNext() {
                sendBroadcast(Intent("ACTION_NEXT"))
            }
            override fun onSkipToPrevious() {
                sendBroadcast(Intent("ACTION_PREV"))
            }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("VIDEO_TITLE") ?: "YouTube Novel Player"
        val isPlaying = intent?.getBooleanAsDefault("IS_PLAYING", false) ?: false
        
        val notification = buildNotification(title, isPlaying)
        startForeground(NOTIFICATION_ID, notification)
        
        return START_STICKY
    }

    private fun buildNotification(title: String, isPlaying: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", 
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play_arrow, "Play", 
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY))
        }

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
            .addAction(R.drawable.ic_replay_10, "Prev", 
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            // Play/Pause
            .addAction(playPauseAction)
            // Next (Forward 30s)
            .addAction(R.drawable.ic_forward_30, "Next", 
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
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
        mediaSession.release()
        super.onDestroy()
    }
}
