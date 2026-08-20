package com.example.webviewapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var notificationReceiver: BroadcastReceiver? = null

    companion object {
        var playerInstance: ExoPlayer? = null
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.example.webviewapp.action.PLAY"
        const val ACTION_PAUSE = "com.example.webviewapp.action.PAUSE"
    }

    override fun onCreate() {
        super.onCreate()
        
        // 1. Создаем плеер внутри сервиса (только если ещё не создан)
        val player = playerInstance ?: ExoPlayer.Builder(this).build().also {
            playerInstance = it
        }

        // 2. Слушаем изменения состояния воспроизведения,
        //    чтобы обновлять кнопку в уведомлении (play/pause)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }
        })

        // 3. Настраиваем клик по уведомлению для возврата в приложение
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Инициализируем сессию Media3.
        //    В Media3 1.3.1 сессия активируется автоматически при создании
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
            
        // 5. Привязываем слушатель жизненного цикла Media3 для шторки
        setListener(Media3ServiceListener())

        // 6. Регистрируем приёмник для кнопок play/pause в уведомлении
        notificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PLAY -> playerInstance?.play()
                    ACTION_PAUSE -> playerInstance?.pause()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(notificationReceiver, filter)
        }

        // 7. Явно запускаем foreground-сервис, чтобы избежать
        //    ForegroundServiceDidNotStartInTimeException
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val isPlaying = playerInstance?.isPlaying == true
        val title = playerInstance?.mediaMetadata?.title?.toString() ?: "KinApp"
        
        // Кнопка play/pause: если играет — показываем паузу, иначе — play
        val actionIntent = Intent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY).apply {
            `package` = packageName
        }
        val actionPendingIntent = PendingIntent.getBroadcast(
            this, 1, actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            // Без строки статуса — только название и кнопка управления
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Показываем уведомление и кнопки на экране блокировки
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Категория медиа: включает управление с экрана блокировки,
            // системные громкие клавиши и вывод в шторке с кнопками
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            .setPriority(Notification.PRIORITY_LOW)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Пауза" else "Воспроизвести",
                actionPendingIntent
            )
            .build()
    }
    
    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Воспроизведение",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        notificationReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { e.printStackTrace() }
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        playerInstance = null
        super.onDestroy()
    }

    // Чистый слушатель для совместимости с Media3 1.3.1
    private inner class Media3ServiceListener : MediaSessionService.Listener
}