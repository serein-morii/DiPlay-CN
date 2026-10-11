package com.shilapi.xcertplay.transport

/**
 * Switches the iPhone to its CarPlay configuration when a head-unit kernel driver holds an
 * interface of the current one.
 *
 * Linux refuses SET_CONFIGURATION while any interface of the active configuration is bound
 * ("usbfs: interface 0 claimed by snd-usb-audio while '…' sets config #6"). An 8227L kernel bound
 * snd-usb-audio to the iPhone's audio configuration, so every switch failed, the claims landed on
 * the wrong configuration and DiPlay reported "could not claim the NCM interface 3" (#518).
 * Android detaches a kernel driver only through a forced claim, so each interface number is
 * claimed with force and released at once; usbfs does not rebind the driver on release.
 */
internal object UsbConfigurationSelection {
    data class Outcome(
        val target: Int,
        val selected: Boolean,
        val before: Int?,
        val after: Int?,
        val detached: List<Int>,
    ) {
        /** Android reported the switch as failed and the iPhone still reads another configuration. */
        val wrongConfiguration: Boolean get() = !selected && after != null && after != target

        fun diagnostic(): String =
            "USB configuration select target=$target ok=$selected before=${before ?: "unknown"} " +
                "detached=${detached.joinToString(",").ifEmpty { "none" }} after=${after ?: "unknown"}"
    }

    /**
     * Returns null when the first [setConfiguration] succeeds. Otherwise detaches drivers from
     * [interfaceIds] unless [activeConfiguration] already reads [target], and tries once more.
     */
    fun select(
        target: Int,
        interfaceIds: List<Int>,
        setConfiguration: () -> Boolean,
        activeConfiguration: () -> Int?,
        detach: (Int) -> Boolean,
    ): Outcome? {
        if (setConfiguration()) return null
        val before = activeConfiguration()
        if (before == target) return Outcome(target, selected = false, before, before, emptyList())
        val detached = interfaceIds.distinct().sorted().filter(detach)
        val selected = setConfiguration()
        return Outcome(target, selected, before, activeConfiguration(), detached)
    }
}
