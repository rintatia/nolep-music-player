package id.nolep.player

import android.Manifest
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import java.io.IOException
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
class Engine(private val ctx: Context) {
    companion object {
        /** Satu mesin untuk seluruh aplikasi: tetap hidup walau layar aplikasi ditutup sistem saat HP terkunci */
        @Volatile var shared: Engine? = null
    }

    var source: (MediaPlayer, String, String) -> Unit = { _, _, _ -> throw IOException("Sumber belum siap") }
    var onTrack: (Int) -> Unit = {}
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

    /* ---------- media session: mendaftarkan NPlayer sebagai pemutar musik ke Android/MIUI ---------- */
    val session = MediaSession(ctx, "NPlayer").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = prev()
            override fun onSeekTo(pos: Long) = seek(pos.toInt())
        })
        isActive = true
    }

    /* ---------- audio focus ---------- */
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    private var resumeOnGain = false
    private var ducked = false
    private val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ c -> handler.post { onFocus(c) } }, handler)
        .build()

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = try { am.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_FAILED } catch (e: Exception) { true }
        return hasFocus
    }

    private fun onFocus(c: Int) {
        when (c) {
            AudioManager.AUDIOFOCUS_LOSS -> { hasFocus = false; resumeOnGain = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (wantPlay) { resumeOnGain = true; pause() } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { try { mp?.setVolume(.25f, .25f) } catch (e: Exception) { }; ducked = true }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (ducked) { try { mp?.setVolume(1f, 1f) } catch (e: Exception) { }; ducked = false }
                if (resumeOnGain) { resumeOnGain = false; play() }
            }
        }
    }

    /* ---------- WiFi tetap bangun untuk lagu Cloud, headset dicabut = jeda ---------- */
    private val wifi: WifiManager.WifiLock? = try {
        @Suppress("DEPRECATION")
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nplayer:stream").apply { setReferenceCounted(false) }
    } catch (e: Exception) { null }
    private val cpu = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nplayer:play").apply { setReferenceCounted(false) }
    private var noisyOn = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun holdLocks(on: Boolean) {
        val cloud = items.getOrNull(index)?.kind == "r2"
        // CPU tetap bangun hanya selama musik diputar (termasuk jeda singkat saat ganti lagu); dilepas saat dijeda
        try { if (on) cpu.acquire(4 * 60 * 60 * 1000L) else if (cpu.isHeld) cpu.release() } catch (e: Exception) { }
        try { if (on && cloud) { if (wifi?.isHeld == false) wifi.acquire() } else if (wifi?.isHeld == true) wifi.release() } catch (e: Exception) { }
        try {
            if (on && !noisyOn) {
                val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(noisy, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(noisy, f)
                noisyOn = true
            } else if (!on && noisyOn) { ctx.unregisterReceiver(noisy); noisyOn = false }
        } catch (e: Exception) { }
    }

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
                if (wantPlay && requestFocus()) it.start()
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

    fun next() {
        if (items.isEmpty()) return
        load(if (index + 1 < items.size) index + 1 else 0, 0, true, 0)
    }

    fun prev() {
        if (items.isEmpty()) return
        val pos = try { if (ready) mp?.currentPosition ?: 0 else 0 } catch (e: Exception) { 0 }
        if (pos > 3000) { seek(0); notifyState(); return }
        load(if (index > 0) index - 1 else items.size - 1, 0, true, 0)
    }

    fun toggle() = if (wantPlay) pause() else play()

    fun isActive() = wantPlay

    fun play() {
        requestFocus()
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
        updateSession()
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

    /** Saat aplikasi tidak terlihat, pembacaan suara untuk animasi dimatikan supaya hemat baterai */
    private var vizOn = true
    fun vizActive(on: Boolean) {
        vizOn = on
        try { viz?.enabled = on } catch (e: Exception) { }
    }

    private fun attachViz(s: Int) {
        sid = s
        try { viz?.release() } catch (e: Exception) { }
        viz = null
        if (!vizAllowed()) return
        try {
            val v = Visualizer(s)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.enabled = vizOn
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
        holdLocks(playing)
        updateSession()
        handler.removeCallbacks(idleStop)
        if (!playing && serviceOn) handler.postDelayed(idleStop, 10 * 60 * 1000L)
        val q = items.getOrNull(index)
        val title = q?.title ?: "NPlayer"
        val artist = q?.artist ?: ""
        if (playing) {
            if (!serviceOn) {
                try {
                    ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
                    serviceOn = true
                } catch (e: Exception) {
                }
            }
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(1, PlaybackService.build(ctx, title, artist, true, session.sessionToken))
            } catch (e: Exception) {
            }
        } else if (serviceOn) {
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(1, PlaybackService.build(ctx, title, artist, false, session.sessionToken))
            } catch (e: Exception) {
            }
        }
    }

    fun notification() = items.getOrNull(index).let {
        PlaybackService.build(ctx, it?.title ?: "NPlayer", it?.artist ?: "", wantPlay, session.sessionToken)
    }

    private var metaKey = ""
    private fun updateSession() {
        try {
            val q = items.getOrNull(index)
            var pos = 0L
            var dur = 0L
            if (ready) try { pos = (mp?.currentPosition ?: 0).toLong(); dur = (mp?.duration ?: 0).toLong() } catch (e: Exception) { }
            val key = "$index:${q?.title}:$dur"
            if (key != metaKey) {
                metaKey = key
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, q?.title ?: "NPlayer")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, q?.artist ?: "")
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "NPlayer")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                        .build()
                )
            }
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                    )
                    .setState(if (wantPlay) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (wantPlay) 1f else 0f)
                    .build()
            )
        } catch (e: Exception) { }
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
        wantPlay = false
        releasePlayer()
        holdLocks(false)
        stopService()
        try { am.abandonAudioFocusRequest(focusReq) } catch (e: Exception) { }
        hasFocus = false
        try { session.release() } catch (e: Exception) { }
        if (shared === this) shared = null
    }
}
