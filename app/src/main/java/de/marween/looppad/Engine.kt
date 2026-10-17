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
import kotlin.math.pow
import kotlin.math.tanh

/** Ein fertiger Loop. [start] ist der Ausgabe-Frame, an dem Position 0 erklingt. */
class Loop(val data: FloatArray) {
    @Volatile var start: Long = 0L
    @Volatile var muted: Boolean = false
    /** ONE SHOT: spielt nur einmal nach Anschlag, ab [shotStart] (Ausgabe-Frame). */
    @Volatile var oneShot: Boolean = false
    @Volatile var shotStart: Long = Long.MIN_VALUE
    /** Lautstärke des aktuellen Anschlags (Sequenzer-Schritt). */
    @Volatile var shotGain: Float = 1f
    /** Aktuelle Ein/Aus-Blende (nur Mischer-Thread). */
    var fade: Float = 1f
    /** Tonhöhe (Halbtöne) für ONE SHOTS, als Abspielrate. */
    @Volatile var semi: Int = 0
    @Volatile var rate: Double = 1.0
    /** Slice-Grenzen (n+1 Werte) – gesetzt, wenn der Loop in Slices zerlegt wurde und dem Tempo folgt. */
    @Volatile var slices: IntArray? = null
    /** Länge in Takten (bei Slices), passt sich dem Master-Tempo an. */
    @Volatile var bars: Float = 1f
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
    /** 0 = Mikrofon, 1 = Resample (Master-Ausgang). */
    var src: Int = 0
    /** Live-Aufnahme (Antippen statt Halten). */
    var live: Boolean = false
    /** Bounce: Pads, die während dieser Wave-Aufnahme mitspielten. */
    var bounceSrc: IntArray? = null
    /** Bei Resample: Ausgabe-Frames der Aufnahme. */
    @Volatile var fromOut: Long = 0L
    @Volatile var toOut: Long = Long.MAX_VALUE

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
    /** Längste Aufnahme (16 Takte bei 60 BPM passen hinein). */
    const val MAX_SECONDS = 66
    private const val MIN_HOLD_NANOS = 180_000_000L
    private const val FADE_MS = 4

    enum class PadState { EMPTY, RECORDING, PLAYING, MUTED, OVERDUB, ONESHOT }

    // ---------------------------------------------------------------- Sequenzer
    const val STEPS = 16
    /** Lautstärke je Schritt (0 = aus, bis 1). */
    private val seq = Array(PADS) { FloatArray(STEPS) }
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
        for (row in seq) java.util.Arrays.fill(row, 0f)
        for (p in 0 until PADS) clearNotes(p)
    }

    fun seqHas(p: Int, step: Int): Boolean = seq[p][step] > 0f
    fun seqVel(p: Int, step: Int): Float = seq[p][step]
    fun setSeqVel(p: Int, step: Int, v: Float) {
        if (p in 0 until PADS && step in 0 until STEPS) seq[p][step] = v.coerceIn(0f, 1f)
    }

    fun seqCount(step: Int): Int {
        var c = 0
        for (p in 0 until PADS) if (seq[p][step] > 0f) c++
        return c
    }

    fun seqToggle(p: Int, step: Int) {
        if (p in 0 until PADS && step in 0 until STEPS) seq[p][step] = if (seq[p][step] > 0f) 0f else 1f
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
        runRoll(w, block)
        val origin = seqStart()
        var n = -Math.floorDiv(-(w - origin) * STEPS, bar) // ceil
        while (true) {
            val f = stepFrame(origin, bar, n)
            if (f >= w + block) break
            if (f >= w) {
                val step = Math.floorMod(n, STEPS.toLong()).toInt()
                for (p in 0 until PADS) {
                    val vel = seq[p][step]
                    if (vel <= 0f) continue
                    val l = loops.get(p)
                    if (l != null && l.oneShot) {
                        l.shotGain = vel
                        l.shotStart = f
                    }
                }
            }
            n++
        }
    }

    // ---------------------------------------------------------------- Projekte

    /** Wird nach jeder neuen Mikrofon-Aufnahme aufgerufen (Pad, Samples, Samplerate). */
    var onRecorded: ((Int, FloatArray, Int) -> Unit)? = null

    class PadSave(
        val data: FloatArray?, val oneShot: Boolean, val muted: Boolean,
        val slices: Int = 0, val bars: Float = 1f, val semi: Int = 0
    )

    fun snapshotPads(): Array<PadSave> = Array(PADS) { p ->
        val l = loops.get(p)
        PadSave(
            l?.data?.copyOf(), l?.oneShot ?: padOneShot[p], l?.muted ?: false,
            l?.slices?.let { it.size - 1 } ?: 0, l?.bars ?: 1f, l?.semi ?: padSemi[p]
        )
    }

    fun masterFrames(): Long = masterLen

    fun seqSnapshot(): Array<FloatArray> = Array(PADS) { seq[it].copyOf() }

    fun seqHasAny(): Boolean {
        for (row in seq) for (b in row) if (b > 0f) return true
        for (p in 0 until PADS) if (noteCount(p) > 0) return true
        return false
    }

    /** Stellt einen gespeicherten Projektzustand wieder her (alle Loops starten gemeinsam auf der Eins). */
    fun restore(pads: Array<PadSave>, master: Long, bpm: Float, pattern: Array<FloatArray>?) {
        seqStop()
        for (p in 0 until PADS) {
            takes.set(p, null)
            loops.set(p, null)
            java.util.Arrays.fill(seq[p], 0f)
            clearNotes(p)
            padOneShot[p] = false
            rollBars[p] = 1
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
            padSemi[p] = sv.semi
            l.semi = sv.semi
            l.rate = 2.0.pow(sv.semi / 12.0)
            if (sv.slices > 1 && !sv.oneShot) {
                l.slices = IntArray(sv.slices + 1) { (it.toLong() * d.size / sv.slices).toInt() }
                l.bars = sv.bars
            }
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
        val bar1 = seqBar().toInt()
        if (bar1 <= 0) return null
        var nb = 1
        for (p in 0 until PADS) if (noteCount(p) > 0) nb = max(nb, rollBars[p])
        val bar = bar1 * nb
        val out = FloatArray(bar)
        var any = false
        for (p in 0 until PADS) {
            val l = loops.get(p) ?: continue
            if (!l.oneShot) continue
            val d = l.data
            for (k in 0 until STEPS) {
                val vel = seq[p][k]
                if (vel <= 0f) continue
                any = true
                for (b in 0 until nb) {
                    val st = b * bar1 + ((k.toLong() * bar1) / STEPS).toInt()
                    for (i in d.indices) {
                        val idx = (st + i) % bar
                        out[idx] += d[i] * vel
                    }
                }
            }
        }
        for (p in 0 until PADS) {
            val l = loops.get(p) ?: continue
            val d = l.data
            if (d.size < 2) continue
            val rb = rollBars[p]
            for (ev in notesOf(p)) {
                any = true
                val rate = 2.0.pow(ev.semi / 12.0)
                val lenF = max(64, (ev.len * bar1).toInt())
                for (rep in 0 until nb / rb) {
                val st = ((rep * rb + ev.pos) * bar1).toInt()
                val a3 = ev.vel
                val rel = (sampleRate * 0.03).toInt()
                var pos = 0.0
                for (i in 0 until lenF + rel) {
                    var i0 = pos.toInt()
                    if (i0 >= d.size - 1) {
                        if (l.oneShot) break
                        pos -= (d.size - 1).toDouble()
                        i0 = pos.toInt().coerceIn(0, d.size - 2)
                    }
                    val fr = (pos - i0).toFloat()
                    var v = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * a3
                    if (i >= lenF) v *= 1f - (i - lenF).toFloat() / rel
                    out[(st + i) % bar] += v
                    pos += rate
                }
                }
            }
        }
        if (!any) return null
        for (i in out.indices) out[i] = softClip(out[i])
        return out
    }

    // ---------------------------------------------------------------- Bewegungs-Aufnahme (XY-Automation)

    const val MOT_IDLE = 0
    const val MOT_ARMED = 1
    const val MOT_REC = 2
    const val MOT_PLAY = 3

    @Volatile var motionState = MOT_IDLE
        private set
    /** Finger liegt wirklich auf der XY-Fläche (hat Vorrang vor der Automation). */
    @Volatile var liveTouch = false
    @Volatile var motCurX = 0.5f
        private set
    @Volatile var motCurY = 0.5f
        private set
    @Volatile var motCurTouch = false
        private set

    private const val MOT_CAP = 40000
    private val motX = FloatArray(MOT_CAP)
    private val motY = FloatArray(MOT_CAP)
    private val motT = BooleanArray(MOT_CAP)
    @Volatile private var motCount = 0
    private var motStart = 0L
    private var motLen = 1L
    private var motBlock = 256
    @Volatile private var motStopReq = false
    private var effX = 0.5f
    private var effY = 0.5f
    private var effTouch = false

    /** REC-Knopf der XY-Fläche: scharf schalten → (Aufnahme) → abspielen → aus. */
    fun motionButton() {
        when (motionState) {
            MOT_IDLE -> motionState = MOT_ARMED
            MOT_ARMED -> motionState = MOT_IDLE
            MOT_REC -> motStopReq = true
            else -> {
                motionState = MOT_IDLE
                motCount = 0
            }
        }
    }

    fun motionSeconds(): Float = when (motionState) {
        MOT_REC -> motCount * motBlock.toFloat() / sampleRate
        MOT_PLAY -> motLen.toFloat() / sampleRate
        else -> 0f
    }

    /** Fortschritt der Wiedergabe 0..1 (oder -1). */
    fun motionProgress(): Float {
        if (motionState != MOT_PLAY || motLen <= 0) return -1f
        val heard = outClock.frameAt(System.nanoTime(), sampleRate)
        return Math.floorMod(heard - motStart, motLen).toFloat() / motLen
    }

    private fun finishMotion() {
        val n = motCount
        if (n < 4) {
            motionState = MOT_IDLE
            motCount = 0
            return
        }
        var len = n.toLong() * motBlock
        val bar = masterLen
        if (bar > 0L) {
            val beat = max(1L, bar / 4)
            len = max(beat, ((len + beat / 2) / beat) * beat)
            motStart = masterStart + Math.floorDiv(motStart - masterStart + beat / 2, beat) * beat
        }
        motLen = len
        motionState = MOT_PLAY
    }

    /** Pro Mischer-Block: Bewegung aufnehmen bzw. abspielen und die wirksamen XY-Werte bestimmen. */
    private fun motionTick(w: Long, block: Int) {
        effX = fxX
        effY = fxY
        effTouch = fxTouch
        when (motionState) {
            MOT_ARMED -> if (liveTouch) {
                motBlock = block
                motCount = 0
                motStart = w
                motionState = MOT_REC
            }
            MOT_REC -> {
                val c = motCount
                if (c < MOT_CAP) {
                    motX[c] = fxX
                    motY[c] = fxY
                    motT[c] = liveTouch
                    motCount = c + 1
                }
                if (motStopReq || motCount >= MOT_CAP) {
                    motStopReq = false
                    finishMotion()
                }
            }
            MOT_PLAY -> {
                val n = motCount
                if (n > 0 && motLen > 0) {
                    val rel = Math.floorMod(w - motStart, motLen)
                    val idx = ((rel * n) / motLen).toInt().coerceIn(0, n - 1)
                    motCurX = motX[idx]
                    motCurY = motY[idx]
                    motCurTouch = motT[idx]
                    if (!liveTouch) {
                        effX = motCurX
                        effY = motCurY
                        effTouch = motCurTouch || fxTouch
                    }
                }
            }
        }
        if (motStopReq && motionState != MOT_REC) motStopReq = false
    }

    // ---------------------------------------------------------------- Effekt-Ziel

    /** -1 = Effekt auf den ganzen Song, sonst nur auf dieses Pad. */
    /** Effekt je Pad an/aus (true = Pad läuft durch den Effekt). */
    val padFx = BooleanArray(PADS) { true }

    fun setAllFx(on: Boolean) {
        for (p in 0 until PADS) padFx[p] = on
    }

    // ---------------------------------------------------------------- Tastatur (Stimmen)

    private class Voice {
        var data: FloatArray = FloatArray(0)
        var pos = 0.0
        var rate = 1.0
        var loop = false
        var gain = 1f
        var releasing = false
        var active = false
        var pad = -1
        var id = 0
        var vel = 1f
        var wait = 0
        var offFrame = Long.MAX_VALUE
    }

    private val voices = Array(16) { Voice() }
    private val voiceLock = Any()
    private var voiceIds = 0

    private fun startVoice(data: FloatArray, pad: Int, rate: Double, loop: Boolean): Int {
        if (data.size < 2) return -1
        synchronized(voiceLock) {
            var v = voices.firstOrNull { !it.active }
            if (v == null) v = voices.minByOrNull { it.id }!!
            v.data = data
            v.pos = 0.0
            v.rate = rate
            v.loop = loop
            v.gain = 1f
            v.releasing = false
            v.pad = pad
            v.vel = 1f
            v.wait = 0
            v.offFrame = Long.MAX_VALUE
            v.id = ++voiceIds
            v.active = true
            return v.id
        }
    }

    /** Spielt das Sample eines Pads in einer Tonhöhe (Halbtöne relativ zum Original). */
    fun noteOn(p: Int, semi: Int): Int {
        val l = loops.get(p) ?: return -1
        return startVoice(l.data, p, 2.0.pow(semi / 12.0), !l.oneShot)
    }

    fun noteOff(id: Int) {
        synchronized(voiceLock) {
            for (v in voices) if (v.active && v.id == id) v.releasing = true
        }
    }

    fun allNotesOff() {
        synchronized(voiceLock) {
            for (v in voices) if (v.active) v.releasing = true
        }
    }

    /** Vorhören eines Ausschnitts (Wave-Editor). */
    fun preview(data: FloatArray): Int = startVoice(data, -1, 1.0, false)

    private fun renderVoices(out: FloatArray, bus: FloatArray, block: Int, w: Long) {
        synchronized(voiceLock) {
            val relStep = 1f / (0.12f * sampleRate)
            for (v in voices) {
                if (!v.active) continue
                val d = v.data
                val len = d.size
                val dst = if (v.pad >= 0 && padFx[v.pad]) bus else out
                var peak = 0f
                for (i in 0 until block) {
                    if (v.wait > 0) {
                        v.wait--
                        continue
                    }
                    if (!v.releasing && w + i >= v.offFrame) v.releasing = true
                    var i0 = v.pos.toInt()
                    if (i0 >= len - 1) {
                        if (v.loop) {
                            v.pos -= (len - 1).toDouble()
                            i0 = v.pos.toInt().coerceIn(0, len - 2)
                        } else {
                            v.active = false
                            break
                        }
                    }
                    val fr = (v.pos - i0).toFloat()
                    val sm = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * v.gain * v.vel
                    dst[i] += sm
                    val a = abs(sm)
                    if (a > peak) peak = a
                    if (v.releasing) {
                        v.gain -= relStep
                        if (v.gain <= 0f) {
                            v.active = false
                            break
                        }
                    }
                    v.pos += v.rate
                }
                if (v.pad in 0 until PADS) levels[v.pad] = max(levels[v.pad], peak)
            }
        }
    }

    // ---------------------------------------------------------------- Matrix / Piano-Roll (Tastatur-Aufnahme)

    /** Eine Note: Position und Länge in Takten (frei, nicht zwingend auf Raster), Halbton, Lautstärke. */
    class NoteEv(var pos: Double, var len: Double, val semi: Int, val vel: Float) {
        /** Ausgabe-Frame, vor dem die Note nicht (nochmal) ausgelöst wird – verhindert Doppelschlag nach Live-Aufnahme. */
        @Volatile var validFrom: Long = Long.MIN_VALUE
        var startHeard: Long = 0L
    }

    private val notes = Array(PADS) { ArrayList<NoteEv>() }
    private val notesLock = Any()
    /** Länge der Matrix je Pad in Takten (1, 2, 4, 8). */
    val rollBars = IntArray(PADS) { 1 }
    /** Raster bei der Aufnahme: 0 = frei, sonst Teilung pro Takt (4, 8, 16, 32). */
    @Volatile var rollQuant = 16

    private fun qPos(v: Double, q: Int): Double = if (q <= 0) v else kotlin.math.round(v * q) / q
    private fun qLen(v: Double, q: Int): Double = if (q <= 0) max(1.0 / 64, v) else max(1.0 / q, kotlin.math.round(v * q) / q)

    /** Beginnt eine Note in der Pattern-Aufnahme (nur wenn REC Pattern läuft). */
    fun recordNoteOn(p: Int, semi: Int): NoteEv? {
        if (!seqRecording || !seqPlaying || p !in 0 until PADS) return null
        val bar = seqBar()
        val origin = seqStart()
        val bars = rollBars[p]
        val loopLen = bar * bars
        val heard = outClock.frameAt(System.nanoTime(), sampleRate)
        val rel = Math.floorMod(heard - origin, loopLen)
        var pos = rel.toDouble() / bar
        pos = qPos(pos, rollQuant)
        if (pos >= bars) pos -= bars
        val ev = NoteEv(pos, 1.0 / max(4, rollQuant), semi, 1f)
        ev.startHeard = heard
        // in diesem Durchlauf schon live gespielt → erst im nächsten Durchlauf wieder auslösen
        ev.validFrom = heard + loopLen / 2
        synchronized(notesLock) {
            notes[p].removeAll { abs(it.pos - ev.pos) < 1e-6 && it.semi == semi }
            notes[p].add(ev)
        }
        return ev
    }

    fun recordNoteOff(ev: NoteEv) {
        val bar = seqBar()
        val heard = outClock.frameAt(System.nanoTime(), sampleRate)
        val raw = max(1L, heard - ev.startHeard).toDouble() / bar
        ev.len = qLen(raw, rollQuant)
    }

    /** Sequenzer-Anzeige: liegt im ersten Takt eine Note auf diesem 1/16-Schritt? */
    fun noteAt(p: Int, step: Int): Boolean {
        if (p !in 0 until PADS) return false
        synchronized(notesLock) { return notes[p].any { ((it.pos * STEPS).toInt() % STEPS) == step } }
    }

    fun noteCount(p: Int): Int = if (p in 0 until PADS) synchronized(notesLock) { notes[p].size } else 0

    /** Kopie der Noten eines Pads (für Matrix-Ansicht). */
    fun notesOf(p: Int): List<NoteEv> = synchronized(notesLock) { notes[p].map { NoteEv(it.pos, it.len, it.semi, it.vel) } }

    /** Für Projekte: [Position in 1/16, Halbton, Länge in 1/16, Lautstärke] je Note. */
    fun getNotes(p: Int): List<FloatArray> = synchronized(notesLock) {
        notes[p].map { floatArrayOf((it.pos * STEPS).toFloat(), it.semi.toFloat(), (it.len * STEPS).toFloat(), it.vel) }
    }

    fun setNotes(p: Int, list: List<FloatArray>) {
        synchronized(notesLock) {
            notes[p].clear()
            for (a in list) if (a.size >= 4) notes[p].add(NoteEv(a[0] / STEPS.toDouble(), a[2] / STEPS.toDouble(), a[1].toInt(), a[3]))
        }
    }

    fun clearNotes(p: Int) {
        synchronized(notesLock) { notes[p].clear() }
    }

    /** Matrix: Note setzen oder (falls dort schon eine liegt) löschen. */
    fun toggleNote(p: Int, pos: Double, semi: Int, len: Double) {
        synchronized(notesLock) {
            val hit = notes[p].firstOrNull { it.semi == semi && pos >= it.pos - 1e-6 && pos < it.pos + it.len - 1e-6 }
            if (hit != null) notes[p].remove(hit) else notes[p].add(NoteEv(pos, len, semi, 1f))
        }
    }

    /** Entfernt die Note, die an dieser Stelle liegt. */
    fun removeNoteAt(p: Int, pos: Double, semi: Int): Boolean = synchronized(notesLock) {
        val hit = notes[p].firstOrNull { it.semi == semi && pos >= it.pos - 1e-6 && pos < it.pos + it.len - 1e-6 }
        if (hit != null) notes[p].remove(hit)
        hit != null
    }

    /** Nachträglich quantisieren: Positionen und Längen aufs Raster (q Teilungen pro Takt). */
    fun quantizeNotes(p: Int, q: Int) {
        if (q <= 0) return
        synchronized(notesLock) {
            val bars = rollBars[p].toDouble()
            for (n in notes[p]) {
                n.pos = qPos(n.pos, q)
                if (n.pos >= bars) n.pos -= bars
                n.len = qLen(n.len, q)
            }
            // doppelte Noten nach dem Quantisieren entfernen
            val seen = HashSet<String>()
            notes[p].removeAll { !seen.add("${it.semi}@${kotlin.math.round(it.pos * 1024)}") }
        }
    }

    fun setRollBars(p: Int, bars: Int) {
        rollBars[p] = bars
        synchronized(notesLock) { notes[p].removeAll { it.pos >= bars } }
    }

    /** Fortschritt in der Matrix eines Pads 0..1 (oder -1). */
    fun rollProgress(p: Int): Float {
        if (!seqPlaying) return -1f
        val loopLen = seqBar() * rollBars[p]
        val rel = outClock.frameAt(System.nanoTime(), sampleRate) - seqStart()
        if (rel < 0) return -1f
        return Math.floorMod(rel, loopLen).toFloat() / loopLen
    }

    /** Spielt fällige Matrix-Noten sample-genau über die Stimmen. */
    private fun runRoll(w: Long, block: Int) {
        val bar = seqBar()
        if (bar <= 0L) return
        val origin = seqStart()
        val end = w + block
        synchronized(notesLock) {
            for (p in 0 until PADS) {
                val list = notes[p]
                if (list.isEmpty()) continue
                val l = loops.get(p) ?: continue
                val loopLen = bar * rollBars[p]
                for (ev in list) {
                    val off = (ev.pos * bar).toLong()
                    val k = -Math.floorDiv(-(w - origin - off), loopLen)
                    val f = origin + k * loopLen + off
                    if (f >= end || f < w) continue
                    if (f < ev.validFrom) continue
                    val id = startVoice(l.data, p, 2.0.pow((ev.semi + 0) / 12.0), !l.oneShot)
                    if (id < 0) continue
                    synchronized(voiceLock) {
                        for (v in voices) if (v.id == id) {
                            v.wait = (f - w).toInt().coerceAtLeast(0)
                            v.vel = ev.vel
                            v.offFrame = f + max(64L, (ev.len * bar).toLong())
                        }
                    }
                }
            }
        }
    }

    /** Feste Tonhöhe eines Pads (wirkt auf ONE SHOTS, auch im Sequenzer). */
    val padSemi = IntArray(PADS)

    fun setPadPitch(p: Int, semi: Int) {
        padSemi[p] = semi
        val l = loops.get(p) ?: return
        l.semi = semi
        l.rate = 2.0.pow(semi / 12.0)
    }

    // ---------------------------------------------------------------- Wave-Editor

    /** Schlägt die passende Länge in Takten (¼ … 8) für eine Länge in Frames vor. */
    fun suggestBars(frames: Int): Float {
        val m = masterLen
        if (m <= 0L) return 1f
        val opts = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)
        var best = 1f
        var err = Double.MAX_VALUE
        for (o in opts) {
            val e = abs(kotlin.math.ln(frames.toDouble() / (m * o)))
            if (e < err) {
                err = e
                best = o
            }
        }
        return best
    }

    fun padSlices(p: Int): Int = loops.get(p)?.slices?.let { it.size - 1 } ?: 0
    fun padBars(p: Int): Float = loops.get(p)?.bars ?: 1f
    fun hasTempo(): Boolean = masterLen > 0L

    /** Schneidet einen Pad-Inhalt zu und zerlegt ihn optional in Slices, die dem Tempo folgen. */
    fun applyEdit(p: Int, start: Int, end: Int, slices: Int, bars: Float) {
        val l = loops.get(p) ?: return
        val s0 = start.coerceIn(0, max(0, l.data.size - 2))
        val e0 = end.coerceIn(s0 + 2, l.data.size)
        val data = l.data.copyOfRange(s0, e0)
        if (l.oneShot || slices <= 1) {
            padOneShot[p] = l.oneShot
            val semi = l.semi
            importLoop(p, data)
            if (l.oneShot) setPadPitch(p, semi)
            return
        }
        val len = data.size
        val fade = min(len / 4, sampleRate * FADE_MS / 1000)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            data[i] *= g
            data[len - 1 - i] *= g
        }
        val loop = Loop(data)
        loop.slices = IntArray(slices + 1) { (it.toLong() * len / slices).toInt() }
        if (masterLen <= 0L) {
            masterLen = len.toLong()
            masterStart = written
            loop.bars = 1f
        } else {
            loop.bars = bars
        }
        val tl = max(1L, (masterLen * loop.bars).toLong())
        val heard = outClock.frameAt(System.nanoTime(), sampleRate)
        loop.start = masterStart + Math.floorDiv(heard - masterStart, tl) * tl
        loop.muted = l.muted
        takes.set(p, null)
        loops.set(p, loop)
    }

    /** Verteilt die Slices eines Ausschnitts als ONE SHOTS auf freie Pads. Gibt die Anzahl zurück. */
    fun distributeSlices(p: Int, start: Int, end: Int, n: Int, maxPad: Int): Int {
        val l = loops.get(p) ?: return 0
        val s0 = start.coerceIn(0, max(0, l.data.size - 2))
        val e0 = end.coerceIn(s0 + 2, l.data.size)
        val len = e0 - s0
        var q = 0
        var count = 0
        for (k in 0 until n) {
            while (q < maxPad && (q == p || loops.get(q) != null)) q++
            if (q >= maxPad) break
            val a = s0 + (k.toLong() * len / n).toInt()
            val b = s0 + ((k + 1).toLong() * len / n).toInt()
            if (b - a < 2) continue
            padOneShot[q] = true
            importLoop(q, l.data.copyOfRange(a, b))
            count++
            q++
        }
        return count
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

    /** Song-Aufnahme ohne Effekte (trocken). */
    @Volatile var songDry = false

    fun songStart(dry: Boolean = false) {
        songDry = dry
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
    private var fxs: Array<Fx>? = null
    private var fxRate = 0
    @Volatile var fxX = 0.5f
    @Volatile var fxY = 0.5f
    @Volatile var fxTouch = false
    /** Regler: MIX / P1 / P2 des gerade bearbeiteten Kettenplatzes, VOL (global). */
    val knobs = floatArrayOf(1f, Fx.P1_DEFAULT[0], Fx.P2_DEFAULT[0], 0.8f)

    // ---------------------------------------------------------------- Effekt-Kette (3 Plätze)
    const val SLOTS = 3
    val slotType = intArrayOf(0, Fx.DELAY, Fx.REVERB)
    val slotOn = booleanArrayOf(true, false, false)
    /** MIX / P1 / P2 je Platz (der bearbeitete Platz nutzt live die Regler oben). */
    val slotKnobs = Array(SLOTS) { s -> floatArrayOf(1f, Fx.P1_DEFAULT[slotType[s]], Fx.P2_DEFAULT[slotType[s]]) }
    @Volatile var editSlot = 0
        private set

    /** Effekt des gerade bearbeiteten Kettenplatzes. */
    val fxType: Int get() = slotType[editSlot]

    fun selectFx(type: Int) {
        val t = Math.floorMod(type, Fx.COUNT)
        knobs[1] = Fx.P1_DEFAULT[t]
        knobs[2] = Fx.P2_DEFAULT[t]
        slotType[editSlot] = t
        slotOn[editSlot] = true
    }

    /** Wechselt den bearbeiteten Kettenplatz (Regler-Werte werden je Platz gemerkt). */
    fun selectSlot(s: Int) {
        if (s !in 0 until SLOTS || s == editSlot) return
        for (k in 0 until 3) slotKnobs[editSlot][k] = knobs[k]
        for (k in 0 until 3) knobs[k] = slotKnobs[s][k]
        editSlot = s
    }

    fun toggleSlot(s: Int) {
        if (s in 0 until SLOTS) slotOn[s] = !slotOn[s]
    }

    fun activeSlots(): Int = (0 until SLOTS).count { slotOn[it] }

    /** Für Projekte: Regler eines Platzes (aktueller Stand). */
    fun slotKnob(s: Int, k: Int): Float = if (s == editSlot) knobs[k] else slotKnobs[s][k]

    fun setSlot(s: Int, type: Int, on: Boolean, mix: Float, p1: Float, p2: Float) {
        if (s !in 0 until SLOTS) return
        slotType[s] = Math.floorMod(type, Fx.COUNT)
        slotOn[s] = on
        slotKnobs[s][0] = mix; slotKnobs[s][1] = p1; slotKnobs[s][2] = p2
        if (s == editSlot) { knobs[0] = mix; knobs[1] = p1; knobs[2] = p2 }
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
        if (outRing.size != newSize) outRing = FloatArray(newSize)

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
        if (fxs == null || fxRate != sampleRate) {
            fxs = Array(SLOTS) { Fx(sampleRate) }
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
            if (take.src == 1) continue
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
        padSemi[p] = 0
        loops.set(p, loop)
        // Mikrofon-Aufnahme automatisch sichern (läuft außerhalb des Audio-Threads weiter)
        onRecorded?.invoke(p, data.copyOf(), sampleRate)
    }

    /** Wählt die Loop-Länge aus 1/8, 1/4, 1/2, 1, 2, 4, 8 x Taktgeber, die der gehaltenen Zeit am nächsten liegt. */
    /** Gehaltene Aufnahme → 1, 2, 4, 8 oder 16 ganze Takte (was der Haltezeit am nächsten liegt). */
    private fun quantizeBars(held: Long): Long {
        val m = masterLen
        val maxFrames = sampleRate.toLong() * MAX_SECONDS
        val h = max(1L, held).toDouble()
        var best = m
        var bestErr = Double.MAX_VALUE
        for (b in intArrayOf(1, 2, 4, 8, 16, 32)) {
            val l = m * b
            if (l > maxFrames && b > 1) break
            val err = abs(kotlin.math.ln(h / l.toDouble()))
            if (err < bestErr) {
                bestErr = err
                best = l
            }
        }
        return best
    }

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
            if (take.src == 1) {
                val rawOut = max(take.fromOut + 1, outClock.frameAt(upNanos, sampleRate))
                val m1 = masterLen
                if (m1 <= 0L || take.oneShot) {
                    take.toOut = rawOut
                } else {
                    val len = quantizeBars(rawOut - take.fromOut)
                    val unit = min(len, m1)
                    val ms = masterStart
                    val qOut = ms + Math.floorDiv(take.fromOut - ms + unit / 2, unit) * unit
                    take.qOut = qOut
                    take.fromOut = qOut
                    take.toOut = qOut + len
                }
                take.released = true
                return
            }
            val rawTo = max(take.fromIn, inClock.frameAt(upNanos, sampleRate))
            val m = masterLen
            if (take.overdub || m <= 0L || take.oneShot) {
                take.toIn = rawTo
            } else {
                val len = quantizeBars(rawTo - take.fromIn)
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
        val bus = FloatArray(block)
        val dryBuf = FloatArray(block)
        val micBuf = FloatArray(block)
        micCursor = -1L
        val ts = AudioTimestamp()
        while (running) {
            java.util.Arrays.fill(out, 0f)
            java.util.Arrays.fill(bus, 0f)
            val w = written
            runSequencer(w, block)
            val mlen = masterLen
            for (p in 0 until PADS) {
                val loop = loops.get(p)
                if (loop == null) {
                    levels[p] *= 0.9f
                    continue
                }
                val dst = if (padFx[p]) bus else out
                val d = loop.data
                val len = d.size
                if (loop.oneShot) {
                    val ss = loop.shotStart
                    var peak = 0f
                    if (ss != Long.MIN_VALUE && len > 1) {
                        val rate = loop.rate
                        val sg = loop.shotGain
                        val rel = w - ss
                        val total = (len / rate).toLong()
                        if (rel + block > 0 && rel < total) {
                            var i = if (rel < 0) (-rel).toInt() else 0
                            while (i < block) {
                                val sp = (rel + i) * rate
                                val i0 = sp.toInt()
                                if (i0 >= len - 1) break
                                val fr = (sp - i0).toFloat()
                                val v = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * sg
                                val a = abs(v)
                                if (a > peak) peak = a
                                dst[i] += v
                                i++
                            }
                        }
                    }
                    levels[p] = max(peak, levels[p] * 0.88f)
                    continue
                }
                val rel = w - loop.start
                val mute = loop.muted
                val fadeTarget = if (mute) 0f else 1f
                val fadeStep = 1f / (0.006f * sampleRate)
                var fg = loop.fade
                var peak = 0f
                val sl = loop.slices
                if (sl != null && mlen > 0L && len > 0) {
                    // Slices: jeder Slice startet auf seinem Rasterplatz im aktuellen Tempo
                    val n = sl.size - 1
                    val tl = max(1L, (mlen * loop.bars).toLong())
                    val fadeN = 96
                    for (i in 0 until block) {
                        val r = rel + i
                        if (r < 0) continue
                        val pos = Math.floorMod(r, tl)
                        val k = ((pos * n) / tl).toInt().coerceIn(0, n - 1)
                        val slotStart = (k.toLong() * tl) / n
                        val slotEnd = ((k + 1).toLong() * tl) / n
                        val off = pos - slotStart
                        val sLen = (sl[k + 1] - sl[k]).toLong()
                        if (off >= sLen) continue
                        var v = d[sl[k] + off.toInt()]
                        if (off < fadeN) v *= off / fadeN.toFloat()
                        val endRem = min(sLen - off, slotEnd - pos)
                        if (endRem < fadeN) v *= endRem / fadeN.toFloat()
                        val a = abs(v)
                        if (a > peak) peak = a
                        fg = if (fg < fadeTarget) min(fadeTarget, fg + fadeStep) else max(fadeTarget, fg - fadeStep)
                        dst[i] += v * fg
                    }
                } else if (rel + block > 0 && len > 0) {
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
                        fg = if (fg < fadeTarget) min(fadeTarget, fg + fadeStep) else max(fadeTarget, fg - fadeStep)
                        dst[i] += v * fg
                        idx++
                        if (idx == len) idx = 0
                        i++
                    }
                }
                loop.fade = fg
                levels[p] = max(if (mute) peak * 0.25f else peak, levels[p] * 0.88f)
            }
            renderVoices(out, bus, block, w)
            if (songRecording && songDry) {
                for (i in 0 until block) dryBuf[i] = out[i] + bus[i]
            }
            motionTick(w, block)
            val chain = fxs
            if (chain != null) {
                val mic = fillMic(micBuf, block)
                val es = editSlot
                for (s in 0 until SLOTS) {
                    if (!slotOn[s]) continue
                    val kn = if (s == es) knobs else slotKnobs[s]
                    chain[s].process(
                        bus, block, w, slotType[s], effX, effY, effTouch,
                        kn[0], kn[1], kn[2], masterLen, masterStart, mic
                    )
                }
            }
            for (i in 0 until block) out[i] += bus[i]
            writeOutRing(out, w, block)
            processResampleTakes(w, block)
            val vol = knobs[3] * 1.25f
            var mpeak = 0f
            for (i in 0 until block) {
                val y = softClip(out[i] * vol)
                out[i] = y
                val a = abs(y)
                if (a > mpeak) mpeak = a
            }
            outputLevel = max(mpeak, outputLevel * 0.9f)
            if (songRecording) {
                if (songDry) {
                    for (i in 0 until block) dryBuf[i] = softClip(dryBuf[i] * vol)
                    songAppend(dryBuf, block)
                } else {
                    songAppend(out, block)
                }
            }
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
        if (!running) return false
        val existing = takes.get(p)
        if (existing != null) {
            if (existing.live) {
                // Live: zweites Antippen beendet eine offene Aufnahme bzw. bricht eine geplante ab
                if (!existing.released) releaseTake(existing, System.nanoTime())
                else takes.compareAndSet(p, existing, null)
            }
            return false
        }
        val loop = loops.get(p)
        if (loop != null && loop.oneShot) {
            seqSelected = p
            if (seqRecording && seqPlaying) {
                val bar = seqBar()
                val origin = seqStart()
                val heard = outClock.frameAt(System.nanoTime(), sampleRate)
                val n = Math.floorDiv((heard - origin) * STEPS + bar / 2, bar)
                val f = stepFrame(origin, bar, n)
                seq[p][Math.floorMod(n, STEPS.toLong()).toInt()] = 1f
                // Liegt der Rasterpunkt noch vor uns, spielt ihn gleich der Sequenzer – kein Doppelschlag
                if (f >= written) return true
            }
            loop.shotGain = 1f
            loop.shotStart = written
            return true
        }
        val now = System.nanoTime()
        if (loop == null && (liveBars != 0 || padSource[p] == 1)) {
            startNewTake(p, now)
            return false
        }
        val from = max(0L, inClock.frameAt(now, sampleRate))
        takes.set(p, Take(loop != null, from, now, padOneShot[p]))
        return false
    }

    // ---------------------------------------------------------------- Resample & Live-Aufnahme

    /** Eingang je Pad: 0 = Mikrofon, 1 = Wave (nimmt auf, was die App ausgibt – inkl. Effekte). */
    val padSource = IntArray(PADS)

    /** Alle Pads auf einen Eingang stellen. */
    fun setAllSources(src: Int) {
        for (p in 0 until PADS) padSource[p] = src
    }

    /** 0 = alle Mikro, 1 = alle Wave, 2 = gemischt (nur sichtbare Pads). */
    fun sourceSummary(n: Int): Int {
        var mic = false
        var wave = false
        for (p in 0 until min(n, PADS)) if (padSource[p] == 1) wave = true else mic = true
        return if (mic && wave) 2 else if (wave) 1 else 0
    }
    /** 0 = Halten zum Aufnehmen, sonst Live-Modus mit fester Länge in Takten (1, 2, 4). */
    /** 0 = HALTEN, -1 = TIPPEN (Start/Stopp per Tipp), >0 = LIVE mit fester Länge in Takten. */
    @Volatile var liveBars = 0

    private var outRing = FloatArray(0)

    private fun writeOutRing(out: FloatArray, w: Long, block: Int) {
        val size = outRing.size
        if (size == 0) return
        var idx = (w % size).toInt()
        for (i in 0 until block) {
            outRing[idx] = out[i]
            idx++
            if (idx == size) idx = 0
        }
    }

    private fun startNewTake(p: Int, now: Long) {
        val t = Take(false, max(0L, inClock.frameAt(now, sampleRate)), now, padOneShot[p])
        t.src = padSource[p]
        t.live = liveBars != 0
        if (t.src == 1) {
            val playing = ArrayList<Int>()
            for (q in 0 until PADS) {
                val l = loops.get(q) ?: continue
                if (q != p && !l.oneShot && !l.muted) playing.add(q)
            }
            t.bounceSrc = playing.toIntArray()
        }
        val heard = outClock.frameAt(now, sampleRate)
        val m = masterLen
        if (liveBars > 0 && m > 0L) {
            var len = m * liveBars
            val maxF = sampleRate.toLong() * MAX_SECONDS
            while (len > maxF && len > m) len -= m
            if (len > maxF) len = maxF
            // nächster Taktanfang – kurz nach der Eins zählt noch die Eins
            val prev = masterStart + Math.floorDiv(heard - masterStart, m) * m
            val start = if (heard - prev < m / 16) prev else prev + m
            t.qOut = start
            if (t.src == 1) {
                t.fromOut = start
                t.toOut = start + len
            } else {
                val qIn = inClock.frameAt(outClock.nanosAt(start, sampleRate), sampleRate)
                t.fromQ = qIn
                t.cursor = qIn
                t.toIn = qIn + len
            }
            t.armed = true
            t.released = true
        } else if (t.src == 1) {
            t.fromOut = heard
        }
        takes.set(p, t)
    }

    private fun processResampleTakes(w: Long, block: Int) {
        val end = w + block
        val maxF = sampleRate.toLong() * MAX_SECONDS
        for (p in 0 until PADS) {
            val take = takes.get(p) ?: continue
            if (take.src != 1) continue
            if (!take.released && end - take.fromOut >= maxF) {
                synchronized(take) {
                    if (!take.released) {
                        take.toOut = take.fromOut + maxF
                        take.upNanos = System.nanoTime()
                        take.released = true
                    }
                }
            }
            if (take.released && end >= take.toOut) {
                if (takes.compareAndSet(p, take, null)) {
                    val e = end
                    Thread { finishResample(p, take, e) }.start()
                }
            }
        }
    }

    private fun finishResample(p: Int, take: Take, endWritten: Long) {
        val size = outRing.size
        if (size == 0) return
        var from = take.fromOut
        if (from < endWritten - size + 1) from = endWritten - size + 1
        val len = (take.toOut - from).toInt()
        val minFrames = (sampleRate * (MIN_HOLD_NANOS / 1_000_000L) / 1000L).toInt()
        if (len < minFrames) return
        val data = FloatArray(len)
        for (i in 0 until len) data[i] = outRing[Math.floorMod(from + i, size.toLong()).toInt()]
        val fade = min(len / 4, sampleRate * FADE_MS / 1000)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            data[i] *= g
            data[len - 1 - i] *= g
        }
        val loop = Loop(data)
        loop.start = from
        if (take.oneShot) {
            loop.oneShot = true
        } else if (masterLen == 0L) {
            masterStart = loop.start
            masterLen = len.toLong()
        }
        padSemi[p] = 0
        if (takes.get(p) == null && loops.get(p) == null) {
            loops.set(p, loop)
            // Bounce: Quellen stummschalten, damit nichts doppelt klingt (in der Raster-Phase, mit Blende)
            val src = take.bounceSrc
            if (bounce && src != null && src.isNotEmpty() && !take.oneShot) {
                val muted = ArrayList<Int>()
                for (q in src) {
                    val l = loops.get(q) ?: continue
                    if (!l.muted) {
                        l.muted = true
                        muted.add(q)
                    }
                }
                if (muted.isNotEmpty()) onBounced?.invoke(p, muted)
            }
            onRecorded?.invoke(p, data.copyOf(), sampleRate)
        }
    }

    /** Bounce-Funktion (wie beim Mehrspurrekorder): nach einer Wave-Aufnahme die Quell-Pads stumm schalten. */
    @Volatile var bounce = true
    var onBounced: ((Int, List<Int>) -> Unit)? = null

    /** Pad wartet im Live-Modus auf den nächsten Takt. */
    fun isArmed(p: Int): Boolean {
        val t = takes.get(p) ?: return false
        if (!t.live || t.qOut == Take.NO_GRID) return false
        return outClock.frameAt(System.nanoTime(), sampleRate) < t.qOut
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
        if (take.live || take.released) return false
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
        padSemi[p] = 0
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
        java.util.Arrays.fill(seq[p], 0f)
        clearNotes(p)
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
        if (take.live && take.qOut != Take.NO_GRID) {
            val heard = outClock.frameAt(System.nanoTime(), sampleRate)
            return max(0L, heard - take.qOut).toFloat() / sampleRate
        }
        val end = if (take.released) take.upNanos else System.nanoTime()
        return (end - take.downNanos) / 1e9f
    }
}
