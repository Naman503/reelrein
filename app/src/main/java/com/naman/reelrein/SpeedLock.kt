package com.naman.reelrein

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.SystemClock

/**
 * Gets Instagram to play at 2x, using Instagram's own gestures.
 *
 * LOCK (preferred): Instagram's "hold, then slide down to lock 2x". Instagram resets the lock
 * on every reel, and the same gesture on a locked reel turns it OFF, so we never slide blind:
 * the press waits (in 100 ms steps) for Instagram's hint to appear, reads which hint it is
 * ("...lock 2x speed" or "...for normal speed"), and only slides when that changes things in
 * the direction we want. No hint within 1.5 s, or the reel moved under the press: let go
 * without sliding, so a press can never turn into a swipe to another reel.
 * After that the finger is up, so your own taps and swipes never interfere.
 *
 * HOLD (fallback, for builds without the lock): an injected press that never lifts. Each
 * segment is a stroke with willContinue = true, continued when it completes (one gesture is
 * capped at 60 s), moving 1 px up or down because a stroke path can't be empty. Any real
 * touch cancels it; onLost tells the service to press again once the next reel settles.
 */
class SpeedLock(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val onLost: () -> Unit,
    /** Which speed hint Instagram is showing under the finger right now. */
    private val readHint: () -> Hint,
    /** True if the reel pager scrolled since the given uptime (the press must be abandoned). */
    private val pagerMovedSince: (Long) -> Boolean,
    private val onChanged: () -> Unit,
    /** A spot on ReelRein's own invisible touch pad. A tap there cancels any held press. */
    private val cancelPoint: () -> PointF?,
    private val log: (String) -> Unit = {},
    /** Instagram's "Locked at 2x speed" toast is on screen (the lock really took). */
    private val lockConfirmed: () -> Boolean = { true },
    /** How each LOCK attempt ended, so the service can retry an unconfirmed one. */
    private val onResult: (Result) -> Unit = {},
) {
    enum class Method { LOCK, HOLD }

    enum class Hint { CAN_LOCK, LOCKED, NONE }

    enum class Result { LOCKED, UNLOCKED, ALREADY, NO_HINT, MOVED, TOUCHED, UNCONFIRMED, TIMEOUT }

    /** 2x is believed to be on for the current reel. */
    var active = false
        private set

    private var busy = false
    private var stroke: GestureDescription.StrokeDescription? = null
    private var pressStroke: GestureDescription.StrokeDescription? = null
    private var x = 0f
    private var y = 0f
    private var step = 0
    private var generation = 0

    /** @param slideDy signed slide distance in px for LOCK (positive = down). */
    fun engage(method: Method, px: Float, py: Float, slideDy: Float) {
        if (active || busy) return
        when (method) {
            Method.LOCK -> pressAndSet(px, py, slideDy, wantLocked = true)
            Method.HOLD -> startHold(px, py)
        }
    }

    /** Back to 1x on the current reel. */
    fun disengage(method: Method, px: Float, py: Float, slideDy: Float) {
        when (method) {
            Method.LOCK -> if (!busy) pressAndSet(px, py, slideDy, wantLocked = false)
            Method.HOLD -> liftHold()
        }
    }

    /** Stop tracking (left Reels / Instagram, or a new reel came on). Ends any press safely. */
    fun forget() {
        liftHold()
        abortPress()
        active = false
    }

    /** A LOCK press still has the finger down: cancel it so it can't count as a tap. */
    private fun abortPress() {
        if (pressStroke == null) return
        pressStroke = null
        generation++
        busy = false
        cancelHeldPress()
    }

    /**
     * Ends the held press as a CANCEL, never a tap. Lifting a press that nobody long-pressed
     * counts as a tap (on an ad that opens the Play Store), and moving a little before lifting
     * doesn't help, because the ad view fills the screen. Starting a new gesture makes Android
     * send ACTION_CANCEL to whatever the held press was touching; the new gesture is a 1 ms tap
     * on ReelRein's own invisible pad, so it touches nothing in Instagram.
     */
    private fun cancelHeldPress() {
        val p = cancelPoint() ?: return
        runCatching {
            val tap = GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y); lineTo(p.x + 1, p.y) }, 0, 1)
            service.dispatchGesture(GestureDescription.Builder().addStroke(tap).build(), null, null)
        }
    }

    // ---------- LOCK ----------

    private fun pressAndSet(px: Float, py: Float, dy: Float, wantLocked: Boolean) {
        busy = true
        val gen = ++generation
        val startedAt = SystemClock.uptimeMillis()
        x = px
        y = py
        step = 0

        fun finish(locked: Boolean?, result: Result) {
            log("lock ${if (wantLocked) "on" else "off"}: $result after ${SystemClock.uptimeMillis() - startedAt} ms")
            busy = false
            pressStroke = null
            if (locked != null) active = locked
            onChanged()
            onResult(result)
        }

        // Instagram took the long press (its hint is showing), so a plain lift is safe.
        fun letGo(s: GestureDescription.StrokeDescription, locked: Boolean) {
            pressStroke = null
            dispatch(
                s.continueStroke(nextSegment(), 0, 1, false), gen,
                onCompleted = { finish(locked, Result.ALREADY) },
                onCancelled = { finish(null, Result.TOUCHED) },
            )
        }

        // Nobody took the long press: lifting would be a tap, so cancel the press instead.
        fun bail(result: Result) {
            generation++ // ignore the cancelled press's callback
            pressStroke = null
            cancelHeldPress()
            finish(null, result)
        }

        // After locking, wait for Instagram's own "Locked at 2x speed" toast before believing it.
        fun confirm(deadline: Long) {
            when {
                lockConfirmed() -> finish(true, Result.LOCKED)
                SystemClock.uptimeMillis() > deadline -> finish(false, Result.UNCONFIRMED)
                else -> handler.postDelayed({ if (gen == generation) confirm(deadline) }, CONFIRM_POLL_MS)
            }
        }

        fun slideAndRelease(s: GestureDescription.StrokeDescription) {
            pressStroke = null
            val from = y + (step % 2)
            val move = s.continueStroke(Path().apply { moveTo(x, from); lineTo(x, from + dy) }, 0, LOCK_SLIDE_MS, false)
            dispatch(move, gen, onCompleted = {
                if (wantLocked) confirm(SystemClock.uptimeMillis() + CONFIRM_WAIT_MS) else finish(false, Result.UNLOCKED)
            }, onCancelled = { finish(null, Result.TOUCHED) })
        }

        fun pressStep(s: GestureDescription.StrokeDescription) {
            pressStroke = s
            dispatch(s, gen, onCompleted = {
                val waited = SystemClock.uptimeMillis() - startedAt
                when {
                    // The reel moved under the press: it's a scroll, not a hold.
                    pagerMovedSince(startedAt) -> bail(Result.MOVED)
                    else -> when (readHint()) {
                        Hint.CAN_LOCK -> if (wantLocked) slideAndRelease(s) else letGo(s, false)
                        Hint.LOCKED -> if (wantLocked) letGo(s, true) else slideAndRelease(s)
                        Hint.NONE -> if (waited < LOCK_MAX_WAIT_MS) {
                            pressStep(s.continueStroke(nextSegment(), 0, LOCK_STEP_MS, true))
                        } else {
                            bail(Result.NO_HINT) // no 2x on this reel (photo, ad, live...)
                        }
                    }
                }
            }, onCancelled = { finish(null, Result.TOUCHED) }) // a real touch took over
        }

        pressStep(GestureDescription.StrokeDescription(nextSegment(), 0, LOCK_FIRST_STEP_MS, true))

        // Watchdog: if Android ever drops a gesture callback, don't stay "busy" on this reel.
        handler.postDelayed({
            if (busy && gen == generation) {
                if (pressStroke != null) bail(Result.TIMEOUT) else finish(null, Result.TIMEOUT)
            }
        }, LOCK_TIMEOUT_MS)
    }

    // ---------- HOLD ----------

    private fun startHold(px: Float, py: Float) {
        x = px
        y = py
        step = 0
        val gen = ++generation
        active = true
        sendHold(GestureDescription.StrokeDescription(nextSegment(), 0, HOLD_SEGMENT_MS, true), gen)
    }

    private fun sendHold(s: GestureDescription.StrokeDescription, gen: Int) {
        stroke = s
        dispatch(s, gen, onCompleted = {
            if (active) sendHold(s.continueStroke(nextSegment(), 0, HOLD_SEGMENT_MS, true), gen)
        }, onCancelled = {
            if (active) {
                active = false
                stroke = null
                onLost()
            }
        })
    }

    private fun liftHold() {
        val s = stroke ?: return
        stroke = null
        generation++
        active = false
        cancelHeldPress()
    }

    /** Alternates y and y+1 so each segment starts where the previous one ended. */
    private fun nextSegment(): Path {
        val from = y + (step % 2)
        val to = y + ((step + 1) % 2)
        step++
        return Path().apply {
            moveTo(x, from)
            lineTo(x, to)
        }
    }

    // ---------- shared ----------

    private fun dispatch(
        s: GestureDescription.StrokeDescription,
        gen: Int,
        onCompleted: () -> Unit,
        onCancelled: () -> Unit,
    ) {
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                if (gen == generation) onCompleted()
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                if (gen == generation) onCancelled()
            }
        }
        val accepted = runCatching {
            service.dispatchGesture(GestureDescription.Builder().addStroke(s).build(), callback, handler)
        }.getOrDefault(false)
        if (!accepted && gen == generation) onCancelled()
    }

    private companion object {
        const val HOLD_SEGMENT_MS = 8_000L
        const val LOCK_FIRST_STEP_MS = 350L // Instagram's long press can't fire sooner than this
        const val LOCK_STEP_MS = 90L        // then check for the hint every ~0.1 s
        const val LOCK_MAX_WAIT_MS = 1_100L
        const val LOCK_SLIDE_MS = 220L
        const val CONFIRM_WAIT_MS = 1_500L
        const val LOCK_TIMEOUT_MS = 4_000L
        const val CONFIRM_POLL_MS = 120L
    }
}
