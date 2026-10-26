package de.marween.looppad

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup als ZIP (in Dropbox, Google Drive … über die System-Dateiauswahl):
 *  files/…   App-Daten (Projekte, Aufnahmen, Bibliothek, Sammlung) – ohne KI-Modelle (lädt die App neu)
 *  prefs.json Einstellungen
 *  songs/…   optional die Cloud-Songs
 */
object Backup {
    private val SKIP = setOf("models")
    private val PREFS = arrayOf("marvpad", "beatmatch")

    fun write(ctx: Context, uri: Uri, withSongs: Boolean, cancel: () -> Boolean, prog: (Float, String) -> Unit) {
        val files = ArrayList<Pair<File, String>>()
        val root = ctx.filesDir
        root.listFiles()?.forEach { top ->
            if (top.name in SKIP) return@forEach
            top.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".part") }.forEach { files.add(it to "files/" + it.relativeTo(root).path) }
        }
        if (withSongs) {
            val sd = CloudImport.dir(ctx)
            sd.walkTopDown().filter { it.isFile }.forEach { files.add(it to "songs/" + it.relativeTo(sd).path) }
        }
        val total = files.sumOf { it.first.length() }.coerceAtLeast(1)
        var done = 0L
        val out = ctx.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Speicherort lässt sich nicht öffnen")
        ZipOutputStream(out.buffered(1 shl 16)).use { z ->
            // Einstellungen
            val pj = JSONObject()
            for (name in PREFS) {
                val o = JSONObject()
                for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                    val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "S"; else -> continue }
                    o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) org.json.JSONArray(v.toList()) else v))
                }
                pj.put(name, o)
            }
            z.putNextEntry(ZipEntry("prefs.json"))
            z.write(pj.toString().toByteArray())
            z.closeEntry()
            val buf = ByteArray(1 shl 16)
            for ((f, name) in files) {
                if (cancel()) throw CloudImport.Cancelled()
                z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { inp ->
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        z.write(buf, 0, r)
                        done += r
                    }
                }
                z.closeEntry()
                prog(done.toFloat() / total, name.substringAfterLast('/'))
            }
            z.putNextEntry(ZipEntry("marvpad-backup.txt"))
            z.write("Marv PAD Backup · ${files.size} Dateien · ${java.util.Date()}".toByteArray())
            z.closeEntry()
        }
        prog(1f, "${files.size} Dateien")
    }

    fun restore(ctx: Context, uri: Uri, cancel: () -> Boolean, prog: (Float, String) -> Unit) {
        val size = try { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (e: Exception) { -1L }
        val root = ctx.filesDir.canonicalFile
        val songs = CloudImport.dir(ctx).canonicalFile
        // erst prüfen, ob es ein Marv-PAD-Backup ist
        var ok = false
        ctx.contentResolver.openInputStream(uri)?.use { s -> ZipInputStream(s.buffered()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "prefs.json" || e.name == "marvpad-backup.txt") { ok = true; break } }
        } }
        if (!ok) throw IllegalStateException("Das ist kein Marv-PAD-Backup")
        // alte Daten weg (außer KI-Modellen)
        root.listFiles()?.forEach { if (it.name !in SKIP) it.deleteRecursively() }
        var done = 0L
        val counting = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Backup lässt sich nicht öffnen")
        ZipInputStream(object : java.io.FilterInputStream(counting.buffered(1 shl 16)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) done += r; return r }
        }).use { z ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val e = z.nextEntry ?: break
                if (cancel()) throw CloudImport.Cancelled()
                val name = e.name
                if (e.isDirectory) continue
                if (name == "prefs.json") {
                    applyPrefs(ctx, JSONObject(String(z.readBytes())))
                    continue
                }
                val target = when {
                    name.startsWith("files/") -> File(root, name.removePrefix("files/")).canonicalFile
                    name.startsWith("songs/") -> File(songs, name.removePrefix("songs/")).canonicalFile
                    else -> null
                } ?: continue
                // Schutz: nichts außerhalb der App-Ordner schreiben
                if (!target.path.startsWith(root.path + File.separator) && !target.path.startsWith(songs.path + File.separator)) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { o ->
                    while (true) {
                        val r = z.read(buf)
                        if (r < 0) break
                        o.write(buf, 0, r)
                    }
                }
                if (e.time > 0) target.setLastModified(e.time)
                prog(if (size > 0) done.toFloat() / size else -1f, name.substringAfterLast('/'))
            }
        }
        prog(1f, "fertig")
    }

    private fun applyPrefs(ctx: Context, j: JSONObject) {
        for (name in PREFS) {
            val o = j.optJSONObject(name) ?: continue
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "s" -> ed.putString(k, e.optString("v"))
                    "S" -> e.optJSONArray("v")?.let { a -> ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
                }
            }
            ed.commit()
        }
    }
}
