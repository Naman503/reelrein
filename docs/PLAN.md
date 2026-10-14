# ReelRein — Build Plan

Background and sources: [RESEARCH.md](RESEARCH.md).

## Decisions (Oct 10, 2026)
- Phone: **iQOO Z7 Pro** (Funtouch OS), **not rooted**.
- Speed: **2x lock in the real Instagram app**, re-applied on every reel automatically. No WebView player for now.
- Instagram's own player has only 1x and 2x. Speeds in between (1.25x, 1.5x) aren't possible without root or a modified Instagram, so the lock is on/off at 2x.

## Stack
- Kotlin (AGP 9.4.1, built-in Kotlin 2.4.20, Gradle 9.8.0), single module. `minSdk 26` (needed for `continueStroke`), `compileSdk 37`, `targetSdk 36`.
- Jetpack Compose for the screens inside the app. Plain `View`s for the overlays.
- SharedPreferences only: daily totals (`stats`) and settings (`settings`). No database needed yet.
- **No `INTERNET` permission** in `:app`.
- Install with `adb install` from Android Studio. This avoids developer verification; you'll still need to allow restricted settings once.

## Architecture

```
ReelReinAccessibilityService            (the only long-running component)
 ├─ ForegroundTracker    window-state events → "Instagram in front / left", session start/end
 ├─ InterceptController  shows the PauseOverlay when Instagram opens (unless a session is still running)
 ├─ SessionTimer         Handler-based; runs only while Instagram is in front → TimesUpOverlay
 ├─ ReelDetector         scroll events (on only while Instagram is in front) → "new reel" ticks
 │     └─ igSignatures.kt   all Instagram-specific IDs and heuristics in one place
 ├─ HudOverlay           counter pill, shown only on Reels screens
 └─ SpeedController      2x lock (hold gesture), best effort

data/   Room: Session(id, start, end, reels, opens…), DailyStat(date, reels, ms, opens)
ui/     Compose: onboarding (permission walkthrough), settings, stats
```

Event flow: `TYPE_WINDOW_STATE_CHANGED (com.instagram.android)` → ForegroundTracker → InterceptController shows the pause screen → user picks 10 min → SessionTimer starts, scroll events turn on → ReelDetector counts → HudOverlay updates → timer runs out → TimesUpOverlay → "Close" = `performGlobalAction(GLOBAL_ACTION_HOME)`.

## Phases

### Phase 0 — Find how Instagram's screens look to accessibility
- With Instagram open on the Reels tab, run `adb shell uiautomator dump` and look at the hierarchy in Android Studio's Layout Inspector. Note the class names, resource IDs and bounds for: the Reels tab pager, the full-screen reel viewer (opened from feed, DMs or profile), Stories, and the bottom tab bar.
- Write a debug-only "event logger" mode in the service that logs event type, class and view ID, **never text**.
- Result: `igSignatures.kt`, all Instagram-specific detection in one file.

### Phase 1 — MVP (the parts that work reliably)
1. Onboarding: steps for allowing restricted settings and turning on Accessibility, with a status check.
2. ForegroundTracker plus session logging.
3. Pause screen: breathing countdown, today's opens and time, intention chips, session length picker, Close.
4. Session timer and "Time's up" overlay (+5 min, limited per day).
5. ReelDetector plus HUD pill (session reels / today's reels / session time).
6. Simple stats screen (today plus last 7 days).

### Phase 2 — Speed
1. **2x lock (native Instagram):** after each reel settles, inject a held edge-press with continued strokes; re-apply after the user touches the screen; toggle from the HUD. Measure whether it's reliable, and drop it if it feels bad.
2. **Reels Player (instagram.com WebView), any speed:** a separate module or app that holds the `INTERNET` permission. Injected JS sets `playbackRate` on every `<video>` and counts reels exactly. Has speed presets, a 1x↔preferred toggle, and per-surface speeds. Cookies stay in the app's own WebView storage.
3. (If rooted) An LSPosed module hooking the player's playback parameters.

### Phase 3 — Extra controls
- Longer wait with each open, quiet hours and schedules, daily reel budget with breathing to earn more, doom-scroll nudge, DMs-only mode (block the Reels tab and Explore), grayscale (`WRITE_SECURE_SETTINGS` granted over adb), Quick Settings tile and widget, 60-second wait before turning protection off, weekly summary, local CSV export.

## Known risks
- An Instagram update can change view IDs. Keeping them all in `igSignatures.kt` plus the debug logger makes fixing it a 10-minute job.
- The 2x hold gesture may get in the way of normal touches. That's why it's a toggle and the WebView player is the reliable route.
- Some phone makers (Xiaomi, Oppo, Vivo, Samsung) kill or turn off accessibility services. You may need to exclude the app from battery optimization or "lock" it in Recents.
- Android 17 Advanced Protection turns off this app's accessibility access, so keep it off.
