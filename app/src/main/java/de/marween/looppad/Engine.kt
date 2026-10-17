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
    /** Warp: Original-Audio, sein Tempo und seine Länge in Takten – bei Tempowechsel wird neu gedehnt. */
    @Volatile var src: FloatArray? = null
    @Volatile var srcBpm: Float = 0f
    @Volatile var warpBars: Float = 0f
    /** Lautstärke des Pads (Wischen hoch/runter). */
    @Volatile var gain: Float = 1f
    /** Quantisierter Start/Stopp: Ausgabe-Frame und Ziel (true = spielen). */
    @Volatile var pendAt: Long = Long.MIN_VALUE
    @Volatile var pendOn: Boolean = false
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
    /** Overdub per Tipp eingerastet (läuft bis zum nächsten Tipp). */
    @Volatile var latched: Boolean = false
    /** Overdub: Loop-Inhalt vor der Aufnahme (zum Abbrechen per Geste). */
    var before: FloatArray? = null

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
    /** Effekt-Ketten: 16 Pads + Mikrofon (Live-Effekte). */
    const val MIC_CHAIN = PADS
    const val CHAINS = PADS + 1
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

    // ---------------------------------------------------------------- Virtuelle Instrumente (ACID 303 / DRUM 909)
    @Volatile var acid: AcidSynth? = null
        private set
    @Volatile var drum: DrumMachine? = null
        private set
    private var instRate = 0

    /** Ein Sequenzer-Schritt für die Instrumente (sample-genau). */
    private fun instStep(step: Int, f: Long, w: Long, stepLen: Long) {
        val st = InstState
        val a = acid
        if (a != null && st.acidOn && st.acidGate[step]) {
            val prev = Math.floorMod(step - 1, STEPS)
            val next = (step + 1) % STEPS
            val slideIn = st.acidSlide[prev] && st.acidGate[prev]
            a.queue(f, true, st.acidSemi[step], st.acidAcc[step], slideIn)
            if (!(st.acidSlide[step] && st.acidGate[next])) a.queue(f + stepLen / 2, false, 0, false, false)
        }
        val d = drum
        if (d != null && st.drumOn) {
            for (v in 0 until InstState.DV) {
                val x = st.drumSteps[v][step]
                if (x > 0) d.trigger(v, (f - w).toInt(), x == 2)
            }
        }
    }

    /** Nächster Rasterschritt (für Live-Aufnahme): Schritt und ob er noch bevorsteht. */
    private fun liveStep(): Pair<Int, Boolean> {
        val bar = seqBar()
        val origin = seqStart()
        val heard = outClock.frameAt(System.nanoTime(), sampleRate)
        val n = Math.floorDiv((heard - origin) * STEPS + bar / 2, bar)
        val f = stepFrame(origin, bar, n)
        return Math.floorMod(n, STEPS.toLong()).toInt() to (f >= written)
    }

    /** Touchpad: ACID-Ton an (slide = vom vorigen Ton gleiten). */
    fun acidLiveOn(semi: Int, slide: Boolean) {
        if (recLive() && !slide) {
            val (step, ahead) = liveStep()
            InstState.acidGate[step] = true
            InstState.acidSemi[step] = semi
            if (ahead) return
        }
        acid?.queue(-1, true, semi, false, slide)
    }

    fun acidLiveOff() {
        acid?.queue(-1, false, 0, false, false)
    }

    /** Touchpad/Pads: DRUM-Instrument anschlagen. */
    fun drumHit(v: Int, accent: Boolean) {
        if (recLive()) {
            val (step, ahead) = liveStep()
            InstState.drumSteps[v][step] = if (accent) 2 else 1
            if (ahead) return
        }
        drum?.trigger(v, 0, accent)
    }

    /** Shuffle/Swing 0..1: verschiebt jede zweite 1/16 um bis zu eine halbe 1/16 nach hinten (50 % … 75 %). */
    @Volatile var swing = 0f

    private fun swingOffset(bar: Long): Long = (swing * 0.5f * bar / STEPS).toLong()
    private fun seqStart(): Long = if (masterLen > 0L) masterStart else seqOrigin
    private fun stepFrame(origin: Long, bar: Long, n: Long): Long = origin + Math.floorDiv(n * bar, STEPS.toLong())

    /** ▶ – startet Loops, Sequenzer und Instrumente gemeinsam auf der Eins. */
    fun seqPlay() = transportPlay(false)

    /** ■ – hält alles an (Loops, Sequenzer, Instrumente). */
    fun seqStop() = transportStop()

    /** Pattern-Aufnahme an/aus. Steht der Transport, wird (falls eingeschaltet) ein Takt eingezählt. */
    fun seqToggleRec() {
        if (seqRecording) {
            seqRecording = false
            return
        }
        pushPatternUndo("Pattern-Aufnahme")
        if (!seqPlaying) transportPlay(countIn)
        seqRecording = true
    }

    fun seqClear() {
        pushPatternUndo("Pattern leeren")
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
        val sw = swingOffset(bar)
        var n = -Math.floorDiv(-(w - origin) * STEPS, bar) - 1 // ceil, einen früher wegen Swing
        while (true) {
            val base = stepFrame(origin, bar, n)
            if (base >= w + block) break
            val f = if (Math.floorMod(n, 2L) == 1L) base + sw else base
            if (f >= w && f < w + block && base >= origin) {
                val step = Math.floorMod(n, STEPS.toLong()).toInt()
                instStep(step, f, w, bar / STEPS)
                for (p in 0 until PADS) {
                    val vel = seq[p][step]
                    if (vel <= 0f) continue
                    val l = loops.get(p)
                    if (l != null && l.oneShot && !l.muted && audible(p)) {
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
        val slices: Int = 0, val bars: Float = 1f, val semi: Int = 0, val gain: Float = 1f
    )

    fun snapshotPads(): Array<PadSave> = Array(PADS) { p ->
        val l = loops.get(p)
        PadSave(
            l?.data?.copyOf(), l?.oneShot ?: padOneShot[p], l?.muted ?: false,
            l?.slices?.let { it.size - 1 } ?: 0, l?.bars ?: 1f, l?.semi ?: padSemi[p], l?.gain ?: 1f
        )
    }

    fun masterFrames(): Long = masterLen

    /** Schlag-Phase (0..1) des Master-Rasters an einem Ausgabe-Frame, oder -1 ohne Tempo. */
    fun beatPhaseAt(frame: Long): Double {
        val m = masterLen
        if (m <= 0L) return -1.0
        val beat = m / 4.0
        val r = (frame - masterStart).toDouble() / beat
        return r - kotlin.math.floor(r)
    }

    /** Tempo setzen und das Raster so legen, dass an [beatFrame] ein Schlag liegt (DJ-Sync ohne Pads). */
    fun setBpmAnchored(bpm: Float, beatFrame: Long) {
        if (masterLen > 0L) {
            setBpm(bpm)
            return
        }
        val b = bpm.coerceIn(40f, 240f)
        fixedBpm = b
        masterLen = (sampleRate * 240.0 / b).toLong()
        masterStart = beatFrame
    }

    /** Ausgabe-Frame, der gerade zu hören ist. */
    fun heardFrame(): Long = outClock.frameAt(System.nanoTime(), sampleRate)

    fun seqSnapshot(): Array<FloatArray> = Array(PADS) { seq[it].copyOf() }

    fun seqHasAny(): Boolean {
        for (row in seq) for (b in row) if (b > 0f) return true
        for (p in 0 until PADS) if (noteCount(p) > 0) return true
        return false
    }

    /** Stellt einen gespeicherten Projektzustand wieder her (alle Loops starten gemeinsam auf der Eins). */
    fun restore(pads: Array<PadSave>, master: Long, bpm: Float, pattern: Array<FloatArray>?) {
        seqStop()
        clearUndo()
        clearSolo()
        resetScenes()
        arrClear()
        songMode = false
        repeatAllUp()
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
            l.gain = sv.gain
            if (sv.slices > 1 && !sv.oneShot) {
                l.slices = IntArray(sv.slices + 1) { (it.toLong() * d.size / sv.slices).toInt() }
                l.bars = sv.bars
            }
            setWarpSource(l)
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
        InstState.acidClear()
        InstState.drumClear()
        padFxFromJson(null)
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
                    val st = b * bar1 + ((k.toLong() * bar1) / STEPS).toInt() + if (k % 2 == 1) swingOffset(bar1.toLong()).toInt() else 0
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
                val u = (ev.pos * STEPS) % 2.0
                val st = ((rep * rb + ev.pos) * bar1).toInt() + if (abs(u - 1.0) < 0.25) swingOffset(bar1.toLong()).toInt() else 0
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

    private fun renderVoices(out: FloatArray, bus: FloatArray, block: Int, w: Long, chain: BooleanArray, padBufs: Array<FloatArray>) {
        synchronized(voiceLock) {
            val relStep = 1f / (0.12f * sampleRate)
            for (v in voices) {
                if (!v.active) continue
                val d = v.data
                val len = d.size
                val dst = if (v.pad in 0 until PADS && chain[v.pad]) padBufs[v.pad] else if (v.pad >= 0 && padFx[v.pad]) bus else out
                val pg = if (v.pad in 0 until PADS) (loops.get(v.pad)?.gain ?: 1f) else 1f
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
                    val sm = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * v.gain * v.vel * pg
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
    class NoteEv(var pos: Double, var len: Double, var semi: Int, var vel: Float, var group: Int = 0, val id: Int = nextNoteId()) {
        /** Ausgabe-Frame, vor dem die Note nicht (nochmal) ausgelöst wird – verhindert Doppelschlag nach Live-Aufnahme. */
        @Volatile var validFrom: Long = Long.MIN_VALUE
        var startHeard: Long = 0L
    }

    private var noteIds = 0
    @Synchronized private fun nextNoteId(): Int = ++noteIds
    private var groupIds = 0
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
        if (!recLive() || p !in 0 until PADS) return null
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
    fun notesOf(p: Int): List<NoteEv> = synchronized(notesLock) { notes[p].map { NoteEv(it.pos, it.len, it.semi, it.vel, it.group, it.id) } }

    /** Für Projekte: [Position in 1/16, Halbton, Länge in 1/16, Lautstärke] je Note. */
    fun getNotes(p: Int): List<FloatArray> = synchronized(notesLock) {
        notes[p].map { floatArrayOf((it.pos * STEPS).toFloat(), it.semi.toFloat(), (it.len * STEPS).toFloat(), it.vel, it.group.toFloat()) }
    }

    fun setNotes(p: Int, list: List<FloatArray>) {
        synchronized(notesLock) {
            notes[p].clear()
            for (a in list) if (a.size >= 4) {
                val g = if (a.size >= 5) a[4].toInt() else 0
                if (g > groupIds) groupIds = g
                notes[p].add(NoteEv(a[0] / STEPS.toDouble(), a[2] / STEPS.toDouble(), a[1].toInt(), a[3], g))
            }
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

    // ---------------------------------------------------------------- Piano-Roll bearbeiten (Auswahl, Kopieren, Gruppen)

    /** Noten mit diesen IDs (Kopien). */
    fun notesById(p: Int, ids: Set<Int>): List<NoteEv> = synchronized(notesLock) {
        notes[p].filter { it.id in ids }.map { NoteEv(it.pos, it.len, it.semi, it.vel, it.group, it.id) }
    }

    /** Verschiebt Noten um dPos Takte und dSemi Halbtöne (mit Umbruch am Ende der Matrix). */
    fun moveNotes(p: Int, ids: Set<Int>, dPos: Double, dSemi: Int) {
        synchronized(notesLock) {
            val bars = rollBars[p].toDouble()
            for (n in notes[p]) {
                if (n.id !in ids) continue
                var np = n.pos + dPos
                np = ((np % bars) + bars) % bars
                n.pos = np
                n.semi = (n.semi + dSemi).coerceIn(-48, 48)
            }
        }
    }

    /** Länge einer Note ändern (in Takten). */
    fun setNoteLen(p: Int, id: Int, len: Double) {
        synchronized(notesLock) { notes[p].firstOrNull { it.id == id }?.len = max(1.0 / 64, len) }
    }

    fun setNoteVel(p: Int, ids: Set<Int>, vel: Float) {
        synchronized(notesLock) { for (n in notes[p]) if (n.id in ids) n.vel = vel.coerceIn(0.05f, 1f) }
    }

    fun deleteNotes(p: Int, ids: Set<Int>) {
        synchronized(notesLock) { notes[p].removeAll { it.id in ids } }
    }

    /** Fügt Noten ein (Positionen in Takten) und liefert ihre neuen IDs. Gruppen bleiben als neue Gruppen erhalten. */
    fun addNotes(p: Int, list: List<NoteEv>): Set<Int> {
        val out = HashSet<Int>()
        synchronized(notesLock) {
            val bars = rollBars[p].toDouble()
            val gmap = HashMap<Int, Int>()
            for (n in list) {
                if (n.pos < 0 || n.pos >= bars) continue
                val g = if (n.group > 0) gmap.getOrPut(n.group) { ++groupIds } else 0
                notes[p].removeAll { abs(it.pos - n.pos) < 1e-6 && it.semi == n.semi }
                val ev = NoteEv(n.pos, min(n.len, bars - n.pos).coerceAtLeast(1.0 / 64), n.semi, n.vel, g)
                notes[p].add(ev)
                out.add(ev.id)
            }
        }
        return out
    }

    /** Gruppieren: alle gleiche Gruppe – oder (wenn schon eine Gruppe) auflösen. Liefert true = gruppiert. */
    fun toggleGroup(p: Int, ids: Set<Int>): Boolean {
        synchronized(notesLock) {
            val sel = notes[p].filter { it.id in ids }
            if (sel.isEmpty()) return false
            val g0 = sel[0].group
            val same = g0 > 0 && sel.all { it.group == g0 } && notes[p].none { it.group == g0 && it.id !in ids }
            return if (same) {
                for (n in sel) n.group = 0
                false
            } else {
                val g = ++groupIds
                for (n in sel) n.group = g
                true
            }
        }
    }

    /** IDs aller Noten derselben Gruppen (Auswahl erweitert sich auf ganze Gruppen). */
    fun expandGroups(p: Int, ids: Set<Int>): Set<Int> = synchronized(notesLock) {
        val gs = notes[p].filter { it.id in ids && it.group > 0 }.map { it.group }.toSet()
        if (gs.isEmpty()) ids else ids + notes[p].filter { it.group in gs }.map { it.id }
    }

    /** Nachträglich quantisieren: Positionen und Längen aufs Raster (q Teilungen pro Takt) – nur [ids], wenn angegeben. */
    fun quantizeNotes(p: Int, q: Int, ids: Set<Int>? = null) {
        if (q <= 0) return
        synchronized(notesLock) {
            val bars = rollBars[p].toDouble()
            for (n in notes[p]) {
                if (ids != null && n.id !in ids) continue
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
                if (l.muted || !audible(p)) continue
                val loopLen = bar * rollBars[p]
                for (ev in list) {
                    // Swing: Noten auf ungeraden 1/16 nach hinten schieben
                    val u = (ev.pos * STEPS) % 2.0
                    val off = (ev.pos * bar).toLong() + if (abs(u - 1.0) < 0.25) swingOffset(bar) else 0L
                    val k = -Math.floorDiv(-(w - origin - off), loopLen)
                    val f = origin + k * loopLen + off
                    if (f >= end || f < w || f < origin) continue
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
        pushPadUndo("Wave-Editor Pad ${p + 1}", p)
        val s0 = start.coerceIn(0, max(0, l.data.size - 2))
        val e0 = end.coerceIn(s0 + 2, l.data.size)
        val data = l.data.copyOfRange(s0, e0)
        if (l.oneShot || slices <= 1) {
            padOneShot[p] = l.oneShot
            val semi = l.semi
            importLoopRaw(p, data)
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
        pushPadUndo("Slices auf Pads", *IntArray(maxPad.coerceAtMost(PADS)) { it })
        var q = 0
        var count = 0
        for (k in 0 until n) {
            while (q < maxPad && (q == p || loops.get(q) != null)) q++
            if (q >= maxPad) break
            val a = s0 + (k.toLong() * len / n).toInt()
            val b = s0 + ((k + 1).toLong() * len / n).toInt()
            if (b - a < 2) continue
            padOneShot[q] = true
            importLoopRaw(q, l.data.copyOfRange(a, b))
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
        val old = masterLen
        val nl = (sampleRate * 240.0 / b).toLong()
        if (old <= 0L) {
            masterStart = written
        } else if (nl != old) {
            // Position im Takt beibehalten, damit der Groove beim Tempowechsel nicht springt
            val heard = heardNow()
            val frac = Math.floorMod(heard - masterStart, old).toDouble() / old
            masterStart = heard - (frac * nl).toLong()
        }
        masterLen = nl
        if (nl != old && old > 0L) scheduleRewarp()
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

    // ---------------------------------------------------------------- Audio-Geräte (USB & Co.)

    /** Gewähltes Ausgabe-/Eingabegerät als „Typ|Name“, null = automatisch. */
    @Volatile var outKey: String? = null
    @Volatile var inKey: String? = null

    fun deviceKey(d: android.media.AudioDeviceInfo): String = "${d.type}|${d.productName}"

    fun findDevice(am: AudioManager, key: String?, flag: Int): android.media.AudioDeviceInfo? {
        if (key == null) return null
        return am.getDevices(flag).firstOrNull { deviceKey(it) == key }
    }

    fun isUsb(d: android.media.AudioDeviceInfo): Boolean =
        d.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
            d.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            d.type == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY

    /** Aktuell tatsächlich genutzte Geräte (für die Anzeige). */
    fun routedOutput(): android.media.AudioDeviceInfo? = try { track?.routedDevice } catch (e: Exception) { null }
    fun routedInput(): android.media.AudioDeviceInfo? = try { record?.routedDevice } catch (e: Exception) { null }

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

    // ---------------------------------------------------------------- Latenz
    /** 0 = Auto (so klein wie möglich, wächst nur bei Aussetzern), 1 = Minimal, 2 = Ausgewogen, 3 = Stabil. */
    @Volatile var latencyMode = 0
    val LATENCY_NAMES = arrayOf("AUTO – so klein wie möglich", "MINIMAL – 2 Puffer", "AUSGEWOGEN – 4 Puffer", "STABIL – 8 Puffer")
    /** Hardware-Puffergröße (Frames), aktuelle Ausgabe-Puffergröße und Aussetzer. */
    @Volatile var burstFrames = 256
        private set
    @Volatile var outBufferFrames = 0
        private set
    @Volatile var underruns = 0
        private set
    @Volatile var fastPath = false
        private set

    /** Geschätzte Ausgabe-Latenz in Millisekunden (Puffer der App). */
    fun outputLatencyMs(): Float = if (sampleRate > 0) outBufferFrames * 1000f / sampleRate else 0f

    // ---------------------------------------------------------------- Live-Mikrofon (Mithören mit Effekten)
    /** Mikrofon live hören. */
    @Volatile var monitorOn = false
    /** Lautstärke beim Mithören. */
    @Volatile var monitorGain = 0.9f
    /** true = zusätzlich durch die XY-Master-Effekte (Effekt-Bus). */
    @Volatile var monitorMaster = false
    @Volatile var monitorLevel = 0f
    private var monCursor = -1L

    /** Liefert die neuesten Mikrofon-Samples mit so wenig Verzögerung wie möglich. */
    private fun fillMonitor(dst: FloatArray, n: Int): Boolean {
        val c = captured
        val lag = max(n.toLong(), burstFrames.toLong())
        if (monCursor < 0 || monCursor + n > c || c - monCursor > lag * 6 + n) monCursor = c - n - lag
        if (monCursor < 0) {
            java.util.Arrays.fill(dst, 0, n, 0f)
            monCursor += n
            return false
        }
        var idx = (monCursor % ringSize).toInt()
        for (i in 0 until n) {
            dst[i] = ring[idx]
            idx++
            if (idx == ringSize) idx = 0
        }
        monCursor += n
        return true
    }
    private var recordThread: Thread? = null
    private var mixThread: Thread? = null

    // ---------------------------------------------------------------- Lifecycle

    @Synchronized
    fun start(context: Context) {
        if (running) return
        error = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = (am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256).coerceIn(32, 2048)
        burstFrames = burst
        underruns = 0
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val newSize = sampleRate * (MAX_SECONDS + 2)
        if (ring.size != newSize) ring = FloatArray(newSize)
        ringSize = newSize
        if (outRing.size != newSize) outRing = FloatArray(newSize)

        val t: AudioTrack = try {
            buildTrack(burst)
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
        // Bevorzugte Geräte (z. B. USB-Audio-Interface) setzen
        try {
            findDevice(am, outKey, AudioManager.GET_DEVICES_OUTPUTS)?.let { t.setPreferredDevice(it) }
            findDevice(am, inKey, AudioManager.GET_DEVICES_INPUTS)?.let { r.setPreferredDevice(it) }
        } catch (ignored: Exception) {}
        if (acid == null || instRate != sampleRate) {
            acid = AcidSynth(sampleRate)
            drum = DrumMachine(sampleRate)
            instRate = sampleRate
        }
        if (fxs == null || fxRate != sampleRate) {
            fxs = Array(SLOTS) { Fx(sampleRate) }
            fxRate = sampleRate
            for (p in 0 until CHAINS) for (sl in 0 until 3) if (pfxInst[p][sl] != null) pfxInst[p][sl] = Fx(sampleRate)
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
        seqOrigin -= shift
        countFrom = Long.MIN_VALUE
        countTo = Long.MIN_VALUE
        repeatAllUp()
        lastWritten = 0L

        val block = max(32, min(burst, t.bufferSizeInFrames / 2))
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
        recordThread = Thread({ recordLoop(r, burst) }, "LoopPad-Record").also { it.start() }
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

    private fun bufferBursts(): Int = when (latencyMode) { 1 -> 2; 2 -> 4; 3 -> 8; else -> 2 }

    private fun buildTrack(burst: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 8 * burst * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IllegalStateException("Audioausgabe konnte nicht gestartet werden")
        }
        // Puffer so klein wie möglich: wenige Hardware-Bursts (niedrige Latenz beim Antippen)
        try { t.setBufferSizeInFrames(burst * bufferBursts()) } catch (ignored: Exception) {}
        outBufferFrames = t.bufferSizeInFrames
        fastPath = try { t.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY } catch (e: Throwable) { false }
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
            .setBufferSizeInBytes(max(minBuf, 4096))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Mikrofon konnte nicht geöffnet werden")
        }
        return rec
    }

    // ---------------------------------------------------------------- Aufnahme

    private fun recordLoop(rec: AudioRecord, burst: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // kleine Lesehappen = Mikrofon sofort im Ringpuffer (Live-Effekte, Overdub)
        val chunk = ShortArray(burst.coerceIn(64, 512))
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
                // Transport steht? Aufnahme startet ihn – die Eins liegt auf dem Tastendruck
                if (!seqPlaying && masterLen > 0L && !take.oneShot) startTransportAt(outClock.frameAt(take.downNanos, sampleRate))
                if (take.overdub) {
                    // Stand vor dem Overdub merken (für UNDO), bevor etwas hineingeschrieben wird
                    val l0 = loops.get(p)
                    if (l0 != null) {
                        val copy = l0.data.copyOf()
                        take.before = copy
                        pushEntry(UndoEntry("Overdub Pad ${p + 1}", listOf(snapPad(p, copy)), null))
                    }
                }
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
                if (take.released && take.cursor >= take.toIn && takes.compareAndSet(p, take, null)) {
                    // Overdub fertig: der Loop ist jetzt das Original für künftiges Warpen
                    loop.src = loop.data
                    loop.srcBpm = currentBpm()
                }
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
        pushPadUndo("Aufnahme Pad ${p + 1}", p)
        if (take.oneShot) {
            loop.oneShot = true
        } else if (masterLen == 0L) {
            masterStart = loop.start
            masterLen = len.toLong()
            if (!seqPlaying) startTransportAt(loop.start)
        }
        padSemi[p] = 0
        setWarpSource(loop)
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
        val instScratch = FloatArray(block)
        val padBufs = Array(PADS) { FloatArray(block) }
        val chainNow = BooleanArray(PADS)
        val monBuf = FloatArray(block)
        val djL = FloatArray(block)
        val djR = FloatArray(block)
        val cue = FloatArray(block)
        val mono = FloatArray(block)
        val stereo = FloatArray(block * 2)
        var lastUnder = try { t.underrunCount } catch (e: Throwable) { 0 }
        var blocks = 0
        micCursor = -1L
        monCursor = -1L
        val ts = AudioTimestamp()
        while (running) {
            java.util.Arrays.fill(out, 0f)
            java.util.Arrays.fill(bus, 0f)
            val w = written
            sceneTick(w, block)
            runSequencer(w, block)
            runRepeat(w, block)
            val mlen = masterLen
            val transport = seqPlaying
            for (p in 0 until PADS) {
                chainNow[p] = padChainActive(p)
                if (chainNow[p]) java.util.Arrays.fill(padBufs[p], 0f)
            }
            for (p in 0 until PADS) {
                val loop = loops.get(p)
                if (loop == null) {
                    levels[p] *= 0.9f
                    continue
                }
                val dst = if (chainNow[p]) padBufs[p] else if (padFx[p]) bus else out
                val pa = loop.pendAt
                if (pa != Long.MIN_VALUE && pa < w + block) {
                    // quantisierter Start/Stopp genau auf dem Rasterpunkt
                    if (loop.pendOn) {
                        loop.start = pa
                        loop.fade = 0f
                        loop.muted = false
                    } else {
                        loop.muted = true
                    }
                    loop.pendAt = Long.MIN_VALUE
                }
                val gn = loop.gain
                val d = loop.data
                val len = d.size
                if (loop.oneShot) {
                    var ss = loop.shotStart
                    if (ss == SHOT_NOW) {
                        ss = w
                        loop.shotStart = w
                    }
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
                                val v = (d[i0] + (d[i0 + 1] - d[i0]) * fr) * sg * gn
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
                val mute = loop.muted || !transport || !audible(p)
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
                        dst[i] += v * fg * gn
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
                        dst[i] += v * fg * gn
                        idx++
                        if (idx == len) idx = 0
                        i++
                    }
                }
                loop.fade = fg
                levels[p] = max(if (mute) peak * 0.25f else peak, levels[p] * 0.88f)
            }
            renderVoices(out, bus, block, w, chainNow, padBufs)
            // Effekt-Kette je Pad (bis zu 3 Effekte), danach in den Mix
            val micP = fillMic(micBuf, block)
            // Live-Mikrofon: sofort hören, mit eigener Effekt-Kette (bis zu 3) und/oder über die XY-Effekte
            if (monitorOn) {
                if (fillMonitor(monBuf, block)) {
                    val g = monitorGain
                    for (i in 0 until block) monBuf[i] *= g
                    if (padChainActive(MIC_CHAIN)) {
                        for (sl in 0 until 3) {
                            val ty = pfxType[MIC_CHAIN][sl]
                            val fx = pfxInst[MIC_CHAIN][sl] ?: continue
                            if (ty < 0) continue
                            val pr = pfxPar[MIC_CHAIN][sl]
                            fx.process(monBuf, block, w, ty, pr[0], pr[1], true, pr[2], pr[3], pr[4], masterLen, masterStart, micP)
                        }
                    }
                    var mp = 0f
                    val dst = if (monitorMaster) bus else out
                    for (i in 0 until block) {
                        val v = monBuf[i]
                        dst[i] += v
                        val a = abs(v)
                        if (a > mp) mp = a
                    }
                    monitorLevel = max(mp, monitorLevel * 0.9f)
                }
            } else {
                monCursor = -1L
                monitorLevel *= 0.9f
            }
            for (p in 0 until PADS) {
                if (!chainNow[p]) continue
                val pb = padBufs[p]
                for (sl in 0 until 3) {
                    val t = pfxType[p][sl]
                    val fx = pfxInst[p][sl] ?: continue
                    if (t < 0) continue
                    val pr = pfxPar[p][sl]
                    fx.process(pb, block, w, t, pr[0], pr[1], true, pr[2], pr[3], pr[4], masterLen, masterStart, micP)
                }
                val dst = if (padFx[p]) bus else out
                for (i in 0 until block) dst[i] += pb[i]
            }
            // SOLO auf einem Pad: Instrumente laufen weiter, sind aber nicht zu hören
            val soloed = soloAny
            if (soloed) java.util.Arrays.fill(instScratch, 0f)
            acid?.render(if (soloed) instScratch else if (InstState.acidFx) bus else out, block, w)
            drum?.render(if (soloed) instScratch else if (InstState.drumFx) bus else out, block)
            if (songRecording && songDry) {
                for (i in 0 until block) dryBuf[i] = out[i] + bus[i]
            }
            motionTick(w, block)
            val chain = fxs
            if (chain != null) {
                val mic = micP
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
            // DJ-Decks (Stereo) – laufen am Master vorbei, haben eigene EQs/Filter/Effekte
            val djOn = Dj.render(djL, djR, cue, block, w)
            if (djOn) {
                for (i in 0 until block) mono[i] = out[i] + (djL[i] + djR[i]) * 0.5f
                writeOutRing(mono, w, block)
            } else {
                writeOutRing(out, w, block)
            }
            processResampleTakes(w, block)
            val vol = knobs[3] * 1.25f
            var mpeak = 0f
            if (djOn) {
                // Stereo: Pads (mono) + Decks; mit Split-Cue links Master (mono), rechts Vorhören
                val split = Dj.splitCue
                val cg = Dj.cueGain
                for (i in 0 until block) {
                    val m = out[i]
                    var l = softClip((m + djL[i]) * vol)
                    var r = softClip((m + djR[i]) * vol)
                    if (split) {
                        val mm = (l + r) * 0.5f
                        l = mm
                        r = softClip(cue[i] * cg)
                    }
                    stereo[2 * i] = l
                    stereo[2 * i + 1] = r
                    val y = (l + r) * 0.5f
                    out[i] = y
                    val a = max(abs(l), abs(r))
                    if (a > mpeak) mpeak = a
                }
            } else {
                for (i in 0 until block) {
                    val y = softClip(out[i] * vol)
                    out[i] = y
                    val a = abs(y)
                    if (a > mpeak) mpeak = a
                }
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

            val splitNow = djOn && Dj.splitCue
            if (splitNow) java.util.Arrays.fill(mono, 0f)
            val clickTo = if (splitNow) mono else out
            if (metronome && transport && (countTo == Long.MIN_VALUE || w >= countTo)) addClicks(clickTo, w, block)
            if (countTo != Long.MIN_VALUE) countClicks(clickTo, w, block)
            if (djOn) {
                if (Dj.splitCue) {
                    // Klick nur auf dem Vorhör-Kanal
                    for (i in 0 until block) stereo[2 * i + 1] = softClip(stereo[2 * i + 1] + mono[i])
                } else {
                    for (i in 0 until block) {
                        val c = out[i] - (stereo[2 * i] + stereo[2 * i + 1]) * 0.5f
                        if (c != 0f) {
                            stereo[2 * i] += c
                            stereo[2 * i + 1] += c
                        }
                    }
                }
            } else {
                for (i in 0 until block) {
                    val y = out[i]
                    stereo[2 * i] = y
                    stereo[2 * i + 1] = y
                }
            }
            var off = 0
            val total = block * 2
            while (off < total && running) {
                val r = try { t.write(stereo, off, total - off, AudioTrack.WRITE_BLOCKING) } catch (ignored: Exception) { -1 }
                if (r <= 0) {
                    Thread.sleep(5)
                    break
                }
                off += r
            }
            // Auto-Latenz: nur bei Aussetzern den Puffer um einen Burst vergrößern
            if (++blocks % 32 == 0) {
                val u = try { t.underrunCount } catch (e: Throwable) { 0 }
                if (u > lastUnder) {
                    underruns += u - lastUnder
                    lastUnder = u
                    if (latencyMode == 0) {
                        val cur = t.bufferSizeInFrames
                        val cap = burstFrames * 8
                        if (cur < cap) {
                            try { t.setBufferSizeInFrames(min(cap, cur + burstFrames)) } catch (ignored: Exception) {}
                        }
                    }
                }
                outBufferFrames = t.bufferSizeInFrames
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
    /** Marke: ONE SHOT startet im nächsten berechneten Block (kein abgeschnittener Anschlag). */
    const val SHOT_NOW = Long.MAX_VALUE - 7

    fun padDown(p: Int, velocity: Float = 1f): Boolean {
        if (!running) return false
        val existing = takes.get(p)
        if (existing != null) {
            if (existing.overdub && existing.latched) {
                // eingerasteter Overdub: zweiter Tipp beendet ihn
                if (!existing.released) releaseTake(existing, System.nanoTime())
                return false
            }
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
            if (recLive()) {
                val bar = seqBar()
                val origin = seqStart()
                val heard = outClock.frameAt(System.nanoTime(), sampleRate)
                val n = Math.floorDiv((heard - origin) * STEPS + bar / 2, bar)
                val f = stepFrame(origin, bar, n)
                seq[p][Math.floorMod(n, STEPS.toLong()).toInt()] = 1f
                // Liegt der Rasterpunkt noch vor uns, spielt ihn gleich der Sequenzer – kein Doppelschlag
                if (f >= written) return true
            }
            loop.shotGain = velocity
            loop.shotStart = SHOT_NOW
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
        // Transport steht: Wave- und Live-Aufnahmen starten ihn sofort (Eins = jetzt)
        if (!seqPlaying && m > 0L && !t.oneShot && (t.src == 1 || liveBars > 0)) startTransportAt(heard)
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
            val src = take.bounceSrc
            pushPadUndo("Wave-Aufnahme Pad ${p + 1}", p, *(if (bounce && !take.oneShot) (src ?: IntArray(0)) else IntArray(0)))
            if (!take.oneShot && !seqPlaying && masterLen > 0L) seqPlaying = true
            setWarpSource(loop)
            loops.set(p, loop)
            // Bounce: Quellen stummschalten, damit nichts doppelt klingt (in der Raster-Phase, mit Blende)
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
        if (loops.get(p) != null) pushPadUndo("Modus Pad ${p + 1}", p)
        padOneShot[p] = oneShot
        val l = loops.get(p) ?: return
        if (oneShot) {
            // neues Objekt statt Umbau – so bleibt der alte Zustand für UNDO unverändert
            val c = cloneLoop(l, l.data.copyOf())
            c.oneShot = true
            c.muted = false
            c.shotStart = Long.MIN_VALUE
            loops.set(p, c)
            if (!anyLooping()) releaseMaster()
        } else if (l.oneShot) {
            importLoopRaw(p, l.data.copyOf())
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
        if (ss == SHOT_NOW) return 0f
        val rel = outClock.frameAt(System.nanoTime(), sampleRate) - ss
        if (rel < 0 || rel >= l.data.size) return -1f
        return rel.toFloat() / l.data.size
    }

    /** Loops starten schon beim Berühren (nicht erst beim Loslassen). */
    @Volatile var instantStart = true
    /** Auch Stoppen beim Berühren (dann kein Overdub per Halten auf laufenden Loops). */
    @Volatile var instantStop = false

    /** Ergebnis von [liveDown]: Bit 1 = ONE SHOT angeschlagen, Bit 2 = Loop beim Berühren gestartet/gestoppt. */
    const val LIVE_SHOT = 1
    const val LIVE_LAUNCHED = 2

    /**
     * Pad gedrückt (Touch oder MIDI) – reagiert sofort: ONE SHOTS klingen im nächsten Block,
     * gestoppte Loops starten beim Berühren (im Start-Raster bzw. phasenrichtig im Takt).
     */
    fun liveDown(p: Int, velocity: Float = 1f): Int {
        val l = loops.get(p)
        val wasMuted = l != null && !l.oneShot && (l.muted || !seqPlaying) && l.pendAt == Long.MIN_VALUE
        val wasPlaying = l != null && !l.oneShot && !l.muted && seqPlaying && l.pendAt == Long.MIN_VALUE
        val existing = takes.get(p)
        if (padDown(p, velocity)) return LIVE_SHOT
        if (existing != null || takes.get(p)?.let { it.live } == true) return 0
        if (l != null && instantStart && wasMuted) {
            launchToggle(p)
            return LIVE_LAUNCHED
        }
        if (l != null && instantStop && wasPlaying) {
            takes.set(p, null)
            launchToggle(p)
            return LIVE_LAUNCHED
        }
        return 0
    }

    /** Pad losgelassen (Touch oder MIDI). Liefert true, wenn es ein kurzer Tipp war. */
    fun liveUp(p: Int, launchedOnDown: Boolean): Boolean {
        val hadLoop = hasLoop(p)
        if (launchedOnDown) {
            // schon beim Berühren gestartet: ein kurzer Tipp rastet keinen Overdub ein
            val t = takes.get(p) ?: return true
            if (!t.live && !t.released && !t.latched && System.nanoTime() - t.downNanos < MIN_HOLD_NANOS) {
                takes.compareAndSet(p, t, null)
                return true
            }
        }
        val tap = padUp(p)
        if (tap && hadLoop && !launchedOnDown) launchToggle(p)
        return tap
    }

    /** Gibt true zurück, wenn es nur ein kurzer Tipp war (keine Aufnahme). */
    fun padUp(p: Int): Boolean {
        val take = takes.get(p) ?: return false
        if (take.live || take.released || take.latched) return false
        val now = System.nanoTime()
        if (now - take.downNanos < MIN_HOLD_NANOS) {
            val l = loops.get(p)
            if (take.overdub && tapOverdub && l != null && !l.oneShot && !l.muted && seqPlaying && l.pendAt == Long.MIN_VALUE) {
                // Tipp auf laufenden Loop = Overdub einrasten (gelb), nächster Tipp beendet
                take.latched = true
                return false
            }
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
        pushPadUndo("Laden Pad ${p + 1}", p)
        importLoopRaw(p, src)
        // Ein neuer Loop bei stehendem Transport startet die Wiedergabe (läuft im Raster weiter)
        if (!padOneShot[p] && !seqPlaying && masterLen > 0L) startTransportAt(heardNow())
    }

    private fun importLoopRaw(p: Int, src: FloatArray) {
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
        setWarpSource(loop)
        loops.set(p, loop)
    }

    /** Stummschalten: Loops verstummen (laufen im Takt weiter), ⚡-Pads werden vom Sequenzer nicht mehr angeschlagen. */
    fun toggleMute(p: Int) {
        loops.get(p)?.let { it.muted = !it.muted }
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
        if (loops.get(p) != null) pushUndo("Pad ${p + 1} löschen", intArrayOf(p), padHasEvents(p))
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
        if (!seqPlaying) return 0f
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

    // ================================================================ Maschine-Workflow
    // Transport mit Einzähler, Undo/Redo, Mute/Solo, Note Repeat, Szenen, Duplizieren

    // ---------------------------------------------------------------- Transport & Einzähler

    /** Einzähler: ein Takt Klicks vor der Pattern-Aufnahme, wenn der Transport steht. */
    @Volatile var countIn = true
    @Volatile private var countFrom = Long.MIN_VALUE
    @Volatile private var countTo = Long.MIN_VALUE
    private var countClickStart = Long.MIN_VALUE
    private var countClickHigh = false

    private fun heardNow(): Long = outClock.frameAt(System.nanoTime(), sampleRate)

    /** Legt die Eins aller Loops und des Sequenzers auf [start] und lässt den Transport laufen. */
    private fun startTransportAt(start: Long) {
        seqOrigin = start
        if (masterLen > 0L) masterStart = start
        for (p in 0 until PADS) {
            val l = loops.get(p) ?: continue
            if (!l.oneShot) l.start = start
        }
        sceneBarCount = 0
        arrBar = 0
        arrApplyNow = true
        seqPlaying = true
    }

    /** ▶: alles startet gemeinsam auf der Eins – auf Wunsch nach einem Takt Einzählen. */
    fun transportPlay(withCountIn: Boolean) {
        if (seqPlaying) return
        val bar = seqBar()
        val start = written + sampleRate / 50 + if (withCountIn) bar else 0L
        if (withCountIn) {
            countFrom = start - bar
            countTo = start
        } else {
            countFrom = Long.MIN_VALUE
            countTo = Long.MIN_VALUE
        }
        countClickStart = Long.MIN_VALUE
        startTransportAt(start)
    }

    fun transportStop() {
        val now = System.nanoTime()
        for (p in 0 until PADS) {
            val t = takes.get(p) ?: continue
            if (t.overdub && !t.released) releaseTake(t, now)
            loops.get(p)?.pendAt = Long.MIN_VALUE
        }
        seqPlaying = false
        seqRecording = false
        countFrom = Long.MIN_VALUE
        countTo = Long.MIN_VALUE
        acid?.queue(-1, false, 0, false, false)
    }

    /** Läuft gerade der Einzähler? Schlag 1..4, sonst 0. */
    fun countInBeat(): Int {
        val to = countTo
        val from = countFrom
        if (to == Long.MIN_VALUE || from == Long.MIN_VALUE || !seqPlaying) return 0
        val h = heardNow()
        if (h >= to) return 0
        val bar = to - from
        if (h < from || bar <= 0L) return 1
        return (((h - from) * 4 / bar).toInt() + 1).coerceIn(1, 4)
    }

    /** Pattern-Aufnahme nimmt wirklich auf (Transport läuft, Einzähler vorbei). */
    private fun recLive(): Boolean {
        if (!seqRecording || !seqPlaying) return false
        val to = countTo
        return to == Long.MIN_VALUE || heardNow() >= to - seqBar() / 32
    }

    private fun countClicks(out: FloatArray, w: Long, block: Int) {
        val from = countFrom
        val to = countTo
        if (to == Long.MIN_VALUE || from == Long.MIN_VALUE) return
        val bar = to - from
        if (bar > 0L && w < to) {
            var n = max(0L, -Math.floorDiv(-(w - from) * 4, bar))
            while (n < 4) {
                val f = from + n * bar / 4
                if (f >= w + block) break
                if (f >= w) {
                    countClickStart = f
                    countClickHigh = n == 0L
                }
                n++
            }
        }
        val cs = countClickStart
        if (cs == Long.MIN_VALUE) return
        val dur = sampleRate / 25
        val freq = if (countClickHigh) 1760.0 else 1100.0
        for (i in 0 until block) {
            val t = w + i - cs
            if (t < 0 || t >= dur) continue
            val env = kotlin.math.exp(-t / (0.012 * sampleRate))
            out[i] += (kotlin.math.sin(2.0 * Math.PI * freq * t / sampleRate) * env * 0.4).toFloat()
        }
    }

    // ---------------------------------------------------------------- Mute / Solo

    val solo = BooleanArray(PADS)
    @Volatile var soloAny = false
        private set

    fun toggleSolo(p: Int) {
        if (p !in 0 until PADS) return
        solo[p] = !solo[p]
        soloAny = solo.any { it }
    }

    fun clearSolo() {
        java.util.Arrays.fill(solo, false)
        soloAny = false
    }

    private fun audible(p: Int): Boolean = !soloAny || solo[p]

    fun isMuted(p: Int): Boolean = loops.get(p)?.muted ?: false

    // ---------------------------------------------------------------- Note Repeat

    @Volatile var repeatOn = false
    /** Teilungen pro Takt: 1/4, 1/8, 1/16, 1/32, 1/8T, 1/16T. */
    val REPEAT_DIVS = intArrayOf(4, 8, 16, 32, 12, 24)
    val REPEAT_NAMES = arrayOf("1/4", "1/8", "1/16", "1/32", "1/8T", "1/16T")
    @Volatile var repeatIdx = 2
    private val repPad = BooleanArray(PADS)
    private val repPadFrom = LongArray(PADS)
    private val repDrum = IntArray(InstState.DV)
    private val repDrumFrom = LongArray(InstState.DV)
    @Volatile private var repOrigin = 0L
    @Volatile private var repAny = false

    fun repeatName(): String = REPEAT_NAMES[repeatIdx]

    fun cycleRepeat() {
        repeatIdx = (repeatIdx + 1) % REPEAT_DIVS.size
    }

    private fun repInterval(): Long = max(1L, seqBar() / REPEAT_DIVS[repeatIdx])

    private fun repUpdateAny() {
        repAny = repPad.any { it } || repDrum.any { it > 0 }
    }

    /** Pad wird gehalten: wiederholt im Raster (der erste Schlag kam schon beim Antippen). */
    fun repeatPadDown(p: Int) {
        if (p !in 0 until PADS) return
        if (!repAny && !seqPlaying) repOrigin = written
        repPadFrom[p] = written + repInterval() / 2
        repPad[p] = true
        repAny = true
    }

    fun repeatPadUp(p: Int) {
        if (p !in 0 until PADS) return
        repPad[p] = false
        repUpdateAny()
    }

    fun repeatDrumDown(v: Int, accent: Boolean) {
        if (v !in 0 until InstState.DV) return
        if (!repAny && !seqPlaying) repOrigin = written
        repDrumFrom[v] = written + repInterval() / 2
        repDrum[v] = if (accent) 2 else 1
        repAny = true
    }

    fun repeatDrumUp(v: Int) {
        if (v !in 0 until InstState.DV) return
        repDrum[v] = 0
        repUpdateAny()
    }

    fun repeatAllUp() {
        java.util.Arrays.fill(repPad, false)
        java.util.Arrays.fill(repDrum, 0)
        repAny = false
    }

    private fun runRepeat(w: Long, block: Int) {
        if (!repAny) return
        val bar = seqBar()
        if (bar <= 0L) return
        val div = REPEAT_DIVS[repeatIdx]
        val origin = if (seqPlaying) seqStart() else repOrigin
        val sw = if (div == 16) swingOffset(bar) else 0L
        val rec = recLive() && STEPS % div == 0
        var n = -Math.floorDiv(-(w - origin) * div, bar) - 1
        while (true) {
            val base = origin + Math.floorDiv(n * bar, div.toLong())
            if (base >= w + block) break
            val f = if (sw > 0L && Math.floorMod(n, 2L) == 1L) base + sw else base
            if (f >= w && f < w + block) {
                val step = if (rec) Math.floorMod(n * (STEPS / div), STEPS.toLong()).toInt() else -1
                for (p in 0 until PADS) {
                    if (!repPad[p] || f < repPadFrom[p]) continue
                    val l = loops.get(p) ?: continue
                    if (!l.oneShot) continue
                    l.shotGain = 1f
                    l.shotStart = f
                    if (step >= 0) seq[p][step] = 1f
                }
                val d = drum
                for (v in 0 until InstState.DV) {
                    val a = repDrum[v]
                    if (a == 0 || f < repDrumFrom[v]) continue
                    d?.trigger(v, (f - w).toInt(), a == 2)
                    if (step >= 0) InstState.drumSteps[v][step] = a
                }
            }
            n++
        }
    }

    // ---------------------------------------------------------------- Undo / Redo

    private class PadSnap(val p: Int, val loop: Loop?, val muted: Boolean, val oneShot: Boolean, val semi: Int)

    private class UndoEntry(val label: String, val pads: List<PadSnap>, val pat: PatternSnap?) {
        val bytes: Long = pads.sumOf { (it.loop?.data?.size ?: 0).toLong() * 4L }
    }

    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<UndoEntry>()
    private val undoLock = Any()
    private const val UNDO_MAX = 40
    private const val UNDO_BYTES = 140L * 1024L * 1024L

    @Volatile var undoCount = 0
        private set
    @Volatile var redoCount = 0
        private set

    private fun cloneLoop(l: Loop, data: FloatArray): Loop {
        val c = Loop(data)
        c.start = l.start
        c.muted = l.muted
        c.oneShot = l.oneShot
        c.semi = l.semi
        c.rate = l.rate
        c.slices = l.slices?.copyOf()
        c.bars = l.bars
        c.src = l.src
        c.srcBpm = l.srcBpm
        c.warpBars = l.warpBars
        c.gain = l.gain
        return c
    }

    private fun snapPad(p: Int, copy: FloatArray? = null): PadSnap {
        val l = loops.get(p)
        val keep = if (copy != null && l != null) cloneLoop(l, copy) else l
        return PadSnap(p, keep, l?.muted ?: false, padOneShot[p], padSemi[p])
    }

    private fun snapPattern(): PatternSnap =
        PatternSnap(Array(PADS) { seq[it].copyOf() }, Array(PADS) { getNotes(it) }, rollBars.copyOf(), InstSnap.capture())

    private fun applyPattern(s: PatternSnap) {
        for (p in 0 until PADS) {
            System.arraycopy(s.seq[p], 0, seq[p], 0, min(STEPS, s.seq[p].size))
            rollBars[p] = s.rollBars[p]
            setNotes(p, s.notes[p])
        }
        s.inst.restore()
    }

    private fun pushEntry(e: UndoEntry) {
        synchronized(undoLock) {
            undoStack.add(e)
            redoStack.clear()
            var total = 0L
            for (u in undoStack) total += u.bytes
            while (undoStack.size > UNDO_MAX || (total > UNDO_BYTES && undoStack.size > 1)) total -= undoStack.removeAt(0).bytes
            undoCount = undoStack.size
            redoCount = 0
        }
    }

    fun pushPadUndo(label: String, vararg pads: Int) {
        pushEntry(UndoEntry(label, pads.filter { it in 0 until PADS }.distinct().map { snapPad(it) }, null))
    }

    fun pushPatternUndo(label: String) {
        pushEntry(UndoEntry(label, emptyList(), snapPattern()))
    }

    private fun pushUndo(label: String, pads: IntArray, pattern: Boolean) {
        pushEntry(UndoEntry(label, pads.filter { it in 0 until PADS }.map { snapPad(it) }, if (pattern) snapPattern() else null))
    }

    /** Stellt einen Eintrag her und liefert den bisherigen Zustand derselben Teile (für die Gegenrichtung). */
    private fun applyEntry(e: UndoEntry): UndoEntry {
        val back = UndoEntry(e.label, e.pads.map { snapPad(it.p) }, if (e.pat != null) snapPattern() else null)
        for (s in e.pads) {
            takes.set(s.p, null)
            val l = s.loop
            val live = loops.get(s.p)
            padOneShot[s.p] = s.oneShot
            padSemi[s.p] = s.semi
            if (l != null) {
                l.muted = s.muted
                l.shotStart = Long.MIN_VALUE
                if (l.oneShot) {
                    l.semi = s.semi
                    l.rate = 2.0.pow(s.semi / 12.0)
                } else {
                    if (masterLen <= 0L) {
                        masterLen = l.data.size.toLong()
                        masterStart = written
                    }
                    // zurückgeholte Loops ins Raster legen: gleiche Länge (Overdub) = gleiche Phase, sonst auf die Eins
                    if (l !== live) {
                        l.start = if (live != null && !live.oneShot && live.data.size == l.data.size) live.start else masterStart
                    }
                }
            }
            loops.set(s.p, l)
        }
        if (e.pads.isNotEmpty() && !anyLooping()) releaseMaster()
        e.pat?.let { applyPattern(it) }
        return back
    }

    /** Macht den letzten Schritt rückgängig. Liefert seine Beschreibung oder null. */
    fun undo(): String? {
        val e = synchronized(undoLock) { if (undoStack.isEmpty()) null else undoStack.removeAt(undoStack.size - 1) } ?: return null
        val back = applyEntry(e)
        synchronized(undoLock) {
            redoStack.add(back)
            undoCount = undoStack.size
            redoCount = redoStack.size
        }
        return e.label
    }

    fun redo(): String? {
        val e = synchronized(undoLock) { if (redoStack.isEmpty()) null else redoStack.removeAt(redoStack.size - 1) } ?: return null
        val back = applyEntry(e)
        synchronized(undoLock) {
            undoStack.add(back)
            undoCount = undoStack.size
            redoCount = redoStack.size
        }
        return e.label
    }

    fun clearUndo() {
        synchronized(undoLock) {
            undoStack.clear()
            redoStack.clear()
            undoCount = 0
            redoCount = 0
        }
    }

    /** Pattern-Schritte gehören zur Szene – beim Szenenwechsel verfallen sie im Undo. */
    private fun dropPatternUndo() {
        synchronized(undoLock) {
            undoStack.removeAll { it.pat != null }
            redoStack.removeAll { it.pat != null }
            undoCount = undoStack.size
            redoCount = redoStack.size
        }
    }

    // ---------------------------------------------------------------- Pattern-Werkzeuge (ERASE / DUPLICATE)

    fun padHasEvents(p: Int): Boolean = p in 0 until PADS && (seq[p].any { it > 0f } || noteCount(p) > 0)

    /** ERASE + Pad: löscht nur die Pattern-Ereignisse (Schritte und Matrix-Noten) des Pads. */
    fun erasePadEvents(p: Int): Boolean {
        if (!padHasEvents(p)) return false
        pushPatternUndo("Pattern Pad ${p + 1} löschen")
        java.util.Arrays.fill(seq[p], 0f)
        clearNotes(p)
        return true
    }

    /** DUPLICATE: kopiert Sample, Modus, Tonhöhe, Schritte und Noten von [src] auf [dst]. */
    fun duplicatePad(src: Int, dst: Int): Boolean {
        if (src == dst || src !in 0 until PADS || dst !in 0 until PADS) return false
        val l = loops.get(src) ?: return false
        pushUndo("Pad ${src + 1} → Pad ${dst + 1}", intArrayOf(dst), true)
        val c = cloneLoop(l, l.data.copyOf())
        c.muted = false
        c.shotStart = Long.MIN_VALUE
        takes.set(dst, null)
        padOneShot[dst] = l.oneShot
        padSemi[dst] = padSemi[src]
        System.arraycopy(seq[src], 0, seq[dst], 0, STEPS)
        rollBars[dst] = rollBars[src]
        setNotes(dst, getNotes(src))
        padFx[dst] = padFx[src]
        padSource[dst] = padSource[src]
        padChainOn[dst] = padChainOn[src]
        for (k in 0 until 3) {
            setPadFxType(dst, k, pfxType[src][k])
            System.arraycopy(pfxPar[src][k], 0, pfxPar[dst][k], 0, 5)
        }
        loops.set(dst, c)
        return true
    }

    private fun clearPatternLive() {
        for (row in seq) java.util.Arrays.fill(row, 0f)
        for (p in 0 until PADS) clearNotes(p)
        InstState.acidClear()
        InstState.drumClear()
    }

    // ---------------------------------------------------------------- Szenen (wie Maschine: Pattern-Kombinationen, im Takt umschalten)

    const val SCENES = 12
    val scenes = arrayOfNulls<Scene>(SCENES)
    @Volatile var curScene = 0
        private set
    @Volatile var queuedScene = -1
        private set
    /** Kette (Song-Modus): Szenen laufen nacheinander, jede so viele Takte wie eingestellt. */
    @Volatile var sceneChain = false
    @Volatile private var sceneBarCount = 0

    private fun captureScene(bars: Int): Scene =
        Scene(snapPattern(), BooleanArray(PADS) { loops.get(it)?.muted ?: false }, bars)

    private fun applyScene(s: Scene) {
        applyPattern(s.pat)
        for (p in 0 until PADS) loops.get(p)?.muted = s.mutes[p]
    }

    /** Sichert den aktuellen Stand in die laufende Szene. */
    fun storeCurrentScene() {
        scenes[curScene] = captureScene(scenes[curScene]?.bars ?: 4)
    }

    fun sceneExists(i: Int): Boolean = i == curScene || (i in 0 until SCENES && scenes[i] != null)

    fun sceneCount(): Int = (0 until SCENES).count { sceneExists(it) }

    fun sceneBars(i: Int): Int = scenes.getOrNull(i)?.bars ?: 4

    fun setSceneBars(i: Int, bars: Int) {
        if (i !in 0 until SCENES) return
        if (scenes[i] == null && i == curScene) storeCurrentScene()
        scenes[i]?.bars = bars.coerceIn(1, 64)
    }

    /** Takt in der laufenden Szene (für die Ketten-Anzeige). */
    fun sceneBarPos(): Int = sceneBarCount

    private fun switchScene(i: Int) {
        storeCurrentScene()
        val s = scenes[i] ?: return
        applyScene(s)
        curScene = i
        queuedScene = -1
        sceneBarCount = 0
        dropPatternUndo()
    }

    /** Bit 0: wartet auf den nächsten Takt, Bit 1: neu angelegt (Kopie der aktuellen Szene). */
    fun launchScene(i: Int): Int {
        if (i !in 0 until SCENES) return 0
        var created = 0
        if (scenes[i] == null && i != curScene) {
            storeCurrentScene()
            scenes[i] = scenes[curScene]!!.copy()
            created = 2
        }
        if (i == curScene) {
            queuedScene = -1
            return created
        }
        if (!seqPlaying) {
            switchScene(i)
            return created
        }
        queuedScene = i
        return created or 1
    }

    /** ERASE + Szene: andere Szene löschen bzw. die laufende leeren. */
    fun deleteScene(i: Int): Boolean {
        if (i !in 0 until SCENES) return false
        if (i == curScene) {
            pushPatternUndo("Szene ${i + 1} leeren")
            clearPatternLive()
            return true
        }
        if (scenes[i] == null) return false
        scenes[i] = null
        if (queuedScene == i) queuedScene = -1
        return true
    }

    /** DUPLICATE + Szene: Szene [a] nach [b] kopieren. */
    fun copyScene(a: Int, b: Int): Boolean {
        if (a !in 0 until SCENES || b !in 0 until SCENES || a == b) return false
        if (a == curScene) storeCurrentScene()
        val s = scenes[a] ?: return false
        scenes[b] = s.copy()
        if (b == curScene) {
            pushPatternUndo("Szene kopieren")
            applyScene(scenes[b]!!)
        }
        return true
    }

    private fun resetScenes() {
        java.util.Arrays.fill(scenes, null)
        curScene = 0
        queuedScene = -1
        sceneChain = false
        sceneBarCount = 0
    }

    private fun nextScene(): Int {
        for (k in 1..SCENES) {
            val j = (curScene + k) % SCENES
            if (scenes[j] != null) return j
        }
        return curScene
    }

    /** Pro Mischer-Block: auf jeder Eins vorgemerkte Szene starten bzw. die Kette weiterschalten. */
    private fun sceneTick(w: Long, block: Int) {
        if (!seqPlaying) return
        if (arrApplyNow) {
            arrApplyNow = false
            if (songMode) applyArr(0)
        }
        val bar = seqBar()
        if (bar <= 0L) return
        val origin = seqStart()
        val k = -Math.floorDiv(-(w - origin), bar)
        if (k <= 0L) return
        val f = origin + k * bar
        if (f >= w + block) return
        arrBar = (arrBar + 1) % max(1, songBars)
        if (songMode) {
            applyArr(arrBar)
            return
        }
        sceneBarCount++
        if (queuedScene < 0 && sceneChain) {
            val s = scenes[curScene]
            if (s != null && sceneBarCount >= s.bars) {
                val nx = nextScene()
                if (nx != curScene) queuedScene = nx else sceneBarCount = 0
            }
        }
        val q = queuedScene
        if (q >= 0) switchScene(q)
    }

    /** Für Projekte. */
    fun scenesToJson(): org.json.JSONObject {
        storeCurrentScene()
        val j = org.json.JSONObject()
        j.put("cur", curScene)
        j.put("chain", sceneChain)
        val a = org.json.JSONArray()
        for (s in scenes) a.put(s?.toJson() ?: org.json.JSONObject.NULL)
        j.put("list", a)
        return j
    }

    fun scenesFromJson(j: org.json.JSONObject?) {
        resetScenes()
        if (j == null) return
        val a = j.optJSONArray("list") ?: return
        for (i in 0 until min(SCENES, a.length())) {
            val o = a.optJSONObject(i) ?: continue
            scenes[i] = try { Scene.fromJson(o) } catch (e: Exception) { null }
        }
        curScene = j.optInt("cur", 0).coerceIn(0, SCENES - 1)
        sceneChain = j.optBoolean("chain", false)
    }

    // ================================================================ Song-Arrangement (Spuren × Takte, wie Ableton)

    /** 12 Pads + ACID + DRUM. */
    const val ARR_ROWS = 14
    const val ARR_MAX = 64
    val arr = Array(ARR_ROWS) { BooleanArray(ARR_MAX) }
    /** Szene, die ab diesem Takt gilt (-1 = keine Änderung). */
    val arrScene = IntArray(ARR_MAX) { -1 }
    @Volatile var songMode = false
    @Volatile var songBars = 16
    @Volatile private var arrBar = 0
    @Volatile private var arrApplyNow = false

    /** Aktueller Song-Takt (0-basiert). */
    fun songBar(): Int = arrBar

    private fun applyArr(bar: Int) {
        if (bar !in 0 until ARR_MAX) return
        val sc = arrScene[bar]
        if (sc in 0 until SCENES && sc != curScene && scenes[sc] != null) switchScene(sc)
        for (r in 0 until min(12, PADS)) loops.get(r)?.muted = !arr[r][bar]
        InstState.acidOn = arr[12][bar]
        InstState.drumOn = arr[13][bar]
    }

    /** Aktuelle Lage (was gerade läuft) in einen Takt schreiben. */
    fun arrCaptureBar(bar: Int, pads: Int) {
        if (bar !in 0 until ARR_MAX) return
        for (r in 0 until min(12, pads)) {
            val l = loops.get(r)
            arr[r][bar] = l != null && !l.muted
        }
        arr[12][bar] = InstState.acidOn && InstState.acidHasNotes()
        arr[13][bar] = InstState.drumOn && InstState.drumHasNotes()
    }

    /** Song aus den Szenen bauen: jede Szene so lange wie eingestellt, nacheinander. */
    fun arrFillFromScenes(pads: Int) {
        storeCurrentScene()
        arrClear()
        var bar = 0
        for (i in 0 until SCENES) {
            val s = scenes[i] ?: continue
            if (bar >= ARR_MAX) break
            arrScene[bar] = i
            val acid = s.pat.inst.gate.any { it }
            val drums = s.pat.inst.drums.any { r -> r.any { it > 0 } }
            for (b in bar until min(ARR_MAX, bar + s.bars)) {
                for (r in 0 until min(12, pads)) arr[r][b] = loops.get(r) != null && !s.mutes[r]
                arr[12][b] = acid
                arr[13][b] = drums
            }
            bar += s.bars
        }
        if (bar > 0) songBars = intArrayOf(4, 8, 16, 32, 64).firstOrNull { it >= bar } ?: 64
    }

    fun arrClear() {
        for (row in arr) java.util.Arrays.fill(row, false)
        java.util.Arrays.fill(arrScene, -1)
    }

    fun arrToJson(): org.json.JSONObject {
        val j = org.json.JSONObject()
        j.put("mode", songMode)
        j.put("bars", songBars)
        val a = org.json.JSONArray()
        for (row in arr) a.put(String(CharArray(ARR_MAX) { if (row[it]) '1' else '0' }))
        j.put("rows", a)
        j.put("scenes", org.json.JSONArray().apply { arrScene.forEach { put(it) } })
        return j
    }

    fun arrFromJson(j: org.json.JSONObject?) {
        arrClear()
        songMode = false
        songBars = 16
        if (j == null) return
        songMode = j.optBoolean("mode", false)
        songBars = j.optInt("bars", 16).coerceIn(1, ARR_MAX)
        j.optJSONArray("rows")?.let { a ->
            for (r in 0 until min(ARR_ROWS, a.length())) {
                val str = a.optString(r)
                for (b in 0 until min(ARR_MAX, str.length)) arr[r][b] = str[b] == '1'
            }
        }
        j.optJSONArray("scenes")?.let { a -> for (b in 0 until min(ARR_MAX, a.length())) arrScene[b] = a.optInt(b, -1) }
    }

    // ================================================================ Effekte je Pad (bis zu 3 pro Pad)

    /** Effekt-Kette des Pads an/aus (kleiner FX-Knopf am Pad). */
    val padChainOn = BooleanArray(CHAINS)
    /** Effekt je Platz (-1 = leer). */
    val pfxType = Array(CHAINS) { IntArray(3) { -1 } }
    /** X, Y, MIX, P1, P2 je Platz. */
    val pfxPar = Array(CHAINS) { Array(3) { floatArrayOf(0.5f, 0.5f, 1f, 0.5f, 0.5f) } }
    private val pfxInst = Array(CHAINS) { arrayOfNulls<Fx>(3) }

    fun padChainActive(p: Int): Boolean = p in 0 until CHAINS && padChainOn[p] && pfxType[p].any { it >= 0 }

    fun padFxCount(p: Int): Int = pfxType[p].count { it >= 0 }

    fun setPadFxType(p: Int, slot: Int, type: Int) {
        if (p !in 0 until CHAINS || slot !in 0 until 3) return
        if (type < 0) {
            pfxType[p][slot] = -1
            pfxInst[p][slot] = null
            return
        }
        val t = type.coerceIn(0, Fx.COUNT - 1)
        if (pfxInst[p][slot] == null) pfxInst[p][slot] = Fx(if (sampleRate > 0) sampleRate else 48000)
        pfxPar[p][slot][3] = Fx.P1_DEFAULT[t]
        pfxPar[p][slot][4] = Fx.P2_DEFAULT[t]
        pfxType[p][slot] = t
    }

    fun padFxToJson(): org.json.JSONArray {
        val a = org.json.JSONArray()
        for (p in 0 until CHAINS) {
            val o = org.json.JSONObject()
            o.put("on", padChainOn[p])
            val sl = org.json.JSONArray()
            for (k in 0 until 3) {
                val so = org.json.JSONObject()
                so.put("type", pfxType[p][k])
                so.put("par", org.json.JSONArray().apply { pfxPar[p][k].forEach { put(it.toDouble()) } })
                sl.put(so)
            }
            o.put("slots", sl)
            a.put(o)
        }
        return a
    }

    fun padFxFromJson(a: org.json.JSONArray?) {
        for (p in 0 until CHAINS) {
            padChainOn[p] = false
            for (k in 0 until 3) setPadFxType(p, k, -1)
        }
        if (a == null) return
        for (p in 0 until min(CHAINS, a.length())) {
            val o = a.optJSONObject(p) ?: continue
            padChainOn[p] = o.optBoolean("on", false)
            val sl = o.optJSONArray("slots") ?: continue
            for (k in 0 until min(3, sl.length())) {
                val so = sl.optJSONObject(k) ?: continue
                val t = so.optInt("type", -1)
                setPadFxType(p, k, t)
                so.optJSONArray("par")?.let { pa -> for (i in 0 until min(5, pa.length())) pfxPar[p][k][i] = pa.optDouble(i).toFloat() }
            }
        }
    }

    // ================================================================ Clip-Launch & Warp (Ableton-Live-Prinzip)

    /** Start-Raster für Loops: aus, 1/4, 1/2, 1, 2, 4 Takte. */
    val LAUNCH_Q = floatArrayOf(0f, 0.25f, 0.5f, 1f, 2f, 4f)
    val LAUNCH_NAMES = arrayOf("AUS", "1/4", "1/2", "1 TAKT", "2 TAKTE", "4 TAKTE")
    @Volatile var launchIdx = 3
    /** Tipp auf einen laufenden Loop: true = Overdub einrasten, false = stoppen. */
    @Volatile var tapOverdub = true

    /** Merkt sich das aktuelle Audio als Original fürs Warpen (Tempo = Master-Tempo). */
    private fun setWarpSource(l: Loop) {
        val m = masterLen
        if (l.oneShot || l.slices != null || m <= 0L) return
        l.src = l.data
        l.srcBpm = (sampleRate * 240.0 / m).toFloat()
        l.warpBars = l.data.size.toFloat() / m
    }

    /** -1 nichts vorgemerkt, 1 startet zur nächsten Rastergrenze, 0 stoppt dann. */
    fun queuedState(p: Int): Int {
        val l = loops.get(p) ?: return -1
        if (l.pendAt == Long.MIN_VALUE) return -1
        return if (l.pendOn) 1 else 0
    }

    /** Pad antippen: Loop starten/stoppen – quantisiert auf das Start-Raster. 1 = vorgemerkt, 0 = sofort, -1 = nichts. */
    fun launchToggle(p: Int): Int {
        val l = loops.get(p) ?: return -1
        if (l.oneShot) {
            l.muted = !l.muted
            return 0
        }
        if (l.pendAt != Long.MIN_VALUE) {
            // nochmal tippen = Vormerkung zurücknehmen
            l.pendAt = Long.MIN_VALUE
            return 0
        }
        val turnOn = l.muted
        if (!seqPlaying) {
            if (turnOn) {
                l.muted = false
                transportPlay(false)
            } else {
                l.muted = true
            }
            return 0
        }
        // DJ-Clips: ohne Start-Raster trotzdem auf den nächsten Schlag – und immer ab dem Startpunkt
        val q = if (padSyncStart[p] && turnOn && LAUNCH_Q[launchIdx] <= 0f) 0.25f else LAUNCH_Q[launchIdx]
        val m = masterLen
        if (q <= 0f || m <= 0L) {
            l.muted = !turnOn
            return 0
        }
        val unit = max(1L, (m * q).toLong())
        val heard = heardNow()
        val ms = masterStart
        var at = ms - Math.floorDiv(-(heard - ms), unit) * unit
        if (at < written + 64) at += unit
        l.pendOn = turnOn
        l.pendAt = at
        return 1
    }

    fun setPadGain(p: Int, g: Float) {
        loops.get(p)?.gain = g.coerceIn(0f, 1.5f)
    }

    fun padGain(p: Int): Float = loops.get(p)?.gain ?: 1f

    /** Bricht eine laufende Aufnahme/Overdub ab (Wischgeste) und stellt den Loop wieder her. */
    fun abortTake(p: Int) {
        val t = takes.get(p) ?: return
        // ein beendeter (eingerasteter) Overdub wird nur noch fertig geschrieben – nicht verwerfen
        if (t.released && t.latched) return
        if (!takes.compareAndSet(p, t, null)) return
        val b = t.before
        val l = loops.get(p)
        if (t.overdub && b != null && l != null && b.size == l.data.size) System.arraycopy(b, 0, l.data, 0, b.size)
    }

    fun isLatched(p: Int): Boolean = takes.get(p)?.let { it.latched && !it.released } ?: false

    /**
     * Datei-Clip laden (Ableton-Stil): Tempo/Länge erkannt, auf das Master-Tempo gewarpt.
     * Der Clip wartet (bereit), bis man das Pad antippt. Liefert eine Beschreibung.
     */
    /** Pads, die beim Antippen immer vom Startpunkt im Takt starten (DJ-Clips). */
    val padSyncStart = BooleanArray(PADS)

    fun importClip(p: Int, src: FloatArray, info: Warp.Info): String {
        padSyncStart[p] = false
        if (src.isEmpty()) return "Leere Datei"
        if (padOneShot[p]) {
            importLoop(p, src)
            return "⚡ One Shot"
        }
        pushPadUndo("Laden Pad ${p + 1}", p)
        var bpmNote = ""
        if (masterLen <= 0L) {
            // erster Loop: sein Tempo wird das Master-Tempo
            masterLen = (sampleRate * 240.0 / info.bpm).toLong()
            masterStart = written
        } else {
            val cur = currentBpm()
            if (abs(cur - info.bpm) > 0.05f) bpmNote = String.format(java.util.Locale.GERMANY, " → gewarpt auf %.0f BPM", cur)
        }
        val m = masterLen
        val target = max(64L, (info.bars * m).toLong()).toInt()
        val data = Warp.stretch(src, target, sampleRate)
        val fade = min(data.size / 4, sampleRate * FADE_MS / 1000)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            data[i] *= g
            data[data.size - 1 - i] *= g
        }
        val loop = Loop(data)
        loop.src = src
        loop.srcBpm = info.bpm
        loop.warpBars = info.bars
        loop.start = masterStart
        loop.muted = true
        takes.set(p, null)
        padSemi[p] = 0
        loops.set(p, loop)
        val barsTxt = if (info.bars >= 1f && info.bars == info.bars.toInt().toFloat()) "${info.bars.toInt()} Takt" + (if (info.bars > 1f) "e" else "") else "${info.bars} Takt"
        return String.format(java.util.Locale.GERMANY, "%s · %.1f BPM (%s)%s", barsTxt, info.bpm, info.source, bpmNote)
    }

    @Volatile private var warpGen = 0

    /** Nach einem Tempowechsel alle Loops neu dehnen (im Hintergrund, Tonhöhe bleibt). */
    private fun scheduleRewarp() {
        val gen = ++warpGen
        Thread {
            try { Thread.sleep(180) } catch (ignored: InterruptedException) {}
            if (gen != warpGen) return@Thread
            for (p in 0 until PADS) {
                if (gen != warpGen) return@Thread
                val l = loops.get(p) ?: continue
                val src = l.src ?: continue
                if (l.oneShot || l.slices != null || l.warpBars <= 0f) continue
                if (takes.get(p) != null) continue
                val m = masterLen
                if (m <= 0L) return@Thread
                val target = max(64L, (l.warpBars * m).toLong()).toInt()
                if (target == l.data.size) continue
                val data = Warp.stretch(src, target, sampleRate)
                if (gen != warpGen) return@Thread
                val nl = cloneLoop(l, data)
                nl.muted = l.muted
                nl.pendAt = l.pendAt
                nl.pendOn = l.pendOn
                // im Raster weiterlaufen: gleicher Takt innerhalb des Loops
                val heard = heardNow()
                val ms = masterStart
                val oldLen = l.data.size.toLong()
                val f = Math.floorMod(heard - l.start, oldLen).toDouble() / oldLen
                val nb = l.warpBars
                val barIdx = if (nb >= 1f) (f * nb).toInt().coerceIn(0, max(0, nb.toInt() - 1)) else 0
                val curBar = ms + Math.floorDiv(heard - ms, m) * m
                nl.start = curBar - barIdx * m
                if (!loops.compareAndSet(p, l, nl)) continue
            }
        }.start()
    }
}

/** Schritte der Instrumente (ACID/DRUM) als Kopie – für Undo und Szenen. */
class InstSnap(
    val semi: IntArray, val gate: BooleanArray, val acc: BooleanArray, val slide: BooleanArray, val drums: Array<IntArray>
) {
    fun restore() {
        val st = InstState
        System.arraycopy(semi, 0, st.acidSemi, 0, min(semi.size, st.acidSemi.size))
        System.arraycopy(gate, 0, st.acidGate, 0, min(gate.size, st.acidGate.size))
        System.arraycopy(acc, 0, st.acidAcc, 0, min(acc.size, st.acidAcc.size))
        System.arraycopy(slide, 0, st.acidSlide, 0, min(slide.size, st.acidSlide.size))
        for (v in 0 until min(drums.size, InstState.DV)) System.arraycopy(drums[v], 0, st.drumSteps[v], 0, min(drums[v].size, InstState.STEPS))
    }

    fun copy() = InstSnap(semi.copyOf(), gate.copyOf(), acc.copyOf(), slide.copyOf(), Array(drums.size) { drums[it].copyOf() })

    fun toJson(): org.json.JSONObject {
        val j = org.json.JSONObject()
        j.put("semi", org.json.JSONArray().apply { semi.forEach { put(it) } })
        j.put("gate", org.json.JSONArray().apply { gate.forEach { put(it) } })
        j.put("acc", org.json.JSONArray().apply { acc.forEach { put(it) } })
        j.put("slide", org.json.JSONArray().apply { slide.forEach { put(it) } })
        j.put("drums", org.json.JSONArray().apply { drums.forEach { r -> put(org.json.JSONArray().apply { r.forEach { put(it) } }) } })
        return j
    }

    companion object {
        fun capture(): InstSnap {
            val st = InstState
            return InstSnap(
                st.acidSemi.copyOf(), st.acidGate.copyOf(), st.acidAcc.copyOf(), st.acidSlide.copyOf(),
                Array(InstState.DV) { st.drumSteps[it].copyOf() }
            )
        }

        fun fromJson(j: org.json.JSONObject): InstSnap {
            val n = InstState.STEPS
            fun ia(k: String) = IntArray(n).also { a -> j.optJSONArray(k)?.let { for (i in 0 until min(n, it.length())) a[i] = it.optInt(i) } }
            fun ba(k: String) = BooleanArray(n).also { a -> j.optJSONArray(k)?.let { for (i in 0 until min(n, it.length())) a[i] = it.optBoolean(i) } }
            val dr = Array(InstState.DV) { IntArray(n) }
            j.optJSONArray("drums")?.let { d ->
                for (v in 0 until min(InstState.DV, d.length())) d.optJSONArray(v)?.let { r -> for (i in 0 until min(n, r.length())) dr[v][i] = r.optInt(i) }
            }
            return InstSnap(ia("semi"), ba("gate"), ba("acc"), ba("slide"), dr)
        }
    }
}

/** Ein komplettes Pattern: Sequenzer-Schritte, Matrix-Noten, Matrix-Längen und Instrument-Schritte. */
class PatternSnap(val seq: Array<FloatArray>, val notes: Array<List<FloatArray>>, val rollBars: IntArray, val inst: InstSnap) {
    fun copy() = PatternSnap(
        Array(seq.size) { seq[it].copyOf() }, Array(notes.size) { p -> notes[p].map { it.copyOf() } },
        rollBars.copyOf(), inst.copy()
    )

    fun toJson(): org.json.JSONObject {
        val j = org.json.JSONObject()
        j.put("seq", org.json.JSONArray().apply { seq.forEach { r -> put(org.json.JSONArray().apply { r.forEach { put(it.toDouble()) } }) } })
        j.put("notes", org.json.JSONArray().apply {
            notes.forEach { list -> put(org.json.JSONArray().apply { list.forEach { ev -> put(org.json.JSONArray().apply { ev.forEach { put(it.toDouble()) } }) } }) }
        })
        j.put("rollBars", org.json.JSONArray().apply { rollBars.forEach { put(it) } })
        j.put("inst", inst.toJson())
        return j
    }

    companion object {
        fun fromJson(j: org.json.JSONObject): PatternSnap {
            val pads = Engine.PADS
            val seq = Array(pads) { FloatArray(Engine.STEPS) }
            j.optJSONArray("seq")?.let { a ->
                for (p in 0 until min(pads, a.length())) a.optJSONArray(p)?.let { r -> for (k in 0 until min(Engine.STEPS, r.length())) seq[p][k] = r.optDouble(k).toFloat() }
            }
            val notes = Array<List<FloatArray>>(pads) { emptyList() }
            j.optJSONArray("notes")?.let { a ->
                for (p in 0 until min(pads, a.length())) {
                    val l = a.optJSONArray(p) ?: continue
                    notes[p] = (0 until l.length()).mapNotNull { q -> l.optJSONArray(q)?.let { e -> FloatArray(e.length()) { e.optDouble(it).toFloat() } } }
                }
            }
            val rb = IntArray(pads) { 1 }
            j.optJSONArray("rollBars")?.let { a -> for (p in 0 until min(pads, a.length())) rb[p] = a.optInt(p, 1) }
            val inst = j.optJSONObject("inst")?.let { InstSnap.fromJson(it) } ?: InstSnap.capture()
            return PatternSnap(seq, notes, rb, inst)
        }
    }
}

/** Eine Szene: Pattern + welche Pads stumm sind + Länge in Takten (für die Kette). */
class Scene(val pat: PatternSnap, val mutes: BooleanArray, @Volatile var bars: Int = 4) {
    fun copy() = Scene(pat.copy(), mutes.copyOf(), bars)

    fun toJson(): org.json.JSONObject {
        val j = pat.toJson()
        j.put("mutes", org.json.JSONArray().apply { mutes.forEach { put(it) } })
        j.put("bars", bars)
        return j
    }

    companion object {
        fun fromJson(j: org.json.JSONObject): Scene {
            val m = BooleanArray(Engine.PADS)
            j.optJSONArray("mutes")?.let { a -> for (p in 0 until min(Engine.PADS, a.length())) m[p] = a.optBoolean(p) }
            return Scene(PatternSnap.fromJson(j), m, j.optInt("bars", 4))
        }
    }
}
