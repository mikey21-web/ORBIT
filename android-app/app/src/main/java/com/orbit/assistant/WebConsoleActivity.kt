package com.orbit.assistant

import android.app.Activity
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent

class WebConsoleActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AgentService.isRunning()) {
            try {
                startForegroundService(Intent(this, AgentService::class.java))
            } catch (_: Throwable) {
            }
        }

        web = WebView(this)
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val token = AgentToken.ensure(this@WebConsoleActivity)
                val script = "window.__ORBIT_EMBEDDED__=true;" +
                    "window.__ORBIT_TOKEN__='$token';" +
                    "window.__ORBIT_BASE__='http://127.0.0.1:8765';"
                view?.evaluateJavascript(script, null)
            }
        }
        web.loadUrl("file:///android_asset/web/index.html")
        setContentView(web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
