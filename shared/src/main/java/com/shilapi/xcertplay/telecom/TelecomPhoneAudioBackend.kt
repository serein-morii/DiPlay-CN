package com.shilapi.xcertplay.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "DiPlay-PhoneAudio"
private const val ACCOUNT_ID = "diplay-car-phone-audio"
private const val ACCOUNT_LABEL = "DiPlay call audio"
private const val ADDRESS = "carplay@diplay"
private const val START_TIMEOUT_MILLIS = 3_000L

/** Places the self-managed call through Telecom and waits until the system has accepted it. */
internal class TelecomPhoneAudioBackend(private val context: Context) : PhoneAudioBackend {
    override fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "self-managed calls need Android 8 or newer")
            return false
        }
        return place()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun place(): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        val handle = PhoneAccountHandle(ComponentName(context, PhoneAudioConnectionService::class.java), ACCOUNT_ID)
        return try {
            telecom.registerPhoneAccount(
                PhoneAccount.builder(handle, ACCOUNT_LABEL)
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                    .build(),
            )
            val created = PhoneAudioCall.expect()
            telecom.placeCall(
                Uri.fromParts(PhoneAccount.SCHEME_SIP, ADDRESS, null),
                Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) },
            )
            created.await(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && PhoneAudioCall.isActive()
        } catch (error: SecurityException) {
            Log.w(TAG, "Telecom call not allowed", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Telecom call failed", error)
            false
        }
    }

    override fun stop() = PhoneAudioCall.end()

    override fun isActive(): Boolean = PhoneAudioCall.isActive()
}

/** The one Telecom connection DiPlay keeps open, shared between the backend and [PhoneAudioConnectionService]. */
internal object PhoneAudioCall {
    private val lock = Any()
    private var created: CountDownLatch? = null
    private var connection: Connection? = null

    fun expect(): CountDownLatch = synchronized(lock) {
        CountDownLatch(1).also { created = it }
    }

    fun isActive(): Boolean = synchronized(lock) { connection != null }

    @RequiresApi(Build.VERSION_CODES.O)
    fun create(): Connection = PhoneAudioConnection().also {
        synchronized(lock) {
            connection = it
            created?.countDown()
        }
        Log.i(TAG, "Telecom call active")
    }

    fun failed() {
        Log.w(TAG, "Telecom did not create the call")
        synchronized(lock) { created?.countDown() }
    }

    fun released(owner: Connection) {
        synchronized(lock) { if (connection === owner) connection = null }
    }

    fun end() {
        val current = synchronized(lock) { connection.also { connection = null } } ?: return
        current.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        current.destroy()
        Log.i(TAG, "Telecom call ended")
    }
}

/**
 * A self-managed call carries no UI and no audio of its own: Telecom only learns that a call is on, which is
 * what moves the car's audio into its phone state.
 */
@RequiresApi(Build.VERSION_CODES.O)
class PhoneAudioConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection = PhoneAudioCall.create()

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection = PhoneAudioCall.create()

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) = PhoneAudioCall.failed()

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) = PhoneAudioCall.failed()
}

@RequiresApi(Build.VERSION_CODES.O)
private class PhoneAudioConnection : Connection() {
    init {
        setConnectionProperties(PROPERTY_SELF_MANAGED)
        setAudioModeIsVoip(true)
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_SIP, ADDRESS, null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(ACCOUNT_LABEL, TelecomManager.PRESENTATION_ALLOWED)
        setActive()
    }

    override fun onDisconnect() {
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        PhoneAudioCall.released(this)
    }

    override fun onAbort() = onDisconnect()

    override fun onReject() = onDisconnect()
}
