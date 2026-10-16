package com.shilapi.xcertplay.turncamera

import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Experimental, explicitly selected zero-argument int getters only. No FID writes,
 * setter calls, shell commands, dynamic JARs or reflection access bypasses. Instantiate
 * and poll on the dedicated worker, never from the settings/probe or UI thread. */
internal class BydExperimentalTurnSignalSource(
    context: Context,
    private val profile: TurnCameraExperimentSettings.Profile,
    private val clock: () -> Long,
    load: (String) -> Class<*> = { Class.forName(it, false, BydExperimentalTurnSignalSource::class.java.classLoader) },
) : AutoCloseable {
    data class Reading(val signal: TurnSignalSample, val native: NativeCameraState)
    private class Getter(val instance: Any, val method: Method) { fun read() = method.invoke(instance) as Int }
    private val getters: List<Getter>
    private var closed = false
    init {
        fun device(name: String, names: List<String>): List<Getter> {
            val type = load(name)
            val methods = names.map { type.getMethod(it).also { m ->
                check(m.returnType == Integer.TYPE && !Modifier.isStatic(m.modifiers))
            } }
            val factory = type.getMethod("getInstance", Context::class.java)
            check(Modifier.isStatic(factory.modifiers))
            val instance = checkNotNull(factory.invoke(null, context.applicationContext))
            return methods.map { Getter(instance, it) }
        }
        require(TurnCameraExperimentSettings.validOemGetter(profile.oemClass, profile.oemGetter))
        getters = device("android.hardware.bydauto.light.BYDAutoLightDevice",
            listOf("getTurnLightState", "getDoubleFlashLightState", "getReversingLightState")) +
            device(profile.oemClass, listOf(profile.oemGetter))
    }
    fun read(): Reading {
        val startedAt = clock()
        if (closed) return unknown(startedAt)
        return try {
            val (turn, hazard, reverse, oem) = getters.map { it.read() }
            val state = when {
                hazard == profile.hazardOn -> TurnSignalState.HAZARD
                hazard != profile.hazardOff -> TurnSignalState.UNKNOWN
                turn == profile.turnLeft -> TurnSignalState.LEFT
                turn == profile.turnRight -> TurnSignalState.RIGHT
                turn == profile.turnOff -> TurnSignalState.OFF
                else -> TurnSignalState.UNKNOWN
            }
            val native = when {
                reverse == profile.reverseOn || oem == profile.oemActive -> NativeCameraState.ACTIVE
                reverse == profile.reverseOff && oem == profile.oemIdle -> NativeCameraState.IDLE
                else -> NativeCameraState.UNKNOWN
            }
            // The timestamp is BEFORE reads, not receipt time after a stuck getter. A
            // slow reading must expire, never look fresh just because it finally returned.
            Reading(TurnSignalSample(state, TurnSignalSemantics.LATCHED_LEVER, startedAt), native)
        } catch (_: Exception) { unknown(startedAt) }
    }
    private fun unknown(at: Long) = Reading(TurnSignalSample(TurnSignalState.UNKNOWN, TurnSignalSemantics.UNKNOWN, at), NativeCameraState.UNKNOWN)
    override fun close() { closed = true } // Singleton vehicle devices are NOT ours to close.
}
