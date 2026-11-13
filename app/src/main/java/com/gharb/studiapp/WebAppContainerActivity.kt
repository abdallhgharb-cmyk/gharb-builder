package com.gharb.studiapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * النشاط التنفيذي المستقل للتطبيق الناتج (WebAppContainerActivity)
 * يقوم بتشغيل التطبيق المحلي بدون إنترنت (ملف PWA محلي أو assets/www/index.html) أو رابط URL.
 */
class WebAppContainerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appTitle = intent.getStringExtra(EXTRA_APP_TITLE)
        if (!appTitle.isNullOrBlank()) {
            title = appTitle
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL)
        val localHtmlPath = intent.getStringExtra(EXTRA_LOCAL_HTML_PATH)

        when {
            !targetUrl.isNullOrBlank() -> webView.loadUrl(targetUrl)
            !localHtmlPath.isNullOrBlank() && File(localHtmlPath).exists() -> {
                webView.loadUrl(File(localHtmlPath).toURI().toString())
            }
            else -> webView.loadUrl("file:///android_asset/www/index.html")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_TARGET_URL = "TARGET_URL"
        const val EXTRA_LOCAL_HTML_PATH = "LOCAL_HTML_PATH"
        const val EXTRA_APP_TITLE = "APP_TITLE"
    }
}
