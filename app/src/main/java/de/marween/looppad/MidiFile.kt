package de.marween.looppad

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Standard-MIDI-Dateien (.mid) für die Piano-Roll: Noten exportieren und importieren. */
object MidiFile {
    private const val PPQ = 480

    class Note(val beat: Double, val len: Double, val pitch: Int, val vel: Int)

    private fun varLen(out: ByteArrayOutputStream, v0: Int) {
        var v = v0
        var buf = v and 0x7F
        while (true) {
            v = v ushr 7
            if (v == 0) break
            buf = (buf shl 8) or ((v and 0x7F) or 0x80)
        }
        while (true) {
            out.write(buf and 0xFF)
            if (buf and 0x80 != 0) buf = buf ushr 8 else break
        }
    }

    /** Noten einer Piano-Roll (Positionen/Längen in Takten, 4/4) als MIDI-Datei (Format 0). C4 = Halbton 0 = Note 60. */
    fun export(notes: List<Engine.NoteEv>, bpm: Float, name: String): ByteArray {
        data class Ev(val tick: Int, val on: Boolean, val pitch: Int, val vel: Int)
        val evs = ArrayList<Ev>()
        for (n in notes) {
            val pitch = (60 + n.semi).coerceIn(0, 127)
            val t0 = (n.pos * 4 * PPQ).roundToInt()
            val t1 = max(t0 + 1, ((n.pos + n.len) * 4 * PPQ).roundToInt())
            evs.add(Ev(t0, true, pitch, (n.vel * 127).roundToInt().coerceIn(1, 127)))
            evs.add(Ev(t1, false, pitch, 0))
        }
        evs.sortWith(compareBy({ it.tick }, { if (it.on) 1 else 0 }))
        val trk = ByteArrayOutputStream()
        // Name + Tempo + 4/4
        val nb = name.toByteArray(Charsets.UTF_8).take(100).toByteArray()
        varLen(trk, 0); trk.write(0xFF); trk.write(0x03); varLen(trk, nb.size); trk.write(nb)
        val us = (60_000_000.0 / (if (bpm > 0f) bpm else 120f)).roundToInt()
        varLen(trk, 0); trk.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03, (us shr 16).toByte(), (us shr 8).toByte(), us.toByte()))
        varLen(trk, 0); trk.write(byteArrayOf(0xFF.toByte(), 0x58, 0x04, 4, 2, 24, 8))
        var last = 0
        for (e in evs) {
            varLen(trk, e.tick - last)
            last = e.tick
            trk.write(if (e.on) 0x90 else 0x80)
            trk.write(e.pitch)
            trk.write(if (e.on) e.vel else 64)
        }
        varLen(trk, 0); trk.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        val body = trk.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, (PPQ shr 8).toByte(), PPQ.toByte()))
        out.write("MTrk".toByteArray(Charsets.US_ASCII))
        val l = body.size
        out.write(byteArrayOf((l shr 24).toByte(), (l shr 16).toByte(), (l shr 8).toByte(), l.toByte()))
        out.write(body)
        return out.toByteArray()
    }

    class Parsed(val notes: List<Note>, val bpm: Float)

    /** Liest alle Noten aller Spuren (Positionen in Vierteln). */
    fun parse(b: ByteArray): Parsed {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        if (b.size < 14 || String(b, 0, 4, Charsets.US_ASCII) != "MThd") throw IllegalArgumentException("Keine MIDI-Datei")
        val hl = u32(4)
        val ntr = u16(10)
        val div = u16(12)
        if (div and 0x8000 != 0) throw IllegalArgumentException("SMPTE-Zeitbasis wird nicht unterstützt")
        val ppq = max(1, div)
        var p = 8 + hl
        val notes = ArrayList<Note>()
        var bpm = 0f
        for (t in 0 until ntr) {
            if (p + 8 > b.size) break
            val id = String(b, p, 4, Charsets.US_ASCII)
            val len = u32(p + 4)
            p += 8
            val end = minOf(b.size, p + len)
            if (id != "MTrk") { p = end; continue }
            var tick = 0L
            var status = 0
            val open = HashMap<Int, Pair<Long, Int>>()
            while (p < end) {
                var dv = 0
                while (p < end) {
                    val c = b[p++].toInt() and 0xFF
                    dv = (dv shl 7) or (c and 0x7F)
                    if (c and 0x80 == 0) break
                }
                tick += dv
                if (p >= end) break
                var c = b[p].toInt() and 0xFF
                if (c >= 0x80) { status = c; p++ } else c = status
                when {
                    status == 0xFF -> {
                        val type = b[p++].toInt() and 0xFF
                        var ml = 0
                        while (p < end) { val x = b[p++].toInt() and 0xFF; ml = (ml shl 7) or (x and 0x7F); if (x and 0x80 == 0) break }
                        if (type == 0x51 && ml == 3 && bpm == 0f) {
                            val us = ((b[p].toInt() and 0xFF) shl 16) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() and 0xFF)
                            if (us > 0) bpm = (60_000_000.0 / us).toFloat()
                        }
                        p += ml
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        var ml = 0
                        while (p < end) { val x = b[p++].toInt() and 0xFF; ml = (ml shl 7) or (x and 0x7F); if (x and 0x80 == 0) break }
                        p += ml
                    }
                    else -> {
                        val type = status and 0xF0
                        val ch = status and 0x0F
                        val d1 = b[p++].toInt() and 0xFF
                        val d2 = if (type == 0xC0 || type == 0xD0) 0 else (b[p++].toInt() and 0xFF)
                        val key = ch * 128 + d1
                        if (type == 0x90 && d2 > 0) {
                            open[key] = tick to d2
                        } else if (type == 0x80 || type == 0x90) {
                            open.remove(key)?.let { (st, v) ->
                                notes.add(Note(st.toDouble() / ppq, max(1L, tick - st).toDouble() / ppq, d1, v))
                            }
                        }
                    }
                }
            }
            p = end
        }
        return Parsed(notes.sortedBy { it.beat }, bpm)
    }
}
