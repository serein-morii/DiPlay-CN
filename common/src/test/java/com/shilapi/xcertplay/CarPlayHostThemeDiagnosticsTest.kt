package com.shilapi.xcertplay

import android.content.res.Configuration
import com.shilapi.xcertplay.airplay.AirPlaySession
import java.io.File
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayHostThemeDiagnosticsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var activity: CarPlayHostActivity
    private lateinit var logFile: File
    private lateinit var commands: PausedExecutorService

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        commands = PausedExecutorService()
        setField("airPlayCommandExecutor", commands)
        logFile = folder.newFile("diplay.log")
        setField("sessionLog", SessionLogFile(logFile))
        setField("darkMode", true)
        setField("lastConfiguration", configuration(Configuration.UI_MODE_NIGHT_YES))
    }

    @After fun tearDown() {
        commands.shutdownNow()
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("sessionLog") as SessionLogFile).close()
    }

    @Test fun undefinedSignalKeepsThePreviousModeAndIsRecordedWithoutASend() {
        refresh(Configuration.UI_MODE_TYPE_CAR, ThemeModeDiagnostics.Source.POLL)
        commands.runAll()

        assertEquals(true, getField("darkMode"))
        assertTrue(savedLog().contains("reported=undefined applied=dark sessionActive=false"))
        assertTrue(!savedLog().contains("THEME_DIAGNOSTIC send"))
    }

    @Test fun changedThemeIsSentOnceAndTheUnavailableEventChannelIsInTheSavedReport() {
        val session = mock(AirPlaySession::class.java)
        setField("activeAirPlaySession", session)
        refresh(Configuration.UI_MODE_NIGHT_NO, ThemeModeDiagnostics.Source.CALLBACK)
        commands.runAll()
        refresh(Configuration.UI_MODE_NIGHT_NO, ThemeModeDiagnostics.Source.POLL)
        commands.runAll()

        assertEquals(false, getField("darkMode"))
        verify(session, times(1)).setNightMode(false)
        val log = savedLog()
        assertTrue(log.contains("source=configuration-callback"))
        assertTrue(log.contains("reported=light applied=light sessionActive=true"))
        assertTrue(log.contains("THEME_DIAGNOSTIC send source=configuration-callback applied=light commandWritten=false"))
    }

    @Test fun fixedDayIsNotOverriddenBySystemNightAndDiagnosticsKeepBothStates() {
        val session = mock(AirPlaySession::class.java)
        setField("activeAirPlaySession", session)
        val controller = activity.javaClass.getDeclaredMethod("getNightModeController")
            .apply { isAccessible = true }.invoke(activity) as CarPlayNightModeController
        controller.configure(CarPlayNightMode.DAY, true)
        commands.runAll()
        refresh(Configuration.UI_MODE_NIGHT_NO, ThemeModeDiagnostics.Source.CALLBACK)
        refresh(Configuration.UI_MODE_NIGHT_YES, ThemeModeDiagnostics.Source.CALLBACK)
        commands.runAll()
        assertEquals(false, getField("darkMode"))
        verify(session, times(1)).setNightMode(false)
        verify(session, times(0)).setNightMode(true)
        assertTrue(savedLog().contains("reported=dark applied=light sessionActive=true"))
    }

    private fun savedLog(): String {
        assertTrue((getField("sessionLog") as SessionLogFile).awaitIdle(2_000))
        return logFile.readText()
    }

    private fun refresh(mode: Int, source: ThemeModeDiagnostics.Source) {
        activity.javaClass.getDeclaredMethod("refreshConfiguration", Configuration::class.java, ThemeModeDiagnostics.Source::class.java)
            .apply { isAccessible = true }.invoke(activity, configuration(mode), source)
    }

    private fun configuration(mode: Int) = Configuration().apply { uiMode = mode }

    private fun getField(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun setField(name: String, value: Any) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
