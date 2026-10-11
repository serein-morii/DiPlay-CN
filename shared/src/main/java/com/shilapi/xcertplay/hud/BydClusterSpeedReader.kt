package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Independent reader: does not drain or stop the iPhone dead-reckoning source. */
object BydClusterSpeedReader {
    fun observe(context: Context, changed: (Int?) -> Unit): () -> Unit {
        val app = context.applicationContext
        val shell = BydAdbShell("DiPlay-Cluster-Speed")
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-cluster-speed").apply { isDaemon = true }
        }
        val poll = executor.scheduleWithFixedDelay({
            val started = SystemClock.elapsedRealtime()
            val speed = runCatching {
                if (BydOutputSettings.legacyVehicleProbe(app) &&
                    BydVehicleFieldStore.load(app)?.supports(BydVehicleField.SPEED) != true) null
                else BydWheelSpeed.metersPerSecond(shell.run(app, BydWheelSpeed.speedCommand(app)))
            }.getOrNull()
            changed(if (SystemClock.elapsedRealtime() - started <= 2_000) kmh(speed) else null)
        }, 0, 250, TimeUnit.MILLISECONDS)
        return {
            poll.cancel(false)
            executor.execute { shell.close() }
            executor.shutdown()
        }
    }

    internal fun kmh(metersPerSecond: Double?): Int? = metersPerSecond
        ?.takeIf { it.isFinite() && it >= 0 && it * 3.6 <= 300 }
        ?.let { (it * 3.6).roundToInt() }
}
