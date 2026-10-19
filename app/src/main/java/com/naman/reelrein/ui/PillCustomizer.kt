package com.naman.reelrein.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naman.reelrein.HudConfig
import com.naman.reelrein.Keys
import com.naman.reelrein.R
import com.naman.reelrein.ReelReinService
import com.naman.reelrein.ReelReinService.Companion.Preview
import kotlin.math.roundToInt

/** "Counter pill" settings: live preview plus everything the pill and its panel can show. */
@Composable
fun PillSection(prefs: SharedPreferences, serviceOn: Boolean) {
    val ctx = LocalContext.current
    // Bumped on every change so the preview redraws from the saved settings.
    var version by remember { mutableIntStateOf(0) }
    val bump: (Boolean) -> Unit = { version++ }
    val config = remember(version) { HudConfig.from(prefs) }

    SectionCard("Counter pill", R.drawable.ic_visibility) {
        PrefSwitchRow(prefs, Keys.HUD, true, R.drawable.ic_visibility, "Show the counter pill", "Over Instagram while a session runs", onChange = bump)
        PrefSwitchRow(prefs, Keys.HUD_REELS_ONLY, false, R.drawable.ic_movie, "Only on Reels", "Hide it in DMs and the feed", onChange = bump)

        // Live preview on a mock reel
        Box(
            Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF3A2A55), Color(0xFF15121F), Color(0xFF0B0B10)))),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                "PREVIEW",
                Modifier.align(Alignment.BottomStart).padding(12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
            )
            PillMock(config, Modifier.padding(top = 18.dp))
        }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (!ReelReinService.preview(Preview.HUD)) {
                        Toast.makeText(ctx, "Turn on accessibility first", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = serviceOn,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_play_arrow), null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Show on screen")
            }
            TextButton(onClick = {
                prefs.edit().remove(Keys.HUD_X).remove(Keys.HUD_Y).apply()
                Toast.makeText(ctx, "Pill moved back to the top", Toast.LENGTH_SHORT).show()
            }) { Text("Reset position") }
        }
        Text(
            "Tip: with the pill on screen, drag it where you want it; it stays there.",
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExpandableRow(R.drawable.ic_tune, "What the pill shows", "Pick the numbers you want at a glance") {
            PrefSwitchRow(prefs, Keys.PILL_BRAIN, true, R.drawable.ic_self_improvement, "Brain pal", "The mascot at the start; its mood follows today's reels", onChange = bump)
            PrefSwitchRow(prefs, Keys.PILL_REEL_TIME, true, R.drawable.ic_schedule, "Time on reels", "This session", onChange = bump)
            PrefSwitchRow(prefs, Keys.PILL_RING, true, R.drawable.ic_timer, "Limit ring", "Fills as your limit is used, amber in the last minute", onChange = bump)
            PrefSwitchRow(prefs, Keys.PILL_REELS, true, R.drawable.ic_movie, "Reels this session", onChange = bump)
            PrefSwitchRow(prefs, Keys.PILL_TODAY_REELS, false, R.drawable.ic_today, "Reels today", "Shown as \"12 / 148\"", onChange = bump)
            PrefSwitchRow(prefs, Keys.PILL_TODAY_TIME, false, R.drawable.ic_today, "Time on reels today", onChange = bump)
            PrefSwitchRow(prefs, Keys.PILL_TIME_LEFT, false, R.drawable.ic_more_time, "Time left", "Until your limit", onChange = bump)
            PrefSwitchRow(prefs, Keys.PILL_SPEED, true, R.drawable.ic_fast_forward, "Speed badge", "2× or 1×, when the speed lock is on", onChange = bump)
        }

        ExpandableRow(R.drawable.ic_visibility, "Look", "Size, style, fading, time format") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceRow(prefs, Keys.PILL_SIZE, "medium", "Size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), bump)
                ChoiceRow(prefs, Keys.PILL_STYLE, "brain", "Style", listOf("brain" to "Brain pal", "solid" to "Solid", "glass" to "Glass", "outline" to "Outline"), bump)
                var fade by remember { mutableFloatStateOf(prefs.getInt(Keys.PILL_IDLE_ALPHA, 80).toFloat()) }
                Column {
                    Text(
                        if (fade >= 99.5f) "When idle: stays fully visible" else "When idle: fades to ${fade.roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = fade,
                        onValueChange = { fade = it },
                        onValueChangeFinished = {
                            prefs.edit().putInt(Keys.PILL_IDLE_ALPHA, fade.roundToInt()).apply()
                            version++
                        },
                        valueRange = 30f..100f,
                    )
                }
            }
            PrefSwitchRow(prefs, Keys.TIME_SECONDS, true, R.drawable.ic_schedule, "Show seconds", "\"4:05\" instead of \"4m\", everywhere", onChange = bump)
        }

        ExpandableRow(R.drawable.ic_play_arrow, "Tapping the pill", "What a tap does") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                ChoiceRow(
                    prefs, Keys.PILL_TAP, "panel", null,
                    listOf("panel" to "Opens panel", "speed" to "Toggles 2×", "nothing" to "Nothing"), bump,
                )
            }
        }

        ExpandableRow(R.drawable.ic_today, "Panel", "What you see when the pill opens") {
            PrefSwitchRow(prefs, Keys.PANEL_SESSION, true, R.drawable.ic_schedule, "This session", "Time on reels, reels, time left", onChange = bump)
            PrefSwitchRow(prefs, Keys.PANEL_TODAY, true, R.drawable.ic_today, "Today", "Reel time, reels and opens so far today", onChange = bump)
            PrefSwitchRow(prefs, Keys.PANEL_SPEED, true, R.drawable.ic_fast_forward, "1× / 2× switch", onChange = bump)
            PrefSwitchRow(prefs, Keys.PANEL_AUTO_SCROLL, true, R.drawable.ic_play_arrow, "Auto scroll switch", onChange = bump)
            PrefSwitchRow(prefs, Keys.PANEL_CHANGE_LIMIT, true, R.drawable.ic_more_time, "Change limit", "Pick a new limit mid-session", onChange = bump)
            PrefSwitchRow(prefs, Keys.PANEL_HIDE, true, R.drawable.ic_visibility, "Hide pill", "Until the next session", onChange = bump)
            PrefSwitchRow(prefs, Keys.PANEL_END, true, R.drawable.ic_home, "End session", "Closes Instagram", onChange = bump)
        }
    }
}

@Composable
private fun ChoiceRow(
    prefs: SharedPreferences,
    key: String,
    default: String,
    label: String?,
    options: List<Pair<String, String>>,
    onChange: (Boolean) -> Unit,
) {
    var value by remember { mutableStateOf(prefs.getString(key, default) ?: default) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (k, l) ->
                SegmentedButton(
                    selected = value == k,
                    onClick = {
                        value = k
                        prefs.edit().putString(key, k).apply()
                        onChange(true)
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(l, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/** A Compose look-alike of the real pill, fed with sample numbers. */
@Composable
private fun PillMock(c: HudConfig, modifier: Modifier = Modifier) {
    if (c.style == "brain") return BrainPillMock(c, modifier)
    val k = c.scale
    val bg = when (c.style) {
        "glass" -> Color(0x8C14141E)
        "outline" -> Color(0x5A000000)
        else -> Color(0xEE08080C)
    }
    val border = when (c.style) {
        "outline" -> BorderStroke(2.dp, Accent)
        "glass" -> BorderStroke(1.dp, Color.White.copy(alpha = 0.27f))
        else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    }
    val white = Color(0xFFF2F2FA)
    val dim = Color(0xB3E6E6F2)

    Surface(modifier, shape = RoundedCornerShape((26 * k).dp), color = bg, border = border) {
        Row(Modifier.padding(horizontal = (10 * k).dp, vertical = (7 * k).dp), verticalAlignment = Alignment.CenterVertically) {
            var groups = 0
            @Composable
            fun Divider() {
                if (groups++ > 0) {
                    Box(Modifier.padding(horizontal = (11 * k).dp).width(1.dp).height((20 * k).dp).background(Color.White.copy(alpha = 0.2f)))
                }
            }

            @Composable
            fun Num(t: String, size: Float, color: Color = white, bold: Boolean = true) {
                Text(t, color = color, fontSize = (size * k).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            }

            @Composable
            fun Ico(res: Int, color: Color, size: Int) {
                Icon(painterResource(res), null, Modifier.size((size * k).dp), tint = color)
                Spacer(Modifier.width((6 * k).dp))
            }

            if (c.brain) {
                groups++
                BrainIcon(0, (34 * k).toInt())
                Spacer(Modifier.width((4 * k).dp))
            }
            if (c.reelTime) {
                Divider()
                if (c.ring) {
                    Box(
                        Modifier.size((30 * k).dp).clip(CircleShape).background(Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.size((30 * k).dp)) {
                            val stroke = 2.2.dp.toPx()
                            drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                            drawArc(Accent, -90f, 216f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size((16 * k).dp), tint = white)
                    }
                    Spacer(Modifier.width((8 * k).dp))
                } else {
                    Ico(R.drawable.ic_schedule, white, 18)
                }
                Num("7:42", 17f)
            }
            if (c.todayTime) {
                Divider()
                Ico(R.drawable.ic_today, dim, 16)
                Num("1h 05m", 15f, dim, bold = false)
            }
            if (c.timeLeft) {
                Divider()
                Ico(R.drawable.ic_timer, white, 17)
                Num("5:18", 16f)
            }
            if (c.reels || c.todayReels) {
                Divider()
                Ico(R.drawable.ic_movie, white, 18)
                if (c.reels) Num("12", 17f)
                if (c.todayReels) {
                    if (c.reels) Spacer(Modifier.width((4 * k).dp))
                    Num(if (c.reels) "/ 148" else "148 today", 14f, dim, bold = false)
                }
            }
            if (c.speed) {
                if (groups > 0) Spacer(Modifier.width((11 * k).dp))
                groups++
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
                        .padding(horizontal = (10 * k).dp, vertical = (5 * k).dp),
                ) { Num("2×", 14f, Color.White) }
            }
            if (groups == 0) Ico(R.drawable.ic_movie, white, 18)
        }
    }
    // Faded state hint
    if (c.idleAlpha < 1f) {
        Text(
            "fades to ${(c.idleAlpha * 100).roundToInt()}% when idle",
            Modifier.padding(top = 70.dp).alpha(0.6f),
            color = Color.White,
            fontSize = 11.sp,
        )
    }
}

private val BrainCream = Color(0xFFFBE7BF)
private val BrainInk = Color(0xFF5C3311)

/** The brain pal pill: the real mascot (as a View) and a bold count on warm cream. */
@Composable
private fun BrainPillMock(c: HudConfig, modifier: Modifier = Modifier, mood: Int = 0, count: String = "14") {
    val k = c.scale
    Surface(modifier, shape = RoundedCornerShape((30 * k).dp), color = BrainCream, border = BorderStroke(1.dp, Color(0x3CA0702E)), shadowElevation = 6.dp) {
        Row(Modifier.padding(start = (8 * k).dp, end = (16 * k).dp, top = (6 * k).dp, bottom = (6 * k).dp), verticalAlignment = Alignment.CenterVertically) {
            BrainIcon(mood, (40 * k).toInt())
            Spacer(Modifier.width((8 * k).dp))
            Text(count, color = BrainInk, fontSize = (24 * k).sp, fontWeight = FontWeight.ExtraBold)
            if (c.reelTime) {
                Box(Modifier.padding(horizontal = (11 * k).dp).width(1.dp).height((20 * k).dp).background(BrainInk.copy(alpha = 0.25f)))
                Text("7:42", color = BrainInk, fontSize = (16 * k).sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun BrainIcon(mood: Int, sizeDp: Int, modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> com.naman.reelrein.overlay.BrainView(ctx) },
        update = { it.mood = mood },
        modifier = modifier.size(sizeDp.dp),
    )
}

/** Brain pal: the four moods and the daily reel cap. */
@Composable
fun BrainSection(prefs: SharedPreferences, todayReels: Int) {
    var cap by remember { mutableIntStateOf(prefs.getInt(Keys.REEL_CAP, 0)) }
    val mood = com.naman.reelrein.overlay.BrainView.moodFor(todayReels, cap)
    SectionCard("Brain pal", R.drawable.ic_self_improvement) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BrainIcon(mood, 64)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOf("Happy brain", "A bit tired", "Getting worried", "Brain is fried")[mood],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$todayReels reels today. Your brain pal's mood follows your reel count.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Happy", "Tired", "Worried", "Fried").forEachIndexed { i, label ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BrainIcon(i, 44, Modifier.alpha(if (i == mood) 1f else 0.55f))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp)) {
            Text("Daily reel cap", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 50 to "50", 100 to "100", 150 to "150", 200 to "200", 300 to "300").forEach { (v, label) ->
                    FilterChip(
                        selected = cap == v,
                        onClick = {
                            cap = v
                            prefs.edit().putInt(Keys.REEL_CAP, v).apply()
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                if (cap == 0) "No cap. The brain still shows how your day is going." else "At $cap reels, Instagram pauses with \"Your brain is full\". You can keep going after a short wait; it checks in again every 25 reels.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
