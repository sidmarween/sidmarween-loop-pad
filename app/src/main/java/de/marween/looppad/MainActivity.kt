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
import android.widget.EditText
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
        pads.onDesignMenu = { showMainMenu() }
        pads.onPatternStopped = { finishPattern() }
        Engine.onRecorded = { pad, data, rate ->
            Thread {
                try {
                    Projects.saveSample(applicationContext, pad, data, rate)
                } catch (e: Exception) {
                    runOnUiThread { toast("Aufnahme konnte nicht gesichert werden") }
                }
            }.start()
        }
        pads.onTempo = { showTempo() }
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        prefs.getString("padSource", null)?.split(",")?.forEachIndexed { i, v ->
            if (i < Engine.PADS) Engine.padSource[i] = v.toIntOrNull() ?: 0
        }
        Engine.liveBars = prefs.getInt("liveBars", 0)
        Engine.bounce = prefs.getBoolean("bounce", true)
        Engine.onBounced = { pad, muted ->
            runOnUiThread {
                toast("Bounce → Pad ${pad + 1} · stumm: " + muted.joinToString(", ") { "Pad ${it + 1}" } + " (antippen holt sie zurück)")
            }
        }
        pads.onRecSettingsChanged = {
            saveRecPrefs()
            val sum = Engine.sourceSummary(PadView.VISIBLE_PADS)
            toast(
                (when (sum) { 1 -> "Eingang aller Pads: Wave (App-Audio inkl. Effekte)"; 2 -> "Eingang: gemischt"; else -> "Eingang aller Pads: Mikrofon" }) +
                    " · " + when {
                    Engine.liveBars < 0 -> "TIPPEN: 1. Tipp startet, 2. Tipp beendet – Loop rastet auf 1–32 Takte ein"
                    Engine.liveBars > 0 -> "LIVE: leeres Pad antippen, Aufnahme ab nächstem Takt (${Engine.liveBars} Takt)"
                    else -> "HALTEN: Aufnahme solange der Finger liegt – Loop rastet auf 1–32 Takte ein"
                }
            )
        }
        pads.onRecMenu = { showRecMenu() }
        pads.onSongStopped = { finishSong() }
        pads.theme = getSharedPreferences("marvpad", MODE_PRIVATE).getInt("design", PadView.NOIR)
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

    private fun saveRecPrefs() {
        getSharedPreferences("marvpad", MODE_PRIVATE).edit()
            .putString("padSource", Engine.padSource.joinToString(","))
            .putInt("liveBars", Engine.liveBars)
            .apply()
    }

    private fun showPadMenu(p: Int) {
        menuPad = p
        val has = Engine.hasLoop(p)
        val shot = Engine.isOneShot(p)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val wave = Engine.padSource[p] == 1
        labels.add(if (wave) "Eingang: 〰 Wave (App-Audio)  →  auf 🎙 Mikrofon" else "Eingang: 🎙 Mikrofon  →  auf 〰 Wave (App-Audio)")
        actions.add {
            Engine.padSource[p] = if (wave) 0 else 1
            saveRecPrefs()
            toast(if (wave) "Pad ${p + 1} nimmt vom Mikrofon auf" else "Pad ${p + 1} nimmt das App-Audio inkl. Effekte auf")
        }
        labels.add(if (shot) "Modus: ⚡ ONE SHOT  →  auf ∞ LOOP umschalten" else "Modus: ∞ LOOP  →  auf ⚡ ONE SHOT umschalten")
        actions.add { Engine.setPadMode(p, !shot) }
        labels.add("Aus Bibliothek laden")
        actions.add { showLibrary(p) }
        labels.add("Audio importieren (WAV / MP3)")
        actions.add { pickImport() }
        if (has) {
            labels.add("〰 Wave-Editor (schneiden, Slices)")
            actions.add { pads.openEditor(p) }
            labels.add("🎹 Tastatur")
            actions.add { pads.openKeyboard(p) }
        }
        val fxOn = Engine.padFx[p]
        labels.add(if (fxOn) "🎛 Effekt: AN  →  Pad ohne Effekt" else "🎛 Effekt: AUS  →  Pad durch den Effekt")
        actions.add {
            Engine.padFx[p] = !fxOn
            toast(if (fxOn) "Pad ${p + 1}: ohne Effekt" else "Pad ${p + 1}: mit Effekt")
        }
        labels.add("🎛 Effekt nur auf dieses Pad")
        actions.add {
            Engine.setAllFx(false)
            Engine.padFx[p] = true
            toast("Effekt wirkt nur auf Pad ${p + 1}")
        }
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
        val recs = Projects.listSamples(this)
        val cats = ArrayList<String>()
        if (recs.isNotEmpty()) cats.add("🎙 Meine Aufnahmen (${recs.size})")
        for (c in SampleLibrary.categories) cats.add("$c (${SampleLibrary.inCategory(c).size})")
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Bibliothek → Pad ${p + 1}")
            .setItems(cats.toTypedArray()) { _, which ->
                if (recs.isNotEmpty() && which == 0) {
                    AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("🎙 Meine Aufnahmen")
                        .setItems(recs.map { Projects.sampleTitle(it) }.toTypedArray()) { _, i -> loadRecording(recs[i], p) }
                        .setNegativeButton("Zurück") { _, _ -> showLibrary(p) }
                        .show()
                    return@setItems
                }
                val cat = SampleLibrary.categories[which - (if (recs.isNotEmpty()) 1 else 0)]
                val list = SampleLibrary.inCategory(cat)
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(cat)
                    .setItems(list.map { it.title }.toTypedArray()) { _, i -> loadLibrarySample(list[i], p) }
                    .setNegativeButton("Zurück") { _, _ -> showLibrary(p) }
                    .show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun loadLibrarySample(smp: SampleLibrary.Sample, p: Int) {
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

    // ---------------------------------------------------------------- Aufnahme: Pattern oder Song

    private var pendingSong: ShortArray? = null
    private var pendingSongRate = 48000

    private fun showRecMenu() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Aufnehmen")
            .setItems(arrayOf(
                "● Pattern aufnehmen  (16 Schritte, ⚡-Pads)",
                "● Song aufnehmen – mit Effekten (wie du es hörst)",
                "● Song aufnehmen – ohne Effekte (trocken)"
            )) { _, which ->
                when (which) {
                    0 -> Engine.seqToggleRec()
                    1 -> {
                        Engine.songStart(false)
                        toast("Song-Aufnahme mit Effekten (${fxRoutingText()}) – REC oder STOP beendet sie")
                    }
                    else -> {
                        Engine.songStart(true)
                        toast("Song-Aufnahme ohne Effekte – REC oder STOP beendet sie")
                    }
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
            .setItems(arrayOf(
                "🎧 Als Audio speichern (WAV)",
                "💾 Als Projekt speichern (inkl. Song)",
                "🗑 Verwerfen"
            )) { _, which ->
                when (which) {
                    0 -> pickSongExport()
                    1 -> askProjectName(data, rate)
                    else -> pendingSong = null
                }
            }
            .setCancelable(false)
            .show()
    }

    // ---------------------------------------------------------------- Pattern beendet

    private var pendingPattern: FloatArray? = null

    private fun finishPattern() {
        if (!Engine.seqHasAny()) return
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Pattern aufgenommen")
            .setItems(arrayOf(
                "🎧 Als Audio speichern (WAV, 1 Takt)",
                "💾 Als Projekt speichern",
                "▶ Weiter"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val d = Engine.renderPattern()
                        if (d == null) {
                            toast("Das Pattern spielt keine ⚡-Samples")
                        } else {
                            pendingPattern = d
                            pickCreate("MarvPAD_Pattern", REQ_PATTERN)
                        }
                    }
                    1 -> askProjectName(null, 0)
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- Menü & Projekte

    private fun showMainMenu() {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Marv PAD")
            .setItems(arrayOf(
                "💾 Projekt speichern",
                "📂 Projekt laden",
                "✨ Neues Projekt",
                "🎙 Meine Aufnahmen",
                if (Engine.bounce) "🔁 Bounce: AN (Quellen nach Wave-Aufnahme stumm)" else "🔁 Bounce: AUS",
                "🎨 Design wählen"
            )) { _, which ->
                when (which) {
                    0 -> askProjectName(null, 0)
                    1 -> showProjects()
                    2 -> confirm("Neues Projekt?", "Alle Pads, das Pattern und das Tempo werden geleert. Nicht gespeicherte Änderungen gehen verloren.") {
                        Engine.newProject()
                        toast("Neues Projekt")
                    }
                    3 -> showRecordings()
                    4 -> {
                        Engine.bounce = !Engine.bounce
                        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("bounce", Engine.bounce).apply()
                        toast(if (Engine.bounce) "Bounce an: nach einer Wave-Aufnahme spielt nur noch das neue Pad" else "Bounce aus: Quell-Pads spielen weiter")
                    }
                    else -> showDesigns()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Ja") { _, _ -> onYes() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun askProjectName(song: ShortArray?, songRate: Int) {
        val input = EditText(this).apply {
            setText("Projekt " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date()))
            setSelectAllOnFocus(true)
            setTextColor(Color.WHITE)
        }
        val box = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Projekt speichern")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Projekt" }
                toast("Speichere Projekt …")
                Thread {
                    try {
                        Projects.save(applicationContext, name, song, songRate)
                        runOnUiThread { toast("Projekt „$name“ gespeichert") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Speichern fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
                if (song != null) pendingSong = null
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showProjects() {
        val list = Projects.list(this)
        if (list.isEmpty()) {
            toast("Noch keine Projekte gespeichert")
            return
        }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
        val names = list.map { "${it.name}\n${fmt.format(Date(it.date))}${if (it.hasSong) " · mit Song" else ""}" }.toTypedArray()
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Projekte")
            .setItems(names) { _, which -> projectActions(list[which]) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun projectActions(info: Projects.Info) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("📂 Laden")
        actions.add {
            toast("Lade „${info.name}“ …")
            Thread {
                try {
                    Projects.load(info.dir)
                    runOnUiThread { toast("„${info.name}“ geladen") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
                }
            }.start()
        }
        if (info.hasSong) {
            labels.add("🎧 Song exportieren (WAV)")
            actions.add {
                pendingCopy = java.io.File(info.dir, "song.wav")
                pickCreate("MarvPAD_Song", REQ_COPY)
            }
        }
        labels.add("🗑 Löschen")
        actions.add {
            confirm("Projekt löschen?", "„${info.name}“ wird endgültig gelöscht.") {
                Projects.delete(info.dir)
                toast("Gelöscht")
            }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(info.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Zurück", null)
            .show()
    }

    private fun showRecordings() {
        val files = Projects.listSamples(this)
        if (files.isEmpty()) {
            toast("Noch keine Aufnahmen – halte ein leeres Pad gedrückt")
            return
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Meine Aufnahmen")
            .setItems(files.map { Projects.sampleTitle(it) }.toTypedArray()) { _, which ->
                val f = files[which]
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(Projects.sampleTitle(f))
                    .setItems(arrayOf("⬇ Auf Pad laden", "🎧 Exportieren (WAV)", "🗑 Löschen")) { _, a ->
                        when (a) {
                            0 -> {
                                val padNames = Array(PadView.VISIBLE_PADS) { "Pad ${it + 1}" }
                                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                    .setTitle("Auf welches Pad?")
                                    .setItems(padNames) { _, p -> loadRecording(f, p) }
                                    .show()
                            }
                            1 -> {
                                pendingCopy = f
                                pickCreate(f.nameWithoutExtension, REQ_COPY)
                            }
                            else -> confirm("Aufnahme löschen?", Projects.sampleTitle(f)) { f.delete() }
                        }
                    }
                    .show()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun loadRecording(f: java.io.File, p: Int) {
        val rate = Engine.sampleRate
        Thread {
            try {
                val data = AudioFiles.decodeFile(f, rate, Engine.MAX_SECONDS)
                Engine.importLoop(p, data)
                runOnUiThread { toast("Pad ${p + 1}: Aufnahme geladen") }
            } catch (e: Exception) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    private var pendingCopy: java.io.File? = null

    private fun pickCreate(base: String, req: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, if (base.contains("_20")) "$base.wav" else "${base}_$stamp.wav")
        }
        try {
            startActivityForResult(i, req)
        } catch (e: Exception) {
            toast("Kein Dateimanager gefunden")
        }
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
            .setTitle("Platz ${Engine.editSlot + 1} · Effekt wählen · ${fxRoutingText()}")
            .setSingleChoiceItems(Fx.NAMES, Engine.fxType) { d, which ->
                Engine.selectFx(which)
                pads.fxChosen()
                d.dismiss()
            }
            .setNeutralButton("Pads wählen …") { _, _ -> showFxRouting() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun fxRoutingText(): String {
        val on = (0 until PadView.VISIBLE_PADS).count { Engine.padFx[it] }
        return when (on) {
            PadView.VISIBLE_PADS -> "alle Pads"
            0 -> "kein Pad"
            else -> "$on Pads"
        }
    }

    /** Auswahl, welche Pads durch den Effekt laufen. */
    private fun showFxRouting() {
        val n = PadView.VISIBLE_PADS
        val names = Array(n) { "Pad ${it + 1}" + if (Engine.hasLoop(it)) "" else " (leer)" }
        val checked = BooleanArray(n) { Engine.padFx[it] }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Effekt wirkt auf …")
            .setMultiChoiceItems(names, checked) { _, i, c -> Engine.padFx[i] = c }
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Alle") { _, _ -> Engine.setAllFx(true); toast("Effekt wirkt auf alle Pads") }
            .setNegativeButton("Keins") { _, _ -> Engine.setAllFx(false); toast("Effekt aus für alle Pads") }
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
            REQ_PATTERN -> {
                val d = pendingPattern ?: return
                pendingPattern = null
                val rate = Engine.sampleRate
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { AudioFiles.writeWav(it, d, rate) }
                            ?: throw IllegalStateException("Datei nicht schreibbar")
                        runOnUiThread { toast("Pattern gespeichert") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Speichern fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
            }
            REQ_COPY -> {
                val f = pendingCopy ?: return
                pendingCopy = null
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                            ?: throw IllegalStateException("Datei nicht schreibbar")
                        runOnUiThread { toast("Exportiert") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Export fehlgeschlagen: ${e.message ?: e}") }
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
        private const val REQ_PATTERN = 11
        private const val REQ_COPY = 12
    }
}
