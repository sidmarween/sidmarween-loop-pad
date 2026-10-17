package de.marween.looppad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var pads: PadView
    private lateinit var dj: DjView
    private var djMode = false
    private var djDeck = 0
    private var askedOnce = false

    /** Die aktive Oberfläche: Pads oder DJ-Controller. */
    private fun mainView(): View = if (djMode) dj else pads

    private fun switchMode(toDj: Boolean) {
        if (toDj == djMode && mainView().parent != null) return
        djMode = toDj
        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("djMode", toDj).apply()
        val v = mainView()
        v.alpha = 0f
        setContentView(v)
        hideSystemBars()
        v.requestApplyInsets()
        v.animate().alpha(1f).setDuration(250L).start()
        if (toDj) hint("🎧 DJ-Modus: oben Track antippen zum Laden · ▦ PADS wechselt zurück")
    }

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
        pads.onRecLongMenu = { showRecMenu() }
        pads.onHint = { hint(it) }
        pads.onSceneMenu = { showSceneMenu(it) }
        Engine.countIn = prefs.getBoolean("countIn", true)
        Engine.launchIdx = prefs.getInt("launchIdx", 3).coerceIn(0, Engine.LAUNCH_Q.size - 1)
        Engine.tapOverdub = prefs.getBoolean("tapOverdub", true)
        pads.statusColors = prefs.getBoolean("statusColors", true)
        pads.onImportMenu = { p -> showImportMenu(p) }
        pads.onPadFxEditor = { p -> showPadFxEditor(p) }
        pads.onEditorSave = { p, data, slices, cues -> showEditorSave(p, data, slices, cues) }
        pads.onRollIo = { p -> showRollIo(p) }
        pads.onInstMidi = { k -> showInstMidi(k) }
        pads.onLaunchQuantChanged = {
            prefs.edit().putInt("launchIdx", Engine.launchIdx).apply()
            hint(if (Engine.LAUNCH_Q[Engine.launchIdx] <= 0f) "Start-Raster AUS: Loops starten/stoppen sofort"
                 else "Start-Raster ${Engine.LAUNCH_NAMES[Engine.launchIdx]}: angetippte Loops starten/stoppen auf dem nächsten Rasterpunkt")
        }
        pads.onSongStopped = { finishSong() }
        pads.theme = getSharedPreferences("marvpad", MODE_PRIVATE).getInt("design", PadView.NOIR)
        pads.infoMode = getSharedPreferences("marvpad", MODE_PRIVATE).getBoolean("info", false)
        Engine.outKey = prefs.getString("outDev", null)
        Engine.inKey = prefs.getString("inDev", null)
        MasterPanel.load(this)
        // Latenz & Live-Reaktion
        Engine.latencyMode = prefs.getInt("latency", 0)
        Engine.instantStart = prefs.getBoolean("instantStart", true)
        Engine.instantStop = prefs.getBoolean("instantStop", false)
        Engine.monitorGain = prefs.getFloat("monGain", 0.9f)
        Engine.monitorMaster = prefs.getBoolean("monMaster", false)
        // DJ-Controller
        dj = DjView(this)
        dj.skinIdx = prefs.getInt("djSkin", 15)
        dj.onLoad = { k -> showDeckLoad(k) }
        dj.onMenu = { showMainMenu() }
        dj.onTempo = { showTempo() }
        dj.onPadsMode = { switchMode(false) }
        dj.onHint = { hint(it) }
        dj.onSkinMenu = { showDesigns() }
        dj.onSamplerLoad = { p -> showSamplerLoad(p) }
        dj.onEditSaveLib = { k -> saveDeckToLib(k) }
        djMode = prefs.getBoolean("djMode", false)
        // MIDI
        Midi.load(prefs)
        Midi.onHint = { m -> runOnUiThread { hint(m) } }
        Midi.onPad = { p, on -> if (on) runOnUiThread { pads.midiFlash(p); dj.flashSampler(p) } }
        try { Midi.start(this) } catch (e: Throwable) {}
        // Gleichmäßige CPU-Leistung (weniger Aussetzer bei kleinen Puffern)
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (pm.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(true)
        } catch (ignored: Throwable) {}
        registerUsbWatcher()
        if (savedInstanceState == null) showSplash() else setContentView(mainView())
        hideSystemBars()
    }

    /** Startbild: 4 Sekunden das Marv-PAD-Logo, dann blendet die Oberfläche ein. */
    private fun showSplash() {
        val bg = Color.rgb(22, 25, 30)
        val img = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.splash)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(bg)
            alpha = 0f
            animate().alpha(1f).setDuration(500L).start()
        }
        window.decorView.setBackgroundColor(bg)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        val d = resources.displayMetrics.density
        val credits = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = "Version $version"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Made with Claude ©marween"
                textSize = 11f
                setTextColor(Color.argb(200, 255, 255, 255))
                gravity = Gravity.CENTER
                setPadding(0, (4 * d).toInt(), 0, 0)
            })
            alpha = 0f
            animate().alpha(1f).setStartDelay(300L).setDuration(600L).start()
        }
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(img, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(credits, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (48 * d).toInt()
            })
        }
        setContentView(root)
        img.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val v = mainView()
            v.alpha = 0f
            setContentView(v)
            hideSystemBars()
            v.animate().alpha(1f).setDuration(350L).start()
        }, 4000L)
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

    /** Dateiauswahl läuft: Audio nicht anhalten (Musik spielt weiter, während man eine Datei wählt). */
    private var pickerOpen = false

    @Deprecated("Activity-Result-API wird hier bewusst nicht genutzt (keine AndroidX-Abhängigkeit)")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        pickerOpen = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode)
    }

    override fun onResume() {
        super.onResume()
        pickerOpen = false
    }

    override fun onStop() {
        // Während einer Dateiauswahl oder wenn im DJ-Modus ein Deck läuft, spielt die Musik weiter
        val djPlaying = djMode && (Dj.decks[0].playing || Dj.decks[1].playing)
        if (!pickerOpen && !djPlaying) Engine.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) Engine.stop()
        super.onDestroy()
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
        labels.add("✨ Eigene Effekte des Pads (bis zu 3) …")
        actions.add { showPadFxEditor(p) }
        labels.add(if (fxOn) "🎛 XY-Effekt: AN  →  Pad ohne XY-Effekt" else "🎛 XY-Effekt: AUS  →  Pad durch den XY-Effekt")
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
        val user = Projects.userSamples(this)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (recs.isNotEmpty()) {
            labels.add("🎙 Meine Aufnahmen (${recs.size})")
            actions.add {
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("🎙 Meine Aufnahmen")
                    .setItems(recs.map { Projects.sampleTitle(it) }.toTypedArray()) { _, i -> loadRecording(recs[i], p) }
                    .setNegativeButton("Zurück") { _, _ -> showLibrary(p) }
                    .show()
            }
        }
        // eingebaute Kategorien + eigene Samples darin, danach rein eigene Kategorien
        val cats = LinkedHashSet<String>(SampleLibrary.categories)
        cats.addAll(user.map { it.category })
        for (c in cats) {
            val own = user.filter { it.category == c }
            val builtIn = SampleLibrary.inCategory(c)
            labels.add("$c (${builtIn.size + own.size})" + if (own.isNotEmpty()) " · ★ ${own.size}" else "")
            actions.add { showLibraryCategory(p, c) }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Bibliothek → Pad ${p + 1}")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showLibraryCategory(p: Int, cat: String) {
        val own = Projects.userSamples(this).filter { it.category == cat }
        val builtIn = SampleLibrary.inCategory(cat)
        val labels = own.map { "★ " + it.title + (if (it.oneShot) "" else " ∞") } + builtIn.map { it.title }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(cat)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < own.size) userSampleActions(p, own[i]) else loadLibrarySample(builtIn[i - own.size], p)
            }
            .setNegativeButton("Zurück") { _, _ -> showLibrary(p) }
            .show()
    }

    private fun userSampleActions(p: Int, smp: Projects.UserSample) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("★ ${smp.title}")
            .setItems(arrayOf("▶ Auf Pad ${p + 1} laden", "🗑 Aus der Bibliothek löschen")) { _, w ->
                if (w == 0) loadUserSample(smp, p)
                else confirm("Löschen?", "„${smp.title}“ wird aus deiner Bibliothek entfernt.") {
                    Projects.deleteUserSample(this, smp)
                    toast("Gelöscht")
                }
            }
            .setNegativeButton("Zurück") { _, _ -> showLibraryCategory(p, smp.category) }
            .show()
    }

    private fun loadUserSample(smp: Projects.UserSample, p: Int) {
        val rate = Engine.sampleRate
        Thread {
            try {
                val data = AudioFiles.decodeFile(smp.file, rate, Engine.MAX_SECONDS)
                Engine.padOneShot[p] = smp.oneShot
                if (smp.oneShot) {
                    Engine.importLoop(p, data)
                    runOnUiThread { toast("Pad ${p + 1}: ★ ${smp.title}") }
                } else {
                    val desc = Engine.importClip(p, data, Warp.analyze(data, rate, smp.title))
                    runOnUiThread { hint("Pad ${p + 1}: ★ ${smp.title} · $desc · antippen startet ihn auf der Eins") }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- Wave-Editor → Bibliothek / Stems

    private var pendingStems: List<FloatArray>? = null
    private var pendingStemName = ""

    private fun showEditorSave(p: Int, data: FloatArray, slices: Int, cues: IntArray) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val defName = "Pad ${p + 1} · " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        // Hot Cues: gesetzte Cue-Punkte – sonst die Slice-Grenzen
        val hot = IntArray(4) { -1 }
        val set = cues.filter { it >= 0 }.sorted()
        if (set.isNotEmpty()) for (k in 0 until min(4, set.size)) hot[k] = set[k]
        else if (slices > 1) for (k in 0 until min(4, slices)) hot[k] = (k.toLong() * data.size / slices).toInt()
        val nHot = hot.count { it >= 0 }
        labels.add("💿 In die DJ-Bibliothek (Startpunkt = S" + (if (nHot > 0) " + $nHot Hot Cues" else "") + ")")
        actions.add { askName(defName, "Titel in der DJ-Bibliothek") { name -> saveToDjLibrary(name, data, hot) } }
        labels.add("💾 Ausschnitt als Sample in die Bibliothek")
        actions.add { askCategory("Ausschnitt speichern") { cat -> askName(defName) { name -> saveToLibrary(cat, listOf(data), name, Engine.isOneShot(p), null) } } }
        if (slices > 1) {
            val stems = splitSlices(data, slices)
            labels.add("🎚 $slices Slices als Stems in die Bibliothek")
            actions.add { askCategory("Stems speichern") { cat -> askName(defName) { name -> saveToLibrary(cat, stems, name, true, name) } } }
            labels.add("📤 $slices Slices als Stems exportieren (Ordner wählen)")
            actions.add { askName(defName) { name -> exportStems(stems, name) } }
        }
        labels.add("📤 Ausschnitt als WAV exportieren")
        actions.add {
            exportData = data
            exportRate = Engine.sampleRate
            pickCreate("MarvPAD_Ausschnitt", REQ_EXPORT)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Speichern · " + String.format(Locale.GERMANY, "%.2f s", data.size.toFloat() / Engine.sampleRate) + if (slices > 1) " · $slices Slices" else "")
            .setItems(labels.toTypedArray()) { _, w -> actions[w]() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveToDjLibrary(name: String, data: FloatArray, hot: IntArray) {
        val rate = Engine.sampleRate
        Thread {
            try {
                val bpm = Warp.bpmFromName(name) ?: if (data.size < rate * 30) Warp.analyze(data, rate, name).bpm else {
                    val tmp = Dj.Deck(9)
                    Dj.load(tmp, Dj.fromMono(data, rate), name, null)
                    tmp.bpm
                }
                val hs = DoubleArray(4) { k -> if (hot[k] >= 0) hot[k].toDouble() / rate else -1.0 }
                DjLibrary.add(this, data, rate, name, bpm, 0.0, 0.0, hs)
                runOnUiThread { hint(String.format(Locale.GERMANY, "💿 „%s“ in der DJ-Bibliothek · %.1f BPM · Startpunkt + %d Hot Cues", name, bpm, hs.count { it >= 0 })) }
            } catch (e: Throwable) {
                runOnUiThread { toast("Speichern fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    // ================================================================ DJ-Bibliothek

    private fun pickDjLib(title: String, onPick: (DjLibrary.Item) -> Unit) {
        val items = DjLibrary.list(this)
        if (items.isEmpty()) {
            toast("DJ-Bibliothek ist leer – im Wave-Editor „💾 → 💿 DJ-Bibliothek“ wählen")
            return
        }
        val labels = items.map { String.format(Locale.GERMANY, "%s · %.1f BPM · %s", it.title, it.bpm, Dj.fmt(it.seconds)) }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i -> onPick(items[i]) }
            .setNeutralButton("Löschen …") { _, _ ->
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Aus der DJ-Bibliothek löschen")
                    .setItems(labels.toTypedArray()) { _, i -> confirm("Löschen?", items[i].title) { DjLibrary.delete(this, items[i].id) } }
                    .show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun loadDeckLib(k: Int, it: DjLibrary.Item) {
        val dd = Dj.decks[k]
        dd.loading = true
        Thread {
            try {
                val data = AudioFiles.decodeFile(it.file, Engine.sampleRate, 600)
                val r = Engine.sampleRate.toDouble()
                Dj.load(dd, Dj.fromMono(data, Engine.sampleRate), it.title, it.bpm)
                dd.grid = it.grid * r
                dd.cuePoint = it.cue * r
                for (h in 0..3) dd.hot[h] = if (it.hot[h] >= 0) it.hot[h] * r else -1.0
                dd.seekTo = dd.cuePoint
                dd.libId = it.id
                runOnUiThread { hint(String.format(Locale.GERMANY, "Deck %s: %s · %.1f BPM · Cue-Punkte geladen", "AB"[k], it.title, it.bpm)) }
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                dd.loading = false
            }
        }.start()
    }

    /** Sampler-Pad im DJ-Modus belegen – das Pad startet beim Antippen synchron ab dem Startpunkt. */
    private fun showSamplerLoad(p: Int) {
        val items = arrayListOf("💿 Aus der DJ-Bibliothek (ab Startpunkt)")
        val acts = arrayListOf<() -> Unit>({ pickDjLib("DJ-Bibliothek → Pad ${p + 1}") { it -> loadPadFromLib(p, it) } })
        for (k in 0..1) {
            val dd = Dj.decks[k]
            if (dd.loaded()) {
                items.add("${if (k == 0) "🅰" else "🅱"} Deck ${"AB"[k]} ab Cue-Punkt (${dd.title})")
                acts.add { loadPadFromDeck(p, k) }
            }
        }
        items.add("📂 Datei oder 📚 Bibliothek …")
        acts.add {
            Engine.padOneShot[p] = false
            showImportMenu(p)
        }
        if (Engine.hasLoop(p)) {
            items.add("🗑 Pad leeren")
            acts.add { Engine.clear(p) }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Sampler-Pad ${p + 1}")
            .setItems(items.toTypedArray()) { _, w -> acts[w]() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Ausschnitt ab dem Startpunkt auf ganze Takte (2er-Potenz) zuschneiden und als synchronen Clip aufs Pad legen. */
    private fun clipToPad(p: Int, src: FloatArray, startFrame: Int, bpm: Float, title: String) {
        val rate = Engine.sampleRate
        val beat = rate * 60.0 / bpm
        val avail = (src.size - startFrame) / (beat * 4)
        val master = if (Engine.currentBpm() > 0f) Engine.currentBpm() else bpm
        var bars = 1f
        while (bars * 2 <= avail && bars * 2 * 240f / master <= 60f) bars *= 2f
        if (avail < 1.0) bars = max(0.25f, (Math.floor(avail * 4) / 4).toFloat())
        val len = (bars * 4 * beat).toInt().coerceAtMost(src.size - startFrame)
        if (len < rate / 20) { runOnUiThread { toast("Zu kurz ab dem Startpunkt") }; return }
        val clip = src.copyOfRange(startFrame, startFrame + len)
        Engine.padOneShot[p] = false
        val desc = Engine.importClip(p, clip, Warp.Info(bpm, bars, "DJ"))
        Engine.padSyncStart[p] = true
        runOnUiThread { hint("Pad ${p + 1}: $title · $desc · antippen startet synchron ab dem Startpunkt") }
    }

    private fun loadPadFromLib(p: Int, it: DjLibrary.Item) {
        Thread {
            try {
                val data = AudioFiles.decodeFile(it.file, Engine.sampleRate, 600)
                clipToPad(p, data, (it.cue * Engine.sampleRate).toInt().coerceIn(0, data.size - 1), it.bpm, it.title)
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    private fun loadPadFromDeck(p: Int, k: Int) {
        val dd = Dj.decks[k]
        val d = dd.data ?: return
        val ch = dd.ch
        val srcRate = dd.srcRate
        val cue = dd.cuePoint
        val bpm = dd.bpm
        val title = dd.title
        Thread {
            try {
                // höchstens 64 s ab dem Cue-Punkt, mono, auf die Engine-Rate
                val rate = Engine.sampleRate
                val start = cue.toInt().coerceIn(0, dd.len - 1)
                val n = min(dd.len - start, srcRate * 64)
                val outN = (n.toLong() * rate / srcRate).toInt()
                val mono = FloatArray(outN)
                for (i in 0 until outN) {
                    val f = start + (i.toLong() * srcRate / rate).toInt()
                    mono[i] = if (ch == 2) (d[2 * f] + d[2 * f + 1]) / 65536f else d[f] / 32768f
                }
                clipToPad(p, mono, 0, if (bpm > 0f) bpm else 120f, title)
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    // ================================================================ ACID/DRUM: MIDI-Dateien

    private var instIoKind = 1
    private var instIoAdd = false
    /** General-MIDI-Drumnoten für BD, SD, LT, MT, HT, RS, CP, CH, OH, CR, RD. */
    private val GM = intArrayOf(36, 38, 41, 45, 48, 37, 39, 42, 46, 49, 51)

    private fun showInstMidi(k: Int) {
        instIoKind = k
        val name = if (k == 1) "ACID 303" else "DRUM 909"
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("$name · Noten")
            .setItems(arrayOf(
                "📤 Als MIDI-Datei exportieren (.mid)",
                "📥 MIDI-Datei laden – ersetzt das Pattern",
                "📥 MIDI-Datei laden – zum Pattern hinzufügen"
            )) { _, w ->
                if (w == 0) {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMANY).format(Date())
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/midi"
                        putExtra(Intent.EXTRA_TITLE, "MarvPAD_${if (k == 1) "ACID" else "DRUM"}_$stamp.mid")
                    }
                    try { startActivityForResult(i, REQ_INST_OUT) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
                } else {
                    instIoAdd = w == 2
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "application/octet-stream"))
                    }
                    try { startActivityForResult(i, REQ_INST_IN) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun writeInstMidi(uri: Uri) {
        val st = InstState
        val notes = ArrayList<MidiFile.Note>()
        if (instIoKind == 1) {
            val len = st.acidLen
            var k = 0
            while (k < len) {
                if (!st.acidGate[k] || (k > 0 && st.acidTie[k] && st.acidGate[k - 1])) { k++; continue }
                var n = 1
                while (k + n < len && st.acidGate[k + n] && st.acidTie[k + n]) n++
                val slide = st.acidSlide[k + n - 1] && k + n < len && st.acidGate[k + n]
                val lenBeats = (if (slide) n + 0.1 else n * 0.5 + (n - 1) * 0.5) / 4.0
                val vel = ((if (st.acidAcc[k]) 127 else 96) * st.acidVel[k]).toInt().coerceIn(1, 127)
                notes.add(MidiFile.Note(k / 4.0, lenBeats, 36 + 12 * st.acidOct + st.acidTune + st.acidSemi[k], vel))
                k += n
            }
        } else {
            for (v in 0 until InstState.DV) for (k in 0 until st.drumLen) {
                val x = st.drumSteps[v][k]
                if (x == 0) continue
                notes.add(MidiFile.Note(k / 4.0, 0.25, GM[v], ((if (x == 2) 127 else 96) * st.drumVel[v][k]).toInt().coerceIn(1, 127)))
            }
        }
        if (notes.isEmpty()) { toast("Keine Noten im Pattern"); return }
        try {
            val bytes = MidiFile.exportRaw(notes, Engine.currentBpm(), if (instIoKind == 1) "Marv PAD · ACID 303" else "Marv PAD · DRUM 909", if (instIoKind == 1) 0 else 9)
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast("MIDI-Datei gespeichert (${notes.size} Noten)")
        } catch (e: Exception) {
            toast("Export fehlgeschlagen: ${e.message ?: e}")
        }
    }

    private fun drumVoiceFor(pitch: Int): Int = when (pitch) {
        35, 36 -> 0; 38, 40 -> 1; 41, 43 -> 2; 45, 47 -> 3; 48, 50 -> 4; 37 -> 5; 39 -> 6
        42, 44 -> 7; 46 -> 8; 49, 52, 55, 57 -> 9; 51, 53, 59 -> 10
        else -> -1
    }

    private fun readInstMidi(uri: Uri) {
        val st = InstState
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val parsed = MidiFile.parse(bytes)
            if (parsed.notes.isEmpty()) { toast("Keine Noten in der Datei"); return }
            val end = parsed.notes.maxOf { it.beat + it.len }
            var need = 16
            while (need < InstState.MAX && need < end * 4 - 1e-6) need += 16
            Engine.pushPatternUndo("MIDI-Import")
            var count = 0
            if (instIoKind == 1) {
                if (!instIoAdd) st.acidClear()
                st.acidLen = max(if (instIoAdd) st.acidLen else 16, need)
                val ns = parsed.notes.sortedBy { it.beat }
                for ((i, n) in ns.withIndex()) {
                    val k = Math.round(n.beat * 4).toInt()
                    if (k !in 0 until st.acidLen) continue
                    var semi = n.pitch - 36 - 12 * st.acidOct - st.acidTune
                    while (semi > 24) semi -= 12
                    while (semi < -12) semi += 12
                    val steps = max(1, Math.round(n.len * 4).toInt())
                    val next = ns.getOrNull(i + 1)
                    val slide = next != null && n.beat + n.len > next.beat + 1e-3
                    for (j in 0 until steps) {
                        val s2 = k + j
                        if (s2 >= st.acidLen) break
                        st.acidGate[s2] = true
                        st.acidTie[s2] = j > 0
                        st.acidSemi[s2] = semi
                        st.acidAcc[s2] = j == 0 && n.vel >= 110
                        st.acidSlide[s2] = false
                        st.acidVel[s2] = (n.vel / 127f).coerceIn(0.05f, 1f)
                    }
                    st.acidSlide[min(st.acidLen - 1, k + steps - 1)] = slide
                    count++
                }
            } else {
                if (!instIoAdd) st.drumClear()
                st.drumLen = max(if (instIoAdd) st.drumLen else 16, need)
                var skipped = 0
                for (n in parsed.notes) {
                    val v = drumVoiceFor(n.pitch)
                    val k = Math.round(n.beat * 4).toInt()
                    if (v < 0) { skipped++; continue }
                    if (k !in 0 until st.drumLen) continue
                    st.drumSteps[v][k] = if (n.vel >= 110) 2 else 1
                    st.drumVel[v][k] = (n.vel / 127f).coerceIn(0.05f, 1f)
                    count++
                }
                if (skipped > 0) toast("$skipped Noten ohne passendes Instrument übersprungen")
            }
            toast("$count Noten geladen · ${(if (instIoKind == 1) st.acidLen else st.drumLen) / 16} Takt(e)")
        } catch (e: Exception) {
            toast("Import fehlgeschlagen: ${e.message ?: e}")
        }
    }

    // ================================================================ Piano-Roll: MIDI-Dateien

    private var rollIoPad = 0
    private var rollIoAdd = false

    private fun showRollIo(p: Int) {
        rollIoPad = p
        val n = Engine.noteCount(p)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Pad ${p + 1} · Noten ($n)")
            .setItems(arrayOf(
                "📤 Noten als MIDI-Datei exportieren (.mid)",
                "📥 MIDI-Datei importieren – ersetzt die Noten",
                "📥 MIDI-Datei importieren – zu den Noten hinzufügen"
            )) { _, w ->
                when (w) {
                    0 -> {
                        if (n == 0) { toast("Keine Noten zum Exportieren"); return@setItems }
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMANY).format(Date())
                        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "audio/midi"
                            putExtra(Intent.EXTRA_TITLE, "MarvPAD_Pad${p + 1}_$stamp.mid")
                        }
                        try { startActivityForResult(i, REQ_MIDI_OUT) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
                    }
                    else -> {
                        rollIoAdd = w == 2
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "application/octet-stream"))
                        }
                        try { startActivityForResult(i, REQ_MIDI_IN) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun writeMidi(uri: Uri) {
        val p = rollIoPad
        try {
            val bytes = MidiFile.export(Engine.notesOf(p), Engine.currentBpm(), "Marv PAD · Pad ${p + 1}")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            toast("MIDI-Datei gespeichert (${Engine.noteCount(p)} Noten)")
        } catch (e: Exception) {
            toast("Export fehlgeschlagen: ${e.message ?: e}")
        }
    }

    private fun readMidi(uri: Uri) {
        val p = rollIoPad
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val parsed = MidiFile.parse(bytes)
            if (parsed.notes.isEmpty()) { toast("Keine Noten in der Datei"); return }
            val endBeats = parsed.notes.maxOf { it.beat + it.len }
            var bars = 1
            while (bars < 16 && bars * 4 < endBeats - 1e-6) bars *= 2
            Engine.pushPatternUndo("MIDI-Import")
            if (!rollIoAdd) Engine.clearNotes(p)
            if (bars > Engine.rollBars[p]) Engine.setRollBars(p, bars)
            val list = parsed.notes.filter { it.beat / 4.0 < Engine.rollBars[p] }.map {
                Engine.NoteEv(it.beat / 4.0, it.len / 4.0, it.pitch - 60, (it.vel / 127f).coerceIn(0.05f, 1f))
            }
            val ids = Engine.addNotes(p, list)
            val cut = parsed.notes.size - ids.size
            toast("${ids.size} Noten importiert" + (if (cut > 0) " · $cut hinter Takt 16 weggelassen" else "") +
                (if (parsed.bpm > 0f) String.format(Locale.GERMANY, " · Datei: %.0f BPM", parsed.bpm) else ""))
        } catch (e: Exception) {
            toast("Import fehlgeschlagen: ${e.message ?: e}")
        }
    }

    private fun splitSlices(data: FloatArray, n: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val fade = Engine.sampleRate * 3 / 1000
        for (k in 0 until n) {
            val a = (k.toLong() * data.size / n).toInt()
            val b = ((k + 1).toLong() * data.size / n).toInt()
            if (b - a < 2) continue
            val s = data.copyOfRange(a, b)
            val f = min(fade, s.size / 4)
            for (i in 0 until f) {
                val g = i.toFloat() / f
                s[i] *= g
                s[s.size - 1 - i] *= g
            }
            out.add(s)
        }
        return out
    }

    /** Kategorie wählen: vorhandene (eingebaute und eigene) oder neue anlegen. */
    private fun askCategory(title: String, onPick: (String) -> Unit) {
        val cats = LinkedHashSet<String>()
        cats.addAll(Projects.userCategories(this))
        cats.addAll(SampleLibrary.categories)
        val list = cats.toList()
        val labels = list.map { it } + "＋ Neue Kategorie …"
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("$title · Kategorie")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w < list.size) onPick(list[w])
                else askName("⭐ Meine Sounds", "Neue Kategorie") { c -> onPick(c.trim().ifEmpty { "⭐ Meine Sounds" }) }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun askName(def: String, title: String = "Name", onOk: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(def)
            setSelectAllOnFocus(true)
            setTextColor(Color.WHITE)
        }
        val box = LinearLayout(this).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim().ifEmpty { def }) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveToLibrary(cat: String, parts: List<FloatArray>, name: String, oneShot: Boolean, stem: String?) {
        val rate = Engine.sampleRate
        Thread {
            try {
                if (parts.size == 1) Projects.saveUserSample(this, cat, name, parts[0], rate, oneShot, stem)
                else parts.forEachIndexed { i, d -> Projects.saveUserSample(this, cat, name + " · Stem " + String.format(Locale.GERMANY, "%02d", i + 1), d, rate, true, stem) }
                runOnUiThread { toast(if (parts.size == 1) "★ „$name“ in $cat gespeichert" else "★ ${parts.size} Stems in $cat gespeichert") }
            } catch (e: Exception) {
                runOnUiThread { toast("Speichern fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    /** Stems als einzelne WAV-Dateien in einen gewählten Ordner. */
    private fun exportStems(stems: List<FloatArray>, name: String) {
        pendingStems = stems
        pendingStemName = name
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_STEMS)
        } catch (e: Exception) {
            toast("Ordnerauswahl nicht verfügbar")
        }
    }

    private fun writeStems(tree: Uri) {
        val stems = pendingStems ?: return
        pendingStems = null
        val rate = Engine.sampleRate
        val base = pendingStemName.replace(Regex("[^\\w\\- äöüÄÖÜß]"), "_")
        Thread {
            var ok = 0
            try {
                val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                stems.forEachIndexed { i, d ->
                    val doc = android.provider.DocumentsContract.createDocument(contentResolver, dir, "audio/wav", base + "_Stem_" + String.format(Locale.GERMANY, "%02d", i + 1) + ".wav")
                    if (doc != null) contentResolver.openOutputStream(doc)?.use { AudioFiles.writeWav(it, d, rate); ok++ }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Export fehlgeschlagen: ${e.message ?: e}") }
            }
            runOnUiThread { if (ok > 0) toast("$ok Stems exportiert") }
        }.start()
    }

    private fun loadLibrarySample(smp: SampleLibrary.Sample, p: Int) {
        val rate = Engine.sampleRate
        Thread {
            try {
                val data = AudioFiles.decodeAsset(this, smp.file, rate, Engine.MAX_SECONDS)
                Engine.padOneShot[p] = smp.oneShot
                if (smp.oneShot) {
                    Engine.importLoop(p, data)
                    runOnUiThread { toast("Pad ${p + 1}: ${smp.title}") }
                } else {
                    val desc = Engine.importClip(p, data, Warp.analyze(data, rate, smp.title + " " + smp.file))
                    runOnUiThread { hint("Pad ${p + 1}: ${smp.title} · $desc · antippen startet ihn auf der Eins") }
                }
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
                if (Engine.seqRecording) "■ Pattern-Aufnahme beenden" else "● Pattern aufnehmen  (REC kurz antippen)",
                "● Song aufnehmen – mit Effekten (wie du es hörst)",
                "● Song aufnehmen – ohne Effekte (trocken)",
                "🎧 Pattern als Audio speichern (WAV)",
                "💾 Projekt speichern (inkl. Szenen)",
                (if (Engine.countIn) "☑" else "☐") + " Einzähler: 1 Takt vor der Pattern-Aufnahme"
            )) { _, which ->
                when (which) {
                    0 -> Engine.seqToggleRec()
                    3 -> {
                        val d = Engine.renderPattern()
                        if (d == null) {
                            toast("Das Pattern spielt keine ⚡-Samples")
                        } else {
                            pendingPattern = d
                            pickCreate("MarvPAD_Pattern", REQ_PATTERN)
                        }
                    }
                    4 -> askProjectName(null, 0)
                    5 -> {
                        Engine.countIn = !Engine.countIn
                        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("countIn", Engine.countIn).apply()
                        toast(if (Engine.countIn) "Einzähler an" else "Einzähler aus")
                    }
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
                (if (pads.infoMode) "☑" else "☐") + " ℹ Info-Modus (Funktionen erklären)",
                "🔌 Audio-Geräte (USB-Interface, Kopfhörer …)",
                "🎨 Design wählen",
                if (Engine.tapOverdub) "👆 Tipp auf laufenden Loop: ● OVERDUB (→ auf Stopp)" else "👆 Tipp auf laufenden Loop: ■ STOPP (→ auf Overdub)",
                (if (pads.statusColors) "☑" else "☐") + " 🚦 Status-Farben (Rot Aufnahme · Grün Play · Gelb Overdub)",
                String.format(Locale.GERMANY, "⚡ Latenz & Live-Reaktion (%.0f ms)", Engine.outputLatencyMs()),
                (if (Engine.monitorOn) "☑" else "☐") + " 🎙 Live-Mikrofon mit Effekten …",
                "🎹 MIDI (" + (if (Midi.deviceNames.isEmpty()) "kein Gerät" else Midi.deviceNames.joinToString(", ")) + ")",
                if (djMode) "▦ Zurück zum Pad-Modus" else "🎧 DJ-Modus (zwei Decks, Crossfader)"
            )) { _, which ->
                when (which) {
                    7 -> showDesigns()
                    10 -> showLatency()
                    11 -> showMonitor()
                    12 -> showMidi()
                    13 -> switchMode(!djMode)
                    8 -> {
                        Engine.tapOverdub = !Engine.tapOverdub
                        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("tapOverdub", Engine.tapOverdub).apply()
                        toast(if (Engine.tapOverdub) "Tipp auf laufenden Loop = Overdub (gelb), nochmal tippen beendet. Stoppen: zwei Finger" else "Tipp auf laufenden Loop = stoppen (im Start-Raster)")
                    }
                    9 -> {
                        pads.statusColors = !pads.statusColors
                        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("statusColors", pads.statusColors).apply()
                    }
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
                    6 -> showAudioDevices()
                    5 -> {
                        pads.infoMode = !pads.infoMode
                        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("info", pads.infoMode).apply()
                        toast(if (pads.infoMode) "Info-Modus an: tippe auf ein Element, um es erklärt zu bekommen" else "Info-Modus aus")
                    }
                    else -> showDesigns()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    // ---------------------------------------------------------------- Audio-Geräte / USB

    private var watcherReady = false
    private val restartAudio = Runnable {
        if (Engine.running) {
            Engine.stop()
            ensureStarted()
        }
    }

    private fun scheduleAudioRestart() {
        window.decorView.removeCallbacks(restartAudio)
        window.decorView.postDelayed(restartAudio, 500L)
    }

    private fun registerUsbWatcher() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (!watcherReady) {
                    // erster Aufruf listet nur die schon vorhandenen Geräte
                    watcherReady = true
                    return
                }
                val usb = added.filter { Engine.isUsb(it) }
                if (usb.isEmpty()) return
                toast("🔌 USB-Audio verbunden: ${usb.first().productName}")
                scheduleAudioRestart()
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                val usb = removed.filter { Engine.isUsb(it) }
                if (usb.isEmpty()) return
                toast("🔌 USB-Audio getrennt: ${usb.first().productName}")
                scheduleAudioRestart()
            }
        }, null)
    }

    private fun deviceName(d: AudioDeviceInfo): String {
        val t = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "🔌 USB-Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "🔌 USB-Headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "🔌 USB-Zubehör"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "🔊 Lautsprecher"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "🎙 Internes Mikrofon"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "🎧 Headset (Kabel)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "🎧 Kopfhörer (Kabel)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "📶 Bluetooth (hohe Latenz)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "📶 Bluetooth-Headset"
            AudioDeviceInfo.TYPE_HDMI -> "📺 HDMI"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line (analog)"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line (digital)"
            else -> "Gerät"
        }
        val ch = d.channelCounts.maxOrNull()
        val rates = d.sampleRates.maxOrNull()
        val extra = listOfNotNull(
            if (ch != null && ch > 0) "${ch} Kan." else null,
            if (rates != null && rates > 0) "${rates / 1000} kHz" else null
        ).joinToString(", ")
        return "$t – ${d.productName}" + if (extra.isNotEmpty()) " ($extra)" else ""
    }

    private fun usefulDevices(flag: Int): List<AudioDeviceInfo> {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val skip = setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
        return am.getDevices(flag).filter { it.type !in skip }.distinctBy { Engine.deviceKey(it) }
    }

    private fun showAudioDevices() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val outSel = Engine.findDevice(am, Engine.outKey, AudioManager.GET_DEVICES_OUTPUTS)
        val inSel = Engine.findDevice(am, Engine.inKey, AudioManager.GET_DEVICES_INPUTS)
        val outNow = Engine.routedOutput()
        val inNow = Engine.routedInput()
        val usbCount = am.getDevices(AudioManager.GET_DEVICES_ALL).count { Engine.isUsb(it) }
        val msg = "Ausgang: " + (if (outSel != null) deviceName(outSel) else "Automatisch") +
            (if (outNow != null) "\n   aktiv: ${deviceName(outNow)}" else "") +
            "\n\nEingang: " + (if (inSel != null) deviceName(inSel) else "Automatisch") +
            (if (inNow != null) "\n   aktiv: ${deviceName(inNow)}" else "") +
            "\n\n" + (if (usbCount > 0) "🔌 USB-Audio erkannt." else "Kein USB-Audio-Gerät erkannt. Class-compliant Interfaces per USB-C (oder OTG-Adapter) anschließen – Android bindet sie automatisch ein.")
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("🔌 Audio-Geräte")
            .setMessage(msg)
            .setPositiveButton("Ausgang …") { _, _ -> pickDevice(false) }
            .setNeutralButton("Eingang …") { _, _ -> pickDevice(true) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun pickDevice(input: Boolean) {
        val list = usefulDevices(if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS)
        val names = ArrayList<String>()
        names.add("⚙ Automatisch (Android wählt, USB hat Vorrang)")
        for (d in list) names.add(deviceName(d))
        val cur = if (input) Engine.inKey else Engine.outKey
        val checked = if (cur == null) 0 else (list.indexOfFirst { Engine.deviceKey(it) == cur } + 1).coerceAtLeast(0)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(if (input) "Eingang wählen" else "Ausgang wählen")
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                val key = if (which == 0) null else Engine.deviceKey(list[which - 1])
                if (input) Engine.inKey = key else Engine.outKey = key
                getSharedPreferences("marvpad", MODE_PRIVATE).edit()
                    .putString(if (input) "inDev" else "outDev", key)
                    .apply()
                d.dismiss()
                toast((if (input) "Eingang: " else "Ausgang: ") + names[which])
                scheduleAudioRestart()
            }
            .setNegativeButton("Abbrechen", null)
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
                if (Engine.padOneShot[p]) {
                    Engine.importLoop(p, data)
                    runOnUiThread { toast("Pad ${p + 1}: Aufnahme geladen") }
                } else {
                    val desc = Engine.importClip(p, data, Warp.analyze(data, rate, f.name))
                    runOnUiThread { hint("Pad ${p + 1}: Aufnahme · $desc · antippen startet sie auf der Eins") }
                }
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
        box.addView(CheckBox(this).apply {
            text = "Einzähler (1 Takt vor der Pattern-Aufnahme)"
            isChecked = Engine.countIn
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c ->
                Engine.countIn = c
                getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("countIn", c).apply()
            }
        })
        show()
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Master-Tempo")
            .setView(box)
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Automatisch") { _, _ -> Engine.setAutoTempo() }
            .show()
    }

    private fun showDesigns() {
        val n = PadView.DESIGN_NAMES.size
        val names = PadView.DESIGN_NAMES.map { "▦ $it" } + DjView.SKINS.map { "🎧 DJ · ${it.name}" }
        val cur = if (djMode) n + dj.skinIdx else pads.theme
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Design wählen · ▦ Pads · 🎧 DJ-Modus")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
                if (which < n) {
                    pads.theme = which
                    prefs.edit().putInt("design", which).apply()
                    switchMode(false)
                } else {
                    dj.skinIdx = which - n
                    prefs.edit().putInt("djSkin", which - n).apply()
                    switchMode(true)
                }
                d.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ================================================================ DJ: Tracks laden

    private fun showDeckLoad(k: Int) {
        val dd = Dj.decks[k]
        val items = arrayListOf(
            "📂 Datei (MP3, WAV, M4A, OGG …)",
            "📚 Bibliothek (Loops & eigene Samples)",
            "🎙 Meine Aufnahmen",
            "▦ Von einem Pad übernehmen",
            "💿 DJ-Bibliothek (mit Cue-Punkten)"
        )
        if (dd.loaded()) items.add(0, "〰 Im Wave-Editor öffnen (Startpunkt, Hot Cues, Raster, Warp/Sync)")
        if (dd.loaded()) {
            items.add(String.format(Locale.GERMANY, "🎚 Tempo/Raster korrigieren (%.1f BPM)", dd.bpm))
            items.add("⏏ Auswerfen")
            items.add(if (dd.libId != null) "💾 Cue-Punkte in der DJ-Bibliothek speichern" else "💿 Track mit Cue-Punkten in die DJ-Bibliothek")
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Deck ${"AB"[k]} laden")
            .setItems(items.toTypedArray()) { _, w0 ->
                if (dd.loaded() && w0 == 0) {
                    dj.openEditor(k)
                    return@setItems
                }
                val w = if (dd.loaded()) w0 - 1 else w0
                when (w) {
                    0 -> {
                        djDeck = k
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "audio/*"
                        }
                        try { startActivityForResult(i, REQ_DECK) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
                    }
                    1 -> deckFromLibrary(k)
                    2 -> {
                        val files = Projects.listSamples(this)
                        if (files.isEmpty()) { toast("Noch keine Aufnahmen"); return@setItems }
                        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Aufnahme → Deck ${"AB"[k]}")
                            .setItems(files.map { Projects.sampleTitle(it) }.toTypedArray()) { _, i ->
                                val f = files[i]
                                loadDeckMono(k, Projects.sampleTitle(f), f.name) { AudioFiles.decodeFile(f, Engine.sampleRate, 600) }
                            }
                            .show()
                    }
                    3 -> {
                        val used = (0 until PadView.VISIBLE_PADS).filter { Engine.hasLoop(it) }
                        if (used.isEmpty()) { toast("Alle Pads sind leer"); return@setItems }
                        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Pad → Deck ${"AB"[k]}")
                            .setItems(used.map { "Pad ${it + 1}" }.toTypedArray()) { _, i ->
                                val p = used[i]
                                val data = Engine.loopData(p) ?: return@setItems
                                loadDeckMono(k, "Pad ${p + 1}", null) { data.copyOf() }
                            }
                            .show()
                    }
                    4 -> pickDjLib("DJ-Bibliothek → Deck ${"AB"[k]}") { loadDeckLib(k, it) }
                    5 -> fixDeckTempo(k)
                    6 -> {
                        dd.playing = false
                        dd.data = null
                        dd.len = 0
                        dd.title = ""
                        dd.libId = null
                    }
                    7 -> saveDeckToLib(k)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Cue-Punkte des Decks in der DJ-Bibliothek sichern (bzw. den Track neu aufnehmen). */
    private fun saveDeckToLib(k: Int) {
        val dd = Dj.decks[k]
        val r = dd.srcRate.toDouble()
        val hot = DoubleArray(4) { if (dd.hot[it] >= 0) dd.hot[it] / r else -1.0 }
        val id = dd.libId
        if (id != null) {
            DjLibrary.update(this, id, dd.bpm, dd.grid / r, dd.cuePoint / r, hot)
            hint("💾 Cue-Punkte gespeichert")
            return
        }
        val d = dd.data ?: return
        askName(dd.title, "Titel in der DJ-Bibliothek") { name ->
            Thread {
                try {
                    // als Mono-WAV in Engine-Rate (max. 10 Minuten)
                    val rate = Engine.sampleRate
                    val outN = (dd.len.toLong() * rate / dd.srcRate).toInt()
                    val mono = FloatArray(outN)
                    for (i in 0 until outN) {
                        val f = min(dd.len - 1, (i.toLong() * dd.srcRate / rate).toInt())
                        mono[i] = if (dd.ch == 2) (d[2 * f] + d[2 * f + 1]) / 65536f else d[f] / 32768f
                    }
                    val it = DjLibrary.add(this, mono, rate, name, dd.bpm, dd.grid / r, dd.cuePoint / r, hot)
                    dd.libId = it.id
                    runOnUiThread { hint("💿 „$name“ mit Cue-Punkten in der DJ-Bibliothek") }
                } catch (e: Throwable) {
                    runOnUiThread { toast("Speichern fehlgeschlagen: ${e.message ?: e}") }
                }
            }.start()
        }
    }

    private fun fixDeckTempo(k: Int) {
        val dd = Dj.decks[k]
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(String.format(Locale.GERMANY, "Deck %s · %.1f BPM", "AB"[k], dd.bpm))
            .setItems(arrayOf("× 2 (doppelt)", "÷ 2 (halb)", "Tempo eingeben …", "Schlag 1 = aktuelle Position (Raster setzen)")) { _, w ->
                when (w) {
                    0 -> dd.bpm = dd.bpm * 2f
                    1 -> dd.bpm = dd.bpm / 2f
                    2 -> askName(String.format(Locale.US, "%.1f", dd.bpm), "Tempo (BPM)") { t ->
                        t.replace(',', '.').toFloatOrNull()?.let { v -> if (v in 40f..240f) dd.bpm = v }
                    }
                    else -> {
                        dd.grid = dd.pos
                        dd.cuePoint = dd.pos
                        hint("Raster gesetzt: Schlag 1 liegt jetzt hier")
                    }
                }
                if (dd.sync) dd.syncReq = true
            }
            .show()
    }

    private fun deckFromLibrary(k: Int) {
        val user = Projects.userSamples(this)
        val cats = (SampleLibrary.categories + user.map { it.category }).distinct()
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Bibliothek → Deck ${"AB"[k]}")
            .setItems(cats.toTypedArray()) { _, ci ->
                val cat = cats[ci]
                val own = user.filter { it.category == cat }
                val lib = SampleLibrary.samples.filter { it.category == cat }
                val labels = own.map { "★ " + it.title } + lib.map { it.title }
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(cat)
                    .setItems(labels.toTypedArray()) { _, i ->
                        if (i < own.size) {
                            val u = own[i]
                            loadDeckMono(k, u.title, u.file.name) { AudioFiles.decodeFile(u.file, Engine.sampleRate, 600) }
                        } else {
                            val smp = lib[i - own.size]
                            loadDeckMono(k, smp.title, smp.file) { AudioFiles.decodeAsset(this, smp.file, Engine.sampleRate, 600) }
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun loadDeckMono(k: Int, title: String, file: String?, get: () -> FloatArray) {
        val dd = Dj.decks[k]
        dd.loading = true
        Thread {
            try {
                val data = get()
                val rate = Engine.sampleRate
                // kurze Loops: Tempo wie beim Pad-Import (Länge + Rhythmus), lange Tracks: eigene Analyse
                val nameBpm = Warp.bpmFromName("$title ${file ?: ""}") ?: if (data.size < rate * 30) Warp.analyze(data, rate, title).bpm else null
                Dj.load(dd, Dj.fromMono(data, rate), title, nameBpm)
                runOnUiThread { hint(String.format(Locale.GERMANY, "Deck %s: %s · %.1f BPM", "AB"[k], title, dd.bpm)) }
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                dd.loading = false
            }
        }.start()
    }

    private fun loadDeckUri(k: Int, uri: Uri) {
        val dd = Dj.decks[k]
        dd.loading = true
        val name = displayName(uri) ?: "Track"
        hint("Deck ${"AB"[k]}: lade $name …")
        Thread {
            try {
                val t = Dj.decode(this, uri)
                Dj.load(dd, t, name.substringBeforeLast('.'), Warp.bpmFromName(name))
                runOnUiThread { hint(String.format(Locale.GERMANY, "Deck %s: %s · %.1f BPM · %s", "AB"[k], name, dd.bpm, Dj.fmt(dd.seconds()))) }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { toast("Track zu groß für den Speicher") }
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                dd.loading = false
            }
        }.start()
    }

    // ================================================================ Latenz, Live-Mikrofon, MIDI

    private fun dialogBox(): Pair<LinearLayout, android.widget.ScrollView> {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        return box to android.widget.ScrollView(this).apply { addView(box) }
    }

    private fun note(box: LinearLayout, t: String, col: Int = Color.rgb(170, 170, 170)): TextView {
        val tv = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(col)
            setPadding(0, 6, 0, 10)
        }
        box.addView(tv)
        return tv
    }

    private fun check(box: LinearLayout, label: String, on: Boolean, f: (Boolean) -> Unit): CheckBox {
        val cb = CheckBox(this).apply {
            text = label
            isChecked = on
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> f(c) }
        }
        box.addView(cb)
        return cb
    }

    private fun bluetoothOut(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }

    private fun headphonesOut(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private fun showLatency() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val (box, scroll) = dialogBox()
        val sr = Engine.sampleRate.toFloat()
        note(box, String.format(
            Locale.GERMANY,
            "Hardware-Puffer: %d Frames (%.1f ms)\nAusgabe-Puffer der App: %d Frames (%.1f ms)\nSchneller Audio-Pfad: %s · Aussetzer bisher: %d",
            Engine.burstFrames, Engine.burstFrames * 1000f / sr, Engine.outBufferFrames, Engine.outputLatencyMs(),
            if (Engine.fastPath) "ja" else "nein", Engine.underruns
        ), Color.WHITE)
        if (bluetoothOut()) note(box, "⚠ Bluetooth-Kopfhörer/-Lautsprecher verbunden: Bluetooth verzögert um 150–300 ms – das kann keine App ausgleichen. Für Live: Kabel-Kopfhörer oder USB-Audio.", Color.rgb(255, 150, 60))
        val group = android.widget.RadioGroup(this)
        Engine.LATENCY_NAMES.forEachIndexed { i, n ->
            group.addView(android.widget.RadioButton(this).apply {
                id = 1000 + i
                text = n
                setTextColor(Color.WHITE)
            })
        }
        group.check(1000 + Engine.latencyMode)
        group.setOnCheckedChangeListener { _, id ->
            Engine.latencyMode = id - 1000
            prefs.edit().putInt("latency", Engine.latencyMode).apply()
            scheduleAudioRestart()
        }
        box.addView(group)
        note(box, "AUTO startet mit dem kleinsten Puffer und vergrößert ihn nur, wenn es knackst.")
        check(box, "Loops starten schon beim Berühren (nicht erst beim Loslassen)", Engine.instantStart) {
            Engine.instantStart = it
            prefs.edit().putBoolean("instantStart", it).apply()
        }
        check(box, "Loops auch beim Berühren stoppen (Overdub dann per Halten auf einem gestoppten Loop)", Engine.instantStop) {
            Engine.instantStop = it
            prefs.edit().putBoolean("instantStop", it).apply()
        }
        check(box, "Start-Raster AUS – Loops reagieren sofort (phasenrichtig im Takt)", Engine.LAUNCH_Q[Engine.launchIdx] <= 0f) {
            if (it) {
                prefs.edit().putInt("launchIdxBefore", Engine.launchIdx).putInt("launchIdx", 0).apply()
                Engine.launchIdx = 0
            } else {
                Engine.launchIdx = prefs.getInt("launchIdxBefore", 3).coerceIn(1, Engine.LAUNCH_Q.size - 1)
                prefs.edit().putInt("launchIdx", Engine.launchIdx).apply()
            }
        }
        note(box, "ONE SHOTS und Noten klingen immer im nächsten Audio-Block (wenige Millisekunden). Touch-Ereignisse werden ohne Bildtakt-Verzögerung zugestellt.")
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("⚡ Latenz & Live-Reaktion")
            .setView(scroll)
            .setPositiveButton("Fertig", null)
            .show()
    }

    private fun showMonitor() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val (box, scroll) = dialogBox()
        if (!headphonesOut()) note(box, "⚠ Kein Kopfhörer erkannt – über den Lautsprecher pfeift es (Rückkopplung). Bitte Kopfhörer anschließen.", Color.rgb(255, 150, 60))
        else if (bluetoothOut()) note(box, "⚠ Bluetooth verzögert die Stimme hörbar – für Live-Gesang Kabel-Kopfhörer nutzen.", Color.rgb(255, 150, 60))
        check(box, "🎙 Mikrofon live hören", Engine.monitorOn) { Engine.monitorOn = it }
        val lab = note(box, "", Color.WHITE)
        fun show() { lab.text = "Lautstärke: ${(Engine.monitorGain * 100).toInt()} %" }
        show()
        box.addView(SeekBar(this).apply {
            max = 150
            progress = (Engine.monitorGain * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Engine.monitorGain = pr / 100f
                    show()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { prefs.edit().putFloat("monGain", Engine.monitorGain).apply() }
            })
        })
        check(box, "Zusätzlich durch die XY-Effekte (Effekt-Fläche spielen, während du singst)", Engine.monitorMaster) {
            Engine.monitorMaster = it
            prefs.edit().putBoolean("monMaster", it).apply()
        }
        box.addView(Button(this).apply {
            text = "✨ Effekte fürs Mikrofon (bis zu 3) …  " + (if (Engine.padChainActive(Engine.MIC_CHAIN)) "AN · ${Engine.padFxCount(Engine.MIC_CHAIN)}" else "aus")
            setOnClickListener { showPadFxEditor(Engine.MIC_CHAIN) }
        })
        note(box, "Mit Effekten aufnehmen: Pad-Eingang auf WAVE stellen (REC-Menü) – dann landet die Stimme samt Effekten im Pad. Der Song-Rekorder nimmt sie ebenfalls mit.")
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("🎙 Live-Mikrofon")
            .setView(scroll)
            .setPositiveButton("Fertig", null)
            .show()
    }

    private fun showMidi() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val (box, scroll) = dialogBox()
        val status = note(box, "", Color.WHITE)
        val live = note(box, "", Color.rgb(120, 220, 255))
        val ticker = object : Runnable {
            override fun run() {
                status.text = if (Midi.deviceNames.isEmpty()) "Kein MIDI-Gerät verbunden – Controller per USB (OTG-Adapter) anschließen oder eine MIDI-App starten."
                              else "Verbunden: " + Midi.deviceNames.joinToString(", ")
                live.text = if (Midi.lastEvent.isEmpty()) "Letztes Ereignis: –" else "Letztes Ereignis: ${Midi.lastEvent}"
                live.postDelayed(this, 250L)
            }
        }
        ticker.run()
        check(box, "MIDI an", Midi.enabled) { Midi.enabled = it }
        fun chName(c: Int) = if (c == 0) "Omni (alle)" else "$c"
        val chBtn = Button(this)
        fun chLabel() { chBtn.text = "Pad-Kanal: ${chName(Midi.channel)}" }
        chLabel()
        chBtn.setOnClickListener {
            val names = Array(17) { chName(it) }
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("MIDI-Kanal der Pads")
                .setSingleChoiceItems(names, Midi.channel) { d, w -> Midi.channel = w; chLabel(); d.dismiss() }.show()
        }
        box.addView(chBtn)
        val baseBtn = Button(this)
        fun baseLabel() { baseBtn.text = "Pad-Noten ab ${Midi.noteName(Midi.padBase)} (${Midi.padBase}) …" }
        baseLabel()
        baseBtn.setOnClickListener {
            val opts = (24..84).toList()
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Pad 1 = Note …")
                .setSingleChoiceItems(opts.map { "${Midi.noteName(it)} ($it)" + if (it == 36) " · MPC/Maschine" else if (it == 48) " · Keyboard C3" else "" }.toTypedArray(), opts.indexOf(Midi.padBase)) { d, w ->
                    Midi.padBase = opts[w]
                    for (i in 0 until 12) Midi.padNotes[i] = -1
                    baseLabel()
                    d.dismiss()
                }.show()
        }
        box.addView(baseBtn)
        box.addView(Button(this).apply {
            text = "🎓 Pad-Noten einzeln lernen …"
            setOnClickListener { midiLearnPads() }
        })
        check(box, "Anschlagdynamik (leise/laut wie gespielt)", Midi.velocity) { Midi.velocity = it }
        val keysBtn = Button(this)
        fun keysLabel() {
            keysBtn.text = "Tastatur-Kanal: " + (if (Midi.keysChannel == 0) "aus" else "${Midi.keysChannel}") + " · spielt " + (if (Midi.keysPad < 0) "das Piano-Roll-Pad" else "Pad ${Midi.keysPad + 1}")
        }
        keysLabel()
        keysBtn.setOnClickListener {
            val names = arrayOf("aus") + Array(16) { "Kanal ${it + 1}" }
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Kanal für die Tastatur (chromatisch, C4 = Originalton)")
                .setSingleChoiceItems(names, Midi.keysChannel) { d, w ->
                    Midi.keysChannel = w
                    d.dismiss()
                    val pn = arrayOf("Pad aus der offenen Piano-Roll") + Array(PadView.VISIBLE_PADS) { "Pad ${it + 1}" }
                    AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Welches Pad spielt die Tastatur?")
                        .setItems(pn) { _, p -> Midi.keysPad = p - 1; keysLabel() }.show()
                    keysLabel()
                }.show()
        }
        box.addView(keysBtn)
        box.addView(Button(this).apply {
            text = "🎛 Regler & Fader zuordnen (CC lernen) …"
            setOnClickListener { midiCcMap() }
        })
        check(box, "MIDI-Clock empfangen – Tempo folgt dem Gerät", Midi.clockIn) { Midi.clockIn = it }
        check(box, "Start/Stopp empfangen", Midi.transportIn) { Midi.transportIn = it }
        check(box, "MIDI-Clock + Start/Stopp senden (Drumcomputer, Synths synchron)", Midi.clockOut) { Midi.clockOut = it }
        check(box, "Programmwechsel startet Szene 1–8", Midi.programScenes) { Midi.programScenes = it }
        box.addView(Button(this).apply {
            text = "🔌 Geräte an/aus …"
            setOnClickListener {
                val all = Midi.allDevices()
                if (all.isEmpty()) { toast("Kein MIDI-Gerät gefunden"); return@setOnClickListener }
                val on = BooleanArray(all.size) { all[it] !in Midi.disabled }
                AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("MIDI-Geräte")
                    .setMultiChoiceItems(all.toTypedArray(), on) { _, w, c -> on[w] = c }
                    .setPositiveButton("OK") { _, _ ->
                        Midi.disabled.clear()
                        for (i in all.indices) if (!on[i]) Midi.disabled.add(all[i])
                        Midi.reopen()
                    }.show()
            }
        })
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("🎹 MIDI")
            .setView(scroll)
            .setPositiveButton("Fertig", null)
            .setOnDismissListener {
                live.removeCallbacks(ticker)
                Midi.learnPad = -1
                Midi.learnCc = -1
                Midi.save(prefs)
            }
            .show()
    }

    private fun midiLearnPads() {
        val labels = Array(PadView.VISIBLE_PADS + 1) { i ->
            if (i == PadView.VISIBLE_PADS) "↺ Alle zurücksetzen (Grundton + Pad)" else {
                val n = Midi.padNote(i)
                "Pad ${i + 1}: " + if (n < 0) "–" else "${Midi.noteName(n)} ($n)"
            }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Pad antippen, dann Taste am Controller drücken")
            .setItems(labels) { _, w ->
                if (w == PadView.VISIBLE_PADS) {
                    for (i in 0 until 12) Midi.padNotes[i] = -1
                    toast("Pad-Noten zurückgesetzt")
                } else {
                    Midi.learnPad = w
                    hint("Pad ${w + 1}: jetzt eine Taste/ein Pad am Controller drücken …")
                }
            }
            .setNegativeButton("Fertig", null)
            .show()
    }

    private fun midiCcMap() {
        val labels = Array(Midi.TARGETS.size) { i -> Midi.TARGETS[i] + ": " + (if (Midi.ccMap[i] < 0) "–" else "CC ${Midi.ccMap[i]}") }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Ziel wählen")
            .setItems(labels) { _, t ->
                AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(Midi.TARGETS[t])
                    .setItems(arrayOf("🎓 Lernen – jetzt Regler am Controller bewegen", "Nummer eingeben …", "Zuordnung löschen")) { _, a ->
                        when (a) {
                            0 -> {
                                Midi.learnCc = t
                                hint("${Midi.TARGETS[t]}: Regler am Controller bewegen …")
                            }
                            1 -> askName(if (Midi.ccMap[t] >= 0) "${Midi.ccMap[t]}" else "", "CC-Nummer (0–127)") { v ->
                                v.trim().toIntOrNull()?.let { n -> if (n in 0..127) Midi.ccMap[t] = n }
                            }
                            else -> Midi.ccMap[t] = -1
                        }
                    }
                    .show()
            }
            .setNegativeButton("Fertig", null)
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
            REQ_STEMS -> writeStems(uri)
            REQ_DECK -> loadDeckUri(djDeck, uri)
            REQ_MIDI_OUT -> writeMidi(uri)
            REQ_MIDI_IN -> readMidi(uri)
            REQ_INST_OUT -> writeInstMidi(uri)
            REQ_INST_IN -> readInstMidi(uri)
            REQ_IMPORT -> {
                if (pad < 0) return
                toast("Importiere …")
                val rate = Engine.sampleRate
                Thread {
                    try {
                        val samples = AudioFiles.decode(this, uri, rate, Engine.MAX_SECONDS)
                        if (Engine.padOneShot[pad]) {
                            Engine.importLoop(pad, samples)
                            runOnUiThread { toast("Pad ${pad + 1}: Import fertig") }
                        } else {
                            val name = displayName(uri)
                            val desc = Engine.importClip(pad, samples, Warp.analyze(samples, rate, name))
                            runOnUiThread { hint("Pad ${pad + 1}: ${name ?: "Datei"} · $desc · antippen startet ihn auf der Eins") }
                        }
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

    private var lastHint: Toast? = null

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** 📂 auf einem leeren Pad: Datei, Bibliothek oder eigene Aufnahme. */
    private fun showImportMenu(p: Int) {
        menuPad = p
        val shot = Engine.padOneShot[p]
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Pad ${p + 1} laden")
            .setItems(arrayOf(
                "📂 Datei (WAV / MP3) – Tempo wird erkannt und gewarpt",
                "📚 Bibliothek & eigene Aufnahmen",
                if (shot) "Modus: ⚡ ONE SHOT  →  auf ∞ LOOP" else "Modus: ∞ LOOP  →  auf ⚡ ONE SHOT"
            )) { _, w ->
                when (w) {
                    0 -> pickImport()
                    1 -> showLibrary(p)
                    else -> {
                        Engine.setPadMode(p, !shot)
                        showImportMenu(p)
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Kurzer Hinweis aus der Bedienung – ersetzt den vorigen sofort statt sich anzustauen. */
    private fun hint(msg: String) {
        lastHint?.cancel()
        lastHint = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    // ---------------------------------------------------------------- Effekte je Pad

    /** Bis zu drei Effekte für ein Pad wählen und einstellen. */
    private fun chainName(p: Int): String = if (p == Engine.MIC_CHAIN) "Mikrofon" else "Pad ${p + 1}"

    private fun showPadFxEditor(p: Int) {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        val master = CheckBox(this).apply {
            text = "Effekte für ${chainName(p)} an"
            isChecked = Engine.padChainOn[p]
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> Engine.padChainOn[p] = c }
        }
        box.addView(master)
        box.addView(TextView(this).apply {
            text = if (p == Engine.MIC_CHAIN) "Die Effekte liegen live auf deiner Stimme (1 › 2 › 3), solange „Mikrofon live hören“ an ist." else "Die Effekte laufen nacheinander (1 › 2 › 3) nur auf diesem Pad – auch bei Noten aus der Piano-Roll. Der kleine FX-Knopf am Pad schaltet sie an/aus."
            textSize = 12f
            setTextColor(Color.rgb(170, 170, 170))
            setPadding(0, 0, 0, pad / 2)
        })
        val slotBoxes = Array(3) { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
        fun buildSlot(k: Int) {
            val sb = slotBoxes[k]
            sb.removeAllViews()
            val t = Engine.pfxType[p][k]
            sb.addView(TextView(this).apply {
                text = "Platz ${k + 1}"
                textSize = 13f
                setTextColor(Color.rgb(255, 120, 30))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, pad / 2, 0, 0)
            })
            sb.addView(Button(this).apply {
                text = if (t >= 0) Fx.NAMES[t] + "  ▾" else "— leer —  ▾"
                setOnClickListener {
                    val names = arrayOf("— leer —") + Fx.NAMES
                    AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("${chainName(p)} · Platz ${k + 1}")
                        .setItems(names) { _, w ->
                            Engine.setPadFxType(p, k, w - 1)
                            if (w > 0) {
                                Engine.padChainOn[p] = true
                                master.isChecked = true
                            }
                            buildSlot(k)
                        }
                        .show()
                }
            })
            if (t < 0) return
            val labels = arrayOf("X · " + Fx.X_LABELS[t], "Y · " + Fx.Y_LABELS[t], "MIX", Fx.P1_LABELS[t], Fx.P2_LABELS[t])
            for (i in 0 until 5) {
                val lab = TextView(this).apply { textSize = 11f; setTextColor(Color.rgb(200, 200, 200)) }
                fun show(v: Float) { lab.text = "${labels[i]}: ${(v * 100).toInt()}%" }
                show(Engine.pfxPar[p][k][i])
                sb.addView(lab)
                sb.addView(SeekBar(this).apply {
                    max = 1000
                    progress = (Engine.pfxPar[p][k][i] * 1000).toInt()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sbar: SeekBar?, pr: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            Engine.pfxPar[p][k][i] = pr / 1000f
                            show(pr / 1000f)
                        }
                        override fun onStartTrackingTouch(sbar: SeekBar?) {}
                        override fun onStopTrackingTouch(sbar: SeekBar?) {}
                    })
                })
            }
        }
        for (k in 0 until 3) {
            buildSlot(k)
            box.addView(slotBoxes[k])
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("✨ Effekte ${chainName(p)}")
            .setView(scroll)
            .setPositiveButton("Fertig", null)
            .show()
    }

    // ---------------------------------------------------------------- Szenen

    private fun showSceneMenu(i: Int) {
        val exists = Engine.sceneExists(i)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (i != Engine.curScene) {
            labels.add(if (exists) "▶ Szene ${i + 1} starten (nächste Eins)" else "＋ Szene ${i + 1} anlegen (Kopie der aktuellen)")
            actions.add { Engine.launchScene(i) }
        }
        labels.add("⏱ Länge in der Kette: ${Engine.sceneBars(i)} Takt" + (if (Engine.sceneBars(i) > 1) "e" else ""))
        actions.add {
            val opts = intArrayOf(1, 2, 4, 8, 16, 32)
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Szene ${i + 1}: Länge")
                .setItems(opts.map { "$it Takt" + if (it > 1) "e" else "" }.toTypedArray()) { _, w ->
                    if (!Engine.sceneExists(i)) Engine.launchScene(i)
                    Engine.setSceneBars(i, opts[w])
                }
                .show()
        }
        labels.add(if (Engine.sceneChain) "⛓ Kette (Song-Modus): AN → ausschalten" else "⛓ Kette (Song-Modus): AUS → einschalten")
        actions.add {
            Engine.sceneChain = !Engine.sceneChain
            toast(if (Engine.sceneChain) "Kette an: Szenen laufen nacheinander, jede so lange wie eingestellt" else "Kette aus")
        }
        if (exists) {
            labels.add(if (i == Engine.curScene) "🗑 Szene leeren (Pattern & Instrumente)" else "🗑 Szene löschen")
            actions.add { Engine.deleteScene(i) }
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Szene ${i + 1}" + if (i == Engine.curScene) " (läuft)" else "")
            .setItems(labels.toTypedArray()) { _, w -> actions[w]() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    companion object {
        private const val REQ_MIC = 7
        private const val REQ_IMPORT = 8
        private const val REQ_EXPORT = 9
        private const val REQ_SONG = 10
        private const val REQ_PATTERN = 11
        private const val REQ_COPY = 12
        private const val REQ_STEMS = 13
        private const val REQ_DECK = 14
        private const val REQ_MIDI_OUT = 15
        private const val REQ_MIDI_IN = 16
        private const val REQ_INST_OUT = 17
        private const val REQ_INST_IN = 18
    }
}
