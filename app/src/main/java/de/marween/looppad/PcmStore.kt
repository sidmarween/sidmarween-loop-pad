package de.marween.looppad

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speicher sparen bei Songs auf Pads: großes Audio, das nur selten gebraucht wird (Original für das Neu-Dehnen
 * nach Tempowechseln, Undo-Stände ganzer Songs), liegt als 16 Bit auf dem Handy-Speicher statt im Arbeitsspeicher.
 * Gelesen wird stückweise ([copy]) – so muss auch beim Neu-Dehnen nie der ganze Song auf einmal in den Speicher.
 */
interface PcmSource {
    /** Länge in Samples (Bereiche dahinter liefern Stille). */
    val size: Int
    /** Samples [from, to) als neues Array (außerhalb → 0). */
    fun copy(from: Int, to: Int): FloatArray
}

/** Ausschnitt eines Arrays im Speicher. */
class ArraySource(val a: FloatArray, val off: Int = 0, override val size: Int = a.size - off) : PcmSource {
    /** true, wenn das ganze Array ohne Versatz gemeint ist. */
    val whole: Boolean get() = off == 0 && size == a.size

    override fun copy(from: Int, to: Int): FloatArray {
        val n = maxOf(0, to - from)
        val out = FloatArray(n)
        val s = maxOf(from, 0)
        val e = minOf(to, size, a.size - off)
        if (e > s) System.arraycopy(a, off + s, out, s - from, e - s)
        return out
    }
}

/** 16-Bit-Audio in einer Datei (App-Ordner „spill“, wird beim Start geleert). */
class DiskPcm private constructor(val file: File, override val size: Int, private val stored: Int) : PcmSource {

    override fun copy(from: Int, to: Int): FloatArray {
        val n = maxOf(0, to - from)
        val out = FloatArray(n)
        val s = maxOf(from, 0)
        val e = minOf(to, stored)
        if (e <= s) return out
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(s * 2L)
                val chunk = ByteArray(1 shl 16)
                var pos = s
                while (pos < e) {
                    val cnt = minOf((e - pos) * 2, chunk.size)
                    raf.readFully(chunk, 0, cnt)
                    val bb = ByteBuffer.wrap(chunk, 0, cnt).order(ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until cnt / 2) out[pos - from + k] = bb.short / 32768f
                    pos += cnt / 2
                }
            }
        } catch (ignored: Exception) {
            // Datei weg (Speicher geleert): Stille statt Absturz
        }
        return out
    }

    @Suppress("ProtectedInFinal")
    protected fun finalize() {
        try { file.delete() } catch (ignored: Throwable) {}
    }

    companion object {
        @Volatile private var dir: File? = null
        private var counter = 0L

        /** Beim App-Start: Ordner festlegen und alte Reste löschen. */
        @Synchronized
        fun init(base: File) {
            if (dir != null) return
            val d = File(base, "spill")
            d.deleteRecursively()
            d.mkdirs()
            dir = d
        }

        /** [a][from, from+len) als 16 Bit ablegen; logische Länge [size] (Rest = Stille). null = ging nicht. */
        fun write(a: FloatArray, from: Int = 0, len: Int = a.size - from, size: Int = len): DiskPcm? {
            val d = dir ?: return null
            val f = synchronized(this) { File(d, "pcm_${System.nanoTime()}_${counter++}.raw") }
            return try {
                val n = maxOf(0, minOf(len, a.size - from))
                f.outputStream().buffered(1 shl 16).use { o ->
                    val bb = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) {
                        if (bb.remaining() < 2) { o.write(bb.array(), 0, bb.position()); bb.clear() }
                        val v = a[from + i]
                        bb.putShort((if (v >= 1f) 32767 else if (v <= -1f) -32768 else (v * 32767f).toInt()).toShort())
                    }
                    o.write(bb.array(), 0, bb.position())
                }
                DiskPcm(f, maxOf(size, n), n)
            } catch (e: Exception) {
                f.delete()
                null
            }
        }
    }
}
