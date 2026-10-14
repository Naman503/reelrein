package com.naman.reelrein

import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Turns scroll events from the Reels pager into "settled on another reel" ticks.
 *
 * Pagers (ViewPager / RecyclerView) report the visible item range in fromIndex..toIndex.
 * While a swipe is in flight two items are visible; once it settles from == to, and a
 * change of that index means another reel is on screen. Only forward moves count as
 * newly watched reels. If the pager reports no indexes, fall back to scroll distance
 * (about one screen per reel).
 */
class ReelCounter {
    enum class Move { NONE, FORWARD, BACK }

    private var lastIndex = -1
    private var initialCounted = false
    private var fallbackDy = 0

    fun reset() {
        lastIndex = -1
        initialCounted = false
        fallbackDy = 0
    }

    /** The pager was seen for the first time this session: the reel on screen counts once. */
    fun markEntered(): Boolean {
        if (initialCounted) return false
        initialCounted = true
        return true
    }

    fun onScroll(e: AccessibilityEvent, screenHeight: Int): Move {
        val from = e.fromIndex
        val to = e.toIndex
        if (from >= 0 && to >= 0) {
            if (lastIndex < 0) {
                lastIndex = from
                return Move.NONE
            }
            if (from != to || from == lastIndex) return Move.NONE // still moving, or same reel
            val move = if (from > lastIndex) Move.FORWARD else Move.BACK
            lastIndex = from
            return move
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            fallbackDy += e.scrollDeltaY
            val page = (screenHeight * 0.45f).toInt()
            if (fallbackDy >= page) {
                fallbackDy = 0
                return Move.FORWARD
            }
            if (fallbackDy <= -page) {
                fallbackDy = 0
                return Move.BACK
            }
        }
        return Move.NONE
    }
}
