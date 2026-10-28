package com.shilapi.xcertplay.turncamera

import android.os.Looper
import android.view.Surface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated experimental AVMCamera adapter. ABI evidence: user-provided APK,
 * VendorCameraHelper openAvm/addPreviewSurface/startPreview/stopPreview/close.
 * Independently implemented: no extracted implementation, JAR or native binary is loaded.
 *
 * Must be called on an owning worker, never the UI thread. The caller supplies an explicitly
 * selected ID and a fail-closed consent/firmware/OEM-occupancy/lease guard. There is NO default
 * ID, camera scan, fallback to OEM activities, vehicle write, or synthetic frame callback.
 * Opening/binding successfully does not prove any frame arrived. The surface stays caller-owned.
 */
internal class VendorAvmCameraDriver {
    private companion object {
        // A second Activity/adapter cannot bypass a blocked call or quarantine.
        val sessions = ReflectionAvmSessions(Surface::class.java) {
            Class.forName("android.hardware.AVMCamera", false, VendorAvmCameraDriver::class.java.classLoader)
        }
    }

    fun open(cameraId: Int, surface: Surface, previewChannel: Int, allowed: () -> Boolean): AutoCloseable {
        check(Looper.myLooper() != Looper.getMainLooper()) { "vendor camera calls require an owning worker" }
        require(surface.isValid) { "preview surface is invalid" }
        return sessions.open(cameraId, surface, previewChannel, allowed)
    }
}

/** Reflection/lifecycle core is separate so its ordering can be tested without a real camera.
 * Each instance admits one session; an unconfirmed close permanently quarantines the instance.
 * No threads are started here: cancellation of a blocking vendor call must invalidate [allowed],
 * not spawn replacement workers. When that call returns the late handle is closed, not published.
 */
internal class ReflectionAvmSessions<S : Any>(
    private val surfaceType: Class<S>,
    private val cameraClass: () -> Class<*>,
) {
    private val occupied = AtomicBoolean(false)
    @Volatile private var quarantined = false

    fun open(cameraId: Int, surface: S, previewChannel: Int, allowed: () -> Boolean): AutoCloseable {
        require(cameraId >= 0 && previewChannel >= 0)
        check(allowed()) { "camera access is not approved" }
        check(occupied.compareAndSet(false, true)) { "previous camera session is busy or quarantined" }
        var instance: Any? = null
        var close: Method? = null
        var stop: Method? = null
        var startAttempted = false
        var openAttempted = false
        try {
            // Read after acquiring the slot: close may have quarantined it between
            // a pre-acquisition check and the successful compare-and-set.
            check(!quarantined) { "previous camera session is quarantined" }
            val type = cameraClass()
            val open = type.getMethod("open", Integer.TYPE)
            check(Modifier.isStatic(open.modifiers) && type.isAssignableFrom(open.returnType)) { "unsupported open ABI" }
            // Resolve the entire ABI BEFORE opening. AVMCamera evidence is the two-argument
            // boolean overload, not the BmmCamera single-Surface overload. Never retry a
            // second overload after a target exception (a partial bind may have occurred).
            val bind = type.getMethod("addPreviewSurface", surfaceType, Integer.TYPE).also(::requireBooleanInstance)
            val start = type.getMethod("startPreview").also(::requireBooleanInstance)
            stop = type.getMethod("stopPreview").also(::requireBooleanInstance)
            close = type.getMethod("close").also {
                check(!Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE) { "unsupported close ABI" }
            }
            check(allowed()) { "camera access was revoked" }
            openAttempted = true
            instance = checkNotNull(open.invoke(null, cameraId)) { "vendor returned no camera" }
            check(allowed()) { "late camera open was cancelled" }
            check(bind.invoke(instance, surface, previewChannel) == true) { "preview binding failed" }
            check(allowed()) { "preview was cancelled" }
            startAttempted = true
            check(start.invoke(instance) == true) { "preview start failed" }
            check(allowed()) { "late preview start was cancelled" }
            val owned = instance
            val closeMethod = close
            val stopMethod = stop
            val owner = Thread.currentThread()
            return object : AutoCloseable {
                private var closed = false
                override fun close() {
                    check(Thread.currentThread() === owner) { "camera must close on its owning worker" }
                    if (closed) return
                    closed = true
                    release(owned, stopMethod, closeMethod, true)
                }
            }
        } catch (failure: Throwable) {
            if (instance != null && close != null) {
                try { release(instance, stop, close, startAttempted) }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            } else {
                // Invocation failures during open can leave an inaccessible vendor resource.
                // Conservatively retire this adapter when its lifecycle cannot be established.
                if (openAttempted) quarantined = true
                occupied.set(false)
            }
            throw failure
        }
    }

    private fun requireBooleanInstance(method: Method) {
        check(!Modifier.isStatic(method.modifiers) &&
            method.returnType in listOf(java.lang.Boolean.TYPE, java.lang.Boolean::class.java)) { "unsupported preview ABI" }
    }

    private fun release(instance: Any, stop: Method?, close: Method, started: Boolean) {
        var failure: Throwable? = null
        try {
            if (started) check(stop!!.invoke(instance) == true) { "preview stop was not confirmed" }
        } catch (t: Throwable) { failure = t }
        try { close.invoke(instance) }
        catch (t: Throwable) {
            quarantined = true
            if (failure == null) failure = t else failure.addSuppressed(t)
        } finally {
            // A close invocation that is still blocked never reaches here and keeps the slot.
            if (failure != null) quarantined = true
            occupied.set(false)
        }
        failure?.let { throw it }
    }
}
