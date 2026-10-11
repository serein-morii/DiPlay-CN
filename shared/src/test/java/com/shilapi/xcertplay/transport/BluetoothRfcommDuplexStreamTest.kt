package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class BluetoothRfcommDuplexStreamTest {
    @Test fun bytesReceivedStaysZeroUntilThePeerDeliversSomething() {
        val silent = BluetoothRfcommDuplexStream(FakeSocket(BlockingInput()))
        assertNull(silent.recv(16, 50))
        assertEquals(0L, silent.bytesReceived)
        silent.close()

        val answering = BluetoothRfcommDuplexStream(FakeSocket(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertArrayEquals(byteArrayOf(1, 2, 3), answering.recv(16, 2_000))
        assertEquals(3L, answering.bytesReceived)
        answering.close()
    }

    @Test fun missingInputFailsSynchronouslyWithoutReadingOrOpeningOutput() {
        val lines = CopyOnWriteArrayList<String>()
        val socket = FakeSocket(null)

        val failure = expectFailure { BluetoothRfcommDuplexStream(socket, lines::add) }

        assertEquals(BluetoothRfcommStreamException.Operation.INPUT_STREAM, failure.operation)
        assertEquals(BluetoothRfcommStreamException.Reason.STREAM_UNAVAILABLE, failure.reason)
        assertEquals(0, socket.outputCalls.get())
        assertEquals(1, socket.closes.get())
        assertFalse(lines.any { it.contains("streams ready") })
        assertTrue(lines.single().contains("operation=INPUT_STREAM reason=STREAM_UNAVAILABLE"))
    }

    @Test fun missingOutputClosesSocketBeforeAnyReaderStarts() {
        val input = BlockingInput()
        val socket = FakeSocket(input, null)

        val failure = expectFailure { BluetoothRfcommDuplexStream(socket) }

        assertEquals(BluetoothRfcommStreamException.Operation.OUTPUT_STREAM, failure.operation)
        assertEquals(1, socket.closes.get())
        assertEquals(0, input.reads.get())
    }

    @Test fun platformGetterFailurePreservesCauseButExcludesItsPrivateMessageFromDiagnostics() {
        val privateFailure = IOException("private phone name / pairing data")
        val lines = CopyOnWriteArrayList<String>()
        val socket = FakeSocket(BlockingInput(), getterFailure = privateFailure)

        val failure = expectFailure { BluetoothRfcommDuplexStream(socket, lines::add) }

        assertSame(privateFailure, failure.cause)
        assertEquals(BluetoothRfcommStreamException.Reason.STREAM_ACCESS_FAILED, failure.reason)
        assertEquals(1, socket.closes.get())
        assertTrue(lines.single().contains("causeClass=IOException"))
        assertFalse(lines.joinToString().contains("private"))
        assertFalse(failure.message!!.contains("private"))
    }

    @Test fun readerIoFailureBeforeFirstByteIsExplicitAndSocketIsClosedOnce() {
        val original = IOException("private Bluetooth address")
        val lines = CopyOnWriteArrayList<String>()
        val socket = FakeSocket(FailingInput(original))
        val stream = BluetoothRfcommDuplexStream(socket, lines::add)

        val failure = expectFailure { stream.recv(32, 2_000) }
        stream.close()

        assertSame(original, failure.cause)
        assertEquals(BluetoothRfcommStreamException.Operation.READ, failure.operation)
        assertEquals(1, socket.closes.get())
        assertTrue(lines.any { it.contains("reader result=FAILED beforeFirstByte=true readCalls=1 receivedBytes=0") })
        assertTrue(lines.any { it.contains("operation=READ reason=READ_FAILED") })
        assertFalse(lines.joinToString().contains("private"))
    }

    @Test fun vendorReaderRuntimeFailureKeepsItsTypeWithoutLeakingItsMessage() {
        val original = NullPointerException("vendor stream payload / phone identifier")
        val lines = CopyOnWriteArrayList<String>()
        val socket = FakeSocket(FailingInput(original))
        val stream = BluetoothRfcommDuplexStream(socket, lines::add)

        val failure = expectFailure { stream.recv(32, 2_000) }
        stream.close()

        assertSame(original, failure.cause)
        assertEquals(1, socket.closes.get())
        assertTrue(lines.any { it.contains("causeClass=NullPointerException") })
        assertFalse(lines.joinToString().contains("phone identifier"))
    }

    @Test fun closingUnblocksReaderAndDoesNotMisclassifyCancellationAsFailure() {
        val input = BlockingInput()
        val socket = FakeSocket(input)
        val lines = CopyOnWriteArrayList<String>()
        val stream = BluetoothRfcommDuplexStream(socket, lines::add)
        assertTrue("reader did not enter its blocking input", input.entered.await(2, TimeUnit.SECONDS))

        stream.close()
        stream.close()

        assertEquals(1, socket.closes.get())
        assertArrayEquals(ByteArray(0), stream.recv(32, 0))
        assertFalse(lines.any { it.contains("FAILED") })
        assertFalse(lines.any { it.contains("result=ENDED") })
    }

    @Test fun earlyEofIsDistinguishedFromReadFailureAndTimeout() {
        val socket = FakeSocket(object : InputStream() { override fun read() = -1 })
        val lines = CopyOnWriteArrayList<String>()
        val stream = BluetoothRfcommDuplexStream(socket, lines::add)

        assertArrayEquals(ByteArray(0), stream.recv(32, 2_000))
        stream.close()

        assertEquals(1, socket.closes.get())
        assertTrue(lines.any { it.contains("reader result=ENDED beforeFirstByte=true") })
        assertFalse(lines.any { it.contains("FAILED") })
    }

    @Test fun outputFailurePreservesCauseAndReaderCancellationDoesNotReportAnotherFailure() {
        val input = BlockingInput()
        val original = IOException("private output failure")
        val socket = FakeSocket(input, object : OutputStream() {
            override fun write(value: Int) { throw original }
        })
        val lines = CopyOnWriteArrayList<String>()
        val stream = BluetoothRfcommDuplexStream(socket, lines::add)
        assertTrue(input.entered.await(2, TimeUnit.SECONDS))

        val failure = expectFailure { stream.send(byteArrayOf(1)) }
        stream.close()

        assertSame(original, failure.cause)
        assertEquals(BluetoothRfcommStreamException.Operation.WRITE, failure.operation)
        assertEquals(1, socket.closes.get())
        assertEquals(1, lines.count { it.contains("result=FAILED") })
        assertFalse(lines.joinToString().contains("private"))
    }

    @Test fun refusedFlushIoctlKeepsTheLinkAndStopsFlushing() {
        val input = BlockingInput()
        val written = ByteArrayOutputStream()
        var flushes = 0
        // Ford SYNC+ (#582): LocalSocket's flush polls TIOCOUTQ and SELinux refuses the ioctl.
        val socket = FakeSocket(input, object : OutputStream() {
            override fun write(value: Int) = written.write(value)
            override fun flush() {
                flushes++
                throw IOException("ioctl failed: EACCES (Permission denied)", IOException("ioctl failed: EACCES (Permission denied)"))
            }
        })
        val lines = CopyOnWriteArrayList<String>()
        val stream = BluetoothRfcommDuplexStream(socket, lines::add)
        assertTrue(input.entered.await(2, TimeUnit.SECONDS))

        stream.send(byteArrayOf(1, 2))
        stream.send(byteArrayOf(3))
        stream.close()

        assertArrayEquals(byteArrayOf(1, 2, 3), written.toByteArray())
        assertEquals(1, flushes)
        assertEquals(1, lines.count { it.contains("flush refused") })
        assertFalse(lines.any { it.contains("result=FAILED") })
    }

    @Test fun otherFlushFailuresStillEndTheLink() {
        val input = BlockingInput()
        val socket = FakeSocket(input, object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun flush() = throw IOException("write failed: EPIPE (Broken pipe)")
        })
        val stream = BluetoothRfcommDuplexStream(socket)
        assertTrue(input.entered.await(2, TimeUnit.SECONDS))

        val failure = expectFailure { stream.send(byteArrayOf(1)) }
        stream.close()

        assertEquals(BluetoothRfcommStreamException.Reason.WRITE_FAILED, failure.reason)
        assertFalse(refusedIoctl(IOException("write failed: EPIPE (Broken pipe)")))
        assertTrue(refusedIoctl(IOException("x", IOException("ioctl failed: EPERM (Operation not permitted)"))))
    }

    @Test fun failingDiagnosticCallbackCannotPreventGetterFailureCleanup() {
        val socket = FakeSocket(null)

        expectFailure { BluetoothRfcommDuplexStream(socket) { throw IllegalStateException("diagnostic callback") } }

        assertEquals(1, socket.closes.get())
    }

    @Test fun closingDuringBlockedWriteExitsSenderWithoutArtificialTransportFailure() {
        assertSenderCancellation(blockFlush = false)
    }

    @Test fun closingDuringBlockedFlushExitsSenderWithoutArtificialTransportFailure() {
        assertSenderCancellation(blockFlush = true)
    }

    private fun assertSenderCancellation(blockFlush: Boolean) {
        val input = BlockingInput()
        val output = BlockingOutput(blockFlush)
        val socket = FakeSocket(input, output)
        val lines = CopyOnWriteArrayList<String>()
        val stream = BluetoothRfcommDuplexStream(socket, lines::add)
        val result = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val sender = Thread {
            try { stream.send(byteArrayOf(1, 2, 3)) }
            catch (failure: Throwable) { result.set(failure) }
            finally { completed.countDown() }
        }
        try {
            assertTrue(input.entered.await(2, TimeUnit.SECONDS))
            sender.start()
            assertTrue("sender did not enter blocking output", output.entered.await(2, TimeUnit.SECONDS))

            stream.close()

            assertTrue("socket close did not unblock sender", completed.await(2, TimeUnit.SECONDS))
            assertTrue(result.get() is IOException)
            assertFalse(result.get() is BluetoothRfcommStreamException)
            assertEquals("Bluetooth RFCOMM stream is closed", result.get()!!.message)
            assertEquals(1, socket.closes.get())
            assertFalse(lines.any { it.contains("FAILED") })
        } finally {
            stream.close()
            sender.join(2_000)
        }
    }

    @Test fun drainingAFullQueueResumesTheBlockedReaderWithoutLosingBytes() {
        val payload = ByteArray(65_536 + 123) { (it % 251).toByte() }
        val input = QueueFillingInput(payload)
        val stream = BluetoothRfcommDuplexStream(FakeSocket(input))
        try {
            awaitFullQueue(stream)
            assertEquals("reader exceeded the bounded queue", 1L, input.ninthRead.count)

            val received = ByteArrayOutputStream()
            // Drain only part of the first chunk: the reader must wake even when its tail remains.
            received.write(stream.recv(64, 2_000)!!)
            assertTrue("reader remained blocked after recv freed capacity", input.ninthRead.await(2, TimeUnit.SECONDS))
            while (true) {
                val chunk = stream.recv(8_192, 2_000)
                assertNotNull("queued RFCOMM bytes were stalled", chunk)
                if (chunk!!.isEmpty()) break
                received.write(chunk)
            }
            assertArrayEquals(payload, received.toByteArray())
        } finally {
            stream.close()
        }
    }

    private fun awaitFullQueue(stream: BluetoothRfcommDuplexStream) {
        val type = stream.javaClass
        val lock = type.getDeclaredField("lock").apply { isAccessible = true }.get(stream) as Object
        val pending = type.getDeclaredField("pendingBytes").apply { isAccessible = true }
        val reader = type.getDeclaredField("reader").apply { isAccessible = true }.get(stream) as Thread
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        synchronized(lock) {
            while (pending.getInt(stream) != 65_536 || reader.state != Thread.State.WAITING) {
                val remaining = deadline - System.nanoTime()
                assertTrue("reader did not enqueue the full buffer and wait for capacity", remaining > 0)
                val waitNanos = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10))
                lock.wait(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
            }
        }
    }

    private fun expectFailure(action: () -> Any?): BluetoothRfcommStreamException {
        try {
            action()
            fail("Expected a classified RFCOMM stream failure")
        } catch (failure: BluetoothRfcommStreamException) {
            return failure
        }
        throw AssertionError("unreachable")
    }

    private class FakeSocket(
        private val input: InputStream?,
        private val output: OutputStream? = ByteArrayOutputStream(),
        private val getterFailure: IOException? = null,
    ) : BluetoothRfcommSocketAccess {
        val closes = AtomicInteger()
        val outputCalls = AtomicInteger()
        override fun inputStream(): InputStream? {
            getterFailure?.let { throw it }
            return input
        }
        override fun outputStream(): OutputStream? {
            outputCalls.incrementAndGet()
            return output
        }
        override fun close() {
            closes.incrementAndGet()
            try { input?.close() } finally { output?.close() }
        }
    }

    private class FailingInput(private val failure: Exception) : InputStream() {
        override fun read(): Int = throw failure
    }

    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1)
        val reads = AtomicInteger()
        private val released = CountDownLatch(1)
        override fun read(): Int {
            reads.incrementAndGet()
            entered.countDown()
            released.await()
            throw IOException("closed by owner")
        }
        override fun close() { released.countDown() }
    }

    private class QueueFillingInput(payload: ByteArray) : InputStream() {
        private val input = ByteArrayInputStream(payload)
        private var calls = 0
        val ninthRead = CountDownLatch(1)
        override fun read() = input.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            if (calls == 9) ninthRead.countDown()
            return input.read(buffer, offset, length)
        }
    }

    private class BlockingOutput(private val blockFlush: Boolean) : OutputStream() {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override fun write(value: Int) { if (!blockFlush) block() }
        override fun flush() { if (blockFlush) block() }
        private fun block() {
            entered.countDown()
            released.await()
            throw IOException("output closed by owner")
        }
        override fun close() { released.countDown() }
    }
}
