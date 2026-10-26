package de.marween.looppad

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * Songs aus Online-Speichern holen:
 *  - über einen geteilten Link (Dropbox – auch ganze Ordner, die kommen als ZIP –, Google Drive, OneDrive, direkte MP3/WAV-Links)
 *  - oder über die System-Dateiauswahl (Dropbox-, Drive-, OneDrive-App), dann wird die Datei kopiert.
 * Die Dateien landen dauerhaft auf dem Handy (Musik/Cloud im App-Ordner), damit sie auch offline und
 * nach einem Neustart noch funktionieren – Cloud-Apps geben den Zugriff sonst oft nur kurz frei.
 */
object CloudImport {
    private val AUDIO = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus", "aif", "aiff", "wma", "mp4")
    private const val MAX_BYTES = 1_500L * 1024 * 1024

    class Cancelled : Exception("abgebrochen")

    fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "music")
        return File(base, "Cloud").apply { mkdirs() }
    }

    fun isAudioName(n: String) = n.substringAfterLast('.', "").lowercase() in AUDIO

    /** Geteilte Links in direkte Download-Links umwandeln. */
    fun directUrl(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http")) u = "https://$u"
        val uri = Uri.parse(u)
        val host = uri.host?.lowercase() ?: return u
        return when {
            host.endsWith("dropbox.com") || host.endsWith("dropboxusercontent.com") -> {
                // ?dl=0 → ?dl=1 (Ordner kommen dann als ZIP)
                val b = uri.buildUpon().clearQuery()
                for (k in uri.queryParameterNames) if (k != "dl" && k != "raw") b.appendQueryParameter(k, uri.getQueryParameter(k))
                b.appendQueryParameter("dl", "1").build().toString()
            }
            host.endsWith("drive.google.com") || host.endsWith("docs.google.com") -> {
                val id = Regex("/d/([A-Za-z0-9_-]{10,})").find(u)?.groupValues?.get(1) ?: uri.getQueryParameter("id")
                if (id != null) "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t" else u
            }
            host.endsWith("1drv.ms") || host.endsWith("onedrive.live.com") -> {
                // OneDrive-Freigabe: „u!“ + Base64 des Links → direkter Inhalt über die Freigabe-API
                val b64 = android.util.Base64.encodeToString(u.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
                "https://api.onedrive.com/v1.0/shares/u!$b64/root/content"
            }
            else -> u
        }
    }

    /**
     * Link herunterladen. Ein ZIP (z. B. Dropbox-Ordner) wird entpackt, nur Audiodateien bleiben.
     * [progress]: (0..1 oder -1 = unbekannt, Text). Liefert die gespeicherten Audiodateien.
     */
    fun download(ctx: Context, link: String, cancel: () -> Boolean, progress: (Float, String) -> Unit): List<File> {
        var url = URL(directUrl(link))
        var con: HttpURLConnection? = null
        // Weiterleitungen selbst folgen (auch http ↔ https, Dropbox/Drive leiten mehrfach weiter)
        for (hop in 0 until 8) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MarvPAD")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Weiterleitung ohne Ziel")
                url = URL(url, loc)
                c.disconnect()
                continue
            }
            if (code == 404) throw IllegalStateException("Nicht gefunden (404) – ist der Link öffentlich geteilt?")
            if (code == 401 || code == 403) throw IllegalStateException("Kein Zugriff ($code) – den Link bitte für „jeden mit dem Link“ freigeben")
            if (code !in 200..299) throw IllegalStateException("Server antwortet mit $code")
            con = c
            break
        }
        val c = con ?: throw IllegalStateException("Zu viele Weiterleitungen")
        try {
            val type = c.contentType?.lowercase() ?: ""
            val len = c.contentLengthLong
            if (len > MAX_BYTES) throw IllegalStateException("Datei zu groß (${len / 1048576} MB)")
            var name = fileNameOf(c.getHeaderField("Content-Disposition")) ?: URLDecoder.decode(url.path.substringAfterLast('/'), "UTF-8")
            if (type.startsWith("text/html")) throw IllegalStateException("Der Link führt zu einer Webseite, nicht zu einer Datei – bei Google Drive: Freigabe „Jeder mit dem Link“, bei großen Dateien lieber die Drive-App über „Aus App wählen“")
            val input = java.io.BufferedInputStream(c.inputStream, 1 shl 16)
            input.mark(8)
            val magic = ByteArray(4)
            val got = input.read(magic)
            input.reset()
            val zip = got >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() || type.contains("zip") || name.lowercase().endsWith(".zip")
            if (zip) return unzip(ctx, input, len, cancel, progress)
            if (name.isBlank() || !name.contains('.')) name = (if (name.isBlank()) "Download" else name) + extOf(type)
            if (!isAudioName(name) && !type.startsWith("audio/")) throw IllegalStateException("„$name“ ist keine Audiodatei")
            val f = unique(dir(ctx), name)
            copy(input, f, len, cancel) { p -> progress(p, name) }
            return listOf(f)
        } finally {
            c.disconnect()
        }
    }

    /** Datei aus einer Cloud-App (System-Dateiauswahl) dauerhaft aufs Handy kopieren. */
    fun copyFromUri(ctx: Context, uri: Uri, cancel: () -> Boolean, progress: (Float, String) -> Unit): File {
        var name = "Track.mp3"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getString(0)?.let { name = it }
                    if (!cur.isNull(1)) size = cur.getLong(1)
                }
            }
        } catch (ignored: Exception) {}
        // schon einmal geholt (gleicher Name, gleiche Größe)? dann nicht nochmal kopieren
        val existing = File(dir(ctx), name)
        if (existing.exists() && size > 0 && existing.length() == size) return existing
        val f = unique(dir(ctx), name)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Datei lässt sich nicht öffnen")
        input.use { copy(it, f, size, cancel) { p -> progress(p, name) } }
        return f
    }

    private fun unzip(ctx: Context, input: InputStream, len: Long, cancel: () -> Boolean, progress: (Float, String) -> Unit): List<File> {
        val out = ArrayList<File>()
        val counting = object : java.io.FilterInputStream(input) {
            var n = 0L
            override fun read(): Int { val r = super.read(); if (r >= 0) n++; return r }
            override fun read(b: ByteArray, off: Int, l: Int): Int { val r = super.read(b, off, l); if (r > 0) n += r; return r }
        }
        ZipInputStream(counting).use { z ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val e = z.nextEntry ?: break
                if (cancel()) throw Cancelled()
                val nm = e.name.substringAfterLast('/')
                if (e.isDirectory || nm.startsWith(".") || nm.startsWith("._") || !isAudioName(nm)) continue
                val f = unique(dir(ctx), nm)
                try {
                    f.outputStream().use { o ->
                        while (true) {
                            val r = z.read(buf)
                            if (r < 0) break
                            o.write(buf, 0, r)
                            if (cancel()) throw Cancelled()
                            progress(if (len > 0) counting.n.toFloat() / len else -1f, "${out.size + 1}: $nm")
                        }
                    }
                    out.add(f)
                } catch (t: Throwable) {
                    f.delete()
                    throw t
                }
            }
        }
        if (out.isEmpty()) throw IllegalStateException("Im Ordner/ZIP sind keine Audiodateien")
        return out
    }

    private fun copy(input: InputStream, f: File, len: Long, cancel: () -> Boolean, progress: (Float) -> Unit) {
        var done = 0L
        var last = 0L
        try {
            f.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                    done += r
                    if (done > MAX_BYTES) throw IllegalStateException("Datei zu groß")
                    if (cancel()) throw Cancelled()
                    if (done - last > 256 * 1024) { last = done; progress(if (len > 0) done.toFloat() / len else -1f) }
                }
            }
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
        progress(1f)
    }

    private fun fileNameOf(cd: String?): String? {
        if (cd == null) return null
        Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { return URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8") }
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun extOf(type: String) = when {
        type.contains("mpeg") || type.contains("mp3") -> ".mp3"
        type.contains("wav") -> ".wav"
        type.contains("flac") -> ".flac"
        type.contains("ogg") -> ".ogg"
        type.contains("mp4") || type.contains("m4a") || type.contains("aac") -> ".m4a"
        else -> ".mp3"
    }

    private fun unique(d: File, name0: String): File {
        val name = name0.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "Track.mp3" }
        var f = File(d, name)
        var k = 2
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) { f = File(d, "$base ($k)" + if (ext.isNotEmpty()) ".$ext" else ""); k++ }
        return f
    }
}
