package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBinder
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

/** Isolate service consent/lifecycle handling from native SurfaceControl rendering. */
@Implements(SurfaceControlViewHost::class)
class MapEmbedSurfaceControlHostShadow {
    @Implementation fun __constructor__(context: Context, display: Display, hostToken: IBinder) = Unit
    @Implementation fun setView(view: View, width: Int, height: Int) { lastView = view }
    companion object { var lastView: View? = null; var releases = 0 }
    @Implementation fun relayout(width: Int, height: Int) = Unit
    @Implementation fun getSurfacePackage(): SurfaceControlViewHost.SurfacePackage? = null
    @Implementation fun release() { releases++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [MapEmbedSurfaceControlHostShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MapEmbedServiceTest {
    @Test fun disablingSharingReleasesAlreadyAttachedMaps() {
        MapEmbedSurfaceControlHostShadow.releases = 0
        MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
        val controller = Robolectric.buildService(MapEmbedService::class.java).create()
        val service = controller.get()
        try {
            AirPlayPersistence.saveLauncherMapSharing(service, true)
            val replies = mutableListOf<Message>()
            val client = Messenger(Handler(Looper.getMainLooper()) { replies += Message.obtain(it); true })
            val endpoint = Messenger(service.onBind(Intent(MapEmbedService.ACTION)))
            endpoint.send(Message.obtain(null, MapEmbedService.MSG_ATTACH).apply {
                replyTo = client
                data = Bundle().apply {
                    putBinder(MapEmbedService.KEY_HOST_TOKEN, Binder())
                    putInt(MapEmbedService.KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                    putInt(MapEmbedService.KEY_WIDTH, 1200)
                    putInt(MapEmbedService.KEY_HEIGHT, 450)
                }
            })
            shadowOf(Looper.getMainLooper()).idle()
            val embeds = service.javaClass.getDeclaredField("embeds").apply { isAccessible = true }
                .get(service) as Map<*, *>
            assertEquals("Control: a launcher map was attached", 1, embeds.size)
            val root = MapEmbedSurfaceControlHostShadow.lastView as android.widget.FrameLayout
            val video = root.getChildAt(0) as android.view.TextureView
            video.surfaceTextureListener!!.onSurfaceTextureAvailable(android.graphics.SurfaceTexture(0), 1200, 450)
            assertTrue(MapMirrors.launcherShowsMap)
            video.layout(0, 0, 1200, 450)
            MapMirrors.streamAspect = MapMirrors.VIRTUAL_STREAM_ASPECT
            shadowOf(Looper.getMainLooper()).idle()
            val matrix = FloatArray(9)
            video.getTransform(android.graphics.Matrix()).getValues(matrix)
            assertEquals("An existing launcher surface must follow negotiated geometry", 1.5f,
                matrix[android.graphics.Matrix.MSCALE_Y], 0.001f)
            AirPlayPersistence.saveLauncherMapSharing(service, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Turning sharing off must release existing launcher maps", embeds.isEmpty())
            assertFalse(MapMirrors.launcherShowsMap)
            assertEquals(1, MapEmbedSurfaceControlHostShadow.releases)
            assertEquals(MapEmbedService.ERROR_DISABLED,
                replies.last { it.what == MapEmbedService.MSG_ERROR }.data.getString(MapEmbedService.KEY_ERROR))
            // A delayed surface callback from the revoked view cannot re-register a mirror.
            video.surfaceTextureListener!!.onSurfaceTextureAvailable(android.graphics.SurfaceTexture(0), 1200, 450)
            assertFalse(MapMirrors.launcherShowsMap)
            // Fresh requests stay refused while consent is off.
            endpoint.send(Message.obtain(null, MapEmbedService.MSG_ATTACH).apply { replyTo = client })
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(embeds.isEmpty())
            assertEquals(MapEmbedService.ERROR_DISABLED, replies.last().data.getString(MapEmbedService.KEY_ERROR))
            // Re-enabling does not silently reconnect the old view.
            AirPlayPersistence.saveLauncherMapSharing(service, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(embeds.isEmpty())
        } finally {
            controller.destroy()
            MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
        }
    }
    private class Launcher(val service: MapEmbedService) {
        val replies = mutableListOf<Message>()
        val endpoint = Messenger(service.onBind(Intent(MapEmbedService.ACTION)))
        val embeds = service.javaClass.getDeclaredField("embeds").apply { isAccessible = true }
            .get(service) as Map<*, *>

        fun attach(client: Messenger = Messenger(Handler(Looper.getMainLooper()) { replies += Message.obtain(it); true })) {
            endpoint.send(Message.obtain(null, MapEmbedService.MSG_ATTACH).apply {
                replyTo = client
                data = Bundle().apply {
                    putBinder(MapEmbedService.KEY_HOST_TOKEN, Binder())
                    putInt(MapEmbedService.KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                    putInt(MapEmbedService.KEY_WIDTH, 1200)
                    putInt(MapEmbedService.KEY_HEIGHT, 450)
                }
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun withService(test: (Launcher) -> Unit) {
        val controller = Robolectric.buildService(MapEmbedService::class.java).create()
        try {
            AirPlayPersistence.saveLauncherMapSharing(controller.get(), true)
            test(Launcher(controller.get()))
        } finally {
            controller.destroy()
            ShadowBinder.reset()
        }
    }

    @Test fun oneAppCannotHoldManyMaps() = withService { launcher ->
        repeat(40) { launcher.attach() }
        assertEquals("One app holds ${launcher.embeds.size} decoders", 4, launcher.embeds.size)
        assertEquals(MapEmbedService.MSG_ERROR, launcher.replies.last().what)
    }

    @Test fun manyAppsCannotHoldManyMapsTogether() = withService { launcher ->
        repeat(40) { index ->
            ShadowBinder.setCallingUid(10_000 + index)
            launcher.attach()
        }
        assertEquals("All apps hold ${launcher.embeds.size} decoders", 8, launcher.embeds.size)
    }

    @Test fun aLauncherStillGetsItsMapAndCanReplaceOrFreeIt() = withService { launcher ->
        val client = Messenger(Handler(Looper.getMainLooper()) { launcher.replies += Message.obtain(it); true })
        launcher.attach(client)
        launcher.attach(client)
        assertEquals(1, launcher.embeds.size)
        assertEquals(MapEmbedService.MSG_ATTACHED, launcher.replies.last().what)
        launcher.attach()
        assertEquals(2, launcher.embeds.size)
        launcher.endpoint.send(Message.obtain(null, MapEmbedService.MSG_DETACH).apply { replyTo = client })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, launcher.embeds.size)
    }
}
