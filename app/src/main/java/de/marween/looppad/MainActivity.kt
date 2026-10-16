package de.marween.looppad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var pads: PadView
    private var askedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        pads = PadView(this)
        pads.onMessageTap = { onMessageTapped() }
        pads.onPadMenu = { p -> showPadMenu(p) }
        pads.onFxList = { showFxList() }
        pads.onDesignMenu = { showDesigns() }
        pads.onTempo = { showTempo() }
        pads.onRecMenu = { showRecMenu() }
        pads.onSongStopped = { finishSong() }
        pads.theme = getSharedPreferences("marvpad", MODE_PRIVATE).getInt("design", PadView.GLOW)
        setContentView(pads)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val c = window.insetsController ?: return
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    override fun onStart() {
        super.onStart()
        ensureStarted()
    }

    override fun onStop() {
        Engine.stop()
        super.onStop()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureStarted() {
        if (!hasMic()) {
            pads.message = "Loop Pad braucht das Mikrofon.\nTippe hier, um es zu erlauben."
            if (!askedOnce) {
                askedOnce = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
            return
        }
        Engine.start(this)
        val err = Engine.error
        pads.message = if (err != null) "Audio-Fehler:\n$err\nTippe für einen neuen Versuch." else null
    }

    private fun onMessageTapped() {
        if (hasMic()) {
            ensureStarted()
            return
        }
        if (askedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Dauerhaft abgelehnt -> App-Einstellungen öffnen
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ensureStarted()
        } else {
            pads.message = "Ohne Mikrofon keine Loops.\nTippe hier, um es zu erlauben."
        }
    }

    // ---------------------------------------------------------------- Import / Export

    private var menuPad = -1
    private var exportData: FloatArray? = null
    private var exportRate = 48000

    private fun showPadMenu(p: Int) {
        menuPad = p
        val has = Engine.hasLoop(p)
        val shot = Engine.isOneShot(p)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(if (shot) "Modus: ⚡ ONE SHOT  →  auf ∞ LOOP umschalten" else "Modus: ∞ LOOP  →  auf ⚡ ONE SHOT umschalten")
        actions.add { Engine.setPadMode(p, !shot) }
        labels.add("Aus Bibliothek laden")
        actions.add { showLibrary(p) }
        labels.add("Audio importieren (WAV / MP3)")
        actions.add { pickImport() }
        if (has) {
            labels.add("Als WAV exportieren")
            actions.add { pickExport(p) }
            labels.add("Löschen")
            actions.add { Engine.clear(p) }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Pad ${p + 1}")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showLibrary(p: Int) {
        val names = SampleLibrary.samples.map { it.title }.toTypedArray()
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Bibliothek → Pad ${p + 1}")
            .setItems(names) { _, which ->
                val smp = SampleLibrary.samples[which]
                val rate = Engine.sampleRate
                Thread {
                    try {
                        val data = AudioFiles.decodeAsset(this, smp.file, rate, Engine.MAX_SECONDS)
                        Engine.padOneShot[p] = smp.oneShot
                        Engine.importLoop(p, data)
                        runOnUiThread { toast("Pad ${p + 1}: ${smp.title}") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------------------------------------------------------------- Aufnahme: Pattern oder Song

    private var pendingSong: ShortArray? = null
    private var pendingSongRate = 48000

    private fun showRecMenu() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Aufnehmen")
            .setItems(arrayOf(
                "● Pattern aufnehmen  (16 Schritte, ⚡-Pads)",
                "● Song aufnehmen  (alles, was du hörst)"
            )) { _, which ->
                if (which == 0) {
                    Engine.seqToggleRec()
                } else {
                    Engine.songStart()
                    toast("Song-Aufnahme läuft – REC oder STOP beendet sie")
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun finishSong() {
        val rate = Engine.sampleRate
        val data = Engine.songStop() ?: return
        pendingSong = data
        pendingSongRate = rate
        val secs = data.size / rate
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(String.format(Locale.GERMANY, "Song aufgenommen (%d:%02d)", secs / 60, secs % 60))
            .setMessage("Als WAV-Datei speichern?")
            .setPositiveButton("Speichern") { _, _ -> pickSongExport() }
            .setNegativeButton("Verwerfen") { _, _ -> pendingSong = null }
            .setCancelable(false)
            .show()
    }

    private fun pickSongExport() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "MarvPAD_Song_$stamp.wav")
        }
        try {
            startActivityForResult(i, REQ_SONG)
        } catch (e: Exception) {
            toast("Kein Dateimanager gefunden")
        }
    }

    // ---------------------------------------------------------------- Master-Tempo

    private val tapTimes = ArrayList<Long>()

    private fun showTempo() {
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val start = if (Engine.currentBpm() > 0f) Engine.currentBpm() else 120f
        var bpm = start.coerceIn(60f, 200f)
        val big = TextView(this).apply {
            textSize = 40f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 120, 30))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val info = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(180, 180, 180))
            text = if (Engine.fixedBpm > 0f) "Festes Tempo · 4/4" else "Automatisch: erster Loop bestimmt das Tempo"
        }
        val bar = SeekBar(this).apply { max = 140 }
        fun show() {
            big.text = String.format(Locale.GERMANY, "%.0f BPM", bpm)
            if (bar.progress != (bpm - 60f).toInt()) bar.progress = (bpm - 60f).toInt()
        }
        fun setTempo(v: Float) {
            bpm = v.coerceIn(60f, 200f)
            Engine.setBpm(bpm)
            info.text = "Festes Tempo · 4/4 – gilt für neue Aufnahmen, Sequenzer und Metronom"
            show()
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setTempo(60f + progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(btn("−") { setTempo(bpm - 1f) })
        row.addView(btn("TAP") {
            val now = System.currentTimeMillis()
            if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2000) tapTimes.clear()
            tapTimes.add(now)
            while (tapTimes.size > 6) tapTimes.removeAt(0)
            if (tapTimes.size >= 2) {
                val avg = (tapTimes.last() - tapTimes.first()).toFloat() / (tapTimes.size - 1)
                setTempo(60000f / avg)
            }
        })
        row.addView(btn("+") { setTempo(bpm + 1f) })
        val metro = CheckBox(this).apply {
            text = "Metronom"
            isChecked = Engine.metronome
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> Engine.metronome = c }
        }
        box.addView(big)
        box.addView(info)
        box.addView(bar)
        box.addView(row)
        box.addView(metro)
        show()
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Master-Tempo")
            .setView(box)
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Automatisch") { _, _ -> Engine.setAutoTempo() }
            .show()
    }

    private fun showDesigns() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Design wählen")
            .setSingleChoiceItems(PadView.DESIGN_NAMES, pads.theme) { d, which ->
                pads.theme = which
                getSharedPreferences("marvpad", MODE_PRIVATE).edit().putInt("design", which).apply()
                d.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showFxList() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Effekt wählen")
            .setSingleChoiceItems(Fx.NAMES, Engine.fxType) { d, which ->
                Engine.selectFx(which)
                pads.fxChosen()
                d.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/mpeg", "audio/mp3", "audio/*")
            )
        }
        try {
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: Exception) {
            toast("Kein Dateimanager gefunden")
        }
    }

    private fun pickExport(p: Int) {
        val data = Engine.loopData(p) ?: return
        exportData = data
        exportRate = Engine.sampleRate
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "MarvPAD_Pad${p + 1}_$stamp.wav")
        }
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            toast("Kein Dateimanager gefunden")
        }
    }

    @Deprecated("Activity-Result-API wird hier bewusst nicht genutzt (keine AndroidX-Abhängigkeit)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val pad = menuPad
        when (requestCode) {
            REQ_IMPORT -> {
                if (pad < 0) return
                toast("Importiere …")
                val rate = Engine.sampleRate
                Thread {
                    try {
                        val samples = AudioFiles.decode(this, uri, rate, Engine.MAX_SECONDS)
                        Engine.importLoop(pad, samples)
                        runOnUiThread { toast("Pad ${pad + 1}: Import fertig") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Import fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
            }
            REQ_SONG -> {
                val d = pendingSong ?: return
                pendingSong = null
                val rate = pendingSongRate
                toast("Speichere Song …")
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { AudioFiles.writeWav(it, d, rate) }
                            ?: throw IllegalStateException("Datei nicht schreibbar")
                        runOnUiThread { toast("Song gespeichert") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Speichern fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
            }
            REQ_EXPORT -> {
                val d = exportData ?: return
                exportData = null
                val rate = exportRate
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { AudioFiles.writeWav(it, d, rate) }
                            ?: throw IllegalStateException("Datei nicht schreibbar")
                        runOnUiThread { toast("Loop exportiert") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Export fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_MIC = 7
        private const val REQ_IMPORT = 8
        private const val REQ_EXPORT = 9
        private const val REQ_SONG = 10
    }
}
