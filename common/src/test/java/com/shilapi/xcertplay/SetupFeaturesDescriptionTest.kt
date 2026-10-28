package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The setup guide's features step names the car it suggests for, and no car when the version is not known. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land")
class SetupFeaturesDescriptionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @Before fun setUp() = clearSettings()

    @After fun tearDown() {
        opened.forEach { it.finish() }
        AppAppearanceRuntime.resetForTest()
        clearSettings()
    }

    private fun clearSettings() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun featuresStep(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "setup"),
    ).setup().get().also {
        opened += it
        ReflectionHelpers.setField(it, "setupStep", SetupGuide.STEP_FEATURES)
        ReflectionHelpers.callInstanceMethod<Unit>(it, "render")
    }

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun anUnknownCarIsNotNamed() {
        DiLinkGeneration.save(context, DiLinkGeneration.UNKNOWN)
        val screen = featuresStep()
        val shown = texts(screen)
        assertTrue(shown.toString(), screen.getString(R.string.setup_features_description_unknown) in shown)
        assertFalse(shown.toString(), shown.any { screen.getString(R.string.dilink_not_sure) in it })
    }

    @Test fun aKnownCarIsNamed() {
        DiLinkGeneration.save(context, DiLinkGeneration.DILINK_4)
        val screen = featuresStep()
        val shown = texts(screen)
        assertTrue(shown.toString(),
            screen.getString(R.string.setup_features_description, screen.getString(R.string.dilink_4)) in shown)
    }
}
