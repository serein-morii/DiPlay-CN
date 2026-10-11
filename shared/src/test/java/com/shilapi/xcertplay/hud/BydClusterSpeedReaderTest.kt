package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class BydClusterSpeedReaderTest {
    @Test fun convertsSpeedAndRejectsMissingOrInvalidReadings() {
        assertEquals(0, BydClusterSpeedReader.kmh(0.0))
        assertEquals(72, BydClusterSpeedReader.kmh(20.0))
        assertNull(BydClusterSpeedReader.kmh(null))
        assertNull(BydClusterSpeedReader.kmh(Double.NaN))
        assertNull(BydClusterSpeedReader.kmh(Double.POSITIVE_INFINITY))
        assertNull(BydClusterSpeedReader.kmh(-1.0))
        assertNull(BydClusterSpeedReader.kmh(100.0))
    }
}
