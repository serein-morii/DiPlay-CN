package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

private const val FIRST_APPLICATION_UID = 10_000

/** Grants iPhone USB access when Android 7.1 through 9 has no permission activity to show. */
internal object IphoneUsbAdbGrant {
    internal const val HEADER = "DIPLAY_USB_GRANT_V1"
    enum class Code { GRANTED, NO_DEVICE, ADB_UNAVAILABLE, UNSUPPORTED, DENIED, ERROR }

    data class Result(
        val code: Code,
        val attached: Int = 0,
        val granted: Int = 0,
    ) {
        val permissionGranted: Boolean get() = granted > 0
    }

    /** AOSP 8-9 shell has MANAGE_USB; Android 7.1 needs a root or vendor-authorized shell. */
    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk in Build.VERSION_CODES.N_MR1..Build.VERSION_CODES.P

    fun grant(context: Context, device: UsbDevice): Result {
        if (!supported()) return Result(Code.UNSUPPORTED)
        if (device.vendorId != IphoneUsbMatcher.APPLE_VENDOR_ID) return Result(Code.DENIED)
        val app = context.applicationContext
        return LocalAdb(AdbKeys.load(app)).use { adb ->
            // Never offer a new key here: a wired connection must not trigger an ADB approval prompt.
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use Result(Code.ADB_UNAVAILABLE)
            parse(
                adb.shell(
                    command(
                        app.applicationInfo.sourceDir,
                        app.packageName,
                        app.applicationInfo.uid,
                        device.vendorId,
                        device.productId,
                    ),
                    10_000,
                ),
            )
        }
    }

    internal fun command(
        apk: String,
        packageName: String,
        targetUid: Int,
        vendorId: Int,
        productId: Int,
    ): String {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))
        require(targetUid >= FIRST_APPLICATION_UID)
        require(vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID && productId in 0..0xffff)
        val quotedApk = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quotedApk app_process /system/bin ${IphoneUsbGrantTool::class.java.name} " +
            "$packageName $targetUid $vendorId $productId"
    }

    internal fun parse(output: String?): Result {
        val fields = output?.trim()?.lineSequence()?.lastOrNull()?.split('|') ?: return Result(Code.ERROR)
        if (fields.size != 4 || fields[0] != HEADER) return Result(Code.ERROR)
        val code = Code.entries.firstOrNull { it.name == fields[1] } ?: return Result(Code.ERROR)
        val counts = fields.drop(2).map { it.toIntOrNull() ?: return Result(Code.ERROR) }
        if (counts.any { it !in 0..16 } || counts[1] > counts[0]) return Result(Code.ERROR)
        if (code == Code.GRANTED && counts[1] == 0) return Result(Code.ERROR)
        return Result(code, counts[0], counts[1])
    }

    internal fun packageMatchesUid(
        packageName: String,
        targetUid: Int,
        packagesForUid: Array<String>?,
    ): Boolean = targetUid >= FIRST_APPLICATION_UID && packageName in packagesForUid.orEmpty()
}

/**
 * Runs under an already-approved adb shell UID. It grants only attached devices with the exact
 * requested Apple VID/PID to the installed DiPlay package and prints no device identifiers.
 */
object IphoneUsbGrantTool {
    private const val ROOT_UID = 0
    private const val SHELL_UID = 2000

    @JvmStatic
    fun main(args: Array<String>) {
        Thread({ Thread.sleep(12_000); exitProcess(2) }, "diplay-usb-grant-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            execute(args)
        } catch (error: Throwable) {
            IphoneUsbAdbGrant.Result(
                if (unwrap(error) is SecurityException) IphoneUsbAdbGrant.Code.DENIED
                else IphoneUsbAdbGrant.Code.ERROR,
            )
        }
        println("${IphoneUsbAdbGrant.HEADER}|${result.code}|${result.attached}|${result.granted}")
        exitProcess(0)
    }

    @SuppressLint("PrivateApi")
    private fun execute(args: Array<String>): IphoneUsbAdbGrant.Result {
        if (Process.myUid() != SHELL_UID && Process.myUid() != ROOT_UID) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.DENIED)
        }
        if (!IphoneUsbAdbGrant.supported() || args.size != 4) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.UNSUPPORTED)
        }
        val packageName = args[0]
        if (!packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+"))) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.DENIED)
        }
        val targetUid = args[1].toIntOrNull()
        val vendorId = args[2].toIntOrNull()
        val productId = args[3].toIntOrNull()
        if (targetUid == null || targetUid < FIRST_APPLICATION_UID ||
            vendorId != IphoneUsbMatcher.APPLE_VENDOR_ID || productId !in 0..0xffff) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.DENIED)
        }

        val context = systemContext()
        val packagesForUid = context.packageManager.getPackagesForUid(targetUid)
        if (!IphoneUsbAdbGrant.packageMatchesUid(
                packageName,
                targetUid,
                packagesForUid,
            )) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.DENIED)
        }
        val usb = context.getSystemService(UsbManager::class.java)
            ?: return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.UNSUPPORTED)
        val devices = usb.deviceList.values
            .filter { it.vendorId == vendorId && it.productId == productId }
            .take(16)
        if (devices.isEmpty()) return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.NO_DEVICE)

        val service = usbService() ?: return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.UNSUPPORTED)
        val grant = Class.forName("android.hardware.usb.IUsbManager")
            .getMethod("grantDevicePermission", UsbDevice::class.java, Integer.TYPE)
        var granted = 0
        var denied = false
        for (device in devices) {
            try {
                invoke(grant, service, device, targetUid)
                granted++
            } catch (error: Throwable) {
                if (unwrap(error) is SecurityException) denied = true
            }
        }
        val code = when {
            granted > 0 -> IphoneUsbAdbGrant.Code.GRANTED
            denied -> IphoneUsbAdbGrant.Code.DENIED
            else -> IphoneUsbAdbGrant.Code.ERROR
        }
        return IphoneUsbAdbGrant.Result(code, devices.size, granted)
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        runCatching { android.os.Looper.prepareMainLooper() }
        val activityThread = Class.forName("android.app.ActivityThread")
        val main = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(main) as Context
    }

    @SuppressLint("PrivateApi")
    private fun usbService(): Any? {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.USB_SERVICE) as? IBinder ?: return null
        val stub = Class.forName("android.hardware.usb.IUsbManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    private fun invoke(method: java.lang.reflect.Method, target: Any, vararg args: Any?) {
        try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is InvocationTargetException && error.targetException != null) error.targetException else error
}
