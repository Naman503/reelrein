package com.naman.reelrein

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naman.reelrein.ReelReinService.Companion.Preview
import com.naman.reelrein.ui.AccentBrush
import com.naman.reelrein.ui.ExpandableRow
import com.naman.reelrein.ui.Good
import com.naman.reelrein.ui.HoldPointPreview
import com.naman.reelrein.ui.IconBadge
import com.naman.reelrein.ui.BrainSection
import com.naman.reelrein.ui.PillSection
import com.naman.reelrein.ui.PrefSwitchRow
import com.naman.reelrein.ui.ReelReinTheme
import com.naman.reelrein.ui.SectionCard
import com.naman.reelrein.ui.SettingRow
import com.naman.reelrein.ui.StatTile
import com.naman.reelrein.ui.Warn
import com.naman.reelrein.ui.WeekBars
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import com.naman.reelrein.telemetry.Usage
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Usage.start(this)
        fromNotification(intent)
        setContent {
            ReelReinTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(resumes.intValue)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        fromNotification(intent)
    }

    /** Opened by tapping bank-safe mode's notification. */
    private fun fromNotification(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_FROM) == FROM_NOTIFICATION) {
            Usage.count("notification_tap")
            intent.removeExtra(EXTRA_FROM)
        }
    }

    companion object {
        const val EXTRA_FROM = "from"
        const val FROM_NOTIFICATION = "notification"
    }

    private val a11yObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            resumes.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++ // re-read service state and stats when coming back from Settings
        LaunchWatcher.sync(this)
        Usage.count("app_open")
        Usage.flushSoon()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11yObserver,
        )
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(a11yObserver)
        super.onPause()
    }
}

@Composable
private fun HomeScreen(refreshKey: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Keys.prefs(ctx) }
    val stats = remember { StatsStore(ctx) }
    val serviceOn = remember(refreshKey) { isServiceEnabled(ctx) }
    val batteryOk = remember(refreshKey) { isBatteryUnrestricted(ctx) }
    var bankSafe by remember { mutableStateOf(prefs.getBoolean(Keys.BANK_SAFE, false)) }
    val bankReady = remember(refreshKey) { LaunchWatcher.ready(ctx) }
    // In bank-safe mode the service is off by design while Instagram is closed.
    val protectedNow = serviceOn || (bankSafe && bankReady)
    val days = remember(refreshKey) { stats.lastDays(7) }
    var noticeSeen by remember { mutableStateOf(prefs.getBoolean(Keys.USAGE_NOTICE, false)) }
    var sharing by remember { mutableStateOf(prefs.getBoolean(Keys.SHARE_USAGE, true)) }
    // Setup steps reached (each sent once per install, if sharing is on).
    LaunchedEffect(refreshKey) {
        if (batteryOk) Usage.once("setup_battery")
        if (bankReady) Usage.once("setup_bank_safe")
        if (serviceOn && batteryOk) Usage.once("setup_complete") else Usage.once("setup_card_seen")
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        SetupCard(protectedNow, batteryOk, sleeping = bankSafe && bankReady && !serviceOn)
        if (!noticeSeen) {
            UsageNotice(
                sharing = sharing,
                onOk = {
                    Usage.once("usage_notice_ok")
                    noticeSeen = true
                    prefs.edit().putBoolean(Keys.USAGE_NOTICE, true).apply()
                },
                onTurnOff = {
                    sharing = false
                    noticeSeen = true
                    prefs.edit().putBoolean(Keys.SHARE_USAGE, false).putBoolean(Keys.USAGE_NOTICE, true).apply()
                    Usage.turnedOff()
                },
            )
        }
        TodayCard(days)

        SectionCard("Before Instagram opens", R.drawable.ic_self_improvement) {
            PrefSwitchRow(prefs, Keys.INTERCEPT, true, R.drawable.ic_pause_circle, "Pause screen", "Breathe for a moment, then pick a time limit")
            PauseLengthChips(prefs)
            RelockChips(prefs)
            PrefSwitchRow(prefs, Keys.ESCALATE, true, R.drawable.ic_trending_up, "Longer each time", "+3 s for every open today, up to 30 s")
            PreviewButtons(serviceOn, listOf("Pause screen" to Preview.PAUSE, "Time's up" to Preview.TIMES_UP))
        }

        BrainSection(prefs, days.first().reels)

        PillSection(prefs, serviceOn)

        SectionCard("Playback", R.drawable.ic_fast_forward) {
            PrefSwitchRow(
                prefs, Keys.AUTO_SCROLL, false, R.drawable.ic_play_arrow, "Auto scroll",
                "Switches on Instagram's own Auto scroll (More → Playback) at the start of every session",
            )
            PrefSwitchRow(
                prefs, Keys.SPEED_LOCK, false, R.drawable.ic_fast_forward, "Keep reels at 2×",
                "Holds Instagram's own 2× for you. Swipe as usual; tap the pill for 1× on one reel.",
            )
            SpeedMethodPicker(prefs)
            ExpandableRow(R.drawable.ic_tune, "Adjust the press point", "If 2× doesn't start or a button gets pressed", onOpen = { Usage.ui("open_press_point") }) {
                HoldPointEditor(prefs)
            }
        }

        SectionCard("Banking apps", R.drawable.ic_lock) {
            BankSafeSection(bankSafe, bankReady) { on ->
                bankSafe = on
                prefs.edit().putBoolean(Keys.BANK_SAFE, on).apply()
                LaunchWatcher.sync(ctx, switchedOff = !on)
            }
        }

        SectionCard("Advanced", R.drawable.ic_code) {
            PrefSwitchRow(prefs, Keys.HAPTICS, true, R.drawable.ic_touch_app, "Haptics", "Little vibrations on the pause screen and pill")
            PrefSwitchRow(prefs, Keys.DEBUG_LOG, false, R.drawable.ic_code, "Debug log", "adb logcat -s ReelRein · view IDs only, never text")
        }

        SectionCard("Privacy", R.drawable.ic_verified_user) {
            // key(sharing): the row re-reads the setting when the notice above turned it off.
            androidx.compose.runtime.key(sharing) {
                PrefSwitchRow(
                    prefs, Keys.SHARE_USAGE, true, R.drawable.ic_verified_user, "Share anonymous usage",
                    "Helps decide what to improve. Turning it off deletes what was sent.",
                    onChange = { on ->
                        sharing = on
                        if (on) Usage.turnedOn() else Usage.turnedOff()
                    },
                )
            }
            ExpandableRow(R.drawable.ic_visibility, "What's sent, and what never is", null, onOpen = { Usage.ui("open_usage_details") }) {
                UsageDetails()
            }
        }

        PrivacyFooter(sharing)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header() {
    Row(Modifier.padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFFFBE7BF)),
            contentAlignment = Alignment.Center,
        ) {
            com.naman.reelrein.ui.BrainIcon(0, 40)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("ReelRein", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Instagram, on your terms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupCard(serviceOn: Boolean, batteryOk: Boolean, sleeping: Boolean) {
    val ctx = LocalContext.current
    val allGood = serviceOn && batteryOk
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = if (allGood) Good.copy(alpha = 0.10f) else Warn.copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (allGood) R.drawable.ic_verified_user else R.drawable.ic_warning, if (allGood) Good else Warn, 44)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (allGood) "Protection is on" else "Finish setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            !allGood -> "A couple of switches so ReelRein can run"
                            sleeping -> "Asleep until Instagram opens (bank-safe mode)"
                            else -> "Watching for Instagram in the background"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!allGood) Spacer(Modifier.height(8.dp))
            if (!serviceOn) {
                SetupStep(
                    R.drawable.ic_accessibility, "Turn on accessibility",
                    "Greyed out? App info → ⋮ → Allow restricted settings",
                    "Open",
                ) {
                    Usage.ui("setup_accessibility")
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            if (!batteryOk) {
                SetupStep(
                    R.drawable.ic_bolt, "Allow background running",
                    "Stops the phone from switching ReelRein off",
                    "Allow",
                ) {
                    Usage.ui("setup_battery")
                    requestBatteryExemption(ctx)
                }
            }
            ExpandableRow(R.drawable.ic_lock, "Keep it alive on iQOO", "If it still turns itself off", onOpen = { Usage.ui("open_keep_alive") }) {
                Text(
                    "1. Recents: pull down on ReelRein's card to lock it\n" +
                        "2. Settings → Battery → Background power consumption management → ReelRein → Allow\n" +
                        "3. Settings → Apps → Autostart → ReelRein on\n" +
                        "4. Keep Advanced Protection off",
                    Modifier.padding(start = 74.dp, end = 20.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        Usage.ui("keep_alive_app_info")
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    },
                    modifier = Modifier.padding(start = 62.dp),
                ) { Text("Open app info") }
            }
        }
    }
}

@Composable
private fun SetupStep(icon: Int, title: String, subtitle: String, action: String, onClick: () -> Unit) {
    SettingRow(icon, title, subtitle, onClick = onClick) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text(action) }
    }
}

@Composable
private fun TodayCard(days: List<DayStat>) {
    val today = days.first()
    var metric by rememberSaveable { mutableIntStateOf(0) } // 0 = reels, 1 = minutes on reels
    SectionCard("Today", R.drawable.ic_today) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(R.drawable.ic_schedule, formatDuration(today.reelMs), "on reels", Modifier.weight(1f))
            StatTile(R.drawable.ic_movie, today.reels.toString(), "reels", Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(R.drawable.ic_today, formatDuration(today.ms), "in Instagram", Modifier.weight(1f))
            StatTile(R.drawable.ic_login, today.opens.toString(), "opens", Modifier.weight(1f))
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Last 7 days", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                listOf("Reels", "Time").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = metric == i,
                        onClick = {
                            if (metric != i) Usage.ui(if (i == 0) "stats_reels" else "stats_time")
                            metric = i
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        val chrono = days.reversed()
        val values = chrono.map { if (metric == 0) it.reels.toFloat() else it.reelMs / 60_000f }
        val labels = chrono.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
        WeekBars(values, labels, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        val total = if (metric == 0) "${chrono.sumOf { it.reels }} reels" else "${formatDuration(chrono.sumOf { it.reelMs })} on reels"
        Text(
            "$total this week",
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PauseLengthChips(prefs: android.content.SharedPreferences) {
    var secs by remember { mutableIntStateOf(prefs.getInt(Keys.PAUSE_SECONDS, 5)) }
    Column(Modifier.padding(start = 74.dp, end = 20.dp, bottom = 4.dp)) {
        Text("Pause length", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 5, 10, 15, 20).forEach { s ->
                FilterChip(
                    selected = secs == s,
                    onClick = {
                        secs = s
                        prefs.edit().putInt(Keys.PAUSE_SECONDS, s).apply()
                    },
                    label = { Text("${s}s") },
                )
            }
        }
    }
}

@Composable
private fun RelockChips(prefs: android.content.SharedPreferences) {
    var secs by remember { mutableIntStateOf(prefs.getInt(Keys.RELOCK_SECONDS, ReelReinService.DEFAULT_RELOCK_S)) }
    Column(Modifier.padding(start = 74.dp, end = 20.dp, top = 6.dp, bottom = 4.dp)) {
        Text("Pause again after closing Instagram for", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Right away", 10 to "10 s", 30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (s, label) ->
                FilterChip(
                    selected = secs == s,
                    onClick = {
                        secs = s
                        prefs.edit().putInt(Keys.RELOCK_SECONDS, s).apply()
                    },
                    label = { Text(label) },
                )
            }
        }
        Text(
            if (secs == 0) "Every time you open Instagram, you get the pause screen." else "Coming back sooner continues the same session (handy for share sheets and links).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewButtons(serviceOn: Boolean, items: List<Pair<String, Preview>>) {
    val ctx = LocalContext.current
    Row(Modifier.padding(start = 74.dp, end = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, which) ->
            OutlinedButton(
                onClick = {
                    Usage.ui(if (which == Preview.PAUSE) "preview_pause" else "preview_times_up")
                    if (!ReelReinService.preview(which)) {
                        Toast.makeText(ctx, "Turn on accessibility first", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = serviceOn,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_play_arrow), null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun HoldPointEditor(prefs: android.content.SharedPreferences) {
    var x by remember { mutableFloatStateOf(prefs.getFloat(Keys.HOLD_X, IgSignatures.DEFAULT_HOLD_X)) }
    var y by remember { mutableFloatStateOf(prefs.getFloat(Keys.HOLD_Y, IgSignatures.DEFAULT_HOLD_Y)) }
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HoldPointPreview(x, y)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Across · ${(x * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(x, { x = it }, valueRange = 0.55f..0.98f, onValueChangeFinished = {
                prefs.edit().putFloat(Keys.HOLD_X, x).apply()
                Usage.ui("press_point_moved")
            })
            Text("Down · ${(y * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(y, { y = it }, valueRange = 0.15f..0.80f, onValueChangeFinished = {
                prefs.edit().putFloat(Keys.HOLD_Y, y).apply()
                Usage.ui("press_point_moved")
            })
            TextButton(onClick = {
                x = IgSignatures.DEFAULT_HOLD_X
                y = IgSignatures.DEFAULT_HOLD_Y
                prefs.edit().remove(Keys.HOLD_X).remove(Keys.HOLD_Y).apply()
                Usage.ui("press_point_reset")
            }) { Text("Reset") }
        }
    }
}

@Composable
private fun SpeedMethodPicker(prefs: android.content.SharedPreferences) {
    var method by remember { mutableStateOf(prefs.getString(Keys.SPEED_METHOD, "lock") ?: "lock") }
    var dir by remember { mutableStateOf(prefs.getString(Keys.LOCK_DIRECTION, "down") ?: "down") }
    Column(Modifier.padding(start = 74.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("How", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("lock" to "Lock", "hold" to "Hold").forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = method == key,
                    onClick = {
                        method = key
                        prefs.edit().putString(Keys.SPEED_METHOD, key).apply()
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    icon = {},
                ) { Text(label) }
            }
        }
        Text(
            if (method == "lock") {
                "Press, hold and slide once per reel, so Instagram locks 2× itself. Your taps don't affect it."
            } else {
                "Keeps a finger-press held down. Use it if your Instagram has no 2× lock. Any tap drops to 1× until the next reel."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (method == "lock") {
            Text("Slide to lock", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("down" to "Down", "up" to "Up").forEach { (key, label) ->
                    FilterChip(
                        selected = dir == key,
                        onClick = {
                            dir = key
                            prefs.edit().putString(Keys.LOCK_DIRECTION, key).apply()
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BankSafeSection(on: Boolean, ready: Boolean, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Usage.event("notif_permission", mapOf("granted" to granted.toString()))
    }
    val usageOk = LaunchWatcher.hasUsageAccess(ctx)
    val secureOk = A11yToggle.canToggle(ctx)
    SettingRow(
        R.drawable.ic_verified_user,
        "Only run while Instagram is open",
        "Banking apps that refuse to open with accessibility on will work normally. ReelRein switches itself on when Instagram opens.",
        onClick = if (ready) ({ onToggle(!on) }) else null,
    ) {
        androidx.compose.material3.Switch(
            checked = on && ready,
            enabled = ready,
            onCheckedChange = { v ->
                if (v && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                onToggle(v)
            },
        )
    }
    if (!ready) {
        Column(Modifier.padding(start = 74.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Needs two one-time permissions:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CheckLine(usageOk, "Usage access, to notice Instagram opening") {
                OutlinedButton(
                    onClick = {
                        Usage.ui("usage_access_grant")
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("Grant") }
            }
            CheckLine(secureOk, "Permission to switch itself on and off (granted once from a computer)") {}
            if (!secureOk) {
                val cmd = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cmd,
                            Modifier.weight(1f).padding(vertical = 10.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        )
                        TextButton(onClick = {
                            Usage.ui("adb_copy")
                            ctx.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("adb command", cmd))
                        }) { Text("Copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckLine(done: Boolean, text: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (done) R.drawable.ic_check else R.drawable.ic_remove),
            null,
            Modifier.size(18.dp),
            tint = if (done) Good else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (!done) action()
    }
}

@Composable
private fun PrivacyFooter(sharing: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            if (sharing) "Nothing on your screen leaves this phone · anonymous usage on"
            else "Nothing leaves this phone · anonymous usage off",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown once: says plainly that usage sharing is on, what it is, and offers to turn it off. */
@Composable
private fun UsageNotice(sharing: Boolean, onOk: () -> Unit, onTurnOff: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Anonymous usage sharing", style = MaterialTheme.typography.titleMedium)
            Text(
                "ReelRein sends anonymous counts (how often the pause screen shows, reels and minutes per day, " +
                    "session lengths in ranges, which features and settings you use, errors and crashes) with a random id. Never what's on screen: no captions, " +
                    "accounts, messages or which reels. You can turn it off any time in Privacy below, and that " +
                    "deletes what was sent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOk) { Text(if (sharing) "OK, keep it on" else "OK") }
                if (sharing) OutlinedButton(onClick = onTurnOff) { Text("Turn off") }
            }
        }
    }
}

@Composable
private fun UsageDetails() {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val sent = listOf(
            "Daily counts: Instagram opens, pause screens and what you chose at each step, reels, minutes on Reels and in Instagram, time's up and reel-cap screens",
            "Sessions in ranges: how long, time on Reels, reels (like 6–20), whether a limit was set and how it ended; yesterday's totals and streaks the same way",
            "Settings as fixed choices (on/off, \"Glass\", \"50\" reel cap), which buttons and sections of ReelRein you use, and your limit in ranges like 6–10 min",
            "Whether ReelRein's own steps worked: 2× lock, Auto scroll, finding the Reels screen (as a category, like \"no reels seen\"), the accessibility service stopping or restarting",
            "Setup steps reached, which permissions are granted (yes/no), app version, Android version, phone model and language",
            "Crashes and errors: the error type and where in ReelRein's code, never screen text",
        )
        val never = listOf(
            "Anything on screen in Instagram: captions, comments, usernames, messages, which reels",
            "Your account, contacts, location or advertising id",
        )
        Text("Sent, with a random id made on this phone:", style = MaterialTheme.typography.labelLarge)
        sent.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Never sent:", style = MaterialTheme.typography.labelLarge)
        never.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "Reports go to api.np-dev.duckdns.org at most every 6 hours. Your IP is used for the country only and isn't stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Reads the switch itself rather than the list of bound services: right after an update the
 * service takes a moment to reconnect, and the bound list is briefly empty.
 */
private fun isServiceEnabled(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')
        ?.any { it.startsWith("${ctx.packageName}/") } == true

private fun isBatteryUnrestricted(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

@SuppressLint("BatteryLife") // personal, sideloaded app: asking once for the exemption is the point
private fun requestBatteryExemption(ctx: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(direct) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
