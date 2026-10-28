package com.shilapi.xcertplay.network

import java.io.FileDescriptor
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TcpLivenessTest {
    @Test
    fun `Android 9 control socket gets peer health tuning`() {
        val lines = mutableListOf<String>()

        TcpLiveness.configure(AndroidSocket()) { lines += it }

        assertEquals(
            listOf("TCP peer health enabled idle=10s interval=3s count=3 deadline=20000ms; no video-idle timeout"),
            lines,
        )
    }

    /** Android's java.net.Socket exposes its own descriptor this way; the JVM's Socket does not. */
    private class AndroidSocket : Socket() {
        @Suppress("unused")
        fun `getFileDescriptor$`(): FileDescriptor = FileDescriptor()
    }
}
