package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
/**
 * Diagnostic snapshot for Bluetooth state using public Android APIs.
 *
 * Privacy guarantee: Never records MAC addresses, PINs, pairing secrets, or device names.
 */
object BluetoothDiagnosticSnapshot {

    enum class Classification {
        ABSENT,
        TURNING_ON,
        TURNING_OFF,
        DISABLED,
        PERMISSION_ERROR,
        READY,
    }

    data class Snapshot(
        val apiLevel: Int,
        val hasBluetoothManager: Boolean,
        val hasManagerAdapter: Boolean,
        val hasDefaultAdapterFallback: Boolean,
        val adapterStateName: String,
        val adapterStateCode: Int?,
        val isEnabled: Boolean,
        val connectPermissionStatus: String,
        val classification: Classification,
    )

    fun capture(context: Context): Snapshot {
        val apiLevel = Build.VERSION.SDK_INT
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val hasBluetoothManager = manager != null
        val managerAdapter = runCatching { manager?.adapter }.getOrNull()
        val hasManagerAdapter = managerAdapter != null

        val defaultAdapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val hasDefaultAdapterFallback = defaultAdapter != null

        val effectiveAdapter = managerAdapter ?: defaultAdapter

        val stateCode = runCatching { effectiveAdapter?.state }.getOrNull()
        val stateName = when (stateCode) {
            BluetoothAdapter.STATE_OFF -> "STATE_OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
            BluetoothAdapter.STATE_ON -> "STATE_ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
            null -> if (effectiveAdapter == null) "ABSENT" else "UNAVAILABLE"
            else -> "UNKNOWN($stateCode)"
        }

        val isEnabled = runCatching { effectiveAdapter?.isEnabled == true }.getOrDefault(false)

        val connectPermissionStatus = if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "GRANTED"
            } else {
                "DENIED"
            }
        } else {
            "NOT_REQUIRED(API $apiLevel)"
        }

        val classification = when {
            effectiveAdapter == null -> Classification.ABSENT
            connectPermissionStatus == "DENIED" -> Classification.PERMISSION_ERROR
            stateCode == BluetoothAdapter.STATE_TURNING_ON -> Classification.TURNING_ON
            stateCode == BluetoothAdapter.STATE_TURNING_OFF -> Classification.TURNING_OFF
            !isEnabled -> Classification.DISABLED
            else -> Classification.READY
        }

        return Snapshot(
            apiLevel = apiLevel,
            hasBluetoothManager = hasBluetoothManager,
            hasManagerAdapter = hasManagerAdapter,
            hasDefaultAdapterFallback = hasDefaultAdapterFallback,
            adapterStateName = stateName,
            adapterStateCode = stateCode,
            isEnabled = isEnabled,
            connectPermissionStatus = connectPermissionStatus,
            classification = classification,
        )
    }

    fun report(context: Context): String {
        val snap = capture(context)

        val presenceDetail = if (snap.classification == Classification.ABSENT) {
            "ABSENT (Standard Bluetooth adapter is unavailable; separate OEM Bluetooth module is a possibility requiring device evidence)"
        } else {
            "PRESENT"
        }

        return buildString {
            appendLine("--- Bluetooth Diagnostic Snapshot ---")
            appendLine("Android API level: ${snap.apiLevel}")
            appendLine("BluetoothManager service: ${if (snap.hasBluetoothManager) "exists" else "null"}")
            appendLine("BluetoothManager adapter: ${if (snap.hasManagerAdapter) "exists" else "null"}")
            appendLine("BluetoothAdapter.getDefaultAdapter(): ${if (snap.hasDefaultAdapterFallback) "exists" else "null"}")
            appendLine("Adapter presence: $presenceDetail")
            appendLine("Adapter state: ${snap.adapterStateName}")
            appendLine("isEnabled: ${snap.isEnabled}")
            appendLine("BLUETOOTH_CONNECT permission: ${snap.connectPermissionStatus}")
            appendLine("Classification: ${snap.classification}")
        }
    }
}
