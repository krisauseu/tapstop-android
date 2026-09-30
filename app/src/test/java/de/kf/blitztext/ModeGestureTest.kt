package de.kf.blitztext

import org.junit.Assert.*
import org.junit.Test

class ModeGestureTest {
    @Test fun shortTapAndThreshold() {
        val gesture = BubbleGesture(10f)
        gesture.down(100, true)
        gesture.move(6f, 8f) // Exactly slop is still a tap.
        assertEquals(BubbleGesture.Release.TAP, gesture.up(579))
        gesture.down(100, true)
        assertEquals(BubbleGesture.Release.LONG_PRESS, gesture.up(580))
        assertEquals(BubbleGesture.Release.NONE, gesture.up(581))
    }

    @Test fun timerLongPressConsumesReleaseAndOnlyFiresOnce() {
        val gesture = BubbleGesture(10f)
        gesture.down(100, true)
        assertFalse(gesture.longPress(579))
        assertTrue(gesture.longPress(580))
        assertFalse(gesture.longPress(581))
        assertEquals(BubbleGesture.Release.NONE, gesture.up(600))
        gesture.down(700, true)
        assertEquals(BubbleGesture.Release.TAP, gesture.up(710))
    }

    @Test fun diagonalDragCannotBecomeTapEvenAfterReturningToOrigin() {
        val gesture = BubbleGesture(10f)
        gesture.down(100, true)
        gesture.move(8f, 8f)
        gesture.move(0f, 0f)
        assertFalse(gesture.longPress(600))
        assertEquals(BubbleGesture.Release.DRAG, gesture.up(700))
    }

    @Test fun dragAfterLongPressCannotTap() {
        val gesture = BubbleGesture(10f)
        gesture.down(100, true)
        assertTrue(gesture.longPress(580))
        gesture.move(11f, 0f)
        assertEquals(BubbleGesture.Release.DRAG, gesture.up(600))
    }

    @Test fun cancelAndMultitouchCannotTapOrOpenMenu() {
        val gesture = BubbleGesture(10f)
        gesture.down(100, true)
        gesture.cancel()
        assertFalse(gesture.longPress(600))
        gesture.move(20f, 20f)
        assertEquals(BubbleGesture.Release.NONE, gesture.up(700))
    }

    @Test fun recordingHasNoLongPressAndStillStopsOnRelease() {
        val gesture = BubbleGesture(10f)
        gesture.down(100, false)
        assertFalse(gesture.longPress(600))
        assertEquals(BubbleGesture.Release.TAP, gesture.up(700))
    }

    @Test fun fanStaysVisibleAndSeparatedAtEdgesAndCorners() {
        for ((width, height) in listOf(360 to 640, 640 to 360, 320 to 240, 800 to 1280)) {
            for (x in listOf(0, 12, width - 68, width - 56)) {
                for (y in listOf(0, height / 2, height - 56)) {
                    val points = fanPositions(width, height, x, y, 56, 52, 8)
                    assertEquals(4, points.size)
                    points.forEach { point ->
                        assertTrue(point.x >= 0 && point.x + 52 <= width)
                        assertTrue(point.y >= 0 && point.y + 52 <= height)
                        if (x < width / 2) assertTrue(point.x >= x + 56)
                        else assertTrue(point.x + 52 <= x)
                    }
                    points.zipWithNext().forEach { (a, b) -> assertTrue(b.y >= a.y + 52) }
                }
            }
        }
    }

    @Test fun rawRewriteBypassesNetworkAndPreservesTextExactly() {
        val client = ApiClient(Provider.GROQ, GroqModel.LARGE_V3_TURBO)
        val raw = "  mein rohes Diktat äh\nmit zweiter Zeile "
        assertEquals(raw, client.rewrite(raw, "", Mode.BLITZTEXT))
    }

    @Test fun modeNamesPreserveHistoricalRowsAndRoutePrompts() {
        assertEquals(Mode.BLITZTEXT, Mode.valueOf("BLITZTEXT"))
        assertEquals(Mode.PLUS, Mode.valueOf("PLUS"))
        assertFalse(Mode.BLITZTEXT.usesRewrite)
        assertEquals(listOf(Mode.PLUS, Mode.CHAT, Mode.FORMAL), Mode.entries.filter { it.usesRewrite })
        assertEquals(ApiClient.REWRITE_PROMPT, ApiClient.promptFor(Mode.PLUS))
        assertEquals(ApiClient.CHAT_PROMPT, ApiClient.promptFor(Mode.CHAT))
        assertEquals(ApiClient.FORMAL_PROMPT, ApiClient.promptFor(Mode.FORMAL))
        assertEquals(3, Mode.entries.filter { it.usesRewrite }.map { ApiClient.promptFor(it) }.distinct().size)
        assertEquals(4, Mode.entries.map { it.symbol }.distinct().size)
    }
}
