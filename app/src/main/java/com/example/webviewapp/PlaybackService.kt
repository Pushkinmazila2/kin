package com.example.webviewapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        var playerInstance: ExoPlayer? = null

        // ID и канал совпадают с DefaultMediaNotificationProvider (Media3):
        // fallback-заглушка будет просто перезаписана стандартным
        // медиа-уведомлением Media3, без появления двух уведомлений в шторке.
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "default_channel_id"
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Создаем плеер внутри сервиса (только если ещё не создан)
        val player = playerInstance ?: ExoPlayer.Builder(this).build().also {
            playerInstance = it
        }

        // 2. Настраиваем клик по уведомлению для возврата в приложение
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Инициализируем сессию Media3.
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        // 4. КЛЮЧЕВОЕ: регистрируем сессию в сервисе.
        //    Без этого MediaNotificationManager не подпишется на события плеера
        //    и никогда не опубликует стандартное медиа-уведомление с постром,
        //    кнопками и прогрессом.
        mediaSession?.let { addSession(it) }

        // 5. Привязываем слушатель жизненного цикла Media3 для шторки
        setListener(Media3ServiceListener())

        // 6. Fallback на время старта, чтобы избежать
        //    ForegroundServiceDidNotStartInTimeException.
        //    Как только плеер начнёт играть, Media3 перезапишет это
        //    уведомление своим стандартным (тот же ID = 1001).
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KinApp")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Воспроизведение",
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.let { removeSession(it) }
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