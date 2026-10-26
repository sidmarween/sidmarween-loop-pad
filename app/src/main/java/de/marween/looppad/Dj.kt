package de.marween.looppad

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * DJ-Modus: zwei Decks (Stereo), 3-Band-EQ, Filter, Effekt, Kanal-Fader, Crossfader,
 * Pitch, SYNC (auf das Master-Tempo der Pads), Cue, Hot Cues, Auto-Loops, Scratchen.
 * Gerendert im Mischer-Thread der [Engine].
 */
object Dj {
    const val BIN = 512
    /** Längster Track (Speicher: Stereo 16 Bit). */
    const val MAX_SECONDS = 600

    /** Biquad (direkte Form I), Koeffizienten werden nur bei Änderung neu berechnet. */
    class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        var key = Double.NaN

        fun lp(sr: Int, f: Double, q: Double) = set(sr, f, q, true)
        fun hp(sr: Int, f: Double, q: Double) = set(sr, f, q, false)

        private fun set(sr: Int, f: Double, q: Double, low: Boolean) {
            val k = (if (low) 1.0 else -1.0) * (f * 1000.0 + q)
            if (k == key) return
            key = k
            val w0 = 2 * PI * min(f, sr * 0.45) / sr
            val al = sin(w0) / (2 * q)
            val c = cos(w0)
            val a0 = 1 + al
            if (low) {
                b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0
            } else {
                b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0
            }
            a1 = -2 * c / a0
            a2 = (1 - al) / a0
        }

        fun run(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = if (abs(y) < 1e-25) 0.0 else y
            return y1.toFloat()
        }

        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
    }

    class Deck(val idx: Int) {
        /** Audio: 16 Bit, [ch] Kanäle verschachtelt, in der Original-Abtastrate [srcRate]. */
        @Volatile var data: ShortArray? = null
        @Volatile var ch = 2
        @Volatile var len = 0
        @Volatile var srcRate = 48000
        @Volatile var title = ""
        @Volatile var bpm = 0f
        /** Erster Schlag in Frames (Beat-Raster). */
        @Volatile var grid = 0.0
        /** Wellenform-Übersicht: Pegel und Bass-Anteil je [BIN] Frames. */
        @Volatile var peaks: ByteArray = ByteArray(0)
        @Volatile var lows: ByteArray = ByteArray(0)
        @Volatile var mids: ByteArray = ByteArray(0)
        @Volatile var highs: ByteArray = ByteArray(0)
        /** Gemessene Lautheit (LUFS-ähnlich) und daraus die Auto-Gain-Verstärkung. */
        @Volatile var lufs = Float.NaN
        @Volatile var agFactor = 1f
        @Volatile var loading = false
        /** Lade-Fortschritt 0..1, -1 = lädt nicht. */
        @Volatile var loadProg = -1f
        /** Eintrag der DJ-Bibliothek (für das Speichern geänderter Cue-Punkte). */
        @Volatile var libId: String? = null
        /** Eintrag der Sammlung ([TrackDb]). */
        @Volatile var colId: String? = null

        /** Abspielposition (Frames der Quelle) – nur der Mischer-Thread schreibt, die Oberfläche liest. */
        @Volatile var pos = 0.0
        @Volatile var seekTo = -1.0
        @Volatile var playing = false
        /** Pitch-Fader -1..1 und Bereich (0.08 = ±8 %). */
        @Volatile var pitch = 0f
        @Volatile var range = 0.08f
        @Volatile var sync = false
        @Volatile var syncReq = false
        /** Jog-Rad: kurzzeitige Tempo-Biegung (−0.5..0.5). */
        @Volatile var bend = 0f
        @Volatile var scratching = false
        @Volatile var scratchTarget = 0.0
        @Volatile var cuePoint = 0.0
        val hot = DoubleArray(8) { -1.0 }
        @Volatile var loopOn = false
        @Volatile var loopIn = 0.0
        @Volatile var loopOut = 0.0
        @Volatile var loopBeats = 4f
        /** Mixer: EQ 0..1 (0.5 = neutral, 0 = Kill), Filter 0..1 (0.5 = aus), Gain, Fader, Effekt. */
        @Volatile var hi = 0.5f
        @Volatile var mid = 0.5f
        @Volatile var lo = 0.5f
        @Volatile var filter = 0.5f
        @Volatile var gain = 0.5f
        @Volatile var fader = 1f
        @Volatile var fxType = 7
        @Volatile var fxAmt = 0f
        @Volatile var fxX = 0.5f
        /** FX-Knopf am Kanal: Effekt dauerhaft an (Stärke [fxLevel]). */
        @Volatile var fxOn = false
        @Volatile var fxLevel = 0.6f
        @Volatile var cue = false
        @Volatile var level = 0f
        /** Effektives Tempo-Verhältnis (für Anzeige). */
        @Volatile var ratio = 1.0
        /** Tonart (0..11 Dur, 12..23 Moll, -1 unbekannt) und Sicherheit der Erkennung. */
        @Volatile var key = -1
        @Volatile var keyConf = 0f
        @Volatile var keyGen = 0
        /** Keylock (Master Tempo): Tonhöhe bleibt beim Tempo-Ändern gleich. */
        @Volatile var keylock = false
        /** Tonart verschieben (Halbtöne, −6..+6) – schaltet automatisch Keylock-Klang ein. */
        @Volatile var keyShift = 0
        // Keylock-Körner (Mischer-Thread)
        internal val gPos = DoubleArray(2)
        internal val gAge = IntArray(2)
        internal val gOn = BooleanArray(2)
        internal var gSlot = 0
        internal var gNext = 0
        @Volatile internal var gReset = true
        internal var gWin = FloatArray(0)

        /** Takt-Eins: Schlag-Versatz (0..3) gegenüber [grid]; Phrasen-Anfang: Takt-Versatz (0..15). */
        @Volatile var barOffset = 0
        @Volatile var phraseOff = 0
        /** Beat Jump: Weite in Takten. */
        @Volatile var jumpBars = 4f
        /** SLIP: Loops, Rolls und Scratchen laufen „im Hintergrund“ weiter – loslassen = dort weiter. */
        @Volatile var slipOn = false
        @Volatile var slipTrack = false
        @Volatile var slipPos = 0.0
        /** Loop Roll (solange gedrückt) – vorheriger Loop wird danach wiederhergestellt. */
        @Volatile var rollBeats = 0f
        internal var rollSaveOn = false
        internal var rollSaveIn = 0.0
        internal var rollSaveOut = 0.0

        /** DJ-Effekte: 1 = Brake (Plattenstopp), 2 = Backspin – laufen von selbst aus und halten das Deck an. */
        @Volatile var stopFx = 0
        @Volatile var stopFxReq = 0
        @Volatile var brakeSec = 1.2f
        internal var stopT = 0.0
        internal var stopLen = 1.0
        /** CENSOR: rückwärts, solange gedrückt (mit Slip weiter). */
        @Volatile var censor = false
        /** Echo-Out: Track hört auf, das Echo läuft im Takt aus. */
        @Volatile var echoReq = false
        @Volatile var echoOn = false
        internal var echoL = FloatArray(0)
        internal var echoR = FloatArray(0)
        internal var echoW = 0
        internal var echoDry = 1f
        internal var echoIn = 1f
        internal var echoCut = 0
        internal var echoHpL = 0f
        internal var echoHpR = 0f
        internal var echoLpL = 0f
        internal var echoLpR = 0f
        /** Filter-Sweep: 1 = BUILD (Hochpass steigt), 2 = SWEEP (Tiefpass schließt), über [sweepBars] Takte. */
        @Volatile var sweepMode = 0
        @Volatile var sweepBars = 8f
        @Volatile var sweepStart = 0L
        /** Stems live: Karte (null = noch nicht berechnet), Fortschritt der Analyse (-1 = keine), Regler VOC/MEL/BASS/DRUMS. */
        @Volatile var stemMask: DeckStems.Mask? = null
        @Volatile var stemProg = -1f
        @Volatile var stemGen = 0
        val stemGain = floatArrayOf(1f, 1f, 1f, 1f)
        internal val stemLive = DeckStems.Live()

        /** Hörbare Tonart: mit Key-Shift und (ohne Keylock) der Tonhöhenänderung durchs Tempo. */
        fun effKey(): Int {
            if (key < 0) return -1
            val tempoSemis = if (keylock || keyShift != 0) 0.0 else Keys.semisOfRatio(ratio * (1.0 + bend))
            return Keys.shift(key, keyShift + Math.round(tempoSemis).toInt())
        }

        /** Tempo verbiegt die Tonhöhe hörbar (ohne Keylock, ab ~¼ Halbton). */
        fun keyDrift(): Double = if (keylock || keyShift != 0) 0.0 else Keys.semisOfRatio(ratio)

        internal val lpL = Biquad(); internal val lpR = Biquad()
        internal val hpL = Biquad(); internal val hpR = Biquad()
        internal val fL = Biquad(); internal val fR = Biquad()
        internal var fxL: Fx? = null
        internal var fxR: Fx? = null
        internal var bufL = FloatArray(0)
        internal var bufR = FloatArray(0)
        internal var scratchVel = 0.0

        fun loaded(): Boolean = data != null && len > 1

        /** Frames pro Schlag in der Quelle. */
        fun beatFrames(): Double = if (bpm > 0f) srcRate * 60.0 / bpm else srcRate * 0.5

        /** Aktuelles Tempo (mit Pitch/Sync). */
        fun effBpm(): Float = (bpm * ratio).toFloat()

        fun seconds(): Double = len.toDouble() / srcRate
    }

    val decks = arrayOf(Deck(0), Deck(1))
    /** Gig-Modus: Schutz vor versehentlichem Stoppen/Laden auf einem hörbaren Deck. */
    @Volatile var gigMode = false

    /** Deck ist gerade im Master zu hören (läuft, Fader offen, Crossfader nicht zu). */
    fun onAir(d: Deck): Boolean = d.playing && d.fader > 0.05f && xGain(d.idx) > 0.05f

    /** Crossfader 0 = nur A, 1 = nur B. */
    @Volatile var xfade = 0.5f
    /** 0 = weich (konstante Leistung), 1 = hart (Scratch). */
    @Volatile var xcurve = 0
    /** Vorhören: links Master, rechts Cue (ein Kopfhörer-Ausgang). */
    @Volatile var splitCue = false
    @Volatile var cueGain = 0.9f

    fun active(): Boolean = decks[0].loaded() || decks[1].loaded()

    private fun xGain(d: Int): Float {
        val x = if (d == 0) 1f - xfade else xfade
        return if (xcurve == 1) min(1f, x * 4f) else sin(x * PI.toFloat() / 2f)
    }

    private fun eqGain(v: Float): Float = if (v < 0.5f) (v / 0.5f).let { it * it } else 1f + (v - 0.5f) * 2f

    private fun gainOf(v: Float): Float = if (v < 0.5f) v * 2f else 1f + (v - 0.5f) * 2f

    /** Rendert beide Decks in L/R (nach Fader + Crossfader) und den Vorhör-Bus (mono). Liefert true, wenn ein Deck geladen ist. */
    fun render(outL: FloatArray, outR: FloatArray, cueBus: FloatArray, n: Int, w: Long): Boolean {
        java.util.Arrays.fill(outL, 0, n, 0f)
        java.util.Arrays.fill(outR, 0, n, 0f)
        java.util.Arrays.fill(cueBus, 0, n, 0f)
        if (!active()) return false
        val sr = Engine.sampleRate
        for (d in decks) {
            if (!d.loaded()) {
                d.level *= 0.9f
                continue
            }
            if (d.bufL.size < n) {
                d.bufL = FloatArray(n)
                d.bufR = FloatArray(n)
            }
            val bl = d.bufL
            val br = d.bufR
            val p0 = d.pos
            renderDeck(d, bl, br, n, w, sr)
            // Stems live (immer im Signalweg: gleiche Verzögerung für beide Decks)
            run {
                val base = d.srcRate.toDouble() / sr
                val moved = abs(d.pos - p0) / max(1, n)
                val pf = when {
                    d.keylock -> Keys.factor(d.keyShift.toDouble())
                    moved > 1e-4 -> moved / base * Keys.factor(d.keyShift.toDouble())
                    else -> 1.0
                }
                d.stemLive.process(bl, br, n, p0, d.pos, pf, sr, d.stemMask, d.stemGain)
            }
            // Filter-Sweep im Takt (BUILD hoch / SWEEP zu)
            if (d.sweepMode != 0 && d.bpm > 0f) {
                val len = sr * 240.0 / max(1f, d.effBpm()) * d.sweepBars
                val t = ((w - d.sweepStart) / len).coerceIn(0.0, 1.0).toFloat()
                val e = t * t * (3 - 2 * t)
                d.filter = if (d.sweepMode == 1) 0.5f + 0.43f * e else 0.5f - 0.44f * e
            }
            // EQ (Bass < 250 Hz, Höhen > 3 kHz, Mitten = Rest) – neutral exakt durchlässig
            val gl = eqGain(d.lo); val gm = eqGain(d.mid); val gh = eqGain(d.hi)
            val eqOn = abs(gl - 1f) > 0.01f || abs(gm - 1f) > 0.01f || abs(gh - 1f) > 0.01f
            d.lpL.lp(sr, 250.0, 0.707); d.lpR.lp(sr, 250.0, 0.707)
            d.hpL.hp(sr, 3000.0, 0.707); d.hpR.hp(sr, 3000.0, 0.707)
            val f = d.filter
            val fOn = abs(f - 0.5f) > 0.03f
            if (fOn) {
                if (f < 0.5f) {
                    val t = (0.5f - f) / 0.47f
                    val fc = 20000.0 * exp(-t.coerceIn(0f, 1f) * ln(20000.0 / 70.0))
                    d.fL.lp(sr, fc, 1.1); d.fR.lp(sr, fc, 1.1)
                } else {
                    val t = (f - 0.5f) / 0.47f
                    val fc = 20.0 * exp(t.coerceIn(0f, 1f) * ln(9000.0 / 20.0))
                    d.fL.hp(sr, fc, 1.1); d.fR.hp(sr, fc, 1.1)
                }
            }
            val g = gainOf(d.gain) * (if (autoGain) d.agFactor else 1f)
            var peak = 0f
            for (i in 0 until n) {
                var l = bl[i]
                var r = br[i]
                if (eqOn) {
                    val ll = d.lpL.run(l); val hl = d.hpL.run(l)
                    val lr = d.lpR.run(r); val hr = d.hpR.run(r)
                    l = ll * gl + (l - ll - hl) * gm + hl * gh
                    r = lr * gl + (r - lr - hr) * gm + hr * gh
                } else {
                    d.lpL.run(l); d.hpL.run(l); d.lpR.run(r); d.hpR.run(r)
                }
                if (fOn) {
                    l = d.fL.run(l)
                    r = d.fR.run(r)
                }
                l *= g
                r *= g
                bl[i] = l
                br[i] = r
                val a = max(abs(l), abs(r))
                if (a > peak) peak = a
            }
            // Effekt je Deck (ein Regler: 0 = aus)
            val amt = if (d.fxAmt > 0.02f) d.fxAmt else if (d.fxOn) d.fxLevel else 0f
            if (amt > 0.02f && d.fxType >= 0) {
                if (d.fxL == null) {
                    d.fxL = Fx(sr)
                    d.fxR = Fx(sr)
                }
                val t = d.fxType
                val mix = min(1f, amt * 1.6f)
                d.fxL!!.process(bl, n, w, t, d.fxX, 0.55f, true, mix, Fx.P1_DEFAULT[t], Fx.P2_DEFAULT[t], Engine.masterFrames(), 0L, bl)
                d.fxR!!.process(br, n, w, t, d.fxX, 0.55f, true, mix, Fx.P1_DEFAULT[t], Fx.P2_DEFAULT[t], Engine.masterFrames(), 0L, br)
            }
            // Echo-Out: Echo (punktierte Achtel) auf den Kanal, das Original blendet in ¼ Schlag aus
            if (d.echoReq) {
                d.echoReq = false
                val sz = sr * 3
                if (d.echoL.size != sz) { d.echoL = FloatArray(sz); d.echoR = FloatArray(sz) } else { java.util.Arrays.fill(d.echoL, 0f); java.util.Arrays.fill(d.echoR, 0f) }
                d.echoW = 0
                d.echoDry = 1f
                d.echoIn = 1f
                d.echoCut = max(64, (sr * 60.0 / max(40f, d.effBpm()) * 0.25).toInt())
                d.echoOn = true
            }
            if (d.echoOn) {
                val beat = sr * 60.0 / max(40f, if (d.bpm > 0f) d.effBpm() else 120f)
                val dl = (beat * 0.75).toInt().coerceIn(64, d.echoL.size - 1)
                val sz = d.echoL.size
                val fb = 0.6f
                val aH = 0.03f
                val aL = 0.35f
                var tail = 0f
                val fadeStep = 1f / max(1, d.echoCut)
                for (i in 0 until n) {
                    if (d.echoCut > 0) {
                        d.echoCut--
                        d.echoDry = max(0f, d.echoDry - fadeStep)
                        if (d.echoCut == 0) { d.echoIn = 0f; if (d.playing) { d.playing = false; d.slipTrack = false } }
                    } else if (d.playing) d.echoDry = min(1f, d.echoDry + 0.002f)
                    var ri = d.echoW - dl
                    if (ri < 0) ri += sz
                    var el = d.echoL[ri]
                    var er = d.echoR[ri]
                    // Rückkopplung dünner und dunkler (klingt weg statt zu matschen)
                    d.echoHpL += aH * (el - d.echoHpL); d.echoHpR += aH * (er - d.echoHpR)
                    el -= d.echoHpL; er -= d.echoHpR
                    d.echoLpL += aL * (el - d.echoLpL); d.echoLpR += aL * (er - d.echoLpR)
                    el = d.echoLpL; er = d.echoLpR
                    val xl = bl[i]
                    val xr = br[i]
                    d.echoL[d.echoW] = xl * d.echoIn + el * fb
                    d.echoR[d.echoW] = xr * d.echoIn + er * fb
                    d.echoW++
                    if (d.echoW >= sz) d.echoW = 0
                    bl[i] = xl * d.echoDry + el
                    br[i] = xr * d.echoDry + er
                    tail = max(tail, abs(el) + abs(er))
                    if (abs(bl[i]) > peak) peak = abs(bl[i])
                }
                if (d.echoIn == 0f && d.echoCut == 0 && tail < 1e-4f) { d.echoOn = false; d.echoDry = 1f }
            }
            val fg = d.fader * xGain(d.idx)
            for (i in 0 until n) {
                outL[i] += bl[i] * fg
                outR[i] += br[i] * fg
                if (d.cue) cueBus[i] += (bl[i] + br[i]) * 0.5f
            }
            d.level = max(peak * d.fader, d.level * 0.88f)
        }
        return true
    }

    private fun renderDeck(d: Deck, bl: FloatArray, br: FloatArray, n: Int, w: Long, sr: Int) {
        val data = d.data ?: return
        val ch = d.ch
        val len = min(d.len, data.size / max(1, ch))
        val st = d.seekTo
        if (st >= 0) {
            d.pos = st.coerceIn(0.0, (len - 2).toDouble())
            d.seekTo = -1.0
            d.gReset = true
        }
        val base = d.srcRate.toDouble() / sr
        val mb = Engine.currentBpm()
        // SYNC: Tempo auf das Master-Tempo, Phase auf den Schlag
        if (d.syncReq) {
            d.syncReq = false
            if (d.bpm > 0f) {
                if (mb <= 0f) {
                    // noch kein Master-Tempo: das andere laufende Deck (oder dieses) gibt es vor
                    val o = decks[1 - d.idx]
                    val ref = if (o.loaded() && o.playing && o.bpm > 0f) o else d
                    val rb = ref.bpm * if (ref === d) (1.0 + d.pitch * d.range) else ref.ratio
                    val ph = beatPhase(ref, ref.pos)
                    val outBeat = sr * 60.0 / rb
                    Engine.setBpmAnchored(rb.toFloat(), w - (ph * outBeat).toLong())
                }
                d.sync = true
                alignPhase(d, w)
            }
        }
        val ratio = if (d.sync && d.bpm > 0f && mb > 0f) mb / d.bpm.toDouble() else 1.0 + d.pitch * d.range
        d.ratio = ratio
        val playing = d.playing
        if (d.scratching) {
            // Scratchen: Position folgt dem Finger (weich nachgezogen)
            val target = d.scratchTarget
            val vel = ((target - d.pos) / n).coerceIn(-6.0, 6.0)
            d.scratchVel = d.scratchVel * 0.6 + vel * 0.4
        } else {
            d.scratchVel = 0.0
        }
        if (!playing && !d.scratching) {
            java.util.Arrays.fill(bl, 0, n, 0f)
            java.util.Arrays.fill(br, 0, n, 0f)
            return
        }
        // SLIP: unsichtbarer Abspielkopf läuft im normalen Tempo weiter
        if (d.slipTrack && playing) d.slipPos = min((len - 2).toDouble(), d.slipPos + base * ratio * n)
        var rate = if (d.scratching) d.scratchVel else base * ratio * (1.0 + d.bend)
        // Brake / Backspin: Tempo läuft von selbst gegen null (wie Platte anhalten / zurückdrehen)
        if (d.stopFxReq != 0) {
            d.stopFx = d.stopFxReq
            d.stopFxReq = 0
            d.stopT = 0.0
            d.stopLen = sr * (if (d.stopFx == 1) d.brakeSec.toDouble() else 1.4)
        }
        if (d.stopFx != 0 && !d.scratching) {
            val t = (d.stopT / d.stopLen).coerceIn(0.0, 1.0)
            val m = if (d.stopFx == 1) (1.0 - t) * (1.0 - t * 0.35) else -3.2 * (1.0 - t) * (1.0 - t)
            rate *= m
            d.stopT += n
            if (t >= 1.0) {
                d.stopFx = 0
                d.playing = false
                d.slipTrack = false
            }
        } else if (d.censor && !d.scratching) rate = -rate
        // Keylock / Key-Shift: Tempo und Tonhöhe getrennt (Körner-Verfahren mit Phasen-Suche)
        if (!d.scratching && d.stopFx == 0 && !d.censor && (d.keylock || d.keyShift != 0)) {
            val pf = Keys.factor(d.keyShift.toDouble())
            val speed = if (d.keylock) base * pf else rate * pf
            if (abs(speed - rate) > 1e-6) {
                renderLocked(d, data, ch, len, bl, br, n, rate, speed, sr)
                return
            }
        }
        d.gReset = true
        var p = d.pos
        val lOn = d.loopOn && d.loopOut > d.loopIn + 16
        val li = d.loopIn
        val lo = d.loopOut
        val last = len - 2
        for (i in 0 until n) {
            if (p < 0) p = 0.0
            if (p > last) {
                bl[i] = 0f
                br[i] = 0f
                continue
            }
            val i0 = p.toInt()
            val fr = (p - i0).toFloat()
            val a = i0 * ch
            val b = a + ch
            val l0 = data[a] / 32768f
            val l1 = data[b] / 32768f
            val l = l0 + (l1 - l0) * fr
            val r = if (ch == 2) {
                val r0 = data[a + 1] / 32768f
                val r1 = data[b + 1] / 32768f
                r0 + (r1 - r0) * fr
            } else l
            bl[i] = l
            br[i] = r
            p += rate
            if (lOn && rate > 0 && p >= lo) p -= (lo - li)
        }
        if (p >= last && !d.scratching) {
            d.playing = false
            p = last.toDouble()
        }
        d.pos = p
    }

    /** Quellposition mit Loop-Umbruch (für Körner, die über das Loop-Ende hinaus lesen). */
    private fun wrapLoop(d: Deck, pos: Double): Double {
        if (!d.loopOn) return pos
        val li = d.loopIn
        val lo = d.loopOut
        val l = lo - li
        if (l <= 16 || pos < lo) return pos
        return li + (pos - li) % l
    }

    private fun monoAt(data: ShortArray, ch: Int, len: Int, i: Int): Float {
        if (i < 0 || i >= len) return 0f
        return if (ch == 2) (data[2 * i] + data[2 * i + 1]) * 0.5f else data[i].toFloat()
    }

    /**
     * Keylock-Wiedergabe: Abspielkopf läuft mit [rate] (Tempo), der Klang wird aus überlappenden
     * Körnern (~60 ms, Hann, 50 %) mit Tonhöhe [speed] gelesen. Jedes neue Korn sucht im Umfeld
     * des Abspielkopfs die Stelle, die am besten an das vorige anschließt (wie WSOLA) – kein Flattern.
     */
    private fun renderLocked(d: Deck, data: ShortArray, ch: Int, len: Int, bl: FloatArray, br: FloatArray, n: Int, rate: Double, speed: Double, sr: Int) {
        val g = ((sr * 0.06).toInt() / 2) * 2
        val h = g / 2
        if (d.gWin.size != g) {
            d.gWin = FloatArray(g) { val s = sin(PI * it / g); (s * s).toFloat() }
            d.gReset = true
        }
        val win = d.gWin
        var p = d.pos
        val last = len - 2
        val lOn = d.loopOn && d.loopOut > d.loopIn + 16
        val li = d.loopIn
        val lo = d.loopOut
        val tol = (d.srcRate * 0.012).toInt()
        val corrN = (d.srcRate * 0.012).toInt()
        for (i in 0 until n) {
            if (d.gReset) {
                // Neustart (Play, Sprung, Seek): ein Korn schon halb durch, das nächste beginnt sofort – keine Delle
                d.gReset = false
                d.gSlot = 0
                d.gPos[0] = p - h * speed
                d.gAge[0] = h
                d.gOn[0] = true
                d.gOn[1] = false
                d.gSlot = 1
                d.gNext = 0
            }
            if (d.gNext <= 0) {
                val s = d.gSlot
                val prev = 1 - s
                var c = p
                if (d.gOn[prev]) {
                    // natürliche Fortsetzung des vorigen Korns
                    val nat = wrapLoop(d, d.gPos[prev] + d.gAge[prev] * speed).toInt()
                    var best = p
                    var bs = Double.NEGATIVE_INFINITY
                    var cand = -tol
                    val pi0 = p.toInt()
                    while (cand <= tol) {
                        val cc = pi0 + cand
                        var sum = 0.0
                        var e = 1e-9
                        var j = 0
                        while (j < corrN) {
                            val u = monoAt(data, ch, len, cc + j)
                            sum += u * monoAt(data, ch, len, nat + j)
                            e += u * u
                            j += 6
                        }
                        val sc = sum / sqrt(e)
                        if (sc > bs) { bs = sc; best = cc.toDouble() }
                        cand += 3
                    }
                    c = best
                }
                d.gPos[s] = c
                d.gAge[s] = 0
                d.gOn[s] = true
                d.gSlot = prev
                d.gNext = h
            }
            d.gNext--
            var l = 0f
            var r = 0f
            for (k in 0..1) {
                if (!d.gOn[k]) continue
                val age = d.gAge[k]
                val sp = wrapLoop(d, d.gPos[k] + age * speed)
                if (sp >= 0 && sp < last) {
                    val i0 = sp.toInt()
                    val fr = (sp - i0).toFloat()
                    val a = i0 * ch
                    val b = a + ch
                    val w = win[age]
                    val l0 = data[a] / 32768f
                    val l1 = data[b] / 32768f
                    l += (l0 + (l1 - l0) * fr) * w
                    r += if (ch == 2) (data[a + 1] / 32768f + (data[b + 1] / 32768f - data[a + 1] / 32768f) * fr) * w else (l0 + (l1 - l0) * fr) * w
                }
                d.gAge[k] = age + 1
                if (d.gAge[k] >= g) d.gOn[k] = false
            }
            bl[i] = l
            br[i] = r
            p += rate
            if (lOn && p >= lo) p -= (lo - li)
            if (p > last) {
                bl[i] = 0f; br[i] = 0f
            }
        }
        if (p >= last) {
            d.playing = false
            p = last.toDouble()
        }
        d.pos = p
    }

    // ---------------------------------------------------------------- Phrasen, Beat Jump, Loop Roll, Slip

    /** Schlag-Index einer Position (0 = erster Schlag des Rasters). */
    fun beatIndex(d: Deck, pos: Double): Double = (pos - d.grid) / d.beatFrames()

    /**
     * Takt-Eins und Phrasen-Anfänge (16 Takte) aus der Wellenform schätzen: Musik ändert sich
     * meist an Takt- und Phrasengrenzen (Drop, Break, neue Spur) – dort, wo die Energie im Mittel
     * am stärksten springt, liegt die Eins bzw. der Phrasenanfang.
     */
    fun analyzePhrase(d: Deck) {
        val pk = d.peaks
        val lw = d.lows
        if (d.bpm <= 0f || pk.size < 64) { d.barOffset = 0; d.phraseOff = 0; return }
        val bf = d.beatFrames()
        val beats = ((d.len - d.grid) / bf).toInt()
        if (beats < 16) { d.barOffset = 0; d.phraseOff = 0; return }
        // Energie (Pegel + Bass) je Schlag
        val e = DoubleArray(beats)
        for (b in 0 until beats) {
            val f0 = ((d.grid + b * bf) / BIN).toInt()
            val f1 = ((d.grid + (b + 1) * bf) / BIN).toInt().coerceAtMost(pk.size)
            var s = 0.0
            var c = 0
            for (i in max(0, f0) until f1) { s += (pk[i].toInt() and 0xFF) + 0.7 * (lw[i].toInt() and 0xFF) * (pk[i].toInt() and 0xFF) / 255.0; c++ }
            e[b] = if (c > 0) s / c else 0.0
        }
        fun mean(a: DoubleArray, from: Int, n: Int): Double { var s = 0.0; var c = 0; for (i in from until from + n) if (i in a.indices) { s += a[i]; c++ }; return if (c > 0) s / c else 0.0 }
        // Takt-Eins: Schlag-Versatz mit der größten Änderung zwischen aufeinanderfolgenden Takten
        var bestOff = 0
        var bs = -1.0
        for (o in 0..3) {
            var sc = 0.0
            var b = o + 4
            while (b + 4 <= beats) { sc += abs(mean(e, b, 4) - mean(e, b - 4, 4)); b += 4 }
            // der erste Schlag zählt etwas mehr (Kick auf der Eins)
            b = o
            while (b < beats) { sc += 0.15 * e[b]; b += 4 }
            if (sc > bs) { bs = sc; bestOff = o }
        }
        d.barOffset = bestOff
        // Phrasen: Takt-Energie, dann Versatz (0..15) mit den stärksten 16-Takt-Wechseln (8 halb gewichtet)
        val bars = (beats - bestOff) / 4
        if (bars < 16) { d.phraseOff = 0; return }
        val be = DoubleArray(bars) { mean(e, bestOff + it * 4, 4) }
        var po = 0
        var ps = -1.0
        for (o in 0 until 16) {
            var sc = 0.0
            var j = o
            while (j < bars) {
                if (j >= 4) sc += abs(mean(be, j, 4) - mean(be, j - 4, 4))
                val h = j + 8
                if (h in 4 until bars) sc += 0.5 * abs(mean(be, h, 4) - mean(be, h - 4, 4))
                j += 16
            }
            if (sc > ps) { ps = sc; po = o }
        }
        d.phraseOff = po
    }

    /** Takt im Track (0 = erster ganzer Takt) an einer Position. */
    fun barAt(d: Deck, pos: Double): Double = (beatIndex(d, pos) - d.barOffset) / 4.0

    /** Phrase: Takt innerhalb der 16er-Phrase (0..15) und Schläge bis zur nächsten Phrase. */
    fun phraseInfo(d: Deck, pos: Double): Pair<Int, Double> {
        val bar = barAt(d, pos) - d.phraseOff
        val inP = Math.floorMod(floor(bar).toLong(), 16L).toInt()
        val toNext = (16.0 - (bar - floor(bar / 16.0) * 16.0)) * 4.0
        return inP to toNext
    }

    /** Beat Jump um [bars] Takte (negativ = zurück), phasengenau; ein laufender Loop springt mit. */
    fun beatJump(d: Deck, bars: Float) {
        if (!d.loaded()) return
        val bf = d.beatFrames() * 4.0 * bars
        val np = (d.pos + bf).coerceIn(0.0, (d.len - 2).toDouble())
        val moved = np - d.pos
        d.seekTo = np
        if (d.loopOn) { d.loopIn += moved; d.loopOut += moved }
        if (d.slipTrack) d.slipPos = (d.slipPos + moved).coerceIn(0.0, (d.len - 2).toDouble())
    }

    /** Loop Roll starten (solange gedrückt): Loop über [beats] Schläge ab dem letzten Rasterpunkt, Slip läuft mit. */
    fun rollStart(d: Deck, beats: Float) {
        if (!d.loaded() || d.bpm <= 0f) return
        if (d.rollBeats <= 0f) {
            d.rollSaveOn = d.loopOn
            d.rollSaveIn = d.loopIn
            d.rollSaveOut = d.loopOut
            if (!d.slipTrack) { d.slipPos = d.pos; d.slipTrack = true }
        }
        d.rollBeats = beats
        val len = d.beatFrames() * beats
        val start = d.grid + floor((d.pos - d.grid) / len) * len
        d.loopIn = max(0.0, start)
        d.loopOut = d.loopIn + len
        d.loopOn = true
    }

    /** Loop Roll loslassen: weiter dort, wo der Track ohne Roll jetzt wäre. */
    fun rollEnd(d: Deck) {
        if (d.rollBeats <= 0f) return
        d.rollBeats = 0f
        d.loopOn = d.rollSaveOn
        d.loopIn = d.rollSaveIn
        d.loopOut = d.rollSaveOut
        if (d.slipTrack && !d.scratching) {
            d.slipTrack = false
            d.seekTo = d.slipPos
        }
    }

    /** Scratchen beginnt: mit SLIP läuft der Track im Hintergrund weiter. */
    fun scratchStart(d: Deck) {
        if (d.slipOn && d.playing && !d.slipTrack) { d.slipPos = d.pos; d.slipTrack = true }
        d.scratchTarget = d.pos
        d.scratching = true
    }

    fun scratchEnd(d: Deck) {
        d.scratching = false
        if (d.slipOn && d.slipTrack && d.rollBeats <= 0f) {
            d.slipTrack = false
            d.seekTo = d.slipPos
        }
    }

    // ---------------------------------------------------------------- DJ-Effekte

    fun echoOut(d: Deck) {
        if (!d.loaded()) return
        if (d.echoOn && d.echoCut == 0) { d.echoOn = false; d.echoDry = 1f; return }
        d.echoReq = true
    }

    fun brake(d: Deck, backspin: Boolean) {
        if (!d.loaded() || !d.playing) return
        d.stopFxReq = if (backspin) 2 else 1
    }

    fun censorOn(d: Deck, on: Boolean) {
        if (!d.loaded()) return
        if (on) {
            if (!d.slipTrack && d.playing) { d.slipPos = d.pos; d.slipTrack = true }
            d.censor = true
        } else {
            d.censor = false
            if (d.slipTrack && d.rollBeats <= 0f && !d.scratching) { d.slipTrack = false; d.seekTo = d.slipPos }
        }
    }

    /** Sweep starten bzw. (nochmal) beenden: BUILD-Ende = Filter schlagartig auf (der „Drop“). */
    fun toggleSweep(d: Deck, mode: Int) {
        if (!d.loaded()) return
        if (d.sweepMode == mode) { d.sweepMode = 0; d.filter = 0.5f; return }
        d.sweepMode = mode
        d.sweepStart = Engine.outFrameNow()
    }

    fun stopAllFx(d: Deck) {
        d.sweepMode = 0
        d.filter = 0.5f
        d.echoOn = false
        d.echoDry = 1f
        d.censor = false
        d.stopFx = 0
        d.fxAmt = 0f
        d.fxOn = false
    }

    /**
     * Remix-Deck: Ausschnitt des laufenden Tracks auf ein freies Pad – als Loop ([bars] Takte ab
     * dem aktuellen Takt bzw. der aktive Loop) synchron ins Pad-Raster gewarpt, oder als ⚡ One-Shot.
     * Liefert das Pad (oder -1).
     */
    fun grabToPad(d: Deck, bars: Float, oneShot: Boolean): Int {
        val data = d.data ?: return -1
        if (!d.loaded() || d.bpm <= 0f) return -1
        val p = (0 until 12).firstOrNull { !Engine.hasLoop(it) } ?: return -1
        val bf = d.beatFrames()
        val start: Double
        val lenF: Double
        val barsOut: Float
        if (d.loopOn && !oneShot) {
            start = d.loopIn
            lenF = d.loopOut - d.loopIn
            barsOut = (lenF / (bf * 4)).toFloat()
        } else if (oneShot) {
            start = snapBeat(d, d.pos - bf * 0.25)
            lenF = bf
            barsOut = 0.25f
        } else {
            val bar = floor(barAt(d, d.pos))
            start = d.grid + (bar * 4 + d.barOffset) * bf
            lenF = bf * 4 * bars
            barsOut = bars
        }
        val er = Engine.sampleRate
        val ch = d.ch
        val len = d.len
        fun mono(pos: Double): Float {
            val i0 = pos.toInt()
            if (i0 < 0 || i0 + 1 >= len) return 0f
            val fr = (pos - i0).toFloat()
            val a = if (ch == 2) (data[2 * i0] + data[2 * i0 + 1]) / 65536f else data[i0] / 32768f
            val b = if (ch == 2) (data[2 * i0 + 2] + data[2 * i0 + 3]) / 65536f else data[i0 + 1] / 32768f
            return a + (b - a) * fr
        }
        val step = d.srcRate.toDouble() / er
        val outN = (lenF / step).toInt()
        if (outN < 64) return -1
        val clip = FloatArray(outN) { mono(start + it * step) }
        val preN = (er / 20)
        val pre = FloatArray(preN) { mono(start - (preN - it) * step) }
        Engine.padOneShot[p] = oneShot
        if (oneShot) {
            Engine.importLoop(p, clip)
        } else {
            Engine.importClip(p, clip, Warp.Info(d.bpm, barsOut, "DJ"), pre)
            Engine.padSyncStart[p] = true
        }
        Engine.setPadName(p, d.title.take(18) + if (oneShot) " ⚡" else " ${if (barsOut >= 1f) barsOut.toInt().toString() else barsOut.toString()}T")
        return p
    }

    /** Tonart eines geladenen Decks im Hintergrund erkennen. */
    fun detectKeyAsync(d: Deck, then: (() -> Unit)? = null) {
        val gen = ++d.keyGen
        val data = d.data ?: return
        val ch = d.ch
        val len = d.len
        val rate = d.srcRate
        Thread {
            try {
                val (k, c) = Keys.detect(data, ch, len, rate)
                if (gen == d.keyGen) {
                    d.key = k
                    d.keyConf = c
                    then?.invoke()
                }
            } catch (ignored: Throwable) {}
        }.start()
    }

    /** Schlag-Phase (0..1) eines Decks an einer Position. */
    fun beatPhase(d: Deck, pos: Double): Double {
        val bf = d.beatFrames()
        val r = (pos - d.grid) / bf
        return r - floor(r)
    }

    /** Phase des Decks auf den Master-Schlag ziehen (kleinster Weg). */
    private fun alignPhase(d: Deck, w: Long) {
        val mp = Engine.beatPhaseAt(w)
        if (mp < 0) return
        // hörbare Position: Stems-Filter verzögert um DeckStems.LAT Ausgabe-Samples
        val heard = d.pos - DeckStems.LAT * (d.srcRate.toDouble() / Engine.sampleRate) * d.ratio
        val dp = beatPhase(d, heard)
        var diff = mp - dp
        if (diff > 0.5) diff -= 1.0
        if (diff < -0.5) diff += 1.0
        d.pos = (d.pos + diff * d.beatFrames()).coerceAtLeast(0.0)
    }

    // ---------------------------------------------------------------- Steuerung (UI)

    fun togglePlay(d: Deck) {
        if (!d.loaded()) return
        d.gReset = true
        d.stopFx = 0
        d.stopFxReq = 0
        if (!d.playing && d.sync) d.syncReq = true
        d.playing = !d.playing
    }

    /** CUE: im Stand Cue-Punkt setzen, beim Spielen zurück zum Cue-Punkt und anhalten. */
    fun cuePress(d: Deck) {
        if (!d.loaded()) return
        if (d.playing) {
            d.playing = false
            d.seekTo = d.cuePoint
        } else {
            d.cuePoint = snapBeat(d, d.pos)
            d.seekTo = d.cuePoint
        }
    }

    fun hotCue(d: Deck, k: Int, clear: Boolean) {
        if (!d.loaded()) return
        if (clear) {
            d.hot[k] = -1.0
            return
        }
        if (d.hot[k] < 0) d.hot[k] = snapBeat(d, d.pos)
        else {
            d.seekTo = d.hot[k]
            if (d.sync && d.playing) d.syncReq = true
        }
    }

    /** Auto-Loop über [beats] Schläge ab dem nächstgelegenen Schlag – nochmal = aus. */
    fun toggleLoop(d: Deck) {
        if (!d.loaded()) return
        if (d.loopOn) {
            d.loopOn = false
            return
        }
        val bf = d.beatFrames()
        val start = snapBeat(d, d.pos - bf * 0.25)
        d.loopIn = start
        d.loopOut = start + bf * d.loopBeats
        d.loopOn = true
    }

    fun setLoopBeats(d: Deck, beats: Float) {
        d.loopBeats = beats.coerceIn(0.125f, 32f)
        if (d.loopOn) d.loopOut = d.loopIn + d.beatFrames() * d.loopBeats
    }

    fun snapBeat(d: Deck, pos: Double): Double {
        if (d.bpm <= 0f) return pos
        val bf = d.beatFrames()
        val k = Math.round((pos - d.grid) / bf)
        return max(0.0, d.grid + k * bf)
    }

    fun toggleSync(d: Deck) {
        if (!d.loaded() || d.bpm <= 0f) return
        if (d.sync) {
            // Tempo beibehalten: Pitch-Fader auf das aktuelle Verhältnis stellen
            d.pitch = ((d.ratio - 1.0) / d.range).toFloat().coerceIn(-1f, 1f)
            d.sync = false
        } else {
            d.syncReq = true
        }
    }

    // ---------------------------------------------------------------- Laden & Analyse

    class Track(val data: ShortArray, val ch: Int, val len: Int, val rate: Int)

    /** Dekodiert eine Audiodatei (MP3, WAV, M4A, OGG …) als 16-Bit-Stereo in Originalrate. */
    fun decode(context: Context, uri: Uri): Track {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("Keine Audiospur gefunden")
            ex.selectTrack(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val outCh = if (inCh >= 2) 2 else 1
            val maxFrames = rate.toLong() * MAX_SECONDS
            var cap = if (durUs > 0) min(maxFrames, durUs * rate / 1_000_000L + rate).toInt() else rate * 60
            var buf = ShortArray(cap * outCh)
            var frames = 0
            var floatPcm = false
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    val nr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nr != rate && frames == 0) rate = nr
                    inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val b = ob.slice().order(ByteOrder.LITTLE_ENDIAN)
                        val fb = if (floatPcm) b.asFloatBuffer() else null
                        val sb = if (!floatPcm) b.asShortBuffer() else null
                        val nf = (if (floatPcm) fb!!.remaining() else sb!!.remaining()) / max(1, inCh)
                        for (f in 0 until nf) {
                            if (frames >= maxFrames) break
                            if (frames >= cap) {
                                cap = min(maxFrames.toInt(), cap + cap / 2 + rate)
                                buf = buf.copyOf(cap * outCh)
                            }
                            for (k in 0 until outCh) {
                                val chn = min(k, inCh - 1)
                                val v: Short = if (floatPcm) (fb!!.get(f * inCh + chn).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                else sb!!.get(f * inCh + chn)
                                buf[frames * outCh + k] = v
                            }
                            frames++
                        }
                        if (frames >= maxFrames) outputDone = true
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    if (durUs > 0) LoadProgress.report(info.presentationTimeUs.toFloat() / durUs)
                }
            }
            if (frames < 2) throw IllegalStateException("Datei enthält kein Audio")
            if (buf.size > frames * outCh + rate * outCh) buf = buf.copyOf(frames * outCh)
            return Track(buf, outCh, frames, rate)
        } finally {
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
            ex.release()
        }
    }

    /**
     * Für die Analyse: dekodiert sparsam als Mono, über 32 kHz auf die halbe Rate gemittelt
     * (10 Minuten ≈ 29 MB statt 115 MB).
     */
    fun decodeMono(context: Context, uri: Uri, maxSeconds: Int): Track {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("Keine Audiospur gefunden")
            ex.selectTrack(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var dec = if (rate > 32000) 2 else 1
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val maxFrames = rate.toLong() * maxSeconds / dec
            var cap = (if (durUs > 0) min(maxFrames, durUs * rate / 1_000_000L / dec + rate) else rate * 60L / dec).toInt()
            var buf = ShortArray(cap)
            var frames = 0
            var acc = 0f
            var accN = 0
            var floatPcm = false
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var started = false
            while (!outputDone) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    val nr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nr != rate && !started) { rate = nr; dec = if (rate > 32000) 2 else 1 }
                    inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        started = true
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val b = ob.slice().order(ByteOrder.LITTLE_ENDIAN)
                        val fb = if (floatPcm) b.asFloatBuffer() else null
                        val sb = if (!floatPcm) b.asShortBuffer() else null
                        val nf = (if (floatPcm) fb!!.remaining() else sb!!.remaining()) / max(1, inCh)
                        for (f in 0 until nf) {
                            var v = 0f
                            for (k in 0 until min(2, inCh)) v += if (floatPcm) fb!!.get(f * inCh + k) else sb!!.get(f * inCh + k) / 32768f
                            acc += v / min(2, inCh)
                            accN++
                            if (accN < dec) continue
                            if (frames >= maxFrames) break
                            if (frames >= cap) { cap = min(maxFrames.toInt(), cap + cap / 2 + rate); buf = buf.copyOf(cap) }
                            buf[frames++] = ((acc / accN).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            acc = 0f
                            accN = 0
                        }
                        if (frames >= maxFrames) outputDone = true
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    if (durUs > 0) LoadProgress.report(info.presentationTimeUs.toFloat() / durUs)
                }
            }
            if (frames < 2) throw IllegalStateException("Datei enthält kein Audio")
            return Track(buf.copyOf(frames), 1, frames, rate / dec)
        } finally {
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
            ex.release()
        }
    }

    /** Mono-Float (z. B. Pad, Bibliothek, Aufnahme) als Track. */
    fun fromMono(x: FloatArray, rate: Int): Track {
        val s = ShortArray(x.size)
        for (i in x.indices) s[i] = (x[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        return Track(s, 1, x.size, rate)
    }

    /** Track ins Deck laden: Wellenform, Tempo und Beat-Raster bestimmen. */
    fun load(d: Deck, t: Track, title: String, nameBpm: Float?) {
        val w = waveOf(t.data, t.ch, t.len, t.rate)
        val det = detectBpm(w.onset, w.fps)
        val bpm = nameBpm ?: det.first
        val grid = findGrid(w.onset, w.fps, bpm) * (t.rate / w.fps)
        d.playing = false
        d.sync = false
        d.loopOn = false
        d.data = null
        d.ch = t.ch
        d.len = t.len
        d.srcRate = t.rate
        d.peaks = w.peaks
        d.lows = w.lows
        d.mids = w.mids
        d.highs = w.highs
        d.title = title
        d.bpm = bpm
        d.grid = grid
        d.cuePoint = grid
        for (k in d.hot.indices) d.hot[k] = -1.0
        d.pos = grid
        d.seekTo = grid
        d.pitch = 0f
        d.libId = null
        d.colId = null
        d.key = -1
        d.keyConf = 0f
        d.keyShift = 0
        d.gReset = true
        d.rollBeats = 0f
        d.slipTrack = false
        d.lufs = w.lufs
        d.agFactor = agOf(w.lufs)
        d.stemMask = null
        d.stemGen++
        d.stemProg = -1f
        for (s in 0..3) d.stemGain[s] = 1f
        d.data = t.data
        analyzePhrase(d)
        detectKeyAsync(d)
        if (d === decks[0] || d === decks[1]) stemsAsync(d)
    }

    /** Stem-Karte im Hintergrund berechnen (beim Laden automatisch). */
    fun stemsAsync(d: Deck) {
        val gen = ++d.stemGen
        val data = d.data ?: return
        val ch = d.ch
        val len = d.len
        val rate = d.srcRate
        d.stemMask = null
        d.stemProg = 0f
        Thread {
            try {
                val m = DeckStems.analyze(data, ch, len, rate, { gen != d.stemGen }) { p -> if (gen == d.stemGen) d.stemProg = p }
                if (gen == d.stemGen && m != null) {
                    d.stemMask = m
                    d.stemProg = -1f
                }
            } catch (t: Throwable) {
                if (gen == d.stemGen) d.stemProg = -1f
            }
        }.start()
    }

    // ---------------------------------------------------------------- Auto-Gain

    /** Auto-Gain: alle Tracks auf gleiche Lautheit (Ziel [targetLufs]). */
    @Volatile var autoGain = true
    @Volatile var targetLufs = -10f
    /** Wellenform nach Frequenzen färben (Bass rot, Mitten grün, Höhen blau). */
    @Volatile var colorWave = true

    /** Verstärkung für eine gemessene Lautheit (begrenzt auf −12 … +9 dB). */
    fun agOf(lufs: Float): Float {
        if (lufs.isNaN() || lufs < -60f) return 1f
        val db = (targetLufs - lufs).coerceIn(-12f, 9f)
        return Math.pow(10.0, db / 20.0).toFloat()
    }

    fun changeTargetLufs(v: Float) {
        targetLufs = v
        for (d in decks) d.agFactor = agOf(d.lufs)
    }

    class Wave(val peaks: ByteArray, val lows: ByteArray, val env: FloatArray, val fps: Double, val onset: FloatArray,
               val mids: ByteArray = ByteArray(0), val highs: ByteArray = ByteArray(0), val lufs: Float = Float.NaN)

    /**
     * Wellenform-Übersicht (Pegel + Anteile Bass/Mitten/Höhen je [BIN] Frames), Hüllkurve für
     * Tempo/Raster und Lautheit (wie LUFS: Höhen leicht betont, Bass unter ~60 Hz gedämpft,
     * 400-ms-Blöcke, leise Stellen ausgeblendet).
     */
    fun waveOf(data: ShortArray, ch: Int, len: Int, rate: Int): Wave {
        val bins = (len + BIN - 1) / BIN
        val pk = ByteArray(bins)
        val lw = ByteArray(bins)
        val md = ByteArray(bins)
        val hg = ByteArray(bins)
        var lpState = 0f
        val a = (1.0 - exp(-2 * PI * 180.0 / rate)).toFloat()
        val a25 = (1.0 - exp(-2 * PI * 2500.0 / rate)).toFloat()
        var lp25 = 0f
        val hop = max(1, rate / 200)
        val env = FloatArray(len / hop + 1)
        val envLo = FloatArray(len / hop + 1)
        val envHi = FloatArray(len / hop + 1)
        var hiLp = 0f
        val a4 = (1.0 - exp(-2 * PI * 4000.0 / rate)).toFloat()
        // Lautheit: Hochpass 60 Hz + Höhen-Anhebung (grob wie K-Filter)
        val a60 = (1.0 - exp(-2 * PI * 60.0 / rate)).toFloat()
        var lp60 = 0f
        val a15 = (1.0 - exp(-2 * PI * 1500.0 / rate)).toFloat()
        var lp15 = 0f
        val blk = (rate * 0.4).toInt().coerceAtLeast(1)
        val blocks = DoubleArray(len / blk + 1)
        var maxPk = 1e-6f
        val pkF = FloatArray(bins)
        for (bi in 0 until bins) {
            var m = 0f
            var sLow = 0f
            var sMid = 0f
            var sHigh = 0f
            val f0 = bi * BIN
            val f1 = min(len, f0 + BIN)
            for (f in f0 until f1) {
                val v = if (ch == 2) (data[2 * f] + data[2 * f + 1]) / 65536f else data[f] / 32768f
                lpState += a * (v - lpState)
                lp25 += a25 * (v - lp25)
                val av = abs(v)
                if (av > m) m = av
                sLow += abs(lpState)
                sMid += abs(lp25 - lpState)
                sHigh += abs(v - lp25)
                env[f / hop] += v * v
                envLo[f / hop] += lpState * lpState
                hiLp += a4 * (v - hiLp)
                val hv = v - hiLp
                envHi[f / hop] += hv * hv
                lp60 += a60 * (v - lp60)
                val x1 = v - lp60
                lp15 += a15 * (x1 - lp15)
                val kw = x1 + 0.6f * (x1 - lp15)
                blocks[f / blk] += (kw * kw).toDouble()
            }
            pkF[bi] = m
            // Höhen werden optisch etwas verstärkt (sie haben wenig Energie, sind aber gut zu sehen)
            val tot = sLow + sMid + 1.6f * sHigh
            if (tot > 1e-6f) {
                lw[bi] = (sLow / tot * 255f).toInt().coerceIn(0, 255).toByte()
                md[bi] = (sMid / tot * 255f).toInt().coerceIn(0, 255).toByte()
                hg[bi] = (1.6f * sHigh / tot * 255f).toInt().coerceIn(0, 255).toByte()
            }
            if (m > maxPk) maxPk = m
        }
        for (bi in 0 until bins) pk[bi] = (min(1f, pkF[bi] / maxPk) * 255f).toInt().toByte()
        // Lautheit: Blöcke über −70 und relativ −10 dB zum Mittel (BS.1770-artig)
        var lufs = Float.NaN
        run {
            val nb = blocks.size - 1
            if (nb >= 1) {
                val l = DoubleArray(nb) { blocks[it] / blk }
                fun lu(x: Double) = -0.691 + 10 * Math.log10(max(1e-12, x))
                var s1 = 0.0; var c1 = 0
                for (x in l) if (lu(x) > -70) { s1 += x; c1++ }
                if (c1 > 0) {
                    val rel = lu(s1 / c1) - 10
                    var s2 = 0.0; var c2 = 0
                    for (x in l) if (lu(x) > rel) { s2 += x; c2++ }
                    if (c2 > 0) lufs = lu(s2 / c2).toFloat()
                }
            }
        }
        // Bass-Anteil für ältere Anzeigen: wie bisher (Anteil an der Summe)
        return Wave(pk, lw, env, rate.toDouble() / hop, onsetEnv(envLo, envHi, rate.toDouble() / hop), md, hg, lufs)
    }

    /** Tempo und Beat-Raster neu erkennen (Frames). [keepBpm] = nur das Raster neu legen. */
    fun reanalyze(d: Deck, keepBpm: Boolean) {
        val data = d.data ?: return
        val w = waveOf(data, d.ch, d.len, d.srcRate)
        val hop = d.srcRate / w.fps
        val bpm = if (keepBpm && d.bpm > 0f) d.bpm else detectBpm(w.onset, w.fps).first
        d.bpm = bpm
        d.grid = findGrid(w.onset, w.fps, bpm) * hop
        analyzePhrase(d)
    }

    /**
     * Ganzen Track auf ein neues Tempo dehnen – ohne Tonhöhenänderung (WSOLA, Stereo).
     * Raster, Cue, Hot Cues, Loop und Position werden mitgerechnet. Läuft im Hintergrund;
     * das Deck spielt währenddessen weiter.
     */
    fun warpTo(d: Deck, newBpm: Float, progress: (Float) -> Unit) {
        val data = d.data ?: return
        val ch = d.ch
        val len = d.len
        val sr = d.srcRate
        if (d.bpm <= 0f || newBpm <= 0f) return
        val ratio = d.bpm.toDouble() / newBpm // Ausgabe-Länge / Eingabe-Länge
        val outLen = (len * ratio).toInt()
        val n = 2048 * sr / 48000 / 2 * 2
        val ho = n / 2
        val tol = n / 4
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val out = ShortArray(outLen * ch)
        val acc = Array(ch) { FloatArray(n) }
        fun mono(i: Int): Float = if (i < 0 || i >= len) 0f else if (ch == 2) (data[2 * i] + data[2 * i + 1]) * 0.5f else data[i].toFloat()
        var prev = 0
        var k = 0
        while (k * ho < outLen) {
            val nominal = (k * ho / ratio).toInt()
            var chosen = nominal
            if (k > 0) {
                // Stelle suchen, die am besten an das vorige Stück anschließt
                val nat = prev + ho
                var best = Double.NEGATIVE_INFINITY
                var cand = max(0, nominal - tol)
                val last = min(len - n, nominal + tol)
                while (cand <= last) {
                    var c = 0.0
                    var j = 0
                    while (j < ho) {
                        c += mono(nat + j) * mono(cand + j)
                        j += 12
                    }
                    if (c > best) { best = c; chosen = cand }
                    cand += 3
                }
            }
            chosen = chosen.coerceIn(0, max(0, len - 1))
            for (c in 0 until ch) {
                val a = acc[c]
                for (j in 0 until n) {
                    val i = chosen + j
                    val v = if (i < len) data[i * ch + c].toFloat() else 0f
                    a[j] += v * win[j]
                }
                val o0 = k * ho
                for (j in 0 until ho) {
                    val oi = o0 + j
                    if (oi >= outLen) break
                    out[oi * ch + c] = a[j].toInt().coerceIn(-32768, 32767).toShort()
                }
                System.arraycopy(a, ho, a, 0, n - ho)
                java.util.Arrays.fill(a, n - ho, n, 0f)
            }
            prev = chosen
            k++
            if (k % 400 == 0) progress(min(1f, k * ho.toFloat() / outLen))
        }
        val w = waveOf(out, ch, outLen, sr)
        // Umschalten: Positionen umrechnen, dann Daten tauschen (der Mischer liest die Länge sicher)
        val r = ratio
        d.peaks = w.peaks
        d.lows = w.lows
        d.mids = w.mids
        d.highs = w.highs
        d.grid = d.grid * r
        d.cuePoint = d.cuePoint * r
        for (h in d.hot.indices) if (d.hot[h] >= 0) d.hot[h] = d.hot[h] * r
        d.loopIn = d.loopIn * r
        d.loopOut = d.loopOut * r
        val newPos = d.pos * r
        d.bpm = newBpm
        if (outLen < d.len) {
            d.len = outLen
            d.data = out
        } else {
            d.data = out
            d.len = outLen
        }
        d.seekTo = newPos
        d.pitch = 0f
        d.stemMask?.let { d.stemMask = it.scaled(r) }
        if (d.stemProg >= 0f) stemsAsync(d) // Analyse lief noch auf dem alten Audio
        analyzePhrase(d)
        progress(1f)
    }

    /**
     * Onset-Kurve für Tempo und Raster: Anstiege der Energie im Bassbereich (Kick) und in den
     * Höhen (Hi-Hats), jeweils logarithmisch und normiert; gleitender Mittelwert (0,5 s) abgezogen.
     * Flächige Synths, Pads und Arpeggios im Mittenbereich stören so kaum noch.
     */
    fun onsetEnv(lo: FloatArray, hi: FloatArray, fps: Double): FloatArray {
        val n = lo.size
        val out = FloatArray(n)
        if (n < 4) return out
        val d = FloatArray(n)
        for ((env, w) in arrayOf(lo to 1f, hi to 0.5f)) {
            var mean = 0.0
            for (v in env) mean += v
            mean /= n
            val sc = 1e4 / (mean + 1e-12)
            var prev = ln(1.0 + sc * env[0])
            var sum = 0.0
            var sq = 0.0
            for (k in 0 until n) {
                val e = ln(1.0 + sc * env[k])
                val v = max(0.0, e - prev)
                prev = e
                d[k] = v.toFloat()
                sum += v
                sq += v * v
            }
            val m = sum / n
            val sd = sqrt(max(1e-18, sq / n - m * m)).toFloat()
            for (k in 0 until n) out[k] += w * d[k] / sd
        }
        // gleitenden Mittelwert abziehen
        val win = max(3, (fps * 0.5).toInt())
        val res = FloatArray(n)
        var acc = 0.0
        val half = win / 2
        var lo0 = 0
        var hi0 = -1
        for (k in 0 until n) {
            val a = k - half
            val b = min(n - 1, k + half)
            while (hi0 < b) { hi0++; acc += out[hi0] }
            while (lo0 < a) { acc -= out[lo0]; lo0++ }
            val c = acc / win
            res[k] = max(0.0, out[k] - c).toFloat()
        }
        return res
    }

    /**
     * Tempo-Erkennung: Autokorrelation der Onset-Kurve, bewertet mit einem Kamm über
     * 1–4 Schläge plus Achtel-Unterteilung. Dadurch rastet die Erkennung nicht mehr auf
     * punktierte Synth-Figuren (Faktor 2/3 bzw. 4/3) ein. Zwischen halbem/doppeltem Tempo
     * entscheidet eine sanfte Gewichtung um 122 BPM (×2 / ÷2 korrigiert im Editor).
     */
    fun detectBpm(on: FloatArray, fps: Double): Pair<Float, Float> {
        val n = on.size
        if (n < fps * 4) return 120f to 0f
        val span = min(n, (fps * 120).toInt())
        val s0 = (n - span) / 2
        var mean = 0.0
        for (k in s0 until s0 + span) mean += on[k]
        mean /= span
        val seg = FloatArray(span) { (on[s0 + it] - mean).toFloat() }
        val maxLag = min(span - 2, (fps * 4.2).toInt())
        val r = DoubleArray(maxLag + 2)
        for (lag in 0..maxLag + 1) {
            if (lag >= span) break
            var s = 0.0
            var k = 0
            val lim = span - lag
            while (k < lim) { s += seg[k] * seg[k + lag]; k++ }
            r[lag] = s / lim
        }
        val r0 = if (r[0] > 0) r[0] else 1.0
        for (i in r.indices) r[i] /= r0
        fun rr(l: Double): Double {
            if (l < 0 || l >= maxLag) return 0.0
            val i = l.toInt()
            val f = l - i
            return r[i] * (1 - f) + r[i + 1] * f
        }
        fun comb(t: Double): Double {
            val l = fps * 60.0 / t
            return rr(l) + 0.85 * rr(2 * l) + 0.6 * rr(3 * l) + 0.7 * rr(4 * l)
        }
        var bestT = 120.0
        var bestS = Double.NEGATIVE_INFINITY
        var t = 60.0
        while (t <= 200.0) {
            val l = fps * 60.0 / t
            val sc = comb(t) + 1.2 * rr(l / 2)
            val lg = ln(t / 122.0) / ln(2.0) / 0.55
            val pr = exp(-0.5 * lg * lg)
            if (sc * pr > bestS) { bestS = sc * pr; bestT = t }
            t += 0.1
        }
        // fein nachjustieren
        var fineT = bestT
        var fineS = Double.NEGATIVE_INFINITY
        var ft = bestT - 0.3
        while (ft <= bestT + 0.3) {
            val sc = comb(ft)
            if (sc > fineS) { fineS = sc; fineT = ft }
            ft += 0.01
        }
        val conf = (bestS / 3.15).toFloat().coerceIn(0f, 1f)
        return (Math.round(fineT * 100).toFloat() / 100f) to conf
    }

    /** Beat-Raster: Phase mit der meisten Onset-Energie (in Hüllkurven-Schritten). */
    fun findGrid(env: FloatArray, fps: Double, bpm: Float): Double {
        val n = env.size
        if (n < 8 || bpm <= 0f) return 0.0
        val period = fps * 60.0 / bpm
        val on = env
        val steps = max(8, period.toInt() * 2)
        var bestPh = 0.0
        var bs = -1.0
        val limit = min(n, (fps * 60).toInt())
        for (si in 0 until steps) {
            val ph = period * si / steps
            var s = 0.0
            var t = ph
            while (t < limit) {
                val k = t.toInt()
                s += on[k] + 0.5 * (if (k + 1 < n) on[k + 1] else 0f)
                t += period
            }
            if (s > bs) {
                bs = s
                bestPh = ph
            }
        }
        return bestPh
    }

    /**
     * Tempo und erster Schlag eines Mono-Clips (Pads, MP3-Ausschnitte):
     * Onset-Kamm wie im DJ-Modus, danach Feinabgleich über den ganzen Clip –
     * je länger der Clip, desto genauer (bis ~0,01 BPM). Liefert BPM und den Frame des ersten Schlags.
     */
    fun analyzeClip(x: FloatArray, sr: Int): Pair<Float, Int> {
        val hop = max(1, sr / 200)
        val n = x.size / hop + 1
        val lo = FloatArray(n)
        val hi = FloatArray(n)
        val aL = (1.0 - exp(-2 * PI * 180.0 / sr)).toFloat()
        val aH = (1.0 - exp(-2 * PI * 4000.0 / sr)).toFloat()
        var l = 0f
        var h = 0f
        for (i in x.indices) {
            val v = x[i]
            l += aL * (v - l)
            h += aH * (v - h)
            val hv = v - h
            lo[i / hop] += l * l
            hi[i / hop] += hv * hv
        }
        val fps = sr.toDouble() / hop
        val on = onsetEnv(lo, hi, fps)
        var bpm = detectBpm(on, fps).first.toDouble()
        // Feinabgleich: Schlag-Kamm mit bester Phase über den ganzen Clip
        if (on.size > fps * 6) {
            var best = bpm
            var bestS = Double.NEGATIVE_INFINITY
            var b = bpm - 0.6
            while (b <= bpm + 0.6) {
                val period = fps * 60.0 / b
                val steps = max(8, (period / 2).toInt())
                var bs = 0.0
                for (si in 0 until steps) {
                    var t = period * si / steps
                    var sum = 0.0
                    while (t < on.size - 1) {
                        val k = t.toInt()
                        val f = t - k
                        sum += on[k] * (1 - f) + on[k + 1] * f
                        t += period
                    }
                    if (sum > bs) bs = sum
                }
                if (bs > bestS) { bestS = bs; best = b }
                b += 0.01
            }
            bpm = best
        }
        val bp = (Math.round(bpm * 100).toFloat() / 100f)
        // erster Schlag, auf dem wirklich etwas passiert (Stille/Intro davor überspringen)
        val period = fps * 60.0 / bp
        var t = findGrid(on, fps, bp)
        fun hit(tt: Double): Float {
            val k = tt.toInt()
            var m = 0f
            for (j in max(0, k - 2)..min(on.size - 1, k + 2)) m = max(m, on[j])
            return m
        }
        var sum = 0.0
        var cnt = 0
        var tt = t
        while (tt < on.size - 2) { val v = hit(tt); if (v > 0f) { sum += v; cnt++ }; tt += period }
        val thr = if (cnt > 0) (sum / cnt * 0.35).toFloat() else 0f
        while (t + period < on.size - 2 && hit(t) < thr) t += period
        val first = (t * hop).toInt().coerceIn(0, max(0, x.size - 1))
        return bp to first
    }

    /** Zeit als m:ss. */
    fun fmt(sec: Double): String {
        val s = max(0, sec.toInt())
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Für die Deck-Effekte: kleine Auswahl gut passender Effekte. */
    val FX_CHOICES: IntArray by lazy {
        val want = arrayOf("DELAY", "TAPE ECHO", "SPACE ECHO", "REVERB", "FLANGER", "PHASER", "STUTTER", "LOOPER", "SLICER", "REVERSE", "CRUSH", "FILTER", "GRAIN DLY", "FREEZE", "TREMOLO")
        want.mapNotNull { n -> Fx.NAMES.indexOf(n).takeIf { it >= 0 } }.toIntArray()
    }
}
