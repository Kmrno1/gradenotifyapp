package az.beu.gradenotifier.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bridges OkHttp's cookie handling onto the WebView cookie store.
 *
 * That store is the single source of truth for the session: the user signs in
 * once in the WebView, the poller reuses the resulting PHPSESSID, and a
 * refreshed cookie from the poller is visible to the WebView in turn. The app
 * therefore never has to keep a copy of the session, let alone the password.
 */
class WebViewCookieJar : CookieJar {
    private val store = CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = store.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val target = url.toString()
        cookies.forEach { store.setCookie(target, it.toString()) }
        store.flush()
    }
}

/** True when a portal session cookie exists, i.e. the user has signed in. */
fun hasPortalSession(): Boolean {
    val header = CookieManager.getInstance().getCookie(PortalUrls.BASE) ?: return false
    return header.contains("PHPSESSID")
}

/** Forget the session, e.g. on explicit sign-out. */
fun clearPortalSession() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
}

/**
 * Reads the grades page over plain HTTP, reusing the WebView session.
 *
 * The WebView's own User-Agent is sent because PHP sessions are sometimes
 * bound to it; a mismatch would look like an expired session.
 */
class HttpPortal(context: Context) : Portal {
    private val userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }
        .getOrDefault("Mozilla/5.0 (Linux; Android)")

    private val client = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun fetchGrades(): PortalResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(PortalUrls.GRADES)
            .header("User-Agent", userAgent)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", PortalUrls.BASE)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext PortalResult.Failure("portal returned HTTP ${response.code}")
                }
                PortalResult.Body(response.body?.string().orEmpty())
            }
        } catch (error: IOException) {
            PortalResult.Failure(error.message ?: "network unavailable")
        }
    }
}
