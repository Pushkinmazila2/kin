package com.example.webviewapp

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private val DEFAULT_URL = "https://kinvd.xyz"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI компонентов
        webView = findViewById(R.id.myWebView)
        drawerLayout = findViewById(R.id.drawerLayout)
        val btnOpenMenu: ImageButton = findViewById(R.id.btnOpenMenu)
        val etTargetUrl: EditText = findViewById(R.id.etTargetUrl)
        val btnSaveUrl: Button = findViewById(R.id.btnSaveUrl)

        // Настройка WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = WebViewClient()

        // Работа с памятью (SharedPreferences)
        val sharedPreferences = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val savedUrl = sharedPreferences.getString("saved_url", DEFAULT_URL) ?: DEFAULT_URL

        // Устанавливаем текущий адрес в поле ввода сайдбара и загружаем в WebView
        etTargetUrl.setText(savedUrl)
        webView.loadUrl(savedUrl)

        // Логика кнопки "Гамбургер" (открытие сайдбара)
        btnOpenMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Логика сохранения нового адреса
        btnSaveUrl.setOnClickListener {
            var inputUrl = etTargetUrl.text.toString().trim()
            
            if (inputUrl.isNotEmpty()) {
                // Если пользователь забыл ввести http/https, добавляем автоматически
                if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                    inputUrl = "https://$inputUrl"
                    etTargetUrl.setText(inputUrl)
                }

                // Сохраняем в память устройства
                sharedPreferences.edit().putString("saved_url", inputUrl).apply()
                
                // Перезагружаем WebView с новым адресом
                webView.loadUrl(inputUrl)
                
                // Закрываем сайдбар
                drawerLayout.closeDrawer(GravityCompat.START)
                Toast.makeText(this, "Адрес обновлен!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Введите корректный адрес", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Изменяем логику кнопки "Назад": если открыт сайдбар — сначала закрываем его
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
