package de.marween.looppad

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Set-Aufnahme: nimmt den Master-Ausgang (Stereo, nach der Mastering-Kette, ohne Metronom) auf.
 * Der Mischer schiebt Blöcke in einen Ringpuffer, ein eigener Thread schreibt sie laufend
 * als 16-Bit-WAV auf den Speicher – so sind auch stundenlange Sets möglich.
 */
object SetRecorder {
    @Volatile var recording = false
        private set
    @Volatile var frames = 0L
        private set
    @Volatile var level = 0f
    @Volatile var dropped = 0L
        private set
    private var rate = 48000
    private var file: File? = null
    private var thread: Thread? = null

    private var ring = FloatArray(0)
    @Volatile private var wPos = 0L
    @Volatile private var rPos = 0L

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sets").apply { mkdirs() }

    fun list(ctx: Context): List<File> = (dir(ctx).listFiles()?.filter { it.name.endsWith(".wav") } ?: emptyList()).sortedByDescending { it.lastModified() }

    fun seconds(): Double = frames.toDouble() / rate

    fun start(ctx: Context, sampleRate: Int): Boolean {
        if (recording) return false
        rate = sampleRate
        val name = "MarvPAD_Set_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date()) + ".wav"
        val f = File(dir(ctx), name)
        val raf = try { RandomAccessFile(f, "rw") } catch (e: Exception) { return false }
        raf.setLength(0)
        raf.write(header(0, rate))
        ring = FloatArray(rate * 2 * 10) // 10 s Reserve
        wPos = 0L
        rPos = 0L
        frames = 0L
        dropped = 0L
        file = f
        recording = true
        thread = Thread({ writerLoop(raf) }, "MarvPAD-SetRec").also {
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
        return true
    }

    /** Beendet die Aufnahme und liefert die fertige Datei. */
    fun stop(): File? {
        if (!recording) return null
        recording = false
        try { thread?.join(4000) } catch (ignored: Exception) {}
        thread = null
        return file
    }

    /** Vom Mischer-Thread: verschachtelte Stereo-Samples (L, R, …). */
    fun push(st: FloatArray, n: Int) {
        if (!recording) return
        val cap = ring.size
        val count = n * 2
        if (wPos + count - rPos > cap) {
            dropped += n
            return
        }
        var p = (wPos % cap).toInt()
        var pk = 0f
        for (i in 0 until count) {
            val v = st[i]
            ring[p] = v
            p++
            if (p == cap) p = 0
            val a = abs(v)
            if (a > pk) pk = a
        }
        wPos += count
        frames += n
        level = max(pk, level * 0.9f)
    }

    private fun writerLoop(raf: RandomAccessFile) {
        val chunk = ByteArray(rate * 4)
        var dataBytes = 0L
        try {
            while (true) {
                val avail = wPos - rPos
                if (avail <= 0) {
                    if (!recording) break
                    Thread.sleep(40)
                    continue
                }
                val cap = ring.size
                val n = minOf(avail, (chunk.size / 2).toLong()).toInt()
                var p = (rPos % cap).toInt()
                var bi = 0
                for (i in 0 until n) {
                    val s = (ring[p].coerceIn(-1f, 1f) * 32767f).toInt()
                    chunk[bi++] = (s and 0xFF).toByte()
                    chunk[bi++] = ((s shr 8) and 0xFF).toByte()
                    p++
                    if (p == cap) p = 0
                }
                raf.write(chunk, 0, bi)
                dataBytes += bi
                rPos += n
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                raf.seek(0)
                raf.write(header(dataBytes, rate))
                raf.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun header(dataBytes: Long, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val d = dataBytes.coerceAtMost(0xFFFFFFF0L).toInt()
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + d)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)
        b.putShort(2)
        b.putInt(rate)
        b.putInt(rate * 4)
        b.putShort(4)
        b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(d)
        return b.array()
    }

    fun fmt(sec: Double): String {
        val s = sec.toInt()
        return if (s >= 3600) String.format(Locale.GERMANY, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.GERMANY, "%d:%02d", s / 60, s % 60)
    }
}
