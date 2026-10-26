package de.marween.looppad

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Nimmt mehrere Mono-Spuren gleichzeitig als WAV auf (Stems beim Song-Export).
 * Der Mischer-Thread schiebt pro Block alle Spuren hinein, ein eigener Thread schreibt die Dateien.
 */
object StemRecorder {
    val NAMES = arrayOf("ACID", "DRUM", "SYNTH1", "SYNTH2", "SYNTH3", "PADS")
    val COUNT = NAMES.size

    @Volatile var recording = false
        private set
    private var rate = 48000
    private var files: List<File> = emptyList()
    private var rafs: List<RandomAccessFile> = emptyList()
    private var thread: Thread? = null
    private var ring = Array(0) { FloatArray(0) }
    @Volatile private var wPos = 0L
    @Volatile private var rPos = 0L
    /** Größter Pegel je Spur (leere Spuren werden nicht exportiert). */
    val peak = FloatArray(COUNT)

    fun start(dir: File, sampleRate: Int): Boolean {
        if (recording) return false
        rate = sampleRate
        dir.mkdirs()
        files = NAMES.map { File(dir, "stem_$it.wav") }
        rafs = try {
            files.map { f -> RandomAccessFile(f, "rw").apply { setLength(0); write(header(0)) } }
        } catch (e: Exception) { return false }
        ring = Array(COUNT) { FloatArray(rate * 10) }
        wPos = 0L; rPos = 0L
        java.util.Arrays.fill(peak, 0f)
        recording = true
        thread = Thread({ writer() }, "MarvPAD-Stems").also { it.start() }
        return true
    }

    /** Vom Mischer-Thread. */
    fun push(bufs: Array<FloatArray>, n: Int) {
        if (!recording) return
        val cap = ring[0].size
        if (wPos + n - rPos > cap) return
        val p0 = (wPos % cap).toInt()
        for (c in 0 until COUNT) {
            val src = bufs[c]
            val r = ring[c]
            var p = p0
            var pk = peak[c]
            for (i in 0 until n) {
                val v = src[i]
                r[p] = v
                if (v > pk) pk = v else if (-v > pk) pk = -v
                p++; if (p == cap) p = 0
            }
            peak[c] = pk
        }
        wPos += n
    }

    fun stop(): List<File> {
        if (!recording) return emptyList()
        recording = false
        try { thread?.join(5000) } catch (ignored: Exception) {}
        thread = null
        return files
    }

    private fun writer() {
        val chunk = ByteArray(rate * 2)
        val bytes = LongArray(COUNT)
        try {
            while (true) {
                val avail = wPos - rPos
                if (avail <= 0) {
                    if (!recording) break
                    Thread.sleep(30)
                    continue
                }
                val cap = ring[0].size
                val n = min(avail, (chunk.size / 2).toLong()).toInt()
                for (c in 0 until COUNT) {
                    var p = (rPos % cap).toInt()
                    var bi = 0
                    val r = ring[c]
                    for (i in 0 until n) {
                        val s = (r[p].coerceIn(-1f, 1f) * 32767f).toInt()
                        chunk[bi++] = (s and 0xFF).toByte()
                        chunk[bi++] = ((s shr 8) and 0xFF).toByte()
                        p++; if (p == cap) p = 0
                    }
                    rafs[c].write(chunk, 0, bi)
                    bytes[c] += bi
                }
                rPos += n
            }
        } catch (ignored: Exception) {
        } finally {
            for (c in 0 until COUNT) try {
                rafs[c].seek(0); rafs[c].write(header(bytes[c])); rafs[c].close()
            } catch (ignored: Exception) {}
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val d = dataBytes.coerceAtMost(0xFFFFFFF0L).toInt()
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + d)
        b.put("WAVE".toByteArray(Charsets.US_ASCII)); b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(d)
        return b.array()
    }
}
