package com.example.webviewapp

import android.content.Context
import android.os.Bundle
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
    
    // Переменные для плеера
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var fabPlayVideo: FloatingActionButton
    private var detectedM3u8Url: String? = null

    private val DEFAULT_URL = "https://kinvd.xyz"

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

        // Инициализация WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false // Разрешаем автоплей медиа сайту
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // НАСТРОЙКА ПЕРЕХВАТА ССЫЛОК
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()
                
                if (url != null && (url.contains(".m3u8") || url.contains("master.m3u8"))) {
                    detectedM3u8Url = url
                    
                    // Потоковые запросы идут в фоновом потоке, переключаемся на главный UI-поток
                    runOnUiThread {
                        if (fabPlayVideo.visibility != View.VISIBLE) {
                            fabPlayVideo.visibility = View.VISIBLE
                            Toast.makeText(this@MainActivity, "Видео-поток обнаружен!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // При переходе на новую страницу сбрасываем старую ссылку
                runOnUiThread {
                    fabPlayVideo.visibility = View.GONE
                    detectedM3u8Url = null
                }
            }
        }

        // Загрузка адреса
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

        // ЛОГИКА НАЖАТИЯ НА КНОПКУ ПЛЕЕРА
        fabPlayVideo.setOnClickListener {
            detectedM3u8Url?.let { url ->
                startBuiltInPlayer(url)
            }
        }
    }

    // ЗАПУСК ВСТРОЕННОГО ПЛЕЕРА EXOPLAYER С ПОДДЕРЖКОЙ ПОЛНОГО ЭКРАНА
        private fun startBuiltInPlayer(url: String) {
        // 1. ПОЛНОСТЬЮ СКРЫВАЕМ WEBVIEW, чтобы JS на сайте не перезагружал страницу при смене разрешения!
        webView.visibility = View.GONE
        webView.onPause()
        webView.pauseTimers()

        playerView.visibility = View.VISIBLE
        fabPlayVideo.visibility = View.GONE

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player
            
            // ОБРАБОТКА КНОПКИ ПОЛНОГО ЭКРАНА
            playerView.setFullscreenButtonClickListener { isFullscreen ->
                if (isFullscreen) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    hideSystemUi()
                } else {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    showSystemUi()
                }
            }

            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }
    }

    // Вспомогательная функция для скрытия шторки уведомлений и кнопок Android
    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // Вспомогательная функция для возврата стандартного интерфейса смартфона
    private fun showSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    // ОСТАНОВКА ПЛЕЕРА С ВОССТАНОВЛЕНИЕМ WEBVIEW
    private fun stopBuiltInPlayer() {
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
        playerView.player = null
        playerView.visibility = View.GONE
        
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemUi()
        
        // ВЕРТИКАЛЬНЫЙ РЕЖИМ ВОССТАНОВЛЕН: Оживляем и показываем WebView обратно
        webView.visibility = View.VISIBLE
        webView.onResume()
        webView.resumeTimers()
        
        if (detectedM3u8Url != null) {
            fabPlayVideo.visibility = View.VISIBLE
        }
    }

    // ИЗМЕНЯЕМ ЛОГИКУ НАЗАД: Если запущен плеер — закрываем плеер
    override fun onBackPressed() {
        if (playerView.visibility == View.VISIBLE) {
            stopBuiltInPlayer()
        } else if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // Важно освобождать ресурсы плеера, если приложение свернули
    override fun onStop() {
        super.onStop()
        if (playerView.visibility == View.VISIBLE) {
            stopBuiltInPlayer()
        }
    }
}
