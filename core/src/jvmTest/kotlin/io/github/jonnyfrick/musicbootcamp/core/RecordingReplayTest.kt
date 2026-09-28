package io.github.jonnyfrick.musicbootcamp.core

import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingEvent
import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingEventType
import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingLog
import io.github.jonnyfrick.musicbootcamp.core.audio.Wav
import io.github.jonnyfrick.musicbootcamp.core.audio.WavAudio
import io.github.jonnyfrick.musicbootcamp.core.midi.NoteNames
import io.github.jonnyfrick.musicbootcamp.core.midi.Tuning
import io.github.jonnyfrick.musicbootcamp.core.persistence.SetupRepository
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordDetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.ChordTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.DetectionParameters
import io.github.jonnyfrick.musicbootcamp.core.pitch.HopTrace
import io.github.jonnyfrick.musicbootcamp.core.pitch.LearnedTemplates
import io.github.jonnyfrick.musicbootcamp.core.pitch.NoteTracker
import io.github.jonnyfrick.musicbootcamp.core.pitch.fft
import io.github.jonnyfrick.musicbootcamp.core.pitch.toJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Analyses recorded sessions (Preferences → "Record exercises" or optimization mode): replays them
 * through the pitch detection with the parameters they were recorded with and writes, per recording,
 * to `core/build/analysis/<name>/`:
 * - `steps.txt`: per step the given note(s), what the player played, what was recognised live and
 *   now, and each mistake classified (missed, wrong note, octave, own sound, extra);
 * - `hops.csv`: every hop's levels, the predicted own sound, onset decisions, pitch and clarity;
 * - `spectrogram.png`: microphone, reference and predicted own sound, with steps and notes marked,
 *   plus the levels the onset decisions are based on.
 *
 * ```
 * ./gradlew :core:jvmTest --tests '*RecordingReplayTest*' --rerun -Pmusicbootcamp.recordings=<folder or .wav>
 *     [-Pmusicbootcamp.played=4=62,9=-]        what was played where it was not the given note ("-" = nothing)
 *     [-Pmusicbootcamp.parameters={"rawRise":1.3}]  parameters to change for the replay
 *     [-Pmusicbootcamp.sweep=true]             also try a grid of parameters and rank them
 *     [-Pmusicbootcamp.templates=<piano-templates.json>]  the piano calibration (chords)
 * ```
 */
class RecordingReplayTest {
    @Test
    fun analyseRecordings() {
        val path = System.getProperty("musicbootcamp.recordings")
        assumeTrue("No recordings given (-Pmusicbootcamp.recordings=…)", path != null)
        val root = File(path!!)
        val files = if (root.isDirectory) root.listFiles { f -> f.name.endsWith(".wav") }!!.sorted() else listOf(root)
        files.forEach { println(Analysis(it).run()) }
    }

    @Test
    fun analysisWorksOnASyntheticRecording() {
        // Two app notes, the player answers the first one late; checks the outputs are written.
        val sampleRate = 44_100
        val reference = FloatArray(3 * sampleRate)
        addPianoStroke(reference, 60, startSeconds = 0.0, durationSeconds = 0.5)
        addPianoStroke(reference, 64, startSeconds = 1.0, durationSeconds = 0.5)
        val microphone = FloatArray(reference.size) { reference[it] * 0.4f }
        addPianoStroke(microphone, 60, startSeconds = 1.1, durationSeconds = 0.4)
        val directory = File("build/analysis-test").apply { deleteRecursively(); mkdirs() }
        val wav = File(directory, "synthetic.wav")
        wav.writeBytes(Wav.header(sampleRate, 2, reference.size * 4L) + Wav.pcm16(listOf(microphone, reference)))
        val log = RecordingLog(
            sampleRate = sampleRate,
            channels = listOf("microphone", "reference"),
            info = mapOf("breathingTime" to "1.0"),
            events = listOf(
                RecordingEvent(0, RecordingEventType.STEP, listOf(60)),
                RecordingEvent(sampleRate.toLong(), RecordingEventType.STEP, listOf(64)),
            ),
        )
        File(directory, "synthetic.json").writeText(SetupRepository.json.encodeToString(log))

        val report = Analysis(wav, output = File(directory, "out")).run()
        assertTrue("step" in report, report)
        assertTrue(File(directory, "out/steps.txt").isFile)
        assertTrue(File(directory, "out/hops.csv").readLines().size > 200)
        assertTrue(ImageIO.read(File(directory, "out/spectrogram.png")).width > 200)
    }

    @Test
    fun chordRecordingsAreAnalysedByChord() {
        // Two app chords 2.5 s apart; the player answers the first one after it has ended.
        val sampleRate = 44_100
        val reference = FloatArray(5 * sampleRate)
        for ((start, notes) in listOf(0.0 to listOf(48, 55), 2.5 to listOf(50, 57))) {
            notes.forEach { addPianoStroke(reference, it, startSeconds = start, durationSeconds = 1.25) }
        }
        val microphone = FloatArray(reference.size) { reference[it] * 0.3f }
        listOf(48, 55).forEach { addPianoStroke(microphone, it, startSeconds = 1.6, durationSeconds = 0.8) }
        val directory = File("build/analysis-test-chords").apply { deleteRecursively(); mkdirs() }
        val wav = File(directory, "chords.wav")
        wav.writeBytes(Wav.header(sampleRate, 2, reference.size * 4L) + Wav.pcm16(listOf(microphone, reference)))
        val log = RecordingLog(
            sampleRate = sampleRate,
            channels = listOf("microphone", "reference"),
            info = mapOf("breathingTime" to "2.5", "voices" to "2", "range" to "48..72"),
            events = listOf(0.0 to listOf(48, 55), 2.5 to listOf(50, 57)).flatMap { (start, notes) ->
                val on = (start * sampleRate).toLong()
                val off = ((start + 1.25) * sampleRate).toLong()
                listOf(RecordingEvent(on, RecordingEventType.STEP, notes), RecordingEvent(on, RecordingEventType.APP_NOTE_ON, notes)) +
                    notes.map { RecordingEvent(off, RecordingEventType.APP_NOTE_OFF, listOf(it)) }
            }.sortedBy { it.sample },
        )
        File(directory, "chords.json").writeText(SetupRepository.json.encodeToString(log))

        val report = Analysis(wav, output = File(directory, "out")).run()
        // The app's own chords are ignored (as by OwnSoundGate): only the answer at 1.6 s counts.
        assertTrue("voices: 2" in report && "HIT 1  MISSED 1  WRONG_NOTE 0  OCTAVE 0  OWN_SOUND 0  EXTRA 0" in report, report)
        assertTrue(File(directory, "out/spectrogram.png").isFile)
    }
}

/** What the player played in one step (distinct notes, lowest first; null = nothing) and when their answer counts. */
private class Expectation(val step: Int, val given: List<Int>, val played: List<Int>?, val from: Long, val until: Long)

/** A note or chord recognised in the replay. */
private class Detection(
    val sampleTime: Long,
    val notes: List<Int>,
    val ownSoundRemoved: Boolean,
    val detail: String,
    /** When the app gets it (after the analysis window for chords), which is when its gate decides. */
    val decidedAt: Long = sampleTime,
)

private enum class Outcome { HIT, MISSED, WRONG_NOTE, OCTAVE, OWN_SOUND, EXTRA }

private class Analysis(private val wavFile: File, output: File? = null) {
    private val name = wavFile.name.removeSuffix(".wav")
    private val output = output ?: File("build/analysis/$name")
    private val audio: WavAudio = Wav.read(wavFile.readBytes())
    private val sampleRate = audio.sampleRate
    private val log: RecordingLog? = File(wavFile.path.removeSuffix(".wav") + ".json").takeIf { it.isFile }
        ?.let { SetupRepository.json.decodeFromString<RecordingLog>(it.readText()) }
    private val microphone = audio.channels[log?.channels?.indexOf("microphone")?.takeIf { it >= 0 } ?: 0]
    private val reference = log?.channels?.indexOf("reference")?.takeIf { it >= 0 }?.let { audio.channels[it] }
    private val referenceA = log?.info?.get("referenceAHz")?.toDoubleOrNull() ?: Tuning.STANDARD_A_HZ
    private val parameters = parameters(System.getProperty("musicbootcamp.parameters"))
    private val events = log?.events.orEmpty()
    private val steps = events.filter { it.type == RecordingEventType.STEP }
    private val stepSamples = (log?.info?.get("breathingTime")?.toDoubleOrNull() ?: 1.0).times(sampleRate).toLong()
    private val toleranceSamples = ((log?.info?.get("lateAnswerToleranceMillis")?.toDoubleOrNull() ?: 150.0) + 60) / 1000 * sampleRate

    /** Recorded parameters with the overrides on top (both may be partial JSON). */
    private fun parameters(overrides: String?): DetectionParameters {
        val recorded = log?.info?.get("detectionParameters")?.let { SetupRepository.json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val changes = overrides?.let { SetupRepository.json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        return SetupRepository.json.decodeFromJsonElement(DetectionParameters.serializer(), JsonObject(recorded + changes))
    }

    private val voices = log?.info?.get("voices")?.toIntOrNull() ?: 1
    private val range = log?.info?.get("range")?.split("..")?.let { it[0].toInt()..it[1].toInt() } ?: 48..72
    private val chordParameters = chordParameters(System.getProperty("musicbootcamp.parameters"))

    /** Like [parameters], for recordings with several voices. */
    private fun chordParameters(overrides: String?): ChordDetectionParameters {
        val recorded = log?.info?.get("chordDetectionParameters")?.let { SetupRepository.json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val changes = overrides?.let { SetupRepository.json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        return SetupRepository.json.decodeFromJsonElement(ChordDetectionParameters.serializer(), JsonObject(recorded + changes))
    }

    /** `-Pmusicbootcamp.templates=<piano-templates.json>`: the calibration to use for chords. */
    private val templates = System.getProperty("musicbootcamp.templates")?.let { File(it) }?.takeIf { it.isFile }
        ?.let { SetupRepository.json.decodeFromString<LearnedTemplates>(it.readText()) } ?: LearnedTemplates()

    private val expectations: List<Expectation> by lazy {
        // "4=62,9=-" (single notes) or "4=60+67" (chords): what was played where it was not the given.
        val played = System.getProperty("musicbootcamp.played").orEmpty().split(',').filter { '=' in it }.associate {
            val (step, notes) = it.split('=')
            step.trim().toInt() to notes.split('+').mapNotNull { n -> n.trim().toIntOrNull() }.distinct().sorted().ifEmpty { null }
        }
        steps.mapIndexed { index, step ->
            val next = steps.getOrNull(index + 1)?.sample ?: (step.sample + stepSamples)
            val given = step.notes.distinct().sorted()
            Expectation(index + 1, given, if (index + 1 in played) played[index + 1] else given, step.sample, next + toleranceSamples.toLong())
        }
    }

    fun run(): String {
        output.mkdirs()
        val traces = mutableListOf<HopTrace>()
        val ownSound = mutableListOf<FloatArray>() // per hop, in spectrogram bins
        val detected = replay { trace ->
            traces += trace
            ownSound += trace.ownSoundPower?.let { bins(it, it.size) } ?: FloatArray(BINS)
        }
        writeCsv(traces)
        val table = stepTable(detected)
        File(output, "steps.txt").writeText(table)
        drawSpectrogram(traces, ownSound, detected)

        return buildString {
            appendLine("== $name: ${microphone.size / sampleRate} s, voices: $voices, reference: ${reference != null}, templates: ${templates.notes.size}")
            appendLine("   parameters: ${if (voices == 1) parameters.toJson() else chordParameters.toJson()}")
            append(table)
            appendLine("   written to ${output.absolutePath}")
            if (System.getProperty("musicbootcamp.sweep") == "true") append(sweep())
        }
    }

    /** Replays the recording as the app would: detection, then the own-sound gate where the app's sound was not removed. */
    private fun replay(
        parameters: DetectionParameters = this.parameters,
        chordParameters: ChordDetectionParameters = this.chordParameters,
        trace: ((HopTrace) -> Unit)? = null,
    ): List<Detection> {
        val detections = if (voices == 1) {
            val tracker = NoteTracker(sampleRate, referenceA, parameters)
            tracker.trace = trace
            blocks { mic, ref -> tracker.process(mic, ref) }
                .map { Detection(it.sampleTime, listOf(it.midiNote), it.ownSoundRemoved, "${it.cents.roundToInt()}ct") }
        } else {
            var now = 0L
            // As the app: the chords given lately are the expected ones.
            val tracker = ChordTracker(sampleRate, voices, range, referenceA, chordParameters, templates) {
                steps.filter { it.sample <= now }.takeLast(3).map { it.notes }
            }
            tracker.trace = trace
            blocks { mic, ref -> tracker.process(mic, ref).also { now += mic.size } }.map {
                val alternative = it.details.alternative?.joinToString("+") { note -> n(note) }
                Detection(
                    it.onsetTime, it.notes, it.ownSoundRemoved,
                    "%.3f (%s %.3f)".format(Locale.ROOT, it.details.score, alternative, it.details.alternativeScore ?: 0.0), it.sampleTime,
                )
            }
        }
        // The own-sound gate ignores what could all be the app's own notes.
        return detections.filter { d -> d.ownSoundRemoved || usesHeadphones || !d.notes.all { appSounding(it, d.decidedAt) } }
    }

    private fun <T> blocks(process: (FloatArray, FloatArray?) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        var start = 0
        while (start < microphone.size) {
            val end = minOf(start + BLOCK, microphone.size)
            result += process(microphone.copyOfRange(start, end), reference?.copyOfRange(start, end))
            start = end
        }
        return result
    }

    private val usesHeadphones = log?.info?.get("usesHeadphones") == "true"

    /** Like OwnSoundGate: the app sounds the note (or an octave of it), or released it within 200 ms. */
    private fun appSounding(note: Int, sample: Long): Boolean {
        val release = sampleRate / 5
        val lastOn = mutableMapOf<Int, Long>()
        val lastOff = mutableMapOf<Int, Long>()
        for (event in events) {
            if (event.sample > sample) break
            when (event.type) {
                RecordingEventType.APP_NOTE_ON -> event.notes.forEach { lastOn[it] = event.sample; lastOff.remove(it) }
                // Only the first note-off ends the note (the app sends another one at the next step).
                RecordingEventType.APP_NOTE_OFF -> event.notes.forEach { if (it in lastOn && it !in lastOff) lastOff[it] = event.sample }
                else -> Unit
            }
        }
        return lastOn.keys.any { own ->
            (note - own) % 12 == 0 && (lastOff[own]?.let { sample - it < release } ?: true)
        }
    }

    /** Assigns detections to the steps they answer; everything else is a mistake of the recognition. */
    private fun score(detected: List<Detection>): Pair<Map<Int, Pair<Outcome, Detection?>>, List<Pair<Outcome, Detection>>> {
        val matched = mutableMapOf<Int, Pair<Outcome, Detection?>>()
        val extra = mutableListOf<Pair<Outcome, Detection>>()
        for (detection in detected) {
            val notes = detection.notes.distinct().sorted()
            val open = expectations.filter { detection.sampleTime in it.from..it.until && it.step !in matched && it.played != null }
            val hit = open.firstOrNull { it.played == notes }
            val candidate = open.firstOrNull()
            when {
                hit != null -> matched[hit.step] = Outcome.HIT to detection
                candidate?.played != null && octaves(notes, candidate.played) -> matched[candidate.step] = Outcome.OCTAVE to detection
                // The app's own current or previous notes, heard as played.
                appNotesAround(detection.sampleTime).containsAll(notes) -> extra += Outcome.OWN_SOUND to detection
                candidate != null -> matched[candidate.step] = Outcome.WRONG_NOTE to detection
                else -> extra += Outcome.EXTRA to detection
            }
        }
        expectations.filter { it.played != null && it.step !in matched }.forEach { matched[it.step] = Outcome.MISSED to null }
        return matched to extra
    }

    /** The same notes up to octaves (the classic pitch detection error). */
    private fun octaves(a: List<Int>, b: List<Int>) =
        a.size == b.size && a.zip(b).all { (x, y) -> (x - y) % 12 == 0 } && a != b

    private fun appNotesAround(sample: Long): Set<Int> {
        val window = stepSamples + sampleRate / 2
        return steps.filter { sample - it.sample in 0..window }.flatMap { it.notes }.toSet()
    }

    private fun chord(notes: List<Int>) = notes.joinToString("+") { n(it) }

    private fun stepTable(detected: List<Detection>): String {
        val (matched, extra) = score(detected)
        val live = events.filter { it.type == RecordingEventType.DETECTED }
        val evaluations = events.filter { it.type == RecordingEventType.EVALUATION }
        return buildString {
            appendLine(" step  time    given        played       | live heard               | replay                          | result")
            for (expectation in expectations) {
                val liveHeard = live.filter { it.sample in expectation.from..expectation.until }
                    .joinToString(" ") { "${chord(it.notes)}@${ms(it.sample - expectation.from)}" }
                val (outcome, detection) = matched[expectation.step] ?: (null to null)
                val evaluation = evaluations.getOrNull(expectation.step - 1)?.correct?.let { if (it) "live ✓" else "live ✗" } ?: ""
                appendLine(
                    String.format(
                        Locale.ROOT, "%5d %6.2fs  %-12s %-12s | %-24s | %-31s | %s %s",
                        expectation.step, expectation.from.toDouble() / sampleRate, chord(expectation.given),
                        expectation.played?.let(::chord) ?: "-", liveHeard,
                        detection?.let { "${chord(it.notes)}@${ms(it.sampleTime - expectation.from)} ${it.detail}" } ?: "",
                        outcome ?: "", evaluation,
                    ),
                )
            }
            extra.forEach { (outcome, detection) ->
                appendLine(String.format(Locale.ROOT, "      %6.2fs  %s %s %s", detection.sampleTime.toDouble() / sampleRate, outcome, chord(detection.notes), detection.detail))
            }
            val counts = matched.values.groupingBy { it.first }.eachCount() + extra.groupingBy { it.first }.eachCount()
            appendLine("   " + Outcome.entries.joinToString("  ") { "$it ${counts[it] ?: 0}" })
        }
    }

    private fun sweep(): String = if (voices == 1) sweepNotes() else sweepChords()

    private fun sweepChords(): String {
        val base = chordParameters
        val variants = buildList {
            for (penalty in listOf(0.0, 0.01, 0.02, 0.04))
                for (share in listOf(0.03, 0.06, 0.12))
                    for (background in listOf(0.5, 1.0, 1.5))
                        for (end in listOf(250, 300, 400))
                            add(base.copy(notePenalty = penalty, minNoteShare = share, backgroundWeight = background, windowEndMillis = end))
        }
        val ranked = variants.map { variant ->
            val (matched, extra) = score(replay(chordParameters = variant))
            Triple(variant, matched.values.count { it.first == Outcome.HIT }, matched.values.count { it.first != Outcome.HIT } + extra.size)
        }.sortedWith(compareBy({ it.third - it.second }, { it.third }))
        return buildString {
            appendLine("   sweep (${variants.size} variants, best first): hits / recognition errors")
            ranked.take(12).forEach { (p, hits, errors) ->
                appendLine("   $hits / $errors  notePenalty=${p.notePenalty} minNoteShare=${p.minNoteShare} backgroundWeight=${p.backgroundWeight} windowEndMillis=${p.windowEndMillis}")
            }
        }
    }

    private fun sweepNotes(): String {
        val base = parameters
        val variants = buildList {
            for (rawRise in listOf(1.2, 1.35, 1.5, 1.8))
                for (share in listOf(0.3, 0.5, 0.8))
                    for (over in listOf(1.5, 2.0, 3.0))
                        for (clarity in listOf(0.7, 0.8))
                            add(base.copy(rawRise = rawRise, ownSoundShare = share, overSubtraction = over, minClarity = clarity))
        }
        val ranked = variants.map { variant ->
            val (matched, extra) = score(replay(parameters = variant))
            val hits = matched.values.count { it.first == Outcome.HIT }
            val errors = matched.values.count { it.first != Outcome.HIT } + extra.size
            Triple(variant, hits, errors)
        }.sortedWith(compareBy({ it.third - it.second }, { it.third }))
        return buildString {
            appendLine("   sweep (${variants.size} variants, best first): hits / recognition errors")
            ranked.take(12).forEach { (p, hits, errors) ->
                appendLine("   $hits / $errors  rawRise=${p.rawRise} ownSoundShare=${p.ownSoundShare} overSubtraction=${p.overSubtraction} minClarity=${p.minClarity}")
            }
        }
    }

    private fun writeCsv(traces: List<HopTrace>) {
        File(output, "hops.csv").printWriter().use { out ->
            out.println("time_s,level,stroke_level,own_sound_level,level_while_reference,delay_hops,own_sound_removed,blocked,reference_onset_near,onset,frequency_hz,note,clarity")
            for (t in traces) {
                val note = t.frequencyHz?.let { 69 + 12 * ln(it / referenceA) / ln(2.0) }
                out.println(
                    String.format(
                        Locale.ROOT, "%.4f,%.5f,%.5f,%.5f,%.5f,%s,%b,%b,%b,%b,%s,%s,%s",
                        t.sampleTime.toDouble() / sampleRate, t.level, t.strokeLevel, t.ownSoundLevel, t.levelWhileReference, t.delayHops ?: "",
                        t.ownSoundRemoved, t.blocked, t.referenceOnsetNear, t.onset,
                        t.frequencyHz?.let { "%.1f".format(Locale.ROOT, it) } ?: "",
                        note?.let { "%.2f".format(Locale.ROOT, it) } ?: "", t.clarity?.let { "%.3f".format(Locale.ROOT, it) } ?: "",
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- spectrogram

    /** Spectrogram rows: log-spaced frequencies from [LOW_HZ] to [HIGH_HZ]. */
    private fun bins(power: DoubleArray, fftSize: Int): FloatArray = FloatArray(BINS) { row ->
        val low = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(row.toDouble() / BINS)
        val high = LOW_HZ * (HIGH_HZ / LOW_HZ).pow((row + 1.0) / BINS)
        val from = (low * fftSize / sampleRate).toInt()
        val to = max(from, (high * fftSize / sampleRate).toInt())
        var peak = 0.0
        for (k in from..to) if (k < fftSize / 2) peak = max(peak, power[k])
        peak.toFloat()
    }

    private fun stft(signal: FloatArray, hops: Int): List<FloatArray> {
        val size = 4096
        val window = 2048
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        return List(hops) { h ->
            val end = (h + 1) * HOP
            re.fill(0.0); im.fill(0.0)
            for (i in 0 until window) {
                val index = end - window + i
                // Same (rectangular) window as the detection, so the panels compare directly.
                re[i] = if (index in signal.indices) signal[index].toDouble() else 0.0
            }
            fft(re, im)
            bins(DoubleArray(size) { re[it] * re[it] + im[it] * im[it] }, size)
        }
    }

    private fun drawSpectrogram(traces: List<HopTrace>, ownSound: List<FloatArray>, detected: List<Detection>) {
        val hops = traces.size
        val panels = listOfNotNull(
            "microphone" to stft(microphone, hops),
            reference?.let { "reference (what the app played)" to stft(it, hops) },
            ("predicted own sound in the microphone" to ownSound).takeIf { reference != null },
        )
        val panelHeight = BINS
        val levelHeight = 160
        val left = 50
        val width = left + hops
        val height = panels.size * (panelHeight + 20) + levelHeight + 40
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        g.color = Color.BLACK
        g.fillRect(0, 0, width, height)
        val loudest = panels.first().second.maxOf { column -> column.max() }.toDouble().coerceAtLeast(1e-12)

        fun yOf(frequency: Double, top: Int) =
            top + panelHeight - (panelHeight * ln(frequency / LOW_HZ) / ln(HIGH_HZ / LOW_HZ)).roundToInt()
        fun xOf(sample: Long) = left + (sample / HOP).toInt()
        fun frequency(note: Int) = referenceA * 2.0.pow((note - 69) / 12.0)

        panels.forEachIndexed { index, (title, columns) ->
            val top = index * (panelHeight + 20) + 20
            g.color = Color.WHITE
            g.drawString(title, left, top - 5)
            columns.forEachIndexed { x, column ->
                column.forEachIndexed { row, power ->
                    val db = 10 * log10((power / loudest).coerceAtLeast(1e-9))
                    image.setRGB(left + x, top + panelHeight - 1 - row, heat(((db + 60) / 60).coerceIn(0.0, 1.0)))
                }
            }
            // Octave lines with the C names.
            g.color = Color(255, 255, 255, 60)
            for (c in 24..108 step 12) {
                val y = yOf(frequency(c), top)
                if (y in top until top + panelHeight) {
                    g.drawLine(left, y, width, y)
                    g.drawString(NoteNames.displayName(c), 2, y + 4)
                }
            }
            // Steps: numbered lines; app notes: green marks; replayed detections: red; live: yellow.
            g.stroke = BasicStroke(1f)
            steps.forEachIndexed { step, event ->
                g.color = Color(255, 255, 255, 140)
                g.drawLine(xOf(event.sample), top, xOf(event.sample), top + panelHeight)
                g.drawString("${step + 1}", xOf(event.sample) + 2, top + 12)
            }
            events.filter { it.type == RecordingEventType.APP_NOTE_ON }.forEach {
                g.color = Color.GREEN
                for (note in it.notes) g.drawLine(xOf(it.sample), yOf(frequency(note), top), xOf(it.sample) + 40, yOf(frequency(note), top))
            }
            events.filter { it.type == RecordingEventType.DETECTED }.forEach {
                g.color = Color.YELLOW
                for (note in it.notes) g.drawOval(xOf(it.sample) - 4, yOf(frequency(note), top) - 4, 8, 8)
            }
            detected.forEach {
                g.color = Color.RED
                for (note in it.notes) g.fillOval(xOf(it.sampleTime) - 3, yOf(frequency(note), top) - 3, 6, 6)
            }
        }

        // Levels (log scale): total, what a stroke is judged by, predicted own sound; onsets as ticks.
        val top = panels.size * (panelHeight + 20) + 20
        g.color = Color.WHITE
        g.drawString("levels: total (white), stroke level (cyan), predicted own sound (orange), onsets (magenta), blocked (gray)", left, top - 5)
        fun yLevel(value: Double) = top + levelHeight - (levelHeight * (20 * log10(value.coerceAtLeast(1e-4)) + 80) / 80).roundToInt().coerceIn(0, levelHeight)
        for ((color, value) in listOf<Pair<Color, (HopTrace) -> Double>>(
            Color.WHITE to { it.level }, Color.CYAN to { it.strokeLevel }, Color.ORANGE to { it.ownSoundLevel },
        )) {
            g.color = color
            for (x in 1 until hops) g.drawLine(left + x - 1, yLevel(value(traces[x - 1])), left + x, yLevel(value(traces[x])))
        }
        traces.forEachIndexed { x, t ->
            if (t.blocked) { g.color = Color.GRAY; g.drawLine(left + x, top + levelHeight + 2, left + x, top + levelHeight + 6) }
            if (t.onset) { g.color = Color.MAGENTA; g.drawLine(left + x, top, left + x, top + levelHeight) }
        }
        // Seconds.
        g.color = Color.WHITE
        for (second in 0..microphone.size / sampleRate) {
            val x = xOf(second.toLong() * sampleRate)
            g.drawLine(x, height - 18, x, height - 12)
            g.drawString("${second}s", x + 2, height - 4)
        }
        g.dispose()
        ImageIO.write(image, "png", File(output, "spectrogram.png"))
    }

    /** Black → blue → red → yellow → white. */
    private fun heat(value: Double): Int {
        val stops = listOf(Color.BLACK, Color(30, 30, 160), Color(200, 30, 60), Color(250, 210, 40), Color.WHITE)
        val position = value * (stops.size - 1)
        val i = position.toInt().coerceAtMost(stops.size - 2)
        val f = position - i
        fun mix(a: Int, b: Int) = (a + (b - a) * f).roundToInt()
        return Color(mix(stops[i].red, stops[i + 1].red), mix(stops[i].green, stops[i + 1].green), mix(stops[i].blue, stops[i + 1].blue)).rgb
    }

    private fun n(note: Int) = NoteNames.displayName(note)
    private fun ms(samples: Long) = "${samples * 1000 / sampleRate}ms"

    private companion object {
        const val BLOCK = 512
        const val HOP = 512
        const val BINS = 240
        const val LOW_HZ = 50.0
        const val HIGH_HZ = 5000.0
    }
}
