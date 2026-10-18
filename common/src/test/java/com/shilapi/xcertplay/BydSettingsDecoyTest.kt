package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * A head unit may answer a standard settings action with a screen that is not the screen.
 *
 * DiLink 1.0 does exactly that: sixty android.settings actions resolve to VehicleSettings'
 * "unsupported" activity, which reports string/unsupport_tips and does nothing else. Because a
 * startActivity that reaches it succeeds, the action has to be resolved before it can be trusted —
 * and the replacement only applies where the decoy actually is, on the one Android version that has
 * been measured (docs/BYD_SETTINGS_ENTRY_POINTS.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en", manifest = Config.NONE)
class BydSettingsDecoyTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var content: LinearLayout

    private val decoy = ComponentName(BYD_PACKAGE, "$BYD_PACKAGE.main.unsupport.UnsupportActivity")
    private val bydBluetooth =
        ComponentName("com.byd.btsetting", "com.byd.btsetting.BluetoothManagerDialog")
    private val stockSettings =
        ComponentName("com.android.settings", "com.android.settings.Settings\$BluetoothSettingsActivity")

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        content = LinearLayout(activity)
        // Render the one card that holds the button under test rather than the whole settings page.
        ReflectionHelpers.setField(activity, "settingsSectionFilter",
            setOf(SettingsSection.PERMISSIONS_AND_HELP))
    }

    /** Installs a component that can be opened by name, without claiming any action. */
    private fun install(component: ComponentName) {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).addActivityIfNotPresent(component)
    }

    /** Makes [action] resolve to [component], the way a head unit's settings app would. */
    private fun resolveTo(component: ComponentName, action: String) {
        val packages = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        packages.addActivityIfNotPresent(component)
        packages.addIntentFilterForActivity(component, IntentFilter(action))
    }

    private fun bluetoothButton(): Button {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        return descendants(content).filterIsInstance<Button>()
            .single { it.text == activity.getString(R.string.bluetooth_settings) }
    }

    /** `nextStartedActivity` pops the queue, so it is read once per test. */
    private fun started() = shadowOf(activity).nextStartedActivity

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    @Test @Config(sdk = [25])
    fun theDecoyOnAndroid71SendsTheDriverToBydsOwnBluetoothScreen() {
        resolveTo(decoy, Settings.ACTION_BLUETOOTH_SETTINGS)
        install(bydBluetooth)

        bluetoothButton().performClick()

        assertEquals(bydBluetooth, started().component)
    }

    @Test @Config(sdk = [25])
    fun aStockSettingsScreenKeepsTheStandardAction() {
        resolveTo(stockSettings, Settings.ACTION_BLUETOOTH_SETTINGS)
        install(bydBluetooth)

        bluetoothButton().performClick()

        val intent = started()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, intent.action)
        assertNull(intent.component)
    }

    /**
     * The same firmware one Android version later is left alone: the replacement components are only
     * audited on 7.1, so the decoy is not even looked for and the driver still gets the decoy's answer.
     */
    @Test @Config(sdk = [26])
    fun theDecoyIsIgnoredAboveAndroid71() {
        resolveTo(decoy, Settings.ACTION_BLUETOOTH_SETTINGS)
        install(bydBluetooth)

        bluetoothButton().performClick()

        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, started().action)
    }

    /** Android 7.0, which DiPlay also installs on, has no audited BYD build either, so it is left alone too. */
    @Test @Config(sdk = [25])
    fun theDecoyIsIgnoredBelowAndroid71() {
        resolveTo(decoy, Settings.ACTION_BLUETOOTH_SETTINGS)
        install(bydBluetooth)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.N)
        try {
            bluetoothButton().performClick()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.N_MR1)
        }

        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, started().action)
    }

    private companion object {
        const val BYD_PACKAGE = "com.byd.vehiclesettings"
    }
}
