package com.shilapi.xcertplay.hud

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Writes integer vehicle properties through the FCE misc service (`fce_misc_service_hal_property`,
 * `com.fce.misc.proxy.hardware.property.ICarProperty`), the same Binder the stock dual-screen
 * service uses. The service does not check the caller, so an ordinary app can use it without root
 * or ADB.
 *
 * Wire format copied from the stock proxy: `setProperty` is transaction 5 and takes a
 * `CarPropertyValue` written as propertyId, areaId, status, timestamp, value class name, value.
 */
internal class FceCarPropertyWriter : FcePropertyWriter {
    private var binder: IBinder? = null

    override fun setInt(propertyId: Int, value: Int): Boolean {
        val remote = binder ?: lookup()?.also { binder = it } ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1) // non-null CarPropertyValue
            data.writeInt(propertyId)
            data.writeInt(AREA_GLOBAL)
            data.writeInt(STATUS_AVAILABLE)
            data.writeLong(0L)
            data.writeString(Integer::class.java.name)
            data.writeValue(Integer.valueOf(value))
            remote.transact(TRANSACTION_SET_PROPERTY, data, reply, 0)
            reply.readException()
            true
        } catch (_: DeadObjectException) {
            Log.w(TAG, "property service died, will look it up again")
            binder = null
            false
        } catch (e: Exception) {
            Log.w(TAG, "setProperty 0x${Integer.toHexString(propertyId)} failed", e)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun lookup(): IBinder? = serviceManager("getService")
        .also { if (it == null) Log.w(TAG, "$SERVICE_NAME not available on this head unit") }

    companion object {
        private const val TAG = "DiPlay-FCE-Cluster"
        private const val SERVICE_NAME = "fce_misc_service_hal_property"
        private const val DESCRIPTOR = "com.fce.misc.proxy.hardware.property.ICarProperty"
        private const val TRANSACTION_SET_PROPERTY = 5
        private const val AREA_GLOBAL = 0
        private const val STATUS_AVAILABLE = 0

        /** True when the head unit offers the service. */
        fun isAvailable(): Boolean = serviceManager("checkService") != null

        private fun serviceManager(method: String): IBinder? = try {
            Class.forName("android.os.ServiceManager")
                .getMethod(method, String::class.java)
                .invoke(null, SERVICE_NAME) as IBinder?
        } catch (_: Throwable) {
            null
        }
    }
}
