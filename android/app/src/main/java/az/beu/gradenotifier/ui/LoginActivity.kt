package az.beu.gradenotifier.ui

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import az.beu.gradenotifier.net.PortalUrls
import az.beu.gradenotifier.net.hasPortalSession

/**
 * One-time sign-in.
 *
 * The portal's own login page is shown in a WebView, so the password is typed
 * into the portal itself and never passes through this app. All the app keeps
 * afterwards is the session cookie that the WebView stores.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieManager.getInstance().setAcceptCookie(true)

        web = WebView(this).apply {
            // Only ever loads my.beu.edu.az, whose own login page needs scripting.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    // The portal drops the login form once authenticated, so the
                    // presence of a session on a non-login page means success.
                    if (hasPortalSession() && !url.contains("auth.php")) {
                        view.evaluateJavascript(
                            "(function(){return document.querySelector(\"input[type='password']\")?1:0})()",
                        ) { result ->
                            if (result == "0") finishSignedIn()
                        }
                    }
                }
            }
            loadUrl(PortalUrls.BASE)
        }

        setContentView(web)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (web.canGoBack()) {
                        web.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun finishSignedIn() {
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}
