package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerFirstFrameTest {
    @Test fun wiredMainFrameReachesTheHostButRetiredSessionsDoNot() {
        val frames = mutableListOf<AirPlaySession>()
        val controller = controller(frames)
        val active = session()
        val stale = session()
        val listener = controller.javaClass.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
        controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, active)
        try {
            listener.onVideoFrameRendered(stale)
            assertTrue(frames.isEmpty())
            listener.onVideoFrameRendered(active)
            assertEquals(listOf(active), frames)
            active.close()
            listener.onVideoFrameRendered(active)
            assertEquals(listOf(active), frames)
        } finally {
            stale.close()
            active.close()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    private fun controller(frames: MutableList<AirPlaySession>) = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(),
        object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) { frames += session }
        },
        object : AirPlayMediaHandler {}, {},
    )

    private fun session() = AirPlaySession(
        Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
    )
}
