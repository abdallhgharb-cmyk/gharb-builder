package com.gharb.studiapp

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * حاوية تشغيل تطبيقات الويب الكاملة (WebAppContainerActivity)
 * - تقدم ملفات www عبر HTTPS محلي آمن (WebViewAssetLoader) لدعم ES Modules و Fetch و Images و LocalStorage.
 * - تدعم النوافذ المتعددة (onCreateWindow / target="_blank") واختيار الملفات والأذونات والحوارات.
 */
class WebAppContainerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appTitle = intent.getStringExtra(EXTRA_APP_TITLE)
        if (!appTitle.isNullOrBlank()) {
            title = appTitle
        }

        webView = WebView(this)
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        configureWebView(webView, assetLoader)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL)
        val localHtmlPath = intent.getStringExtra(EXTRA_LOCAL_HTML_PATH)

        when {
            !targetUrl.isNullOrBlank() -> webView.loadUrl(targetUrl)
            !localHtmlPath.isNullOrBlank() && File(localHtmlPath).exists() -> {
                webView.loadUrl(File(localHtmlPath).toURI().toString())
            }
            else -> webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
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
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        targetWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "file" || scheme == "data" || scheme == "blob") {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }

        targetWebView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val popupDialog = Dialog(this@WebAppContainerActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val container = LinearLayout(this@WebAppContainerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val topBar = LinearLayout(this@WebAppContainerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setBackgroundColor(0xFF0F172A.toInt())
                    setPadding(16, 12, 16, 12)
                }
                val closeBtn = Button(this@WebAppContainerActivity).apply {
                    text = "إغلاق النافذة"
                    setOnClickListener { popupDialog.dismiss() }
                }
                topBar.addView(closeBtn)

                val childWebView = WebView(this@WebAppContainerActivity)
                configureWebView(childWebView, assetLoader)
                childWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        popupDialog.dismiss()
                    }
                }

                container.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                container.addView(childWebView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                popupDialog.setContentView(container)
                popupDialog.setOnDismissListener { childWebView.destroy() }
                popupDialog.show()

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = childWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@WebAppContainerActivity)
                    .setMessage(message)
                    .setPositiveButton("حسناً") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@WebAppContainerActivity)
                    .setMessage(message)
                    .setPositiveButton("موافق") { _, _ -> result?.confirm() }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@WebAppContainerActivity).apply { setText(defaultValue ?: "") }
                AlertDialog.Builder(this@WebAppContainerActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("موافق") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("إلغاء") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        targetWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimetype)
                        addRequestHeader("User-Agent", userAgent)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this, "بدأ تنزيل الملف...", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "تعذر تنزيل الملف", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_TARGET_URL = "TARGET_URL"
        const val EXTRA_LOCAL_HTML_PATH = "LOCAL_HTML_PATH"
        const val EXTRA_APP_TITLE = "APP_TITLE"
    }
}
