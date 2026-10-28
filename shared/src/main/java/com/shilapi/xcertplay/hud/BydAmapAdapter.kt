package com.shilapi.xcertplay.hud

/**
 * The stock AMap adapter that turns AUTONAVI_STANDARD_BROADCAST_SEND broadcasts into cluster guidance.
 * DiLink 3 (Android 10, Qualcomm 6125, "1for2" cluster) ships the same receiver as
 * com.example.amapservice, and its cluster shows the guidance card only in simple-navigation mode.
 */
internal enum class BydAmapAdapter(val packageName: String, val needsSimpleNavigationMode: Boolean) {
    BYD("com.byd.amapservice", needsSimpleNavigationMode = false),
    DILINK3("com.example.amapservice", needsSimpleNavigationMode = true);

    companion object {
        fun find(installed: (String) -> Boolean): BydAmapAdapter? = entries.firstOrNull { installed(it.packageName) }
    }
}

/**
 * The DiLink 3 cluster mode, switched through the AutoContainer binder as ClusterDebug does with
 * sendInfo(1000, command, ""). Apps would need a BYD signature; the adb shell may call it.
 * ClusterDebug labels 16 "full-screen projection on", 17 "half-screen projection on",
 * 18 "projection off" (the stock state) and 39 "simple navigation".
 */
/**
 * The shape the cluster shows DiPlay's map in. Deliberately not DiLink 3 specific: a DiLink 4.0
 * cluster asks for the same choice (#728), so only the availability check has to grow when that
 * route learns to set a shape too.
 */
enum class BydClusterProjectionShape(val key: String) {
    /** Half screen keeps the cluster's own speed and status readouts beside the map. */
    HALF("half"),

    /** Full screen: the map fills the cluster. */
    FULL("full"),
}

/**
 * The DiLink 3 cluster mode, switched through the AutoContainer binder as ClusterDebug does with
 * sendInfo(1000, command, ""). Apps would need a BYD signature; the adb shell may call it.
 * ClusterDebug labels 16 "full-screen projection on", 17 "half-screen projection on",
 * 18 "projection off" (the stock state) and 39 "simple navigation".
 */
internal object BydDiLink3ClusterMode {
    enum class Mode(val info: Int, private val enterVia: Int? = null) {
        // Half screen keeps the cluster's own speed and status readouts beside the map. After 18 the
        // cluster ignores 17 alone and stays empty; it shows the projection display again only once 16
        // has been sent first (tested on a GCC Han).
        PROJECTION(17, enterVia = 16),
        // Full screen: the map fills the cluster. DiPlay switches the cluster to 17 whenever the map
        // appears, which overrides the shape picked on the car, so the driver can pick 16 here.
        FULL_PROJECTION(16),
        SIMPLE_NAVIGATION(39),
        STOCK(18);

        /** True for both projection modes; the recovery check must keep a full-screen projection too. */
        val showsMap: Boolean get() = this == PROJECTION || this == FULL_PROJECTION

        // The serial session validates each Binder reply separately and compensates failure.
        val entryCommand: String? get() = enterVia?.let(::call)
        val command: String get() = call(info)
    }

    private fun call(info: Int) = "service call AutoContainer 2 i32 1000 i32 $info s16 \"\""

    /** The mode to request now, or null while DiPlay has never changed the stock mode. */
    fun desired(
        mapShown: Boolean,
        guidanceActive: Boolean,
        requested: Mode?,
        fullScreen: Boolean = false,
    ): Mode? = when {
        mapShown -> if (fullScreen) Mode.FULL_PROJECTION else Mode.PROJECTION
        guidanceActive -> Mode.SIMPLE_NAVIGATION
        requested != null -> Mode.STOCK
        else -> null
    }

    /** True when the binder call returned without an exception. */
    fun accepted(output: String?): Boolean = BydParcel.words(output).firstOrNull() == 0L

    /**
     * Creates the cluster projection display, fission_bg_xdjaVirtualSurface, which does not exist until
     * the cluster first projects. As DashCast does: full projection, then 35 (Di4.0 mode) creates it; the
     * display then stays after projection off restores the stock view.
     */
    val CREATE_DISPLAY = listOf(16, 35, 18).map { "service call AutoContainer 2 i32 1000 i32 $it s16 \"\"" }
}

/** The DiLink 3 adapter shows these preformatted strings, not the numeric distance and time extras. */
internal object BydDiLink3GuidanceText {
    fun distance(meters: Int): String? = when {
        meters < 0 -> null
        meters < 1000 -> "$meters m"
        meters < 10_000 -> String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
        else -> "${meters / 1000} km"
    }

    fun duration(seconds: Int): String? {
        if (seconds < 0) return null
        val minutes = (seconds + 59) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /**
     * AMap's own ETA_TEXT form, "预计HH:mm到达". The simple-navigation cluster drops the first and last
     * two characters, so a bare "11:49" showed as ":4" (#384), and the adapter's parseTime only sends the
     * arrival hour and minute to the instrument CAN when both words are present.
     */
    fun arrival(nowMillis: Long, seconds: Int, zone: java.util.TimeZone, use24Hour: Boolean): String? {
        if (seconds < 0) return null
        val format = java.text.SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm", java.util.Locale.US)
        format.timeZone = zone
        return "预计${format.format(java.util.Date(nowMillis + seconds * 1000L))}到达"
    }
}
