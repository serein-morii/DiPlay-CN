package com.shilapi.xcertplay.turncamera.sideview

import android.util.Log
import android.view.Surface
import dalvik.system.PathClassLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vendor AVM camera access for the turn side-view feature, written for DiPlay-CN on top of the
 * hidden android.hardware.AVMCamera / BmmCameraInfo interfaces the DiLink firmware ships.
 *
 * Design points, all cheap on purpose:
 *  - [discover] is read-only (id lookup, no open) and can be re-run when everything failed;
 *  - [open] binds BOTH preview channels at once so flipping sides later is a pure window move;
 *  - every vendor call lands in a small timestamped journal the settings row can show;
 *  - one PathClassLoader is kept per jar for the process: ART binds a native library to the
 *    first loader that opened it, so a second loader for the same jar fails to link.
 */
internal class SideViewCamera {
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val journal = ArrayDeque<String>()

    var cameraId: Int = -1
        private set
    var lastDiscoverAt: Long = 0
        private set
    var discoverJournal: List<String> = emptyList()
        private set

    private var handle: Any? = null
    private var loader: ClassLoader? = null
    private var jarLoader: ClassLoader? = null

    /** index → surface of the currently open camera; close() unbinds them all. */
    private val bound = LinkedHashMap<Int, Surface>()

    /** Candidate tags, known names first (they matched the Han DM-i out of the box), then whatever
     *  the firmware itself lists. */
    private val knownTags = listOf("pano_h", "pano_l", "apa", "byd_apa")

    @Synchronized
    fun discover(): Boolean {
        val lines = mutableListOf<String>()
        val note: (String) -> Unit = { journal += it.also { line -> lines += line } }
        lastDiscoverAt = android.os.SystemClock.elapsedRealtime()
        try {
            val info = lookupInfo(note)
            note("cameras=${info.getMethod("getCameraNumbers").invoke(null)}")
            val firmwareTags = runCatching {
                info.getMethod("getValidCameraTag").invoke(null)?.toString()
                    ?.split(',', ';', '|', ' ', '\t', '\n', '[', ']', '{', '}', '(', ')', '"', '\'')
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                    ?: emptyList()
            }.getOrElse { emptyList<String>() }
            for (tag in knownTags) tryTag(info, tag, note)
            if (cameraId < 0) {
                for (tag in firmwareTags.filter { it !in knownTags }) {
                    if (tryTag(info, tag, note)) break
                }
            }
        } catch (e: Throwable) {
            note("discover failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            discoverJournal = lines
            trim()
        }
        return cameraId >= 0
    }

    /** Camera info class from the app class path, or from the vendor jar when the firmware keeps
     *  its camera classes off the class path. */
    private fun lookupInfo(note: (String) -> Unit): Class<*> {
        try {
            val cls = Class.forName(BMM_INFO)
            loader = null
            return cls
        } catch (_: ClassNotFoundException) {
        }
        return lookupInfoFromJar(note) ?: throw ClassNotFoundException(BMM_INFO)
    }

    private fun lookupInfoFromJar(note: (String) -> Unit): Class<*>? {
        val jar = JARS.firstOrNull { File(it).exists() } ?: return null
        val reused = jarLoader
        val use: ClassLoader = reused ?: PathClassLoader(jar, SideViewCamera::class.java.classLoader)
            .also { jarLoader = it }
        return try {
            val cls = Class.forName(BMM_INFO, true, use)
            loader = use
            note("jar ${if (reused != null) "reused" else "loaded"}: $jar")
            cls
        } catch (e: Throwable) {
            note("jar load failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun tryTag(info: Class<*>, tag: String, note: (String) -> Unit): Boolean {
        val id = try {
            (info.getMethod("getCameraId", String::class.java).invoke(null, tag) as Number).toInt()
        } catch (e: Throwable) {
            note("tag $tag lookup failed: ${e.javaClass.simpleName}")
            return false
        }
        note("tag $tag -> id $id")
        if (id < 0 || cameraId >= 0) return false
        cameraId = id
        runCatching {
            val w = info.getMethod("getDefaultPreviewWidth", Int::class.javaPrimitiveType).invoke(null, id)
            val h = info.getMethod("getDefaultPreviewHeight", Int::class.javaPrimitiveType).invoke(null, id)
            note("preview ${w}x$h")
        }
        return true
    }

    /**
     * Opens the camera with [channels] (preview index → surface) all bound. Any refused bind or
     * a failed startPreview closes the handle again — a half-open camera never counts as warm.
     */
    @Synchronized
    fun open(channels: Map<Int, Surface>): Boolean {
        if (cameraId < 0 || channels.isEmpty()) return false
        if (handle != null) close("reopen")
        val avm = try {
            Class.forName(AVM, true, loader ?: SideViewCamera::class.java.classLoader)
        } catch (e: Throwable) {
            note("camera class missing: ${e.javaClass.simpleName}")
            return false
        }
        return try {
            var opened = avm.getMethod("open", Int::class.javaPrimitiveType).invoke(null, cameraId)
            if (opened == null) {
                val ctor = avm.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
                val candidate = ctor.newInstance(cameraId)
                val openMethod = avm.getDeclaredMethod("open").apply { isAccessible = true }
                if (openMethod.invoke(candidate) == true) opened = candidate
            }
            checkNotNull(opened) { "open returned null" }
            note("open id=$cameraId ok")
            handle = opened
            bound.clear()
            bound.putAll(channels)

            hookEventCallback(avm, opened)

            val add = avm.getMethod("addPreviewSurface", Surface::class.java, Int::class.javaPrimitiveType)
            val set = avm.getMethod("setPreviewSurface", Surface::class.java, Int::class.javaPrimitiveType)
            var bindsOk = true
            for ((index, surface) in channels) {
                val a = add.invoke(opened, surface, index)
                val s = set.invoke(opened, surface, index)
                note("bind index=$index add=$a set=$s")
                if (a == false || s == false) bindsOk = false
            }
            val started = avm.getMethod("startPreview").invoke(opened)
            note("startPreview=$started")
            if (!bindsOk || started == false) {
                close("bind refused")
                false
            } else {
                true
            }
        } catch (e: Throwable) {
            note("open failed: ${rootCause(e)}")
            close("open failed")
            false
        }
    }

    /** The vendor pushes per-frame events; only the interesting (non-frame) ones are journalled. */
    private fun hookEventCallback(avm: Class<*>, opened: Any) {
        runCatching {
            val type = Class.forName("$AVM\$IEventCallback", true, avm.classLoader)
            val callback = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                if (method.name == "onEvent" && args != null && args.size >= 4) {
                    val code = (args[1] as? Number)?.toInt() ?: 0
                    if (code != EVENT_FRAME) note("vendor event code=$code a1=${args[2]} a2=${args[3]}")
                }
                null
            }
            avm.getMethod("setEventCallback", type).invoke(opened, callback)
        }
    }

    /** Unbind everything, stop, close — each step attempted independently, once retried. */
    @Synchronized
    fun close(why: String) {
        val cam = handle ?: return
        val type = cam.javaClass
        var firstError: Throwable? = null
        for (attempt in 1..2) {
            var failed = false
            fun step(name: String, body: () -> Unit) {
                runCatching(body).onFailure {
                    failed = true
                    if (firstError == null) firstError = it
                    Log.w(TAG, "close/$name attempt $attempt failed: $it")
                }
            }
            val remove = runCatching {
                type.getMethod("rmPreviewSurface", Surface::class.java, Int::class.javaPrimitiveType)
            }.getOrNull()
            for ((index, surface) in bound) step("unbind[$index]") { remove?.invoke(cam, surface, index) }
            step("stop") { type.getMethod("stopPreview").invoke(cam) }
            step("close") { type.getMethod("close").invoke(cam) }
            if (!failed) break
        }
        handle = null
        bound.clear()
        note("closed ($why${firstError?.let { ", first error ${rootCause(it)}" } ?: ""})")
    }

    val warm: Boolean get() = handle != null

    fun journalTail(lines: Int = 14): List<String> = journal.toList().takeLast(lines)

    @Synchronized
    private fun note(line: String) {
        Log.i(TAG, line)
        journal += "${stamp.format(Date())} $line"
        trim()
    }

    private fun trim() {
        while (journal.size > 200) journal.removeFirst()
    }

    private fun rootCause(e: Throwable): String {
        var cur = e
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return "${cur.javaClass.simpleName}: ${cur.message}"
    }

    companion object {
        private const val TAG = "SideViewCamera"
        private const val BMM_INFO = "android.hardware.BmmCameraInfo"
        private const val AVM = "android.hardware.AVMCamera"
        private val JARS = listOf("/system/framework/bmmcamera.jar")
        private const val EVENT_FRAME = 1001
    }
}
