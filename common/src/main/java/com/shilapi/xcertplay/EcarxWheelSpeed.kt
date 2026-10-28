package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Wheel speed and gear from the ECARX car SDK (`ISensor`), for the iPhone's dead reckoning. The SDK is
 * in the head unit's boot classpath, so it is reached by reflection; an ordinary app reads these
 * sensors with no extra permission (Geely Monjaro, ECARX KX11, Android 9).
 *
 * The speed is polled rather than subscribed to: a listener only fires on a change, so a standing car
 * would send nothing, and the iPhone should hear 0 m/s at a red light. Its raw value is taken as m/s:
 * the Monjaro tweak app MConfig+ shows raw x 3.72 as the cluster's km/h, and clusters read about 3 %
 * over the true speed.
 */
internal object EcarxWheelSpeed : VehicleSpeedSource {
    private const val TAG = "DiPlay-EcarxSpeed"
    private const val CAR = "com.ecarx.xui.adaptapi.car.Car"
    private const val ICAR = "com.ecarx.xui.adaptapi.car.ICar"
    private const val SENSOR = "com.ecarx.xui.adaptapi.car.sensor.ISensor"
    private const val SENSOR_TYPE_CAR_SPEED = 1048832
    private const val SENSOR_TYPE_GEAR = 2097664
    private const val GEAR_PARK = 2097712
    private const val GEAR_REVERSE = 2097728
    private const val GEAR_NEUTRAL = 2097680
    private const val GEAR_DRIVE = 2097696
    private val GEAR_FORWARD = 2097665..2097674 // GEAR_FIRST..GEAR_TENTH, manual mode
    private const val MAX_METERS_PER_SECOND = 90.0
    private const val READ_MILLIS = 250L
    private const val GEAR_EVERY_READS = 4
    private const val MAX_SAMPLES = 40

    private var context: Context? = null
    private var sensor: Any? = null
    private var executor: ScheduledExecutorService? = null
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var reads = 0
    private var firstSampleLogged = false
    private var failureLogged = false

    /**
     * No position for the iPhone: the Monjaro's head unit has no GPS receiver, and an Android network
     * fix would only mislead it. With this, only `$PASCD` is sent, beside the iPhone's own GPS.
     */
    object NoPosition : Iap2LocationProvider {
        override fun start(): Boolean = false

        override fun stop() = Unit

        override fun latestNmea(): String? = null
    }

    /** Whether the ECARX car SDK is on this head unit. */
    fun present(): Boolean = runCatching { Class.forName(CAR); Class.forName(SENSOR) }.isSuccess

    fun attach(appContext: Context) = apply { context = appContext.applicationContext }

    @Synchronized
    override fun start() {
        if (executor != null) return
        samples.clear()
        gear = null
        reads = 0
        firstSampleLogged = false
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "wheel speed started")
    }

    override fun stop() {
        val running = synchronized(this) { executor.also { executor = null } } ?: return
        running.shutdown()
        Log.i(TAG, "wheel speed stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    internal fun gear(event: Int): VehicleGear? = when (event) {
        GEAR_PARK -> VehicleGear.PARK
        GEAR_REVERSE -> VehicleGear.REVERSE
        GEAR_NEUTRAL -> VehicleGear.NEUTRAL
        GEAR_DRIVE, in GEAR_FORWARD -> VehicleGear.DRIVE
        else -> null
    }

    internal fun metersPerSecond(raw: Float): Double? =
        raw.toDouble().takeIf { it.isFinite() && it in 0.0..MAX_METERS_PER_SECOND }

    private fun poll() {
        val sensor = sensor() ?: return
        if (reads++ % GEAR_EVERY_READS == 0) {
            val event = runCatching { call(sensor, "getSensorEvent", SENSOR_TYPE_GEAR) as Int }.getOrNull()
            val next = event?.let(::gear)
            val changed = synchronized(this) { (gear != next).also { gear = next } }
            if (changed) Log.i(TAG, "gear $next (event $event)")
        }
        val before = SystemClock.elapsedRealtime()
        val raw = runCatching { call(sensor, "getSensorLatestValue", SENSOR_TYPE_CAR_SPEED) as Float }.getOrNull() ?: return
        val speed = metersPerSecond(raw) ?: return
        val sample = VehicleSpeedSample((before + SystemClock.elapsedRealtime()) / 2, speed)
        synchronized(this) {
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += sample
        }
        if (!firstSampleLogged) {
            firstSampleLogged = true
            Log.i(TAG, "first wheel speed raw $raw = ${"%.1f".format(speed * 3.6)} km/h")
        }
    }

    /**
     * The ECARX sensor manager, once it reports the speed sensor as supported. An unsupported sensor
     * may read 0, and a false 0 would tell the iPhone the car stands while its GPS sees it moving.
     */
    private fun sensor(): Any? = sensor ?: runCatching {
        val app = context ?: return null
        val car = Class.forName(CAR).getMethod("create", Context::class.java).invoke(null, app)
        // Through the public interface: the implementing class may not be accessible to reflection.
        val manager = Class.forName(ICAR).getMethod("getSensorManager").invoke(car)!!
        val status = call(manager, "isSensorSupported", SENSOR_TYPE_CAR_SPEED)?.toString()
        check(status != null && status != "notavailable" && status != "error") { "speed sensor $status" }
        Log.i(TAG, "ECARX speed sensor $status")
        manager
    }.onSuccess { sensor = it }.onFailure {
        // The ECARX framework can come up after DiPlay at boot, so this retries; say it once.
        if (!failureLogged) Log.w(TAG, "ECARX sensors unavailable, retrying", it)
        failureLogged = true
    }.getOrNull()

    private fun call(sensor: Any, method: String, type: Int): Any? =
        Class.forName(SENSOR).getMethod(method, Int::class.javaPrimitiveType).invoke(sensor, type)
}
