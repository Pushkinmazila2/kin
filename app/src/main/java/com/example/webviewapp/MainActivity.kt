package com.example.webviewapp

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var fabPlayVideo: FloatingActionButton
    private var detectedM3u8Url: String? = null

    private val DEFAULT_URL = "https://kinvd.xyz"

    private val ACTION_MEDIA_CONTROL = "media_control"
    private val EXTRA_CONTROL_TYPE = "control_type"
    private val CONTROL_TYPE_PLAY = 1
    private val CONTROL_TYPE_PAUSE = 2

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_CONTROL) return
            val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
            when (controlType) {
                CONTROL_TYPE_PLAY -> {
                    exoPlayer?.play()
                    updatePipParams(true)
                }
                CONTROL_TYPE_PAUSE -> {
                    exoPlayer?.pause()
                    updatePipParams(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.myWebView)
        drawerLayout = findViewById(R.id.drawerLayout)
        playerView = findViewById(R.id.playerView)
        fabPlayVideo = findViewById(R.id.fabPlayVideo)
        
        val btnOpenMenu: ImageButton = findViewById(R.id.btnOpenMenu)
        val etTargetUrl: EditText = findViewById(R.id.etTargetUrl)
        val btnSaveUrl: Button = findViewById(R.id.btnSaveUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerView.visibility == View.VISIBLE) {
                    enterPipMode()
                } else if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && (url.contains(".m3u8") || url.contains("master.m3u8"))) {
                    detectedM3u8Url = url
                    runOnUiThread {
                        if (fabPlayVideo.visibility != View.VISIBLE && playerView.visibility != View.VISIBLE) {
                            fabPlayVideo.visibility = View.VISIBLE
                            Toast.makeText(this@MainActivity, "Видео-поток обнаружен!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                runOnUiThread {
                    fabPlayVideo.visibility = View.GONE
                    detectedM3u8Url = null
                }
            }
        }

        val sharedPreferences = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val savedUrl = sharedPreferences.getString("saved_url", DEFAULT_URL) ?: DEFAULT_URL
        etTargetUrl.setText(savedUrl)
        webView.loadUrl(savedUrl)

        btnOpenMenu.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        btnSaveUrl.setOnClickListener {
            var inputUrl = etTargetUrl.text.toString().trim()
            if (inputUrl.isNotEmpty()) {
                if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                    inputUrl = "https://$inputUrl"
                    etTargetUrl.setText(inputUrl)
                }
                sharedPreferences.edit().putString("saved_url", inputUrl).apply()
                webView.loadUrl(inputUrl)
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        fabPlayVideo.setOnClickListener {
            detectedM3u8Url?.let { m3u8Url ->
                // Получаем текущий URL страницы сайта
                val currentWebUrl = webView.url

                if (currentWebUrl != null) {
                    val sharedPreferences = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
                    val savedPosition = sharedPreferences.getLong(currentWebUrl, 0L)

                    if (savedPosition > 0L) {
                        // Если нашли сохраненный момент для этой страницы, предлагаем выбор
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Продолжить просмотр?")
                            .setMessage("Вы остановились на моменте ${formatTime(savedPosition)}")
                            .setPositiveButton("Продолжить") { _, _ ->
                                startBuiltInPlayer(m3u8Url, savedPosition)
                            }
                            .setNegativeButton("Сначала") { _, _ ->
                                // Стираем кэш для этой страницы, если выбрали сначала
                                sharedPreferences.edit().remove(currentWebUrl).apply() 
                                startBuiltInPlayer(m3u8Url, 0L)
                            }
                            .setCancelable(true)
                            .show()
                    } else {
                        // Если для этой страницы истории нет, запускаем с нуля
                        startBuiltInPlayer(m3u8Url, 0L)
                    }
                } else {
                    // На всякий случай, если URL страницы вдруг пустой
                    startBuiltInPlayer(m3u8Url, 0L)
                }
            }
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), Context.RECEIVER_EXPORTED)

        }
    }

    private fun startBuiltInPlayer(url: String, startPosition: Long = 0L) {
        playerView.visibility = View.VISIBLE
        fabPlayVideo.visibility = View.GONE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemUi()

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            playerView.setFullscreenButtonClickListener { stopBuiltInPlayer() }

            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            
            if (startPosition > 0L) {
                player.seekTo(startPosition)
            }
            
            player.prepare()
            player.play()
        }
    }


    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val pipParams = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
            updatePipParams(exoPlayer?.isPlaying == true)
            enterPictureInPictureMode(pipParams)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    private fun updatePipParams(isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val actions = ArrayList<RemoteAction>()
            val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val title = if (isPlaying) "Пауза" else "Воспроизвести"
            val controlType = if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY

            val intent = Intent(ACTION_MEDIA_CONTROL).apply {
                `package` = packageName
                putExtra(EXTRA_CONTROL_TYPE, controlType)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                this, controlType, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            actions.add(RemoteAction(Icon.createWithResource(this, iconRes), title, title, pendingIntent))
            setPictureInPictureParams(PictureInPictureParams.Builder().setActions(actions).build())
        }
    }



    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val btnOpenMenu: ImageButton = findViewById(R.id.btnOpenMenu)

        if (isInPictureInPictureMode) {
            playerView.useController = false
            webView.visibility = View.GONE
            btnOpenMenu.visibility = View.GONE
            fabPlayVideo.visibility = View.GONE
        } else {
            playerView.useController = true
            webView.visibility = View.VISIBLE
            btnOpenMenu.visibility = View.VISIBLE
            if (exoPlayer == null) {
                stopBuiltInPlayer()
            } else {
                fabPlayVideo.visibility = View.GONE 
            }
        }
    }


    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun showSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun stopBuiltInPlayer() {
        exoPlayer?.let { player ->
            val currentWebUrl = webView.url 
            if (currentWebUrl != null) {
                val sharedPreferences = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
                val currentPosition = player.currentPosition
                val duration = player.duration

                if (currentPosition > 5000 && (duration == -1L || currentPosition < duration - 5000)) {
                    sharedPreferences.edit().putLong(currentWebUrl, currentPosition).apply()
                } else {
                    sharedPreferences.edit().remove(currentWebUrl).apply()
                }
            }
            player.stop()
            player.release()
        }
        exoPlayer = null
        playerView.player = null
        playerView.visibility = View.GONE
        
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemUi()
        webView.visibility = View.VISIBLE
        
        if (detectedM3u8Url != null) {
            fabPlayVideo.visibility = View.VISIBLE
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playerView.visibility == View.VISIBLE && exoPlayer != null) {
            enterPipMode()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode) {
            exoPlayer?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isInPictureInPictureMode) {
            stopBuiltInPlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pipReceiver) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
