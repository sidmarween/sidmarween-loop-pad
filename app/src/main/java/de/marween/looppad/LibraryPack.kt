package de.marween.looppad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bibliotheks-Paket (.zip): eigene Samples (★) mit Kategorie/Titel/Modus und optional
 * die DJ-Bibliothek mit Tempo, Raster und Cue-Punkten. Inhalt:
 *   marvpad-library.json  – Beschreibung
 *   samples/…wav, dj/…wav – Audio
 */
object LibraryPack {
    private const val INDEX = "marvpad-library.json"

    class Result(val samples: Int, val dj: Int)

    fun export(ctx: Context, out: OutputStream, samples: List<Projects.UserSample>, includeDj: Boolean): Result {
        val dj = if (includeDj) DjLibrary.rawEntries(ctx) else emptyList()
        val idx = JSONObject()
        idx.put("format", "marvpad-library")
        idx.put("version", 1)
        val sa = JSONArray()
        ZipOutputStream(out.buffered()).use { z ->
            samples.forEachIndexed { i, s ->
                val path = "samples/" + String.format(java.util.Locale.ROOT, "%03d_", i + 1) + safe(s.title) + ".wav"
                z.putNextEntry(ZipEntry(path))
                s.file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                sa.put(JSONObject().apply {
                    put("file", path); put("cat", s.category); put("title", s.title); put("oneShot", s.oneShot)
                    if (s.stem != null) put("stem", s.stem)
                })
            }
            val da = JSONArray()
            dj.forEachIndexed { i, (o, f) ->
                val path = "dj/" + String.format(java.util.Locale.ROOT, "%03d_", i + 1) + safe(o.optString("title")) + ".wav"
                z.putNextEntry(ZipEntry(path))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                da.put(JSONObject(o.toString()).apply { put("file", path); remove("id") })
            }
            idx.put("samples", sa)
            idx.put("dj", da)
            z.putNextEntry(ZipEntry(INDEX))
            z.write(idx.toString(1).toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return Result(samples.size, dj.size)
    }

    /**
     * Liest ein Paket. Die Beschreibung steht am Ende der ZIP-Datei, deshalb werden die
     * Audio-Dateien zuerst zwischengespeichert und danach mit ihren Angaben übernommen.
     * Fremde ZIP-Dateien ohne Beschreibung: alle WAV-Dateien landen in [fallbackCat].
     */
    fun import(ctx: Context, input: InputStream, fallbackCat: String): Result {
        val tmp = java.io.File(ctx.cacheDir, "libimport_" + System.currentTimeMillis()).apply { mkdirs() }
        try {
            var idx: JSONObject? = null
            val wavs = LinkedHashMap<String, java.io.File>()
            ZipInputStream(input.buffered()).use { z ->
                var e = z.nextEntry
                var n = 0
                while (e != null) {
                    if (!e.isDirectory) {
                        val name = e.name
                        if (name == INDEX) {
                            idx = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        } else if (name.lowercase().endsWith(".wav") && !name.contains("..")) {
                            val f = java.io.File(tmp, "f${n++}.wav")
                            f.outputStream().buffered().use { z.copyTo(it) }
                            wavs[name] = f
                        }
                    }
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
            var ns = 0
            var nd = 0
            val ix = idx
            if (ix != null) {
                val sa = ix.optJSONArray("samples") ?: JSONArray()
                for (i in 0 until sa.length()) {
                    val o = sa.optJSONObject(i) ?: continue
                    val f = wavs[o.optString("file")] ?: continue
                    f.inputStream().use {
                        Projects.importUserSampleFile(ctx, o.optString("cat").ifEmpty { fallbackCat }, o.optString("title").ifEmpty { "Sample" },
                            o.optBoolean("oneShot", true), o.optString("stem").ifEmpty { null }, it)
                    }
                    ns++
                }
                val da = ix.optJSONArray("dj") ?: JSONArray()
                for (i in 0 until da.length()) {
                    val o = da.optJSONObject(i) ?: continue
                    val f = wavs[o.optString("file")] ?: continue
                    f.inputStream().use { DjLibrary.importRaw(ctx, o, it) }
                    nd++
                }
            } else {
                for ((name, f) in wavs) {
                    val title = name.substringAfterLast('/').substringBeforeLast('.')
                    val secs = (f.length() - 44).coerceAtLeast(0) / 2.0 / 44100.0
                    f.inputStream().use { Projects.importUserSampleFile(ctx, fallbackCat, title, secs < 3.0, null, it) }
                    ns++
                }
            }
            return Result(ns, nd)
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun isZip(head: ByteArray): Boolean = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _.-]"), "_").trim().take(48).ifEmpty { "sample" }
}
