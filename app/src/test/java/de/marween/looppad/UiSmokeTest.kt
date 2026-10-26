package de.marween.looppad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rauchtest der Oberfläche: zeichnet alle Designs, Panels und Lektionsschritte und tippt/wischt zufällig
 * über die Ansichten. Jeder Absturz wird mit Stelle gesammelt und am Ende gemeldet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiSmokeTest {
    private val errors = ArrayList<String>()
    private val ctx: android.content.Context get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            val sw = java.io.StringWriter(); t.printStackTrace(java.io.PrintWriter(sw))
            val lines = sw.toString().lines().filter { it.contains("de.marween") || it == sw.toString().lines().first() }.take(8)
            errors.add("[$what] " + lines.joinToString("\n    "))
        }
    }

    private fun sized(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun draw(v: View, w: Int, h: Int) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
    }

    private var t0 = SystemClock.uptimeMillis()
    private fun touch(v: View, action: Int, x: Float, y: Float) {
        t0 += 16
        val e = MotionEvent.obtain(t0, t0, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun tap(v: View, x: Float, y: Float) { touch(v, MotionEvent.ACTION_DOWN, x, y); touch(v, MotionEvent.ACTION_UP, x, y) }

    private fun monkey(name: String, v: View, w: Int, h: Int, n: Int, seed: Long) {
        val r = java.util.Random(seed)
        for (i in 0 until n) {
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            safe("$name Tipp #$i (${x.toInt()},${y.toInt()})") {
                when (r.nextInt(6)) {
                    0 -> { touch(v, MotionEvent.ACTION_DOWN, x, y); touch(v, MotionEvent.ACTION_MOVE, x + r.nextFloat() * 200 - 100, y + r.nextFloat() * 200 - 100); touch(v, MotionEvent.ACTION_UP, x, y) }
                    1 -> { touch(v, MotionEvent.ACTION_DOWN, x, y); t0 += 700; touch(v, MotionEvent.ACTION_UP, x, y) }
                    else -> tap(v, x, y)
                }
            }
            if (i % 15 == 0) safe("$name Zeichnen nach #$i") { draw(v, w, h) }
            if (errors.size > 60) return
        }
    }

    @Test
    fun padView() {
        val w = 1080; val h = 2340
        val pv = PadView(ctx)
        sized(pv, w, h)
        for (th in 0 until PadView.DESIGN_NAMES.size) safe("Design $th") { pv.theme = th; draw(pv, w, h) }
        pv.theme = PadView.CLAUDE
        monkey("Pads", pv, w, h, 1200, 1)
        for ((name, open) in listOf<Pair<String, () -> Unit>>(
            "Ghost" to { pv.openGhost() }, "Loop Station" to { pv.openStation() }, "Launcher" to { pv.openLauncher() }, "Slicer" to { pv.openSlicer() })) {
            safe("$name öffnen") { open(); draw(pv, w, h) }
            monkey(name, pv, w, h, 300, name.hashCode().toLong())
            safe("$name zeichnen") { draw(pv, w, h) }
            // schließen: Zurück-Logik ist in der Activity – Panel per Tipp oben rechts (✕) versuchen
            safe("$name schließen") { tap(pv, w - 60f, 230f); tap(pv, w - 60f, 260f) }
        }
        for (i in pv.lessonList().indices) {
            safe("Lektion $i starten") { pv.startLesson(i); draw(pv, w, h) }
            for (s in 0 until pv.testLessonSteps()) safe("Lektion $i Schritt $s") { pv.testLessonGo(s); draw(pv, w, h); tap(pv, w / 2f, h / 2f); draw(pv, w, h) }
            safe("Lektion $i beenden") { pv.endLesson() }
        }
        // Querformat
        sized(pv, h, w)
        safe("Pads quer") { draw(pv, h, w) }
        monkey("Pads quer", pv, h, w, 300, 7)
        report()
    }

    @Test
    fun djView() {
        for ((w, h) in listOf(1080 to 2340, 2340 to 1080)) {
            val dv = DjView(ctx)
            sized(dv, w, h)
            for (s in DjView.SKINS.indices) safe("DJ-Skin $s ${w}x$h") { dv.skinIdx = s; draw(dv, w, h) }
            for (m in 0..5) { safe("DJ Pad-Modus $m") { dv.testPadMode(m); draw(dv, w, h) }; monkey("DJ ${w}x$h Modus $m", dv, w, h, 200, (m * 31 + w).toLong()) }
            safe("DJ Pads groß") { dv.setPadsBig(true); draw(dv, w, h) }
            monkey("DJ groß ${w}x$h", dv, w, h, 300, 99)
            safe("DJ-Lektion") { dv.startLesson(); draw(dv, w, h) }
            for (s in 0 until dv.testLessonSteps()) safe("DJ-Lektion Schritt $s") { dv.testLessonGo(s); draw(dv, w, h) }
            safe("DJ Ghost") { dv.openGhost(); draw(dv, w, h) }
            monkey("DJ Ghost", dv, w, h, 200, 5)
        }
        report()
    }

    private fun report() {
        if (errors.isEmpty()) return
        // gleiche Ursachen zusammenfassen
        val byCause = errors.groupBy { it.substringAfter("] ").lines().take(3).joinToString("|") }
        val msg = byCause.entries.joinToString("\n\n") { (_, list) -> "${list.size}× " + list.first() }
        println("UI-FEHLER:\n$msg")
        fail("${byCause.size} verschiedene Fehler:\n$msg")
    }
}
