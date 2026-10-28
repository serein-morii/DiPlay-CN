package com.shilapi.xcertplay.turncamera

import android.app.Activity
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterTurnCameraLayerTest {
    @Test fun shutterHidesPixelsWithoutSuppressingTextureUpdatesAndPlacementStaysInsideSafeArea() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(host.get())
            host.get().setContentView(root)
            val layer = ClusterTurnCameraLayer(root, { RectF(0f, 0f, 1000f, 500f) },
                listOf(.1001f, .1001f, .8999f, .8999f)) { fail("confirmed disposal must not quarantine") }
            root.addView(layer, FrameLayout.LayoutParams(-1, -1))
            root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1000, 500)
            val lease = CameraLease(1, 1)
            val calibration = TurnCameraCalibration(windowX = 1f, windowY = 1f, windowWidth = 1f, windowHeight = 1f)
            val route = TurnCameraRoute(7, 0, 640, 480, calibration, TurnCameraFrameTimebase.MONOTONIC_NANOS)
            var lost = false
            layer.prepare(lease, route, {}, {}, { lost = true })
            assertFalse(lost)
            val box = layer.getChildAt(0) as FrameLayout
            val shutter = box.getChildAt(1)
            assertEquals(1f, box.alpha)
            assertEquals(View.VISIBLE, shutter.visibility)
            assertNull(layer.occupiedBounds())
            layer.show(TurnCameraSide.LEFT, lease)
            assertEquals(View.GONE, shutter.visibility)
            assertEquals(RectF(101f, 51f, 899f, 449f), layer.occupiedBounds())
            layer.hide(lease)
            assertEquals(View.VISIBLE, shutter.visibility)
            assertEquals(1f, box.alpha)
            assertNull(layer.occupiedBounds())
            layer.dispose(lease, true)
            assertEquals(0, layer.childCount)
        } finally { host.pause().stop().destroy() }
    }
}
