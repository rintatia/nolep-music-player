package id.nolep.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    lateinit var web: WebView
    lateinit var bridge: Bridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#0f1020")
        window.statusBarColor = bg
        window.navigationBarColor = bg
        web = WebView(this)
        web.setBackgroundColor(bg)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        bridge = Bridge(this)
        web.addJavascriptInterface(bridge, "NolepNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                bridge.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == Bridge.HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = WebChromeClient()
        web.loadUrl("https://${Bridge.HOST}/assets/index.html")
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        bridge.pendingShare = url
        emitJs("share", JSONObject().put("url", url).toString())
    }

    fun emitJs(type: String, json: String) {
        runOnUiThread {
            if (::web.isInitialized) {
                web.evaluateJavascript("window.onNative&&window.onNative(${JSONObject.quote(type)},$json)", null)
            }
        }
    }

    fun askAudioPermission() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        runOnUiThread { requestPermissions(perms, 7) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        emitJs("perm", JSONObject().put("granted", bridge.hasPerm()).toString())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.nolepBack?window.nolepBack():false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }
}
