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
        @Volatile var loading = false
        /** Eintrag der DJ-Bibliothek (für das Speichern geänderter Cue-Punkte). */
        @Volatile var libId: String? = null

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
        val hot = DoubleArray(4) { -1.0 }
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
        @Volatile var cue = false
        @Volatile var level = 0f
        /** Effektives Tempo-Verhältnis (für Anzeige). */
        @Volatile var ratio = 1.0

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
            renderDeck(d, bl, br, n, w, sr)
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
            val g = gainOf(d.gain)
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
            val amt = d.fxAmt
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
        val len = d.len
        val ch = d.ch
        val st = d.seekTo
        if (st >= 0) {
            d.pos = st.coerceIn(0.0, (len - 2).toDouble())
            d.seekTo = -1.0
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
        val rate = if (d.scratching) d.scratchVel else base * ratio * (1.0 + d.bend)
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
        val dp = beatPhase(d, d.pos)
        var diff = mp - dp
        if (diff > 0.5) diff -= 1.0
        if (diff < -0.5) diff += 1.0
        d.pos = (d.pos + diff * d.beatFrames()).coerceAtLeast(0.0)
    }

    // ---------------------------------------------------------------- Steuerung (UI)

    fun togglePlay(d: Deck) {
        if (!d.loaded()) return
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

    /** Mono-Float (z. B. Pad, Bibliothek, Aufnahme) als Track. */
    fun fromMono(x: FloatArray, rate: Int): Track {
        val s = ShortArray(x.size)
        for (i in x.indices) s[i] = (x[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        return Track(s, 1, x.size, rate)
    }

    /** Track ins Deck laden: Wellenform, Tempo und Beat-Raster bestimmen. */
    fun load(d: Deck, t: Track, title: String, nameBpm: Float?) {
        val bins = (t.len + BIN - 1) / BIN
        val pk = ByteArray(bins)
        val lw = ByteArray(bins)
        var lpState = 0f
        val a = (1.0 - exp(-2 * PI * 180.0 / t.rate)).toFloat()
        // Mono-Hüllkurve für die Tempo-Analyse (10-ms-Raster)
        val hop = max(1, t.rate / 200)
        val env = FloatArray(t.len / hop + 1)
        var maxPk = 1e-6f
        val pkF = FloatArray(bins)
        val lwF = FloatArray(bins)
        for (bi in 0 until bins) {
            var m = 0f
            var sAll = 0f
            var sLow = 0f
            val f0 = bi * BIN
            val f1 = min(t.len, f0 + BIN)
            for (f in f0 until f1) {
                val v = if (t.ch == 2) (t.data[2 * f] + t.data[2 * f + 1]) / 65536f else t.data[f] / 32768f
                lpState += a * (v - lpState)
                val av = abs(v)
                if (av > m) m = av
                sAll += av
                sLow += abs(lpState)
                env[f / hop] += v * v
            }
            pkF[bi] = m
            lwF[bi] = if (sAll > 1e-6f) (sLow / sAll).coerceIn(0f, 1f) else 0f
            if (m > maxPk) maxPk = m
        }
        for (bi in 0 until bins) {
            pk[bi] = (min(1f, pkF[bi] / maxPk) * 255f).toInt().toByte()
            lw[bi] = (lwF[bi] * 255f).toInt().toByte()
        }
        val det = detectBpm(env, t.rate.toDouble() / hop)
        val bpm = nameBpm ?: det.first
        val grid = findGrid(env, t.rate.toDouble() / hop, bpm) * hop
        d.playing = false
        d.sync = false
        d.loopOn = false
        d.data = null
        d.ch = t.ch
        d.len = t.len
        d.srcRate = t.rate
        d.peaks = pk
        d.lows = lw
        d.title = title
        d.bpm = bpm
        d.grid = grid
        d.cuePoint = grid
        for (k in 0 until 4) d.hot[k] = -1.0
        d.pos = grid
        d.seekTo = grid
        d.pitch = 0f
        d.libId = null
        d.data = t.data
    }

    /** Tempo-Erkennung über Onset-Autokorrelation (70–180 BPM, auf 85–175 gefaltet). */
    fun detectBpm(env: FloatArray, fps: Double): Pair<Float, Float> {
        val n = env.size
        if (n < fps * 4) return 120f to 0f
        val on = FloatArray(n)
        var prev = ln(1.0 + 1000.0 * env[0]).toFloat()
        var mean = 0f
        for (k in 1 until n) {
            val e = ln(1.0 + 1000.0 * env[k]).toFloat()
            on[k] = max(0f, e - prev)
            prev = e
            mean += on[k]
        }
        mean /= n
        for (k in 0 until n) on[k] -= mean
        // Mittelteil (höchstens 90 s) analysieren
        val span = min(n, (fps * 90).toInt())
        val s0 = (n - span) / 2
        val minLag = (fps * 60.0 / 180.0).toInt()
        val maxLag = (fps * 60.0 / 70.0).toInt() + 1
        val r = DoubleArray(maxLag * 2 + 2)
        for (lag in minLag..min(maxLag * 2, span - 1)) {
            var s = 0.0
            for (k in s0 until s0 + span - lag) s += on[k] * on[k + lag]
            r[lag] = s / (span - lag)
        }
        var best = minLag
        var bs = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val sc = r[lag] + 0.5 * (if (2 * lag < r.size) r[2 * lag] else 0.0)
            if (sc > bs) {
                bs = sc
                best = lag
            }
        }
        // parabolische Verfeinerung
        var lagF = best.toDouble()
        if (best > minLag && best < maxLag) {
            val y0 = r[best - 1]; val y1 = r[best]; val y2 = r[best + 1]
            val den = y0 - 2 * y1 + y2
            if (abs(den) > 1e-12) lagF = best + 0.5 * (y0 - y2) / den
        }
        var bpm = (60.0 * fps / lagF).toFloat()
        while (bpm < 85f) bpm *= 2f
        while (bpm > 175f) bpm /= 2f
        val conf = if (r[0] != 0.0) (bs / max(1e-9, abs(r[best]))).toFloat() else 0f
        return (Math.round(bpm * 10f) / 10f) to conf
    }

    /** Beat-Raster: Phase mit der meisten Onset-Energie (in Hüllkurven-Schritten). */
    private fun findGrid(env: FloatArray, fps: Double, bpm: Float): Double {
        val n = env.size
        if (n < 8 || bpm <= 0f) return 0.0
        val period = fps * 60.0 / bpm
        val on = FloatArray(n)
        for (k in 1 until n) on[k] = max(0f, sqrt(env[k]) - sqrt(env[k - 1]))
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

    /** Zeit als m:ss. */
    fun fmt(sec: Double): String {
        val s = max(0, sec.toInt())
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Für die Deck-Effekte: kleine Auswahl gut passender Effekte. */
    val FX_CHOICES: IntArray by lazy {
        val want = arrayOf("DELAY", "REVERB", "FLANGER", "PHASER", "STUTTER", "CRUSH", "FILTER", "GRAIN DLY", "FREEZE", "TREMOLO")
        want.mapNotNull { n -> Fx.NAMES.indexOf(n).takeIf { it >= 0 } }.toIntArray()
    }
}
