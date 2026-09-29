package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The building blocks of every settings screen, after Material 3's list and settings patterns:
 * one row per setting with a headline, the current value and an optional explanation below,
 * the whole row as touch target (at least 48 dp) and with the semantics of its control.
 */

/** Horizontal padding of everything in a settings column, aligned with [ListItem]'s content. */
internal val SettingPadding = 16.dp

/** Transparent list items, so they sit on any surface (sheets, panes, cards). */
@Composable
private fun itemColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

/** A group of settings under a small heading in the primary colour. */
@Composable
internal fun SettingsSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = SettingPadding, end = SettingPadding, top = 16.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
        }
        content()
    }
}

/** An explanation that belongs to the settings around it rather than to one of them. */
@Composable
internal fun SettingHint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 4.dp),
    )
}

/** A setting that is on or off; the whole row toggles it. */
@Composable
internal fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
    icon: ImageVector? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = itemColors(),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/**
 * A number on a slider, snapped to [step]. With [fineSteps] it also gets − and + buttons, for
 * exact values on small screens where a slider over many values is too coarse for a finger.
 */
@Composable
internal fun SliderSetting(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    valueText: String,
    onChange: (Double) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
    fineSteps: Boolean = false,
) {
    fun snap(raw: Double) = (range.start + ((raw - range.start) / step).roundToInt() * step).coerceIn(range)
    val count = ((range.endInclusive - range.start) / step).roundToInt()
    Column(Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (fineSteps) {
                IconButton(onClick = { onChange(snap(value - step)) }, enabled = enabled && value > range.start) {
                    Icon(AppIcons.Remove, stringResource(Res.string.decrease, title))
                }
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { raw -> snap(raw.toDouble()).let { if (abs(it - value) > step / 2) onChange(it) } },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                // Tick marks only where there are few values; otherwise snapping is enough.
                steps = if (count <= 20) (count - 1).coerceAtLeast(0) else 0,
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = title
                    stateDescription = valueText
                },
            )
            if (fineSteps) {
                IconButton(onClick = { onChange(snap(value + step)) }, enabled = enabled && value < range.endInclusive) {
                    Icon(AppIcons.Add, stringResource(Res.string.increase, title))
                }
            }
        }
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [SliderSetting] over whole numbers. */
@Composable
internal fun IntSliderSetting(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    step: Int = 1,
    valueText: String = value.toString(),
    supporting: String? = null,
    fineSteps: Boolean = false,
) = SliderSetting(
    title = title,
    value = value.toDouble(),
    range = range.first.toDouble()..range.last.toDouble(),
    step = step.toDouble(),
    valueText = valueText,
    onChange = { onChange(it.roundToInt()) },
    enabled = enabled,
    supporting = supporting,
    fineSteps = fineSteps,
)

/** A few options shown at once, each a row with a radio button. */
@Composable
internal fun <T> RadioSetting(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            val isEnabled = enabled(option)
            ListItem(
                headlineContent = { Text(label(option)) },
                leadingContent = { RadioButton(selected = option == selected, onClick = null, enabled = isEnabled) },
                colors = if (isEnabled) itemColors() else ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
                modifier = Modifier.selectable(selected = option == selected, enabled = isEnabled, role = Role.RadioButton) { onSelect(option) },
            )
        }
    }
}

/**
 * One of many options (devices, for example): the row shows the current one and opens a dialog
 * to choose, as Material's settings do on phones and desktops alike.
 */
@Composable
internal fun <T> ChoiceSetting(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    val active = enabled && options.isNotEmpty()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(label(selected)) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Icon(AppIcons.ArrowDropDown, contentDescription = null) },
        colors = if (active) itemColors() else ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            supportingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        modifier = Modifier.selectable(selected = false, enabled = active, role = Role.DropdownList) { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        ListItem(
                            headlineContent = { Text(label(option)) },
                            leadingContent = { RadioButton(selected = option == selected, onClick = null) },
                            colors = itemColors(),
                            modifier = Modifier.selectable(selected = option == selected, role = Role.RadioButton) {
                                open = false
                                onSelect(option)
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** A row that leads to another page (settings categories). */
@Composable
internal fun NavigationSetting(
    title: String,
    summary: String?,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it, maxLines = 1) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            itemColors()
        },
        modifier = Modifier.selectable(selected = selected, role = Role.Button, onClick = onClick),
    )
}

/** Buttons side by side that wrap to the next line on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 8.dp),
        content = content,
    )
}

@Composable
internal fun VerticalSpace(height: Int = 8) = Spacer(Modifier.height(height.dp))

/**
 * A screen's scrolling content, centred and no wider than a comfortable reading width on large
 * windows; [bottomSpace] keeps the last item clear of a floating action button.
 */
@Composable
internal fun CenteredColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 640.dp,
    bottomSpace: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(top = 8.dp, bottom = bottomSpace), content = content)
    }
}
