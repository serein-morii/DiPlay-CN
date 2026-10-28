package com.shilapi.xcertplay

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import java.util.Date

/**
 * A home-screen widget with CarPlay's next turn (arrow, distance, road), arrival and the
 * song. A standard Android widget, so it works in any launcher that hosts widgets; BYD's own home
 * accepts only its listed widgets. A widget cannot show video, so there is no live map here.
 */
class NavigationWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NavigationWidgetUpdater.attach(context)
        manager.updateAppWidget(ids, NavigationWidgetUpdater.views(context, CarPlayGlance.snapshot()))
    }
}

/** Follows [CarPlayGlance] and redraws every placed widget, at most once a second. */
internal object NavigationWidgetUpdater {
    private const val MIN_INTERVAL_MILLIS = 1_000L

    private val worker = Handler(HandlerThread("diplay-widget").apply { start() }.looper)
    @Volatile private var context: Context? = null
    private var lastRender = 0L // worker thread
    private var pending = false // worker thread
    private val render = Runnable {
        pending = false
        lastRender = SystemClock.elapsedRealtime()
        val app = context ?: return@Runnable
        val manager = AppWidgetManager.getInstance(app)
        val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, NavigationWidget::class.java)) }.getOrNull()
        if (ids == null || ids.isEmpty()) return@Runnable
        val glance = CarPlayGlance.snapshot()
        runCatching { manager.updateAppWidget(ids, views(app, glance)) }
        // Route state has expiry deadlines. Keep checking while an instruction is visible,
        // including when the iPhone stops sending updates without ending the session.
        if (glance.connected && glance.maneuverType != null) schedule()
    }

    fun attach(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        CarPlayGlance.listener = { worker.post(::schedule) }
        worker.post(::schedule)
    }

    private fun schedule() {
        if (pending) return
        pending = true
        val wait = (lastRender + MIN_INTERVAL_MILLIS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        worker.postDelayed(render, wait)
    }

    fun views(context: Context, glance: CarPlayGlance.Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_navigation)
        val target = if (glance.connected) CarPlayScreen.target(context) else DiPlayActivity::class.java
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 0, Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val type = glance.maneuverType
        when {
            !glance.connected -> {
                views.setImageViewResource(R.id.widget_arrow, R.drawable.ic_dp_navigation)
                views.setTextViewText(R.id.widget_distance, "DiPlay")
                views.setTextViewText(R.id.widget_road, context.getString(R.string.widget_not_connected))
                views.setViewVisibility(R.id.widget_eta, View.GONE)
            }
            type == null -> {
                views.setImageViewResource(R.id.widget_arrow, R.drawable.ic_dp_navigation)
                views.setTextViewText(R.id.widget_distance, "CarPlay")
                views.setTextViewText(R.id.widget_road, context.getString(R.string.widget_no_route))
                views.setViewVisibility(R.id.widget_eta, View.GONE)
            }
            else -> {
                views.setImageViewResource(R.id.widget_arrow, arrow(type, glance.drivingSide))
                views.setTextViewText(R.id.widget_distance, distance(context, glance.distanceMeters.toLong()))
                views.setTextViewText(R.id.widget_road, glance.road)
                val eta = listOfNotNull(
                    glance.arrivalEpochSeconds?.let { DateFormat.getTimeFormat(context).format(Date(it * 1000)) },
                    glance.remainingMeters?.let { distance(context, it) },
                    glance.remainingSeconds?.let { duration(context, it) },
                ).joinToString(" · ")
                views.setTextViewText(R.id.widget_eta, eta)
                views.setViewVisibility(R.id.widget_eta, if (eta.isEmpty()) View.GONE else View.VISIBLE)
            }
        }
        val song = glance.song?.takeIf { glance.connected }
        views.setTextViewText(R.id.widget_song, song?.let { (if (glance.playing) "♪ " else "❚❚ ") + it }.orEmpty())
        views.setViewVisibility(R.id.widget_song, if (song == null) View.GONE else View.VISIBLE)
        return views
    }

    /** Apple's RouteGuidanceManeuverType, grouped as DiPlay's BYD outputs group it. */
    fun arrow(type: Int, drivingSide: Int): Int = when (type) {
        1, 20 -> R.drawable.ic_maneuver_left
        2, 21 -> R.drawable.ic_maneuver_right
        47 -> R.drawable.ic_maneuver_sharp_left
        48 -> R.drawable.ic_maneuver_sharp_right
        13, 22, 49, 52 -> R.drawable.ic_maneuver_slight_left
        14, 23, 50, 53 -> R.drawable.ic_maneuver_slight_right
        4, 18, 19, 26 -> if (drivingSide == 1) R.drawable.ic_maneuver_u_turn_right else R.drawable.ic_maneuver_u_turn_left
        6, 7, in 28..46 -> R.drawable.ic_maneuver_roundabout
        10, 12, 24, 25, 27 -> R.drawable.ic_maneuver_destination
        else -> R.drawable.ic_maneuver_straight
    }

    private fun distance(context: Context, meters: Long): String = when {
        meters < 1_000 -> context.getString(R.string.widget_distance_meters, ((meters + 5) / 10 * 10).toInt())
        meters < 10_000 -> context.getString(R.string.widget_distance_km_decimal, meters / 1000.0)
        else -> context.getString(R.string.widget_distance_km, ((meters + 500) / 1000).toInt())
    }

    private fun duration(context: Context, seconds: Long): String {
        val minutes = ((seconds + 30) / 60).toInt()
        return if (minutes < 60) context.getString(R.string.widget_minutes, minutes)
        else context.getString(R.string.widget_hours_minutes, minutes / 60, minutes % 60)
    }
}
