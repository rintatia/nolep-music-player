package id.nolep.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Layanan latar depan supaya musik tetap jalan saat layar mati atau aplikasi ditutup ke belakang. */
class PlaybackService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (e: Exception) {
        }
        wake = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("play", "Pemutar musik", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, "play")
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(intent?.getStringExtra("title") ?: "NPlayer")
            .setContentText(intent?.getStringExtra("artist") ?: "Sedang diputar")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        if (wake == null) {
            try {
                wake = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nplayer:playback").apply {
                        setReferenceCounted(false)
                        acquire(6 * 60 * 60 * 1000L)
                    }
            } catch (e: Exception) {
            }
        }
        return START_NOT_STICKY
    }
}
