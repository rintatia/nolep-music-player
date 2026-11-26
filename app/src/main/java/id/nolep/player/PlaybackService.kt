package id.nolep.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder

/** Layanan latar depan: menjaga aplikasi tetap hidup selama musik diputar, plus tombol kontrol di notifikasi & layar kunci. */
class PlaybackService : Service() {
    companion object {
        private fun act(ctx: Context, action: String, req: Int): PendingIntent {
            val i = Intent(ctx, PlaybackService::class.java).setAction(action)
            return PendingIntent.getForegroundService(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun build(ctx: Context, title: String, artist: String, playing: Boolean, token: MediaSession.Token?): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("play", "Pemutar musik", NotificationManager.IMPORTANCE_LOW))
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = Notification.Builder(ctx, "play")
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(title.ifEmpty { "NPlayer" })
                .setContentText(if (playing) artist.ifEmpty { "Sedang diputar" } else "Dijeda")
                .setContentIntent(pi)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_prev), "Sebelumnya", act(ctx, "prev", 11)).build())
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(ctx, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        if (playing) "Jeda" else "Putar", act(ctx, "toggle", 12)
                    ).build()
                )
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_next), "Berikutnya", act(ctx, "next", 13)).build())
            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            if (token != null) style.setMediaSession(token)
            b.style = style
            return b.build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = Engine.shared
        val n = e?.notification() ?: build(this, "NPlayer", "", false, null)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
        if (e == null) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            "prev" -> e.prev()
            "toggle" -> e.toggle()
            "next" -> e.next()
        }
        return START_NOT_STICKY
    }
}
