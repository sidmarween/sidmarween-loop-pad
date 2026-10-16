package de.marween.looppad

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Ein fertiger Loop. [start] ist der Ausgabe-Frame, an dem Position 0 erklingt. */
class Loop(val data: FloatArray) {
    @Volatile var start: Long = 0L
    @Volatile var muted: Boolean = false
    /** ONE SHOT: spielt nur einmal nach Anschlag, ab [shotStart] (Ausgabe-Frame). */
    @Volatile var oneShot: Boolean = false
    @Volatile var shotStart: Long = Long.MIN_VALUE
}

/** Eine laufende Aufnahme (neu oder Overdub) auf einem Pad. */
class Take(val overdub: Boolean, val fromIn: Long, val downNanos: Long, val oneShot: Boolean = false) {
    @Volatile var toIn: Long = Long.MAX_VALUE
    @Volatile var upNanos: Long = 0L
    @Volatile var released: Boolean = false
    @Volatile var armed: Boolean = false
    var cursor: Long = fromIn
    /** Erster Eingangs-Frame des fertigen Loops (bei Sync auf das Raster gerückt). */
    @Volatile var fromQ: Long = fromIn
    /** Ausgabe-Frame, an dem der Loop startet (Rasterpunkt), oder NO_GRID. */
    @Volatile var qOut: Long = NO_GRID

    companion object {
        const val NO_GRID = Long.MIN_VALUE
    }
}

/** Ordnet Frame-Zähler eines Audio-Streams der Systemzeit (System.nanoTime) zu. */
class FrameClock {
    private var anchorFrame = 0L
    private var anchorNanos = 0L

    @Synchronized fun set(frame: Long, nanos: Long) {
        anchorFrame = frame
        anchorNanos = nanos
    }

    @Synchronized fun frameAt(nanos: Long, rate: Int): Long =
        anchorFrame + (nanos - anchorNanos) * rate / 1_000_000_000L

    @Synchronized fun nanosAt(frame: Long, rate: Int): Long =
        anchorNanos + (frame - anchorFrame) * 1_000_000_000L / rate
}

/**
 * Audio-Engine: nimmt dauerhaft ins Mikrofon-Ringpuffer auf und mischt alle Loops.
 * Aufnahme- und Wiedergabe-Zeitstempel werden über [FrameClock] verbunden, damit
 * Loops exakt so lang sind wie gehalten und Overdubs im Takt landen.
 */
object Engine {
    const val PADS = 16
    const val MAX_SECONDS = 30
    private const val MIN_HOLD_NANOS = 180_000_000L
    private const val FADE_MS = 4

    enum class PadState { EMPTY, RECORDING, PLAYING, MUTED, OVERDUB, ONESHOT }

    // ---------------------------------------------------------------- Sequenzer
    const val STEPS = 16
    private val seq = Array(PADS) { BooleanArray(STEPS) }
    @Volatile var seqPlaying = false
        private set
    @Volatile var seqRecording = false
        private set
    /** Zuletzt angeschlagenes ONE-SHOT-Pad – dessen Schritte zeigt und bearbeitet der Sequenzer. */
    @Volatile var seqSelected = -1
    @Volatile private var seqOrigin = 0L

    private fun seqBar(): Long = if (masterLen > 0L) masterLen else sampleRate * 2L
    private fun seqStart(): Long = if (masterLen > 0L) masterStart else seqOrigin
    private fun stepFrame(origin: Long, bar: Long, n: Long): Long = origin + Math.floorDiv(n * bar, STEPS.toLong())

    fun seqPlay() {
        if (seqPlaying) return
        seqOrigin = written + sampleRate / 50
        seqPlaying = true
    }

    fun seqStop() {
        seqPlaying = false
        seqRecording = false
    }

    fun seqToggleRec() {
        seqRecording = !seqRecording
        if (seqRecording) seqPlay()
    }

    fun seqClear() {
        for (row in seq) java.util.Arrays.fill(row, false)
    }

    fun seqHas(p: Int, step: Int): Boolean = seq[p][step]

    fun seqCount(step: Int): Int {
        var c = 0
        for (p in 0 until PADS) if (seq[p][step]) c++
        return c
    }

    fun seqToggle(p: Int, step: Int) {
        if (p in 0 until PADS && step in 0 until STEPS) seq[p][step] = !seq[p][step]
    }

    /** Aktuell hörbarer Schritt 0..15, oder -1 wenn gestoppt. */
    fun seqCurrentStep(): Int {
        if (!seqPlaying) return -1
        val bar = seqBar()
        val rel = outClock.frameAt(System.nanoTime(), sampleRate) - seqStart()
        if (rel < 0) return -1
        return Math.floorMod(Math.floorDiv(rel * STEPS, bar), STEPS.toLong()).toInt()
    }

    /** Wird im Mischer-Thread pro Block aufgerufen: schlägt die ONE SHOTS der fälligen Schritte sample-genau an. */
    private fun runSequencer(w: Long, block: Int) {
        if (!seqPlaying) return
        val bar = seqBar()
        if (bar <= 0L) return
        val origin = seqStart()
        var n = -Math.floorDiv(-(w - origin) * STEPS, bar) // ceil
        while (true) {
            val f = stepFrame(origin, bar, n)
            if (f >= w + block) break
            if (f >= w) {
                val step = Math.floorMod(n, STEPS.toLong()).toInt()
                for (p in 0 until PADS) {
                    if (!seq[p][step]) continue
                    val l = loops.get(p)
                    if (l != null && l.oneShot) l.shotStart = f
                }
            }
            n++
        }
    }

    // ---------------------------------------------------------------- Projekte

    /** Wird nach jeder neuen Mikrofon-Aufnahme aufgerufen (Pad, Samples, Samplerate). */
    var onRecorded: ((Int, FloatArray, Int) -> Unit)? = null

    class PadSave(val data: FloatArray?, val oneShot: Boolean, val muted: Boolean)

    fun snapshotPads(): Array<PadSave> = Array(PADS) { p ->
        val l = loops.get(p)
        PadSave(l?.data?.copyOf(), l?.oneShot ?: padOneShot[p], l?.muted ?: false)
    }

    fun masterFrames(): Long = masterLen

    fun seqSnapshot(): Array<BooleanArray> = Array(PADS) { seq[it].copyOf() }

    fun seqHasAny(): Boolean {
        for (row in seq) for (b in row) if (b) return true
        return false
    }

    /** Stellt einen gespeicherten Projektzustand wieder her (alle Loops starten gemeinsam auf der Eins). */
    fun restore(pads: Array<PadSave>, master: Long, bpm: Float, pattern: Array<BooleanArray>?) {
        seqStop()
        for (p in 0 until PADS) {
            takes.set(p, null)
            loops.set(p, null)
            java.util.Arrays.fill(seq[p], false)
            padOneShot[p] = false
        }
        fixedBpm = bpm
        masterLen = master
        masterStart = written
        for (p in 0 until min(PADS, pads.size)) {
            val sv = pads[p]
            padOneShot[p] = sv.oneShot
            val d = sv.data ?: continue
            if (d.isEmpty()) continue
            val l = Loop(d)
            l.oneShot = sv.oneShot
            l.muted = sv.muted
            l.start = masterStart
            loops.set(p, l)
        }
        if (pattern != null) {
            for (p in 0 until min(PADS, pattern.size)) {
                for (k in 0 until min(STEPS, pattern[p].size)) seq[p][k] = pattern[p][k]
            }
        }
        seqSelected = -1
    }

    fun newProject() {
        restore(Array(PADS) { PadSave(null, false, false) }, 0L, 0f, null)
    }

    /** Rendert das Sequenzer-Pattern als nahtlosen Ein-Takt-Loop. */
    fun renderPattern(): FloatArray? {
        val bar = seqBar().toInt()
        if (bar <= 0) return null
        val out = FloatArray(bar)
        var any = false
        for (p in 0 until PADS) {
            val l = loops.get(p) ?: continue
            if (!l.oneShot) continue
            val d = l.data
            for (k in 0 until STEPS) {
                if (!seq[p][k]) continue
                any = true
                val st = ((k.toLong() * bar) / STEPS).toInt()
                for (i in d.indices) {
                    val idx = (st + i) % bar
                    out[idx] += d[i]
                }
            }
        }
        if (!any) return null
        for (i in out.indices) out[i] = softClip(out[i])
        return out
    }

    // ---------------------------------------------------------------- Analyse (Spektrum-Anzeigen)
    private const val SCOPE = 4096
    private val scope = FloatArray(SCOPE)
    @Volatile private var scopeW = 0

    /** Die letzten dst.size Samples des Master-Ausgangs. */
    fun copyOutput(dst: FloatArray) {
        val w = scopeW
        val n = dst.size
        for (i in 0 until n) dst[i] = scope[(w - n + i) and (SCOPE - 1)]
    }

    /** Die letzten dst.size Samples vom Mikrofon. */
    fun copyInput(dst: FloatArray) {
        val c = captured
        val n = dst.size
        val rs = ringSize
        if (ring.size < rs) return
        for (i in 0 until n) {
            val f = c - n + i
            dst[i] = if (f >= 0) ring[(f % rs).toInt()] else 0f
        }
    }

    // ---------------------------------------------------------------- Song-Aufnahme (Master-Ausgang)
    const val SONG_MAX_MINUTES = 15
    @Volatile var songRecording = false
        private set
    private val songLock = Any()
    private val songChunks = ArrayList<ShortArray>()
    private var songFrames = 0L

    fun songStart() {
        synchronized(songLock) {
            songChunks.clear()
            songFrames = 0L
        }
        songRecording = true
    }

    /** Beendet die Song-Aufnahme und liefert sie als 16-Bit-Samples (mono). */
    fun songStop(): ShortArray? {
        if (!songRecording) return null
        songRecording = false
        synchronized(songLock) {
            if (songFrames <= 0L) return null
            val out = ShortArray(songFrames.toInt())
            var pos = 0
            val chunk = sampleRate
            for (c in songChunks) {
                val n = min(chunk, out.size - pos)
                if (n <= 0) break
                System.arraycopy(c, 0, out, pos, n)
                pos += n
            }
            songChunks.clear()
            songFrames = 0L
            return out
        }
    }

    fun songSeconds(): Float = synchronized(songLock) { songFrames.toFloat() / sampleRate }

    private fun songAppend(buf: FloatArray, n: Int) {
        synchronized(songLock) {
            val max = sampleRate.toLong() * 60L * SONG_MAX_MINUTES
            val chunk = sampleRate
            for (i in 0 until n) {
                if (songFrames >= max) break
                val ci = (songFrames / chunk).toInt()
                if (ci >= songChunks.size) songChunks.add(ShortArray(chunk))
                songChunks[ci][(songFrames % chunk).toInt()] = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                songFrames++
            }
        }
    }

    /** Modus je Pad: true = ONE SHOT, false = LOOP. */
    val padOneShot = BooleanArray(PADS)

    @Volatile var running = false
        private set
    @Volatile var error: String? = null
        private set
    var sampleRate = 48000
        private set

    private val loops = AtomicReferenceArray<Loop?>(PADS)
    private val takes = AtomicReferenceArray<Take?>(PADS)

    /** Pegel pro Pad (0..1+), für die Lichteffekte. */
    val levels = FloatArray(PADS)
    @Volatile var inputLevel = 0f
        private set
    @Volatile var outputLevel = 0f
        private set

    private val inClock = FrameClock()
    private val outClock = FrameClock()
    @Volatile private var captured = 0L
    @Volatile private var written = 0L
    private var ring = FloatArray(0)
    private var ringSize = 1

    /** Taktraster: der erste Loop legt Länge und Phase fest, alle anderen rasten darauf ein. */
    @Volatile private var masterLen = 0L

    // ---------------------------------------------------------------- Master-Tempo
    /** Fest eingestelltes Tempo in BPM (4/4, ein Takt = Raster), 0 = automatisch durch den ersten Loop. */
    @Volatile var fixedBpm = 0f
        private set
    @Volatile var metronome = false

    fun setBpm(bpm: Float) {
        val b = bpm.coerceIn(40f, 240f)
        fixedBpm = b
        if (masterLen <= 0L) masterStart = written
        masterLen = (sampleRate * 240.0 / b).toLong()
    }

    fun setAutoTempo() {
        fixedBpm = 0f
        if (!anyLooping()) masterLen = 0L
    }

    /** Aktuelles Tempo in BPM (4 Schläge pro Takt), 0 wenn noch keins festgelegt ist. */
    fun currentBpm(): Float {
        val m = masterLen
        return if (m > 0L) (sampleRate * 240.0 / m).toFloat() else 0f
    }

    private fun releaseMaster() {
        if (fixedBpm <= 0f) masterLen = 0L
    }

    private var clickStart = Long.MIN_VALUE
    private var clickHigh = false

    /** Metronom-Klick auf jeden Schlag (nicht in der Song-Aufnahme). */
    private fun addClicks(out: FloatArray, w: Long, block: Int) {
        val bar = masterLen
        if (bar <= 0L) return
        val ms = masterStart
        var n = -Math.floorDiv(-(w - ms) * 4, bar)
        while (true) {
            val f = ms + Math.floorDiv(n * bar, 4L)
            if (f >= w + block) break
            if (f >= w) {
                clickStart = f
                clickHigh = Math.floorMod(n, 4L) == 0L
            }
            n++
        }
        if (clickStart == Long.MIN_VALUE) return
        val dur = sampleRate / 25
        val freq = if (clickHigh) 1600.0 else 1000.0
        for (i in 0 until block) {
            val t = w + i - clickStart
            if (t < 0 || t >= dur) continue
            val env = kotlin.math.exp(-t / (0.012 * sampleRate))
            out[i] += (kotlin.math.sin(2.0 * Math.PI * freq * t / sampleRate) * env * 0.35).toFloat()
        }
    }
    @Volatile private var masterStart = 0L
    private var lastWritten = 0L

    // Effekte: Typ, XY-Position der Touch-Fläche, Regler MIX / P1 / P2 / VOL
    private var fx: Fx? = null
    private var fxRate = 0
    @Volatile var fxType = 0
        private set
    @Volatile var fxX = 0.5f
    @Volatile var fxY = 0.5f
    @Volatile var fxTouch = false
    val knobs = floatArrayOf(1f, Fx.P1_DEFAULT[0], Fx.P2_DEFAULT[0], 0.8f)

    fun selectFx(type: Int) {
        val t = Math.floorMod(type, Fx.COUNT)
        knobs[1] = Fx.P1_DEFAULT[t]
        knobs[2] = Fx.P2_DEFAULT[t]
        fxType = t
    }

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var recordThread: Thread? = null
    private var mixThread: Thread? = null

    // ---------------------------------------------------------------- Lifecycle

    @Synchronized
    fun start(context: Context) {
        if (running) return
        error = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val newSize = sampleRate * (MAX_SECONDS + 2)
        if (ring.size != newSize) ring = FloatArray(newSize)
        ringSize = newSize

        val t: AudioTrack = try {
            buildTrack()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            return
        }
        val r: AudioRecord = try {
            try {
                buildRecord(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION)
            } catch (e: Exception) {
                buildRecord(MediaRecorder.AudioSource.MIC)
            }
        } catch (e: Exception) {
            t.release()
            error = e.message ?: e.toString()
            return
        }
        track = t
        record = r
        if (fx == null || fxRate != sampleRate) {
            fx = Fx(sampleRate)
            fxRate = sampleRate
        }

        captured = 0L
        written = 0L
        val now = System.nanoTime()
        inClock.set(0L, now)
        outClock.set(0L, now)
        // Ausgabezähler beginnt wieder bei 0 -> alle Loops gemeinsam verschieben, Phasen bleiben erhalten
        val shift = lastWritten
        for (p in 0 until PADS) {
            takes.set(p, null)
            val l = loops.get(p)
            if (l != null) {
                l.start = l.start - shift
                l.shotStart = Long.MIN_VALUE
            }
        }
        masterStart -= shift
        lastWritten = 0L

        val block = max(64, min(burst, t.bufferSizeInFrames / 2))
        running = true
        try {
            t.play()
            r.startRecording()
        } catch (e: Exception) {
            running = false
            error = e.message ?: e.toString()
            releaseDevices()
            return
        }
        recordThread = Thread({ recordLoop(r) }, "LoopPad-Record").also { it.start() }
        mixThread = Thread({ mixLoop(t, block) }, "LoopPad-Mix").also { it.start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { record?.stop() } catch (ignored: Exception) {}
        try { recordThread?.join(500) } catch (ignored: Exception) {}
        try { mixThread?.join(500) } catch (ignored: Exception) {}
        recordThread = null
        mixThread = null
        lastWritten = written
        releaseDevices()
        for (p in 0 until PADS) takes.set(p, null)
    }

    private fun releaseDevices() {
        try { record?.release() } catch (ignored: Exception) {}
        try {
            track?.pause()
            track?.flush()
        } catch (ignored: Exception) {}
        try { track?.release() } catch (ignored: Exception) {}
        record = null
        track = null
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 4 * 256 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IllegalStateException("Audioausgabe konnte nicht gestartet werden")
        }
        return t
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(source: Int): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf * 2, 8192))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Mikrofon konnte nicht geöffnet werden")
        }
        return rec
    }

    // ---------------------------------------------------------------- Aufnahme

    private fun recordLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(256)
        val ts = AudioTimestamp()
        while (running) {
            val n = try { rec.read(chunk, 0, chunk.size) } catch (ignored: Exception) { -1 }
            if (n <= 0) {
                if (n < 0) Thread.sleep(5)
                continue
            }
            var peak = 0f
            var idx = (captured % ringSize).toInt()
            for (i in 0 until n) {
                val v = chunk[i] / 32768f
                ring[idx] = v
                idx++
                if (idx == ringSize) idx = 0
                val a = abs(v)
                if (a > peak) peak = a
            }
            captured += n
            inputLevel = max(peak, inputLevel * 0.85f)
            updateInClock(rec, ts)
            processTakes()
        }
    }

    private fun updateInClock(rec: AudioRecord, ts: AudioTimestamp) {
        val now = System.nanoTime()
        var ok = false
        try {
            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                val predicted = ts.framePosition + (now - ts.nanoTime) * sampleRate / 1_000_000_000L
                if (abs(predicted - captured) < sampleRate / 2) {
                    inClock.set(ts.framePosition, ts.nanoTime)
                    ok = true
                }
            }
        } catch (ignored: Exception) {}
        if (!ok) inClock.set(captured, now)
    }

    private fun processTakes() {
        val now = System.nanoTime()
        val maxFrames = sampleRate.toLong() * MAX_SECONDS
        for (p in 0 until PADS) {
            val take = takes.get(p) ?: continue
            if (!take.armed && now - take.downNanos >= MIN_HOLD_NANOS) {
                take.armed = true
                if (take.overdub) loops.get(p)?.muted = false
            }
            if (take.overdub) {
                if (!take.armed) continue
                val loop = loops.get(p)
                if (loop == null) {
                    takes.compareAndSet(p, take, null)
                    continue
                }
                val oldest = captured - ringSize + sampleRate
                if (take.cursor < oldest) take.cursor = oldest
                val end = min(captured, take.toIn)
                if (take.cursor < end) {
                    val d = loop.data
                    val len = d.size.toLong()
                    val t0 = inClock.nanosAt(take.cursor, sampleRate)
                    var pos = Math.floorMod(outClock.frameAt(t0, sampleRate) - loop.start, len).toInt()
                    var ri = Math.floorMod(take.cursor, ringSize.toLong()).toInt()
                    var c = take.cursor
                    while (c < end) {
                        d[pos] += ring[ri]
                        pos++
                        if (pos == d.size) pos = 0
                        ri++
                        if (ri == ringSize) ri = 0
                        c++
                    }
                    take.cursor = end
                }
                if (take.released && take.cursor >= take.toIn) takes.compareAndSet(p, take, null)
            } else {
                if (!take.released && captured - take.fromIn >= maxFrames) {
                    releaseTake(take, inClock.nanosAt(take.fromIn + maxFrames, sampleRate))
                }
                if (take.released && captured >= take.toIn) {
                    if (takes.compareAndSet(p, take, null)) finishRecording(p, take)
                }
            }
        }
    }

    private fun finishRecording(p: Int, take: Take) {
        val from = take.fromQ
        val len = (take.toIn - from).toInt()
        val minFrames = (sampleRate * (MIN_HOLD_NANOS / 1_000_000L) / 1000L).toInt()
        if (len < minFrames) return
        val data = FloatArray(len)
        val oldest = captured - ringSize
        for (i in 0 until len) {
            val f = from + i
            if (f >= oldest && f >= 0 && f < captured) data[i] = ring[(f % ringSize).toInt()]
        }
        val fade = min(len / 4, sampleRate * FADE_MS / 1000)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            data[i] *= g
            data[len - 1 - i] *= g
        }
        val loop = Loop(data)
        if (take.qOut != Take.NO_GRID) {
            // Startet exakt auf dem Rasterpunkt -> synchron zu allen anderen Loops
            loop.start = take.qOut
        } else {
            // Erster Loop: Position 0 erklingt im Moment des Loslassens und wird zum Taktgeber
            loop.start = outClock.frameAt(take.upNanos, sampleRate)
        }
        if (take.oneShot) {
            loop.oneShot = true
        } else if (masterLen == 0L) {
            masterStart = loop.start
            masterLen = len.toLong()
        }
        loops.set(p, loop)
        // Mikrofon-Aufnahme automatisch sichern (läuft außerhalb des Audio-Threads weiter)
        onRecorded?.invoke(p, data.copyOf(), sampleRate)
    }

    /** Wählt die Loop-Länge aus 1/8, 1/4, 1/2, 1, 2, 4, 8 x Taktgeber, die der gehaltenen Zeit am nächsten liegt. */
    private fun quantizeLen(held: Long): Long {
        val m = masterLen
        val maxFrames = sampleRate.toLong() * MAX_SECONDS
        var best = m
        var bestErr = Double.MAX_VALUE
        val h = max(1L, held).toDouble()
        for (k in -3..3) {
            val l = if (k >= 0) m shl k else m shr -k
            if (l < sampleRate / 20 || l > maxFrames) continue
            val err = abs(kotlin.math.ln(h / l.toDouble()))
            if (err < bestErr) {
                bestErr = err
                best = l
            }
        }
        return best
    }

    /** Beendet eine neue Aufnahme und rastet sie, falls es schon einen Taktgeber gibt, auf das Raster ein. */
    private fun releaseTake(take: Take, upNanos: Long) {
        synchronized(take) {
            if (take.released) return
            take.upNanos = upNanos
            val rawTo = max(take.fromIn, inClock.frameAt(upNanos, sampleRate))
            val m = masterLen
            if (take.overdub || m <= 0L || take.oneShot) {
                take.toIn = rawTo
            } else {
                val len = quantizeLen(rawTo - take.fromIn)
                val unit = min(len, m)
                val ms = masterStart
                val pressOut = outClock.frameAt(take.downNanos, sampleRate)
                val qOut = ms + Math.floorDiv(pressOut - ms + unit / 2, unit) * unit
                val qIn = inClock.frameAt(outClock.nanosAt(qOut, sampleRate), sampleRate)
                take.qOut = qOut
                take.fromQ = qIn
                take.toIn = qIn + len
            }
            take.released = true
        }
    }

    // ---------------------------------------------------------------- Wiedergabe

    private fun mixLoop(t: AudioTrack, block: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val out = FloatArray(block)
        val micBuf = FloatArray(block)
        micCursor = -1L
        val ts = AudioTimestamp()
        while (running) {
            java.util.Arrays.fill(out, 0f)
            val w = written
            runSequencer(w, block)
            for (p in 0 until PADS) {
                val loop = loops.get(p)
                if (loop == null) {
                    levels[p] *= 0.9f
                    continue
                }
                val d = loop.data
                val len = d.size
                if (loop.oneShot) {
                    val ss = loop.shotStart
                    var peak = 0f
                    if (ss != Long.MIN_VALUE) {
                        val rel = w - ss
                        if (rel + block > 0 && rel < len) {
                            var i = if (rel < 0) (-rel).toInt() else 0
                            var idx = if (rel < 0) 0 else rel.toInt()
                            while (i < block && idx < len) {
                                val v = d[idx]
                                val a = abs(v)
                                if (a > peak) peak = a
                                out[i] += v
                                i++
                                idx++
                            }
                        }
                    }
                    levels[p] = max(peak, levels[p] * 0.88f)
                    continue
                }
                val rel = w - loop.start
                val mute = loop.muted
                var peak = 0f
                if (rel + block > 0 && len > 0) {
                    var i: Int
                    var idx: Int
                    if (rel < 0) {
                        i = (-rel).toInt()
                        idx = 0
                    } else {
                        i = 0
                        idx = (rel % len).toInt()
                    }
                    while (i < block) {
                        val v = d[idx]
                        val a = abs(v)
                        if (a > peak) peak = a
                        if (!mute) out[i] += v
                        idx++
                        if (idx == len) idx = 0
                        i++
                    }
                }
                levels[p] = max(if (mute) peak * 0.25f else peak, levels[p] * 0.88f)
            }
            val f = fx
            if (f != null) {
                f.process(
                    out, block, w, fxType, fxX, fxY, fxTouch,
                    knobs[0], knobs[1], knobs[2], masterLen, masterStart, fillMic(micBuf, block)
                )
            }
            val vol = knobs[3] * 1.25f
            var mpeak = 0f
            for (i in 0 until block) {
                val y = softClip(out[i] * vol)
                out[i] = y
                val a = abs(y)
                if (a > mpeak) mpeak = a
            }
            outputLevel = max(mpeak, outputLevel * 0.9f)
            if (songRecording) songAppend(out, block)
            var sp = scopeW
            for (i in 0 until block) {
                scope[sp and (SCOPE - 1)] = out[i]
                sp++
            }
            scopeW = sp

            if (metronome) addClicks(out, w, block)
            var off = 0
            while (off < block && running) {
                val r = try { t.write(out, off, block - off, AudioTrack.WRITE_BLOCKING) } catch (ignored: Exception) { -1 }
                if (r <= 0) {
                    Thread.sleep(5)
                    break
                }
                off += r
            }
            written = w + block
            updateOutClock(t, ts)
        }
    }

    private var micCursor = -1L

    /** Liefert die neuesten Mikrofon-Samples für den Vocoder (läuft der Aufnahme leicht hinterher). */
    private fun fillMic(dst: FloatArray, n: Int): FloatArray {
        val c = captured
        if (micCursor < 0 || micCursor + n > c || c - micCursor > n * 16L) micCursor = c - 4L * n
        for (i in 0 until n) {
            val f = micCursor + i
            dst[i] = if (f >= 0) ring[(f % ringSize).toInt()] else 0f
        }
        micCursor += n
        return dst
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
        return if (x < 0) -y else y
    }

    private fun updateOutClock(t: AudioTrack, ts: AudioTimestamp) {
        val now = System.nanoTime()
        var ok = false
        try {
            if (t.getTimestamp(ts)) {
                val predicted = ts.framePosition + (now - ts.nanoTime) * sampleRate / 1_000_000_000L
                if (predicted <= written && written - predicted < sampleRate) {
                    outClock.set(ts.framePosition, ts.nanoTime)
                    ok = true
                }
            }
        } catch (ignored: Exception) {}
        if (!ok) outClock.set(written - t.bufferSizeInFrames, now)
    }

    // ---------------------------------------------------------------- Steuerung (UI-Thread)

    /** Gibt true zurück, wenn ein ONE SHOT angeschlagen wurde (keine Aufnahme). */
    fun padDown(p: Int): Boolean {
        if (!running || takes.get(p) != null) return false
        val loop = loops.get(p)
        if (loop != null && loop.oneShot) {
            seqSelected = p
            if (seqRecording && seqPlaying) {
                val bar = seqBar()
                val origin = seqStart()
                val heard = outClock.frameAt(System.nanoTime(), sampleRate)
                val n = Math.floorDiv((heard - origin) * STEPS + bar / 2, bar)
                val f = stepFrame(origin, bar, n)
                seq[p][Math.floorMod(n, STEPS.toLong()).toInt()] = true
                // Liegt der Rasterpunkt noch vor uns, spielt ihn gleich der Sequenzer – kein Doppelschlag
                if (f >= written) return true
            }
            loop.shotStart = written
            return true
        }
        val now = System.nanoTime()
        val from = max(0L, inClock.frameAt(now, sampleRate))
        takes.set(p, Take(loop != null, from, now, padOneShot[p]))
        return false
    }

    /** Schaltet ein Pad zwischen LOOP und ONE SHOT um (auch mit vorhandener Aufnahme). */
    fun setPadMode(p: Int, oneShot: Boolean) {
        padOneShot[p] = oneShot
        val l = loops.get(p) ?: return
        if (oneShot) {
            l.oneShot = true
            l.muted = false
            l.shotStart = Long.MIN_VALUE
            if (!anyLooping()) releaseMaster()
        } else if (l.oneShot) {
            importLoop(p, l.data.copyOf())
        }
    }

    private fun anyLooping(): Boolean {
        for (i in 0 until PADS) {
            val l = loops.get(i)
            if (l != null && !l.oneShot) return true
        }
        return false
    }

    fun isOneShot(p: Int): Boolean = loops.get(p)?.oneShot ?: padOneShot[p]

    /** Wiedergabeposition eines ONE SHOT 0..1, oder -1 wenn er gerade nicht spielt. */
    fun shotProgress(p: Int): Float {
        val l = loops.get(p) ?: return -1f
        val ss = l.shotStart
        if (!l.oneShot || ss == Long.MIN_VALUE || l.data.isEmpty()) return -1f
        val rel = outClock.frameAt(System.nanoTime(), sampleRate) - ss
        if (rel < 0 || rel >= l.data.size) return -1f
        return rel.toFloat() / l.data.size
    }

    /** Gibt true zurück, wenn es nur ein kurzer Tipp war (keine Aufnahme). */
    fun padUp(p: Int): Boolean {
        val take = takes.get(p) ?: return false
        if (take.released) return false
        val now = System.nanoTime()
        if (now - take.downNanos < MIN_HOLD_NANOS) {
            takes.compareAndSet(p, take, null)
            return true
        }
        releaseTake(take, now)
        return false
    }

    fun hasLoop(p: Int) = loops.get(p) != null

    /** Kopie der Loop-Samples für den Export. */
    fun loopData(p: Int): FloatArray? = loops.get(p)?.data?.copyOf()

    /** Setzt eine importierte Datei als Loop ein – synchron zum Takt, falls es schon einen gibt. */
    fun importLoop(p: Int, src: FloatArray) {
        if (src.isEmpty()) return
        if (padOneShot[p]) {
            val fade = min(src.size / 4, sampleRate * FADE_MS / 1000)
            for (i in 0 until fade) src[src.size - 1 - i] *= i.toFloat() / fade
            val shot = Loop(src)
            shot.oneShot = true
            takes.set(p, null)
            loops.set(p, shot)
            if (!anyLooping()) releaseMaster()
            return
        }
        val m = masterLen
        val data: FloatArray
        val start: Long
        if (m > 0L) {
            val len = quantizeLen(src.size.toLong()).toInt()
            data = src.copyOf(len)
            val heard = outClock.frameAt(System.nanoTime(), sampleRate)
            start = masterStart + Math.floorDiv(heard - masterStart, len.toLong()) * len
        } else {
            data = src
            start = written
        }
        val len = data.size
        val fade = min(len / 4, sampleRate * FADE_MS / 1000)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            data[i] *= g
            data[len - 1 - i] *= g
        }
        val loop = Loop(data)
        loop.start = start
        takes.set(p, null)
        if (m <= 0L) {
            masterStart = start
            masterLen = len.toLong()
        }
        loops.set(p, loop)
    }

    fun toggleMute(p: Int) {
        loops.get(p)?.let { if (!it.oneShot) it.muted = !it.muted }
    }

    fun toggleMuteAll() {
        var anyOn = false
        for (p in 0 until PADS) {
            val l = loops.get(p)
            if (l != null && !l.muted && !l.oneShot) anyOn = true
        }
        for (p in 0 until PADS) {
            val l = loops.get(p)
            if (l != null && !l.oneShot) l.muted = anyOn
        }
    }

    fun anyPlaying(): Boolean {
        for (p in 0 until PADS) {
            val l = loops.get(p)
            if (l != null && !l.muted && !l.oneShot) return true
        }
        return false
    }

    fun clear(p: Int) {
        takes.set(p, null)
        loops.set(p, null)
        java.util.Arrays.fill(seq[p], false)
        var any = false
        for (i in 0 until PADS) {
            val l = loops.get(i)
            if ((l != null && !l.oneShot) || takes.get(i) != null) any = true
        }
        if (!any) releaseMaster()
    }

    /** Position im Takt des Taktgebers 0..1, oder -1 wenn es noch keinen gibt. */
    fun masterProgress(): Float {
        val m = masterLen
        if (m <= 0L) return -1f
        val heard = outClock.frameAt(System.nanoTime(), sampleRate)
        return Math.floorMod(heard - masterStart, m).toFloat() / m
    }

    fun masterSeconds(): Float = masterLen.toFloat() / sampleRate

    fun state(p: Int): PadState {
        val take = takes.get(p)
        val loop = loops.get(p)
        if (take != null) {
            if (!take.overdub) return PadState.RECORDING
            if (!take.released && System.nanoTime() - take.downNanos >= MIN_HOLD_NANOS) return PadState.OVERDUB
        }
        if (loop == null) return PadState.EMPTY
        if (loop.oneShot) return PadState.ONESHOT
        return if (loop.muted) PadState.MUTED else PadState.PLAYING
    }

    /** Abspielposition 0..1 dessen, was gerade zu hören ist. */
    fun progress(p: Int): Float {
        val loop = loops.get(p) ?: return 0f
        val len = loop.data.size
        if (len == 0) return 0f
        val heard = outClock.frameAt(System.nanoTime(), sampleRate)
        val rel = heard - loop.start
        if (rel < 0) return 0f
        return (rel % len).toFloat() / len
    }

    fun loopSeconds(p: Int): Float = (loops.get(p)?.data?.size ?: 0).toFloat() / sampleRate

    fun recordingSeconds(p: Int): Float {
        val take = takes.get(p) ?: return 0f
        val end = if (take.released) take.upNanos else System.nanoTime()
        return (end - take.downNanos) / 1e9f
    }
}
