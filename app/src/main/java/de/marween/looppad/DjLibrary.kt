package de.marween.looppad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DJ-Bibliothek: geschnittene Samples/MP3-Ausschnitte als WAV mit Tempo, Beat-Raster,
 * Cue-Punkt (Startpunkt) und bis zu 4 Hot Cues (Sekunden).
 */
object DjLibrary {
    class Item(
        val id: String,
        val file: File,
        val title: String,
        val bpm: Float,
        val grid: Double,
        val cue: Double,
        val hot: DoubleArray,
        val seconds: Double
    )

    private fun dir(ctx: Context): File = File(ctx.filesDir, "djlib").apply { mkdirs() }
    private fun index(ctx: Context): File = File(dir(ctx), "index.json")

    private fun readIndex(ctx: Context): JSONArray = try {
        val f = index(ctx)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    fun list(ctx: Context): List<Item> {
        val a = readIndex(ctx)
        val out = ArrayList<Item>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val f = File(dir(ctx), o.optString("file"))
            if (!f.exists()) continue
            val h = o.optJSONArray("hot")
            out.add(Item(
                o.optString("id"), f, o.optString("title"), o.optDouble("bpm", 120.0).toFloat(),
                o.optDouble("grid", 0.0), o.optDouble("cue", 0.0),
                DoubleArray(4) { k -> h?.optDouble(k, -1.0) ?: -1.0 }, o.optDouble("sec", 0.0)
            ))
        }
        return out.reversed()
    }

    /** Speichert Audio (mono) mit Cue-Punkten (Sekunden ab Anfang des Audios). */
    fun add(ctx: Context, data: FloatArray, rate: Int, title: String, bpm: Float, grid: Double, cue: Double, hot: DoubleArray): Item {
        val id = "dj_" + System.currentTimeMillis()
        val f = File(dir(ctx), "$id.wav")
        f.outputStream().buffered().use { AudioFiles.writeWav(it, data, rate) }
        val a = readIndex(ctx)
        val o = JSONObject()
        o.put("id", id)
        o.put("file", f.name)
        o.put("title", title)
        o.put("bpm", bpm.toDouble())
        o.put("grid", grid)
        o.put("cue", cue)
        o.put("hot", JSONArray().apply { hot.forEach { put(it) } })
        o.put("sec", data.size.toDouble() / rate)
        a.put(o)
        index(ctx).writeText(a.toString())
        return Item(id, f, title, bpm, grid, cue, hot, data.size.toDouble() / rate)
    }

    /** Cue-Punkte und Tempo eines Eintrags aktualisieren (z. B. im DJ-Modus neu gesetzt). */
    fun update(ctx: Context, id: String, bpm: Float, grid: Double, cue: Double, hot: DoubleArray) {
        val a = readIndex(ctx)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            o.put("bpm", bpm.toDouble())
            o.put("grid", grid)
            o.put("cue", cue)
            o.put("hot", JSONArray().apply { hot.forEach { put(it) } })
        }
        index(ctx).writeText(a.toString())
    }

    fun delete(ctx: Context, id: String) {
        val a = readIndex(ctx)
        val n = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == id) File(dir(ctx), o.optString("file")).delete() else n.put(o)
        }
        index(ctx).writeText(n.toString())
    }

    /** Rohdaten aller Einträge (für den Export als Paket). */
    fun rawEntries(ctx: Context): List<Pair<JSONObject, File>> {
        val a = readIndex(ctx)
        val out = ArrayList<Pair<JSONObject, File>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val f = File(dir(ctx), o.optString("file"))
            if (f.exists()) out.add(o to f)
        }
        return out
    }

    /** Übernimmt einen Eintrag aus einem Paket (neue ID, Audio aus [input]). */
    @Synchronized
    fun importRaw(ctx: Context, src: JSONObject, input: java.io.InputStream) {
        val id = "dj_" + System.currentTimeMillis() + "_" + (System.nanoTime() % 100000)
        val f = File(dir(ctx), "$id.wav")
        f.outputStream().buffered().use { input.copyTo(it) }
        val o = JSONObject(src.toString())
        o.put("id", id)
        o.put("file", f.name)
        val a = readIndex(ctx)
        a.put(o)
        index(ctx).writeText(a.toString())
    }
}
