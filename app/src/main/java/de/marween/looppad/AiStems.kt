package de.marween.looppad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * KI-Stems im DJ-Deck: Spleeter 4 Stems (neuronales Netz von Deezer) statt der schnellen Spektral-Trennung.
 *  - Modelle (~80 MB) werden einmalig von GitHub geladen (Release „ai-models“ des App-Repos).
 *  - Pro Track läuft die Trennung im Hintergrund (eins nach dem anderen), das Ergebnis ist eine fein aufgelöste
 *    Stem-Karte (21,5 Hz × 23 ms), die der Live-Filter im Deck nutzt – Regler, Keylock, Loops, Scratchen funktionieren weiter.
 *  - Ergebnisse werden zwischengespeichert: derselbe Track lädt beim nächsten Mal sofort mit KI-Stems.
 */
object AiStems {
    private val URLS = listOf(
        "https://github.com/sidmarween/sidmarween-loop-pad/releases/download/ai-models/%s.fp16.onnx",
        "https://huggingface.co/Best-Practice/spleeter-4stems-onnx/resolve/main/%s.fp16.onnx"
    )
    private const val CACHE_MAX = 24
    private const val MAGIC = 0x4B495354 // „KIST“

    /** Automatisch beim Laden eines Tracks rechnen (Einstellung). */
    @Volatile var auto = true

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "KI-Stems").apply { priority = Thread.MIN_PRIORITY + 1 }
    }
    private var sessions: List<OrtSession>? = null
    private var lastUse = 0L

    fun dir(ctx: Context) = File(ctx.filesDir, "models/spleeter4").apply { mkdirs() }
    private fun file(ctx: Context, stem: String) = File(dir(ctx), "$stem.fp16.onnx")

    fun ready(ctx: Context): Boolean = SpleeterCore.STEMS.all { file(ctx, it).length() > 1_000_000 }

    fun sizeMb(ctx: Context): Int = (SpleeterCore.STEMS.sumOf { file(ctx, it).length() } / 1048576).toInt()

    fun deleteModels(ctx: Context) {
        closeSessions()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Modelle herunterladen. [progress] 0..1 und Text. */
    fun download(ctx: Context, cancel: () -> Boolean, progress: (Float, String) -> Unit) {
        val stems = SpleeterCore.STEMS
        for ((i, st) in stems.withIndex()) {
            val f = file(ctx, st)
            if (f.length() > 1_000_000) continue
            var last: Exception? = null
            var ok = false
            for (u in URLS) {
                try {
                    fetch(String.format(u, st), File(f.path + ".part"), cancel) { p -> progress((i + p) / stems.size, "Modell ${i + 1}/4: $st") }
                    File(f.path + ".part").renameTo(f)
                    ok = true
                    break
                } catch (e: CloudImport.Cancelled) {
                    File(f.path + ".part").delete()
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            if (!ok) throw IllegalStateException("Modell „$st“ nicht ladbar: ${last?.message ?: "?"}")
        }
        progress(1f, "fertig")
    }

    private fun fetch(url0: String, out: File, cancel: () -> Boolean, progress: (Float) -> Unit) {
        var url = URL(url0)
        for (hop in 0 until 8) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "MarvPAD")
            val code = c.responseCode
            if (code in 300..399) {
                url = URL(url, c.getHeaderField("Location") ?: throw IllegalStateException("Weiterleitung ohne Ziel"))
                c.disconnect()
                continue
            }
            if (code !in 200..299) { c.disconnect(); throw IllegalStateException("HTTP $code") }
            try {
                val len = c.contentLengthLong
                var done = 0L
                c.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = inp.read(buf)
                            if (r < 0) break
                            o.write(buf, 0, r)
                            done += r
                            if (cancel()) throw CloudImport.Cancelled()
                            if (len > 0) progress(done.toFloat() / len)
                        }
                    }
                }
                if (len > 0 && done < len) throw IllegalStateException("Download unvollständig")
                if (done < 1_000_000) throw IllegalStateException("Datei zu klein")
                return
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("Zu viele Weiterleitungen")
    }

    // ---------------------------------------------------------------- Trennung

    /** Für ein Deck anstoßen (läuft nach den anderen Aufträgen). */
    fun request(ctx: Context, d: Dj.Deck) {
        val app = ctx.applicationContext
        if (!ready(app)) return
        val gen = d.aiGen
        val data = d.data ?: return
        val ch = d.ch
        val len = d.len
        val rate = d.srcRate
        val key = keyOf(d.title, data, ch, len, rate)
        // schon gerechnet? → sofort
        loadCache(app, key, rate)?.let { m ->
            if (gen == d.aiGen && d.data === data) apply(d, m)
            return
        }
        d.aiProg = 0f
        exec.execute {
            if (gen != d.aiGen || d.data !== data) { if (gen == d.aiGen) d.aiProg = -1f; return@execute }
            try {
                val m = run(app, data, ch, len, rate, { gen != d.aiGen || d.data !== data }) { p -> if (gen == d.aiGen) d.aiProg = p }
                if (m != null && gen == d.aiGen && d.data === data) {
                    apply(d, m)
                    saveCache(app, key, m)
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
            } finally {
                if (gen == d.aiGen) d.aiProg = -1f
                lastUse = System.currentTimeMillis()
                // Modelle nach einer Minute ohne Auftrag freigeben (Speicher)
                scheduleClose()
            }
        }
    }

    @Volatile var lastError: String? = null

    /** KI-Karte ins Deck – die schnelle Trennung wird dann nicht mehr gebraucht. */
    private fun apply(d: Dj.Deck, m: DeckStems.Mask) {
        d.stemGen++
        d.stemProg = -1f
        d.stemMask = m
        d.aiProg = -1f
    }

    private fun scheduleClose() {
        Thread {
            try { Thread.sleep(60_000) } catch (ignored: InterruptedException) {}
            exec.execute { if (System.currentTimeMillis() - lastUse >= 59_000) closeSessions() }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun closeSessions() {
        sessions?.forEach { try { it.close() } catch (ignored: Exception) {} }
        sessions = null
    }

    @Synchronized
    private fun sessions(ctx: Context): List<OrtSession> {
        sessions?.let { return it }
        val env = OrtEnvironment.getEnvironment()
        val threads = max(1, min(4, Runtime.getRuntime().availableProcessors() - 2))
        val s = SpleeterCore.STEMS.map { st ->
            val o = OrtSession.SessionOptions()
            o.setIntraOpNumThreads(threads)
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(file(ctx, st).path, o)
        }
        sessions = s
        return s
    }

    /** Trennt ein Deck-Audio; null = abgebrochen. */
    private fun run(ctx: Context, data: ShortArray, ch: Int, len: Int, rate: Int, cancel: () -> Boolean, progress: (Float) -> Unit): DeckStems.Mask? {
        val ss = sessions(ctx)
        val env = OrtEnvironment.getEnvironment()
        val inName = ss[0].inputNames.first()
        val shape = longArrayOf(2, 1, SpleeterCore.T.toLong(), SpleeterCore.F.toLong())
        val (n, reader) = SpleeterCore.resampledReader(rate, len) { c, i ->
            if (i < 0 || i >= len) 0f else data[i * ch + (if (ch == 2) c else 0)] / 32768f
        }
        val frames = SpleeterCore.frames(n)
        val nb = SpleeterCore.OUT_BINS
        val out = ByteArray(frames * nb * 3)
        val runner = SpleeterCore.Runner { st, x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { inp ->
                ss[st].run(mapOf(inName to inp)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
        fun q(v: Float): Byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        val ok = SpleeterCore.separate(n, reader, runner, { t, v, d, b ->
            val o = t * nb * 3
            for (j in 0 until nb) {
                out[o + j * 3] = q(d[j])
                out[o + j * 3 + 1] = q(b[j])
                out[o + j * 3 + 2] = q(v[j])
            }
        }, progress, cancel)
        if (!ok) return null
        return maskOf(frames, out, rate)
    }

    /**
     * Pad-Audio (mono, [sr]) in 4 fertige Spuren trennen: [vocals, drums, bass, other], gleich lang wie [data].
     * Läuft in der KI-Warteschlange (nach evtl. laufenden Deck-Aufträgen) und wartet auf das Ergebnis. null = abgebrochen.
     */
    fun separatePad(ctx: Context, data: FloatArray, sr: Int, cancel: () -> Boolean, progress: (Float) -> Unit): Array<FloatArray>? {
        val app = ctx.applicationContext
        val fut = exec.submit(java.util.concurrent.Callable {
            try {
                val ss = sessions(app)
                val env = OrtEnvironment.getEnvironment()
                val inName = ss[0].inputNames.first()
                val shape = longArrayOf(2, 1, SpleeterCore.T.toLong(), SpleeterCore.F.toLong())
                val runner = SpleeterCore.Runner { st, x ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { inp ->
                        ss[st].run(mapOf(inName to inp)).use { r ->
                            val fb = (r[0] as OnnxTensor).floatBuffer
                            FloatArray(fb.remaining()).also { fb.get(it) }
                        }
                    }
                }
                // auf 44,1 kHz (Modell), danach zurück
                val x44 = resample(data, sr, SpleeterCore.SR, -1)
                val st = SpleeterCore.separateMono(x44, runner, progress, cancel) ?: return@Callable null
                Array(4) { k -> resample(st[k], SpleeterCore.SR, sr, data.size).also { st[k] = FloatArray(0) } }
            } finally {
                lastUse = System.currentTimeMillis()
                scheduleClose()
            }
        })
        return fut.get()
    }

    /** Lineare Abtastraten-Wandlung; [outLen] < 0 = passend berechnen. */
    private fun resample(x: FloatArray, from: Int, to: Int, outLen: Int): FloatArray {
        if (from == to && (outLen < 0 || outLen == x.size)) return x
        val n = if (outLen >= 0) outLen else (x.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        val last = x.size - 1
        return FloatArray(n) { i ->
            val p = i * step
            val i0 = p.toInt()
            if (i0 >= last) x[last.coerceAtLeast(0)] else { val f = (p - i0).toFloat(); x[i0] + (x[i0 + 1] - x[i0]) * f }
        }
    }

    private fun maskOf(frames: Int, data: ByteArray, rate: Int) = DeckStems.Mask(
        frames, SpleeterCore.HOP * rate.toDouble() / SpleeterCore.SR, data, SpleeterCore.OUT_BINS,
        SpleeterCore.SR.toDouble() / SpleeterCore.N_FFT * 2, SpleeterCore.CENTER_SHIFT.toDouble() / SpleeterCore.HOP, true)

    // ---------------------------------------------------------------- Zwischenspeicher

    private fun keyOf(title: String, data: ShortArray, ch: Int, len: Int, rate: Int): String {
        var h = 1125899906842597L
        val step = max(1, data.size / 4096)
        var i = 0
        while (i < data.size) { h = 31 * h + data[i]; i += step }
        return java.lang.Long.toHexString(h xor (len.toLong() shl 20) xor (rate.toLong() shl 4) xor ch.toLong() xor title.hashCode().toLong())
    }

    private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "aistems").apply { mkdirs() }

    private fun saveCache(ctx: Context, key: String, m: DeckStems.Mask) {
        try {
            val f = File(cacheDir(ctx), "$key.bin")
            DataOutputStream(java.util.zip.DeflaterOutputStream(f.outputStream().buffered(1 shl 16), java.util.zip.Deflater(1))).use { o ->
                o.writeInt(MAGIC); o.writeInt(m.frames); o.writeInt(m.nb); o.write(m.data)
            }
            val all = cacheDir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: return
            for (old in all.drop(CACHE_MAX)) old.delete()
        } catch (ignored: Exception) {}
    }

    private fun loadCache(ctx: Context, key: String, rate: Int): DeckStems.Mask? {
        val f = File(cacheDir(ctx), "$key.bin")
        if (!f.exists()) return null
        return try {
            DataInputStream(java.util.zip.InflaterInputStream(f.inputStream().buffered(1 shl 16))).use { i ->
                if (i.readInt() != MAGIC) return null
                val frames = i.readInt()
                val nb = i.readInt()
                if (nb != SpleeterCore.OUT_BINS) return null
                val d = ByteArray(frames * nb * 3)
                i.readFully(d)
                f.setLastModified(System.currentTimeMillis())
                maskOf(frames, d, rate)
            }
        } catch (e: Exception) { null }
    }
}
