package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class BluetoothDiagnosticSnapshotTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
    }

    @Test
    fun capturesAdapterPresenceAndStateWhenEnabled() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull(adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter).setEnabled(true)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals(27, snap.apiLevel)
        assertTrue(snap.hasDefaultAdapterFallback)
        assertEquals("STATE_ON", snap.adapterStateName)
        assertTrue(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.READY, snap.classification)
    }

    @Test
    fun capturesDisabledStateWhenOff() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull(adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        shadowOf(adapter).setEnabled(false)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals("STATE_OFF", snap.adapterStateName)
        assertFalse(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.DISABLED, snap.classification)
    }

    @Test
    fun capturesTransitionalStateWhenTurningOn() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull(adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_TURNING_ON)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals("STATE_TURNING_ON", snap.adapterStateName)
        assertFalse(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.TURNING_ON, snap.classification)
    }

    @Test
    fun capturesAbsentStateWhenBluetoothUnsupported() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)

        val snap = BluetoothDiagnosticSnapshot.capture(context)
        assertEquals("ABSENT", snap.adapterStateName)
        assertFalse(snap.isEnabled)
        assertEquals(BluetoothDiagnosticSnapshot.Classification.ABSENT, snap.classification)
    }

    @Test
    fun reportFormattingContainsSnapshotHeader() {
        val report = BluetoothDiagnosticSnapshot.report(context)
        assertTrue(report.contains("--- Bluetooth Diagnostic Snapshot ---"))
        assertTrue(report.contains("Android API level: 27"))
    }
}
