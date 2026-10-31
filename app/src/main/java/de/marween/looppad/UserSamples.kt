package de.marween.looppad

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Eigene Samples für Instrument-Stimmen: 909 (Stimmen 0..10, Schlüssel "drum:k") und EUKLID (Spuren 0..3, "euk:k").
 * Mono in der Engine-Rate, im App-Ordner als 16-Bit gespeichert – bleiben nach dem Neustart erhalten.
 */
object UserSamples {
    private val data = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun drum(v: Int): FloatArray? = data["drum:$v"]
    fun euk(l: Int): FloatArray? = data["euk:$l"]
    fun name(key: String): String? = names[key]
    fun has(key: String) = data.containsKey(key)

    private fun dir(ctx: Context) = File(ctx.filesDir, "voice_samples").apply { mkdirs() }

    /** Sample setzen: auf höchstens 4 s kürzen, Stille am Anfang weg, normalisieren, Ende weich ausblenden. */
    fun set(ctx: Context, key: String, x: FloatArray, sr: Int, name: String) {
        var s = 0
        var pk = 1e-6f
        for (v in x) pk = maxOf(pk, kotlin.math.abs(v))
        while (s < x.size - 1 && kotlin.math.abs(x[s]) < pk * 0.02f) s++
        s = maxOf(0, s - sr / 2000)
        val len = minOf(x.size - s, sr * 4)
        val y = FloatArray(maxOf(1, len))
        val g = 0.9f / pk
        for (i in 0 until len) y[i] = x[s + i] * g
        val f = minOf(len / 4, sr / 100)
        for (i in 0 until f) y[len - 1 - i] *= i.toFloat() / f
        data[key] = y
        names[key] = name
        try {
            val bb = ByteBuffer.allocate(y.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in y) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            File(dir(ctx), "$key.pcm".replace(':', '_')).writeBytes(bb.array())
            File(dir(ctx), "$key.name".replace(':', '_')).writeText(name)
        } catch (ignored: Exception) {}
    }

    fun remove(ctx: Context, key: String) {
        data.remove(key); names.remove(key)
        try {
            File(dir(ctx), "$key.pcm".replace(':', '_')).delete()
            File(dir(ctx), "$key.name".replace(':', '_')).delete()
        } catch (ignored: Exception) {}
    }

    /** Beim Start laden. */
    fun load(ctx: Context) {
        try {
            for (f in dir(ctx).listFiles() ?: return) {
                if (!f.name.endsWith(".pcm")) continue
                val key = f.name.removeSuffix(".pcm").replaceFirst('_', ':')
                val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val y = FloatArray(b.remaining() / 2) { b.short / 32768f }
                data[key] = y
                names[key] = File(dir(ctx), f.name.removeSuffix(".pcm") + ".name").let { if (it.exists()) it.readText() else "Sample" }
            }
        } catch (ignored: Exception) {}
    }
}
