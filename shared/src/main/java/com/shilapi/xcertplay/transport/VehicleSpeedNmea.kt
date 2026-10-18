package com.shilapi.xcertplay.transport

import java.util.Locale

enum class VehicleGear(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }

/** One wheel-speed reading; [elapsedMillis] is on the monotonic clock since boot. */
data class VehicleSpeedSample(val elapsedMillis: Long, val metersPerSecond: Double)

/** Wheel-speed readings collected since the last sentence, with the gear at the time. */
data class VehicleSpeedReading(val gear: VehicleGear, val samples: List<VehicleSpeedSample>)

/** Car wheel speed for dead reckoning; sampled in the background so the iAP2 loop never waits. */
interface VehicleSpeedSource {
    fun start()

    fun stop()

    /** Readings since the previous call, or null when there are none. */
    fun drain(): VehicleSpeedReading?
}

/**
 * Apple's `$PASCD` vehicle-speed sentence, sent in iAP2 0xFFFB next to GGA/RMC. The layout follows a
 * real head unit's log: `$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54` is the first sample's
 * time in seconds, `C`, the gear, `0`, the sample count, then offset/speed (m/s) pairs. What `C` and
 * the `0` mean is not public, so they are copied as seen.
 */
object PascdEncoder {
    fun encode(reading: VehicleSpeedReading): String? {
        val samples = reading.samples.takeIf { it.isNotEmpty() } ?: return null
        val start = samples.first().elapsedMillis
        val body = buildString {
            append("PASCD,").append(format("%.3f", start / 1000.0)).append(",C,")
            append(reading.gear.letter).append(",0,").append(samples.size)
            for (sample in samples) {
                append(',').append(format("%.2f", (sample.elapsedMillis - start) / 1000.0))
                append(',').append(format("%.3f", sample.metersPerSecond.coerceAtLeast(0.0)))
            }
        }
        var checksum = 0
        for (character in body) checksum = checksum xor character.code
        return "$$body*${format("%02X", checksum)}\r\n"
    }

    private fun format(format: String, vararg arguments: Any): String = String.format(Locale.US, format, *arguments)
}

/**
 * Adds `$PASCD` to [position]'s GGA/RMC when the iPhone asked for vehicle speed. Speed is still sent
 * when there is no position fix, as in a tunnel, which is when the iPhone needs it most.
 */
class VehicleSpeedLocationProvider(
    private val position: Iap2LocationProvider,
    private val speed: VehicleSpeedSource,
    private val log: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : Iap2LocationProvider {
    @Volatile private var speedRequested = false
    // A minute of what reached the iPhone. In a tunnel the speed went after about two minutes (#807), and
    // the report could not say whether the car's position or its wheel speed had stopped.
    private var windowStart = -1L
    private var updates = 0
    private var withFix = 0
    private var withSpeed = 0
    private var speedSamples = 0
    private var lastKmh: Double? = null

    override fun onRequested(components: Set<Int>) {
        speedRequested = Iap2LocationMessages.VEHICLE_SPEED_DATA in components
        position.onRequested(components)
    }

    override fun start(): Boolean {
        val started = position.start()
        if (speedRequested) speed.start()
        return started || speedRequested
    }

    override fun stop() {
        speed.stop()
        position.stop()
    }

    override fun latestNmea(): String? {
        val fix = position.latestNmea()
        val reading = if (speedRequested) speed.drain() else null
        val pascd = reading?.let(PascdEncoder::encode)
        record(fix != null, reading)
        return if (fix == null) pascd else fix + pascd.orEmpty()
    }

    private fun record(hasFix: Boolean, reading: VehicleSpeedReading?) {
        val now = nowMillis()
        if (windowStart < 0) windowStart = now
        updates++
        if (hasFix) withFix++
        if (!reading?.samples.isNullOrEmpty()) {
            withSpeed++
            speedSamples += reading!!.samples.size
            lastKmh = reading.samples.last().metersPerSecond * 3.6
        }
        if (now - windowStart < SUMMARY_MILLIS) return
        log("Location: last ${(now - windowStart) / 1000} s updates=$updates withPosition=$withFix " +
            "withWheelSpeed=$withSpeed wheelSpeedSamples=$speedSamples " +
            "lastWheelSpeed=${lastKmh?.let { String.format(Locale.US, "%.1f", it) + " km/h" } ?: "none"} " +
            "speedRequested=$speedRequested")
        windowStart = now
        updates = 0
        withFix = 0
        withSpeed = 0
        speedSamples = 0
    }

    internal companion object {
        const val SUMMARY_MILLIS = 60_000L
    }
}
