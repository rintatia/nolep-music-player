package id.nolep.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread

interface Source {
    fun length(): Long
    fun open(): InputStream
}

class FileSource(private val f: File) : Source {
    override fun length() = f.length()
    override fun open(): InputStream = FileInputStream(f)
}

class MediaSource(private val ctx: Context, private val uri: Uri) : Source {
    override fun length(): Long =
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

    override fun open(): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka file")
}

class LimitedStream(private val inner: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val r = inner.read()
        if (r >= 0) left--
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun close() = inner.close()
}

class Bridge(private val act: MainActivity) {
    companion object {
        const val HOST = "appassets.androidplatform.net"
        private val AUDIO_EXT = Regex("\\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac|webm|mp4|3gp|amr|awb|mid|midi|xmf|imy|ota|rtttl|mka|mkv|aif|aiff|wma|ape|alac|caf|dsf|ac3|eac3|weba)$", RegexOption.IGNORE_CASE)
    }

    private val prefs = act.getSharedPreferences("nolep", Context.MODE_PRIVATE)
    private val musicDir: File get() = File(act.filesDir, "music").apply { mkdirs() }

    @Volatile
    var pendingShare: String? = null

    private fun emit(type: String, json: String) = act.emitJs(type, json)

    fun hasPerm(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    @JavascriptInterface
    fun hasPermission(): Boolean = hasPerm()

    @JavascriptInterface
    fun requestPermission() = act.askAudioPermission()

    @JavascriptInterface
    fun takeShare(): String {
        val s = pendingShare ?: ""
        pendingShare = null
        return s
    }

    /* ---------- lagu di HP ---------- */
    @JavascriptInterface
    fun scan(): String {
        val arr = JSONArray()
        if (hasPerm()) {
            val proj = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED
            )
            try {
                act.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    "(${MediaStore.Audio.Media.DURATION} >= ? OR ${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} = 0) AND ${MediaStore.Audio.Media.SIZE} > ?", arrayOf("20000", "150000"),
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        arr.put(
                            JSONObject()
                                .put("kind", "media").put("ref", c.getLong(0).toString())
                                .put("title", c.getString(1) ?: "").put("artist", c.getString(2) ?: "")
                                .put("dur", c.getLong(3) / 1000.0).put("size", c.getLong(4))
                                .put("name", c.getString(5) ?: "").put("added", c.getLong(6) * 1000)
                        )
                    }
                }
            } catch (e: Exception) {
            }
        }
        val files = musicDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: emptyList()
        for (f in files.sortedByDescending { it.lastModified() }) {
            var title = ""
            var artist = ""
            var dur = 0.0
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(f.absolutePath)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                r.release()
            } catch (e: Exception) {
            }
            arr.put(
                JSONObject().put("kind", "file").put("ref", f.name).put("name", f.name)
                    .put("title", title).put("artist", artist).put("dur", dur)
                    .put("size", f.length()).put("added", f.lastModified())
            )
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun deleteFile(name: String): Boolean = File(musicDir, File(name).name).delete()

    /* ---------- pengaturan Cloudflare ---------- */
    @JavascriptInterface
    fun getConfig(): String = JSONObject()
        .put("accountId", prefs.getString("accountId", ""))
        .put("bucket", prefs.getString("bucket", ""))
        .put("accessKey", prefs.getString("accessKey", ""))
        .put("hasSecret", !prefs.getString("secret", "").isNullOrEmpty())
        .put("auto", prefs.getBoolean("auto", true))
        .toString()

    @JavascriptInterface
    fun setConfig(json: String) {
        val o = JSONObject(json)
        val e = prefs.edit()
        for (k in listOf("accountId", "bucket", "accessKey")) e.putString(k, o.optString(k).trim())
        val s = o.optString("secret").trim()
        if (s.isNotEmpty()) e.putString("secret", s)
        e.putBoolean("auto", o.optBoolean("auto", true))
        e.apply()
    }

    private fun r2(): R2? {
        val a = prefs.getString("accountId", "") ?: ""
        val b = prefs.getString("bucket", "") ?: ""
        val k = prefs.getString("accessKey", "") ?: ""
        val s = prefs.getString("secret", "") ?: ""
        return if (a.isEmpty() || b.isEmpty() || k.isEmpty() || s.isEmpty()) null else R2(a, b, k, s)
    }

    @JavascriptInterface
    fun cloudReady(): Boolean = r2() != null

    @JavascriptInterface
    fun testCloud() {
        thread {
            val r = r2()
            val out = JSONObject()
            if (r == null) out.put("ok", false).put("msg", "Isi semua kolom dulu")
            else try {
                val n = r.list("music/").size
                out.put("ok", true).put("msg", "Tersambung. $n lagu di bucket.")
            } catch (e: Exception) {
                out.put("ok", false).put("msg", e.message ?: "Gagal tersambung")
            }
            emit("test", out.toString())
        }
    }

    @JavascriptInterface
    fun listCloud() {
        thread {
            val r = r2()
            val out = JSONObject()
            if (r == null) out.put("ok", false).put("msg", "Cloudflare belum diatur")
            else try {
                val a = JSONArray()
                for (it in r.list("music/")) a.put(JSONObject().put("key", it.first).put("size", it.second).put("added", it.third))
                out.put("ok", true).put("items", a)
            } catch (e: Exception) {
                out.put("ok", false).put("msg", e.message ?: "Gagal memuat cloud")
            }
            emit("cloud", out.toString())
        }
    }

    @JavascriptInterface
    fun deleteCloud(key: String) {
        thread {
            val out = JSONObject()
            try {
                r2()?.delete(key)
                out.put("ok", true)
            } catch (e: Exception) {
                out.put("ok", false).put("msg", e.message ?: "Gagal menghapus")
            }
            emit("del", out.toString())
        }
    }

    @JavascriptInterface
    fun upload(kind: String, ref: String, name: String) {
        thread {
            val src: Source = if (kind == "media") {
                MediaSource(act, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ref.toLong()))
            } else FileSource(File(musicDir, File(ref).name))
            uploadFile(safeName(name), src)
        }
    }

    private fun uploadFile(name: String, src: Source) {
        fun ev(st: String, pct: Int = 0, msg: String = "") = emit(
            "up", JSONObject().put("name", name).put("status", st).put("pct", pct).put("msg", msg).toString()
        )
        val r = r2()
        if (r == null) {
            ev("error", msg = "Cloudflare belum diatur")
            return
        }
        try {
            ev("progress", 0)
            r.put("music/$name", src, mimeOf(name)) { p -> ev("progress", p) }
            ev("done", 100)
        } catch (e: Exception) {
            ev("error", msg = e.message ?: "Gagal mengunggah")
        }
    }

    /* ---------- unduh dari link ---------- */
    @JavascriptInterface
    fun download(url: String, jobId: String, autoUpload: Boolean) {
        thread {
            fun ev(st: String, pct: Int = 0, msg: String = "", name: String = "") = emit(
                "dl", JSONObject().put("id", jobId).put("status", st).put("pct", pct).put("msg", msg).put("name", name).toString()
            )
            try {
                val u = normalize(url.trim())
                val host = (Uri.parse(u).host ?: "").lowercase()
                if (host.contains("youtube.") || host.contains("youtu.be") || host.endsWith("googlevideo.com")) {
                    ev("error", msg = "Link YouTube tidak didukung")
                    return@thread
                }
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NolepPlayer/1.0")
                val code = conn.responseCode
                if (code !in 200..299) {
                    ev("error", msg = "Server menjawab $code")
                    return@thread
                }
                val ct = (conn.contentType ?: "").lowercase()
                if (ct.startsWith("text/") || ct.contains("html") || ct.contains("json")) {
                    ev("error", msg = "Link ini halaman web, bukan file audio langsung")
                    return@thread
                }
                var name = safeName(fileNameFrom(conn, u))
                if (!AUDIO_EXT.containsMatchIn(name)) name += extFromMime(ct)
                val dest = uniqueFile(name)
                val part = File(dest.path + ".part")
                val total = conn.contentLengthLong
                var lastEmit = 0L
                conn.inputStream.use { inp ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 350) {
                                lastEmit = now
                                ev("progress", if (total > 0) (done * 100 / total).toInt() else -1, name = dest.name)
                            }
                        }
                    }
                }
                if (!part.renameTo(dest)) throw IOException("Gagal menyimpan file")
                ev("done", 100, name = dest.name)
                if (autoUpload && r2() != null) uploadFile(dest.name, FileSource(dest))
            } catch (e: Exception) {
                ev("error", msg = e.message ?: "Gagal mengunduh")
            }
        }
    }

    private fun normalize(u: String): String {
        Regex("drive\\.google\\.com/file/d/([\\w-]+)").find(u)?.let {
            return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}"
        }
        Regex("drive\\.google\\.com/open\\?id=([\\w-]+)").find(u)?.let {
            return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}"
        }
        if (u.contains("dropbox.com")) {
            return if (u.contains("dl=0")) u.replace("dl=0", "dl=1") else if (u.contains("dl=1")) u else u + (if (u.contains("?")) "&" else "?") + "dl=1"
        }
        return u
    }

    private fun fileNameFrom(conn: HttpURLConnection, u: String): String {
        val cd = conn.getHeaderField("Content-Disposition") ?: ""
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            return URLDecoder.decode(it.groupValues[1], "UTF-8")
        }
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1] }
        val seg = Uri.parse(conn.url.toString()).lastPathSegment ?: Uri.parse(u).lastPathSegment
        return if (!seg.isNullOrBlank() && seg.length < 150) seg else "lagu-${System.currentTimeMillis()}"
    }

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(140).ifEmpty { "lagu" }

    private fun uniqueFile(name: String): File {
        var f = File(musicDir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (f.exists()) {
            f = File(musicDir, "$base ($i)$ext"); i++
        }
        return f
    }

    private fun extFromMime(ct: String) = when {
        ct.contains("mpeg") || ct.contains("mp3") -> ".mp3"
        ct.contains("mp4") || ct.contains("m4a") || ct.contains("aac") -> ".m4a"
        ct.contains("ogg") -> ".ogg"
        ct.contains("opus") -> ".opus"
        ct.contains("wav") -> ".wav"
        ct.contains("flac") -> ".flac"
        ct.contains("webm") -> ".webm"
        else -> ".mp3"
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "m4a", "mp4" -> "audio/mp4"
            "opus", "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "3gp" -> "audio/3gpp"
            "amr" -> "audio/amr"
            "mid", "midi" -> "audio/midi"
            "aif", "aiff" -> "audio/aiff"
            "wma" -> "audio/x-ms-wma"
            "mka" -> "audio/x-matroska"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /* ---------- pemutar bawaan Android (cadangan untuk format yang tidak bisa diputar WebView) ---------- */
    private var mp: MediaPlayer? = null
    @Volatile
    private var mpReady = false

    @JavascriptInterface
    fun nPlay(kind: String, ref: String, startMs: Int) {
        act.runOnUiThread {
            try {
                mp?.release()
                mp = null
                mpReady = false
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                when (kind) {
                    "media" -> p.setDataSource(act, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ref.toLong()))
                    "file" -> p.setDataSource(File(musicDir, File(ref).name).absolutePath)
                    "r2" -> {
                        val r = r2() ?: throw IOException("Cloudflare belum diatur")
                        val (url, headers) = r.signedGet(ref)
                        p.setDataSource(act, Uri.parse(url), headers)
                    }
                    else -> throw IOException("Sumber tidak dikenal")
                }
                p.setOnPreparedListener {
                    mpReady = true
                    if (startMs > 0) it.seekTo(startMs)
                    it.start()
                    emit("np", JSONObject().put("state", "playing").put("dur", it.duration).toString())
                }
                p.setOnCompletionListener { emit("np", JSONObject().put("state", "ended").toString()) }
                p.setOnErrorListener { _, what, extra ->
                    mpReady = false
                    emit("np", JSONObject().put("state", "error").put("msg", "Format tidak didukung HP ini ($what/$extra)").toString())
                    true
                }
                mp = p
                p.prepareAsync()
            } catch (e: Exception) {
                emit("np", JSONObject().put("state", "error").put("msg", e.message ?: "Gagal memutar").toString())
            }
        }
    }

    @JavascriptInterface
    fun nPause() = act.runOnUiThread { try { if (mpReady) mp?.pause() } catch (e: Exception) { } }

    @JavascriptInterface
    fun nResume() = act.runOnUiThread { try { if (mpReady) mp?.start() } catch (e: Exception) { } }

    @JavascriptInterface
    fun nSeek(ms: Int) = act.runOnUiThread { try { if (mpReady) mp?.seekTo(ms) } catch (e: Exception) { } }

    @JavascriptInterface
    fun nStop() = act.runOnUiThread {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        mpReady = false
    }

    @JavascriptInterface
    fun nPos(): String {
        val p = mp
        if (p == null || !mpReady) return "{\"pos\":0,\"dur\":0,\"playing\":false}"
        return try {
            JSONObject().put("pos", p.currentPosition).put("dur", p.duration).put("playing", p.isPlaying).toString()
        } catch (e: Exception) {
            "{\"pos\":0,\"dur\":0,\"playing\":false}"
        }
    }

    /* ---------- notifikasi pemutar ---------- */
    @JavascriptInterface
    fun setPlaying(playing: Boolean, title: String, artist: String) {
        val i = Intent(act, PlaybackService::class.java).putExtra("title", title).putExtra("artist", artist)
        try {
            if (playing) act.startForegroundService(i) else act.stopService(i)
        } catch (e: Exception) {
        }
    }

    /* ---------- server lokal untuk WebView ---------- */
    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != HOST) return null
        val path = u.path ?: return null
        return try {
            when {
                path.startsWith("/assets/") -> {
                    val a = path.removePrefix("/assets/")
                    WebResourceResponse(mimeOf(a), "utf-8", act.assets.open(a))
                }
                path.startsWith("/media/") -> {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, path.removePrefix("/media/").toLong())
                    serve(MediaSource(act, uri), act.contentResolver.getType(uri) ?: "audio/mpeg", req)
                }
                path.startsWith("/file/") -> {
                    val f = File(musicDir, File(path.removePrefix("/file/")).name)
                    serve(FileSource(f), mimeOf(f.name), req)
                }
                path.startsWith("/r2/") -> proxyR2(path.removePrefix("/r2/"), req)
                else -> null
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), "".byteInputStream())
        }
    }

    private fun rangeOf(req: WebResourceRequest): String? =
        req.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value

    private fun serve(src: Source, mime: String, req: WebResourceRequest): WebResourceResponse {
        val len = src.length()
        val h = mutableMapOf("Accept-Ranges" to "bytes", "Access-Control-Allow-Origin" to "*")
        val range = rangeOf(req)
        if (range != null && len > 0) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
            if (m != null) {
                var start = m.groupValues[1].toLongOrNull() ?: 0L
                var end = m.groupValues[2].toLongOrNull() ?: (len - 1)
                if (m.groupValues[1].isEmpty()) {
                    start = maxOf(0L, len - end)
                    end = len - 1
                }
                end = minOf(end, len - 1)
                if (start > end) {
                    h["Content-Range"] = "bytes */$len"
                    return WebResourceResponse(mime, null, 416, "Range Not Satisfiable", h, "".byteInputStream())
                }
                val ins = src.open()
                var skip = start
                while (skip > 0) {
                    val s = ins.skip(skip)
                    if (s <= 0) {
                        if (ins.read() < 0) break
                        skip--
                    } else skip -= s
                }
                h["Content-Range"] = "bytes $start-$end/$len"
                h["Content-Length"] = (end - start + 1).toString()
                return WebResourceResponse(mime, null, 206, "Partial Content", h, LimitedStream(ins, end - start + 1))
            }
        }
        if (len > 0) h["Content-Length"] = len.toString()
        return WebResourceResponse(mime, null, 200, "OK", h, src.open())
    }

    private fun proxyR2(key: String, req: WebResourceRequest): WebResourceResponse {
        val r = r2() ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), "".byteInputStream())
        val c = r.get(key, rangeOf(req))
        val code = c.responseCode
        val h = mutableMapOf("Accept-Ranges" to "bytes", "Access-Control-Allow-Origin" to "*")
        c.getHeaderField("Content-Range")?.let { h["Content-Range"] = it }
        c.getHeaderField("Content-Length")?.let { h["Content-Length"] = it }
        val stream = if (code >= 400) (c.errorStream ?: "".byteInputStream()) else c.inputStream
        val reason = when {
            code == 206 -> "Partial Content"
            code < 300 -> "OK"
            else -> "Error"
        }
        return WebResourceResponse(c.contentType ?: mimeOf(key), null, code, reason, h, stream)
    }
}
