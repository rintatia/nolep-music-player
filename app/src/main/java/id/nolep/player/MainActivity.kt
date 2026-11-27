package id.nolep.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.File
import java.io.FileOutputStream
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

    fun pickImages() {
        runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                startActivityForResult(i, 43)
            } catch (e: Exception) {
                emitJs("bgs", JSONObject().put("added", 0).put("msg", "Pemilih foto tidak tersedia").toString())
            }
        }
    }

    /** Foto latar diperkecil (sisi terpanjang maks 1600px) lalu disimpan sebagai JPEG supaya ringan & hemat memori */
    private fun saveBgs(uris: List<Uri>) {
        Thread {
            var ok = 0
            var bad = 0
            val dir = bridge.bgDir
            uris.forEachIndexed { k, u ->
                try {
                    val max = 1600
                    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, u)) { d, info, _ ->
                            val w = info.size.width
                            val h = info.size.height
                            val sc = minOf(1f, max.toFloat() / maxOf(w, h))
                            d.setTargetSize(maxOf(1, (w * sc).toInt()), maxOf(1, (h * sc).toInt()))
                            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                        var ss = 1
                        while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= max) ss *= 2
                        val b = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) }
                            ?: throw Exception("gagal")
                        val sc = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
                        if (sc < 1f) Bitmap.createScaledBitmap(b, (b.width * sc).toInt(), (b.height * sc).toInt(), true) else b
                    }
                    val f = File(dir, "bg_" + System.currentTimeMillis() + "_" + k + ".jpg")
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    bmp.recycle()
                    ok++
                } catch (e: Throwable) {
                    bad++
                }
            }
            emitJs("bgs", JSONObject().put("added", ok).put("failed", bad).toString())
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 43) {
            val l = mutableListOf<Uri>()
            if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) for (k in 0 until clip.itemCount) l.add(clip.getItemAt(k).uri) else data.data?.let { l.add(it) }
            }
            if (l.isEmpty()) emitJs("bgs", JSONObject().put("added", 0).put("msg", "Tidak ada foto dipilih").toString())
            else {
                emitJs("bgs_wait", JSONObject().put("n", l.size).toString())
                saveBgs(l)
            }
            return
        }
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
