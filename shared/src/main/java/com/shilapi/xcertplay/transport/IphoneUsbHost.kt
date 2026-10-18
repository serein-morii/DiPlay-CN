package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(
            val device: UsbDevice,
            val afterMissingUiAdbFailure: Boolean = false,
        ) : PermissionRequest()
        data class AdbRequired(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    /**
     * Every USB device Android lists, as `vid:pid node` like [Ch341UsbHost.describe], sorted. When no iPhone is
     * listed, this shows whether Android sees anything on the head unit's port at all.
     */
    fun listedDevices(): List<String> =
        usbManager.deviceList.values.map(Ch341UsbHost::describe).sorted()

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice, allowAdbFallback: Boolean = true): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        val ui = UsbPermissionActivityProbe.inspect(appContext)
        if (ui.state != UsbPermissionActivityProbe.State.UNKNOWN) {
            runCatching { onDiagnostic(ui.diagnostic()) }
        }
        if (ui.state == UsbPermissionActivityProbe.State.UNAVAILABLE) {
            if (allowAdbFallback && IphoneUsbAdbGrant.supported()) {
                return PermissionRequest.AdbRequired(device)
            }
        }
        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(
            device,
            afterMissingUiAdbFailure = ui.state == UsbPermissionActivityProbe.State.UNAVAILABLE,
        )
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                val transferred = connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    CARPLAY_CONFIGURATION_REQUEST,
                    0,
                    CARPLAY_CONFIGURATION_INDEX,
                    response,
                    response.size,
                    CONTROL_TRANSFER_TIMEOUT_MILLIS,
                )
                if (transferred != response.size) {
                    // iOS 12 and 15 rejected this request even while unlocked (#509, #455); a locked
                    // iPhone rejects it too (#100). Record what the iPhone exposes to tell them apart.
                    runCatching { onDiagnostic(modeRejectionDiagnostic(connection, device)) }
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $transferred of ${response.size} bytes",
                    )
                }
                TransitionResult.ReenumerationRequested
            })
        }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        var connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            connection = selectConfiguration(connection, device, configuration)
            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=${configuration.id} iface=${usbMux.id} alt=${usbMux.alternateSetting} " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface 1")
            }
            claimedInterface = usbMux
            return Iap2UsbSession(connection, endpoints.first, endpoints.second, onDiagnostic)
        } catch (error: Throwable) {
            if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            connection.close()
            throw error
        }
    }

    /**
     * Selects [configuration], detaching head-unit drivers that block the switch (#518). Returns
     * the connection to use from now on: the detach replaces [connection] with a new one.
     */
    private fun selectConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configuration: UsbConfiguration,
    ): UsbDeviceConnection {
        val interfaces = (0 until device.configurationCount).map(device::getConfiguration)
            .flatMap { config -> (0 until config.interfaceCount).map(config::getInterface) }
            .associateBy { it.id }
        var current = connection
        try {
            val outcome = UsbConfigurationSelection.select(
                target = configuration.id,
                interfaceIds = interfaces.keys.toList(),
                setConfiguration = { current.setConfiguration(configuration) },
                activeConfiguration = { activeConfiguration(current) },
                // A forced claim detaches the kernel driver. It stays claimed until the reopen.
                detach = { id -> current.claimInterface(interfaces.getValue(id), true) },
                // Closing drops the claims without the driver reattach that releaseInterface() requests.
                reopen = {
                    current.close()
                    current = usbManager.openDevice(device)
                        ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not reopen the iPhone")
                },
            ) ?: return current
            runCatching { onDiagnostic(outcome.diagnostic()) }
            if (outcome.wrongConfiguration) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not switch the iPhone to USB configuration ${configuration.id} " +
                        "(still ${outcome.after}); a head-unit driver may hold it",
                )
            }
            if (!outcome.selected) {
                Log.w(IphoneCarPlayConfiguration.TAG, "setConfiguration ${configuration.id} reported failure; claiming anyway")
            }
            return current
        } catch (error: Throwable) {
            // The caller closes the connection it passed in; a replacement is closed here.
            if (current !== connection) current.close()
            throw error
        }
    }

    /** The iPhone's current configuration value (standard GET_CONFIGURATION), or null if unreadable. */
    private fun activeConfiguration(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        val read = connection.controlTransfer(
            USB_STANDARD_DEVICE_IN, GET_CONFIGURATION_REQUEST, 0, 0, value, value.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return if (read == value.size) value[0].toInt() and 0xff else null
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    /** The iPhone's current USB mode (usbmuxd's 0x45 request) and every configuration it exposes. */
    private fun modeRejectionDiagnostic(connection: UsbDeviceConnection, device: UsbDevice): String {
        val mode = ByteArray(USB_MODE_RESPONSE_LENGTH)
        val read = connection.controlTransfer(
            USB_VENDOR_DEVICE_IN, GET_USB_MODE_REQUEST, 0, 0, mode, mode.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return usbModeDiagnostic(read, mode, device.version,
            (0 until device.configurationCount).map(device::getConfiguration).map { "${it.id}[${IphoneCarPlayConfiguration.describe(it)}]" })
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val USB_STANDARD_DEVICE_IN = 0x80
        private const val GET_CONFIGURATION_REQUEST = 0x08
        private const val GET_USB_MODE_REQUEST = 0x45
        private const val USB_MODE_RESPONSE_LENGTH = 4
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000

        /** usbmuxd reads 3:3:3:0 in the initial mode and 5:3:3:0 once a mode is set. */
        /** iOS 12 and 15 answer the mode request with 3 bytes instead of 4 (#509, #455); keep them. */
        internal fun usbModeDiagnostic(read: Int, mode: ByteArray, deviceVersion: String?, configurations: List<String>): String {
            val bytes = mode.copyOf(read.coerceIn(0, mode.size)).joinToString(":") { (it.toInt() and 0xff).toString() }
            val current = when (read) {
                mode.size -> bytes
                in 1 until mode.size -> "$bytes (short:$read)"
                else -> "unreadable($read)"
            }
            return "CarPlay USB mode request rejected; current mode=$current " +
                "usbVersion=${deviceVersion ?: "unknown"} configurations=$configurations"
        }
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var pendingRead: UsbRequest? = null
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val requests = UsbRequestQueue(connection, "usbmux-read-reaper")

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val transferred = connection.bulkTransfer(outEndpoint, data, data.size, timeoutMillis)
        if (transferred != data.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val request = UsbRequest()
        var initialized = false
        try {
            if (!request.initialize(connection, inEndpoint)) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                )
            }
            initialized = true
            val buffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
            val queueResult = synchronized(stateLock) {
                checkOpenLocked()
                pendingRead = request
                readQueuePolicy.queue(buffer, ::checkOpenLocked) { requests.queue(request, it) }
            }
            if (!queueResult.queued) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis)} " +
                        "firstBytes=${queueResult.firstBytes} fallbackBytes=${queueResult.fallbackBytes ?: "not_attempted"})",
                )
            }
            // The policy remembers an accepted fallback, so this event occurs once per pipe.
            if (queueResult.fallbackBytes != null) runCatching {
                onDiagnostic(
                    "USBMUX read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queueResult.firstBytes} " +
                        "fallbackBytes=${queueResult.fallbackBytes}",
                )
            }
            val completed = try {
                requests.await(timeoutMillis)
            } catch (_: TimeoutException) {
                return@synchronized drainTimedOutRead(request, buffer)
            }
            if (completed == null) {
                throw failSession("Android returned no USBMUX read request")
            }
            if (completed !== request) {
                throw failSession("Android completed an unexpected USB request")
            }
            return@synchronized ByteArray(buffer.position()).also {
                buffer.flip()
                buffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        } finally {
            synchronized(stateLock) {
                if (pendingRead === request) pendingRead = null
            }
            if (initialized) request.cancel()
            request.close()
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingRead
        }
        requestToCancel?.cancel()
        requests.close()
        connection.close()
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    /**
     * Cancels a read that timed out and takes it back from Android. A read that completes between
     * the timeout and the cancel can no longer be cancelled: it is already on its way back. That
     * race ended the whole session every few minutes on a Telechips and a DiLink 5.1 head unit
     * (#610, #301). The read is drained either way, and any bytes it carries are returned so the
     * USBMUX stream stays intact.
     */
    private fun drainTimedOutRead(request: UsbRequest, buffer: ByteBuffer): ByteArray? {
        val cancelled = request.cancel()
        val completed = try {
            requests.await(CANCEL_DRAIN_TIMEOUT_MILLIS)
        } catch (_: TimeoutException) {
            throw failSession(
                if (cancelled) "Timed out draining cancelled USBMUX read request"
                else "Android could not cancel timed out USBMUX read request",
            )
        }
        if (completed !== request) {
            throw failSession("Android did not drain the cancelled USBMUX read request")
        }
        if (buffer.position() == 0) return null
        return ByteArray(buffer.position()).also {
            buffer.flip()
            buffer.get(it)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun requestDiagnostics(timeoutMillis: Long): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536
        const val CANCEL_DRAIN_TIMEOUT_MILLIS = 1_000L
    }
}

internal fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi I2C failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
