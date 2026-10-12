package com.shilapi.xcertplay.orchestration

/**
 * What to do with the configurations a wired iPhone offers once USB permission is granted.
 *
 * iOS 17 and later offer USBMUX and NCM outside CarPlay mode too: an iPhone 12 Pro Max on iOS 26
 * listed five configurations, and the fifth had both but no Apple Ethernet interface (#634).
 * DiPlay used it as it was. iAP2 and StartSession succeeded, but the iPhone never opened AirPlay.
 * After one CarPlay mode request the same iPhone re-enumerated with configuration 6 and worked.
 * So a configuration without Apple Ethernet is used only after the mode request was tried.
 */
internal enum class WiredConfigurationAction(val diagnostic: String) {
    USE("reuse-descriptors"),
    USE_WITHOUT_APPLE_ETHERNET("reuse-without-apple-ethernet"),
    REQUEST_TRANSITION("request-transition"),
    REJECT("reject-missing-configuration"),
    ;

    companion object {
        /**
         * [found] is a configuration with USBMUX and NCM, and [carPlayMode] says that it also has
         * Apple Ethernet. A rejected request falls back to [found] (see [fallbackAfterRejection]).
         */
        fun choose(found: Boolean, carPlayMode: Boolean, transitionsTried: Int, maximumTransitions: Int) = when {
            found && carPlayMode -> USE
            found && transitionsTried > 0 -> USE_WITHOUT_APPLE_ETHERNET
            transitionsTried < maximumTransitions -> REQUEST_TRANSITION
            else -> REJECT
        }

        /**
         * An iPhone that rejects the mode request (iOS 12 and 15 do, #509, #455, and so does a
         * locked one, #100) is still tried on the configuration it offered, as before.
         */
        fun fallbackAfterRejection(found: Boolean, carPlayMode: Boolean): Boolean = found && !carPlayMode
    }
}
