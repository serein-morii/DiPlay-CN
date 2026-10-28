package com.diplay.home

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Another app can declare the map service too; the host token must only reach DiPlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiPlayMapPanelTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun answerMapService(vararg packages: String) {
        val packageManager = shadowOf(app.packageManager)
        for (pkg in packages) {
            val service = ComponentName(pkg, "$pkg.MapEmbedService")
            packageManager.addOrUpdateService(
                android.content.pm.ServiceInfo().apply { packageName = pkg; name = service.className })
            packageManager.addIntentFilterForService(service, IntentFilter("com.shihab.diplay.action.EMBED_MAP"))
        }
    }

    private fun boundPackages(): List<String?> {
        val bound = mutableListOf<String?>()
        val context = object : ContextWrapper(app) {
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                bound += service.component?.packageName
                return true
            }
        }
        DiPlayMapPanel(context).start()
        return bound
    }

    @Test fun doesNotBindAnUnknownAppThatAnswersFirst() {
        answerMapService("com.evil.map", "com.shihab.diplay")
        assertEquals(listOf<String?>("com.shihab.diplay"), boundPackages())
    }

    @Test fun doesNotBindWhenOnlyAnUnknownAppAnswers() {
        answerMapService("com.evil.map")
        assertEquals(listOf<String?>(), boundPackages())
    }

    private fun assertBinds(pkg: String) {
        answerMapService(pkg)
        assertEquals(listOf<String?>(pkg), boundPackages())
    }

    @Test fun bindsTheReleaseBuild() = assertBinds("com.shihab.diplay")

    @Test fun bindsTheDebugBuild() = assertBinds("com.shihab.diplay.hudtest")

    @Test fun bindsTheAutomotiveBuild() = assertBinds("com.shilapi.xcertplay")
}
