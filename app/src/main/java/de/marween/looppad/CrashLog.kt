package de.marween.looppad

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Absturz-Bericht: fängt unerwartete Fehler, schreibt Ursache + Ort in eine Datei und zeigt sie beim
 * nächsten Start (zum Teilen/Kopieren) – damit sich Fehler, die nur auf dem Handy auftreten, finden lassen.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "absturz.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ver = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (x: Exception) { "?" }
                file(app).writeText(
                    "Marv PAD $ver · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Thread: ${t.name} · ${java.util.Date()}\n\n" + sw.toString().take(12000)
                )
            } catch (ignored: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** Bericht vom letzten Absturz (und löschen), oder null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try { f.readText().also { f.delete() } } catch (e: Exception) { null }
    }
}
