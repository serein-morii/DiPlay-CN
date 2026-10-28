package com.shilapi.xcertplay.turncamera

import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Raw int reads of the experimental signal getters for calibration capture: the three
 * BYD Light getters plus the draft profile's OEM getter when it parses. Returns the four
 * ints in [TurnCameraSignalCapture] order (turn, hazard, reverse, oem); null on any read
 * failure so a stuck getter never freezes a capture phase. Instantiate on the capture
 * worker; never from the UI thread. No setters, writes or shell — reads only.
 */
internal class TurnCameraRawSignalReader(
    context: Context,
    draftJson: String,
    private val clock: () -> Long,
    private val load: (String) -> Class<*> = { Class.forName(it, false, TurnCameraRawSignalReader::class.java.classLoader) },
) : AutoCloseable {
    private class Getter(val instance: Any, val method: Method) { fun read() = method.invoke(instance) as Int }

    private val appContext = context.applicationContext
    private val getters: List<Getter>
    private var closed = false

    @Volatile private var last: IntArray? = null

    init {
        val light = device(
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            listOf("getTurnLightState", "getDoubleFlashLightState", "getReversingLightState"),
        )
        val oem = runCatching {
            val o = org.json.JSONObject(draftJson)
            val type = o.optString("oemClass")
            val getter = o.optString("oemGetter")
            if (TurnCameraExperimentSettings.validOemGetter(type, getter)) device(type, listOf(getter)) else emptyList()
        }.getOrDefault(emptyList())
        getters = light + oem
    }

    fun available(): Boolean = getters.isNotEmpty()

    fun read(): IntArray? {
        if (closed) return null
        val at = clock()
        return try {
            val values = IntArray(4)
            for (i in 0 until 3) values[i] = getters[i].read()
            values[3] = getters.getOrNull(3)?.read() ?: 0
            last = values
            values
        } catch (_: Exception) {
            null
        }.also { if (it == null && last != null) return null }
    }

    /** The most recent successful read, for the live capture display. */
    fun lastRead(): IntArray? = last

    override fun close() {
        closed = true
    }

    private fun device(name: String, names: List<String>): List<Getter> {
        val type = load(name)
        val methods = names.map { type.getMethod(it).also { m ->
            check(m.returnType == Integer.TYPE && !Modifier.isStatic(m.modifiers))
        } }
        val factory = type.getMethod("getInstance", Context::class.java)
        check(Modifier.isStatic(factory.modifiers))
        val instance = checkNotNull(factory.invoke(null, appContext))
        return methods.map { Getter(instance, it) }
    }
}
