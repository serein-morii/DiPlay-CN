package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The touch executor runs one bplist encode, one AEAD seal and one socket write per queued report. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerTouchQueueTest {
    /** Counts writes; the first one blocks until [release] when [stall] is set. */
    private class Wire(stall: Boolean) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        val started = CountDownLatch(1)
        private val release = CountDownLatch(if (stall) 1 else 0)
        @Volatile var writes = 0
        fun release() = release.countDown()
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) {
            started.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "test never released the stalled write" }
            synchronized(bytes) { bytes.write(b, off, len) }
            writes++
        }
    }

    private class Rig(stall: Boolean) {
        val wire = Wire(stall)
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
            ),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
        )
        val session = AirPlaySession(
            Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
        )

        init {
            val socket = object : Socket() { override fun getOutputStream() = wire }
            field(session, "eventSocket", socket)
            field(session, "eventCipher", ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
            field(controller, "activeSession", session)
        }

        fun close() {
            wire.release()
            session.close()
            controller.close()
            controller.awaitClosed(2_000)
        }

        /** Reports in send order, one string per report: the slots that are down or have a position. */
        fun reports(): List<String> {
            val decoder = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 })
            val plain = decoder.decrypt(synchronized(wire.bytes) { wire.bytes.toByteArray() }).data
            return RtspMessage.parseMessages(plain).messages.map { request ->
                val report = (BplistCodec.decode(request.body) as Map<*, *>)["hidReport"] as ByteArray
                (0 until AirPlayHid.TOUCH_CONTACTS).mapNotNull { slot ->
                    val o = slot * 6
                    val x = (report[o + 2].toInt() and 0xff) or ((report[o + 3].toInt() and 0xff) shl 8)
                    val y = (report[o + 4].toInt() and 0xff) or ((report[o + 5].toInt() and 0xff) shl 8)
                    if (report[o + 1].toInt() == 0 && x == 0 && y == 0) null else "$slot:${report[o + 1]}@$x,$y"
                }.joinToString(" ")
            }
        }

        fun awaitWrites(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (wire.writes < count && System.nanoTime() < deadline) Thread.sleep(5)
        }

        fun settle(): Int {
            var last = -1
            while (last != wire.writes) { last = wire.writes; Thread.sleep(200) }
            return last
        }

        private fun field(target: Any, name: String, value: Any) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }

    private fun finger(id: Int, step: Int, down: Boolean = true) =
        AirPlayContact(id, 0.1 + step * 0.001, 0.2 + id * 0.1, down)

    private fun px(contact: AirPlayContact) = "${Math.round(contact.x * 800).toInt()},${Math.round(contact.y * 480).toInt()}"

    @Test fun stalledSocketDoesNotQueueEveryMove() {
        val rig = Rig(stall = true)
        try {
            rig.controller.sendTouch(listOf(finger(7, 0)))
            assertTrue(rig.wire.started.await(20, TimeUnit.SECONDS))
            repeat(MOVES) { rig.controller.sendTouch(listOf(finger(7, it + 1))) }
            rig.wire.release()
            val executed = rig.settle()
            println("MEASURED reports executed after ${MOVES + 1} events behind a stalled write: $executed")
            assertTrue("$executed touch reports ran after a stall of ${MOVES + 1} events", executed <= 3)
        } finally {
            rig.close()
        }
    }

    @Test fun finalPositionIsAlwaysDelivered() {
        val rig = Rig(stall = true)
        try {
            rig.controller.sendTouch(listOf(finger(7, 0)))
            assertTrue(rig.wire.started.await(20, TimeUnit.SECONDS))
            repeat(MOVES) { rig.controller.sendTouch(listOf(finger(7, it + 1))) }
            rig.wire.release()
            rig.settle()
            val reports = rig.reports()
            assertEquals("0:1@${px(finger(7, 0))}", reports.first())
            assertEquals("0:1@${px(finger(7, MOVES))}", reports.last())
        } finally {
            rig.close()
        }
    }

    @Test fun downUpAndSecondFingerKeepTheirOrderWhileMovesCollapse() {
        val rig = Rig(stall = true)
        try {
            val first = listOf(finger(7, 0))
            rig.controller.sendTouch(first)
            assertTrue(rig.wire.started.await(20, TimeUnit.SECONDS))
            repeat(20) { rig.controller.sendTouch(listOf(finger(7, 1 + it))) }
            val twoDown = listOf(finger(7, 21), finger(3, 21))
            rig.controller.sendTouch(twoDown)
            repeat(20) { rig.controller.sendTouch(listOf(finger(7, 22 + it), finger(3, 22 + it))) }
            val secondUp = listOf(finger(7, 42), finger(3, 42, down = false))
            rig.controller.sendTouch(secondUp)
            repeat(20) { rig.controller.sendTouch(listOf(finger(7, 43 + it))) }
            val allUp = listOf(finger(7, 63, down = false))
            rig.controller.sendTouch(allUp)
            rig.wire.release()
            rig.settle()
            assertEquals(
                listOf(
                    "0:1@${px(finger(7, 0))}",
                    "0:1@${px(finger(7, 20))}",
                    "0:1@${px(finger(7, 21))} 1:1@${px(finger(3, 21))}",
                    "0:1@${px(finger(7, 41))} 1:1@${px(finger(3, 41))}",
                    "0:1@${px(finger(7, 42))} 1:0@${px(finger(3, 42))}",
                    "0:1@${px(finger(7, 62))}",
                    "0:0@${px(finger(7, 63))}",
                ),
                rig.reports(),
            )
        } finally {
            rig.close()
        }
    }

    @Test fun everyDownAndUpOfAStalledBurstOfTapsIsDelivered() {
        val rig = Rig(stall = true)
        try {
            repeat(TAPS) {
                rig.controller.sendTouch(listOf(finger(7, it)))
                rig.controller.sendTouch(listOf(finger(7, it, down = false)))
            }
            rig.wire.release()
            rig.awaitWrites(TAPS * 2)
            assertEquals(TAPS * 2, rig.settle())
        } finally {
            rig.close()
        }
    }

    @Test fun movesAreNotDroppedWhenTheSocketKeepsUp() {
        val rig = Rig(stall = false)
        try {
            val events = listOf(finger(7, 0)) + (1..30).map { finger(7, it) } + finger(7, 31, down = false)
            events.forEachIndexed { index, contact ->
                rig.controller.sendTouch(listOf(contact))
                rig.awaitWrites(index + 1)
            }
            assertEquals(events.size, rig.settle())
            assertEquals(events.map { (if (it.down) "0:1@" else "0:0@") + px(it) }, rig.reports())
        } finally {
            rig.close()
        }
    }

    private companion object {
        const val MOVES = 500
        const val TAPS = 40
    }

    @Test fun movesAreRefusedWhenTheExecutorCannotRunThem() {
        val rig = Rig(stall = false)
        try {
            assertTrue(rig.controller.sendTouch(listOf(finger(7, 0))))
            rig.awaitWrites(1)
            rig.controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
                .get(rig.controller).let { (it as java.util.concurrent.ExecutorService).shutdownNow() }
            assertFalse("first move reported as sent", rig.controller.sendTouch(listOf(finger(7, 1))))
            assertFalse("later move merged into a move that never ran", rig.controller.sendTouch(listOf(finger(7, 2))))
        } finally {
            rig.close()
        }
    }
}
