package id.nolep.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Layanan latar depan: menjaga aplikasi tetap hidup selama musik diputar. Wake lock dipegang oleh MediaPlayer hanya saat memutar. */
class PlaybackService : Service() {
    companion object {
        fun build(ctx: Context, title: String, artist: String, playing: Boolean): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("play", "Pemutar musik", NotificationManager.IMPORTANCE_LOW))
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, "play")
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(title.ifEmpty { "NPlayer" })
                .setContentText(if (playing) artist.ifEmpty { "Sedang diputar" } else "Dijeda")
                .setContentIntent(pi)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra("title") ?: "NPlayer", intent?.getStringExtra("artist") ?: "", true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
        return START_NOT_STICKY
    }
}
