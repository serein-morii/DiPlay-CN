package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.OpusEncoderSupport
import com.shilapi.xcertplay.media.SoftwareOpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayInfoPlistTest {
    @Test
    fun bothDisplaysDeclareAutomaticAppearanceAtConnection() {
        val config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            cluster = AirPlayDisplayConfig(widthPixels = 960, heightPixels = 360),
        )
        val displays = AirPlayInfoPlist.build(config)["displays"] as List<*>
        assertEquals(2, displays.size)
        for (display in displays) {
            val fields = display as Map<*, *>
            for (key in listOf("uiAppearanceMode", "uiAppearanceSetting", "mapAppearanceMode", "mapAppearanceSetting")) {
                assertEquals(key, 0, fields[key])
            }
        }
    }

    @Test
    fun defaultDisplayIncludesFullViewAndSafeAreas() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(0, display["initialViewArea"])
        assertFalse(display.containsKey("cornerMasks"))
        assertEquals(1280, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        assertEquals(0, view["originXPixels"])
        assertEquals(0, view["originYPixels"])
        assertEquals(1, display["primaryInputDevice"])
        assertNotNull(safe)
        assertEquals(1280, safe["widthPixels"])
        assertEquals(720, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun hevcCapabilityIsAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        assertFalse(AirPlayInfoPlist.build(base).containsKey("hevcInfo"))
        assertTrue(AirPlayInfoPlist.build(base.copy(hevc = true)).containsKey("hevcInfo"))
    }

    @Test
    fun drivingSideAndDisplayValuesAreAdvertised() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 1280,
                    heightPixels = 720,
                    fps = 37,
                    widthPhysicalMm = 125,
                ),
                rightHandDrive = true,
                manufacturer = "Example",
                model = "HeadUnit",
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["rightHandDrive"])
        assertEquals("Example", info["manufacturer"])
        assertEquals("HeadUnit", info["model"])
        assertEquals(35, display["maxFPS"])
        assertEquals(125, display["widthPhysical"])
        assertEquals(70, display["heightPhysical"])
    }

    @Test
    fun squareOemIconIsAdvertisedWithItsOriginalBytes() {
        val iconBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                icons = listOf(AirPlayIcon(1, 1, iconBytes)),
                oemLabel = "xcertplay",
            ),
        )

        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["oemIconVisible"])
        assertEquals("xcertplay", info["oemIconLabel"])
        assertEquals(1, icon["widthPixels"])
        assertEquals(1, icon["heightPixels"])
        assertTrue((icon["imageData"] as ByteArray).contentEquals(iconBytes))
    }

    @Test
    fun safeAreaInsetsReachTheAirPlayViewArea() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 960,
                    heightPixels = 540,
                    safeArea = AirPlayInsets(top = 10, bottom = 15, left = 20, right = 25),
                    safeAreaDrawOutside = false,
                ),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(915, safe["widthPixels"])
        assertEquals(515, safe["heightPixels"])
        assertEquals(20, safe["originXPixels"])
        assertEquals(10, safe["originYPixels"])
        assertEquals(false, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun reportedSafeAreaUsesActivityMappingAndEvenAlignment() {
        val fullHdInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 0, top = 0, right = 1920, bottom = 975),
            activityWidthPixels = 1920,
            activityHeightPixels = 1080,
            displayWidthPixels = 1920,
            displayHeightPixels = 1080,
        )
        val compactInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 34, top = 75, right = 734, bottom = 725),
            activityWidthPixels = 768,
            activityHeightPixels = 800,
            displayWidthPixels = 768,
            displayHeightPixels = 800,
        )

        fun safeArea(
            widthPixels: Int,
            heightPixels: Int,
            insets: AirPlayInsets,
        ): Map<*, *> {
            val info = AirPlayInfoPlist.build(
                AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:02",
                    sourceVersion = "366.0",
                    main = AirPlayDisplayConfig(
                        widthPixels = widthPixels,
                        heightPixels = heightPixels,
                        safeArea = insets,
                    ),
                ),
            )
            val display = (info["displays"] as List<*>).single() as Map<*, *>
            val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
            return view["safeArea"] as Map<*, *>
        }

        val fullHd = safeArea(1920, 1080, fullHdInsets)
        val compact = safeArea(768, 800, compactInsets)

        assertEquals(1920, fullHd["widthPixels"])
        assertEquals(976, fullHd["heightPixels"])
        assertEquals(700, compact["widthPixels"])
        assertEquals(650, compact["heightPixels"])
        assertEquals(34, compact["originXPixels"])
        assertEquals(75, compact["originYPixels"])
    }

    @Test
    fun microphoneInputsAreAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        fun telephony(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["audioType"] == "telephony" }

        fun defaultAudio(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["type"] == 100 && it["audioType"] == "default" }

        assertFalse(telephony(AirPlayInfoPlist.build(base)).containsKey("audioInputFormats"))
        assertTrue(
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))
                .containsKey("audioInputFormats"),
        )
        assertEquals(
            0x70004154,
            defaultAudio(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )
        assertEquals(
            0x70004154,
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )

        // Explicitly disabling Opus describes neither platform nor software encoding being usable.
        val withoutOpus = AirPlayInfoPlist.build(base.copy(microphone = true, microphoneOpus = false))
        assertEquals(0x4154, telephony(withoutOpus)["audioInputFormats"])
        assertEquals(0x4154, defaultAudio(withoutOpus)["audioInputFormats"])
        assertEquals(
            0x4154,
            (withoutOpus["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["audioType"] == "speechRecognition" }["audioInputFormats"],
        )
        // Output is untouched: the Opus decoder exists from API 21, only the encoder is missing.
        assertEquals(
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioOutputFormats"],
            telephony(withoutOpus)["audioOutputFormats"],
        )
        assertFalse(telephony(AirPlayInfoPlist.build(base.copy(microphoneOpus = false)))
            .containsKey("audioInputFormats"))
    }

    @Test
    fun bundledEncoderKeepsWirelessMicrophoneOpusWithoutAPlatformEncoder() {
        val config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            microphone = true,
            microphoneOpus = OpusEncoderSupport.isAvailable(
                platformAvailable = { false }, software = { SoftwareOpusEncoder(48_000) },
            ),
        )
        val formats = AirPlayInfoPlist.build(config)["audioFormats"] as List<*>
        for (audioType in listOf("telephony", "speechRecognition", "default")) {
            val format = formats.map { it as Map<*, *> }.single {
                it["type"] == 100 && it["audioType"] == audioType
            }
            assertEquals(audioType, 0x70004154, format["audioInputFormats"])
        }
    }

    @Test
    fun mainAltAndHighAudioStreamsAreDeclared() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val types = (info["audioFormats"] as List<*>)
            .map { (it as Map<*, *>)["type"] }
            .toSet()
        assertEquals(setOf(100, 101, 102), types)
    }

    private fun mainDisplay(areas: List<AirPlayViewArea>?, initial: Int = 0, safeArea: AirPlayInsets? = null): Map<*, *> {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 2560, heightPixels = 1440, safeArea = safeArea,
                    viewAreas = areas, initialViewArea = initial),
            ),
        )
        return (info["displays"] as List<*>).single() as Map<*, *>
    }

    @Test
    fun withoutAreasTheWholeScreenIsOneAreaWithoutAnEdge() {
        val display = mainDisplay(areas = null)
        val area = (display["viewAreas"] as List<*>).single() as Map<*, *>
        assertFalse(area.containsKey("viewAreaStatusBarEdge"))
        assertFalse(display.containsKey("viewAreaTransitionControl"))
        assertEquals(0, display["initialViewArea"])
    }

    @Test
    fun areasCarryTheirRectangleDockEdgeAndAClippedSafeArea() {
        val display = mainDisplay(
            areas = listOf(
                AirPlayViewArea(2560, 1440, dockEdge = AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE),
                AirPlayViewArea(1270, 1208, dockEdge = AirPlayInfoPlist.DOCK_EDGE_BOTTOM),
            ),
            initial = 1,
            safeArea = AirPlayInsets(top = 10, left = 20),
        )
        val areas = (display["viewAreas"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(2, 1), areas.map { it["viewAreaStatusBarEdge"] })
        assertEquals(1270, areas[1]["widthPixels"])
        assertEquals(1208, areas[1]["heightPixels"])
        assertEquals(0, areas[1]["originXPixels"])
        val safe = areas[1]["safeArea"] as Map<*, *>
        assertEquals(20, safe["originXPixels"])
        assertEquals(10, safe["originYPixels"])
        assertEquals(1270 - 20, safe["widthPixels"])
        assertEquals(1208 - 10, safe["heightPixels"])
        assertEquals(1, display["initialViewArea"])
        assertEquals(true, display["viewAreaTransitionControl"])
    }

    @Test
    fun viewAreaCommandCarriesAnimationAndTheOtherAreas() {
        val command = AirPlayInfoPlist.viewAreaCommand(1, areaCount = 2)
        assertEquals("updateViewArea", command["type"])
        val params = command["params"] as Map<*, *>
        assertEquals(AirPlayInfoPlist.MAIN_UUID, params["uuid"])
        assertEquals(1, params["viewAreaIndex"])
        assertEquals(300, params["animationDurationMillis"])
        assertEquals(listOf(0), params["adjacentViewAreas"])
        assertEquals(listOf(0, 1, 3), (AirPlayInfoPlist.viewAreaCommand(2, areaCount = 4)["params"] as Map<*, *>)["adjacentViewAreas"])
    }

    @Test
    fun aSavedMappingOutsideTheSplitAreaUsesItsBoundsWithoutChangingTheFullArea() {
        val split = AirPlayViewArea(1270, 1208)
        // The first mapping lies to the right, the second below, and the third only touches the edge.
        for (mapping in listOf(
            SafeAreaRect(1280, 0, 2560, 1440),
            SafeAreaRect(0, 1300, 2560, 1440),
            SafeAreaRect(1270, 0, 2560, 1440),
        )) {
            val insets = AirPlaySafeArea.toInsets(mapping, 2560, 1440, 2560, 1440)
            val display = mainDisplay(listOf(AirPlayViewArea(2560, 1440), split), safeArea = insets)
            val areas = (display["viewAreas"] as List<*>).map { it as Map<*, *> }
            val fullSafe = areas[0]["safeArea"] as Map<*, *>
            assertEquals(mapping.width, fullSafe["widthPixels"])
            assertEquals(mapping.height, fullSafe["heightPixels"])
            assertEquals(mapping.left, fullSafe["originXPixels"])
            assertEquals(mapping.top, fullSafe["originYPixels"])
            val splitSafe = areas[1]["safeArea"] as Map<*, *>
            assertEquals(split.width, splitSafe["widthPixels"])
            assertEquals(split.height, splitSafe["heightPixels"])
            assertEquals(split.originX, splitSafe["originXPixels"])
            assertEquals(split.originY, splitSafe["originYPixels"])
        }
    }

    @Test
    fun cornerMasksIsAdvertisedWhenConfigured() {
        fun displayWithMasks(enabled: Boolean?): Map<*, *> {
            val info = AirPlayInfoPlist.build(
                AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:02",
                    sourceVersion = "366.0",
                    main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720, mainDisplayCornerMasks = enabled),
                    cluster = AirPlayDisplayConfig(widthPixels = 960, heightPixels = 360, mainDisplayCornerMasks = true),
                ),
            )
            val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
            val mainDisplay = displays.single { it["uuid"] == AirPlayInfoPlist.MAIN_UUID }
            val clusterDisplay = displays.single { it["uuid"] == AirPlayInfoPlist.ALT_UUID }
            assertFalse(clusterDisplay.containsKey("cornerMasks"))
            return mainDisplay
        }

        assertEquals(true, displayWithMasks(true)["cornerMasks"])
        assertEquals(false, displayWithMasks(false)["cornerMasks"])
        assertFalse(displayWithMasks(null).containsKey("cornerMasks"))
    }

    @Test
    fun mainDisplayDisableSafeAreaOmitsSafeAreaFromViewAreasWhileKeepingClusterIntact() {
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720, mainDisplayCornerMasks = true),
            cluster = AirPlayDisplayConfig(widthPixels = 960, heightPixels = 360, mainDisplayDisableSafeArea = true),
        )
        val info = AirPlayInfoPlist.build(config)
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        val mainDisplay = displays.single { it["uuid"] == AirPlayInfoPlist.MAIN_UUID }
        val clusterDisplay = displays.single { it["uuid"] == AirPlayInfoPlist.ALT_UUID }

        val mainView = (mainDisplay["viewAreas"] as List<*>).single() as Map<*, *>
        assertFalse(mainView.containsKey("safeArea"))

        // AltScreen (cluster) must keep its safeArea even if mainDisplayDisableSafeArea was passed
        val clusterView = (clusterDisplay["viewAreas"] as List<*>).single() as Map<*, *>
        assertTrue(clusterView.containsKey("safeArea"))
    }

    @Test
    fun speechWithout8And16KhzLeavesHigherRatesAndOtherStreams() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )
        fun outputs(config: AirPlayConfig, type: Int, audioType: String): Int =
            (AirPlayInfoPlist.build(config)["audioFormats"] as List<*>).map { it as Map<*, *> }
                .single { it["type"] == type && it["audioType"] == audioType }["audioOutputFormats"] as Int
        val on = base.copy(speechWithout8And16Khz = true)
        // #148, #341: over USB the iPhone picked 0x8, PCM 8 kHz stereo, for navigation prompts.
        for ((type, audioType) in listOf(100 to "default", 100 to "alert", 101 to "default")) {
            assertEquals(0x3c, outputs(base, type, audioType) and 0x3c)
            assertEquals(0, outputs(on, type, audioType) and 0x3c)
            assertEquals(outputs(base, type, audioType) and 0x3c.inv(), outputs(on, type, audioType))
        }
        assertEquals(outputs(base, 100, "telephony"), outputs(on, 100, "telephony"))
        assertEquals(outputs(base, 100, "media"), outputs(on, 100, "media"))
    }

    @Test
    fun callAtCallRateOffersTelephonyOnlyAsPcmAtTheVocoderRates() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            microphone = true,
        )
        fun format(config: AirPlayConfig, audioType: String) =
            (AirPlayInfoPlist.build(config)["audioFormats"] as List<*>).map { it as Map<*, *> }
                .single { it["type"] == 100 && it["audioType"] == audioType }
        val on = base.copy(callAtCallRate = true)
        // Default: PCM mono 8/16/24/32/48 kHz plus the three Opus rates.
        assertEquals(0x70004154, format(base, "telephony")["audioOutputFormats"])
        // On: PCM mono 8, 16, 24 and 32 kHz; no Opus, no 44.1 or 48 kHz.
        assertEquals(0x154, format(on, "telephony")["audioOutputFormats"])
        // The call microphone is offered at the same rates; every other stream is untouched.
        assertEquals(0x70004154, format(base, "telephony")["audioInputFormats"])
        assertEquals(0x154, format(on, "telephony")["audioInputFormats"])
        // Without a microphone there is still no input offer.
        assertFalse(format(on.copy(microphone = false), "telephony").containsKey("audioInputFormats"))
        for (audioType in listOf("default", "alert", "media", "speechRecognition", "compatibility")) {
            assertEquals(format(base, audioType), format(on, audioType))
        }
    }
}
