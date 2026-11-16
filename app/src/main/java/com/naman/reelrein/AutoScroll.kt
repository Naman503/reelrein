package com.naman.reelrein

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Turns Instagram's own "Auto scroll" on (or off) for the session. Instagram forgets it between
 * sessions and keeps it three menus deep: More (⋯) on a reel -> Playback -> Auto scroll.
 *
 * Every step clicks a specific item through accessibility (no screen taps), and the switch's
 * current state is read before touching it, so it's never flipped the wrong way. If anything
 * isn't where expected, it backs out and gives up.
 */
class AutoScroll(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val log: (String) -> Unit,
) {
    var running = false
        private set

    /**
     * How the last try went, as a fixed word for usage counts: switched, already, or where it
     * gave up (no_more_button, no_playback, no_item, no_switch, tap_failed).
     */
    var outcome = "none"
        private set

    /** @param done true/false = Auto scroll is now on/off, null = couldn't tell or failed. */
    fun apply(want: Boolean, done: (Boolean?) -> Unit) {
        if (running) return
        running = true
        val more = roots().firstNotNullOfOrNull { r ->
            r.findAccessibilityNodeInfosByViewId(MORE_ID).firstOrNull { it.isVisibleToUser }
        }
        if (more == null || !click(more)) return finish(0, null, "no More button", done, "no_more_button")
        // Usually More -> Playback -> Auto scroll; some menus (e.g. on ads) show it right away.
        waitFor(IgSignatures.MENU_AUTO_SCROLL, IgSignatures.MENU_PLAYBACK) { found, which ->
            when {
                found == null -> finish(1, null, "no Playback item", done, "no_playback")
                which == IgSignatures.MENU_AUTO_SCROLL -> toggle(found, want, done)
                !click(found) -> finish(1, null, "couldn't open Playback", done, "no_playback")
                else -> waitFor(IgSignatures.MENU_AUTO_SCROLL) { label, _ -> toggle(label, want, done) }
            }
        }
    }

    /** Menus slide in: look for any of [labels] every 150 ms for up to 2 s. */
    private fun waitFor(vararg labels: String, then: (AccessibilityNodeInfo?, String?) -> Unit) {
        val until = SystemClock.uptimeMillis() + WAIT_MS
        fun poll() {
            for (l in labels) {
                val n = exact(l)
                if (n != null) return then(n, l)
            }
            if (SystemClock.uptimeMillis() > until) then(null, null) else handler.postDelayed({ poll() }, POLL_MS)
        }
        handler.postDelayed({ poll() }, POLL_MS)
    }

    private fun toggle(label: AccessibilityNodeInfo?, want: Boolean, done: (Boolean?) -> Unit) {
        if (label == null) return finish(2, null, "no Auto scroll item", done, "no_item")
        // The sheet is still sliding in when its items first appear: let it settle before tapping.
        handler.postDelayed({ flip(want, attempt = 1, done) }, SETTLE_MS)
    }

    private fun currentSwitch(): AccessibilityNodeInfo? =
        exact(IgSignatures.MENU_AUTO_SCROLL)?.let { findToggle(clickableAncestor(it) ?: it) ?: findToggle(it.parent) }

    /** Tap the toggle like a finger would (then, on a retry, click it directly) and read it again. */
    private fun flip(want: Boolean, attempt: Int, done: (Boolean?) -> Unit) {
        val switch = currentSwitch() ?: return finish(2, null, "can't read the switch", done, "no_switch")
        val on = switch.isChecked
        if (attempt == 1) log("auto scroll: switch ${describe(switch)}")
        if (on == want) {
            return if (attempt == 1) finish(2, on, "already ${if (on) "on" else "off"}", done, "already")
            else finish(2, on, "switched", done, if (attempt == 2) "switched" else "switched_retry")
        }
        if (attempt > 2) return finish(2, on, "tap didn't take (now $on)", done, "tap_failed")
        if (attempt == 1) tapCenter(switch) else switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({ flip(want, attempt + 1, done) }, VERIFY_MS)
    }


    /**
     * Close the menus we opened: Back only while one of Instagram's menu items is still on
     * screen, at most 3 times, so we never back out of Reels itself.
     */
    private fun finish(opened: Int, result: Boolean?, why: String, done: (Boolean?) -> Unit, outcome: String) {
        log("auto scroll: $why")
        this.outcome = outcome
        fun back(left: Int) {
            val menuShowing = IgSignatures.MENU_MARKERS.any { exact(it) != null }
            if (left <= 0 || !menuShowing) {
                running = false
                done(result)
                return
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            handler.postDelayed({ back(left - 1) }, BACK_MS)
        }
        back(if (opened > 0) 3 else 0)
    }

    /** Every Instagram window, top first: the menus are sheets in windows of their own. */
    private fun roots(): List<AccessibilityNodeInfo> {
        val fromWindows = runCatching { service.windows }.getOrNull().orEmpty()
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == IgSignatures.PACKAGE }
        if (fromWindows.isNotEmpty()) return fromWindows
        return listOfNotNull(service.rootInActiveWindow?.takeIf { it.packageName?.toString() == IgSignatures.PACKAGE })
    }

    /**
     * A visible node whose text or description is exactly [label] (case-insensitive). Walks the
     * tree itself: Instagram's menus are drawn by its own UI framework, which Android's
     * findAccessibilityNodeInfosByText doesn't search.
     */
    private fun exact(label: String): AccessibilityNodeInfo? {
        for (r in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(r)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val n = queue.removeFirst()
                visited++
                val match = n.text?.toString()?.trim().equals(label, true) ||
                    n.contentDescription?.toString()?.trim().equals(label, true)
                if (match && n.isVisibleToUser) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        val state = if (android.os.Build.VERSION.SDK_INT >= 30) n.stateDescription else null
        return "${n.className} checked=${n.isChecked} state=$state clickable=${n.isClickable} bounds=$r"
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    /**
     * The row's real toggle. Instagram's row holds two checkable nodes: a dummy Switch over the
     * label (not clickable, and its state is wrong) and the actual ToggleButton on the right
     * (clickable, correct state). Prefer a clickable checkable node.
     */
    private fun findToggle(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(x: AccessibilityNodeInfo, depth: Int) {
            if (x.isCheckable) all += x
            if (depth < 4) for (i in 0 until x.childCount) x.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(n, 0)
        return all.firstOrNull { it.isClickable } ?: all.firstOrNull()
    }

    /**
     * Instagram's menu rows don't always say they're clickable, so: try the click action on the
     * node and its parents, and if none accept it, tap the centre of that exact item.
     */
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return tapCenter(n)
    }

    /** A short tap on the centre of exactly this item. */
    private fun tapCenter(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val tap = GestureDescription.StrokeDescription(
            Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()); lineTo(r.exactCenterX() + 1, r.exactCenterY()) }, 0, 40,
        )
        return service.dispatchGesture(GestureDescription.Builder().addStroke(tap).build(), null, null)
    }

    private companion object {
        const val MORE_ID = "${IgSignatures.PACKAGE}:id/clips_ufi_more_button_component"
        const val WAIT_MS = 2_000L
        const val POLL_MS = 150L
        const val VERIFY_MS = 600L
        const val SETTLE_MS = 450L
        const val BACK_MS = 350L
        const val MAX_NODES = 1_500
    }
}
