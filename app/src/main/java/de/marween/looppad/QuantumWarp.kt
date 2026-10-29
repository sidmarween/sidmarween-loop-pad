package de.marween.looppad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * QUANTUM WARP – Performance-Effekt für ein DJ-Deck (Stereo).
 *
 * Eingang: das Deck-Signal ([process] ml/mr) und sein „oberer“ Anteil (Gesang + Melodie, tl/tr) aus den Live-Stems.
 * „Unten“ (Drums + Bass) = Gesamt − oben.
 *
 *  X (ein Finger): links weiches Tape-Echo (punktierte Achtel, leichtes Leiern) → rechts Zeit-Freeze im Raster,
 *                  die Körner werden nach rechts immer feiner (1 Schlag … 1/16 Schlag) und wiederholen sich im Takt.
 *  Y: Mitte = normal. Hoch = Drums/Bass weg, Gesang/Melodie eine Oktave hoch in einen riesigen Shimmer-Hall.
 *     Runter = Melodien sterben weg, Drums + Sub werden trocken, hart, leicht gecrusht und pumpen im Viertel.
 *  Zwei Finger: Finger 1 friert die Drums ein (Korngröße = X von Finger 1), Finger 2 spielt den Gesang
 *               rückwärts im Raster (Scratch-Glitch, Stückgröße = X von Finger 2).
 *  Loslassen: „Gravity Release“ – das Effekt-Ende wird bis zur nächsten Takt-Eins in die Mitten gezogen
 *             (Bandpass zieht sich zu, wird leiser), auf der Eins knallt das Original zurück mit kurzem Filter-Sweep.
 */
class QuantumWarp(private val sr: Int) {
    companion object {
        private const val RN = 1 shl 18
        private const val RM = RN - 1
        const val IDLE = 0
        const val ACTIVE = 1
        const val PULL = 2
        const val SLAM = 3

        private fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        private fun frac(x: Double) = x - floor(x)
    }

    // Eingangs-Ringe (unten/oben, L/R) und Tape-Echo-Ringe
    private val loL = FloatArray(RN); private val loR = FloatArray(RN)
    private val tpL = FloatArray(RN); private val tpR = FloatArray(RN)
    private val dlL = FloatArray(RN); private val dlR = FloatArray(RN)
    private var wc = 0L

    @Volatile var state = IDLE
        private set
    private var mixW = 0f
    private var touchN = 0L
    private var engaged = 0f

    // geglättete Regler
    private var sx = 0.5f
    private var sy = 0f
    private var sx2 = 0.5f
    private var frzLo = 0f
    private var frzTp = 0f
    private var revTp = 0f
    private var dAmt = 0f

    // Freeze
    private var frzAnchor = -1L
    private var frzBar = 0L

    // Shimmer
    private val pitch = PitchHead(sr, 60f)
    private val plL = Plate(sr, 1.0f)
    private val plR = Plate(sr, 1.13f)
    private var shFb = 0f
    private var shHp = 0f
    private var shEnv = 0f

    // Unten-Bearbeitung
    private var subL = 0f; private var subR = 0f
    private var holdN = 0; private var holdL = 0f; private var holdR = 0f
    private var eIn = 1e-6f; private var eOut = 1e-6f; private var gMatch = 1f
    private var tLpL = 0f; private var tLpR = 0f

    // Tape-Echo
    private var lfo1 = 0.0; private var lfo2 = 0.0
    private var fbLpL = 0f; private var fbLpR = 0f; private var fbHpL = 0f; private var fbHpR = 0f
    private var dEnv = 0f

    // Gravity
    private var pullStart = 0.0
    private var pullEnd = 0.0
    private var slamT = 0
    private val pHpL = Dj.Biquad(); private val pHpR = Dj.Biquad()
    private val pLpL = Dj.Biquad(); private val pLpR = Dj.Biquad()
    private val sLpL = Dj.Biquad(); private val sLpR = Dj.Biquad()

    /** Läuft gerade etwas Hörbares (Effekt, Rückkehr oder Ausklang)? */
    fun busy(): Boolean = state != IDLE || mixW > 0f

    /** Fortschritt der Rückkehr 0..1 (für die Anzeige), −1 = keine. */
    @Volatile var pullProgress = -1f
        private set

    private fun rd(b: FloatArray, pos: Double): Float {
        val i = floor(pos).toLong()
        val t = (pos - i).toFloat()
        return FxDsp.hermite(b[((i - 1) and RM.toLong()).toInt()], b[(i and RM.toLong()).toInt()],
            b[((i + 1) and RM.toLong()).toInt()], b[((i + 2) and RM.toLong()).toInt()], t)
    }

    private fun resetTails() {
        plL.reset(); plR.reset(); pitch.reset()
        java.util.Arrays.fill(dlL, 0f); java.util.Arrays.fill(dlR, 0f)
        shFb = 0f; shHp = 0f; shEnv = 0f; dEnv = 0f
        fbLpL = 0f; fbLpR = 0f; fbHpL = 0f; fbHpR = 0f
        frzLo = 0f; frzTp = 0f; revTp = 0f; dAmt = 0f
        frzAnchor = -1L
        eIn = 1e-6f; eOut = 1e-6f; gMatch = 1f
        pHpL.reset(); pHpR.reset(); pLpL.reset(); pLpR.reset()
    }

    /**
     * [ml]/[mr] Deck-Signal (wird ersetzt), [tl]/[tr] oberer Anteil, [bp0] Schlag-Position der ersten Probe
     * (ganze Zahlen = Schläge, Vielfache von 4 = Takt-Eins), [bpStep] Schläge je Probe, [beatLen] Probe je Schlag.
     * [on] Finger liegt (oder gehalten), [x]/[y] Finger 1 (x 0..1, y −1..1), [two]/[x2] zweiter Finger.
     */
    fun process(ml: FloatArray, mr: FloatArray, tl: FloatArray, tr: FloatArray, n: Int,
                bp0: Double, bpStep: Double, beatLen: Double,
                on: Boolean, x: Float, y: Float, two: Boolean, x2: Float) {
        val L = beatLen.coerceIn(sr * 0.2, sr * 1.5)
        // Zustände
        if (on) {
            if (state == IDLE || state == SLAM) {
                if (mixW <= 0f || state == SLAM) { resetTails(); sx = x; sy = y; sx2 = x2 }
                touchN = 0
                engaged = 0f
            }
            state = ACTIVE
            touchN += n
        } else if (state == ACTIVE) {
            // kurz angetippt oder kaum bewegt → einfach ausblenden; sonst Gravity Release bis zur nächsten Eins
            if (touchN > sr / 4 && engaged > 0.08f) {
                state = PULL
                pullStart = bp0
                var t = floor(bp0 / 4.0) * 4.0 + 4.0
                if (t - bp0 < 2.0) t += 4.0
                pullEnd = t
            } else state = IDLE
        }
        val idle = state == IDLE && mixW <= 0f
        // Koeffizienten je Block
        val up = max(0f, sy)
        val dn = max(0f, -sy)
        val fcTop = 18000.0 * (400.0 / 18000.0).pow(dn.toDouble())
        val aTop = (1.0 - exp(-2 * PI * fcTop / sr)).toFloat()
        val aSub = (1.0 - exp(-2 * PI * 100.0 / sr)).toFloat()
        val aShHp = (1.0 - exp(-2 * PI * 150.0 / sr)).toFloat()
        val aFbLp = (1.0 - exp(-2 * PI * 4500.0 / sr)).toFloat()
        val aFbHp = (1.0 - exp(-2 * PI * 120.0 / sr)).toFloat()
        val aRms = 1f / (0.05f * sr)
        val kCtl = 1f / (0.012f * sr)
        val kMix = 1f / (0.008f * sr)
        val kEng = 1f / (0.01f * sr)
        val fd = max(16.0, sr * 0.0025)
        val refreshBars = if (12 * L < RN - 4096) 2 else 1
        for (i in 0 until n) {
            val bp = bp0 + i * bpStep
            val dryL = ml[i]
            val dryR = mr[i]
            val tL = tl[i]
            val tR = tr[i]
            val lL = dryL - tL
            val lR = dryR - tR
            val w = (wc and RM.toLong()).toInt()
            loL[w] = lL; loR[w] = lR; tpL[w] = tL; tpR[w] = tR
            wc++
            if (idle) continue

            // Regler gleiten (in der Rückkehr eingefroren)
            if (state == ACTIVE) {
                sx += (x - sx) * kCtl
                sy += (y - sy) * kCtl
                sx2 += (x2 - sx2) * kCtl
            }
            // Zeit-Effekt: Ziele
            val tFrzLo: Float
            val tFrzTp: Float
            val tRev: Float
            val tDel: Float
            val qLo: Int
            if (two) {
                tFrzLo = 1f; tFrzTp = 0f; tRev = 1f; tDel = 0f
                qLo = (sx * 4.999f).toInt().coerceIn(0, 4)
            } else {
                val fz = smooth(0.45f, 0.55f, sx)
                tFrzLo = fz; tFrzTp = fz; tRev = 0f
                tDel = smooth(0f, 0.45f, sx) * (1f - 0.85f * smooth(0.5f, 0.75f, sx))
                qLo = (((sx - 0.55f) / 0.45f).coerceIn(0f, 1f) * 4.999f).toInt()
            }
            frzLo += (tFrzLo - frzLo) * kMix
            frzTp += (tFrzTp - frzTp) * kMix
            revTp += (tRev - revTp) * kMix
            dAmt += (tDel - dAmt) * kCtl
            val e0 = max(max(frzLo, frzTp), max(revTp, max(dAmt, abs(sy))))
            if (e0 > engaged) engaged = e0

            // Freeze-Anker: Anfang des letzten vollen Schlags; im Raster erneuert
            val beatFrac = frac(bp)
            val bar = floor(bp / 4.0).toLong()
            if (frzLo > 0.001f || frzTp > 0.001f) {
                if (frzAnchor < 0 || (bar - frzBar >= refreshBars && beatFrac < 0.5 && Math.floorMod(bp.toLong(), 4L) == 0L) || wc - frzAnchor > RN - 3 * L) {
                    frzAnchor = wc - 1 - (beatFrac * L).toLong() - L.toLong()
                    frzBar = bar
                }
            } else frzAnchor = -1L

            var oLoL = lL; var oLoR = lR
            var oTpL = tL; var oTpR = tR
            if (frzAnchor >= 0 && (frzLo > 0.001f || frzTp > 0.001f)) {
                val sub = 1 shl qLo
                val gs = L / sub
                val t = frac(bp * sub) * gs
                // feine Körner (oft der Kick-Anschlag) etwas leiser – sonst wird es zum Dauerknattern
                val env = min(1.0, min(t / fd, (gs - t) / fd)).toFloat().coerceAtLeast(0f) * (1f - 0.11f * qLo)
                val pos = frzAnchor + t
                if (frzLo > 0.001f) {
                    val a = rd(loL, pos) * env; val b = rd(loR, pos) * env
                    oLoL += (a - oLoL) * frzLo; oLoR += (b - oLoR) * frzLo
                }
                if (frzTp > 0.001f) {
                    val a = rd(tpL, pos) * env; val b = rd(tpR, pos) * env
                    oTpL += (a - oTpL) * frzTp; oTpR += (b - oTpR) * frzTp
                }
            }
            if (revTp > 0.001f) {
                // Rückwärts im Raster: das gerade vergangene Stück läuft rückwärts (mit Scratch-Kurve)
                val sub = 1 shl (sx2 * 3.999f).toInt().coerceIn(0, 3)
                val cs = L / sub
                val ph = frac(bp * sub)
                val tIn = ph * cs
                val shape = ph + 0.1 * sin(2 * PI * ph)
                val pos = (wc - 1) - tIn - 1.0 - cs * shape
                val env = min(1.0, min(tIn / fd, (cs - tIn) / fd)).toFloat().coerceAtLeast(0f)
                val a = rd(tpL, pos) * env; val b = rd(tpR, pos) * env
                oTpL += (a - oTpL) * revTp; oTpR += (b - oTpR) * revTp
            }

            // Y: oben Shimmer
            var shL = 0f; var shR = 0f
            if (up > 0.001f || shEnv > 1e-5f) {
                val s = (oTpL + oTpR) * 0.5f * up
                val oct = pitch.tick(s + shFb * 0.35f, 2.0)
                shHp += (oct - shHp) * aShHp
                val pin = s * 0.5f + (oct - shHp) * 0.8f
                val wl = plL.tick(pin, 0.93f, 0.3f, 0.7f)
                val wr = plR.tick(pin, 0.93f, 0.3f, 0.7f)
                shFb = tanh((wl + wr) * 0.5f)
                shEnv = max(abs(wl) + abs(wr), shEnv * 0.9998f)
                shL = wl * 2.2f; shR = wr * 2.2f
            }
            // Y: unten Industrial
            var outLoL = oLoL * (1f - 0.92f * up)
            var outLoR = oLoR * (1f - 0.92f * up)
            if (dn > 0.001f) {
                subL += (oLoL - subL) * aSub; subR += (oLoR - subR) * aSub
                val xl = oLoL + subL * 0.7f * dn
                val xr = oLoR + subR * 0.7f * dn
                val k = 1f + 3f * dn
                var vl = tanh(k * xl) / k
                var vr = tanh(k * xr) / k
                // leichter Bitcrusher (14 → 9 Bit, ab der Hälfte doppelte Proben)
                val q = 2f.pow(14f - 5f * dn)
                if (holdN <= 0) {
                    holdL = Math.round(vl * q) / q; holdR = Math.round(vr * q) / q
                    holdN = if (dn > 0.5f) 2 else 1
                }
                holdN--
                val cm = 0.45f * dn
                vl += (holdL - vl) * cm; vr += (holdR - vr) * cm
                // Pegel angleichen
                eIn += (oLoL * oLoL + oLoR * oLoR - eIn) * aRms
                eOut += (vl * vl + vr * vr - eOut) * aRms
                val gt = sqrt(eIn / (eOut + 1e-9f)).coerceIn(0.5f, 2.5f)
                gMatch += (gt - gMatch) * 0.001f
                // Sidechain-Pumpen: Kick-Anschlag bleibt (20 ms), dann ducken, bis 0,62 Schlag erholt
                val ts = beatFrac * L
                val a0 = sr * 0.02; val a1 = sr * 0.035; val re = 0.62 * L
                val depth = 0.7f * dn
                val pg = when {
                    ts < a0 -> 1f
                    ts < a1 -> 1f - depth * ((ts - a0) / (a1 - a0)).toFloat()
                    ts < re -> { val u = 1f - ((ts - a1) / (re - a1)).toFloat(); 1f - depth * u * u }
                    else -> 1f
                }
                val g = gMatch * pg * 1.15f
                outLoL += (vl * g - outLoL) * dn
                outLoR += (vr * g - outLoR) * dn
            }
            // oben: wird nach unten dunkler und leiser, nach oben in den Hall gezogen
            tLpL += (oTpL - tLpL) * aTop; tLpR += (oTpR - tLpR) * aTop
            val tg = (1f - 0.6f * up) * (1f - dn)
            val lm = min(1f, dn * 4f)   // Tiefpass erst beim Runterziehen (Mitte bleibt exakt trocken)
            var oL = outLoL + (oTpL + (tLpL - oTpL) * lm) * tg + shL
            var oR = outLoR + (oTpR + (tLpR - oTpR) * lm) * tg + shR

            // Tape-Echo (punktierte Achtel, weich leiernd)
            if (dAmt > 0.001f || dEnv > 1e-5f) {
                lfo1 += 0.55 / sr; if (lfo1 > 1) lfo1 -= 1.0
                lfo2 += 6.3 / sr; if (lfo2 > 1) lfo2 -= 1.0
                val wow = (0.0016 * sin(2 * PI * lfo1) + 0.0002 * sin(2 * PI * lfo2)) * sr
                val d0 = 0.75 * L + wow
                val el = rd(dlL, (wc - 1) - d0)
                val er = rd(dlR, (wc - 1) - d0 * 1.015)
                fbLpL += (el - fbLpL) * aFbLp; fbLpR += (er - fbLpR) * aFbLp
                fbHpL += (fbLpL - fbHpL) * aFbHp; fbHpR += (fbLpR - fbHpR) * aFbHp
                val fb = 0.3f + 0.4f * dAmt
                dlL[w] = oL * dAmt + tanh((fbLpL - fbHpL) * fb * 1.1f) / 1.1f
                dlR[w] = oR * dAmt + tanh((fbLpR - fbHpR) * fb * 1.1f) / 1.1f
                dEnv = max(abs(el) + abs(er), dEnv * 0.9999f)
                oL += el * 0.85f
                oR += er * 0.85f
            } else { dlL[w] = 0f; dlR[w] = 0f }

            // Gravity Release / Slam / Ein-Ausblenden
            when (state) {
                ACTIVE -> {
                    if (i and 15 == 0) { pHpL.hp(sr, 30.0, 0.707); pHpR.hp(sr, 30.0, 0.707); pLpL.lp(sr, 16000.0, 0.707); pLpR.lp(sr, 16000.0, 0.707) }
                    pHpL.run(oL); pHpR.run(oR); pLpL.run(oL); pLpR.run(oR)   // warm halten
                    mixW = min(1f, mixW + kEng)
                    ml[i] = dryL + (oL - dryL) * mixW
                    mr[i] = dryR + (oR - dryR) * mixW
                }
                PULL -> {
                    if (bp >= pullEnd) {
                        state = SLAM
                        slamT = 0
                        sLpL.reset(); sLpR.reset()
                        pullProgress = -1f
                    } else {
                        val e = ((bp - pullStart) / max(1e-6, pullEnd - pullStart)).coerceIn(0.0, 1.0)
                        if (i and 15 == 0) {
                            val hf = 30.0 * (700.0 / 30.0).pow(e)
                            val lf = 16000.0 * (1200.0 / 16000.0).pow(e)
                            pHpL.hp(sr, hf, 0.9); pHpR.hp(sr, hf, 0.9)
                            pLpL.lp(sr, lf, 1.4); pLpR.lp(sr, lf, 1.4)
                            pullProgress = e.toFloat()
                        }
                        val g = (1.0 - e).pow(1.4).toFloat()
                        ml[i] = pLpL.run(pHpL.run(oL)) * g
                        mr[i] = pLpR.run(pHpR.run(oR)) * g
                        mixW = 1f
                        continue
                    }
                }
                else -> {}
            }
            if (state == SLAM) {
                val T = (0.13 * sr).toInt()
                if (i and 15 == 0 || slamT == 0) {
                    val fc = 700.0 * (20000.0 / 700.0).pow(min(1.0, slamT.toDouble() / T))
                    sLpL.lp(sr, fc, 1.3); sLpR.lp(sr, fc, 1.3)
                }
                ml[i] = sLpL.run(dryL)
                mr[i] = sLpR.run(dryR)
                slamT++
                if (slamT >= T) { state = IDLE; mixW = 0f }
            } else if (state == IDLE) {
                mixW = max(0f, mixW - kEng * 0.35f)
                ml[i] = dryL + (oL - dryL) * mixW
                mr[i] = dryR + (oR - dryR) * mixW
            }
        }
    }
}
