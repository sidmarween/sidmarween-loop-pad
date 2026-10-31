package de.marween.looppad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Bildschirmfotos aller Designs (für die Sichtprüfung auf überlappende Schrift). Ablage: build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ShotTest {
    private val ctx: android.content.Context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val dir = File("build/shots").apply { mkdirs() }

    private fun shot(v: View, w: Int, h: Int, name: String, cropH: Int = h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try { v.draw(Canvas(bmp)) } catch (t: Throwable) { println("FEHLER $name: $t") }
        val out = Bitmap.createBitmap(bmp, 0, 0, w, cropH)
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pads(tag: String) {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        Engine.setBpm(128f)
        val pv = PadView(ctx)
        for (th in PadView.DESIGN_NAMES.indices) { pv.theme = th; shot(pv, w, h, "pad_${tag}_%02d".format(th), h / 3) }
        Engine.metronome = true
        pv.theme = 0; shot(pv, w, h, "pad_${tag}_metro", h / 3)
        Engine.metronome = false
        shot(pv, h, w, "pad_${tag}_quer", w / 2)
        // eigene Anordnung: Sequenzer oben, Regler aus, Auswahl-Leiste unter den Pads
        pv.theme = PadView.CLAUDE
        pv.applyLayout("5,2,4,3,1,0|0")
        shot(pv, w, h, "lay_${tag}_custom")
        pv.applyLayout("0,1,2,3,4,5|2")
        shot(pv, w, h, "lay_${tag}_noxy")
        pv.applyLayout(null)
        shot(pv, w, h, "lay_${tag}_std")
        pv.startLayoutEdit()
        shot(pv, w, h, "lay_${tag}_edit")
        pv.endLayoutEdit()
    }

    private fun dj(tag: String) {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val rate = 44100; val len = rate * 30
        val data = ShortArray(len * 2) { ((Math.sin(it * 0.01) * 8000) * (if ((it / 2) % (rate / 2) < 3000) 3 else 1)).toInt().coerceIn(-32000, 32000).toShort() }
        for (k in 0..1) {
            val d = Dj.decks[k]
            Dj.load(d, Dj.Track(data, 2, len, rate), if (k == 0) "Ein ziemlich langer Songtitel – Extended Club Mix" else "Track B", if (k == 0) 128f else 174.5f)
            d.key = 7
        }
        val dv = DjView(ctx)
        for (s in DjView.SKINS.indices) { dv.skinIdx = s; shot(dv, w, h, "dj_${tag}_%02d".format(s), h / 2) }
        dv.skinIdx = 15; shot(dv, h, w, "dj_${tag}_quer")
    }

    private fun song(tag: String) {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        Engine.setBpm(124f)
        val pv = PadView(ctx)
        Engine.arrClear(); Engine.songBars = 0
        pv.openSongEditor()
        shot(pv, w, h, "song_${tag}_leer")
        InstState.drumApplyPreset(0); InstState.drumOn = true
        InstState.acidRandom()
        InstState.synRandom()
        pv.songEd.scaffold()
        shot(pv, w, h, "song_${tag}_geruest")
        shot(pv, h, w, "song_${tag}_quer")
        pv.songEd.close()
        pv.openSongBuilder()
        shot(pv, w, h, "bau_${tag}_teile")
        shot(pv, h, w, "bau_${tag}_quer")
        Engine.arrClear(); Engine.songBars = 0
        shot(pv, w, h, "bau_${tag}_leer")
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun normal() { pads("n"); dj("n"); song("n") }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun schmal() { pads("s"); dj("s") }
}
