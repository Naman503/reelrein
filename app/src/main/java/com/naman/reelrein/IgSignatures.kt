package com.naman.reelrein

/**
 * Everything that depends on Instagram's (undocumented) UI lives here, so an Instagram
 * update means editing this one file. To re-check after an update, turn on
 * "Debug log" in the app and run:  adb logcat -s ReelRein
 */
object IgSignatures {
    const val PACKAGE = "com.instagram.android"

    /** Resource-id fragments of the full-screen Reels pager (Reels tab, and reels opened from DMs/feed). */
    val REELS_ID_HINTS = listOf("clips_viewer_view_pager", "clips_viewer")

    fun isReelsId(viewId: String?): Boolean =
        viewId != null && REELS_ID_HINTS.any { viewId.contains(it) }

    /**
     * Windows from these packages can appear on top of Instagram (notification shade,
     * system dialogs) without the user leaving Instagram. The keyboard is handled separately.
     */
    val TRANSIENT_PACKAGES = setOf("com.android.systemui", "android")

    /**
     * Instagram's speed hint while a finger is held on a reel (no view ID, so matched by text,
     * case-insensitive). Seen on Instagram 450 in English:
     *   "Slide down to lock 2x speed"  -> not locked yet
     *   "Slide down for normal speed"  -> already locked (the same slide would unlock)
     */
    const val HINT_CAN_LOCK = "lock 2x"
    const val HINT_LOCKED = "for normal speed"

    /** Toast Instagram shows once the slide has locked 2x ("Locked at 2x speed"). */
    const val TOAST_LOCKED = "Locked at 2x"

    /** Menu items on the way to Instagram's Auto scroll switch: More (⋯) -> Playback -> Auto scroll. */
    const val MENU_PLAYBACK = "Playback"
    const val MENU_AUTO_SCROLL = "Auto scroll"

    /** Labels that only show while the reel's More menu (or its Playback page) is open. */
    val MENU_MARKERS = listOf(MENU_PLAYBACK, MENU_AUTO_SCROLL, "Remix and sequence", "About this reel", "Closed Captions")

    /** Exact labels and call-to-action buttons on ad reels. ReelRein never presses on these. */
    val AD_LABELS = listOf(
        "Ad", "Sponsored",
        "Install now", "Install", "Learn more", "Shop now", "Sign up", "Book now", "Order now",
        "Watch more", "Get offer", "Download", "Apply now", "Contact us", "Subscribe", "Get quote",
    )

    /**
     * Where the held press goes, as a fraction of the screen. Instagram plays 2x while you hold
     * the left or right edge; the right edge, above the like/comment column, is the default.
     * Both are adjustable in the app in case they land on a button.
     */
    const val DEFAULT_HOLD_X = 0.90f
    const val DEFAULT_HOLD_Y = 0.35f
}
