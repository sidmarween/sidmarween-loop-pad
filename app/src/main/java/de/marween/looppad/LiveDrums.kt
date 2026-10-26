package de.marween.looppad

/**
 * Lebendige Drums: verändert das Drum-Pattern beim Abspielen Takt für Takt – das Pattern selbst bleibt unverändert.
 *
 * Je Takt wird (immer gleich für denselben Takt, also wiederholbar) ausgewählt:
 *  - Groove-Variationen (Ghost-Notes, offene Hi-Hat, Kick-Synkopen, Snare-Ghosts, Rimshots, Ride statt Hi-Hat,
 *    halbe Hi-Hat, Clap-Doppel, Kick-Pause …) – je höher die Stufe, desto mehr gleichzeitig und desto öfter
 *  - Fills: großer Fill vor jeder Phrase, kleine Fills alle 4 Takte (bzw. öfter bei „wild“),
 *    aus bis zu 10 Fill-Arten (Snare-Wirbel, Tom-Lauf, Clap-Stotterer, Kick+Snare-Unisono, Hi-Hat-Aufbau,
 *    Stopp, 32tel-Wirbel, 32tel-Toms, Flam-Rush, Rim-Ruf) – „dezent“ nutzt die ruhigeren, „wild“ alle.
 * Zusatzschläge zwischen den 1/16 (32tel, Flams) kommen als [Out.exV] mit Bruchteil des Schritts.
 */
object LiveDrums {
    const val BD = 0; const val SD = 1; const val LT = 2; const val MT = 3; const val HT = 4
    const val RS = 5; const val CP = 6; const val CH = 7; const val OH = 8; const val CR = 9; const val RD = 10

    class Out {
        val x = IntArray(InstState.DV)
        val vel = FloatArray(InstState.DV)
        val exV = IntArray(16)
        val exFrac = FloatArray(16)
        val exVel = FloatArray(16)
        var exN = 0
        /** Für die Anzeige/Tests: gewählter Fill (−1 = keiner) und Groove-Variationen (Bits). */
        var fill = -1
        var grooves = 0
        fun add(v: Int, frac: Float, vel: Float) { if (exN < exV.size) { exV[exN] = v; exFrac[exN] = frac; exVel[exN] = vel; exN++ } }
        fun hit(v: Int, ve: Float, acc: Boolean = false) { x[v] = if (acc) 2 else 1; vel[v] = ve.coerceIn(0.05f, 1f) }
        fun off(v: Int) { x[v] = 0 }
    }

    val FILL_NAMES = arrayOf("Snare-Wirbel", "Tom-Lauf", "Clap-Stotterer", "Kick+Snare", "Hi-Hat-Aufbau", "Stopp", "32tel-Wirbel", "32tel-Toms", "Flam-Rush", "Rim-Ruf")
    val GROOVE_NAMES = arrayOf("Hat-Ghosts", "offene Hat", "Kick-Synkope", "Snare-Ghosts", "Kick-Doppel", "Rimshots", "halbe Hat", "Ride", "Clap-Doppel", "Kick-Pause", "Hat-Akzente", "Tom-Groove")

    /** Zufall 0..1, für (a, salt) immer gleich. */
    fun rnd(a: Long, salt: Int): Float {
        var x = a * 6364136223846793005L + salt * 1442695040888963407L + 0x2545F4914F6CDD1DL
        x = x xor (x ushr 29); x *= -0x4b47d5b1a1d3a2e5L; x = x xor (x ushr 32)
        return (x and 0xFFFFFF).toFloat() / 0xFFFFFF
    }

    /** Stufe: 0 = dezent, 1 = mittel, 2 = wild. */
    fun tier(la: Float) = if (la < 0.45f) 0 else if (la < 0.8f) 1 else 2

    /**
     * [n] Sequenzer-Schritt (fortlaufend), [la] Stärke 0..1, [used] Spuren mit Schlägen im Pattern,
     * [o] enthält beim Aufruf das Pattern dieses Schritts und wird verändert.
     */
    fun apply(n: Long, la: Float, fills: Boolean, phrase: Int, used: BooleanArray, o: Out) {
        o.exN = 0; o.fill = -1; o.grooves = 0
        if (la <= 0f) return
        val t = tier(la)
        val bar = Math.floorDiv(n, 16L)
        val s = Math.floorMod(n, 16L).toInt()
        val ph = phrase.coerceAtLeast(1).toLong()
        val pb = Math.floorMod(bar, ph).toInt()
        // ---- Fill dieses Takts?
        var span = 0
        if (fills) {
            if (pb == ph.toInt() - 1) {
                span = when (t) { 0 -> 4; 1 -> if (rnd(bar, 11) < 0.5f) 8 else 4; else -> if (rnd(bar, 11) < 0.6f) 8 else 4 }
            } else if (ph >= 8 && pb % 4 == 3) {
                if (rnd(bar, 12) < floatArrayOf(0.4f, 0.65f, 0.9f)[t]) span = if (t == 2 && rnd(bar, 13) < 0.4f) 4 else 2
            } else if (t >= 1 && pb % 2 == 1) {
                if (rnd(bar, 14) < (if (t == 2) 0.35f else 0.15f)) span = if (t == 2 && rnd(bar, 15) < 0.5f) 2 else 1
            }
        }
        val fillFrom = 16 - span
        if (span > 0 && s >= fillFrom) {
            val pool = when (t) { 0 -> intArrayOf(0, 1, 2, 9); 1 -> intArrayOf(0, 1, 2, 3, 4, 5, 9); else -> intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9) }
            var ft = pool[(rnd(bar, 21) * pool.size).toInt().coerceIn(0, pool.size - 1)]
            // derselbe Fill nicht zweimal hintereinander an derselben Stelle
            if (ft == pool[(rnd(bar - ph, 21) * pool.size).toInt().coerceIn(0, pool.size - 1)]) ft = pool[(pool.indexOf(ft) + 1) % pool.size]
            o.fill = ft
            fill(ft, s - fillFrom, span, bar, s, t, used, o)
            return
        }
        // ---- Groove-Variationen für diesen Takt
        val pool = IntArray(12)
        var np = 0
        fun cand(g: Int, ok: Boolean) { if (ok) pool[np++] = g }
        val hats = used[CH]
        val kick = used[BD]
        val snare = used[SD] || used[CP]
        cand(0, hats)
        cand(1, hats)
        cand(2, kick)
        cand(3, snare)
        cand(4, kick)
        cand(5, true)
        cand(6, hats && t >= 1)
        cand(7, hats && t >= 1 && pb >= ph / 2)
        cand(8, used[SD] || used[CP])
        cand(9, kick && t == 2 && pb != 0)
        cand(10, hats)
        cand(11, t >= 1)
        if (np == 0) return
        val want = when (t) {
            0 -> if (rnd(bar, 31) < 0.55f) 1 else 0
            1 -> if (rnd(bar, 31) < 0.2f) 0 else 1 + (if (rnd(bar, 32) < 0.45f) 1 else 0)
            else -> 2 + (if (rnd(bar, 32) < 0.5f) 1 else 0)
        }
        // „want“ verschiedene Variationen ziehen (Fisher-Yates mit Takt-Zufall)
        for (i in np - 1 downTo 1) { val j = (rnd(bar, 40 + i) * (i + 1)).toInt().coerceIn(0, i); val tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp }
        for (i in 0 until minOf(want, np)) {
            val g = pool[i]
            // „Kick-Pause“ nur selten
            if (g == 9 && rnd(bar, 50) > 0.35f) continue
            // Hi-Hat-Variationen schließen sich gegenseitig aus (Ghosts / halbe Hat / Ride)
            val hatMask = (1 shl 0) or (1 shl 6) or (1 shl 7)
            if ((1 shl g) and hatMask != 0 && o.grooves and hatMask != 0) continue
            o.grooves = o.grooves or (1 shl g)
            groove(g, s, bar, t, la, used, o)
        }
    }

    private fun fill(ft: Int, k: Int, span: Int, bar: Long, s: Int, t: Int, used: BooleanArray, o: Out) {
        val p = (k + 1f) / span          // 0..1 Fortschritt im Fill
        val last = s == 15
        // Becken und Hi-Hats pausieren im Fill (außer beim Hi-Hat-Aufbau)
        if (ft != 4) { o.off(CH); o.off(OH); o.off(RD); o.off(CR) }
        when (ft) {
            0 -> { // Snare-Wirbel: 8tel, zum Ende 16tel, lauter werdend
                val on = span <= 2 || s >= 12 || s % 2 == 0
                if (on) o.hit(SD, 0.4f + 0.6f * p, last) else o.off(SD)
                o.off(CP)
                if (k > 0) o.off(BD)
            }
            1 -> { // Tom-Lauf hoch → tief
                o.off(SD); o.off(CP)
                val v = when { p <= 0.34f -> HT; p <= 0.67f -> MT; else -> LT }
                if (span <= 2 || s % 2 == 0 || t >= 1) o.hit(v, 0.75f + 0.25f * p)
                if (last) o.hit(BD, 0.9f)
                else if (k > 0) o.off(BD)
            }
            2 -> { // Clap-Stotterer
                o.off(SD)
                o.hit(CP, 0.35f + 0.65f * p, last)
                if (k > 0) o.off(BD)
            }
            3 -> { // Kick + Snare im Unisono auf 8teln
                if (s % 2 == 0 || span <= 2) { o.hit(BD, 0.95f); o.hit(SD, 0.6f + 0.4f * p, last) } else { o.off(BD); o.off(SD) }
                o.off(CP)
            }
            4 -> { // Hi-Hat-Aufbau: offene Hat auf 8teln, Snare 16tel am Ende
                o.off(CH)
                if (s % 2 == 1) o.hit(OH, 0.5f + 0.4f * p) else o.off(OH)
                if (s >= 12) o.hit(SD, 0.5f + 0.5f * p) else o.off(SD)
            }
            5 -> { // Stopp: alles still, nur ein Auftakt am Ende
                for (v in 0 until InstState.DV) o.off(v)
                if (last && rnd(bar, 60) < 0.6f) o.hit(if (used[SD]) SD else CP, 0.75f)
            }
            6 -> { // 32tel-Wirbel (Snare, je Schritt zwei Schläge)
                o.off(CP)
                if (k > 0) o.off(BD)
                o.hit(SD, 0.3f + 0.6f * p)
                o.add(SD, 0.5f, 0.35f + 0.65f * p)
            }
            7 -> { // 32tel-Toms, hoch → tief
                o.off(SD); o.off(CP)
                val v = when { p <= 0.34f -> HT; p <= 0.67f -> MT; else -> LT }
                o.hit(v, 0.7f + 0.3f * p)
                o.add(v, 0.5f, 0.6f + 0.3f * p)
                if (last) o.hit(BD, 1f) else if (k > 0) o.off(BD)
            }
            8 -> { // Flam-Rush: Snare mit Vorschlag, Toms abwechselnd
                o.off(CP)
                if (s % 2 == 0) { o.hit(SD, 0.7f + 0.3f * p, last); o.add(SD, 0.12f, 0.35f) } else o.hit(if (p < 0.5f) HT else LT, 0.8f)
                if (k > 0 && !last) o.off(BD)
            }
            else -> { // Rim-Ruf: Rimshot-Antworten, Kick drunter
                o.off(SD); o.off(CP)
                if (s % 2 == 1 || span <= 2) o.hit(RS, 0.6f + 0.4f * p) else o.off(RS)
                if (last) o.hit(SD, 0.85f, true)
            }
        }
    }

    private fun groove(g: Int, s: Int, bar: Long, t: Int, la: Float, used: BooleanArray, o: Out) {
        val r = rnd(bar * 16 + s, 100 + g)
        when (g) {
            0 -> if (o.x[CH] == 0 && o.x[OH] == 0 && r < 0.35f + 0.3f * la) o.hit(CH, 0.22f + 0.18f * rnd(bar * 16 + s, 160)) // Hat-Ghosts
            1 -> { // offene Hat auf dem „und“ (eine Stelle je Takt)
                val pos = intArrayOf(14, 6, 10, 2)[(rnd(bar, 101) * (2 + t)).toInt().coerceIn(0, 3)]
                if (s == pos) { o.off(CH); o.hit(OH, 0.75f) }
            }
            2 -> { // Kick-Synkope
                val pos = intArrayOf(14, 10, 7, 3, 6)[(rnd(bar, 102) * (3 + t)).toInt().coerceIn(0, 4)]
                if (s == pos && o.x[BD] == 0) o.hit(BD, 0.7f)
            }
            3 -> { // Snare-Ghosts
                val v = if (used[SD]) SD else CP
                if ((s == 7 || s == 9 || s == 15 || (t == 2 && s == 13)) && o.x[v] == 0 && r < 0.5f + 0.3f * la) o.hit(v, 0.18f + 0.12f * r)
            }
            4 -> if ((s == 3 || s == 11) && o.x[BD] == 0 && rnd(bar, 104 + s) < 0.6f) o.hit(BD, 0.65f) // Kick-Doppel vor der Snare
            5 -> { // Rimshots auf Offbeats
                if (s % 2 == 1 && o.x[RS] == 0 && r < 0.12f + 0.15f * t) o.hit(RS, 0.35f + 0.3f * r)
            }
            6 -> { // halbe Hat: nur jede zweite 8tel bleibt
                if (o.x[CH] > 0 && s % 4 != 2) o.off(CH)
            }
            7 -> { // Ride statt geschlossener Hat
                if (o.x[CH] > 0) { o.off(CH); o.hit(RD, o.vel[CH].coerceAtLeast(0.5f)) }
            }
            8 -> { // Clap verdoppelt die Snare (oder umgekehrt), dazu ein Vorschlag
                if (o.x[SD] > 0 && o.x[CP] == 0) o.hit(CP, o.vel[SD] * 0.8f)
                else if (o.x[CP] > 0 && o.x[SD] == 0) o.hit(SD, o.vel[CP] * 0.7f)
                if ((s == 3 || s == 11) && t >= 1 && rnd(bar, 108) < 0.5f) o.add(if (used[SD]) SD else CP, 0.5f, 0.25f)
            }
            9 -> if (s < 8) o.off(BD) // Kick-Pause in der ersten Takthälfte
            10 -> { // Hat-Akzente: Viertel betont, Rest leiser
                if (o.x[CH] > 0) o.vel[CH] = if (s % 4 == 2) 0.95f else (o.vel[CH] * 0.6f).coerceAtLeast(0.15f)
            }
            11 -> { // Tom-Groove: leise Toms auf ein, zwei Offbeats
                if ((s == 10 || s == 14 || (t == 2 && s == 6)) && rnd(bar, 111 + s) < 0.6f) o.hit(if (s == 14) LT else MT, 0.35f + 0.2f * r)
            }
        }
    }
}
