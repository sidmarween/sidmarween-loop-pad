package de.marween.looppad

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DJ-Sammlung: Tracks (als Datei-Verweise mit dauerhafter Leseberechtigung) mit ihrer Analyse –
 * Tempo, Beat-Raster, Tonart, Lautheit, Energie, Cue-Punkte – dazu Crates (Ordner für Sets)
 * und der Verlauf der gespielten Tracks.
 */
object TrackDb {
    class Track(
        val id: String,
        val uri: String,
        var title: String,
        var bpm: Float = 0f,
        /** Erster Schlag in Sekunden. */
        var grid: Double = 0.0,
        var key: Int = -1,
        var keyConf: Float = 0f,
        var lufs: Float = Float.NaN,
        /** Energie 1..10 (0 = unbekannt). */
        var energy: Int = 0,
        var seconds: Double = 0.0,
        var cue: Double = -1.0,
        val hot: DoubleArray = DoubleArray(8) { -1.0 },
        val crates: MutableSet<String> = LinkedHashSet(),
        var plays: Int = 0,
        var lastPlayed: Long = 0L,
        val added: Long = System.currentTimeMillis(),
        var analyzed: Boolean = false,
        var barOffset: Int = 0,
        var phraseOff: Int = 0,
        var missing: Boolean = false
    )

    class Play(val id: String, val title: String, val at: Long, val bpm: Float, val key: Int)

    private val tracks = ArrayList<Track>()
    private val crateNames = ArrayList<String>()
    private val history = ArrayList<Play>()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "djtracks.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val j = JSONObject(f.readText())
            val a = j.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val h = o.optJSONArray("hot")
                val t = Track(
                    o.optString("id"), o.optString("uri"), o.optString("title"),
                    o.optDouble("bpm", 0.0).toFloat(), o.optDouble("grid", 0.0), o.optInt("key", -1),
                    o.optDouble("keyConf", 0.0).toFloat(), o.optDouble("lufs", Double.NaN).toFloat(), o.optInt("energy", 0),
                    o.optDouble("sec", 0.0), o.optDouble("cue", -1.0), DoubleArray(8) { k -> h?.optDouble(k, -1.0) ?: -1.0 },
                    LinkedHashSet<String>().apply { o.optJSONArray("crates")?.let { c -> for (k in 0 until c.length()) add(c.optString(k)) } },
                    o.optInt("plays", 0), o.optLong("last", 0L), o.optLong("added", 0L), o.optBoolean("ok", false),
                    o.optInt("barOff", 0), o.optInt("phraseOff", 0)
                )
                tracks.add(t)
            }
            j.optJSONArray("crates")?.let { c -> for (k in 0 until c.length()) crateNames.add(c.optString(k)) }
            j.optJSONArray("history")?.let { hh ->
                for (k in 0 until hh.length()) {
                    val o = hh.optJSONObject(k) ?: continue
                    history.add(Play(o.optString("id"), o.optString("title"), o.optLong("at"), o.optDouble("bpm", 0.0).toFloat(), o.optInt("key", -1)))
                }
            }
        } catch (ignored: Exception) {}
    }

    @Synchronized
    fun save(ctx: Context) {
        try {
            val a = JSONArray()
            for (t in tracks) {
                val o = JSONObject()
                o.put("id", t.id); o.put("uri", t.uri); o.put("title", t.title)
                o.put("bpm", t.bpm.toDouble()); o.put("grid", t.grid); o.put("key", t.key); o.put("keyConf", t.keyConf.toDouble())
                if (!t.lufs.isNaN()) o.put("lufs", t.lufs.toDouble())
                o.put("energy", t.energy); o.put("sec", t.seconds); o.put("cue", t.cue)
                o.put("hot", JSONArray().apply { t.hot.forEach { put(it) } })
                o.put("crates", JSONArray().apply { t.crates.forEach { put(it) } })
                o.put("plays", t.plays); o.put("last", t.lastPlayed); o.put("added", t.added); o.put("ok", t.analyzed)
                o.put("barOff", t.barOffset); o.put("phraseOff", t.phraseOff)
                a.put(o)
            }
            val j = JSONObject()
            j.put("tracks", a)
            j.put("crates", JSONArray().apply { crateNames.forEach { put(it) } })
            j.put("history", JSONArray().apply {
                for (p in history.takeLast(500)) put(JSONObject().apply { put("id", p.id); put("title", p.title); put("at", p.at); put("bpm", p.bpm.toDouble()); put("key", p.key) })
            })
            val tmp = File(ctx.filesDir, "djtracks.json.tmp")
            tmp.writeText(j.toString())
            tmp.renameTo(file(ctx))
        } catch (ignored: Exception) {}
    }

    @Synchronized fun all(): List<Track> = ArrayList(tracks)
    @Synchronized fun crates(): List<String> = ArrayList(crateNames)
    @Synchronized fun history(): List<Play> = ArrayList(history)
    @Synchronized fun byId(id: String?): Track? = if (id == null) null else tracks.firstOrNull { it.id == id }
    @Synchronized fun byUri(uri: String): Track? = tracks.firstOrNull { it.uri == uri }

    /** Track aufnehmen (falls noch nicht da). Liefert ihn und ob er neu ist. */
    @Synchronized
    fun add(uri: Uri, title: String): Pair<Track, Boolean> {
        byUri(uri.toString())?.let { return it to false }
        val t = Track("t" + System.currentTimeMillis() + "_" + (System.nanoTime() % 100000), uri.toString(), title)
        tracks.add(t)
        return t to true
    }

    @Synchronized fun remove(t: Track) { tracks.remove(t) }

    @Synchronized
    fun addCrate(name: String) { if (name.isNotBlank() && name !in crateNames) crateNames.add(name) }

    @Synchronized
    fun deleteCrate(name: String) {
        crateNames.remove(name)
        for (t in tracks) t.crates.remove(name)
    }

    @Synchronized
    fun renameCrate(old: String, new: String) {
        val i = crateNames.indexOf(old)
        if (i < 0 || new.isBlank()) return
        crateNames[i] = new
        for (t in tracks) if (t.crates.remove(old)) t.crates.add(new)
    }

    @Synchronized
    fun played(t: Track) {
        t.plays++
        t.lastPlayed = System.currentTimeMillis()
        history.add(Play(t.id, t.title, t.lastPlayed, t.bpm, t.key))
    }

    @Synchronized fun clearHistory() { history.clear() }

    // ---------------------------------------------------------------- Ordner einlesen

    private val AUDIO = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus", "aif", "aiff", "wma")

    /** Alle Audiodateien eines freigegebenen Ordners (rekursiv, max. 3000). */
    fun scanTree(ctx: Context, tree: Uri): List<Pair<Uri, String>> {
        val out = ArrayList<Pair<Uri, String>>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val stack = ArrayDeque<String>()
        stack.add(root)
        while (stack.isNotEmpty() && out.size < 3000) {
            val doc = stack.removeLast()
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
            try {
                ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(id)
                        else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO) {
                            out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to name.substringBeforeLast('.'))
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }
        return out
    }

    // ---------------------------------------------------------------- Analyse

    /** Analysiert einen Track vollständig (Tempo, Raster, Tonart, Lautheit, Energie). */
    fun analyze(ctx: Context, t: Track) {
        val m = Dj.decodeMono(ctx, Uri.parse(t.uri), Dj.MAX_SECONDS)
        val w = Dj.waveOf(m.data, 1, m.len, m.rate)
        val nameBpm = Warp.bpmFromName(t.title)
        val det = Dj.detectBpm(w.onset, w.fps)
        val bpm = nameBpm ?: det.first
        val gridF = Dj.findGrid(w.onset, w.fps, bpm) * (m.rate / w.fps)
        val (k, kc) = Keys.detect(m.data, 1, m.len, m.rate)
        t.bpm = bpm
        t.grid = gridF / m.rate
        t.key = k
        t.keyConf = kc
        t.lufs = w.lufs
        t.seconds = m.len.toDouble() / m.rate
        // Energie: Lautheit, Anschlagsdichte, Bass- und Höhenanteil, Tempo
        var on = 0.0
        for (v in w.onset) on += v
        val dens = on / max(1.0, t.seconds)
        var hi = 0.0
        var lo = 0.0
        for (i in w.highs.indices) { hi += (w.highs[i].toInt() and 0xFF); lo += (w.lows[i].toInt() and 0xFF) }
        val nb = max(1, w.highs.size)
        hi /= nb * 255.0
        lo /= nb * 255.0
        fun nrm(v: Double, a: Double, b: Double) = ((v - a) / (b - a)).coerceIn(0.0, 1.0)
        val e = 0.38 * nrm(if (w.lufs.isNaN()) -14.0 else w.lufs.toDouble(), -20.0, -6.0) + 0.27 * nrm(dens, 2.0, 12.0) +
            0.15 * nrm(hi, 0.08, 0.35) + 0.1 * nrm(lo, 0.25, 0.6) + 0.1 * nrm(bpm.toDouble(), 85.0, 145.0)
        t.energy = (1 + e * 9).roundToInt().coerceIn(1, 10)
        if (t.cue < 0) t.cue = t.grid
        // Takt-Eins und Phrasen wie im Deck
        val tmp = Dj.Deck(9)
        tmp.peaks = w.peaks; tmp.lows = w.lows; tmp.bpm = bpm; tmp.grid = gridF; tmp.len = m.len; tmp.srcRate = m.rate
        Dj.analyzePhrase(tmp)
        t.barOffset = tmp.barOffset
        t.phraseOff = tmp.phraseOff
        t.analyzed = true
        t.missing = false
    }

    // ---------------------------------------------------------------- Vorschläge

    /** Tempo-Abstand in Prozent (halbes/doppeltes Tempo zählt als gleich). */
    fun bpmDiff(a: Float, b: Float): Double {
        if (a <= 0f || b <= 0f) return 99.0
        var best = 99.0
        for (k in floatArrayOf(0.5f, 1f, 2f)) best = min(best, abs(ln((b * k / a).toDouble())) * 100)
        return best
    }

    /**
     * Wie gut passt [t] als nächster Track nach einem Deck mit Tempo [bpm], Tonart [key] und
     * Energie [energy]? Kleiner = besser; null = passt nicht (Tempo zu weit weg oder Tonart beißt).
     */
    fun score(t: Track, bpm: Float, key: Int, energy: Int, wantUp: Boolean = false): Double? {
        if (!t.analyzed || t.bpm <= 0f) return null
        val bd = bpmDiff(bpm, t.bpm)
        if (bd > 6.5) return null
        val kc = Keys.compat(key, t.key)
        if (kc == 3) return null
        var s = bd * 1.0 + when (kc) { 0 -> 0.0; 1 -> 1.0; 2 -> 3.0; else -> 4.0 }
        if (energy > 0 && t.energy > 0) {
            val de = t.energy - energy
            s += if (wantUp) (if (de < 0) -de * 1.5 else abs(de - 1) * 0.6) else abs(de) * 0.8
        }
        // frisch gespielte nach hinten
        if (t.lastPlayed > 0 && System.currentTimeMillis() - t.lastPlayed < 3 * 3600_000L) s += 6.0
        return s
    }

    fun suggestions(bpm: Float, key: Int, energy: Int, excludeId: String?, wantUp: Boolean = false, limit: Int = 30): List<Pair<Track, Double>> =
        all().filter { it.id != excludeId }.mapNotNull { t -> score(t, bpm, key, energy, wantUp)?.let { t to it } }.sortedBy { it.second }.take(limit)
}
