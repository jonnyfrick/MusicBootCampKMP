package io.github.jonnyfrick.musicbootcamp.web

import io.github.jonnyfrick.musicbootcamp.core.audio.RecordingFile
import io.github.jonnyfrick.musicbootcamp.core.midi.MidiMessage
import io.github.jonnyfrick.musicbootcamp.core.persistence.DocumentStore
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputBackend
import io.github.jonnyfrick.musicbootcamp.platform.AudioInputPort
import io.github.jonnyfrick.musicbootcamp.platform.BUILT_IN_PIANO
import io.github.jonnyfrick.musicbootcamp.platform.Instruments
import io.github.jonnyfrick.musicbootcamp.platform.MidiBackend
import io.github.jonnyfrick.musicbootcamp.platform.MidiInputPort
import io.github.jonnyfrick.musicbootcamp.platform.MidiOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.PlatformServices
import io.github.jonnyfrick.musicbootcamp.platform.PlayedAudioBuffer
import io.github.jonnyfrick.musicbootcamp.platform.RecordingStore
import io.github.jonnyfrick.musicbootcamp.platform.RenderedOutputPort
import io.github.jonnyfrick.musicbootcamp.platform.RenderedSynth
import io.github.jonnyfrick.musicbootcamp.platform.SynthMidiBackend
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Services of the web app (JavaScript and WebAssembly alike): setups in the browser's local
 * storage, MIDI through the Web MIDI API (Chromium browsers and Firefox), the microphone through
 * `getUserMedia`, and the app's own piano ([Instruments.piano]) through the Web Audio API as sound output.
 *
 * The browser side is a handful of small JavaScript functions (below) that keep their objects in
 * `globalThis.__mbc` and exchange only numbers, strings and callbacks with Kotlin, which is what
 * both targets can pass.
 */
fun webServices(): PlatformServices {
    initState()
    val piano = WebPianoSynth()
    return PlatformServices(
        midi = SynthMidiBackend(WebMidiBackend(), piano),
        documents = LocalStorageDocumentStore(),
        legacyFiles = null,
        audio = WebAudioInput(),
        recordings = DownloadRecordingStore(),
        // TEMPORARY: the developer tools (Settings → Recognition: recording, optimization mode,
        // detection parameters) are on for everyone on the web, to tune the recognition in
        // browsers. Set back to false when that is done (see MIGRATION.md, "Open points").
        debugTools = true,
        renderedSynths = listOf(piano),
    )
}

/** One local-storage entry per document. Local storage is synchronous and holds a few megabytes. */
private class LocalStorageDocumentStore : DocumentStore {
    override suspend fun read(name: String): String? = storageGet(PREFIX + name)

    override suspend fun write(name: String, content: String) = storageSet(PREFIX + name, content)

    override suspend fun delete(name: String) = storageRemove(PREFIX + name)

    override suspend fun list(): List<String> =
        storageKeys(PREFIX).split('\n').filter { it.isNotEmpty() }.map { it.removePrefix(PREFIX) }.sorted()

    private companion object {
        const val PREFIX = "musicbootcamp/"
    }
}

/** The app's own piano on the loudspeaker; rendered by the app, so its sound can be removed from the microphone signal. */
private class WebPianoSynth : RenderedSynth {
    override val deviceName = BUILT_IN_PIANO

    // The browser decides the sample rate; microphone and synthesizer share one audio context.
    override fun open(sampleRate: Int): RenderedOutputPort = WebSynthPort()
}

private class WebSynthPort : RenderedOutputPort {
    private val synth = Instruments.piano(audioSampleRate())
    private val played = PlayedAudioBuffer()
    private var block = FloatArray(BLOCK_FRAMES)
    private var rendered = 0L

    // The browser asks for each block on its main thread, where all of this runs: no locking.
    private val node = synthStart(
        render = { frames ->
            if (block.size != frames) block = FloatArray(frames)
            synth.render(block)
            played.append(block)
            rendered += frames
        },
        sample = { index -> block[index] },
    )

    override fun send(message: MidiMessage) = synth.send(message)

    // The block just rendered is on its way to the loudspeaker; the microphone hears it later.
    override fun playedAudio(frames: Int): FloatArray = played.read(frames, rendered - block.size, LEAD_FRAMES, RESYNC_FRAMES)

    override fun close() = synthStop(node)

    private companion object {
        const val BLOCK_FRAMES = 1024
        const val LEAD_FRAMES = 1024
        const val RESYNC_FRAMES = 8192
    }
}

/** MIDI through the Web MIDI API. */
private class WebMidiBackend : MidiBackend {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    init {
        // Asks for access once (the browser may ask the user); devices appear when it is granted.
        midiInit { changes.tryEmit(Unit) }
    }

    // Without Web MIDI (Safari) the microphone and the app's piano still work.
    override val unavailableReason: String? = null

    override val devicesChanged: Flow<Unit> = changes

    override fun inputDevices(): List<String> = midiNames(true).split('\n').filter { it.isNotEmpty() }

    override fun outputDevices(): List<String> = midiNames(false).split('\n').filter { it.isNotEmpty() }

    override fun openInput(name: String): MidiInputPort {
        val messages = MutableSharedFlow<MidiMessage>(extraBufferCapacity = 256)
        val listening = midiListen(name) { status, data1, data2 -> messages.tryEmit(MidiMessage(status, data1, data2)) }
        if (!listening) throw IllegalStateException("MIDI device $name is not connected.")
        return object : MidiInputPort {
            override val messages: Flow<MidiMessage> = messages
            override fun close() = midiUnlisten(name)
        }
    }

    override fun openOutput(name: String): MidiOutputPort {
        return object : MidiOutputPort {
            override fun send(message: MidiMessage) = midiSend(name, message.status, message.data1, message.data2)
            override fun close() = Unit
        }
    }
}

/** The microphone through `getUserMedia`, as unprocessed as the browser allows. */
private class WebAudioInput : AudioInputBackend {
    override val unavailableReason: String? =
        if (hasMicrophoneApi()) null else "This browser gives no access to the microphone (it needs HTTPS or localhost)."

    override val hasAccess: Boolean get() = hasMicrophoneStream()

    override suspend fun requestAccess(): Boolean = hasAccess || suspendCoroutine { continuation ->
        microphoneRequest { granted -> continuation.resume(granted) }
    }

    // The browser's own choice of microphone; it offers no names before access is granted.
    override fun devices(): List<String> = emptyList()

    override fun open(name: String?): AudioInputPort {
        if (!hasAccess) throw IllegalStateException("No access to the microphone.")
        return WebAudioPort()
    }
}

private class WebAudioPort : AudioInputPort {
    override val sampleRate: Int = audioSampleRate()

    private val received = Channel<FloatArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var block = FloatArray(0)

    init {
        microphoneStart(
            begin = { frames -> block = FloatArray(frames) },
            put = { index, value -> block[index] = value },
            end = { received.trySend(block) },
        )
    }

    override val blocks: Flow<FloatArray> = received.receiveAsFlow()

    override fun close() {
        microphoneStop()
        received.close()
    }
}

/**
 * Recordings in the browser: kept in memory while the exercise runs and handed to the user as
 * downloads when it ends (`session-<date>_<time>.wav` and `.json`), since a page cannot write
 * into a folder by itself.
 */
private class DownloadRecordingStore : RecordingStore {
    override val location: String = "Downloads"

    override fun create(): RecordingFile = object : RecordingFile {
        private val id = recordingCreate()
        override val name: String = recordingName()

        override fun append(bytes: ByteArray) = recordingAppend(id, bytes.size) { index -> bytes[index].toInt() and 0xFF }

        override fun finish(wavHeader: ByteArray, log: String) =
            recordingFinish(id, name, wavHeader.size, { index -> wavHeader[index].toInt() and 0xFF }, log)
    }
}

// ----------------------------------------------------------------------------- JavaScript side

private fun initState(): Unit = js("{ globalThis.__mbc = globalThis.__mbc || { synths: {}, nextId: 0, listeners: {}, recordings: {} }; }")

/** `session-<date>_<time>` in local time, as the other platforms name their recordings. */
private fun recordingName(): String = js(
    """(function () {
        var d = new Date();
        function two(n) { return (n < 10 ? '0' : '') + n; }
        return 'session-' + d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()) +
            '_' + two(d.getHours()) + '-' + two(d.getMinutes()) + '-' + two(d.getSeconds());
    })()""",
)

private fun recordingCreate(): Int = js("(function () { var s = globalThis.__mbc; var id = ++s.nextId; s.recordings[id] = []; return id; })()")

/** Keeps [size] more bytes of recording [id], read one by one through [byteAt]. */
private fun recordingAppend(id: Int, size: Int, byteAt: (Int) -> Int): Unit = js(
    """{
        var chunk = new Uint8Array(size);
        for (var i = 0; i < size; i++) chunk[i] = byteAt(i);
        globalThis.__mbc.recordings[id].push(chunk);
    }""",
)

/** Offers recording [id] as two downloads: the WAV (header first) and the log. */
private fun recordingFinish(id: Int, name: String, headerSize: Int, headerAt: (Int) -> Int, log: String): Unit = js(
    """{
        var s = globalThis.__mbc;
        var header = new Uint8Array(headerSize);
        for (var i = 0; i < headerSize; i++) header[i] = headerAt(i);
        function save(blob, fileName) {
            var link = document.createElement('a');
            link.href = URL.createObjectURL(blob);
            link.download = fileName;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            setTimeout(function () { URL.revokeObjectURL(link.href); }, 60000);
        }
        save(new Blob([header].concat(s.recordings[id]), { type: 'audio/wav' }), name + '.wav');
        delete s.recordings[id];
        // A moment later: browsers hold back two downloads started at the very same time.
        setTimeout(function () { save(new Blob([log], { type: 'application/json' }), name + '.json'); }, 500);
    }""",
)

private fun storageGet(key: String): String? = js("window.localStorage.getItem(key)")

private fun storageSet(key: String, value: String): Unit = js("{ window.localStorage.setItem(key, value); }")

private fun storageRemove(key: String): Unit = js("{ window.localStorage.removeItem(key); }")

private fun storageKeys(prefix: String): String =
    js("Object.keys(window.localStorage).filter(function (k) { return k.startsWith(prefix); }).join('\\n')")

/** The shared audio context's sample rate; creates the context and wakes it (browsers start it only after a click). */
private fun audioSampleRate(): Int = js(
    """(function () {
        var s = globalThis.__mbc;
        if (!s.context) s.context = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
        if (s.context.state === 'suspended') s.context.resume();
        return Math.round(s.context.sampleRate);
    })()""",
)

/** Starts a node that asks [render] for each block and reads it sample by sample; returns its id. */
private fun synthStart(render: (Int) -> Unit, sample: (Int) -> Float): Int = js(
    """(function () {
        var s = globalThis.__mbc;
        var node = s.context.createScriptProcessor(1024, 1, 1);
        node.onaudioprocess = function (e) {
            var out = e.outputBuffer.getChannelData(0);
            render(out.length);
            for (var i = 0; i < out.length; i++) out[i] = sample(i);
        };
        node.connect(s.context.destination);
        var id = ++s.nextId;
        s.synths[id] = node;
        return id;
    })()""",
)

private fun synthStop(id: Int): Unit = js(
    """{
        var s = globalThis.__mbc;
        var node = s.synths[id];
        if (node) { node.onaudioprocess = null; node.disconnect(); delete s.synths[id]; }
    }""",
)

private fun midiInit(onChange: () -> Unit): Unit = js(
    """{
        if (navigator.requestMIDIAccess) {
            navigator.requestMIDIAccess().then(function (access) {
                globalThis.__mbc.midi = access;
                access.onstatechange = function () { onChange(); };
                onChange();
            }, function () { });
        }
    }""",
)

/** The names of the MIDI inputs or outputs, one per line. */
private fun midiNames(inputs: Boolean): String = js(
    """(function () {
        var access = globalThis.__mbc.midi;
        if (!access) return '';
        var names = [];
        (inputs ? access.inputs : access.outputs).forEach(function (port) { if (port.state !== 'disconnected') names.push(port.name); });
        return names.join('\n');
    })()""",
)

/** Calls [onMessage] for every channel message of the input called [name]; false if there is none. */
private fun midiListen(name: String, onMessage: (Int, Int, Int) -> Unit): Boolean = js(
    """(function () {
        var access = globalThis.__mbc.midi;
        if (!access) return false;
        var found = false;
        access.inputs.forEach(function (port) {
            if (found || port.name !== name) return;
            found = true;
            port.onmidimessage = function (e) {
                var d = e.data;
                if (d.length > 0 && d[0] >= 0x80 && d[0] < 0xF0) onMessage(d[0], d.length > 1 ? d[1] : 0, d.length > 2 ? d[2] : 0);
            };
        });
        return found;
    })()""",
)

private fun midiUnlisten(name: String): Unit = js(
    """{
        var access = globalThis.__mbc.midi;
        if (access) access.inputs.forEach(function (port) { if (port.name === name) port.onmidimessage = null; });
    }""",
)

private fun midiSend(name: String, status: Int, data1: Int, data2: Int): Unit = js(
    """{
        var access = globalThis.__mbc.midi;
        if (access) access.outputs.forEach(function (port) {
            if (port.name === name) port.send((status & 0xF0) === 0xC0 || (status & 0xF0) === 0xD0 ? [status, data1] : [status, data1, data2]);
        });
    }""",
)

private fun hasMicrophoneApi(): Boolean = js("!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)")

private fun hasMicrophoneStream(): Boolean = js("!!globalThis.__mbc.stream")

/** Asks for the microphone without the processing meant for calls, which would distort a piano. */
private fun microphoneRequest(done: (Boolean) -> Unit): Unit = js(
    """{
        navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
            .then(function (stream) { globalThis.__mbc.stream = stream; done(true); }, function () { done(false); });
    }""",
)

/** Delivers the microphone block by block: [begin] with its length, [put] for each sample, [end]. */
private fun microphoneStart(begin: (Int) -> Unit, put: (Int, Float) -> Unit, end: () -> Unit): Unit = js(
    """{
        var s = globalThis.__mbc;
        var source = s.context.createMediaStreamSource(s.stream);
        var node = s.context.createScriptProcessor(1024, 1, 1);
        node.onaudioprocess = function (e) {
            var data = e.inputBuffer.getChannelData(0);
            begin(data.length);
            for (var i = 0; i < data.length; i++) put(i, data[i]);
            end();
        };
        // A processor only runs while it leads to the output; a silent gain keeps the microphone off the loudspeaker.
        var mute = s.context.createGain();
        mute.gain.value = 0;
        source.connect(node);
        node.connect(mute);
        mute.connect(s.context.destination);
        s.microphone = { source: source, node: node, mute: mute };
    }""",
)

private fun microphoneStop(): Unit = js(
    """{
        var m = globalThis.__mbc.microphone;
        if (m) { m.node.onaudioprocess = null; m.source.disconnect(); m.node.disconnect(); m.mute.disconnect(); }
        globalThis.__mbc.microphone = null;
    }""",
)
