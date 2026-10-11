package de.feromonde.sw411control

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.WebChromeClient
import android.webkit.JsResult
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** The controller serves the UI: Android and browser always use the same panel. */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("sw411", MODE_PRIVATE) }
    private var serverUrl = DEFAULT_SERVER
    private lateinit var webView: WebView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var loadingProgress: ProgressBar
    private var pageFailed = false
    private val shellScript by lazy {
        assets.open("android-shell.js").bufferedReader().use { it.readText() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverUrl = normalizeServer(prefs.getString("server_url", DEFAULT_SERVER).orEmpty())
            ?: DEFAULT_SERVER
        val root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        webView = WebView(this).apply {
            setBackgroundColor(BACKGROUND)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    if (!isControllerUrl(url)) {
                        result.cancel()
                        return true
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("SW411 Control")
                        .setMessage(message)
                        .setPositiveButton("Подтвердить") { _, _ -> result.confirm() }
                        .setNegativeButton("Отмена") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }

                override fun onProgressChanged(view: WebView, value: Int) {
                    loadingProgress.progress = value
                    loadingProgress.visibility = if (value < 100 && !pageFailed) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "sw411-app") {
                        if (request.isForMainFrame && uri.host == "connection" &&
                            isControllerUrl(view.url.orEmpty())) {
                            showServerDialog()
                        }
                        return true
                    }
                    if (isControllerUrl(uri.toString())) return false
                    if (request.isForMainFrame && request.hasGesture() &&
                        uri.scheme in listOf("https", "http")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageFailed = false
                    errorPanel.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loadingProgress.visibility = View.GONE
                    if (!pageFailed && isControllerUrl(url)) view.evaluateJavascript(shellScript, null)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showError("Не удалось подключиться к Nexbox")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                    if (request.isForMainFrame) showError("Сервер ответил HTTP ${error.statusCode}")
                }
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(BACKGROUND)
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        errorPanel.addView(errorText)
        errorPanel.addView(Button(this).apply {
            text = "Повторить подключение"
            setOnClickListener { loadController() }
        })
        errorPanel.addView(Button(this).apply {
            text = "Адрес сервера"
            setOnClickListener { showServerDialog() }
        })
        root.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        loadingProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(loadingProgress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        setContentView(root)
        root.requestApplyInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (errorPanel.visibility == View.VISIBLE) {
                    finish()
                    return
                }
                webView.evaluateJavascript("window.sw411AndroidCloseOverlay ? window.sw411AndroidCloseOverlay() : false") { closed ->
                    if (closed != "true") {
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                }
            }
        })
        loadController()
    }

    private fun loadController() {
        pageFailed = false
        errorPanel.visibility = View.GONE
        webView.stopLoading()
        webView.clearHistory()
        webView.loadUrl(serverUrl)
    }

    private fun showError(message: String) {
        pageFailed = true
        loadingProgress.visibility = View.GONE
        errorText.text = "$message\n\n$serverUrl\n\nПроверь подключение к домашней сети и работу SW411 Control."
        errorPanel.visibility = View.VISIBLE
    }

    private fun showServerDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(serverUrl)
            selectAll()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Адрес SW411 Control")
            .setMessage("Например: http://192.168.2.159")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = normalizeServer(input.text.toString())
                if (url == null) {
                    input.error = "Введи адрес http:// или https:// без логина и пароля"
                } else {
                    serverUrl = url
                    prefs.edit().putString("server_url", serverUrl).apply()
                    dialog.dismiss()
                    loadController()
                }
            }
        }
        dialog.show()
    }

    private fun isControllerUrl(value: String): Boolean = runCatching {
        val base = Uri.parse(serverUrl)
        val target = Uri.parse(value)
        fun port(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        target.scheme == base.scheme && target.host.equals(base.host, ignoreCase = true) &&
            port(target) == port(base) && target.userInfo == null
    }.getOrDefault(false)

    private fun normalizeServer(value: String): String? {
        val text = value.trim().let { if ("://" in it) it else "http://$it" }
        return runCatching {
            val url = java.net.URI(text)
            if (url.scheme !in listOf("http", "https") || url.host.isNullOrBlank() ||
                url.userInfo != null || url.rawQuery != null || url.rawFragment != null ||
                url.port < -1 || url.port > 65535 || url.port == 0) null
            else text.trimEnd('/')
        }.getOrNull()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.stopLoading()
        (webView.parent as? FrameLayout)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_SERVER = "http://192.168.2.159"
        private val BACKGROUND = Color.rgb(7, 16, 25)
    }
}
