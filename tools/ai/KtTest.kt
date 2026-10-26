import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import de.marween.looppad.SpleeterCore
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

fun readF32(f: File): FloatArray {
    val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(b.remaining()).also { b.get(it) }
}

fun main(args: Array<String>) {
    val dir = args[0]
    val sig = readF32(File("sig.bin"))
    val n = sig.size / 2
    val ref = readF32(File(args[1]))
    val env = OrtEnvironment.getEnvironment()
    val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    val sessions = SpleeterCore.STEMS.map { env.createSession("$dir/$it.fp16.onnx", opts) }
    val inName = sessions[0].inputNames.first()
    val shape = longArrayOf(2, 1, SpleeterCore.T.toLong(), SpleeterCore.F.toLong())
    var tInf = 0L
    val runner = SpleeterCore.Runner { st, x ->
        val t0 = System.nanoTime()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { inp ->
            sessions[st].run(mapOf(inName to inp)).use { r ->
                val o = r[0] as OnnxTensor
                val fb = o.floatBuffer
                val out = FloatArray(fb.remaining()); fb.get(out)
                tInf += System.nanoTime() - t0
                out
            }
        }
    }
    val total = SpleeterCore.frames(n)
    val ob = SpleeterCore.OUT_BINS
    val got = FloatArray(total * 3 * ob)
    val t0 = System.nanoTime()
    SpleeterCore.separate(n, { c, i -> sig[c * n + i] }, runner, { t, v, d, b ->
        System.arraycopy(v, 0, got, (t * 3 + 0) * ob, ob)
        System.arraycopy(d, 0, got, (t * 3 + 1) * ob, ob)
        System.arraycopy(b, 0, got, (t * 3 + 2) * ob, ob)
    })
    val all = (System.nanoTime() - t0) / 1e9
    var maxD = 0f; var sum = 0.0
    check(ref.size == got.size) { "Größe ${ref.size} vs ${got.size}" }
    for (i in ref.indices) { val d = abs(ref[i] - got[i]); sum += d; if (d > maxD) maxD = d }
    println("Kotlin: gesamt %.2f s, davon Netz %.2f s, für %.0f s Audio".format(all, tInf / 1e9, n / 44100.0))
    println("Abweichung zur Python-Referenz: max %.5f  mittel %.6f".format(maxD, sum / ref.size))
    check(maxD < 0.02f) { "Kotlin weicht zu stark ab" }
    println("OK")
}
