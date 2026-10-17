package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jonnyfrick.musicbootcamp.core.model.PracticeSettings
import io.github.jonnyfrick.musicbootcamp.core.practice.PracticeStatus
import io.github.jonnyfrick.musicbootcamp.core.practice.StepVerdict
import io.github.jonnyfrick.musicbootcamp.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * The practice screen's stage: a ring that runs through each step's breathing time around what
 * the app wants (or how the answer went), the streak of right answers, and a keyboard that shows
 * the keys of the latest answer.
 */
@Composable
internal fun PracticeStage(
    status: PracticeStatus,
    feedback: PracticeFeedback,
    running: Boolean,
    settings: PracticeSettings,
    showGivenNotes: Boolean,
    noteName: (Int) -> String,
) {
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(status.lastResult) {
        if (status.lastResult == null) return@LaunchedEffect
        holding = true
        delay(minOf(RESULT_HOLD_MILLIS, settings.stepPeriodMillis / 2))
        holding = false
    }
    val shown = shownResult(status, running, holding)
    val answer = feedback.answer?.takeIf { shown != null && it.verdict == shown }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingPadding, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                BreathRing(status.steps, running, settings, shown)
                StageCenter(shown, answer, running, status.given.takeIf { showGivenNotes }.orEmpty(), noteName)
            }
            VerticalSpace(14)
            StreakBar(feedback)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingPadding),
    ) {
        PianoKeys(
            range = settings.lowLimit..settings.highLimit,
            marked = if (running && showGivenNotes) status.given else emptyList(),
            answer = answer,
            noteName = noteName,
            modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally),
        )
    }
}

private const val RESULT_HOLD_MILLIS = 700L

/**
 * The latest result to show, or null for "listening" (or idle when stopped). [holding]: the
 * latest result came in less than [RESULT_HOLD_MILLIS] ago. After that it stays only while it is
 * about the current step, i.e. no newer step waits for its answer.
 */
internal fun shownResult(status: PracticeStatus, running: Boolean, holding: Boolean): StepVerdict? =
    status.lastResult?.takeIf { running && (holding || it.step == status.steps - 1) }

/**
 * The breathing time of the current step as a ring filling clockwise, restarted by every step;
 * the first part, in a lighter tone, is the late-answer tolerance in which the previous step can
 * still be answered. While a result shows, the ring takes its colour.
 */
@Composable
private fun BreathRing(steps: Int, running: Boolean, settings: PracticeSettings, shown: StepVerdict?) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(steps, running) {
        progress.snapTo(0f)
        if (running && steps > 0) progress.animateTo(1f, tween(settings.stepPeriodMillis.toInt(), easing = LinearEasing))
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val late = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val color = when {
        shown == null -> MaterialTheme.colorScheme.primary
        shown.correct -> MaterialTheme.extendedColors.correct
        else -> MaterialTheme.colorScheme.error
    }
    val lateFraction = (settings.lateAnswerToleranceMillis.toFloat() / settings.stepPeriodMillis).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val width = 12.dp.toPx()
        val inset = width / 2
        val arcSize = Size(size.width - width, size.height - width)
        val topLeft = Offset(inset, inset)
        drawCircle(track, radius = (size.minDimension - width) / 2, style = Stroke(width))
        if (running && lateFraction > 0f) {
            drawArc(late, -90f, 360f * lateFraction, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width))
        }
        if (progress.value > 0f) {
            drawArc(
                color, -90f, 360f * progress.value, useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
    }
}

/** Inside the ring: idle, listening (or the given notes), or the result with its keys. */
@Composable
private fun StageCenter(shown: StepVerdict?, answer: Answer?, running: Boolean, given: List<Int>, noteName: (Int) -> String) {
    AnimatedContent(
        targetState = shown,
        transitionSpec = { (fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.8f)) togetherWith fadeOut(tween(100)) },
        contentAlignment = Alignment.Center,
    ) { verdict ->
        val keys = answer?.takeIf { it.verdict == verdict }
        val names = { notes: List<Int> -> notes.distinct().joinToString(" ") { noteName(it) } }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.widthIn(max = 160.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            when {
                verdict == null && !running -> {
                    Icon(AppIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                    Text(
                        stringResource(Res.string.practice_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                verdict == null -> {
                    if (given.isNotEmpty()) {
                        val description = stringResource(Res.string.practice_given_notes)
                        Text(
                            names(given),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    } else {
                        // Listening: a gently breathing note, so the display is visibly alive.
                        val pulse by rememberInfiniteTransition().animateFloat(
                            initialValue = 0.45f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        )
                        Icon(
                            AppIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(52.dp).alpha(pulse).scale(0.92f + 0.08f * pulse),
                        )
                    }
                    Text(stringResource(Res.string.practice_listen), style = MaterialTheme.typography.titleMedium)
                }
                verdict.correct -> {
                    ResultIcon(AppIcons.Check, MaterialTheme.extendedColors.correctContainer, MaterialTheme.extendedColors.correct)
                    keys?.let { Text(names(it.given), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold) }
                    Text(
                        stringResource(Res.string.practice_correct),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.extendedColors.correct,
                    )
                }
                verdict.missed -> {
                    ResultIcon(AppIcons.Remove, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
                    Text(stringResource(Res.string.practice_missed), style = MaterialTheme.typography.titleMedium)
                    keys?.let {
                        Text(
                            stringResource(Res.string.practice_was, names(it.given)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    ResultIcon(AppIcons.Close, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
                    if (keys != null && keys.played.isNotEmpty()) {
                        val description = stringResource(Res.string.practice_answer, names(keys.played), names(keys.given))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                        ) {
                            Text(names(keys.played), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(Res.string.practice_instead_of),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(names(keys.given), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        keys?.let { intervalText(it) } ?: stringResource(Res.string.practice_wrong),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** For a single wrong note: how far off it was ("a semitone too high"); otherwise just "Wrong". */
@Composable
private fun intervalText(answer: Answer): String {
    val played = answer.played.distinct().singleOrNull()
    val given = answer.given.distinct().singleOrNull()
    if (played == null || given == null || played == given) return stringResource(Res.string.practice_wrong)
    val distance = abs(played - given)
    return when {
        distance == 1 && played > given -> stringResource(Res.string.practice_semitone_higher)
        distance == 1 -> stringResource(Res.string.practice_semitone_lower)
        played > given -> stringResource(Res.string.practice_semitones_higher, distance)
        else -> stringResource(Res.string.practice_semitones_lower, distance)
    }
}

@Composable
private fun ResultIcon(icon: ImageVector, container: Color, content: Color) {
    Box(Modifier.size(52.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(30.dp))
    }
}

/** The run of right answers (it jumps a little with each one), the best run, and the latest results as dots. */
@Composable
private fun StreakBar(feedback: PracticeFeedback) {
    val bump = remember { Animatable(1f) }
    LaunchedEffect(feedback.streak) {
        if (feedback.streak > 0) {
            bump.snapTo(1.3f)
            bump.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val streakDescription = stringResource(Res.string.streak_description, feedback.streak, feedback.best)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { contentDescription = streakDescription },
        ) {
            Icon(AppIcons.Flame, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        feedback.streak.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.scale(bump.value),
                    )
                    Text(
                        stringResource(Res.string.streak_in_a_row),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    stringResource(Res.string.streak_best, feedback.best),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        RecentResults(feedback.recent)
    }
}

/** The latest results in two rows of dots, newest last; empty places for a run just begun. */
@Composable
private fun RecentResults(recent: List<Boolean>) {
    val right = MaterialTheme.extendedColors.correct
    val wrong = MaterialTheme.colorScheme.error
    val empty = MaterialTheme.colorScheme.outlineVariant
    val slots = List(PracticeFeedback.RECENT - recent.size) { null } + recent
    val description = stringResource(Res.string.recent_results, recent.count { it }, recent.size)
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.clearAndSetSemantics { if (recent.isNotEmpty()) contentDescription = description },
    ) {
        slots.chunked(PracticeFeedback.RECENT / 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { result ->
                    val color = when (result) {
                        true -> right
                        false -> wrong
                        null -> empty
                    }
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                }
            }
        }
    }
}

private fun isBlack(note: Int) = note % 12 in BLACK_KEYS
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10)

/**
 * The exercise's range as a keyboard (widened to white keys at both ends). The keys of the
 * latest answer light up in the result's colour; after a wrong answer the right keys get a
 * green frame. [marked]: keys with a dot (the given notes when they are shown). Decorative for
 * screen readers: the result above says the same in words.
 */
@Composable
private fun PianoKeys(range: IntRange, marked: List<Int>, answer: Answer?, noteName: (Int) -> String, modifier: Modifier) {
    val low = if (isBlack(range.first)) range.first - 1 else range.first
    val high = if (isBlack(range.last)) range.last + 1 else range.last
    val whites = (low..high).filterNot(::isBlack)
    val colors = MaterialTheme.extendedColors
    val played = answer?.played.orEmpty()
    val playedColor = if (answer?.verdict?.correct == true) colors.correct else MaterialTheme.colorScheme.error
    val framed = if (answer != null && !answer.verdict.correct) answer.given else emptyList()
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()

    Canvas(modifier.widthIn(max = (whites.size * 28).dp).fillMaxWidth().height(96.dp).clearAndSetSemantics {}) {
        val keyWidth = size.width / whites.size
        val gap = 1.5.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        val frame = 3.dp.toPx()

        fun DrawScope.key(x: Float, width: Float, height: Float, note: Int, base: Color, dotColor: Color) {
            val fill = if (note in played) playedColor else base
            drawRoundRect(fill, Offset(x, 0f), Size(width, height), radius)
            if (note in framed) {
                drawRoundRect(
                    colors.correct, Offset(x + frame / 2, frame / 2), Size(width - frame, height - frame), radius, style = Stroke(frame),
                )
            }
            if (note in marked) drawCircle(dotColor, 3.dp.toPx(), Offset(x + width / 2, height - 22.dp.toPx()))
        }

        whites.forEachIndexed { index, note ->
            val x = index * keyWidth + gap / 2
            key(x, keyWidth - gap, size.height, note, colors.whiteKey, dot)
            if (note !in played) drawRoundRect(line, Offset(x, 0f), Size(keyWidth - gap, size.height), radius, style = Stroke(1.dp.toPx()))
            if (note % 12 == 0 && keyWidth >= 16.dp.toPx()) {
                val style = labelStyle.copy(color = if (note in played) Color.White else labelStyle.color)
                // "small C" is too wide for a key: then only its last word.
                val label = measurer.measure(noteName(note), style).takeIf { it.size.width <= keyWidth - gap }
                    ?: measurer.measure(noteName(note).substringAfterLast(' '), style)
                drawText(label, topLeft = Offset(x + (keyWidth - gap - label.size.width) / 2, size.height - label.size.height - 4.dp.toPx()))
            }
        }
        val blackWidth = keyWidth * 0.6f
        (low..high).filter(::isBlack).forEach { note ->
            val x = (whites.indexOf(note - 1) + 1) * keyWidth - blackWidth / 2
            key(x, blackWidth, size.height * 0.6f, note, colors.blackKey, Color.White)
        }
    }
}
