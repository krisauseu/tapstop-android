package de.kf.blitztext

/** One terminal decision per pointer stream. Long press and drag permanently consume the tap. */
internal class BubbleGesture(private val touchSlop: Float) {
    enum class State { NONE, PRESSED, LONG_PRESSED, DRAGGING, CANCELLED }
    enum class Release { NONE, TAP, LONG_PRESS, DRAG }
    var state = State.NONE
        private set
    private var started = 0L
    private var allowLongPress = false

    fun down(time: Long, idle: Boolean) {
        started = time
        allowLongPress = idle
        state = State.PRESSED
    }

    fun move(dx: Float, dy: Float) {
        if ((state == State.PRESSED || state == State.LONG_PRESSED) &&
            dx * dx + dy * dy > touchSlop * touchSlop) state = State.DRAGGING
    }

    fun longPress(time: Long): Boolean {
        if (state != State.PRESSED || !allowLongPress || time - started < LONG_PRESS_MS) return false
        state = State.LONG_PRESSED
        return true
    }

    fun up(time: Long): Release {
        val result = when {
            longPress(time) -> Release.LONG_PRESS // Also handles a delayed main-loop callback.
            state == State.PRESSED -> Release.TAP
            state == State.DRAGGING -> Release.DRAG
            else -> Release.NONE
        }
        state = State.NONE
        return result
    }

    fun cancel() { state = State.CANCELLED }

    companion object { const val LONG_PRESS_MS = 480L }
}

internal data class FanPoint(val x: Int, val y: Int)

/** Four non-overlapping targets, mirrored towards the free half of the display. */
internal fun fanPositions(width: Int, height: Int, bubbleX: Int, bubbleY: Int, bubbleSize: Int, size: Int, gap: Int): List<FanPoint> {
    val right = bubbleX + bubbleSize / 2 >= width / 2
    val stackHeight = 4 * size + 3 * gap
    val top = (bubbleY + bubbleSize / 2 - stackHeight / 2).coerceIn(0, (height - stackHeight).coerceAtLeast(0))
    return (0..3).map { index ->
        val inset = if (index == 1 || index == 2) size / 2 else 0
        val x = if (right) bubbleX - gap - size - inset else bubbleX + bubbleSize + gap + inset
        FanPoint(x.coerceIn(0, (width - size).coerceAtLeast(0)), top + index * (size + gap))
    }
}
