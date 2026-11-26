package id.nolep.player

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Mesin putar NPlayer. Semua lagu diputar di sini (bukan di WebView), jadi musik tetap
 * jalan dan pindah lagu sendiri saat layar mati atau memakai aplikasi lain.
 * WebView hanya mengirim perintah dan membaca status + data suara untuk animasi.
 */
class Engine(
    private val ctx: Context,
    private val source: (MediaPlayer, String, String) -> Unit,
    private val onTrack: (Int) -> Unit
) {
    class Item(val kind: String, val ref: String, val title: String, val artist: String)

    private var items: List<Item> = emptyList()
    @Volatile
    var index = 0
        private set
    var repeat = "off"
    private var mp: MediaPlayer? = null
    @Volatile private var ready = false
    @Volatile private var wantPlay = false
    private var pendingSeek = 0
    private var viz: Visualizer? = null
    private var sid = 0
    private var serviceOn = false
    private val handler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { if (!wantPlay) stopService() }

    fun setQueue(list: List<Item>) {
        items = list
        if (index >= items.size) index = 0
    }

    fun jump(i: Int, startMs: Int, autoplay: Boolean) = load(i, startMs, autoplay, 0)

    private fun load(i: Int, startMs: Int, autoplay: Boolean, tries: Int) {
        releasePlayer()
        if (i < 0 || i >= items.size || tries > items.size) {
            wantPlay = false
            notifyState()
            return
        }
        index = i
        wantPlay = autoplay
        pendingSeek = startMs
        val q = items[i]
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            source(p, q.kind, q.ref)
            p.setOnPreparedListener {
                ready = true
                if (pendingSeek > 0) it.seekTo(pendingSeek)
                if (wantPlay) it.start()
                attachViz(it.audioSessionId)
                notifyState()
            }
            p.setOnCompletionListener { advance() }
            p.setOnErrorListener { _, _, _ ->
                ready = false
                load(index + 1, 0, wantPlay, tries + 1)
                true
            }
            mp = p
            p.prepareAsync()
        } catch (e: Exception) {
            load(i + 1, 0, autoplay, tries + 1)
            return
        }
        onTrack(index)
        notifyState()
    }

    private fun advance() {
        when {
            repeat == "one" -> load(index, 0, true, 0)
            index + 1 < items.size -> load(index + 1, 0, true, 0)
            repeat == "all" && items.isNotEmpty() -> load(0, 0, true, 0)
            else -> {
                wantPlay = false
                try { mp?.pause(); mp?.seekTo(0) } catch (e: Exception) { }
                notifyState()
            }
        }
    }

    fun play() {
        wantPlay = true
        val p = mp
        if (p == null) {
            if (items.isNotEmpty()) load(index, 0, true, 0)
            return
        }
        if (ready) try { p.start() } catch (e: Exception) { }
        notifyState()
    }

    fun pause() {
        wantPlay = false
        if (ready) try { mp?.pause() } catch (e: Exception) { }
        notifyState()
    }

    fun seek(ms: Int) {
        if (ready) try { mp?.seekTo(ms) } catch (e: Exception) { } else pendingSeek = ms
    }

    fun state(): String {
        val p = mp
        var pos = 0
        var dur = 0
        var playing = false
        if (p != null && ready) {
            try {
                pos = p.currentPosition
                dur = p.duration
                playing = p.isPlaying
            } catch (e: Exception) {
            }
        } else playing = wantPlay
        return "{\"index\":$index,\"pos\":$pos,\"dur\":$dur,\"playing\":$playing,\"ready\":$ready,\"viz\":${viz != null}}"
    }

    /* ---------- data suara untuk animasi (Android Visualizer) ---------- */
    fun vizAllowed() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun reattachViz() { if (sid != 0) attachViz(sid) }

    private fun attachViz(s: Int) {
        sid = s
        try { viz?.release() } catch (e: Exception) { }
        viz = null
        if (!vizAllowed()) return
        try {
            val v = Visualizer(s)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.enabled = true
            viz = v
        } catch (e: Exception) {
            viz = null
        }
    }

    private var fftBuf = ByteArray(0)
    private var waveBuf = ByteArray(0)

    /** 128 nilai spektrum (0-255, mirip AnalyserNode) lalu '|' lalu 128 sampel gelombang (0-255). */
    fun vizData(): String {
        val v = viz ?: return ""
        if (mp?.isPlaying != true) return ""
        return try {
            val n = v.captureSize
            if (fftBuf.size != n) { fftBuf = ByteArray(n); waveBuf = ByteArray(n) }
            v.getFft(fftBuf)
            v.getWaveForm(waveBuf)
            val half = n / 2
            val per = maxOf(1, half / 128)
            val sb = StringBuilder(1200)
            for (b in 0 until 128) {
                var m = 0.0
                for (k in 0 until per) {
                    val j = b * per + k
                    if (j == 0 || j >= half) continue
                    val re = fftBuf[2 * j].toDouble()
                    val im = fftBuf[2 * j + 1].toDouble()
                    val mag = sqrt(re * re + im * im)
                    if (mag > m) m = mag
                }
                val db = 20 * log10(m / 128.0 + 1e-6)
                val x = ((db + 48) / 48 * 255).toInt().coerceIn(0, 255)
                if (b > 0) sb.append(',')
                sb.append(x)
            }
            sb.append('|')
            val step = maxOf(1, n / 128)
            for (k in 0 until 128) {
                if (k > 0) sb.append(',')
                sb.append(waveBuf[minOf(n - 1, k * step)].toInt() and 0xff)
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    /* ---------- notifikasi & layanan latar depan ---------- */
    private fun notifyState() {
        val playing = wantPlay
        handler.removeCallbacks(idleStop)
        if (!playing && serviceOn) handler.postDelayed(idleStop, 10 * 60 * 1000L)
        val q = items.getOrNull(index)
        val title = q?.title ?: "NPlayer"
        val artist = q?.artist ?: ""
        if (playing) {
            if (!serviceOn) {
                try {
                    ctx.startForegroundService(Intent(ctx, PlaybackService::class.java).putExtra("title", title).putExtra("artist", artist))
                    serviceOn = true
                } catch (e: Exception) {
                }
            }
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(1, PlaybackService.build(ctx, title, artist, true))
            } catch (e: Exception) {
            }
        } else if (serviceOn) {
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(1, PlaybackService.build(ctx, title, artist, false))
            } catch (e: Exception) {
            }
        }
    }

    fun stopService() {
        if (!serviceOn) return
        try { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } catch (e: Exception) { }
        serviceOn = false
    }

    private fun releasePlayer() {
        ready = false
        try { viz?.release() } catch (e: Exception) { }
        viz = null
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    fun release() {
        releasePlayer()
        stopService()
    }
}
