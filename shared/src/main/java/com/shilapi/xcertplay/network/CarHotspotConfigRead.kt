package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.util.concurrent.atomic.AtomicReference

/** The hotspot details saved on the car, read back from its own configuration. */
data class CarHotspotSavedCredentials(
    val ssid: String,
    val passphrase: String,
    val security: ManualHotspotSecurity,
)

/**
 * Reads the car's saved hotspot configuration without touching it. Android 7.1 reads from the app:
 * getWifiApConfiguration is hidden, but its only service-side gate is ACCESS_WIFI_STATE and the
 * stored WifiConfiguration still carries the raw SSID and passphrase. Android 11+ reads through the
 * shell helper, because the service enforces the signature NETWORK_SETTINGS permission that the
 * shell holds. WPA3 configurations are read as-is; DiPlay publishes them unchanged.
 */
object CarHotspotConfigRead {
    enum class Reason { UNSUPPORTED_SDK, ADB_UNAVAILABLE, UNAVAILABLE }

    data class Outcome(
        val credentials: CarHotspotSavedCredentials? = null,
        val reason: Reason? = null,
        val access: LocalAdb.Access? = null,
    )

    /**
     * [mayAsk]: an unknown DiPlay key is offered to adbd, which shows the car's approval prompt, and
     * the read waits for the driver's answer. Only for a read the driver started themselves.
     */
    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT, mayAsk: Boolean = false, log: (String) -> Unit): Outcome {
        if (!CarHotspotConfigWrite.supported(sdk)) {
            log("car hotspot config: unsupported Android $sdk")
            return Outcome(reason = Reason.UNSUPPORTED_SDK)
        }
        return if (sdk <= Build.VERSION_CODES.N_MR1) readWithApp(context, log)
        else readWithAdb(LocalAdb(AdbKeys.load(context)), context.applicationInfo.sourceDir, mayAsk, log)
    }

    private fun readWithApp(context: Context, log: (String) -> Unit): Outcome {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return Outcome(reason = Reason.UNAVAILABLE).also { log("car hotspot config: no Wi-Fi manager") }
        val managerType = WifiManager::class.java
        return readApp({ managerType.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration }, log)
    }

    internal fun readApp(read: () -> WifiConfiguration?, log: (String) -> Unit): Outcome = try {
        val config = read()
        val keyManagement = config?.allowedKeyManagement
        val credentials = savedCredentials(
            ssid = config?.SSID,
            passphrase = config?.preSharedKey ?: "",
            security = when {
                keyManagement == null -> null
                keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) -> ManualHotspotSecurity.WPA2
                keyManagement.cardinality() == 0 || keyManagement.get(WifiConfiguration.KeyMgmt.NONE) ->
                    ManualHotspotSecurity.OPEN
                else -> null
            },
            log = log)
        if (credentials == null) Outcome(reason = Reason.UNAVAILABLE) else Outcome(credentials)
    } catch (error: Exception) {
        log("car hotspot config: app read failed ${CarHotspotTethering.describe(error)}")
        Outcome(reason = Reason.UNAVAILABLE)
    }

    /** Uses and closes [adb]. Asking adds the driver's approval time to the read's 15 s. */
    internal fun readWithAdb(adb: LocalAdb, apk: String, mayAsk: Boolean, log: (String) -> Unit): Outcome {
        val outcome = AtomicReference<Outcome?>()
        val approval = if (mayAsk) LocalAdb.APPROVAL_TIMEOUT_MS * 1_000_000L else 0L
        val deadline = System.nanoTime() + 15_000_000_000L + approval
        CarHotspotAdbFallback.bounded(deadline, { false },
            abort = { adb.cancelPendingOperations() }) {
            adb.use {
                val access = if (System.nanoTime() >= deadline) null else adb.connect(mayAsk = mayAsk)
                if (access != LocalAdb.Access.READY) {
                    outcome.set(Outcome(reason = Reason.ADB_UNAVAILABLE, access = access))
                    false
                } else {
                    val timeout = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1_000, 30_000).toInt()
                    outcome.set(readAdb(adb::shell, apk, timeout, log))
                    outcome.get()?.credentials != null
                }
            }
        }
        return outcome.get()
            ?: Outcome(reason = Reason.UNAVAILABLE).also { log("car hotspot config: ADB read did not finish") }
    }

    internal fun readAdb(
        shell: (String, Int) -> String?,
        apk: String,
        timeoutMillis: Int,
        log: (String) -> Unit,
    ): Outcome {
        val reply = CarHotspotStartMain.parseApConfigRead(
            shell(CarHotspotStartMain.apConfigReadCommand(apk), timeoutMillis))
        if (reply == null || !reply.startsWith("ok")) {
            log("car hotspot config: shell read failed: ${reply ?: "no reply"}")
            return Outcome(reason = Reason.UNAVAILABLE)
        }
        val credentials = savedCredentials(
            ssid = CarHotspotStartMain.apConfigSsid(reply),
            passphrase = CarHotspotStartMain.apConfigPassphrase(reply),
            security = CarHotspotStartMain.apConfigSecurity(reply)?.let { securityOf(it) },
            log = log)
        return if (credentials == null) Outcome(reason = Reason.UNAVAILABLE) else Outcome(credentials)
    }

    /** The SoftApConfiguration security constants of Android 11: 0 open, 1 WPA2, 2 transition, 3 SAE. */
    internal fun securityOf(securityType: Int): ManualHotspotSecurity? = when (securityType) {
        0 -> ManualHotspotSecurity.OPEN
        1 -> ManualHotspotSecurity.WPA2
        2 -> ManualHotspotSecurity.WPA3_TRANSITION
        3 -> ManualHotspotSecurity.WPA3
        else -> null
    }

    private fun savedCredentials(
        ssid: String?,
        passphrase: String,
        security: ManualHotspotSecurity?,
        log: (String) -> Unit,
    ): CarHotspotSavedCredentials? {
        if (ssid.isNullOrBlank()) {
            log("car hotspot config: the car saved no hotspot name")
            return null
        }
        if (security == null) {
            log("car hotspot config: the car saved an unsupported security mode")
            return null
        }
        if (security == ManualHotspotSecurity.OPEN && passphrase.isNotEmpty()) {
            log("car hotspot config: an open hotspot with a passphrase")
            return null
        }
        if (security != ManualHotspotSecurity.OPEN && passphrase.isBlank()) {
            log("car hotspot config: a secured hotspot without a passphrase")
            return null
        }
        return CarHotspotSavedCredentials(ssid, passphrase, security)
    }
}
