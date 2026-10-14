package com.shilapi.xcertplay.settings

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsWidgetsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun constructingAndChangingASwitchDoesNotSaveTheInitialValue() {
        for (theme in SettingsTheme.entries) {
            val saved = mutableListOf<Boolean>()
            val row = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, theme,
                onChanged = saved::add)
            assertTrue(saved.isEmpty())
            row.switch.isChecked = false
            row.switch.isChecked = false
            assertEquals(listOf(false), saved)
        }
    }

    @Test fun choicesEmitOnlyNewSelectionsAndIgnoreClearingTheSelection() {
        val saved = mutableListOf<MfiTarget>()
        val row = ConnectionSettingsSection.createMfiTargetChoice(context, MfiTarget.LOCAL,
            onSelected = saved::add)
        assertTrue(saved.isEmpty())
        val target = views(row.container).filterIsInstance<RadioButton>().first { it.tag == MfiTarget.USB_CH341 }
        row.radioGroup.check(target.id)
        row.radioGroup.check(target.id)
        row.radioGroup.clearCheck()
        assertEquals(listOf(MfiTarget.USB_CH341), saved)
    }

    @Test fun resolutionDisplayAndSliderStayWithinTheSameRangeWithoutSavingDuringConstruction() {
        for (initial in listOf(-1, 73, 999)) {
            val saved = mutableListOf<Int>()
            val row = SettingsWidgets.createResolutionSlider(context, initial, onPercentChanged = saved::add)
            val expected = initial.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
            assertEquals(expected - CarPlayDisplayScale.MIN_PERCENT, row.seekBar.progress)
            assertEquals(expected.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.isEmpty())
            row.seekBar.progress = 0
            row.seekBar.progress = Int.MAX_VALUE
            assertEquals(CarPlayDisplayScale.MAX_PERCENT.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.all { it in CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT })
            assertEquals(CarPlayDisplayScale.MAX_PERCENT, saved.last())
        }
    }

    @Test fun overlayKeepsTheExistingBlueControlsAndLabelMetrics() {
        val row = ConnectionSettingsSection.createWirelessCarPlayRow(context, true, onChanged = {})
        val label = (row.rowView as ViewGroup).getChildAt(0) as TextView
        assertEquals(20f, label.textSize / context.resources.displayMetrics.scaledDensity, 0.01f)
        assertFalse(label.includeFontPadding)
        assertEquals(Color.rgb(144, 163, 188), label.currentTextColor)
        assertEquals(Color.rgb(39, 136, 251), row.switch.thumbTintList!!.getColorForState(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0))
        val heading = SettingsWidgets.createCategoryHeader(context, "Heading")
        assertEquals(Color.rgb(39, 136, 251), heading.currentTextColor)
        assertFalse(heading.includeFontPadding)
    }

    @Test fun switchesUseTheLargeTrackAndMarkTheDisabledState() {
        for (theme in SettingsTheme.entries) {
            val switch = SettingsWidgets.createSwitchRow(context, "Title", "Details", false, theme, onChanged = {}).switch
            switch.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            assertTrue(switch.measuredWidth >= theme.dp(context, 60))
            assertTrue(switch.measuredHeight >= theme.dp(context, 34))
            val enabledOff = switch.trackTintList!!.getColorForState(intArrayOf(android.R.attr.state_enabled), 0)
            val disabled = switch.trackTintList!!.getColorForState(intArrayOf(), 0)
            assertEquals(theme.trackOff, enabledOff)
            assertNotEquals(enabledOff, disabled)
        }
    }

    @Test fun cardDescriptionsKeepMultilineSpacingAndTheirAccessibleTitle() {
        val row = SettingsWidgets.createSwitchRow(context, "Title", "First line\nSecond line", false,
            SettingsTheme.CARD, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        assertEquals(listOf("Title", "First line\nSecond line"), texts.map { it.text.toString() })
        assertTrue(texts.all { it.lineSpacingExtra == SettingsTheme.CARD.dp(context, 3).toFloat() })
        assertEquals("Title", row.switch.contentDescription)
    }

    @Test fun lightCardUsesTheResolvedSemanticPalette() {
        val theme = SettingsTheme.card(DiPlayPalette.LIGHT)
        val row = SettingsWidgets.createSwitchRow(context, "Title", "Details", false,
            theme, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        assertEquals(DiPlayPalette.LIGHT.primaryText, texts[0].currentTextColor)
        assertEquals(DiPlayPalette.LIGHT.secondaryText, texts[1].currentTextColor)
        assertEquals(DiPlayPalette.LIGHT.trackOff, row.switch.trackTintList!!.getColorForState(intArrayOf(android.R.attr.state_enabled), 0))
        assertEquals(
            DiPlayPalette.LIGHT.accent,
            row.switch.thumbTintList!!.getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0),
        )
        assertEquals(DiPlayPalette.LIGHT.ripple, theme.ripple)
        assertEquals(DiPlayPalette.LIGHT.focusRing, theme.focusRing)
    }

    @Test fun lightOverlayUsesSemanticTextAndControlColors() {
        val theme = SettingsTheme.overlay(DiPlayPalette.LIGHT)
        val row = SettingsWidgets.createSwitchRow(
            context,
            "Title",
            "Details",
            true,
            theme,
            onChanged = {},
        )
        val label = (row.rowView as ViewGroup).getChildAt(0) as TextView

        assertEquals(DiPlayPalette.LIGHT.overlaySecondaryText, label.currentTextColor)
        assertEquals(
            DiPlayPalette.LIGHT.overlayAccent,
            row.switch.thumbTintList!!.getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0),
        )
        assertEquals(
            DiPlayPalette.LIGHT.overlayAccentTrack,
            row.switch.trackTintList!!.getColorForState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0),
        )
        assertEquals(
            DiPlayPalette.LIGHT.overlayTrackOff,
            row.switch.trackTintList!!.getColorForState(intArrayOf(android.R.attr.state_enabled), 0),
        )
    }

    @Test @Config(sdk = [28]) fun android9HotspotChoiceDoesNotExposeUnavailableWifiDirect() {
        val row = ConnectionSettingsSection.createHotspotModeChoice(context, WirelessHotspotMode.MANUAL,
            onSelected = {})
        val options = views(row.container).filterIsInstance<RadioButton>().toList()
        assertEquals(listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI), options.map { it.tag })
        assertEquals(WirelessHotspotMode.MANUAL, options.first { it.isChecked }.tag)
    }

    @Test fun carHotspotIsTheFirstAndRecommendedHotspotChoice() {
        val row = ConnectionSettingsSection.createHotspotModeChoice(context, WirelessHotspotMode.WIFI_P2P,
            onSelected = {})
        val options = views(row.container).filterIsInstance<RadioButton>().toList()
        assertEquals(listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI),
            options.map { it.tag })
        assertEquals("Built-in car hotspot (recommended)", options.first().text.toString())
        assertEquals(WirelessHotspotMode.WIFI_P2P, options.first { it.isChecked }.tag)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
