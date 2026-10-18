package com.shilapi.xcertplay.network

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import android.util.Base64
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.system.exitProcess

/**
 * Shell-UID entry point that [CarHotspotAdbFallback] starts through the head unit's adb with app_process. An app can no
 * longer start tethering itself ("No field mService" on Android 10, "NoSuchMethodException … startTethering" on 12,
 * #616), while the shell may, and holds the tethering permission it checks. Starts Wi-Fi tethering with the car's
 * saved hotspot configuration, never a new one; the opted-in "apconfig" subcommand is the one
 * exception, keeping the car's saved configuration and rewriting only DiPlay's passphrase (and a
 * 5 GHz request) on Android 11+ (see CarHotspotConfigWrite), and
 * prints only the protocol line. Android 7.0–10 call
 * IConnectivityManager; Android 11 moved tethering to TetheringManager.
 */
object CarHotspotStartMain {
    internal const val HEADER = "DIPLAY_HOTSPOT_START_V1"
    internal const val APCONFIG_HEADER = "DIPLAY_HOTSPOT_APCONFIG_V1"
    internal const val APCONFIG_READ_HEADER = "DIPLAY_HOTSPOT_APCONFIG_READ_V1"
    private const val APCONFIG_COMMAND = "apconfig"
    private const val APCONFIG_READ_COMMAND = "apconfig-read"
    private const val DEADLINE_MS = 10_000L
    private const val RESULT_WAIT_MS = 5_000L
    private const val TETHERING_WIFI = 0
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val NO_RESULT = Int.MIN_VALUE

    @JvmStatic fun main(args: Array<String>) {
        val apConfig = args.firstOrNull() == APCONFIG_COMMAND
        val apConfigRead = args.firstOrNull() == APCONFIG_READ_COMMAND
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "hotspot-start-helper-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            when {
                apConfig -> apConfig(args.drop(1).toTypedArray())
                apConfigRead -> apConfigRead()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startWithTetheringManager()
                else -> startWithConnectivity()
            }
        } catch (error: Throwable) {
            "error ${CarHotspotTethering.describe(error)}"
        }
        val header = when {
            apConfigRead -> APCONFIG_READ_HEADER
            apConfig -> APCONFIG_HEADER
            else -> HEADER
        }
        println("$header|$result")
        exitProcess(0)
    }

    private fun service(name: String): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, name) as? IBinder

    /**
     * The shell rewrites only what the car's saved configuration lacks: DiPlay's passphrase (and a 5 GHz request), on top
     * of [Builder(current)]; the passphrase arrives on stdin so it never appears in the process list.
     */
    private fun apConfig(args: Array<String>): String {
        val configuration = Class.forName("android.net.wifi.SoftApConfiguration")
        if (args.size < 2) return "usage"
        val (ssid, band) = args
        val passphrase = readPassphrase() ?: return "usage"
        val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("wifi") ?: return "unsupported") ?: return "unsupported"
        val current = currentSoftAp(wifi)
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = if (current != null) {
            builderClass.getDeclaredConstructor(configuration).newInstance(current)
        } else {
            builderClass.getDeclaredConstructor().newInstance()
        }
        val builderType = builder.javaClass
        val saved = current?.javaClass?.methods?.firstOrNull { it.name == "getSsid" }?.invoke(current) as? String
        val effective = saved?.takeIf { it.isNotEmpty() } ?: ssid
        builderType.getMethod("setSsid", String::class.java).invoke(builder, effective)
        val security = current?.javaClass?.methods?.firstOrNull { it.name == "getSecurityType" }?.invoke(current) as? Int
        // An open hotspot from DiPlay must not strip the car's saved password; if the car's security
        // type cannot be read, refusing the empty passphrase is the safe choice.
        if (passphrase.isEmpty() && current != null &&
            security != staticInt(configuration, "SECURITY_TYPE_OPEN")) return "error open"
        if (passphrase.isNotEmpty()) {
            builderType.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                .invoke(builder, passphrase, security?.takeIf { it != staticInt(configuration, "SECURITY_TYPE_OPEN") }
                    ?: staticInt(configuration, "SECURITY_TYPE_WPA2_PSK"))
        } else {
            builderType.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                .invoke(builder, null, staticInt(configuration, "SECURITY_TYPE_OPEN"))
        }
        if (band == ManualHotspotBand.GHZ_5.name) {
            val currentBand = current?.javaClass?.methods?.firstOrNull { it.name == "getBand" }?.invoke(current) as? Int
            // A saved 5 GHz band keeps its channel; anything else moves to 5 GHz with driver channel selection.
            if (currentBand == null || currentBand != staticInt(configuration, "BAND_5GHZ")) {
                builderType.getMethod("setBand", Int::class.javaPrimitiveType)
                    .invoke(builder, staticInt(configuration, "BAND_5GHZ"))
            }
        }
        val config = builderType.getMethod("build").invoke(builder)
        val method = wifi.javaClass.methods.firstOrNull {
            it.name == "setSoftApConfiguration" &&
                it.parameterTypes.contentEquals(arrayOf(config.javaClass, String::class.java))
        } ?: return "unsupported"
        return if (method.invoke(wifi, config, SHELL_PACKAGE) as? Boolean == true) "ok|${b64(effective)}" else "error rejected"
    }

    /** "<byte length>\n<bytes>" from stdin; the writer sends the length line first so no EOF is needed. */
    private fun readPassphrase(): String? = try {
        val input = System.`in`
        val length = readAsciiLine(input).trim().toIntOrNull()
        if (length == null || length < 0 || length > 256) {
            null
        } else {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) break
                offset += read
            }
            String(bytes, 0, offset, Charsets.UTF_8)
        }
    } catch (error: Throwable) {
        null
    }

    private fun readAsciiLine(input: java.io.InputStream): String {
        val line = StringBuilder()
        while (true) {
            val character = input.read()
            if (character < 0 || character == '\n'.code) break
            if (character != '\r'.code) line.append(character.toChar())
        }
        return line.toString()
    }

    /** The shell reads the car's saved AP configuration verbatim (11+); nothing is written. */
    private fun apConfigRead(): String {
        val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("wifi") ?: return "unsupported") ?: return "unsupported"
        val config = currentSoftAp(wifi) ?: return "unsupported"
        val type = config.javaClass
        val ssid = type.methods.firstOrNull { it.name == "getSsid" }?.invoke(config) as? String
            ?: return "unsupported"
        val passphrase = type.methods.firstOrNull { it.name == "getPassphrase" }?.invoke(config) as? String ?: ""
        val security = type.methods.firstOrNull { it.name == "getSecurityType" }?.invoke(config) as? Int
            ?: return "unsupported"
        return "ok|${b64(ssid)}|${b64(passphrase)}|$security"
    }

    private fun currentSoftAp(wifi: Any): Any? = try {
        wifi.javaClass.methods.firstOrNull {
            it.name == "getSoftApConfiguration" && it.parameterTypes.isEmpty()
        }?.invoke(wifi)
    } catch (error: Throwable) {
        null
    }

    private fun b64(value: String): String = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)

    private fun staticInt(type: Class<*>, field: String): Int =
        type.getDeclaredField(field).get(null) as Int

    private fun startWithConnectivity(): String {
        val binder = service("connectivity") ?: return "unsupported"
        val service = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return "unsupported"
        val method = service.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.contentEquals(arrayOf(
                Int::class.javaPrimitiveType, ResultReceiver::class.java, Boolean::class.javaPrimitiveType, String::class.java))
        } ?: return "unsupported"
        val result = Answer()
        method.invoke(service, TETHERING_WIFI, object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) = result.set(resultCode)
        }, false, SHELL_PACKAGE)
        return result.await()
    }

    private fun startWithTetheringManager(): String {
        val connector = service("tethering") ?: return "unsupported"
        val managerType = Class.forName("android.net.TetheringManager")
        val manager = managerType.getDeclaredConstructor(Context::class.java, Supplier::class.java)
            .apply { isAccessible = true }
            .newInstance(ShellContext(), Supplier<IBinder> { connector })
        val builder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        val request = builder.javaClass.getMethod("build").invoke(builder)
        val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val result = Answer()
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { result.set(0); null }
                "onTetheringFailed" -> { result.set(args?.firstOrNull() as? Int ?: -1); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DiPlayStartTetheringCallback"
                else -> null
            }
        }
        managerType.getMethod("startTethering", request.javaClass, Executor::class.java, callbackType)
            .invoke(manager, request, Executor { it.run() }, callback)
        return result.await()
    }

    /** The calls the tethering service checks: the shell's package for the shell's uid, no attribution tag. */
    private class ShellContext : ContextWrapper(null) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
    }

    /** "requested" when no result arrived in time, else "result N" (0 is TETHER_ERROR_NO_ERROR). */
    private class Answer {
        private val code = AtomicInteger(NO_RESULT)
        private val answered = CountDownLatch(1)

        fun set(value: Int) {
            code.set(value)
            answered.countDown()
        }

        fun await(): String {
            answered.await(RESULT_WAIT_MS, TimeUnit.MILLISECONDS)
            return if (code.get() == NO_RESULT) "requested" else "result ${code.get()}"
        }
    }

    fun command(apk: String): String {
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${CarHotspotStartMain::class.java.name}"
    }

    /** The helper's reply, or null when its protocol line is missing; other output is ignored. */
    fun parse(output: String?): String? {
        val prefix = "$HEADER|"
        return output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** "requested" when the call returned without a result yet; "result 0" is TETHER_ERROR_NO_ERROR. */
    fun accepted(reply: String?): Boolean = reply == "requested" || reply == "result 0"

    fun apConfigCommand(apk: String, credentials: HotspotCredentials): String {
        fun quoted(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        return "${command(apk)} $APCONFIG_COMMAND ${quoted(credentials.ssid)} ${credentials.band.name}"
    }

    fun apConfigReadCommand(apk: String): String = "${command(apk)} $APCONFIG_READ_COMMAND"

    /** The apconfig helper's reply, or null when its protocol line is missing. */
    fun parseApConfig(output: String?): String? {
        val prefix = "$APCONFIG_HEADER|"
        return output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** The apconfig-read helper's reply, or null when its protocol line is missing. */
    fun parseApConfigRead(output: String?): String? {
        val prefix = "$APCONFIG_READ_HEADER|"
        return output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    fun apConfigAccepted(reply: String?): Boolean = reply?.startsWith("ok") == true

    /** The hotspot name the helper actually saved or read, reported as a Base64 field of "ok|<ssid>". */
    fun apConfigSsid(reply: String?): String? = apConfigField(reply, 1)

    /** The passphrase the helper read, "" for an open hotspot. */
    fun apConfigPassphrase(reply: String?): String = apConfigField(reply, 2) ?: ""

    /** The SoftApConfiguration security type the helper read (0 open, 1 WPA2, 2 transition, 3 SAE). */
    fun apConfigSecurity(reply: String?): Int? =
        reply?.takeIf { it.startsWith("ok|") }?.split('|')?.getOrNull(3)?.toIntOrNull()

    private fun apConfigField(reply: String?, index: Int): String? =
        reply?.takeIf { it.startsWith("ok|") }?.split('|')?.getOrNull(index)
            ?.takeIf { it.isNotEmpty() }
            ?.let { field ->
                runCatching {
                    val decoded = Base64.decode(field, Base64.NO_WRAP)
                    String(decoded).takeIf { Base64.encodeToString(decoded, Base64.NO_WRAP) == field }
                }.getOrNull()
            }
            ?.takeIf { it.isNotEmpty() }
}
