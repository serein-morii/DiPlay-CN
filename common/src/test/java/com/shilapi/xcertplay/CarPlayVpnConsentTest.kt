package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], qualifiers = "en", shadows = [VpnConsentTestShadow::class, VpnPreConsentTestShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVpnConsentTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var instructions: TextView
    private lateinit var help: TextView
    private var launches = 0

    @Before fun setUp() {
        VpnConsentTestShadow.failure = null
        VpnConsentTestShadow.consent = Intent().setClassName(
            "com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog",
        )
        VpnPreConsentTestShadow.access = LocalAdb.Access.UNREACHABLE
        VpnPreConsentTestShadow.allowsVpn = false
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "stageStatusView", TextView(activity))
        instructions = TextView(activity)
        help = TextView(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "connectionInstructionsView", instructions)
        ReflectionHelpers.setField(activity, "connectionHelpView", help)
        invoke("initializeSessionLog")
        replaceLauncher { launches += 1 }
    }

    @After fun tearDown() {
        awaitAdbConsent()
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun missingConsentDialogDoesNotCrashOrLeaveAuthorizationPending() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }

        invoke("requestVpnConsent")

        assertUnavailable()
        assertTrue(log().contains("failureClass=ActivityNotFoundException"))
        assertTrue(log().contains("component=com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog"))
    }

    @Test fun deniedConsentLaunchDoesNotCrashOrUnlockUsb() {
        replaceLauncher { throw SecurityException("OEM blocked VPN activity") }
        invoke("requestVpnConsent")
        assertUnavailable()
        assertTrue(log().contains("operation=launch"))
        assertTrue(log().contains("failureClass=SecurityException"))
    }

    @Test fun deniedPrepareDoesNotCrashOrLeaveStaleAuthorization() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        VpnConsentTestShadow.failure = SecurityException("OEM blocked VPN preparation")
        invoke("requestVpnConsent")
        assertUnavailable()
        assertEquals(0, launches)
        assertTrue(log().contains("operation=prepare"))
    }

    @Test fun grantedAuthorizationDoesNotOpenAConsentActivity() {
        VpnConsentTestShadow.consent = null
        invoke("requestVpnConsent")
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertEquals(0, launches)
    }

    @Test fun pendingAuthorizationDoesNotLaunchTwice() {
        repeat(2) { invoke("requestVpnConsent") }
        assertFalse(field("vpnReady"))
        assertTrue(field("awaitingVpnConsent"))
        assertEquals(1, launches)
    }

    @Test fun failedLaunchCanBeRetried() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        replaceLauncher { launches += 1 }
        invoke("requestVpnConsent")
        assertTrue(field("awaitingVpnConsent"))
        assertFalse(field("vpnReady"))
        assertEquals(1, launches)
    }

    @Test fun lateSuccessfulResultAfterFailedLaunchDoesNotUnlockUsb() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
    }

    @Test fun resultOfPendingConsentCompletesOrDeniesAuthorization() {
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
    }

    @Test fun withoutAConsentScreenOrNetworkAdbTheScreenGivesTheAdbCommand() {
        consentScreenMissingThenAdb(LocalAdb.Access.UNREACHABLE)
        assertAdbStep(R.string.vpn_authorization_adb_unusable)
        assertTrue(log().contains("VPN consent over adb access=UNREACHABLE granted=false"))
    }

    @Test fun pairingOnlyAdbIsNamedLikeAdbThatDoesNotAnswer() {
        consentScreenMissingThenAdb(LocalAdb.Access.UNSUPPORTED)
        assertAdbStep(R.string.vpn_authorization_adb_unusable)
    }

    @Test fun adbThatHasNotApprovedDiPlayIsNamed() {
        consentScreenMissingThenAdb(LocalAdb.Access.NOT_APPROVED)
        assertAdbStep(R.string.vpn_authorization_adb_not_approved)
    }

    @Test @Config(qualifiers = "zh-rTW") fun theCauseStaysNamedOutsideEnglish() {
        consentScreenMissingThenAdb(LocalAdb.Access.NOT_APPROVED)
        assertAdbStep(R.string.vpn_authorization_adb_not_approved)
    }

    @Test fun adbThatRanWithoutAllowingTheVpnSaysSo() {
        consentScreenMissingThenAdb(LocalAdb.Access.READY)
        assertAdbStep(R.string.vpn_authorization_adb_did_not_allow)
        assertTrue(log().contains("VPN consent over adb access=READY granted=false"))
    }

    @Test fun consentAllowedOverAdbContinuesWithoutAStep() {
        consentScreenMissingThenAdb(LocalAdb.Access.READY, allowsVpn = true)
        assertTrue(field("vpnReady"))
        assertEquals(View.VISIBLE, instructions.visibility)
        assertEquals(View.GONE, help.visibility)
        assertNotEquals(activity.getString(R.string.vpn_authorization_adb_did_not_allow), stage())
        assertTrue(log().contains("VPN consent over adb access=READY granted=true"))
    }

    @Test fun theStepGoesOnceTheNextAttemptHasConsent() {
        consentScreenMissingThenAdb(LocalAdb.Access.UNREACHABLE)
        assertEquals(View.VISIBLE, help.visibility)
        // The command was run from a computer, then the iPhone plugged in again.
        VpnConsentTestShadow.consent = null
        invoke("requestVpnConsent")
        assertTrue(field("vpnReady"))
        assertEquals(View.VISIBLE, instructions.visibility)
        assertEquals(View.GONE, help.visibility)
    }

    private fun consentScreenMissingThenAdb(access: LocalAdb.Access, allowsVpn: Boolean = false) {
        VpnPreConsentTestShadow.access = access
        VpnPreConsentTestShadow.allowsVpn = allowsVpn
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        awaitAdbConsent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitAdbConsent() = Thread.getAllStackTraces().keys
        .filter { it.name == "diplay-vpn-adb-consent" }.forEach { it.join(3_000) }

    private fun assertAdbStep(@StringRes cause: Int) {
        assertEquals(activity.getString(cause), stage())
        assertEquals(View.GONE, instructions.visibility)
        assertEquals(View.VISIBLE, help.visibility)
        assertTrue(help.text.toString(),
            help.text.endsWith("\nadb shell appops set ${activity.packageName} ACTIVATE_VPN allow"))
        assertFalse(field("vpnReady"))
        assertEquals(1, log().lines().count { it.contains("VPN consent over adb") })
    }

    private fun stage() = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()

    private fun replaceLauncher(launch: (Intent) -> Unit) {
        ReflectionHelpers.setField(activity, "vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) = launch(input)
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
    }

    private fun assertUnavailable() {
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
        val message = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertTrue(message.contains("VPN authorization"))
        assertTrue(message.contains("Return to DiPlay"))
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("adb shell"))
    }

    private fun field(name: String): Boolean = ReflectionHelpers.getField(activity, name)
    private fun log(): String {
        assertTrue(ReflectionHelpers.getField<SessionLogFile>(activity, "sessionLog").awaitIdle(2_000))
        return File(activity.filesDir, "logs/diplay.log").readText()
    }
    private fun invoke(name: String) {
        try { activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
}

@Implements(VpnService::class)
class VpnConsentTestShadow {
    companion object {
        var consent: Intent? = null
        var failure: RuntimeException? = null
        @JvmStatic @Implementation fun prepare(context: Context): Intent? {
            failure?.let { throw it }
            return consent
        }
    }
}

/** The car's adb as [VpnPreConsent] finds it; with [allowsVpn], its appops call gives the consent. */
@Implements(VpnPreConsent::class, isInAndroidSdk = false)
class VpnPreConsentTestShadow {
    @Implementation fun grant(context: Context, adb: LocalAdb): LocalAdb.Access {
        if (access == LocalAdb.Access.READY && allowsVpn) VpnConsentTestShadow.consent = null
        return access
    }

    companion object {
        @Volatile var access = LocalAdb.Access.UNREACHABLE
        @Volatile var allowsVpn = false
    }
}
