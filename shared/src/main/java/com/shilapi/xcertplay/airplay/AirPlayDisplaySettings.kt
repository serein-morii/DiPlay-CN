package com.shilapi.xcertplay.airplay

enum class AirPlayPhysicalSizeBasis {
    WIDTH,
    HEIGHT,
}

data class AirPlayPhysicalSizeMm(
    val widthMm: Int,
    val heightMm: Int,
)

/** User-adjustable display values with the same stepped ranges used by the settings UI. */
object AirPlayDisplaySettings {
    const val MIN_FPS = 30
    const val MAX_FPS = 60
    const val FPS_STEP = 5
    const val DEFAULT_FPS = MAX_FPS

    const val MIN_WIDTH_PHYSICAL_MM = 100
    const val MAX_WIDTH_PHYSICAL_MM = 400
    // Fine steps: iOS changes its layout at a few physical sizes, and 50 mm could jump past them (#489).
    // 5 mm keeps every earlier 25 mm value on the grid (#913).
    const val WIDTH_PHYSICAL_MM_STEP = 5
    const val DEFAULT_WIDTH_PHYSICAL_MM = 200
    val DEFAULT_PHYSICAL_SIZE_BASIS = AirPlayPhysicalSizeBasis.WIDTH
    const val MIN_REPORTED_PHYSICAL_MM = 1
    const val MAX_REPORTED_PHYSICAL_MM = 2_000

    fun sanitizeFps(value: Int): Int =
        snapToStep(value, MIN_FPS, MAX_FPS, FPS_STEP)

    fun sanitizeWidthPhysicalMm(value: Int): Int =
        snapToStep(
            value,
            MIN_WIDTH_PHYSICAL_MM,
            MAX_WIDTH_PHYSICAL_MM,
            WIDTH_PHYSICAL_MM_STEP,
        )

    fun sanitizeReportedPhysicalMm(value: Int): Int =
        value.coerceIn(MIN_REPORTED_PHYSICAL_MM, MAX_REPORTED_PHYSICAL_MM)

    fun resolvePhysicalSizeMm(
        currentWidthPixels: Int,
        currentHeightPixels: Int,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
        referenceMillimeters: Int,
        basis: AirPlayPhysicalSizeBasis,
    ): AirPlayPhysicalSizeMm {
        require(currentWidthPixels > 0 && currentHeightPixels > 0) {
            "Current display dimensions must be positive"
        }
        require(maximumWidthPixels > 0 && maximumHeightPixels > 0) {
            "Maximum display dimensions must be positive"
        }
        val referencePixels = when (basis) {
            AirPlayPhysicalSizeBasis.WIDTH ->
                currentWidthPixels.toDouble() / maximumWidthPixels
            AirPlayPhysicalSizeBasis.HEIGHT ->
                currentHeightPixels.toDouble() / maximumHeightPixels
        }
        val referenceMm = referenceMillimeters.coerceAtLeast(1) * referencePixels
        val widthMm: Double
        val heightMm: Double
        when (basis) {
            AirPlayPhysicalSizeBasis.WIDTH -> {
                widthMm = referenceMm
                heightMm = widthMm * currentHeightPixels / currentWidthPixels
            }
            AirPlayPhysicalSizeBasis.HEIGHT -> {
                heightMm = referenceMm
                widthMm = heightMm * currentWidthPixels / currentHeightPixels
            }
        }
        return AirPlayPhysicalSizeMm(
            widthMm = sanitizeReportedPhysicalMm(Math.round(widthMm).toInt()),
            heightMm = sanitizeReportedPhysicalMm(Math.round(heightMm).toInt()),
        )
    }

    fun fpsProgress(value: Int): Int =
        (sanitizeFps(value) - MIN_FPS) / FPS_STEP

    fun widthPhysicalMmProgress(value: Int): Int =
        (sanitizeWidthPhysicalMm(value) - MIN_WIDTH_PHYSICAL_MM) / WIDTH_PHYSICAL_MM_STEP

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Int {
        val clamped = value.coerceIn(minimum, maximum)
        return (((clamped - minimum) + step / 2) / step) * step + minimum
    }
}
