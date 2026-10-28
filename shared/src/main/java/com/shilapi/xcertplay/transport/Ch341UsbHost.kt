package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * Android USB Host discovery and authorization for a configured CH341 identity.
 *
 * This class intentionally stops before the CH341 I2C stream protocol. [openAsync] executes the
 * potentially blocking open/claim work on the supplied executor; callers should also close the
 * returned session away from the main thread.
 */
class Ch341UsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: Ch341DeviceMatcher,
    private val permissionAction: String = "${context.packageName}.CH341_USB_PERMISSION",
) {
    private val appContext = context.applicationContext
    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class OpenResult {
        data class Connected(val session: Ch341UsbSession) : OpenResult()
        data class Failed(val error: I2cTransportException) : OpenResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    /**
     * Every USB device Android lists, as `vid:pid node`, sorted. When no configured CH341 is listed, this shows
     * whether the adapter left the bus or came back under another identity (#792).
     */
    fun listedDevices(): List<String> =
        usbManager.deviceList.values.map(::describe).sorted()

    @Throws(I2cTransportException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /** Returns null for unrelated broadcasts, malformed results, or devices outside the matcher. */
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

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                parsePermissionResult(intent)?.let(onResult)
            }
        }
        val filter = IntentFilter(permissionAction)
        UsbPermissionBroadcast.register(appContext, receiver, filter)
        return Closeable { appContext.unregisterReceiver(receiver) }
    }

    /** Opens and claims bulk endpoints on [executor], never on the caller thread. */
    fun openAsync(device: UsbDevice, executor: Executor, callback: (OpenResult) -> Unit) {
        executor.execute {
            try {
                callback(OpenResult.Connected(open(device)))
            } catch (error: I2cTransportException) {
                callback(OpenResult.Failed(error))
            }
        }
    }

    private fun open(device: UsbDevice): Ch341UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw I2cTransportException.PermissionDenied("USB permission has not been granted")
        }
        val endpoints = findBulkEndpoints(device)
            ?: throw I2cTransportException.DeviceUnavailable("No USB interface has both bulk IN and OUT endpoints")
        val connection = usbManager.openDevice(device)
            ?: throw I2cTransportException.DeviceUnavailable("UsbManager could not open the CH341 device")

        if (!connection.claimInterface(endpoints.usbInterface, true)) {
            connection.close()
            throw I2cTransportException.DeviceUnavailable("Could not claim CH341 USB interface")
        }
        return Ch341UsbSession(connection, endpoints.usbInterface, endpoints.input, endpoints.output)
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw I2cTransportException.DeviceUnavailable("USB device is not a configured CH341 identity")
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

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun findBulkEndpoints(device: UsbDevice): BulkEndpoints? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> input = input ?: endpoint
                    UsbConstants.USB_DIR_OUT -> output = output ?: endpoint
                }
            }
            if (input != null && output != null) return BulkEndpoints(usbInterface, input, output)
        }
        return null
    }

    private data class BulkEndpoints(
        val usbInterface: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    companion object {
        /** `1a86:5512 /dev/bus/usb/001/004`: the USB identity and device node only, no serial number. */
        fun describe(device: UsbDevice): String =
            "%04x:%04x %s".format(device.vendorId, device.productId, device.deviceName)
    }
}

/** Claimed CH341 USB resources. All transfer methods are blocking and must run off the main thread. */
class Ch341UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val inputEndpoint: UsbEndpoint,
    val outputEndpoint: UsbEndpoint,
) : Closeable {
    private var closed = false

    @Synchronized
    internal fun bulkWrite(data: ByteArray, timeoutMillis: Int) {
        val transferred = transfer(outputEndpoint, data, timeoutMillis, "write")
        if (transferred != data.size) {
            Log.w(TAG, "bulk write sent $transferred of ${data.size} bytes: ${data.toHexPreview()}")
            throw I2cTransportException.Protocol(
                "CH341 bulk write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /**
     * Reads the controller's answer to one stream packet, stopping at whatever it sends.
     *
     * The number of ACK/NACK status bytes the CH341 prepends to the read data is a firmware detail,
     * so callers pass the worst-case length and align the answer from its tail. The first packet is
     * awaited for [firstPacketMillis]; continuation packets only wait [quietMillis], so an answer
     * shorter than [maxLength] costs one short wait instead of the full transaction timeout.
     * [allowEmpty] accepts a packet the controller chooses not to answer at all.
     */
    @Synchronized
    internal fun bulkReadAtMost(
        maxLength: Int,
        firstPacketMillis: Int,
        quietMillis: Int,
        allowEmpty: Boolean,
    ): ByteArray {
        if (maxLength < 0) {
            throw I2cTransportException.InvalidRequest("Bulk read length must not be negative")
        }
        if (maxLength == 0) return ByteArray(0)
        if (firstPacketMillis <= 0 || quietMillis <= 0) {
            throw I2cTransportException.InvalidRequest("Bulk transfer timeout must be positive")
        }
        val data = ByteArray(maxLength)
        // A full-size USB packet does NOT complete a bulk transfer: the read returns only
        // once the buffer is full or a SHORT packet arrives. Asking for even one byte past
        // the end of a whole-packet answer therefore makes bulkTransfer wait for data that
        // never comes and time out, discarding the bytes that already arrived. The CH341
        // answers in whole 32-byte packets, so every request stops on a packet boundary.
        val packetSize = minOf(inputEndpoint.maxPacketSize.coerceAtLeast(1), MAX_USB_PACKET_BYTES)
        var offset = 0
        while (offset < maxLength) {
            val packet = ByteArray(minOf(maxLength - offset, packetSize))
            val waitMillis = if (offset == 0) firstPacketMillis else quietMillis
            val fatal = offset == 0 && !allowEmpty
            val transferred = try {
                transfer(inputEndpoint, packet, waitMillis, "read", clearHaltOnFailure = fatal)
            } catch (error: I2cTransportException.DeviceUnavailable) {
                // The answer ended early: fewer status bytes than the worst case, or a packet the
                // controller does not answer at all.
                if (offset > 0 || allowEmpty) {
                    Log.d(
                        TAG,
                        "bulk read ended early at $offset of $maxLength bytes " +
                            "(quiet-packet timeout of ${waitMillis}ms; accepted as end of answer)",
                    )
                    break
                }
                throw error
            }
            if (transferred <= 0) {
                if (offset > 0 || allowEmpty) break
                throw I2cTransportException.DeviceUnavailable("CH341 bulk read returned no data")
            }
            System.arraycopy(packet, 0, data, offset, transferred)
            offset += transferred
        }
        if (offset != maxLength) {
            // The answer was shorter than the worst-case length the transport budgeted for: the
            // controller returned fewer status bytes than it had written bytes, or the stream
            // framing drifted off a packet boundary. Both are worth seeing in logcat.
            Log.d(TAG, "bulk read answered $offset of $maxLength bytes (allowEmpty=$allowEmpty)")
        }
        return data.copyOf(offset)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    private fun transfer(
        endpoint: UsbEndpoint,
        data: ByteArray,
        timeoutMillis: Int,
        operation: String,
        clearHaltOnFailure: Boolean = true,
    ): Int {
        if (closed) throw I2cTransportException.DeviceUnavailable("CH341 USB session is closed")
        if (data.isEmpty()) throw I2cTransportException.InvalidRequest("Bulk $operation data must not be empty")
        if (timeoutMillis <= 0) {
            throw I2cTransportException.InvalidRequest("Bulk transfer timeout must be positive")
        }

        val transferred = try {
            connection.bulkTransfer(endpoint, data, data.size, timeoutMillis)
        } catch (error: SecurityException) {
            throw I2cTransportException.PermissionDenied("USB permission was denied during bulk $operation")
        } catch (error: RuntimeException) {
            throw I2cTransportException.DeviceUnavailable("CH341 bulk $operation failed", error)
        }
        if (transferred < 0) {
            // Android's bulkTransfer result does not identify whether the device timed out, NAKed,
            // or reported another USB failure. Do not manufacture a CH341 NAK classification.
            clearEndpointHalt(endpoint, operation)
            throw I2cTransportException.DeviceUnavailable("CH341 bulk $operation failed or timed out")
        }
        return transferred
    }

    /**
     * Releases a bulk endpoint left halted by a failed transfer.
     *
     * A USB stall stays latched per endpoint, so once one transfer fails every later transfer on
     * that endpoint fails the same way until something clears the halt. Re-opening the device
     * clears it, which is why a full stack rebuild always recovers; clearing it here keeps a single
     * stalled transfer from consuming the caller's entire retry budget.
     *
     * A control transfer is harmless when the endpoint was never halted, so this is a one-sided bet.
     */
    private fun clearEndpointHalt(endpoint: UsbEndpoint, operation: String) {
        val result = try {
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or
                    USB_RECIP_ENDPOINT,
                USB_REQUEST_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                endpoint.address,
                null,
                0,
                RECOVERY_TIMEOUT_MILLIS,
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "halt clear on endpoint ${endpoint.address} threw after failed bulk $operation", error)
            -1
        }
        Log.w(TAG, "bulk $operation failed; halt clear on endpoint ${endpoint.address} returned $result")
    }

    private fun ByteArray.toHexPreview(): String = joinToString(" ") { "%02x".format(it) }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** CH341A bulk endpoints report wMaxPacketSize 32; never request across a boundary. */
        const val MAX_USB_PACKET_BYTES = 32

        const val TAG = "Ch341UsbHost"

        /** Milliseconds allowed for the halt-clear control transfer before recovery is abandoned. */
        const val RECOVERY_TIMEOUT_MILLIS = 200

        // USB 2.0 standard request: CLEAR_FEATURE(ENDPOINT_HALT) on one endpoint.
        // Android exposes USB_DIR_* and USB_TYPE_* on UsbConstants but not the USB_RECIP_*
        // recipient codes, so the recipient value is spelled out locally.
        const val USB_REQUEST_CLEAR_FEATURE = 0x01
        const val USB_FEATURE_ENDPOINT_HALT = 0x0000
        const val USB_RECIP_ENDPOINT = 0x02
    }
}
