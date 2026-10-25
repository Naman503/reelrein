package com.naman.reelrein.ui

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naman.reelrein.R

/** A rounded card with a small icon + title header. */
@Composable
fun SectionCard(
    title: String,
    icon: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

/** Icon in a tinted circle, used as the leading element of rows. */
@Composable
fun IconBadge(icon: Int, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 40) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size((size * 0.5f).dp), tint = tint)
    }
}

@Composable
fun SettingRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun PrefSwitchRow(
    prefs: SharedPreferences,
    key: String,
    default: Boolean,
    icon: Int,
    title: String,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit = {},
): Boolean {
    var on by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    val toggle = { v: Boolean ->
        on = v
        prefs.edit().putBoolean(key, v).apply()
        onChange(v)
    }
    SettingRow(icon, title, subtitle, onClick = { toggle(!on) }) {
        Switch(checked = on, onCheckedChange = toggle)
    }
    return on
}

@Composable
fun PrefStepperRow(
    prefs: SharedPreferences,
    key: String,
    default: Int,
    range: IntRange,
    icon: Int,
    title: String,
    subtitle: String,
) {
    var v by remember { mutableIntStateOf(prefs.getInt(key, default)) }
    fun set(n: Int) {
        v = n.coerceIn(range)
        prefs.edit().putInt(key, v).apply()
    }
    SettingRow(icon, title, subtitle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { set(v - 1) }, enabled = v > range.first, modifier = Modifier.size(34.dp)) {
                Icon(painterResource(R.drawable.ic_remove), "Less", Modifier.size(18.dp))
            }
            Text(
                "$v",
                Modifier.width(32.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalIconButton(onClick = { set(v + 1) }, enabled = v < range.last, modifier = Modifier.size(34.dp)) {
                Icon(painterResource(R.drawable.ic_add), "More", Modifier.size(18.dp))
            }
        }
    }
}

/** A row that expands to show more content below it. */
@Composable
fun ExpandableRow(icon: Int, title: String, subtitle: String?, onOpen: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    SettingRow(icon, title, subtitle, onClick = {
        open = !open
        if (open) onOpen?.invoke()
    }) {
        Icon(
            painterResource(R.drawable.ic_expand_more),
            if (open) "Collapse" else "Expand",
            Modifier.rotate(angle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(open) { Column { content() } }
}

/** Big number with an icon and label, for the "Today" card. */
@Composable
fun StatTile(icon: Int, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp)) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Seven rounded bars, today highlighted with the accent gradient. */
@Composable
fun WeekBars(values: List<Float>, labels: List<String>, modifier: Modifier = Modifier) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val muted = MaterialTheme.colorScheme.surfaceContainerHighest
    val todayBrush = Brush.verticalGradient(listOf(Accent2, Accent))
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val n = values.size
            val gap = 10.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            values.forEachIndexed { i, v ->
                val h = (size.height * (v / max)).coerceAtLeast(4.dp.toPx())
                val left = i * (barW + gap)
                val topLeft = Offset(left, size.height - h)
                val radius = CornerRadius(8.dp.toPx())
                if (i == n - 1) {
                    drawRoundRect(todayBrush, topLeft, Size(barW, h), radius)
                } else {
                    drawRoundRect(muted, topLeft, Size(barW, h), radius)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEachIndexed { i, l ->
                Text(
                    l,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == labels.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (i == labels.lastIndex) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A tiny phone outline with a dot where the 2x press lands. */
@Composable
fun HoldPointPreview(x: Float, y: Float, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.size(width = 64.dp, height = 128.dp)) {
        val r = CornerRadius(12.dp.toPx())
        drawRoundRect(fill, cornerRadius = r)
        drawRoundRect(outline, cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        // right-side action column, so the dot can be kept clear of it
        for (i in 0 until 4) {
            drawCircle(outline.copy(alpha = 0.6f), 3.dp.toPx(), Offset(size.width * 0.86f, size.height * (0.55f + i * 0.09f)))
        }
        val c = Offset(size.width * x, size.height * y)
        drawCircle(Accent.copy(alpha = 0.3f), 10.dp.toPx(), c)
        drawCircle(Accent, 5.dp.toPx(), c)
    }
}
