package de.kf.blitztext

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp")
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayIntegrationTest {
    private lateinit var service: OverlayService
    private lateinit var bubble: TextView
    private var downTime = 0L

    @Before fun setUp() {
        service = Robolectric.buildService(OverlayService::class.java).create().get()
        OverlayService::class.java.getDeclaredMethod("addBubble").apply { isAccessible = true }.invoke(service)
        bubble = field("bubble") as TextView
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
    }

    @After fun tearDown() { service.onDestroy() }

    private fun field(name: String): Any? = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun event(action: Int, x: Float = 25f, y: Float = 25f) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also {
            bubble.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    private fun openFan(): FrameLayout {
        event(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val fan = field("fan") as? FrameLayout
        assertNotNull("Long press must attach the fan", fan)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals("Four satellites must exist", 4, fan!!.childCount)
        for (i in 0..3) {
            val child = fan.getChildAt(i)
            assertTrue("Satellite $i must have visible bounds", child.width > 0 && child.height > 0)
            assertEquals(View.VISIBLE, child.visibility)
        }
        return fan
    }

    @Test fun longPressRendersFourClickableModesAndReleaseDoesNotRecord() {
        openFan()
        event(MotionEvent.ACTION_UP)
        assertNotNull(field("fan"))
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun smallMovementAfterLongPressKeepsMenuVisible() {
        val fan = openFan()
        // Small drift stays within Android touchSlop.
        event(MotionEvent.ACTION_MOVE, 26f, 25f)
        event(MotionEvent.ACTION_UP, 26f, 25f)
        assertSame("A recognized long press must remain a menu gesture", fan, field("fan"))
        for (i in 0..3) assertEquals(View.VISIBLE, fan.getChildAt(i).visibility)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun satelliteSelectionPersistsAndDoesNotTapTheBubble() {
        val fan = openFan()
        event(MotionEvent.ACTION_UP)
        val satellite = fan.getChildAt(2)
        val x = satellite.left + satellite.width / 2f
        val y = satellite.top + satellite.height / 2f
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, time, action, x, y, 0).also { fan.dispatchTouchEvent(it); it.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(Mode.CHAT, Settings(service).mode)
        assertTrue(bubble.contentDescription.toString().startsWith("Chat."))
        assertNull(field("fan"))
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun outsideTapClosesWithoutRecordingOrChangingMode() {
        val mode = Settings(service).mode
        val fan = openFan()
        event(MotionEvent.ACTION_UP)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, time, action, 1f, 1f, 0).also { fan.dispatchTouchEvent(it); it.recycle() }
        }
        assertNull(field("fan"))
        assertEquals(mode, Settings(service).mode)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun secondLongPressThroughMenuWindowClosesWithoutRecording() {
        val fan = openFan()
        event(MotionEvent.ACTION_UP)
        val bubbleOrigin = IntArray(2); bubble.getLocationOnScreen(bubbleOrigin)
        val fanOrigin = IntArray(2); fan.getLocationOnScreen(fanOrigin)
        val start = SystemClock.uptimeMillis()
        fun dispatch(action: Int) {
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                bubbleOrigin[0] + bubble.width / 2f, bubbleOrigin[1] + bubble.height / 2f, 0).also {
                it.offsetLocation(-fanOrigin[0].toFloat(), -fanOrigin[1].toFloat())
                fan.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        dispatch(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        dispatch(MotionEvent.ACTION_UP)
        assertNull(field("fan"))
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun dragBeforeLongPressNeverOpensMenuOrRecords() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_MOVE, 100f, 100f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        event(MotionEvent.ACTION_UP, 100f, 100f)
        assertNull(field("fan"))
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test fun settingsChangeReachesRunningOverlayAndNewSettingsInstance() {
        val settings = Settings(service)
        Mode.entries.forEach { mode ->
            settings.mode = mode
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(mode, (field("settings") as Settings).mode)
            assertEquals(mode, Settings(service).mode)
            assertTrue(bubble.contentDescription.toString().startsWith("${mode.label}."))
        }
    }
}
