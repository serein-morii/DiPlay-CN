package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Reuses the lifecycle test's shell shadow: Robolectric shares one sandbox across both. Runs the real command strings through /bin/sh with a stub app_process that records CLASSPATH. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [BydCarPlayCallLifecycleTest.Shell::class])
class BydCarPlayCallQuotingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private val writer get() = ReflectionHelpers.getField<ExecutorService>(BydCarPlayCall, "writer")
    private val originalSourceDir by lazy { app.applicationInfo.sourceDir }
    private fun drain() { writer.submit {}.get(5, TimeUnit.SECONDS) }
    private fun ringing() = Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
        u8(2, 2); string(4, "a")
    }

    @Before fun setup() {
        originalSourceDir
        BydCarPlayCall.watcherReadyMillis = 500L
        drain()
        ReflectionHelpers.getField<CarPlayCallState>(BydCarPlayCall, "state").clear()
        ReflectionHelpers.setField(BydCarPlayCall, "shown", null)
        ReflectionHelpers.setField(BydCarPlayCall, "watcherToken", null)
        ReflectionHelpers.setField(BydCarPlayCall, "watcherRunning", false)
        ReflectionHelpers.setField(BydCarPlayCall, "cleanupPending", false)
        ReflectionHelpers.setField(BydCarPlayCall, "prepared", false)
        ReflectionHelpers.setField(BydCarPlayCall, "sessionActive", false)
        BydCarPlayCall.attach(app)
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCallLifecycleTest.Shell.run {
            commands.clear(); rejectPhase = null; endFailures = 0; omitCompletion = false
            rejectWatcher = false; omitReadiness = false; omitCancelCompletion = false; emptyWatcherReply = false
        }
    }

    @After fun cleanup() {
        BydCarPlayCall.end(); drain()
        app.applicationInfo.sourceDir = originalSourceDir
    }

    @Test fun anApkPathWithASpaceAndAQuoteReachesEveryToolAsOneClasspath() {
        val marker = File(temporary.root, "injected")
        val apk = "/data/app/a b'; touch ${marker.path}; echo '/base.apk"
        assertEquals(listOf(apk), classpathsSeenByTools(apk).distinct())
        assertFalse("the path was run as shell code", marker.exists())
    }

    @Test fun anOrdinaryApkPathStillReachesEveryTool() {
        val apk = "/data/app/~~x==/com.example-y==/base.apk"
        val seen = classpathsSeenByTools(apk)
        assertTrue(seen.size >= 3)
        assertEquals(listOf(apk), seen.distinct())
    }

    private fun classpathsSeenByTools(apk: String): List<String> {
        assumeTrue(File("/bin/sh").exists())
        app.applicationInfo.sourceDir = apk
        BydCarPlayCall.onFrame(ringing()); drain()
        BydCarPlayCall.end(); drain()
        val out = File(temporary.root, "classpaths.txt")
        val stubs = "app_process() { printf '%s\\n' \"\$CLASSPATH\" >> '${out.path}'; echo write=0; }; " +
            "setsid() { \"\$@\"; }; nohup() { \"\$@\"; }; "
        BydCarPlayCallLifecycleTest.Shell.commands.filter { it.contains("app_process") }.forEach { command ->
            ProcessBuilder("/bin/sh", "-c", "$stubs$command; wait").redirectErrorStream(true).start()
                .also { it.inputStream.readBytes(); it.waitFor(5, TimeUnit.SECONDS) }
        }
        return if (out.exists()) out.readLines() else emptyList()
    }
}
