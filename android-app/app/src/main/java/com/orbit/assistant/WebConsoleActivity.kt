package com.orbit.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat

class WebConsoleActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val appOrigin = "https://appassets.androidplatform.net"
    private val assetUrl = "$appOrigin/assets/web/index.html"
    private val requestMic = 410
    private val requestOverlay = 411

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AgentService.isRunning()) {
            try {
                startForegroundService(Intent(this, AgentService::class.java))
            } catch (_: Throwable) {
            }
        }
        requestVoiceSetup()

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return request?.let { assetLoader.shouldInterceptRequest(it.url) }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return !url.startsWith(appOrigin)
            }

        }
        val token = AgentToken.ensure(this)
        val bridge = "window.__ORBIT_EMBEDDED__=true;" +
            "window.__ORBIT_TOKEN__='$token';" +
            "window.__ORBIT_BASE__='http://127.0.0.1:8765';"
        WebViewCompat.addDocumentStartJavaScript(web, bridge, setOf("*"))
        web.loadUrl(assetUrl)
        setContentView(web)
    }

    private fun requestVoiceSetup() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), requestMic)
            return
        }
        maybeRequestOverlay()
    }

    private fun maybeRequestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val prefs = getSharedPreferences("voice", MODE_PRIVATE)
            if (!prefs.getBoolean("overlay_asked", false)) {
                prefs.edit().putBoolean("overlay_asked", true).apply()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                try {
                    startActivityForResult(intent, requestOverlay)
                    return
                } catch (_: Throwable) {
                }
            }
        }
        startWakeService()
    }

    private fun startWakeService() {
        try {
            startForegroundService(Intent(this, DiyaWakeService::class.java))
        } catch (t: Throwable) {
            Toast.makeText(this, "Hey Diya could not start: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != requestMic) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            maybeRequestOverlay()
        } else {
            Toast.makeText(this, "Microphone permission is needed for Hey Diya", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestOverlay) startWakeService()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
