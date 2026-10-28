package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Speed and gear through the adb shell (autoservice binder), where apps would need a BYD signature.
 * The ids are BYD SDK 1.0.5's speed (km/h) and gearbox devices; on DiLink 5.0 they read 0.0 and 1
 * while parked.
 */
internal object BydWheelSpeed {
    const val SPEED = "service call autoservice 7 i32 1013 i32 -1807745016" // float km/h
    const val GEAR = "service call autoservice 5 i32 1011 i32 555745336" // 1 P, 2 R, 3 N, 4 D

    fun speedCommand(context: Context): String = if (BydOutputSettings.legacyVehicleProbe(context)) {
        BydVehicleFieldStore.load(context)?.result(BydVehicleField.SPEED)?.address?.command() ?: SPEED
    } else SPEED

    fun gearCommand(context: Context): String = if (BydOutputSettings.legacyVehicleProbe(context)) {
        BydVehicleFieldStore.load(context)?.result(BydVehicleField.GEAR)?.address?.command() ?: GEAR
    } else GEAR

    fun metersPerSecond(output: String?): Double? {
        val kmh = BydParcel.value(output)?.let { java.lang.Float.intBitsToFloat(it).toDouble() } ?: return null
        return if (kmh in 0.0..300.0) kmh / 3.6 else null
    }

    fun gear(output: String?): VehicleGear? = when (BydParcel.value(output)) {
        1 -> VehicleGear.PARK
        2 -> VehicleGear.REVERSE
        3 -> VehicleGear.NEUTRAL
        4, 5, 6 -> VehicleGear.DRIVE // M and S are forward selector positions on older SDKs.
        else -> null
    }
}

/**
 * Optional, needs ADB over network: wheel speed for the iPhone's dead reckoning in tunnels. Reads the
 * speed four times a second and the gear once a second, only while the iPhone asks for location.
 */
internal object BydWheelSpeedSource : VehicleSpeedSource {
    private const val TAG = "DiPlay-BYD-Speed"
    private const val READ_MILLIS = 250L
    private const val GEAR_EVERY_READS = 4
    private const val MAX_SAMPLES = 40

    private val shell = BydAdbShell(TAG)
    private var context: Context? = null
    private var executor: ScheduledExecutorService? = null
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var reads = 0
    private var readLog = WheelSpeedReads()
    private var firstSampleLogged = false

    fun attach(appContext: Context) = apply { context = appContext.applicationContext }

    @Synchronized
    override fun start() {
        if (executor != null) return
        samples.clear()
        gear = null
        reads = 0
        readLog = WheelSpeedReads()
        firstSampleLogged = false
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-wheel-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "wheel speed started")
    }

    override fun stop() {
        val running = synchronized(this) { executor.also { executor = null } } ?: return
        // Close adb on the reader thread, after any read in flight, then let the thread end.
        running.execute { shell.close() }
        running.shutdown()
        Log.i(TAG, "wheel speed stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    @Synchronized
    override fun status(): String = readLog.describe(shell.lastAccess?.name ?: "untried", gear)

    private fun poll() {
        val app = context ?: return
        if (reads++ % GEAR_EVERY_READS == 0) {
            val reply = shell.run(app, BydWheelSpeed.gearCommand(app))
            val read = BydWheelSpeed.gear(reply)
            synchronized(this) { readLog.gear(reply, read) }
            read?.let { next ->
                val changed = synchronized(this) { (gear != next).also { gear = next } }
                if (changed) Log.i(TAG, "gear $next")
            }
        }
        val before = SystemClock.elapsedRealtime()
        val reply = shell.run(app, BydWheelSpeed.speedCommand(app))
        val read = BydWheelSpeed.metersPerSecond(reply)
        synchronized(this) { readLog.speed(reply, read) }
        val speed = read ?: return
        val sample = VehicleSpeedSample((before + SystemClock.elapsedRealtime()) / 2, speed)
        synchronized(this) {
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += sample
        }
        if (!firstSampleLogged) {
            firstSampleLogged = true
            Log.i(TAG, "first wheel speed ${"%.1f".format(speed * 3.6)} km/h")
        }
    }
}

/**
 * What the wheel-speed reads returned since the source started. A DiLink 4.0 sent no speed for a whole drive
 * (#807) and the report could not say whether the speed or the gear read failed, or what the car answered.
 */
internal class WheelSpeedReads {
    private var speedReads = 0
    private var speedValues = 0
    private var speedReply: String? = null
    private var gearReply: String? = null

    fun speed(reply: String?, value: Double?) {
        speedReads++
        if (value != null) speedValues++ else if (speedReply == null) speedReply = shown(reply)
    }

    fun gear(reply: String?, value: VehicleGear?) {
        if (value == null && gearReply == null) gearReply = shown(reply)
    }

    /** [gear] is the latest gear read; the first unreadable reply of each read is kept, a missing one as none. */
    fun describe(adb: String, gear: VehicleGear?): String = buildString {
        append("adb=").append(adb).append(" speed=").append(speedValues).append('/').append(speedReads)
        append(" gear=").append(gear?.letter ?: "none")
        if (speedValues < speedReads) append(" speedReply=").append(speedReply)
        if (gear == null) append(" gearReply=").append(gearReply ?: "none")
    }

    private fun shown(reply: String?): String =
        reply?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_REPLY_CHARS)?.let { "\"$it\"" } ?: "none"

    private companion object {
        const val MAX_REPLY_CHARS = 80
    }
}
