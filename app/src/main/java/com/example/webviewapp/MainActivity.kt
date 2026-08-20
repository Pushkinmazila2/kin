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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
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
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var fabPlayVideo: FloatingActionButton
    private var detectedM3u8Url: String? = null
    private var pendingPosterUrl: String? = null

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
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

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

                        extractCurrentSeriesInfo { season, episode ->
                            val vodId = extractVodId(webView.url) ?: webView.url
                            if (vodId != null) {
                                val sharedPreferences = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
                                if (!episode.isNullOrEmpty()) {
                                    sharedPreferences.edit().putString("${vodId}_episode", episode).apply()
                                }
                                if (!season.isNullOrEmpty()) {
                                    sharedPreferences.edit().putString("${vodId}_season", season).apply()
                                }
                            }
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
                extractPosterUrl { posterUrl ->
                    pendingPosterUrl = posterUrl
                    val vodId = extractVodId(webView.url) ?: webView.url
                    if (vodId != null) {
                        val prefs = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
                        val savedPosition = prefs.getLong(vodId, 0L)
                        val savedTimestamp = prefs.getString("${vodId}_time", "")
                        val savedEpisode = prefs.getString("${vodId}_episode", "")
                        val savedSeason = prefs.getString("${vodId}_season", "")

                        if (savedPosition > 0L) {
                            val titleText = when {
                                !savedEpisode.isNullOrEmpty() && !savedSeason.isNullOrEmpty() ->
                                    "Продолжить: $savedSeason сезон · $savedEpisode?"
                                !savedEpisode.isNullOrEmpty() -> "Продолжить: $savedEpisode?"
                                !savedSeason.isNullOrEmpty() -> "Продолжить: $savedSeason сезон?"
                                else -> "Продолжить просмотр?"
                            }
                            val messageText = if (!savedTimestamp.isNullOrEmpty()) {
                                "Момент: ${formatTime(savedPosition)}\n(Смотрели: $savedTimestamp)"
                            } else {
                                "Вы остановились на моменте ${formatTime(savedPosition)}"
                            }

                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle(titleText)
                                .setMessage(messageText)
                                .setPositiveButton("Продолжить") { _, _ -> startBuiltInPlayer(m3u8Url, savedPosition) }
                                .setNegativeButton("Сначала") { _, _ ->
                                    prefs.edit().apply {
                                        remove(vodId)
                                        remove("${vodId}_time")
                                        remove("${vodId}_episode")
                                        remove("${vodId}_season")
                                    }.apply()
                                    startBuiltInPlayer(m3u8Url, 0L)
                                }
                                .setCancelable(true)
                                .show()
                        } else {
                            startBuiltInPlayer(m3u8Url, 0L)
                        }
                    } else {
                        startBuiltInPlayer(m3u8Url, 0L)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), Context.RECEIVER_EXPORTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun startBuiltInPlayer(url: String, startPosition: Long = 0L) {
        playerView.visibility = View.VISIBLE
        fabPlayVideo.visibility = View.GONE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemUi()

        // Не даём экрану засыпать и уменьшать яркость во время воспроизведения
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Паузим WebView, чтобы не было двойного воспроизведения и лишней нагрузки на память
        webView.onPause()

        // 1. Создаём плеер и передаём его сервису ДО запуска,
        //    чтобы сервис использовал тот же плеер (устраняет гонку)
        val player = PlaybackService.playerInstance ?: ExoPlayer.Builder(this).build().also {
            PlaybackService.playerInstance = it
        }
        
        exoPlayer = player
        playerView.player = player
        playerView.setFullscreenButtonClickListener { stopBuiltInPlayer() }

        // 2. Запускаем сервис воспроизведения (он использует уже созданный плеер)
        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 3. Подтягиваем метаданные серии для пульта в шторке
        val vodId = extractVodId(webView.url) ?: webView.url
        val prefs = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
        val episodeTitle = prefs.getString("${vodId}_episode", "Просмотр видео") ?: "Просмотр видео"
        val savedSeason = prefs.getString("${vodId}_season", "")

        // Подбираем название сериала: в приоритете сохранённое название серии,
        // иначе заголовок страницы WebView
        val baseTitle = extractSeriesTitle(vodId)
            ?: webView.title?.takeIf { it.isNotBlank() && it != DEFAULT_URL }
            ?: episodeTitle

        // Добавляем сезон/серию в заголовок уведомления, если известны
        val seriesTitle = when {
            baseTitle == episodeTitle && !savedSeason.isNullOrEmpty() && !episodeTitle.isNullOrEmpty() ->
                "$baseTitle — $savedSeason сезон $episodeTitle"
            !savedSeason.isNullOrEmpty() && !episodeTitle.isNullOrEmpty() && !episodeTitle.contains("сезон") ->
                "$baseTitle — $savedSeason сезон $episodeTitle"
            else -> baseTitle
        }

        val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(seriesTitle)
            .setDisplayTitle(seriesTitle)
            .setArtist("Кинотеатр")
            // Указываем тип контента (Фильм/Сериал), чтобы пробить защиту экрана блокировки
            .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MOVIE)
            .apply {
                // Постер из WebView → показываем в стандартном уведомлении Media3
                pendingPosterUrl?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(mediaMetadata)
            .build()

        player.setMediaItem(mediaItem)
        
        if (startPosition > 0L) {
            player.seekTo(startPosition)
        }
        
        player.prepare()
        player.play()
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

            if (exoPlayer == null || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
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
        // 1. Сначала останавливаем плеер, пока он ещё жив
        exoPlayer?.let { player ->
            val currentWebUrl = webView.url 
            if (currentWebUrl != null) {
                val vodId = extractVodId(currentWebUrl) ?: currentWebUrl
                val sharedPreferences = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
                val currentPosition = player.currentPosition
                val duration = player.duration

                if (currentPosition > 5000 && (duration == -1L || currentPosition < duration - 5000)) {
                    sharedPreferences.edit().putLong(vodId, currentPosition).apply()
                    val timestamp = SimpleDateFormat("dd.MM в HH:mm", Locale.getDefault()).format(Date())
                    sharedPreferences.edit().putString("${vodId}_time", timestamp).apply()
                } else {
                    sharedPreferences.edit().apply {
                        remove(vodId)
                        remove("${vodId}_time")
                        remove("${vodId}_episode")
                        remove("${vodId}_season")
                    }.apply()
                }
            }
            player.stop()
        }
        
        exoPlayer = null
        playerView.player = null
        playerView.visibility = View.GONE

        // Разрешаем экрану снова засыпать
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Теперь останавливаем сервис (он освободит плеер в onDestroy)
        val serviceIntent = Intent(this, PlaybackService::class.java)
        stopService(serviceIntent)

        // 3. Возобновляем WebView
        webView.onResume()
        
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
        // Не останавливаем плеер при уходе в фон/PiP — это вызывало вылет.
        // Плеер останавливается только при реальном завершении Activity (onDestroy).
    }

    override fun onDestroy() {
        super.onDestroy()
        if (exoPlayer != null) {
            stopBuiltInPlayer()
        }
        try { unregisterReceiver(pipReceiver) } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Достаёт URL постера со страницы WebView.
     * Сначала ищет #media .poster img, затем og:image.
     * Результат передаёт в колбэк (на главном потоке).
     */
    private fun extractPosterUrl(onResult: (String?) -> Unit) {
        webView.evaluateJavascript(
            """
            (function() {
                var url = null;

                // 1. Постер из блока #media .poster
                var mediaBlock = document.getElementById('media');
                if (mediaBlock) {
                    var poster = mediaBlock.querySelector('.poster');
                    var img = poster ? poster.querySelector('img') : null;
                    if (img) {
                        url = img.getAttribute('src') || img.getAttribute('data-src') || null;
                    }
                }

                // 2. Fallback: og:image
                if (!url) {
                    var meta = document.querySelector('meta[property="og:image"]');
                    if (meta) url = meta.getAttribute('content');
                }

                if (url) {
                    try { url = new URL(url, location.href).href; } catch (e) {}
                }
                return url;
            })();
            """.trimIndent()
        ) { result: String? ->
            val cleanResult = result?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\/", "/")
            val isNull = cleanResult.isNullOrEmpty() || cleanResult == "null"
            runOnUiThread {
                onResult(if (isNull) null else cleanResult)
            }
        }
    }

    /**
     * Номер сезона/серии из плеера сайта (pjsdiv строки).
     * Активная серия подсвечена жёлтым цветом rgb(255,221,31).
     * Работает только на страницах сериалов (/serial/, /tv/, /animation/).
     */
    private fun extractCurrentSeriesInfo(onResult: (String?, String?) -> Unit) {
        val currentUrl = webView.url
        if (currentUrl == null ||
            !(currentUrl.contains("/serial/") || currentUrl.contains("/tv/") || currentUrl.contains("/animation/"))
        ) {
            runOnUiThread { onResult(null, null) }
            return
        }

        webView.evaluateJavascript(
            """
            (function() {
                var season = null;
                var episode = null;

                // Строки плеера: сезон ('N сезон') и серии ('N серия ...')
                var rows = document.querySelectorAll('pjsdiv[fid]');
                for (var i = 0; i < rows.length; i++) {
                    var text = (rows[i].textContent || '').trim();
                    if (text.length > 40) continue;

                    var isSeason = /^\d+\s*сезон\s*$/i.test(text);
                    var isEpisode = /^\d+\s*серия/i.test(text);
                    if (!isSeason && !isEpisode) continue;

                    // Ищем текстовый элемент строки (float:left, непустой) и проверяем его цвет
                    var children = rows[i].children;
                    for (var j = 0; j < children.length; j++) {
                        var child = children[j];
                        var childText = (child.textContent || '').trim();
                        if (!childText || child.querySelector('svg')) continue;

                        var color = window.getComputedStyle(child).color.replace(/\s/g, '');
                        var isActive = (color === 'rgb(255,221,31)');
                        if (isActive) {
                            if (isSeason) {
                                season = childText.replace(/[^\d]/g, '');
                            } else if (isEpisode) {
                                episode = childText;
                            }
                        }
                    }
                }

                return JSON.stringify({ season: season, episode: episode });
            })();
            """.trimIndent()
        ) { result: String? ->
            var parsedSeason: String? = null
            var parsedEpisode: String? = null
            try {
                val json = result?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\\"", "\"")
                if (!json.isNullOrEmpty() && json != "null") {
                    val obj = org.json.JSONObject(json)
                    parsedSeason = if (obj.isNull("season") || obj.getString("season") == "null") null else obj.getString("season")
                    parsedEpisode = if (obj.isNull("episode") || obj.getString("episode") == "null") null else obj.getString("episode")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val season = parsedSeason
            val episode = parsedEpisode
            runOnUiThread { onResult(season, episode) }
        }
    }

    private fun extractVodId(url: String?): String? {
        if (url == null) return null
        val regex = Regex("/(?:serial|tv|animation|vod)/(\\d+)")
        val matchResult = regex.find(url)
        return matchResult?.groups?.get(1)?.value
    }

    private fun extractSeriesTitle(vodId: String?): String? {
        if (vodId == null) return null
        val prefs = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
        return prefs.getString("${vodId}_series", null)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun onEpisodeDetected(episodeInfo: String) {
            runOnUiThread {
                val vodId = extractVodId(webView.url) ?: webView.url
                if (vodId != null && episodeInfo.isNotEmpty() && episodeInfo != "null") {
                    val sharedPreferences = getSharedPreferences("PlayerCache", Context.MODE_PRIVATE)
                    sharedPreferences.edit().putString("${vodId}_episode", episodeInfo.trim()).apply()
                }
            }
        }
    }
}