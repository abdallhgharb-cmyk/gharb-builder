package com.gharbstudio.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class WebAppContainerActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        configureWebView(webView, assetLoader)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        val initialUrl = "https://ais-pre-25czqkejrbjbynm3tkb4bh-731978972581.europe-west1.run.app/"
        if (initialUrl.isNotBlank()) {
            webView.loadUrl(initialUrl)
        } else {
            webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(targetWebView: WebView, assetLoader: WebViewAssetLoader) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(targetWebView, true)
        }
        targetWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        targetWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "data" || scheme == "blob") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, uri)); true } catch (_: Exception) { true }
            }
        }
        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val dlg = Dialog(this@WebAppContainerActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val root = LinearLayout(this@WebAppContainerActivity).apply { orientation = LinearLayout.VERTICAL }
                val bar = LinearLayout(this@WebAppContainerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                    setBackgroundColor(0xFF0F172A.toInt()); setPadding(16, 12, 16, 12)
                }
                bar.addView(Button(this@WebAppContainerActivity).apply { text = "إغلاق"; setOnClickListener { dlg.dismiss() } })
                val child = WebView(this@WebAppContainerActivity)
                configureWebView(child, assetLoader)
                child.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) { dlg.dismiss() }
                }
                root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                root.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                dlg.setContentView(root)
                dlg.setOnDismissListener { child.destroy() }
                dlg.show()
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = child
                resultMsg?.sendToTarget()
                return true
            }
        }
    }
}