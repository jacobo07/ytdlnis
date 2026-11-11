package com.deniscerri.ytdl.ui.more.cookies

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.viewmodel.CookieViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
import com.deniscerri.ytdl.util.YoutubeSession
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


open class WebViewActivity : BaseActivity() {
    private lateinit var cookiesViewModel: CookieViewModel
    private var webView: WebView? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var generateBtn: MaterialButton
    private lateinit var cookieManager: CookieManager
    private lateinit var url: String
    private lateinit var description: String
    private lateinit var cookies: String
    private lateinit var webViewClient: WebViewClient
    private lateinit var preferences: SharedPreferences
    // sign in mode: open the Google sign in page and save the cookies by itself once signed in
    private var loginMode = false
    private var autoSaveStarted = false

    @SuppressLint("SetJavaScriptEnabled")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.webview_activity)
        url = intent.getStringExtra("url") ?: return finish()
        description = intent.getStringExtra("description") ?: ""
        loginMode = intent.getBooleanExtra(EXTRA_LOGIN, false) && url.isYoutubeURL()

        val incognito = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            this@WebViewActivity.packageName
        }.endsWith(":incognito_process")

        cookiesViewModel = ViewModelProvider(this)[CookieViewModel::class.java]
        lifecycleScope.launch {
            val appbar = findViewById<AppBarLayout>(R.id.webview_appbarlayout)
            toolbar = appbar.findViewById(R.id.webviewToolbar)
            generateBtn = toolbar.findViewById(R.id.generate)

            if (!url.isYoutubeURL()) {
                toolbar.menu.children.firstOrNull { it.itemId == R.id.get_data_sync_id }?.isVisible = false
            }

            toolbar.menu.children.firstOrNull { it.itemId == R.id.get_data_sync_id }?.isVisible = false

            toolbar.setOnMenuItemClickListener { m : MenuItem ->
                when(m.itemId) {
                    R.id.desktop -> {
                        m.isChecked = !m.isChecked
                        webView.apply {
                            if (this == null) {
                                m.isChecked = false
                                return@apply
                            }

                            configureDesktopMode(this, m.isChecked)
                            this.reload()
                        }
                    }
                    else -> {}
                }
                true
            }

            preferences = PreferenceManager.getDefaultSharedPreferences(this@WebViewActivity)

            toolbar.setNavigationOnClickListener {
                finishAndRemoveTask()
            }

            val backCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView?.canGoBack() == true) {
                        webView?.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }

            onBackPressedDispatcher.addCallback(this@WebViewActivity, backCallback)

            generateBtn.setOnClickListener {
                generateBtn.isEnabled = false
                if (url.contains("youtube.com")) {
                    //redirect to robots.txt so cookies last longer for youtube
                    webView?.loadUrl("https://www.youtube.com/robots.txt")
                } else {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            generateCookies()
                        }
                    }
                }

            }

            cookieManager = CookieManager.getInstance()

            if (savedInstanceState == null) {
                cookieManager.flush()
            }
            if (incognito) {
                cookieManager.removeAllCookies(null)
            }

            webView = findViewById<WebView>(R.id.webview)
            webView?.apply {
                settings.run {
                    javaScriptEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    // Google refuses to sign in from a page that announces itself as an embedded webview
                    userAgentString = browserUserAgent(context)

                    if (!incognito) {
                        cacheMode = WebSettings.LOAD_DEFAULT
                        domStorageEnabled = true
                        setGeolocationEnabled(true)
                    } else {
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        domStorageEnabled = false
                        setGeolocationEnabled(false)
                        WebStorage.getInstance().deleteAllData()

                        clearHistory()
                        clearCache(true)
                        clearFormData()
                    }

                    if (Build.VERSION.SDK_INT >= 26) {
                        safeBrowsingEnabled = true
                    }
                }

                // the X-Requested-With header (app package name) also gives the webview away to Google
                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                    runCatching {
                        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                    }
                }

                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                this.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        webView = view
                        super.onPageFinished(view, url)
                        runCatching {
                            toolbar.title = view?.title ?: ""
                            cookies = cookieManager.getCookie(view?.url)
                        }

                        if (url?.contains("robots.txt") == true) {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    generateCookies()
                                }
                            }
                        } else if (loginMode && !autoSaveStarted && isSignedInToYoutube(url)) {
                            // the user finished signing in, save the cookies without needing to find the OK button
                            autoSaveStarted = true
                            generateBtn.isEnabled = false
                            view?.loadUrl("https://www.youtube.com/robots.txt")
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        if (request?.url?.scheme?.contains("http") == false) {
                            return true
                        }
                        return super.shouldOverrideUrlLoading(view, request)
                    }
                }
                this.webChromeClient = object : WebChromeClient() {}
            }
        }

        webView?.loadUrl(if (loginMode) GOOGLE_YOUTUBE_SIGN_IN_URL else url)
    }

    private fun isSignedInToYoutube(pageUrl: String?): Boolean {
        val host = runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull() ?: return false
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return false
        val youtubeCookies = runCatching { cookieManager.getCookie(YOUTUBE_URL) }.getOrNull() ?: return false
        return youtubeCookies.contains("LOGIN_INFO=")
    }

    private suspend fun generateCookies() = withContext(Dispatchers.IO) {
        val cookies = cookiesViewModel.getCookiesFromDB(url).getOrNull()
        if (cookies == null) {
            // stay open, the user may not have finished signing in yet
            withContext(Dispatchers.Main) {
                Toast.makeText(this@WebViewActivity, R.string.no_cookies_found, Toast.LENGTH_LONG).show()
            }
        }
        if (cookies != null && loginMode) {
            // also reached from the download error notification, where no caller enables the cookies
            val saved = runCatching { YoutubeSession.saveSession(this@WebViewActivity, cookies) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebViewActivity,
                    if (saved.isSuccess) getString(R.string.youtube_signed_in) else "Something went wrong",
                    Toast.LENGTH_LONG
                ).show()
                this@WebViewActivity.setResult(if (saved.isSuccess) RESULT_OK else RESULT_CANCELED)
                this@WebViewActivity.finish()
            }
            return@withContext
        }
        cookies?.let {
            runCatching {
                cookiesViewModel.insert(
                    com.deniscerri.ytdl.database.models.CookieItem(
                        0,
                        url,
                        it,
                        description,
                        true
                    )
                )
                cookiesViewModel.updateCookiesFile()
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@WebViewActivity,
                        "Something went wrong",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        withContext(Dispatchers.Main) {
            this@WebViewActivity.setResult(RESULT_OK)
            this@WebViewActivity.finish()
        }
    }



    private fun configureDesktopMode(webView: WebView, desktop: Boolean) {
        webView.settings.apply {
            if (desktop) {
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = browserUserAgent(webView.context)
                useWideViewPort = false
                loadWithOverviewMode = false
            }
        }
    }



    companion object {
        const val TAG = "WebViewActivity"
        const val EXTRA_LOGIN = "login"
        const val YOUTUBE_URL = "https://www.youtube.com"
        const val GOOGLE_YOUTUBE_SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2F"

        /**
         * The default webview user agent with the webview markers removed ("; wv" and "Version/4.0"),
         * so it reads as the regular Chrome browser of the device.
         */
        fun browserUserAgent(context: Context): String {
            return WebSettings.getDefaultUserAgent(context)
                .replace("; wv)", ")")
                .replace(Regex("""Version/\d+(\.\d+)*\s"""), "")
        }
    }

    data class CookieItem(
        val domain: String = "",
        val name: String = "",
        val value: String = "",
        val includeSubdomains: Boolean = true,
        val path: String = "/",
        val secure: Boolean = true,
        val expiry: Long = 0L,
    ) {
        constructor(
            url: String,
            name: String,
            value: String
        ) : this(domain = url.replace(Regex("""http(s)?://(\w*(www|m|account|sso))?|/.*"""), ""), name = name, value = value)

        fun toNetscapeFormat(): String {
            val stringList = listOf(domain,
                includeSubdomains.toString().uppercase(),
                path,
                secure.toString().uppercase(),
                expiry.toString(),
                name,
                value)

            val builder = StringBuilder(stringList.first())

            for (s in stringList.subList(1, stringList.size)) {
                if (s.isNotEmpty()) {
                    if (builder.isNotEmpty())
                        builder.append("\u0009")
                    builder.append(s)
                }
            }
            return builder.toString()
        }
    }

}
