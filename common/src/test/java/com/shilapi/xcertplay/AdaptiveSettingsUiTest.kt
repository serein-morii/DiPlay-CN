package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class AdaptiveSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun compactSettingsOpenOnAnEverydayDriverOverview() {
        val screen = openSettings()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_overview) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun savedLightAppearanceThemesTheSettingsCanvasAndSystemBars() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val screen = openSettings()
        val palette = ReflectionHelpers.getField<DiPlayPalette>(screen, "palette")
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        assertSame(DiPlayPalette.LIGHT, palette)
        assertEquals(DiPlayPalette.LIGHT.background, (scroll.background as ColorDrawable).color)
        assertEquals(
            DiPlayPalette.LIGHT.background,
            (screen.window.decorView.background as ColorDrawable).color,
        )
        assertEquals(DiPlayPalette.LIGHT.systemBar, screen.window.navigationBarColor)
    }

    @Test fun headerShortcutTogglesAppearanceAndReturnsFocusToItself() {
        val screen = openSettings()
        val switchToLight = screen.getString(R.string.settings_switch_to_light_appearance)
        val button = descendants(screen.window.decorView).single {
            it.contentDescription == switchToLight
        }
        assertEquals(Math.round(48 * screen.resources.displayMetrics.density), button.layoutParams.width)
        assertTrue(button.requestFocus())

        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutRoot(screen)

        assertEquals(AppAppearance.LIGHT, AirPlayPersistence.loadAppAppearance(screen))
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val replacement = descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_switch_to_dark_appearance)
        }
        assertTrue(replacement.isFocused)
    }

    @Test fun autoAppearanceRepaintsWhenTheCarPlayPolicyChanges() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        val screen = openSettings()
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))

        AirPlayPersistence.saveCarPlayNightMode(screen, CarPlayNightMode.NIGHT)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        assertEquals(DiPlayPalette.DARK.background, (scroll.background as ColorDrawable).color)
    }

    @Test fun hostAppearancePublishedOffMainRepaintsOnMain() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        val screen = openSettings()
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val owner = Any()
        val failure = AtomicReference<Throwable?>()

        val publisher = Thread {
            try {
                AppAppearanceRuntime.publishHost(owner, true)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        publisher.start()
        publisher.join()

        assertNull(failure.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        AppAppearanceRuntime.clearHost(owner)
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w500dp-h400dp")
    fun appearanceRepaintKeepsTheCurrentSettingsPageAndScrollPosition() {
        val screen = openSettings()
        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(
                R.string.settings_open_category,
                screen.getString(R.string.settings_display),
            )
        }.performClick()
        layoutRoot(screen)
        val oldScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        oldScroll.scrollTo(0, oldScroll.getChildAt(0).height)
        val previousY = oldScroll.scrollY
        assertTrue(previousY > 0)

        descendants(screen.window.decorView).single {
            it.contentDescription == screen.getString(R.string.settings_switch_to_light_appearance)
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        layoutRoot(screen)

        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertEquals(previousY, ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").scrollY)
    }

    @Test fun openDialogKeepsItsAppearanceAndEditsWhileTheScreenRepaints() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val screen = openSettings()
        texts(screen).single { it.text == screen.getString(R.string.settings_search) }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("display")
        val dialogAccent = dialog.context.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent),
        ).let { attributes ->
            try {
                attributes.getColor(0, 0)
            } finally {
                attributes.recycle()
            }
        }

        AirPlayPersistence.saveAppAppearance(screen, AppAppearance.DARK)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "checkForAppearanceChange")

        assertTrue(dialog.isShowing)
        assertEquals("display", input.text.toString())
        assertEquals(DiPlayPalette.LIGHT.accent, dialogAccent)
        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
    }

    @Config(qualifiers = "en-w700dp-h400dp-land")
    @Test fun fullSettingsOverrideIsIndependentAndCanBeDisabledFromDisplay() {
        val screen = openSettings()
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        fun settingsSwitch() = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_force_full_settings) }

        settingsSwitch().isChecked = true
        assertTrue(SettingsLayoutPreferences.forceFull(context))
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))

        settingsSwitch().isChecked = false
        assertFalse(SettingsLayoutPreferences.forceFull(context))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
    }

    @Config(qualifiers = "en-w400dp-h700dp-port")
    @Test fun portraitIgnoresFullSettingsOverrideWithoutClearingPreference() {
        SettingsLayoutPreferences.saveForceFull(context, true)
        val screen = openSettings()
        assertFalse(SettingsLayoutPreferences.isActive(screen))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
        assertTrue(SettingsLayoutPreferences.forceFull(context))
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h700dp-land")
    fun naturallyExpandedSettingsHideTheForceLayoutSwitch() {
        SettingsLayoutPreferences.saveForceFull(context, true)
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(descendants(screen.window.decorView).filterIsInstance<Switch>().any {
            it.contentDescription == screen.getString(R.string.settings_force_full_settings)
        })
    }

    @Test fun readinessAsksForAnIphoneBeforeWirelessCanConnect() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val screen = openSettings()
        // Robolectric has no CarPlay authentication assets, which is a setup error of its own.
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_choose_iphone_title) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_choose_iphone_action) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
    }

    @Test fun chosenPhoneWithMissingManualHotspotOffersConnectionSetup() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "My iPhone")
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.setup_needs_attention) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
        texts(screen).single { it.text == screen.getString(R.string.open_connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun searchOpensTheCategoryThatOwnsTheSetting() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val results = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "searchSettings",
            ReflectionHelpers.ClassParameter(List::class.java, index),
            ReflectionHelpers.ClassParameter(String::class.java, "FRAME rate"))
        val frameRate = results.single {
            ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.frame_rate)
        }

        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSearchResult",
            ReflectionHelpers.ClassParameter(frameRate.javaClass, frameRate))

        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") })
    }

    @Test
    @Config(shadows = [HotspotSearchProbe::class])
    fun hotspotSearchIndexesItsAsyncCardWithoutStartingAnAdbProbe() {
        installBydSettingsPackage()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        HotspotSearchProbe.workers.clear()
        HotspotSearchProbe.entered = CountDownLatch(1)
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            screen, "buildSettingsSearchIndex")
        val result = index.single { it.title == screen.getString(R.string.auto_car_hotspot_title) }

        assertEquals(SettingsCategory.CONNECTION, result.category)
        assertTrue("Building search metadata must not contact ADB", HotspotSearchProbe.workers.isEmpty())
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.auto_car_hotspot_title) })

        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSearchResult",
            ReflectionHelpers.ClassParameter(result.javaClass, result))
        assertTrue(HotspotSearchProbe.entered.await(3, TimeUnit.SECONDS))
        val worker = HotspotSearchProbe.workers.single()
        worker.join(3_000)
        assertFalse(worker.isAlive)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SettingsCategory.CONNECTION,
            ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertEquals(1, descendants(screen.window.decorView).filterIsInstance<Switch>().count {
            it.contentDescription == screen.getString(R.string.auto_car_hotspot_title)
        })
    }

    @Test
    @Config(shadows = [HotspotSearchProbe::class])
    fun hotspotSearchKeepsTheBydAndCarHotspotAudienceGates() {
        installBydSettingsPackage()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        HotspotSearchProbe.workers.clear()
        HotspotSearchProbe.entered = CountDownLatch(1)
        val screen = openSettings()
        fun index() = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            screen, "buildSettingsSearchIndex")
        val title = screen.getString(R.string.auto_car_hotspot_title)

        assertFalse(index().any { it.title == title })
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        shadowOf(context.packageManager).removePackage("com.byd.carsettings")
        assertFalse(index().any { it.title == title })
        assertTrue(HotspotSearchProbe.workers.isEmpty())
    }

    @Test fun cardControlsShareOneHeightAndGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        val density = screen.resources.displayMetrics.density
        fun dp(value: Int) = Math.round(value * density)
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        val buttons = descendants(scroll).filterIsInstance<android.widget.Button>()
            .filter { it !is Switch && (it.parent as? LinearLayout)?.orientation == LinearLayout.VERTICAL }
            .toList()

        assertTrue(buttons.size >= 4)
        buttons.forEach { button ->
            val lp = button.layoutParams as LinearLayout.LayoutParams
            assertEquals(button.text.toString(), dp(60), lp.height)
            assertEquals(button.text.toString(), 0, lp.topMargin)
            assertTrue(button.text.toString(), lp.bottomMargin in setOf(0, dp(6), dp(12)))
        }
        val page = scroll.getChildAt(0)
        assertFalse(descendants(scroll).any { it.tag == "spacer" && it.parent !== page })
    }

    @Test fun searchNamesOpenTheirOwnCategoryNotACardThatLinksToIt() {
        val screen = openSettings()
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val advanced = index.single { ReflectionHelpers.getField<String>(it, "title") == screen.getString(R.string.settings_advanced) }

        assertEquals(SettingsCategory.ADVANCED, ReflectionHelpers.getField<SettingsCategory>(advanced, "category"))
    }

    @Test fun aRowBeforeAButtonKeepsTheGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_vehicle)) }
            .performClick()
        val chooseImage = texts(screen).single { it.text == screen.getString(R.string.choose_image) }
        val card = chooseImage.parent as LinearLayout
        val preview = card.getChildAt(card.indexOfChild(chooseImage) - 1)

        assertEquals(Math.round(12 * screen.resources.displayMetrics.density),
            (preview.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    @Test fun tappingAToggleRowChangesTheSetting() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.full_screen) }

        (fullScreen.parent as View).performClick()

        assertTrue(fullScreen.isChecked)
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
    }

    @Test fun compactDetailReturnsToOverviewBeforeLeavingSettings() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun openingACompactCategoryAlwaysStartsAtTheTop() {
        val screen = openSettings()
        val firstScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        firstScroll.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
        )
        firstScroll.layout(0, 0, 600, 700)
        firstScroll.scrollTo(0, 400)
        assertTrue(firstScroll.scrollY > 0)

        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        val detailScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        assertEquals(0, detailScroll.scrollY)
    }

    @Test fun quickFullScreenChangesBothExistingPreferencesTogether() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.full_screen) }

        fullScreen.performClick()

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(true, fullScreen.isChecked)
    }

    @Test fun systemBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun toolbarBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        texts(screen).single { it.text == screen.getString(R.string.back) }.performClick()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
    }

    @Test fun connectionSetupOpenedDirectlyStillReturnsHome() {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "connection"),
        ).setup().get().also { activity = it }

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun advancedContainsOnlyExpertSections() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { candidate ->
                candidate.contentDescription == screen.getString(
                    R.string.settings_open_category,
                    screen.getString(R.string.settings_advanced),
                ) && descendants(candidate).filterIsInstance<ImageView>().count() == 2
            }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_advanced_caution_title) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.carplay_map_on_instrument_cluster_experimental) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.advanced_vehicle_data) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.automatic_connection) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.display_and_performance) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.audio_routing) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.location) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.diagnostics) })
    }

    @Test fun displayOpensPictureAdjustments() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.picture_adjustments) })
    }

    @Test fun settingsLiveWhereDriversLookForThem() {
        val screen = openSettings()
        fun visibleIn(category: Int): List<CharSequence> {
            descendants(screen.window.decorView).first { candidate ->
                candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
            }.performClick()
            return texts(screen).map { it.text }.toList().also { screen.onBackPressedDispatcher.onBackPressed() }
        }
        fun text(id: Int) = screen.getString(id)

        val display = visibleIn(R.string.settings_display)
        val audio = visibleIn(R.string.audio)
        val navigation = visibleIn(R.string.settings_navigation)
        val vehicle = visibleIn(R.string.settings_vehicle)
        val advanced = visibleIn(R.string.settings_advanced)

        assertTrue(audio.any { it.startsWith(text(R.string.music_buffer)) })
        listOf(R.string.main_buffered_audio, R.string.settings_car_bluetooth_audio, R.string.efficient_video, R.string.smooth_video, R.string.call_echo_cancellation, R.string.call_voice_filter, R.string.contrib_audio_home_toggle_audio_focus).forEach {
            assertTrue(text(it), text(it) in advanced)
            assertFalse(text(it), text(it) in audio)
        }
        assertTrue(text(R.string.right_hand_drive) in vehicle)
        assertTrue(text(R.string.settings_launcher_returns_to_carplay) in vehicle)
        assertTrue(text(R.string.car_button_in_carplay) in vehicle)
        assertTrue(text(R.string.wheel_siri_key) in vehicle)
        assertTrue(text(R.string.settings_wheel_keys) in vehicle)
        assertTrue(text(R.string.side_panel) in advanced)
        assertTrue(text(R.string.carplay_map_on_instrument_cluster_experimental) in navigation)
        assertFalse(text(R.string.carplay_map_on_instrument_cluster_experimental) in advanced)
        assertTrue(display.any { it.startsWith(text(R.string.settings_app_appearance)) })
        assertFalse(audio.any { it.startsWith(text(R.string.settings_app_appearance)) })
        assertFalse(vehicle.any { it.startsWith(text(R.string.settings_app_appearance)) })
        assertFalse(advanced.any { it.startsWith(text(R.string.settings_app_appearance)) })
        listOf(R.string.main_buffered_audio, R.string.efficient_video, R.string.smooth_video, R.string.call_echo_cancellation, R.string.call_voice_filter, R.string.right_hand_drive, R.string.car_button_in_carplay,
            R.string.side_panel, R.string.split_screen_areas, R.string.carplay_rotation).forEach {
            assertFalse(text(it), text(it) in display)
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun experimentalCallProcessingIsOptInAndMarksTheActiveSessionForReconnect() {
        assertFalse(AirPlayPersistence.loadCallEchoCancellation(context))
        assertFalse(AirPlayPersistence.loadCallVoiceFilter(context))
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
            listOf(R.string.call_echo_cancellation, R.string.call_voice_filter).forEach { title ->
                PendingReconnect.clear()
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                    .single { it.contentDescription == screen.getString(title) }
                assertFalse(setting.isChecked)
                setting.performClick()

                assertTrue(PendingReconnect.isPending(session))
                assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
                assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
                assertEquals(0, stops)
                assertEquals(null, shadowOf(screen).nextStartedActivity)
            }
            assertTrue(AirPlayPersistence.loadCallEchoCancellation(context))
            assertTrue(AirPlayPersistence.loadCallVoiceFilter(context))
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailStaysOutsideTheScrollingCategory() {
        val screen = openSettings()
        val categoryScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val railDestination = descendants(screen.window.decorView).first {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) &&
                descendants(it).filterIsInstance<ImageView>().count() == 1
        }

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) && isInside(it, categoryScroll) })
        assertFalse(isInside(railDestination, categoryScroll))
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailUsesAnIconForEveryDestination() {
        val screen = openSettings()
        val rail = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        val destinations = descendants(rail).filter { destination ->
            destination.contentDescription?.startsWith("Open ") == true &&
                destination.contentDescription?.endsWith(" settings") == true &&
                descendants(destination).filterIsInstance<ImageView>().count() == 1
        }.toList()
        assertEquals(10, destinations.size)
        destinations.forEach { destination ->
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test fun compactOverviewCategoriesHaveIcons() {
        val screen = openSettings()
        listOf(SettingsCategory.CONNECTION, SettingsCategory.DISPLAY, SettingsCategory.AUDIO,
            SettingsCategory.NAVIGATION, SettingsCategory.VEHICLE).forEach { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            val destination = descendants(screen.window.decorView).single {
                it.contentDescription == screen.getString(R.string.settings_open_category, title)
            }
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test fun compactLanguageAndAboutDestinationsWork() {
        verifyLanguageAndAboutDestinations()
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h700dp")
    fun expandedLanguageAndAboutDestinationsWork() {
        verifyLanguageAndAboutDestinations()
    }

    private fun verifyLanguageAndAboutDestinations() {
        val screen = openSettings()
        val categoryScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val languageDestination = descendants(categoryScroll).single {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.language_section_title))
        }
        assertTrue(isInside(languageDestination, categoryScroll))
        run {
            val titles = descendants(categoryScroll).mapNotNull { it.contentDescription?.toString() }.toList()
            val vehicle = screen.getString(R.string.settings_open_category, screen.getString(R.string.settings_vehicle))
            val language = screen.getString(R.string.settings_open_category, screen.getString(R.string.language_section_title))
            val about = screen.getString(R.string.settings_open_category, screen.getString(R.string.about))
            assertEquals(titles.indexOf(vehicle) + 1, titles.indexOf(language))
            assertEquals(titles.indexOf(language) + 1, titles.indexOf(about))
        }
        fun open(title: Int) {
            descendants(screen.window.decorView).first {
                it.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(title))
            }.performClick()
        }
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.language_app_language) })
        open(R.string.language_section_title)
        assertEquals(SettingsCategory.LANGUAGE, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.language_hint) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_language_summary) })
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.OVERVIEW))
        open(R.string.about)
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertEquals(SettingsCategory.ABOUT, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.carplay_at_home_in_your_car) })
        val expanded = ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout")
        if (expanded) {
            val rail = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
            assertTrue(descendants(rail).any {
                it.isSelected && it.contentDescription == screen.getString(
                    R.string.settings_open_category, screen.getString(R.string.about))
            })
        }
        screen.onBackPressedDispatcher.onBackPressed()
        if (expanded) {
            assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
        } else {
            assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
            assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h400dp")
    fun selectingTheBottomRailDestinationKeepsItVisibleAndFocusedOnAShortScreen() {
        val screen = openSettings()
        val density = screen.resources.displayMetrics.density
        fun layout() {
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val width = Math.round(1000 * density)
            val height = Math.round(400 * density)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
        }
        fun rail() = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        fun advanced(scroll: ScrollView) = descendants(scroll).single {
            it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_advanced))
        }
        layout()
        val previousRail = rail()
        previousRail.scrollTo(0, previousRail.getChildAt(0).height)
        assertTrue("This window must require rail scrolling", previousRail.scrollY > 0)
        val destination = advanced(previousRail)
        assertTrue(destination.requestFocus())
        assertTrue(previousRail.hasFocus())

        destination.performClick()
        layout()

        val currentRail = rail()
        val selected = advanced(currentRail)
        assertFalse(previousRail === currentRail)
        assertTrue(selected.isSelected)
        assertTrue("D-pad focus must follow the selected category", selected.isFocused)
        assertTrue(currentRail.scrollY > 0)
        val top = selected.top + currentRail.getChildAt(0).top
        assertTrue("Selected row starts inside the rail viewport", top >= currentRail.scrollY)
        assertTrue("Selected row ends inside the rail viewport",
            top + selected.height <= currentRail.scrollY + currentRail.height)
        assertEquals("Category scrolling remains independent", 0,
            ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").scrollY)
    }

    @Test fun overviewUtilitiesAreOneGroupedCardWithSectionSpacing() {
        val screen = openSettings()
        fun utilityRow(category: Int) = descendants(screen.window.decorView).single { candidate ->
            candidate.contentDescription == screen.getString(
                R.string.settings_open_category,
                screen.getString(category),
            ) && descendants(candidate).filterIsInstance<ImageView>().count() == 2
        }

        val diagnostics = utilityRow(R.string.diagnostics)
        val advanced = utilityRow(R.string.settings_advanced)
        assertSame(diagnostics.parent, advanced.parent)
        val card = diagnostics.parent as View
        assertEquals(Math.round(18 * screen.resources.displayMetrics.density), (card.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    private fun installBydSettingsPackage() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    @Implements(CarHotspotSetup::class, isInAndroidSdk = false)
    class HotspotSearchProbe {
        @Implementation fun check(context: Context, adb: LocalAdb): LocalAdb.Access {
            workers += Thread.currentThread()
            entered.countDown()
            return LocalAdb.Access.READY
        }

        companion object {
            val workers = CopyOnWriteArrayList<Thread>()
            var entered = CountDownLatch(1)
        }
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

    private fun openConnectionSetupFromSettings(): DiPlayActivity = openSettings().also { screen ->
        descendants(screen.window.decorView)
            .single { it.contentDescription == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.connection)) }
            .performClick()
        texts(screen).single { it.text == screen.getString(R.string.open_connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
    }

    private fun layoutRoot(screen: DiPlayActivity) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun isInside(view: View, ancestor: View): Boolean =
        generateSequence(view.parent) { it.parent }.any { it === ancestor }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
