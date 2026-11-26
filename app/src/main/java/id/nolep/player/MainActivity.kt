package id.nolep.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
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

    override fun onStart() {
        super.onStart()
        if (::web.isInitialized) {
            web.onResume()
            web.resumeTimers()
            emitJs("fg", "{}")
        }
        Engine.shared?.vizActive(true)
    }

    /* Saat tidak terlihat, tampilan & animasi dihentikan total supaya hemat baterai; musik tetap diputar oleh Engine */
    override fun onStop() {
        if (::web.isInitialized) {
            web.onPause()
            web.pauseTimers()
        }
        Engine.shared?.vizActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        // Layar aplikasi bisa ditutup sistem saat HP terkunci: musik yang sedang diputar TIDAK ikut dimatikan
        Engine.shared?.let { e ->
            e.onTrack = {}
            if (!e.isActive()) e.release()
        }
        super.onDestroy()
    }

    fun askVizPermission() {
        runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8) }
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

    fun pickAudio() {
        runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                startActivityForResult(i, 42)
            } catch (e: Exception) {
                emitJs("picked", JSONObject().put("items", JSONArray()).put("msg", "Pemilih file tidak tersedia").toString())
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
            else data.data?.let { uris.add(it) }
        }
        val arr = JSONArray()
        for (u in uris) {
            var name = u.lastPathSegment ?: "lagu"
            var size = -1L
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (e: Exception) {
            }
            arr.put(JSONObject().put("uri", u.toString()).put("name", name).put("size", size))
        }
        emitJs("picked", JSONObject().put("items", arr).toString())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) {
            bridge.engine.reattachViz()
            emitJs("viz", JSONObject().put("granted", bridge.engine.vizAllowed()).toString())
            return
        }
        emitJs("perm", JSONObject().put("granted", bridge.hasPerm()).toString())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.nolepBack?window.nolepBack():false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }
}
