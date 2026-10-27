package io.github.smslistener.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.smslistener.R
import io.github.smslistener.ui.MainActivity
import io.github.smslistener.ui.PermissionsActivity
import io.github.smslistener.ui.TestsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Navigation and in-place test control; no physical alarm is asserted here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class UiNavigationTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }?.dismiss()
    }

    @Test fun homeRunReadinessOpensTheSamePermissionsPageAsSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "运行准备检查")
        assertEquals(PermissionsActivity::class.java.name, requireNotNull(shadowOf(activity).nextStartedActivity).component?.className)

        activity.findViewById<View>(R.id.nav_settings).performClick()
        idle()
        click(activity, "运行准备检查")
        assertEquals(PermissionsActivity::class.java.name, requireNotNull(shadowOf(activity).nextStartedActivity).component?.className)
        controller.pause().stop().destroy()
    }

    @Test fun alarmTestShowsTestingDialogAndStopsFromIt() {
        val controller = Robolectric.buildActivity(TestsActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "振动测试")

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog!!.isShowing)
        val texts = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue("dialog names the active test", texts.any { it == "测试中" })
        assertTrue("dialog explains the test is running", texts.any { it.contains("正在运行") })
        val stop = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals("停止", stop.text.toString())

        assertTrue(stop.performClick())
        idle()
        assertFalse(dialog.isShowing)
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "已停止测试" })
        controller.pause().stop().destroy()
    }

    private fun click(activity: Activity, text: String) {
        var target: View = views(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.isShown && it.text.toString() == text }
        while (!target.isClickable) target = target.parent as? View ?: error("No clickable ancestor: " + text)
        assertTrue(target.performClick())
        idle()
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
