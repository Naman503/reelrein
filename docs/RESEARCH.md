# ReelRein — Research Notes (Oct 2026)

Personal Android app to watch and control how I use Instagram:

1. **Pause screen when opening Instagram** (like One Sec) and a **break reminder** after a time I pick.
2. **Live HUD over Reels**: reels watched this session and today, plus time spent.
3. **Always-on playback speed** for Reels, with speeds above 2x (like the Video Speed Controller browser extension).

---

## 1. How the existing apps do it

| App | What it does | How it works on Android |
|---|---|---|
| **One Sec** | Breathing pause before a chosen app opens, then "continue or close" | **AccessibilityService** catches the app opening and shows an **overlay**. There's also an older "Usage Access" mode that polls, which is slower and less reliable. They say all processing stays on the device. |
| **ScrollToll** | Counts short videos (Reels, Shorts, TikTok), blocks the feed at a daily limit, and gives +10 swipes after box breathing (3 times a day max) | **AccessibilityService** detects swipes inside the feeds and a full-screen overlay blocks them. Says it reads "no content, no keystrokes". |
| **Reel Counter** | Reel count and watch time on Instagram | Accessibility permission, data stored on the device. |
| **ScreenZen** | Pause before opening, and **the wait gets longer each time**, daily limits, schedules by weekday and hour | Accessibility plus usage stats. |
| **FeurStagram** (open source) | Patched Instagram APK that blocks Feed, Explore and Reels, with toggles inside the app and telemetry blocked | Smali patches to the real Instagram app. Network-level blocking. |
| **InstaEclipse** (Xposed module) | Hooks into Instagram while it runs | Needs root plus LSPosed. |
| **FocusGram / JustAgram** | "Distraction-free" Instagram | Wrappers around instagram.com (web). |

**Takeaway:** every non-root monitoring app uses **AccessibilityService plus an overlay**. That's the standard approach, and it's what we'll use.

---

## 2. Playback speed: the hard part

Instagram does not let other apps control its video player. The options, from least to most invasive:

| Option | Max speed | Root? | Works in the real Instagram app? | Risk / effort |
|---|---|---|---|---|
| **A. Simulated "hold" gesture** — the accessibility service uses `dispatchGesture` to press and hold the screen edge, the same thing you do by hand for 2x | **2x only** (whatever Instagram offers) | No | Yes | Fragile. Any real touch cancels an injected gesture, so it has to be re-applied after every swipe. Gesture length is capped, so it has to be chained with `continueStroke` (API 26+). It may also hide or change Instagram's UI while held. **Experimental.** |
| **B. Built-in "Reels player" (WebView of instagram.com)** — our own screen that loads Instagram's mobile web and sets `video.playbackRate` | **Any speed (0.25x–16x)** | No | No, it's Instagram's web version inside our app | Low risk (it's Instagram's official site). Counting is exact (we see every `<video>`). Downsides: the web UI is weaker, the selectors need upkeep when Instagram changes its site, and this part needs internet access. |
| **C. LSPosed hook** on Instagram's player (`setPlaybackParameters` / ExoPlayer) | Any | **Yes** | Yes, the native app | Breaks when Instagram updates, needs root, and may be detected. |
| **D. Patched Instagram APK** (FeurStagram-style smali patch) | Any | No | A modified Instagram | Against Instagram's terms (account risk), has to be re-patched after every update, and may fail Play Integrity checks. **Not recommended.** |

**Recommendation:** ship **A as a "2x lock" toggle** (best effort, native app) and **B as the real speed controller** for anything above 2x. Offer C later only if the phone is rooted. Skip D.

Speed controls to copy from Video Speed Controller (igrigorik): range 0.07x–16x, remembered speed, quick toggle between 1x and my preferred speed, step up/down, reset, skip ±N seconds, and a speed label on the video.

---

## 3. Android platform constraints (2026)

- **Restricted settings (Android 13+, wider in 15).** A sideloaded app can't turn on Accessibility, Usage Access or Display-over-other-apps until you go to **Settings → Apps → ReelRein → ⋮ → Allow restricted settings**. You only do this once.
- **Developer verification (rolling out from Sept 30, 2026, starting with BR/ID/SG/TH, worldwide in 2027).** **Installs over `adb install` are exempt**, so for a personal app installed over USB/wireless ADB from Android Studio this doesn't matter. The non-ADB "advanced flow" has a 24-hour wait. There are also free "limited distribution" accounts (up to 20 devices).
- **Advanced Protection Mode (Android 17).** It removes accessibility access from apps that aren't real accessibility tools (`isAccessibilityTool="true"` is only for screen readers, switch input and similar). **Leave Advanced Protection off on this phone**, or the app stops working. Don't set `isAccessibilityTool` to get around it; that flag is reserved for real accessibility tools.
- **Overlays:** an accessibility service can draw `TYPE_ACCESSIBILITY_OVERLAY` windows **without** the separate "Display over other apps" permission, so only **one** permission is needed.
- **Instagram view IDs are obfuscated or undocumented and change between versions.** Reel detection has to rely on a few signals (full-screen vertical pager, scroll distance about equal to screen height, a small set of resource IDs found with a `uiautomator dump`) and stay in one config file so it's easy to fix after an update.

---

## 4. Privacy and resource-light design

| Principle | How |
|---|---|
| **Nothing leaves the phone** | The core monitor module has **no `INTERNET` permission in its manifest**, so it can't send anything. No Firebase, no analytics, no crash reporting. The Option-B WebView player, if built, goes in a **separate small app or module** so the monitor keeps no network access. |
| **Read as little as possible** | Only read **window and scroll events and view class/ID names**. Never read captions, usernames, DM text or any `getText()` content. Store **counts and durations only**. |
| **Zero cost when Instagram is closed** | Accessibility config subscribes only to `typeWindowStateChanged` by default. When Instagram comes to the front, switch on `typeViewScrolled` with `setServiceInfo()`, and switch it off again when Instagram leaves. `notificationTimeout` about 100 ms to batch events. |
| **No polling, no extra foreground service** | The accessibility service already stays alive. Timers are only a `Handler` while Instagram is in front. No `AlarmManager`, no WorkManager loops. |
| **Light UI in overlays** | Plain Android `View`s for the HUD and pause screen (Compose in an overlay needs lifecycle workarounds and costs more memory). Compose only for the settings and stats screens inside the app. |
| **Small storage** | Room (SQLite) with one row per session plus daily totals, or DataStore for settings. `allowBackup=false`. Manual **local** JSON/CSV export only. |
| **Optional cross-check** | `UsageStatsManager` (Usage Access) can confirm daily Instagram time without accessibility. It's optional and off by default. |

---

## 5. Feature ideas gathered (prioritized in [PLAN.md](PLAN.md))

**Before opening** (One Sec / ScreenZen)
- Breathing or countdown pause, configurable from 3 to 30 seconds
- **Wait gets longer** with each open today (5s → 10s → 20s…)
- "Why are you opening Instagram?" chips: DMs / Post / Just browsing. Picking "DMs" can start **DMs-only mode**.
- Shows "This is your **7th** open today · 42 min so far"
- Pick a session length (5 / 10 / 15 / custom), or close (goes Home)
- Quiet hours (for example no Instagram after 23:00) and a weekday/hour schedule

**During use**
- HUD pill above Reels: `🎬 12 · today 148 · 9:42 · 2.0x`. Draggable, tap to cycle speed, long-press to hide. Shown only on Reels surfaces.
- Reminder when the chosen time runs out: "Time's up" overlay with *Close Instagram* / *+5 min* (+5 limited to N per day)
- **Reel budget** per session and per day. When it runs out, Reels are blocked (overlay or Back action); box breathing earns a few more, limited per day (ScrollToll)
- **Doom-scroll detection:** more than X reels in Y minutes (for example 30 in 5 min) shows a gentle nudge
- **Grayscale while in Instagram.** Needs a one-time `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`, then turns on the system daltonizer only while Instagram is in front
- **Block Reels tab / Explore** (Back action when detected), as an optional "DMs-only" mode

**Speed**
- Default Reels speed, a quick 1x ↔ preferred toggle, presets 1 / 1.25 / 1.5 / 2 (native) and up to 4x or more (Reels player)
- Separate speed per surface (Reels / Stories / Feed videos) in the Reels player

**After / insights**
- Stats screen: daily and weekly reels, time, number of opens, average session, longest session, heat map by hour
- Weekly summary notification (generated on the device)
- Quick Settings tile and home-screen widget (today's count)
- Turning protection off needs a 60-second wait first, so impulse-disabling is harder

---

## Sources
- [One Sec — Android permission setup](https://tutorials.one-sec.app/android-accessibility-permission) · [Using One Sec without Accessibility](https://feedback.one-sec.app/help/articles/6315890-can-i-use-one-sec-without-the-accessibility-permission) · [One Sec for Android](https://one-sec.app/android)
- [ScrollToll (Product Hunt)](https://producthunt.com/products/scrolltoll-2) · [ScrollToll stack](https://www.producthunt.com/products/scrolltoll-2/built-with) · [Reel Counter privacy page](https://sites.google.com/view/reelcounter/home) · [ScreenZen](https://apkcombo.com/screenzen%E3%83%BBscreen-time-control/com.screenzen/)
- [FeurStagram (awesome.ecosyste.ms)](https://awesome.ecosyste.ms/projects/github.com%2Fjean-voila%2Ffeurstagram) · [FeurStagram privacy doc](https://raw.githubusercontent.com/jean-voila/FeurStagram/master/docs/PRIVACY.md) · [InstaEclipse Xposed module](https://github.com/Xposed-Modules-Repo/ps.reso.instaeclipse) · [FocusGram](https://alternativeto.net/software/focusgram/about)
- [Instagram 2x hold-to-speed (Lilach Bullock)](https://www.lilachbullock.com/how-to-speed-up-instagram-reels-playback/) · [InstaSpeed Firefox add-on](https://addons.mozilla.org/fr/firefox/addon/instaspeed/)
- [Video Speed Controller (Chrome)](https://chromewebstore.google.com/detail/nffaoalbilbmmfgbnbgppjihopabppdk) · [Universal Media Speed Controller](https://addons.mozilla.org/addon/universal-media-speed-control/)
- [GestureDescription.StrokeDescription docs](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.StrokeDescription)
- [Android 15 sideloading restrictions (9to5Google)](https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/) · [Android 13 restricted settings (Bayton)](https://bayton.org/android/android-13-restricted-permissions/)
- [Developer verification rollout (9to5Google, Aug 2026)](https://9to5google.com/2026/08/18/google-gradually-rolling-out-androids-advanced-sideloading-ahead-of-developer-verification/) · [24-hour wait (The Hacker News)](https://thehackernews.com/2026/03/google-adds-24-hour-wait-for-unverified.html) · [Android Authority on the advanced flow](https://androidauthority.com/google-android-advanced-flow-sideloading-rollout-begins-3700073)
- [Android 17 Advanced Protection blocks non-accessibility tools (The Hacker News)](https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html) · [Android Authority teardown](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860)
