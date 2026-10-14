package com.naman.reelrein

/**
 * Session state lives in the process, not in the service object. In bank-safe mode the
 * accessibility service is switched off and on around Instagram, which creates a new service
 * instance each time; keeping this here means a quick trip out and back stays one session.
 */
object SessionState {
    class Session(var limitMs: Long?) {
        var usedMs = 0L     // Instagram in front
        var reelMs = 0L     // of which on Reels
        var reels = 0
        var timesUpShown = false
    }

    var session: Session? = null
    var lastLeftAt = 0L
    var knownReelsId: String? = null

    /** Instagram's Auto scroll was set this session (Instagram forgets it between sessions). */
    var autoScrollDone = false

    /** The pill was hidden from its panel; it comes back next session. */
    var hudHidden = false
    val counter = ReelCounter()
}
