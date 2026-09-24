package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun <T> RadioGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    Column {
        options.forEach { option ->
            val isEnabled = enabled(option)
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == selected, enabled = isEnabled, role = Role.RadioButton) { onSelect(option) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null, enabled = isEnabled)
                Spacer(Modifier.width(8.dp))
                Text(label(option), color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** A labelled slider over integers. */
@Composable
internal fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    valueText: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(140.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // Tick marks only where there are few values; wide ranges snap by rounding instead.
            steps = if (range.last - range.first <= 20) (range.last - range.first - 1).coerceAtLeast(0) else 0,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(valueText(value), Modifier.width(72.dp).padding(start = 8.dp))
    }
}

/**
 * A text field that only commits on Enter or when it loses focus, like the Java
 * text fields. [onCommit] returns the value actually applied, which the field then shows.
 */
@Composable
internal fun CommitTextField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    numeric: Boolean = true,
    onCommit: (String) -> String,
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { text = value }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { text = onCommit(text) }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused && text != value) text = onCommit(text)
            focused = it.isFocused
        },
    )
}

@Composable
internal fun LabeledRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
