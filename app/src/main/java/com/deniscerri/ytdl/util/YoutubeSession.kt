package com.deniscerri.ytdl.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.models.CookieItem
import com.deniscerri.ytdl.database.repository.CookieRepository
import com.deniscerri.ytdl.ui.more.cookies.WebViewActivity
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The YouTube account the downloads use: the cookies saved by the "Sign in to YouTube" button.
 * One place decides what a sign in error is, whether a session exists, and how to replace or remove it,
 * so the home screen, the download error notification and the sign in webview agree.
 */
object YoutubeSession {

    // what yt-dlp prints when YouTube wants an account: age restricted, "made for kids" + age gate, bot check
    private val signInErrorMarkers = listOf(
        "sign in",
        "cookies",
        "confirm your age",
        "inappropriate for some users",
        "age-restricted",
        "age restricted",
        "not a bot",
        "login required",
    )

    fun isSignInError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return signInErrorMarkers.any { message.contains(it, ignoreCase = true) }
    }

    fun isYoutubeUrl(url: String?): Boolean = url?.isYoutubeURL() == true

    /** Cookie entries created by the sign in button (or Get cookies on a YouTube link) */
    private fun isYoutubeEntry(item: CookieItem): Boolean {
        val host = runCatching { Uri.parse(item.url).host }.getOrNull() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun repository(context: Context) =
        CookieRepository(DBManager.getInstance(context).cookieDao, context.applicationContext as Application)

    /** Blocking database read, call it off the main thread */
    fun hasSession(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("use_cookies", false)) return false
        return repository(context).getAllEnabled().any { item ->
            item.content.lineSequence().any { line ->
                line.contains("youtube.com") && line.contains("\tLOGIN_INFO\t")
            }
        }
    }

    fun signInIntent(context: Context): Intent =
        Intent(context, WebViewActivity::class.java).apply {
            putExtra("url", WebViewActivity.YOUTUBE_URL)
            putExtra("description", context.getString(R.string.youtube_sign_in))
            putExtra(WebViewActivity.EXTRA_LOGIN, true)
        }

    /** Replaces any previous YouTube session, so an old account's cookies never mix with the new one */
    suspend fun saveSession(context: Context, content: String) = withContext(Dispatchers.IO) {
        val repo = repository(context)
        repo.getAll().filter { isYoutubeEntry(it) }.forEach { repo.delete(it) }
        repo.insert(
            CookieItem(
                0,
                WebViewActivity.YOUTUBE_URL,
                content,
                context.getString(R.string.youtube_sign_in),
                true
            )
        )
        repo.updateCookieFile()
        PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean("use_cookies", true) }
    }

    /**
     * Removes the saved YouTube cookies and signs the sign in webview out (its cookie store is cleared,
     * cookies of other sites that were already saved in the cookies list are kept).
     */
    suspend fun signOut(context: Context) {
        withContext(Dispatchers.IO) {
            val repo = repository(context)
            repo.getAll().filter { isYoutubeEntry(it) }.forEach { repo.delete(it) }
            repo.updateCookieFile()
        }
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
