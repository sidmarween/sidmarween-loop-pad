package de.marween.looppad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Speichert und lädt Projekte und Mikrofon-Aufnahmen im App-Speicher. */
object Projects {

    class Info(val dir: File, val name: String, val date: Long, val hasSong: Boolean)

    private fun root(ctx: Context) = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun samplesDir(ctx: Context) = File(ctx.filesDir, "recordings").also { it.mkdirs() }

    fun list(ctx: Context): List<Info> {
        val dirs = root(ctx).listFiles() ?: return emptyList()
        return dirs.filter { File(it, "project.json").exists() }.mapNotNull { d ->
            try {
                val j = JSONObject(File(d, "project.json").readText())
                Info(d, j.optString("name", d.name), j.optLong("saved", d.lastModified()), File(d, "song.wav").exists())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.date }
    }

    fun save(ctx: Context, name: String, song: ShortArray?, songRate: Int): File {
        val dir = File(root(ctx), "p_" + System.currentTimeMillis())
        dir.mkdirs()
        val rate = Engine.sampleRate
        val pads = Engine.snapshotPads()
        val j = JSONObject()
        j.put("name", name)
        j.put("saved", System.currentTimeMillis())
        j.put("version", 1)
        j.put("sampleRate", rate)
        j.put("masterLen", Engine.masterFrames())
        j.put("bpm", Engine.fixedBpm.toDouble())
        j.put("fxType", Engine.fxType)
        j.put("fxTarget", Engine.fxTarget)
        val kn = JSONArray()
        for (v in Engine.knobs) kn.put(v.toDouble())
        j.put("knobs", kn)
        j.put("metronome", Engine.metronome)
        val pa = JSONArray()
        for (p in pads.indices) {
            val sv = pads[p]
            val o = JSONObject()
            o.put("oneShot", sv.oneShot)
            o.put("muted", sv.muted)
            o.put("slices", sv.slices)
            o.put("bars", sv.bars.toDouble())
            o.put("semi", sv.semi)
            val d = sv.data
            if (d != null) {
                val f = "pad_$p.wav"
                File(dir, f).outputStream().use { AudioFiles.writeWav(it, d, rate) }
                o.put("file", f)
            }
            pa.put(o)
        }
        j.put("pads", pa)
        val sq = JSONArray()
        for (row in Engine.seqSnapshot()) {
            val r = JSONArray()
            for (b in row) r.put(b.toDouble())
            sq.put(r)
        }
        j.put("seq", sq)
        if (song != null) File(dir, "song.wav").outputStream().use { AudioFiles.writeWav(it, song, songRate) }
        File(dir, "project.json").writeText(j.toString())
        return dir
    }

    fun load(dir: File) {
        val j = JSONObject(File(dir, "project.json").readText())
        val rate = Engine.sampleRate
        val srcRate = j.optInt("sampleRate", rate)
        val pa = j.getJSONArray("pads")
        val pads = Array(Engine.PADS) { p ->
            if (p < pa.length()) {
                val o = pa.getJSONObject(p)
                val f = o.optString("file", "")
                val data = if (f.isNotEmpty() && File(dir, f).exists()) AudioFiles.decodeFile(File(dir, f), rate, 60) else null
                Engine.PadSave(
                    data, o.optBoolean("oneShot", false), o.optBoolean("muted", false),
                    o.optInt("slices", 0), o.optDouble("bars", 1.0).toFloat(), o.optInt("semi", 0)
                )
            } else {
                Engine.PadSave(null, false, false)
            }
        }
        var master = j.optLong("masterLen", 0L)
        if (srcRate != rate && master > 0L) master = master * rate / srcRate
        val sq = j.optJSONArray("seq")
        val pattern = if (sq != null) Array(sq.length()) { p ->
            val r = sq.getJSONArray(p)
            FloatArray(r.length()) {
                when (val o = r.get(it)) {
                    is Boolean -> if (o) 1f else 0f
                    is Number -> o.toFloat()
                    else -> 0f
                }
            }
        } else null
        Engine.restore(pads, master, j.optDouble("bpm", 0.0).toFloat(), pattern)
        Engine.selectFx(j.optInt("fxType", 0))
        val kn = j.optJSONArray("knobs")
        if (kn != null) for (k in 0 until min(4, kn.length())) Engine.knobs[k] = kn.getDouble(k).toFloat()
        Engine.metronome = j.optBoolean("metronome", false)
        Engine.fxTarget = j.optInt("fxTarget", -1)
    }

    fun delete(dir: File) {
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- Mikrofon-Aufnahmen

    fun saveSample(ctx: Context, pad: Int, data: FloatArray, rate: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        File(samplesDir(ctx), "Aufnahme_${stamp}_Pad${pad + 1}.wav").outputStream().use { AudioFiles.writeWav(it, data, rate) }
    }

    fun listSamples(ctx: Context): List<File> =
        samplesDir(ctx).listFiles()?.filter { it.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun sampleTitle(f: File): String {
        val secs = (f.length() - 44).coerceAtLeast(0) / 2f / Engine.sampleRate
        val date = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(f.lastModified()))
        val pad = Regex("Pad(\\d+)").find(f.name)?.groupValues?.get(1)
        return String.format(Locale.GERMANY, "🎙 %s%s · %.1fs", date, if (pad != null) " · Pad $pad" else "", secs)
    }
}
