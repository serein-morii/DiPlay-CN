package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.network.CarHotspotSavedCredentials
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class CarHotspotImportSaveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: DiPlayActivity
    private val overwrite = "Overwrite the car hotspot settings?"

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        AirPlayPersistence.saveManualHotspotSecurity(app, ManualHotspotSecurity.WPA2)
        CarHotspotSettings.setConfigWrite(app, true)
        ReflectionHelpers.setField(activity, "importedCarHotspot",
            CarHotspotSavedCredentials("Car hotspot", "12345678", ManualHotspotSecurity.WPA2))
    }

    // Galaxy Note20, Android 13: the import read the car's own details, and saving them unchanged
    // warned that devices using the hotspot might have to re-enter its password.
    @Test fun savingTheImportedDetailsUnchangedDoesNotWarn() {
        save(editCredentials())
        assertNotEquals(overwrite, shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title)
        assertEquals("12345678", AirPlayPersistence.loadManualHotspotPassphrase(app))
    }

    @Test fun changedDetailsStillWarnBeforeTheCarIsOverwritten() {
        val dialog = editCredentials()
        descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()[1].setText("87654321")
        save(dialog)
        assertEquals(overwrite, shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title)
    }

    private fun editCredentials(): AlertDialog {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        activity.setContentView(parent)
        descendants(parent).filterIsInstance<Button>().first { it.text.startsWith("Edit saved hotspot") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun save(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
