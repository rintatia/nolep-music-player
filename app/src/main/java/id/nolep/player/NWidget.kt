package id.nolep.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/** Widget layar utama: judul lagu + tombol sebelumnya / putar-jeda / berikutnya. Warnanya mengikuti tema NPlayer. */
class NWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) = push(ctx, null, null, null)

    companion object {
        private fun col(s: String?, d: Int) = try { if (s.isNullOrEmpty()) d else Color.parseColor(s) } catch (e: Exception) { d }

        fun theme(ctx: Context, card: String, ink: String, acc: String, on: String) {
            ctx.getSharedPreferences("nwidget", 0).edit().putString("card", card).putString("ink", ink).putString("acc", acc).putString("on", on).apply()
            push(ctx, null, null, null)
        }

        fun push(ctx: Context, title: String?, artist: String?, playing: Boolean?) {
            try {
                val pr = ctx.getSharedPreferences("nwidget", 0)
                if (title != null) pr.edit().putString("t", title).putString("a", artist ?: "").putBoolean("p", playing == true).apply()
                val m = AppWidgetManager.getInstance(ctx)
                val ids = m.getAppWidgetIds(ComponentName(ctx, NWidget::class.java))
                if (ids.isEmpty()) return
                val p = playing ?: (Engine.shared?.isActive() == true)
                val t = pr.getString("t", null) ?: "NPlayer"
                val a = pr.getString("a", null)?.ifEmpty { null } ?: if (p) "Sedang diputar" else "Ketuk ▶ untuk memutar"
                val card = col(pr.getString("card", null), Color.parseColor("#1A1B33"))
                val ink = col(pr.getString("ink", null), Color.parseColor("#ECEBFF"))
                val acc = col(pr.getString("acc", null), Color.parseColor("#FF5C8A"))
                val on = col(pr.getString("on", null), Color.WHITE)
                val v = RemoteViews(ctx.packageName, R.layout.widget)
                v.setTextViewText(R.id.w_title, t)
                v.setTextViewText(R.id.w_artist, a)
                v.setTextColor(R.id.w_title, ink)
                v.setTextColor(R.id.w_artist, ink)
                v.setInt(R.id.w_bg, "setColorFilter", card)
                v.setInt(R.id.w_playbg, "setColorFilter", acc)
                v.setInt(R.id.w_prev, "setColorFilter", ink)
                v.setInt(R.id.w_next, "setColorFilter", ink)
                v.setInt(R.id.w_play, "setColorFilter", on)
                v.setImageViewResource(R.id.w_play, if (p) R.drawable.ic_pause else R.drawable.ic_play)
                v.setOnClickPendingIntent(R.id.w_prev, PlaybackService.act(ctx, "prev", 21))
                v.setOnClickPendingIntent(R.id.w_play, PlaybackService.act(ctx, "toggle", 22))
                v.setOnClickPendingIntent(R.id.w_next, PlaybackService.act(ctx, "next", 23))
                val open = PendingIntent.getActivity(ctx, 24, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                v.setOnClickPendingIntent(R.id.w_icon, open)
                v.setOnClickPendingIntent(R.id.w_title, open)
                v.setOnClickPendingIntent(R.id.w_artist, open)
                m.updateAppWidget(ids, v)
            } catch (e: Exception) {
            }
        }
    }
}
