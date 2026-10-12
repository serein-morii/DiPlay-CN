package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayVideoLayout
import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoSurfaceTest {
    @Test fun windowCapabilityKeepsTexturesAndSelectsFallbackWithoutHardwareAcceleration() {
        assertEquals(CarPlayVideoSurfaceMode.TEXTURE, carPlayVideoSurfaceMode(true))
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(false))
    }

    @Test fun smoothVideoAlwaysSelectsTheSurfaceViewItsTimestampsNeed() {
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(true, smoothVideo = true))
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(false, smoothVideo = true))
    }

    @Test fun directVideoOutputSelectsTheSurfaceView() {
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(true, directVideoOutput = true))
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(false, directVideoOutput = true))
    }

    @Test fun theLastPictureStaysUntilAFrameIsRenderedOrItsTimeRunsOut() {
        // Still the count from before DiPlay left: nothing new on the screen yet.
        assertFalse(lastPictureDone(rendered = 5_000, baseline = 5_000, elapsedMillis = 400, maxMillis = 30_000))
        // A parked decoder rendered on: the new surface has a picture.
        assertTrue(lastPictureDone(rendered = 5_001, baseline = 5_000, elapsedMillis = 400, maxMillis = 30_000))
        // A decoder built anew counts from 0 and has rendered nothing yet, then its first frame.
        assertFalse(lastPictureDone(rendered = 0, baseline = 5_000, elapsedMillis = 400, maxMillis = 30_000))
        assertTrue(lastPictureDone(rendered = 1, baseline = 5_000, elapsedMillis = 400, maxMillis = 30_000))
        // No decoder at all (-1): only the time limit ends it.
        assertFalse(lastPictureDone(rendered = -1, baseline = -1, elapsedMillis = 400, maxMillis = 30_000))
        assertTrue(lastPictureDone(rendered = -1, baseline = -1, elapsedMillis = 30_001, maxMillis = 30_000))
    }

    @Test fun aBackgroundSessionIsAdoptedOnlyWithTheSamePacing() {
        // A Smooth video change whose reconnect stopped early leaves the old session running.
        assertFalse(backgroundSessionMatchesView(sinkPaces = false, viewSmoothVideo = true))
        assertFalse(backgroundSessionMatchesView(sinkPaces = true, viewSmoothVideo = false))
        assertTrue(backgroundSessionMatchesView(sinkPaces = true, viewSmoothVideo = true))
        assertTrue(backgroundSessionMatchesView(sinkPaces = false, viewSmoothVideo = false))
    }

    @Test fun smoothVideoDelayCoversThreeFramesPlusAMargin() {
        assertEquals(90, smoothVideoDelayMillis(60))
        assertEquals(140, smoothVideoDelayMillis(30))
        assertEquals(90, smoothVideoDelayMillis(120)) // clamped to the 60 fps the stream tops out at
        assertEquals(140, smoothVideoDelayMillis(0))
    }

    @Test fun replacingTextureDetachesBeforeReleaseAndDoesNotReleaseHolderSurface() {
        val texture = Any()
        val holder = Any()
        val events = mutableListOf<Pair<String, Any>>()
        val owner = CarPlayVideoSurfaceOwner<Any>(
            detach = { events.add("detach" to it) }, release = { events.add("release" to it) })
        owner.replace(texture, releaseOnDetach = true)
        owner.replace(holder, releaseOnDetach = false)
        assertEquals(listOf("detach" to texture, "release" to texture), events)
        owner.clear(texture) // The old TextureView may deliver destruction after replacement.
        assertSame(holder, owner.current)
        owner.clear(holder)
        owner.clear() // Activity destruction after surfaceDestroyed must be idempotent.
        assertNull(owner.current)
        assertEquals(listOf("detach" to texture, "release" to texture, "detach" to holder), events)
    }

    @Test fun recreatingHolderIgnoresStaleDestructionAndReusesTheSameSurface() {
        val old = Any()
        val recreated = Any()
        val detached = mutableListOf<Any>()
        val released = mutableListOf<Any>()
        val owner = CarPlayVideoSurfaceOwner<Any>(
            detach = { detached.add(it) }, release = { released.add(it) })
        owner.replace(old, releaseOnDetach = false)
        owner.replace(recreated, releaseOnDetach = false)
        owner.replace(recreated, releaseOnDetach = false)
        owner.clear(old)
        assertSame(recreated, owner.current)
        assertEquals(listOf(old), detached)
        assertTrue(released.isEmpty())
    }

    @Test fun fittedSurfaceAndTouchContentShareLetterboxBounds() {
        val content = CarPlayVideoLayout.fit(1920, 1080, 1280, 720 + 80)
        val bounds = CarPlaySurfaceBounds.from(content)
        assertEquals(CarPlaySurfaceBounds(0, 40, 1280, 720), bounds)
        assertFalse(content.contains(640f, 20f))
        assertTrue(content.contains(640f, 400f))
    }

    @Test fun croppingRetainsNegativeOriginAndFullCanvasSize() {
        // A selected CarPlay view area shows the center of a larger canvas. Clamping it to
        // the window would squash the video while touches still reference the full canvas.
        val content = CarPlayVideoLayout(-320f, -180f, 1920f, 1080f)
        assertEquals(CarPlaySurfaceBounds(-320, -180, 1920, 1080), CarPlaySurfaceBounds.from(content))
        val normalizedX = (0f - content.left) / content.width
        val normalizedY = (0f - content.top) / content.height
        assertEquals(1f / 6f, normalizedX, 0.00001f)
        assertEquals(1f / 6f, normalizedY, 0.00001f)
    }
}
