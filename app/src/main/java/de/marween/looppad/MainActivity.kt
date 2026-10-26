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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var pads: PadView
    private lateinit var dj: DjView
    private var djMode = false
    private var djDeck = 0
    private var askedOnce = false

    /** Die aktive Oberfläche: Pads oder DJ-Controller. */
    private fun mainView(): View = remoteView ?: if (djMode) dj else pads

    /** Fernbedienungs-Modus: dieses Handy steuert ein anderes (kein eigener Klang). */
    private var remoteView: RemoteView? = null

    private fun switchMode(toDj: Boolean) {
        if (toDj == djMode && mainView().parent != null) return
        djMode = toDj
        if (toDj) pads.closeFocus()
        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("djMode", toDj).apply()
        applyDjOrientation()
        val v = mainView()
        v.alpha = 0f
        setContentView(v)
        hideSystemBars()
        v.requestApplyInsets()
        v.animate().alpha(1f).setDuration(250L).start()
        if (toDj) hint("🎧 DJ-Modus: oben Track antippen zum Laden · ▦ PADS wechselt zurück")
    }

    /** DJ-Modus im Querformat (wie ein Controller) – abschaltbar; Pad-Modus bleibt hochkant. */
    private fun applyDjOrientation() {
        val land = getSharedPreferences("marvpad", MODE_PRIVATE).getBoolean("djLand", true)
        requestedOrientation = if (djMode && land) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        Engine.initSpill(filesDir)
        applyFrameRate(getSharedPreferences("marvpad", MODE_PRIVATE).getBoolean("fps60", true))
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
        prefs.getString("padSrcMask", null)?.split(",")?.forEachIndexed { i, v ->
            if (i < Engine.PADS) Engine.padSrcMask[i] = v.toIntOrNull() ?: 0
        }
        prefs.getString("padInstMask", null)?.split(",")?.forEachIndexed { i, v ->
            if (i < Engine.PADS) Engine.padInstMask[i] = v.toIntOrNull() ?: 7
        }
        Engine.liveBars = prefs.getInt("liveBars", 0)
        Engine.bounce = prefs.getBoolean("bounce", true)
        Engine.onBouncedInst = { pad, names ->
            runOnUiThread { toast("Bounce → Pad ${pad + 1} · ausgeschaltet: $names (im Reiter Instrumente wieder AN)") }
        }
        Engine.onFxBaked = { pad ->
            runOnUiThread { toast("Pad ${pad + 1}: XY-Effekt ist schon mit aufgenommen – das Pad läuft jetzt am Effekt vorbei, damit er nicht doppelt wirkt (Pad-Menü ⋯ → 🎛 XY-Effekt schaltet es zurück)") }
        }
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
        Engine.playMode = prefs.getInt("playMode", 0).coerceIn(0, Engine.PLAY_NAMES.size - 1)
        Engine.autoBeatmatch = prefs.getBoolean("autoBeat", true)
        MenuStyle.load(this)
        pads.zoomEnabled = prefs.getBoolean("zoomOn", true)
        zoomLandscape = prefs.getBoolean("zoomLand", false)
        pads.onFocusChanged = { open ->
            if (zoomLandscape) requestedOrientation = if (open) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (Engine.savedSignature == 0L) Engine.markSaved()
        pads.onPlayModeChanged = {
            prefs.edit().putInt("playMode", Engine.playMode).apply()
            hint(when (Engine.playMode) {
                1 -> "⇄ WECHSELN: immer nur ein Loop – der neue löst den alten genau im Start-Raster ab"
                2 -> "⇄ JE REIHE: pro Pad-Reihe läuft ein Loop, die Reihen spielen zusammen"
                else -> "⇉ ZUSAMMEN: angetippte Loops laufen gemeinsam"
            })
        }
        Engine.tapOverdub = prefs.getBoolean("tapOverdub", true)
        pads.statusColors = prefs.getBoolean("statusColors", true)
        pads.onImportMenu = { p -> showImportMenu(p) }
        pads.onDrumKit = { showDrumKits() }
        pads.onTimingCalib = { showTimingCalib() }
        pads.padGrid = prefs.getInt("padGrid", 0)
        loadSidechain()
        pads.seqFold = prefs.getBoolean("seqFold", false)
        pads.onLayoutPrefs = { prefs.edit().putInt("padGrid", pads.padGrid).putBoolean("seqFold", pads.seqFold).apply() }
        pads.onDrumClear = { p ->
            val items = ArrayList<String>()
            val acts = ArrayList<() -> Unit>()
            if (p >= 0) { items.add("⌫ Spur von Pad ${p + 1} leeren"); acts.add { Engine.clearHits(p); hint("Spur von Pad ${p + 1} geleert – ↶ UNDO holt sie zurück") } }
            items.add("⌫ Alle Drum-Spuren leeren"); acts.add { Engine.clearHits(-1); hint("Alle Drum-Spuren geleert – ↶ UNDO holt sie zurück") }
            ui().setTitle("Drum-Spuren leeren").setItems(items.toTypedArray()) { _, w -> acts[w]() }.setNegativeButton("Abbrechen", null).show()
        }
        pads.onPadFxEditor = { p -> showPadFxEditor(p) }
        pads.onEditorSave = { p, data, slices, cues -> showEditorSave(p, data, slices, cues) }
        pads.onRollIo = { p -> showRollIo(p) }
        pads.onInstMidi = { k -> showInstMidi(k) }
        pads.onInstPresets = { k -> showInstPresets(k) }
        pads.onInstScale = { showScaleMenu() }
        pads.onInstChords = { showChordProgression() }
        pads.onSidechain = { showSidechain() }
        pads.onSlicerSource = { pickSlicerSource() }
        pads.onTourDone = {
            val pr = getSharedPreferences("marvpad", MODE_PRIVATE)
            pr.edit().putBoolean("tourDone", true).apply()
            if (!pr.getBoolean("lessonDone0", false)) ui().setTitle("🎓 Jetzt deinen ersten Song bauen?")
                .setMessage("In der Lektion „Dein erster Song“ führe ich dich Schritt für Schritt durch Beat, Bass, Akkorde, Melodie, eigene Aufnahme und Effekte. Alle Lektionen findest du im Menü unter ❓ Hilfe.")
                .setPositiveButton("Los geht's") { _, _ -> pads.startLesson(0) }.setNegativeButton("Später", null).show()
        }
        pads.onLessonDone = { i -> getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("lessonDone$i", true).apply(); hint("🎓 Lektion geschafft!") }
        pads.onLessonAction = { id ->
            when (id) {
                "kit" -> SampleLibrary.KITS.firstOrNull()?.let { kit -> val empty = (0 until min(12, kit.files.size)).filter { !Engine.hasLoop(it) }; if (empty.isNotEmpty()) loadKit(kit, empty) }
                "calib" -> calibrateMic()
                "calibAsk" -> ui().setTitle("🎯 Mikro-Timing einmessen?")
                    .setMessage("Damit deine Loops genau auf der Eins beginnen, misst die App einmal, wie spät das Mikrofon bei deinem Handy ankommt (8× zum Klick klatschen, ca. 10 Sekunden). Ohne das liegen Aufnahmen oft ein Stück versetzt.")
                    .setPositiveButton("Einmessen") { _, _ -> calibrateMic() }
                    .setNegativeButton("Ohne aufnehmen") { _, _ ->
                        Engine.micCalibrated = true
                        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putFloat("micOffset", Engine.micOffsetMs).apply()
                        hint("Okay – jetzt die Spur nochmal antippen. Einmessen geht jederzeit im Menü (Mikrofon).")
                    }.show()
                "beatbox" -> askMicCapture(1)
                "hum" -> askMicCapture(0)
                "autotune" -> showAutoTune()
            }
        }
        if (!prefs.getBoolean("tourDone", false)) pads.postDelayed({ if (!djMode && pads.parent != null) pads.startTour() }, 2500L)
        CrashLog.take(this)?.let { rep ->
            pads.postDelayed({
                ui().setTitle("😕 Marv PAD ist beim letzten Mal abgestürzt")
                    .setMessage("Schick den Bericht an den Entwickler (Teilen) oder kopiere ihn – dann lässt sich der Fehler genau finden.\n\n" + rep.take(1500))
                    .setPositiveButton("Teilen") { _, _ ->
                        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Marv PAD Absturz"); putExtra(Intent.EXTRA_TEXT, rep) }, "Bericht teilen")) } catch (e: Exception) {}
                    }
                    .setNeutralButton("Kopieren") { _, _ ->
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Absturz", rep))
                        toast("Bericht kopiert – einfach in den Chat einfügen")
                    }
                    .setNegativeButton("Schließen", null).show()
            }, 1200L)
        }
        pads.onSlicerToPads = {
            val n = Engine.slicerToPads(PadView.VISIBLE_PADS)
            hint(if (n > 0) "✂ $n Slices als ⚡ One-Shots auf freie Pads gelegt" else "Keine freien Pads – erst Pads leeren")
        }
        pads.onLaunchQuantChanged = {
            prefs.edit().putInt("launchIdx", Engine.launchIdx).apply()
            hint(if (Engine.LAUNCH_Q[Engine.launchIdx] <= 0f) "Start-Raster SOFORT: Loops starten/stoppen beim Antippen"
                 else "Start-Raster ${Engine.LAUNCH_NAMES[Engine.launchIdx]}: angetippte Loops starten/stoppen auf dem nächsten Rasterpunkt")
        }
        pads.onSongStopped = { finishSong() }
        run {
            // „Claudes Design“ einmalig aktivieren (danach gilt wieder die eigene Wahl)
            val pr = getSharedPreferences("marvpad", MODE_PRIVATE)
            if (!pr.getBoolean("claudeDesignOnce", false)) {
                pr.edit().putBoolean("claudeDesignOnce", true).putInt("design", PadView.CLAUDE).putInt("djSkin", DjView.SKINS.size - 1).apply()
            }
        }
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
        Dj.autoGain = prefs.getBoolean("djAutoGain", true)
        TouchVelocity.mode = prefs.getInt("velMode", 0)
        TouchVelocity.curve = prefs.getInt("velCurve", 1)
        TouchVelocity.floor = prefs.getFloat("velFloor", 0.2f)
        Engine.recOffsetMs = prefs.getFloat("recOffsetMs", 0f)
        Engine.tightTiming = prefs.getBoolean("tightTiming", true)
        Engine.quadOut = prefs.getBoolean("quadOut", false)
        Dj.colorWave = prefs.getBoolean("djColorWave", true)
        Dj.changeTargetLufs(prefs.getFloat("djTargetLufs2", -14f))
        dj.onLoad = { k -> showDeckLoad(k) }
        dj.onMenu = { showMainMenu() }
        dj.onTempo = { showTempo() }
        dj.onPadsMode = { switchMode(false) }
        dj.onHint = { hint(it) }
        dj.onAiStems = { k -> showAiStems(k) }
        dj.onLessonAction = { id -> if (id == "mashup") showMashup() }
        dj.onLessonDone = { getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("lessonDone${pads.lessonList().size}", true).apply(); hint("🎓 DJ-Lektion geschafft!") }
        AiStems.auto = prefs.getBoolean("aiAuto", true)
        Engine.autoTuneSpeed = prefs.getFloat("atSpeed", 0.25f)
        Engine.micOffsetMs = prefs.getFloat("micOffset", 0f)
        Engine.micCalibrated = prefs.contains("micOffset")
        InstState.liveAmt = prefs.getFloat("liveAmt", 0f)
        InstState.liveFills = prefs.getBoolean("liveFills", true)
        InstState.livePhrase = prefs.getInt("livePhrase", 8)
        Engine.autoTuneMix = prefs.getFloat("atMix", 1f)
        Dj.onDeckLoaded = { d -> if (AiStems.auto) AiStems.request(applicationContext, d) }
        dj.onSkinMenu = { showDesigns() }
        dj.onSamplerLoad = { p -> showSamplerLoad(p) }
        dj.onEditSaveLib = { k -> saveDeckToLib(k) }
        dj.onSetRec = { toggleSetRec() }
        dj.onKeyMenu = { k -> showKeyMenu(k) }
        pads.onSetRec = { toggleSetRec() }
        pads.onInfo = { showLogoMenu() }
        dj.onInfo = { showLogoMenu() }
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

    // ---------------------------------------------------------------- Info-Seite

    private class ChangeEntry(val version: String, val date: String, val items: List<String>)

    private fun loadChangelog(): List<ChangeEntry> = try {
        val txt = assets.open("changelog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = org.json.JSONArray(txt)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val it = o.optJSONArray("items")
            ChangeEntry(o.optString("version"), o.optString("date"),
                if (it == null) emptyList() else (0 until it.length()).map { j -> it.getString(j) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun showAbout() {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val log = loadChangelog()
        val updated = log.firstOrNull { it.version == version }?.date ?: log.firstOrNull()?.date ?: try {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(packageManager.getPackageInfo(packageName, 0).lastUpdateTime))
        } catch (e: Exception) { "–" }
        val accent = Color.rgb(0, 229, 255)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24f), px(20f), px(24f), px(12f))
        }
        box.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(56f))
        })
        fun line(t: CharSequence, size: Float, color: Int, top: Float, bold: Boolean = false) = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(0, px(top), 0, 0)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }.also { box.addView(it) }
        line("Marv PAD", 22f, Color.WHITE, 10f, true)
        line("Version $version", 15f, accent, 4f)
        line("Letzte Aktualisierung: $updated", 13f, Color.argb(210, 255, 255, 255), 6f)
        line("${log.size} Versionen seit Version 1.0.1", 12f, Color.argb(150, 255, 255, 255), 2f)
        line("📄 Alle Änderungen seit Version 1", 15f, accent, 18f, true).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(px(12f), px(14f), px(12f), px(10f))
            isClickable = true
            setOnClickListener { showChangelog(log) }
        }
        val credit = android.text.SpannableString("Made with Claude ©marween")
        val st = credit.indexOf("©marween")
        credit.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) { showContact() }
            override fun updateDrawState(ds: android.text.TextPaint) {
                ds.color = accent
                ds.isUnderlineText = true
            }
        }, st, credit.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line(credit, 14f, Color.WHITE, 18f).apply {
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            setPadding(px(8f), px(18f), px(8f), px(8f))
        }
        ui()
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun showContact() {
        val mail = "sidmarween@gmx.de"
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = "✉  $mail"
            textSize = 18f
            setTextColor(Color.rgb(0, 229, 255))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            gravity = Gravity.CENTER
            setPadding((20 * d).toInt(), (22 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
        }
        val hintTv = TextView(this).apply {
            text = "Antippen öffnet dein Mail-Programm"
            textSize = 12f
            setTextColor(Color.argb(150, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * d).toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(hintTv)
        }
        val dlg = ui()
            .setTitle("Kontakt – marween")
            .setView(box)
            .setNegativeButton("Schließen", null)
            .create()
        tv.setOnClickListener {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$mail")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "Marv PAD")
            }
            try {
                pickerOpen = true
                startActivity(i)
                dlg.dismiss()
            } catch (e: Exception) {
                pickerOpen = false
                Toast.makeText(this, "Kein Mail-Programm gefunden", Toast.LENGTH_SHORT).show()
            }
        }
        dlg.show()
    }

    private fun showChangelog(log: List<ChangeEntry>) {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val accent = Color.rgb(0, 229, 255)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(8f), px(20f), px(16f))
        }
        if (log.isEmpty()) box.addView(TextView(this).apply {
            text = "Keine Änderungsliste gefunden."
            setTextColor(Color.WHITE)
        })
        for (e in log) {
            box.addView(TextView(this).apply {
                text = "Version ${e.version}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
                setPadding(0, px(14f), 0, 0)
            })
            box.addView(TextView(this).apply {
                text = e.date
                textSize = 11f
                setTextColor(Color.argb(140, 255, 255, 255))
                setPadding(0, 0, 0, px(4f))
            })
            for (it in e.items) box.addView(TextView(this).apply {
                text = "•  $it"
                textSize = 13.5f
                setTextColor(Color.argb(230, 255, 255, 255))
                setPadding(px(6f), px(2f), 0, px(2f))
            })
        }
        ui()
            .setTitle("Änderungen seit Version 1")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Schließen", null)
            .show()
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
        if (remoteView == null) ensureStarted()
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
        TouchVelocity.update(this)
        window.decorView.postDelayed(deckAutosave, 5_000L)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(deckAutosave)
        try { for (k in 0..1) saveDeckIfChanged(k) } catch (ignored: Throwable) {}
        TouchVelocity.stop()
        super.onPause()
    }

    override fun onStop() {
        // Während einer Dateiauswahl oder wenn im DJ-Modus ein Deck läuft, spielt die Musik weiter
        val djPlaying = djMode && (Dj.decks[0].playing || Dj.decks[1].playing)
        // läuft eine Set-Aufnahme, bleibt das Audio an
        if (!pickerOpen && !djPlaying && !SetRecorder.recording && !Link.hosting) Engine.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            Link.stopHost()
            Link.disconnect()
            SetRecorder.stop()
            Engine.stop()
        }
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
        if (requestCode == REQ_BT) { btAfter?.invoke(); btAfter = null; return }
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
            .putString("padSrcMask", Engine.padSrcMask.joinToString(","))
            .putString("padInstMask", Engine.padInstMask.joinToString(","))
            .putInt("liveBars", Engine.liveBars)
            .apply()
    }

    // ---------------------------------------------------------------- Eingang eines Pads

    private fun srcLabel(p: Int): String = when (Engine.padSource[p]) {
        3 -> "🎹 " + Engine.INST_NAMES.filterIndexed { k, _ -> (Engine.padInstMask[p] shr k) and 1 != 0 }.joinToString(" + ")
        2 -> "🎛 Pads " + Engine.maskPads(Engine.padSrcMask[p]).joinToString("+") { "${it + 1}" }
        1 -> "〰 Wave (App-Audio)"
        else -> "🎙 Mikrofon"
    }

    /** Eingang wählen: Mikrofon, gesamtes App-Audio oder einzelne Pads / Pad-Gruppen. */
    private fun showSourceMenu(p: Int) {
        val labels = arrayOf(
            "🎙 Mikrofon",
            "〰 Wave – alles, was die App ausgibt (inkl. Effekte)",
            "🎛 Andere Pads als Eingang (einzeln oder als Gruppe) …",
            "🎹 Instrumente: 303, Drumcomputer, Synth …"
        )
        val cur = Engine.padSource[p].coerceIn(0, 3)
        ui()
            .setTitle("Eingang für Pad ${p + 1}")
            .setSingleChoiceItems(labels, cur) { d, w ->
                d.dismiss()
                when (w) {
                    0, 1 -> {
                        Engine.padSource[p] = w; Engine.capOwner = p
                        saveRecPrefs()
                        toast("Pad ${p + 1}: Eingang " + srcLabel(p))
                    }
                    2 -> showPadGroupPicker(p)
                    else -> showInstSourcePicker(p)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Reiter „Instrumente“: ACID 303, DRUM 909 und SYNTH einzeln oder zusammen als Eingang. */
    private fun showInstSourcePicker(p: Int) {
        val names = arrayOf("🔊 ACID 303 (Bass)", "🥁 DRUM 909 (Drumcomputer)", "🎹 SYNTH (polyfon, Akkorde)")
        val m0 = if (Engine.padSource[p] == 3) Engine.padInstMask[p] else 7
        val checked = BooleanArray(3) { (m0 shr it) and 1 != 0 }
        ui()
            .setTitle("🎹 Instrumente → Pad ${p + 1}")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Übernehmen") { _, _ ->
                var m = 0
                for (i in 0 until 3) if (checked[i]) m = m or (1 shl i)
                if (m == 0) {
                    toast("Kein Instrument gewählt – Eingang bleibt " + srcLabel(p))
                } else {
                    Engine.padInstMask[p] = m
                    Engine.padSource[p] = 3; Engine.capOwner = p
                    saveRecPrefs()
                    toast("Pad ${p + 1} nimmt auf: " + srcLabel(p) + " · Instrumente laufen mit ▶")
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showPadGroupPicker(p: Int) {
        val others = (0 until PadView.VISIBLE_PADS).filter { it != p }
        val names = others.map { q ->
            val st = when {
                !Engine.hasLoop(q) -> "leer"
                Engine.isOneShot(q) -> "⚡ One-Shot"
                Engine.isMuted(q) -> "∞ Loop · stumm"
                else -> "∞ Loop"
            }
            "Pad ${q + 1} · $st"
        }.toTypedArray()
        val mask0 = if (Engine.padSource[p] == 2) Engine.padSrcMask[p] else 0
        val checked = BooleanArray(others.size) { (mask0 shr others[it]) and 1 != 0 }
        val dlg = ui()
            .setTitle("Pad ${p + 1} nimmt auf von …")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Übernehmen") { _, _ ->
                var m = 0
                for (i in others.indices) if (checked[i]) m = m or (1 shl others[i])
                if (m == 0) {
                    toast("Keine Pads gewählt – Eingang bleibt " + srcLabel(p))
                } else {
                    Engine.padSrcMask[p] = m
                    Engine.padSource[p] = 2; Engine.capOwner = p
                    saveRecPrefs()
                    toast("Pad ${p + 1} nimmt auf: " + srcLabel(p) + " · halten/antippen zum Aufnehmen")
                }
            }
            .setNeutralButton("Alle laufenden", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.setOnShowListener {
            // Schnellwahl: alle gerade laufenden Loops/Pads (bleibt offen zum Anpassen)
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val lv = dlg.listView
                for (i in others.indices) {
                    val q = others[i]
                    val on = Engine.hasLoop(q) && !Engine.isMuted(q)
                    checked[i] = on
                    lv.setItemChecked(i, on)
                }
            }
        }
        dlg.show()
    }

    // ---------------------------------------------------------------- Auto-Beatmatch

    private fun beatKey(name: String?, data: FloatArray) = (name ?: "?").trim() + "|" + data.size

    private fun cachedInfo(key: String): Warp.Info? = try {
        val j = getSharedPreferences("beatmatch", MODE_PRIVATE).getString(key, null)?.let { org.json.JSONObject(it) }
        j?.let { Warp.Info(it.getDouble("bpm").toFloat(), it.getDouble("bars").toFloat(), "gespeichert", it.optInt("off", 0), it.optBoolean("cut", false)) }
    } catch (e: Exception) { null }

    private fun saveInfo(key: String, i: Warp.Info) {
        try {
            val j = org.json.JSONObject().put("bpm", i.bpm.toDouble()).put("bars", i.bars.toDouble()).put("off", i.offset).put("cut", i.cut)
            getSharedPreferences("beatmatch", MODE_PRIVATE).edit().putString(key, j.toString()).apply()
        } catch (ignored: Exception) {}
    }

    /** So viele Sekunden lädt ein Pad, wie der freie Speicher sicher hergibt (Song + Kopie fürs Tempo + Analyse). */
    private fun memSeconds(rate: Int): Int {
        System.gc()
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        // Laden braucht jetzt etwa 2× den Song (Dekodieren + Dehnen) – das Original wandert danach auf den Handy-Speicher
        return (free / (rate * 4.0 * 2.3)).toInt().coerceIn(30, Engine.MAX_IMPORT_SECONDS)
    }

    // ---------------------------------------------------------------- Musik-Sammlung (Ordner → katalogisiert)

    private fun showMusicLibrary() {
        TrackDb.load(this)
        val all = TrackDb.all()
        val done = all.count { it.analyzed }
        val open = all.count { !it.analyzed && !it.missing }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("📁 Ordner hinzufügen – alle Songs darin werden aufgenommen" to { pickCollectionFolder() })
        items.add("🎵 Einzelne Songs hinzufügen" to { pickCollectionFiles() })
        if (all.isNotEmpty()) items.add("📋 Sammlung durchsuchen (BPM, Tonart, Crates) → ins Deck" to { showCollection(0) })
        if (open > 0) items.add((if (colBatch) "⟳ Analyse läuft … ($open offen)" else "⟳ Analyse fortsetzen ($open offen)") to { startBatchAnalysis(); hint("Analyse läuft im Hintergrund – App offen lassen") })
        ui().setTitle("💿 Musik-Sammlung · ${all.size} Songs · $done analysiert")
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setNegativeButton("Schließen", null)
            .show()
        if (all.isEmpty()) hint("Ordner wählen: alle Songs darin bekommen Tempo, Takt-Raster, Tonart und Lautheit – danach laden sie sofort synchron in Decks und Pads")
    }

    /** Song aus der Sammlung aufs Pad – mit gespeichertem Tempo und erstem Schlag, ohne neue Analyse. */
    private fun pickCollectionForPad(p: Int) {
        TrackDb.load(this)
        val list = TrackDb.all().filter { !it.missing }.sortedBy { it.title.lowercase() }
        if (list.isEmpty()) { showMusicLibrary(); return }
        val labels = list.map { t ->
            t.title + "\n   " + (if (t.analyzed && t.bpm > 0f) String.format(Locale.GERMANY, "%.1f BPM · %s · %s", t.bpm, Keys.camelot(t.key), Dj.fmt(t.seconds)) else "noch nicht analysiert")
        }.toTypedArray()
        ui().setTitle("Pad ${p + 1} · aus der Sammlung (${list.size})")
            .setItems(labels) { _, w -> loadPadFromCollection(p, list[w]) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun loadPadFromCollection(p: Int, t: TrackDb.Track) {
        val rate = Engine.sampleRate
        Thread {
            try { padProgress(p) {
                LoadProgress.stage(0f, 0.65f)
                val secs = memSeconds(rate)
                val data = AudioFiles.decode(this, Uri.parse(t.uri), rate, secs)
                if (Engine.padOneShot[p]) {
                    Engine.importLoop(p, data)
                    runOnUiThread { hint("Pad ${p + 1}: ${t.title} (⚡ One Shot)") }
                } else if (Engine.autoBeatmatch && t.analyzed && t.bpm > 0f) {
                    val off = (t.grid * rate).toInt().coerceIn(0, max(0, data.size - 2))
                    val usable = (data.size - off).toFloat() / rate
                    var bars = kotlin.math.floor(usable * t.bpm / 240f)
                    if (usable <= 45f) { var pb = 1f; while (pb * 2 <= bars && pb < 32f) pb *= 2; bars = pb }
                    val info = Warp.Info(t.bpm, max(1f, bars.coerceAtMost(1024f)), "Sammlung", off, off > 0)
                    val desc = Engine.importClip(p, data, info)
                    runOnUiThread { hint("Pad ${p + 1}: ${t.title} · $desc · antippen startet ihn auf der Eins") }
                } else {
                    val desc = clipToPad(p, data, rate, t.title)
                    runOnUiThread { hint("Pad ${p + 1}: ${t.title} · $desc") }
                }
            } } catch (e: OutOfMemoryError) {
                runOnUiThread { toast("Song zu lang für den Speicher") }
            } catch (e: SecurityException) {
                runOnUiThread { toast("Kein Zugriff mehr auf „${t.title}“ – Ordner neu hinzufügen") }
            } catch (e: Exception) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    /** Lade-Fortschritt am Pad anzeigen, solange [block] läuft. */
    private fun <T> padProgress(p: Int, block: () -> T): T {
        Engine.padLoadText[p] = "lädt"
        try {
            return LoadProgress.run({ Engine.padLoadProg[p] = it }) { block() }
        } finally { Engine.padLoadProg[p] = -1f }
    }

    /** Lade-Fortschritt im DJ-Deck: Dekodieren bis 85 %, danach Analyse. */
    private fun deckDecode(dd: Dj.Deck, get: () -> Dj.Track): Dj.Track {
        val t = LoadProgress.run({ dd.loadProg = it * 0.85f }) { get() }
        dd.loadProg = 0.9f
        return t
    }

    /** Datei auf ein Pad laden – mit Auto-Beatmatch (einmal analysiert, dann gemerkt) oder im Original-Tempo. */
    private fun clipToPad(p: Int, data: FloatArray, rate: Int, name: String?): String {
        if (!Engine.autoBeatmatch) return Engine.importFree(p, data)
        val key = beatKey(name, data)
        Engine.padLoadText[p] = "Tempo-Analyse"
        LoadProgress.stage(0.6f, 0.7f)
        val info = cachedInfo(key) ?: Warp.analyze(data, rate, name).also { saveInfo(key, it) }
        return Engine.importClip(p, data, info)
    }

    /** Pad nachträglich ins Raster bringen (Beatmatch) bzw. zurück ins Original-Tempo. */
    private fun beatmatchPad(p: Int, on: Boolean) {
        val src = Engine.padSource(p) ?: return
        val rate = Engine.sampleRate
        toast(if (on) "Beatmatch läuft …" else "Original-Tempo …")
        Thread {
            val desc = if (on) Engine.importClip(p, src, Warp.analyze(src, rate, null)) else Engine.importFree(p, src)
            runOnUiThread { hint("Pad ${p + 1}: $desc · antippen startet ihn") }
        }.start()
    }

    // ---------------------------------------------------------------- Auf die Eins tappen

    /**
     * Einspiel-Timing kalibrieren: im Klick mittippen – die App misst, wie viel deine Anschläge
     * systematisch daneben liegen (Touch-Verzögerung, Bluetooth …) und zieht das beim Aufnehmen ab.
     * Dazu Anschlagsdynamik (Art, Kurve, leisester Schlag) und jitterfreies Auslösen.
     */
    private fun showTimingCalib() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val wasPlaying = Engine.seqPlaying
        val wasMetro = Engine.metronome
        if (!wasPlaying) Engine.seqPlay()
        Engine.metronome = true
        val taps = ArrayList<Float>()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), px(6f), px(18f), px(6f)) }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        box.addView(TextView(this).apply {
            text = "1) Hör auf den Klick und tippe 8–16 Mal genau mit – so, wie du auch trommelst.\n2) „Übernehmen“: Deine Schläge werden beim Aufnehmen um diesen Versatz korrigiert, damit AUTO-Q sie auf den richtigen Platz legt."
            textSize = 13f
        })
        val info = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(0, px(10f), 0, px(6f)); setTextColor(Color.rgb(255, 176, 40)) }
        fun median(): Float? = if (taps.size < 4) null else taps.drop(2).sorted().let { it[it.size / 2] }
        fun update() {
            val m = median()
            val spread = if (taps.size > 4) taps.drop(2).let { it.maxOrNull()!! - it.minOrNull()!! } else 0f
            info.text = (if (m == null) "${taps.size} Taps – weiter mittippen …" else String.format(Locale.GERMANY, "%d Taps · du liegst %+.0f ms %s · Streuung %.0f ms", taps.size, m, if (m > 0) "zu spät" else "zu früh", spread)) +
                String.format(Locale.GERMANY, "\nJetzt eingestellt: %+.0f ms · Auslöse-Verzögerung %.0f ms", -Engine.recOffsetMs, Engine.triggerDelayMs())
        }
        update()
        box.addView(info)
        box.addView(Button(this).apply {
            text = "TAP"
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(255, 106, 16))
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    Engine.beatOffsetMs(TouchVelocity.eventNanos(e))?.let { taps.add(it); if (taps.size > 24) taps.removeAt(0) }
                    update()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                true
            }
        }, LinearLayout.LayoutParams(-1, px(120f)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(8f), 0, 0) }
        fun nb(t: String, f: () -> Unit) = row.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f(); update() } }, LinearLayout.LayoutParams(0, -2, 1f))
        nb("−5 ms") { Engine.recOffsetMs += 5f }
        nb("0") { Engine.recOffsetMs = 0f }
        nb("+5 ms") { Engine.recOffsetMs -= 5f }
        box.addView(row)
        check(box, "Präzises Auslösen (feste Verzögerung statt Schwanken – klingt tighter)", Engine.tightTiming) { Engine.tightTiming = it }
        check(box, "Vor der Aufnahme 1 Takt einzählen", Engine.countIn) { Engine.countIn = it }
        val velBtn = Button(this).apply { isAllCaps = false }
        fun velLabel() { velBtn.text = "Anschlag: ${TouchVelocity.MODES[TouchVelocity.mode]} · Kurve ${TouchVelocity.CURVES[TouchVelocity.curve]} · leisester ${(TouchVelocity.floor * 100).toInt()} %" }
        velLabel()
        velBtn.setOnClickListener {
            ui().setTitle("Anschlagsdynamik").setItems(arrayOf(
                "FEST – immer volle Lautstärke",
                "HÖHE – oben im Pad laut, unten leise",
                "STÄRKE – fest tippen = laut (Bewegungssensor + Fingerfläche, lernt mit)",
                "Kurve: weich (leichtes Tippen schon laut)", "Kurve: linear", "Kurve: hart (nur kräftig = laut)",
                "Leisester Schlag: 10 %", "Leisester Schlag: 20 %", "Leisester Schlag: 35 %", "Leisester Schlag: 50 %"
            )) { _, w ->
                when (w) {
                    in 0..2 -> TouchVelocity.mode = w
                    in 3..5 -> TouchVelocity.curve = w - 3
                    else -> TouchVelocity.floor = floatArrayOf(0.1f, 0.2f, 0.35f, 0.5f)[w - 6]
                }
                TouchVelocity.update(this)
                velLabel()
            }.show()
        }
        box.addView(velBtn)
        ui().setTitle("⏱ Einspiel-Timing & Anschlag").setView(scroll)
            .setPositiveButton("Übernehmen") { _, _ ->
                median()?.let { Engine.recOffsetMs = it }
                hint(String.format(Locale.GERMANY, "Einspiel-Ausgleich %+.0f ms – AUTO-Q legt die Schläge jetzt auf den richtigen Platz", -Engine.recOffsetMs))
            }
            .setNegativeButton("Schließen", null)
            .setOnDismissListener {
                Engine.metronome = wasMetro
                if (!wasPlaying) Engine.seqStop()
                prefs.edit().putFloat("recOffsetMs", Engine.recOffsetMs).putBoolean("tightTiming", Engine.tightTiming)
                    .putInt("velMode", TouchVelocity.mode).putInt("velCurve", TouchVelocity.curve).putFloat("velFloor", TouchVelocity.floor)
                    .putBoolean("countIn", Engine.countIn).apply()
            }
            .show()
    }

    /** Pad läuft im Rhythmus, aber versetzt: im Takt auf seine Eins tippen – der Loop wird auf die Eins des Rasters geschoben. */
    private fun showTapAlign(p: Int) {
        if (Engine.beatLen() <= 0.0) { toast("Erst braucht es ein Tempo (ein Loop oder Tempo einstellen)"); return }
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val taps = ArrayList<Long>()
        val sr = Engine.sampleRate
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18f), px(6f), px(18f), px(6f))
        }
        box.addView(TextView(this).apply {
            text = "Lass Pad ${p + 1} laufen und tippe auf TAP genau dann, wenn du in diesem Loop seine Eins hörst – am besten 4–8 Mal. Dann „Übernehmen“: der Loop wird so verschoben, dass seine Eins auf der Eins des Takts liegt. Feinkorrektur mit den Pfeilen (wirkt sofort)."
            textSize = 13f
            setTextColor(Color.argb(210, 255, 255, 255))
        })
        val info = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(255, 176, 40))
            gravity = Gravity.CENTER
            setPadding(0, px(10f), 0, px(6f))
            text = "Noch keine Taps"
        }
        fun median(): Long? = if (taps.isEmpty()) null else taps.sorted()[taps.size / 2]
        fun ms(f: Long) = String.format(Locale.GERMANY, "%+.0f ms", f * 1000.0 / sr)
        fun update() {
            val m = median()
            if (m == null) { info.text = "Noch keine Taps"; return }
            val spread = if (taps.size > 1) (taps.maxOrNull()!! - taps.minOrNull()!!) else 0L
            val beat = Engine.beatLen()
            val beats = m / beat
            val where = if (kotlin.math.abs(beats) >= 0.5) String.format(Locale.GERMANY, " (≈ %+.1f Schläge)", beats) else ""
            info.text = "${taps.size} Taps · Eins liegt ${ms(m)} daneben$where · Streuung ${(spread * 1000 / sr)} ms"
        }
        val tap = Button(this).apply {
            text = "TAP"
            textSize = 26f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(255, 106, 16))
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val g = Engine.barOffsetAt(e.eventTime * 1_000_000L)
                    if (g != null) {
                        taps.add(g)
                        if (taps.size > 16) taps.removeAt(0)
                        update()
                    }
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                true
            }
        }
        box.addView(info)
        box.addView(tap, LinearLayout.LayoutParams(-1, px(110f)))
        val bar = (Engine.beatLen() * 4).toLong()
        val nudges = listOf(
            "◀ 1/16" to (bar / 16), "◀ 10 ms" to (sr / 100).toLong(), "10 ms ▶" to -(sr / 100).toLong(), "1/16 ▶" to -(bar / 16)
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10f), 0, 0)
        }
        for ((lab, fr) in nudges) {
            row.addView(Button(this).apply {
                text = lab
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    if (Engine.shiftPad(p, fr)) hint("Pad ${p + 1}: ${if (fr > 0) "früher" else "später"} um ${kotlin.math.abs(fr) * 1000 / sr} ms")
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.addView(row)
        box.addView(TextView(this).apply {
            text = "◀ = Loop früher · ▶ = später · ↶ UNDO macht alles rückgängig"
            textSize = 11f
            setTextColor(Color.argb(140, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, px(6f), 0, 0)
        })
        val dlg = ui()
            .setTitle("🎯 Pad ${p + 1} auf die Eins")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Übernehmen", null)
            .setNeutralButton("Taps löschen", null)
            .setNegativeButton("Schließen", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val m = median()
                if (m == null) { toast("Erst ein paar Mal auf TAP tippen"); return@setOnClickListener }
                if (Engine.shiftPad(p, m)) {
                    hint("Pad ${p + 1} um ${ms(-m)} verschoben – seine Eins liegt jetzt auf der Eins")
                    taps.clear()
                    update()
                }
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { taps.clear(); update() }
        }
        dlg.show()
    }

    // ---------------------------------------------------------------- Menü-Aussehen

    /** Dialog-Builder im gewählten Menü-Aussehen (Schrift, Hell/Dunkel, Akzent, Größe, Ecken). */
    private inner class StyledBuilder : AlertDialog.Builder(this@MainActivity, MenuStyle.themeRes(this@MainActivity)) {
        override fun create(): AlertDialog {
            val d = super.create()
            MenuStyle.attach(d, this@MainActivity)
            return d
        }
    }

    private fun ui(): AlertDialog.Builder = StyledBuilder()

    /** Liste, deren Einträge in einer bestimmten Schrift erscheinen (Vorschau der Schriften). */
    private fun fontAdapter(): android.widget.ArrayAdapter<String> {
        val names = MenuStyle.FONTS.map { it.name }
        return object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, names) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.tag = MenuStyle.KEEP
                v.typeface = MenuStyle.typeface(this@MainActivity, position)
                v.setTextColor(MenuStyle.ink(this@MainActivity))
                v.textSize = 17f * MenuStyle.FONTS[position].scale
                return v
            }
        }
    }

    private fun showMenuLook() {
        val items = arrayOf(
            "🔤 Schrift: " + MenuStyle.FONTS[MenuStyle.font].name,
            "🌓 Modus: " + MenuStyle.MODES[MenuStyle.mode],
            "🎨 Akzentfarbe: " + MenuStyle.ACCENT_NAMES[MenuStyle.accent],
            "🔠 Schriftgröße: " + MenuStyle.SIZES[MenuStyle.size],
            "▢ Ecken: " + MenuStyle.CORNERS[MenuStyle.corner],
            "↺ Zurücksetzen"
        )
        fun done() { MenuStyle.save(this); showMenuLook() }
        ui().setTitle("✨ Menü-Aussehen")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> {
                        val dlg = ui().setTitle("🔤 Schrift für Menüs")
                            .setSingleChoiceItems(fontAdapter(), MenuStyle.font) { d, i ->
                                MenuStyle.font = i
                                d.dismiss()
                                done()
                            }
                            .setNegativeButton("Zurück") { _, _ -> showMenuLook() }
                            .create()
                        dlg.show()
                    }
                    1 -> ui().setTitle("🌓 Modus").setSingleChoiceItems(MenuStyle.MODES, MenuStyle.mode) { d, i -> MenuStyle.mode = i; d.dismiss(); done() }
                        .setNegativeButton("Zurück") { _, _ -> showMenuLook() }.show()
                    2 -> ui().setTitle("🎨 Akzentfarbe").setSingleChoiceItems(MenuStyle.ACCENT_NAMES.mapIndexed { i, n -> "●  $n" }.toTypedArray(), MenuStyle.accent) { d, i -> MenuStyle.accent = i; d.dismiss(); done() }
                        .setNegativeButton("Zurück") { _, _ -> showMenuLook() }.show()
                    3 -> ui().setTitle("🔠 Schriftgröße").setSingleChoiceItems(MenuStyle.SIZES, MenuStyle.size) { d, i -> MenuStyle.size = i; d.dismiss(); done() }
                        .setNegativeButton("Zurück") { _, _ -> showMenuLook() }.show()
                    4 -> ui().setTitle("▢ Ecken").setSingleChoiceItems(MenuStyle.CORNERS, MenuStyle.corner) { d, i -> MenuStyle.corner = i; d.dismiss(); done() }
                        .setNegativeButton("Zurück") { _, _ -> showMenuLook() }.show()
                    else -> {
                        MenuStyle.font = 1; MenuStyle.mode = 0; MenuStyle.accent = 0; MenuStyle.size = 1; MenuStyle.corner = 0
                        done()
                    }
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    // ---------------------------------------------------------------- Logo: Info / Schließen

    private var zoomLandscape = false

    private fun showLogoMenu() {
        ui().setTitle("Marv PAD")
            .setItems(arrayOf("ℹ Info & alle Änderungen", "⏻ Programm schließen")) { _, w ->
                if (w == 0) showAbout() else requestClose()
            }
            .setNegativeButton("Zurück", null)
            .show()
    }

    /** Schließen: bei ungespeicherten Änderungen erst fragen, sonst sofort. */
    private fun requestClose() {
        if (!Engine.isDirty()) { closeApp(); return }
        ui().setTitle("Projekt speichern?")
            .setMessage("Seit dem letzten Speichern wurde etwas geändert. Vor dem Schließen speichern?")
            .setPositiveButton("💾 Speichern") { _, _ -> askProjectName(null, 0) { closeApp() } }
            .setNeutralButton("Nicht speichern") { _, _ -> closeApp() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun closeApp() {
        try { if (SetRecorder.recording) SetRecorder.stop() } catch (ignored: Throwable) {}
        try { Dj.decks.forEach { it.playing = false } } catch (ignored: Throwable) {}
        try { Engine.stop() } catch (ignored: Throwable) {}
        finishAndRemoveTask()
    }

    private fun showPadMenu(p: Int) {
        menuPad = p
        val has = Engine.hasLoop(p)
        val shot = Engine.isOneShot(p)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("Eingang: " + srcLabel(p) + "  →  ändern …")
        actions.add { showSourceMenu(p) }
        labels.add(if (shot) "Modus: ⚡ ONE SHOT  →  auf ∞ LOOP umschalten" else "Modus: ∞ LOOP  →  auf ⚡ ONE SHOT umschalten")
        actions.add { Engine.setPadMode(p, !shot) }
        if (has && !shot && Engine.canShiftPad(p)) {
            labels.add("🎯 Auf die Eins tappen / verschieben …")
            actions.add { showTapAlign(p) }
        }
        if (has && !shot) {
            if (Engine.isFree(p)) {
                labels.add("🎯 Jetzt beatmatchen (Tempo erkennen, ins Raster)")
                actions.add { beatmatchPad(p, true) }
            } else {
                labels.add("↺ Original-Tempo (Beatmatch aufheben)")
                actions.add { beatmatchPad(p, false) }
            }
        }
        labels.add("Aus Bibliothek laden")
        actions.add { showLibrary(p) }
        labels.add("Audio importieren (WAV / MP3)")
        actions.add { pickImport() }
        if (has) {
            if (!shot) {
                labels.add(if (AiStems.ready(this)) "🧠 KI-Stems: Vocals · Drums · Bass · Melodie auf 4 Pads" else "🎚 Stems trennen (Drums · Bass · Melodie – oder 🧠 KI auf 4 Pads)")
                actions.add { splitStems(p) }
                labels.add("🪄 Ein-Tipp-Remix: aus dem Song ein neues Arrangement bauen")
                actions.add { oneTapRemix(p) }
                labels.add("🔍 Besten Loop finden: die Stelle, die sich am saubersten wiederholt")
                actions.add { findBestLoop(p) }
                labels.add("✂ Slicer: an den Schlägen in 16 Stücke auf 16 Pads zerlegen")
                actions.add { openSlicerFor(p) }
            }
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
        ui()
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
                ui()
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
        labels.add("📥📤 Importieren / Exportieren …")
        actions.add { showLibraryIo() }
        labels.add("🌐 Weitere freie Samples im Netz …")
        actions.add { showFreeSampleLinks() }
        ui()
            .setTitle("Bibliothek → Pad ${p + 1}")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showLibraryCategory(p: Int, cat: String) {
        val own = Projects.userSamples(this).filter { it.category == cat }
        val builtIn = SampleLibrary.inCategory(cat)
        val labels = own.map { "★ " + it.title + (if (it.oneShot) "" else " ∞") } + builtIn.map { it.title }
        ui()
            .setTitle(cat)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < own.size) userSampleActions(p, own[i]) else loadLibrarySample(builtIn[i - own.size], p)
            }
            .setNegativeButton("Zurück") { _, _ -> showLibrary(p) }
            .show()
    }

    private fun userSampleActions(p: Int, smp: Projects.UserSample) {
        ui()
            .setTitle("★ ${smp.title}")
            .setItems(arrayOf("▶ Auf Pad ${p + 1} laden", "💾 Als WAV exportieren", "🗑 Aus der Bibliothek löschen")) { _, w ->
                if (w == 0) loadUserSample(smp, p)
                else if (w == 1) exportLibWav(smp.title) { out -> smp.file.inputStream().use { it.copyTo(out) } }
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
                val data = AudioFiles.decodeFile(smp.file, rate, Engine.MAX_IMPORT_SECONDS)
                Engine.padOneShot[p] = smp.oneShot
                if (smp.oneShot) {
                    Engine.importLoop(p, data)
                    runOnUiThread { toast("Pad ${p + 1}: ★ ${smp.title}") }
                } else {
                    val desc = clipToPad(p, data, rate, smp.title)
                    runOnUiThread { hint("Pad ${p + 1}: ★ ${smp.title} · $desc · antippen startet ihn auf der Eins") }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- Bibliothek: Import / Export

    /** Wartender Export: schreibt in den gewählten Ziel-Stream und liefert die Meldung. */
    private var pendingLibExport: ((java.io.OutputStream) -> String)? = null

    private fun showLibraryIo() {
        val user = Projects.userSamples(this)
        val djN = DjLibrary.list(this).size
        val labels = arrayListOf(
            "📥 Importieren: Audio-Dateien (WAV, MP3 …) oder Bibliotheks-Paket (.zip)",
            "📤 Ganze eigene Bibliothek als Paket (★ ${user.size} Samples + 💿 $djN DJ-Tracks)",
            "📤 Eine Kategorie als Paket",
            "📤 Einzelnes Sample als WAV (auch mitgelieferte)",
            "🌐 Weitere freie Samples im Netz …"
        )
        ui()
            .setTitle("📚 Bibliothek – Import / Export")
            .setItems(labels.toTypedArray()) { _, w ->
                when (w) {
                    0 -> pickLibraryImport()
                    1 -> {
                        if (user.isEmpty() && djN == 0) { toast("Deine Bibliothek ist noch leer"); return@setItems }
                        startLibExport("MarvPAD_Bibliothek_" + stamp() + ".zip", "application/zip") { out ->
                            val r = LibraryPack.export(this, out, user, true)
                            "Exportiert: ${r.samples} Samples, ${r.dj} DJ-Tracks"
                        }
                    }
                    2 -> {
                        val cats = user.map { it.category }.distinct()
                        if (cats.isEmpty()) { toast("Noch keine eigenen Samples (★) – speichere welche aus dem Wave-Editor oder importiere sie"); return@setItems }
                        ui()
                            .setTitle("Kategorie exportieren")
                            .setItems(cats.map { c -> "$c (${user.count { it.category == c }})" }.toTypedArray()) { _, i ->
                                val c = cats[i]
                                startLibExport("MarvPAD_" + c.replace(Regex("[^A-Za-z0-9äöüÄÖÜß]+"), "_").trim('_') + "_" + stamp() + ".zip", "application/zip") { out ->
                                    val r = LibraryPack.export(this, out, user.filter { it.category == c }, false)
                                    "Exportiert: ${r.samples} Samples aus $c"
                                }
                            }
                            .setNegativeButton("Zurück") { _, _ -> showLibraryIo() }
                            .show()
                    }
                    3 -> pickSampleToExport()
                    4 -> showFreeSampleLinks()
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    /** Name, Inhalt/Lizenz, Adresse – freie Quellen für weitere Samples. */
    private val freeSampleLinks = listOf(
        Triple("Freesound", "Riesige Community-Sammlung aller Art · Lizenz je Sound (CC0, CC BY …) · kostenloses Konto", "https://freesound.org"),
        Triple("VCSL – Versilian Community Sample Library", "Akustische Instrumente & Percussion · CC0 (gemeinfrei) · Quelle vieler Marv-PAD-Sounds", "https://github.com/sgossner/VCSL"),
        Triple("VS Chamber Orchestra (VSCO 2 CE)", "Orchester: Streicher, Bläser, Pauken · CC0 (gemeinfrei)", "https://github.com/sgossner/VSCO-2-CE"),
        Triple("Philharmonia Orchestra", "Einzeltöne aller Orchesterinstrumente · kostenlos für eigene Musik", "https://philharmonia.co.uk/audio-sound-samples/"),
        Triple("University of Iowa – Musical Instrument Samples", "Instrumente Ton für Ton, sehr sauber aufgenommen · frei nutzbar", "https://theremin.music.uiowa.edu/MIS.html"),
        Triple("MusicRadar SampleRadar", "Tausende Loops & Hits nach Genres · lizenzfrei für eigene Tracks", "https://www.musicradar.com/news/tech/free-music-samples-download-loops-hits-and-multis-627820"),
        Triple("99Sounds", "Kostenlose Sample-Packs (Drums, FX, Texturen) · lizenzfrei", "https://99sounds.org"),
        Triple("Bedroom Producers Blog – Free Samples", "Übersicht vieler kostenloser Packs · Lizenz je Pack beachten", "https://bedroomproducersblog.com/free-samples/"),
        Triple("Looperman", "Loops und Acapellas der Community · kostenloses Konto, Bedingungen je Loop", "https://www.looperman.com"),
        Triple("Sonniss – GDC Game Audio Bundle", "Große Sound-Effekt-Pakete · lizenzfrei, auch kommerziell", "https://sonniss.com/gameaudiogdc"),
        Triple("BBC Sound Effects", "Über 30.000 Geräusche aus dem BBC-Archiv · nur privat/Bildung", "https://sound-effects.bbcrewind.co.uk")
    )

    private fun showFreeSampleLinks() {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(4f), px(20f), px(12f))
        }
        box.addView(TextView(this).apply {
            text = "Datei herunterladen, entpacken und dann in der Bibliothek über 📥 Importieren hinzufügen (auch mehrere WAV/MP3 auf einmal). Bitte die Lizenz der jeweiligen Quelle beachten."
            textSize = 12.5f
            setTextColor(Color.argb(190, 255, 255, 255))
            setPadding(0, 0, 0, px(10f))
        })
        for ((name, info, url) in freeSampleLinks) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(9f), 0, px(9f))
                isClickable = true
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        toast("Kein Browser gefunden")
                    }
                }
                addView(TextView(this@MainActivity).apply {
                    text = "🌐 $name"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(0, 229, 255))
                })
                addView(TextView(this@MainActivity).apply {
                    text = info
                    textSize = 12f
                    setTextColor(Color.argb(200, 255, 255, 255))
                })
                addView(TextView(this@MainActivity).apply {
                    text = url.removePrefix("https://")
                    textSize = 11f
                    setTextColor(Color.argb(130, 255, 255, 255))
                })
            })
        }
        ui()
            .setTitle("🌐 Freie Samples im Netz")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("📥 Importieren …") { _, _ -> pickLibraryImport() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date())

    private fun startLibExport(name: String, mime: String, w: (java.io.OutputStream) -> String) {
        pendingLibExport = w
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(i, REQ_LIB_OUT)
        } catch (e: Exception) {
            toast("Kein Dateimanager gefunden")
        }
    }

    private fun exportLibWav(title: String, copy: (java.io.OutputStream) -> Unit) {
        val n = title.replace(Regex("[^A-Za-z0-9äöüÄÖÜß ._-]+"), "_").trim().ifEmpty { "Sample" }
        startLibExport("$n.wav", "audio/wav") { out ->
            copy(out)
            "„$title“ exportiert"
        }
    }

    private fun pickSampleToExport() {
        val user = Projects.userSamples(this)
        val cats = LinkedHashSet<String>(SampleLibrary.categories)
        cats.addAll(user.map { it.category })
        val list = cats.toList()
        ui()
            .setTitle("Sample exportieren · Kategorie")
            .setItems(list.toTypedArray()) { _, ci ->
                val c = list[ci]
                val own = user.filter { it.category == c }
                val builtIn = SampleLibrary.inCategory(c)
                val labels = own.map { "★ " + it.title } + builtIn.map { it.title }
                ui()
                    .setTitle(c)
                    .setItems(labels.toTypedArray()) { _, i ->
                        if (i < own.size) {
                            val s = own[i]
                            exportLibWav(s.title) { out -> s.file.inputStream().use { it.copyTo(out) } }
                        } else {
                            val s = builtIn[i - own.size]
                            exportLibWav(s.title) { out -> assets.open("samples/" + s.file).use { it.copyTo(out) } }
                        }
                    }
                    .setNegativeButton("Zurück") { _, _ -> pickSampleToExport() }
                    .show()
            }
            .setNegativeButton("Zurück") { _, _ -> showLibraryIo() }
            .show()
    }

    private fun pickLibraryImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/zip", "application/x-zip-compressed", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_LIB_IN)
        } catch (e: Exception) {
            toast("Kein Dateimanager gefunden")
        }
    }

    /** Pakete werden direkt übernommen, Audio-Dateien landen in einer gewählten Kategorie. */
    private fun importLibraryUris(uris: List<Uri>) {
        Thread {
            val zips = ArrayList<Uri>()
            val audio = ArrayList<Uri>()
            for (u in uris) {
                val head = try { contentResolver.openInputStream(u)?.use { s -> ByteArray(4).also { s.read(it) } } } catch (e: Exception) { null }
                if (head != null && LibraryPack.isZip(head)) zips.add(u) else audio.add(u)
            }
            var ns = 0
            var nd = 0
            var err: String? = null
            for (z in zips) {
                try {
                    contentResolver.openInputStream(z)?.use { inp ->
                        val r = LibraryPack.import(this, inp, "⭐ Importiert")
                        ns += r.samples
                        nd += r.dj
                    }
                } catch (e: Exception) {
                    err = e.message ?: e.toString()
                }
            }
            runOnUiThread {
                if (zips.isNotEmpty()) toast(if (err != null && ns + nd == 0) "Paket-Import fehlgeschlagen: $err" else "Paket importiert: $ns Samples" + if (nd > 0) ", $nd DJ-Tracks" else "")
                if (audio.isNotEmpty()) askCategory("${audio.size} Datei(en) importieren") { cat -> importAudioToLibrary(audio, cat) }
            }
        }.start()
    }

    private fun importAudioToLibrary(uris: List<Uri>, cat: String) {
        val rate = Engine.sampleRate
        toast("Importiere ${uris.size} Datei(en) …")
        Thread {
            var ok = 0
            var fail = 0
            for (u in uris) {
                try {
                    val data = AudioFiles.decode(this, u, rate, Engine.MAX_IMPORT_SECONDS)
                    if (data.size < 64) { fail++; continue }
                    val title = (displayName(u) ?: "Sample").substringBeforeLast('.').ifEmpty { "Sample" }
                    // kurz = ⚡ One-Shot, lang = ∞ Loop (folgt beim Laden dem Tempo)
                    val oneShot = data.size < rate * 3
                    Projects.saveUserSample(this, cat, title, data, rate, oneShot)
                    ok++
                } catch (e: Exception) {
                    fail++
                }
            }
            runOnUiThread { toast("★ $ok Sample(s) in $cat importiert" + if (fail > 0) " · $fail nicht lesbar" else "") }
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
        ui()
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
        ui()
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i -> onPick(items[i]) }
            .setNeutralButton("Löschen …") { _, _ ->
                ui()
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
                dd.loadProg = 0.3f
                val data = AudioFiles.decodeFile(it.file, Engine.sampleRate, 600)
                dd.loadProg = 0.8f
                val r = Engine.sampleRate.toDouble()
                Dj.load(dd, Dj.fromMono(data, Engine.sampleRate), it.title, it.bpm)
                dd.grid = it.grid * r
                dd.cuePoint = it.cue * r
                for (h in dd.hot.indices) dd.hot[h] = if (h < it.hot.size && it.hot[h] >= 0) it.hot[h] * r else -1.0
                dd.seekTo = dd.cuePoint
                dd.libId = it.id
                if (it.key >= 0) { dd.keyGen++; dd.key = it.key; dd.keyConf = 1f }
                runOnUiThread { hint(String.format(Locale.GERMANY, "Deck %s: %s · %.1f BPM · Cue-Punkte geladen", "AB"[k], it.title, it.bpm)) }
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                dd.loading = false; dd.loadProg = -1f
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
        ui()
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
        val pre = if (startFrame > 0) src.copyOfRange(max(0, startFrame - rate / 20), startFrame) else null
        val desc = Engine.importClip(p, clip, Warp.Info(bpm, bars, "DJ"), pre)
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

    // ================================================================ Set-Aufnahme (Master)

    private var pendingSet: java.io.File? = null

    private fun toggleSetRec() {
        if (!SetRecorder.recording) {
            if (!Engine.running) { toast("Audio läuft nicht"); return }
            if (SetRecorder.start(this, Engine.sampleRate)) hint("🔴 Set-Aufnahme läuft – Master in Stereo, nochmal tippen beendet")
            else toast("Aufnahme konnte nicht gestartet werden")
            return
        }
        val f = SetRecorder.stop() ?: return
        val dur = SetRecorder.fmt(SetRecorder.seconds())
        val lost = SetRecorder.dropped
        ui()
            .setTitle("Set aufgenommen · $dur")
            .setMessage(String.format(Locale.GERMANY, "%s\n%.1f MB · WAV Stereo 16 Bit", f.name, f.length() / 1048576.0) +
                (if (lost > 0) "\n⚠ ${lost} Frames übersprungen (Speicher zu langsam)" else ""))
            .setPositiveButton("📤 Exportieren") { _, _ -> exportSet(f) }
            .setNeutralButton("Umbenennen") { _, _ -> renameSet(f) }
            .setNegativeButton("Fertig", null)
            .show()
    }

    private fun exportSet(f: java.io.File) {
        pendingSet = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, f.name)
        }
        try { startActivityForResult(i, REQ_SET) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
    }

    private fun renameSet(f: java.io.File) {
        askName(f.nameWithoutExtension, "Name des Sets") { n ->
            val clean = n.replace(Regex("[^\\w\\- äöüÄÖÜß.]"), "_").trim().ifEmpty { f.nameWithoutExtension }
            val dst = java.io.File(f.parentFile, "$clean.wav")
            if (f.renameTo(dst)) toast("Umbenannt: ${dst.name}") else toast("Umbenennen nicht möglich")
        }
    }

    private fun showSets() {
        val sets = SetRecorder.list(this)
        if (sets.isEmpty()) { toast("Noch keine Sets – oben unter MASTER auf REC tippen"); return }
        val labels = sets.map { f ->
            val sec = (f.length() - 44) / (Engine.sampleRate * 4.0)
            String.format(Locale.GERMANY, "%s · %s · %.0f MB", f.nameWithoutExtension, SetRecorder.fmt(sec), f.length() / 1048576.0)
        }
        ui()
            .setTitle("🔴 Meine Set-Aufnahmen")
            .setItems(labels.toTypedArray()) { _, i ->
                val f = sets[i]
                ui()
                    .setTitle(f.nameWithoutExtension)
                    .setItems(arrayOf("📤 Exportieren (WAV)", "✎ Umbenennen", "🎧 In Deck A laden", "🗑 Löschen", "📲 Teilen (WhatsApp, Instagram …)")) { _, a ->
                        when (a) {
                            4 -> shareWav(f, f.nameWithoutExtension, false)
                            0 -> exportSet(f)
                            1 -> renameSet(f)
                            2 -> {
                                val dd = Dj.decks[0]
                                dd.loading = true
                                Thread {
                                    try {
                                        val t = deckDecode(dd) { Dj.decode(this, Uri.fromFile(f)) }
                                        Dj.load(dd, t, f.nameWithoutExtension, null)
                                        runOnUiThread { hint("Deck A: ${f.nameWithoutExtension}"); switchMode(true) }
                                    } catch (e: Throwable) {
                                        runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
                                    } finally { dd.loading = false; dd.loadProg = -1f }
                                }.start()
                            }
                            else -> confirm("Set löschen?", f.name) { f.delete() }
                        }
                    }
                    .show()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    // ================================================================ ACID/DRUM: Presets

    private fun showSynthPresets() {
        val genres = InstPresets.SYNTHS.map { it.genre }.distinct()
        val items = listOf("🎲 Zufallsmelodie", "🎼 Akkordfolge erzeugen (Pop, House, Trance …)") + genres.map { g -> "$g (${InstPresets.SYNTHS.count { it.genre == g }})" } + listOf("📥 Eigene MIDI-Datei laden …")
        ui()
            .setTitle("🎹 SYNTH · Presets")
            .setItems(items.toTypedArray()) { _, w ->
                when {
                    w == 0 -> {
                        Engine.pushPatternUndo("SYNTH Zufall")
                        InstState.synRandom()
                        hint("🎲 Neue Zufallsmelodie – nochmal für eine andere")
                    }
                    w == items.size - 1 -> showInstMidi(3)
                    w == 1 -> showChordProgression()
                    else -> {
                        val g = genres[w - 2]
                        val list = InstPresets.SYNTHS.filter { it.genre == g }
                        ui().setTitle(g)
                            .setItems(list.map { "${it.name} · ${it.bpm} BPM" }.toTypedArray()) { _, i ->
                                val p = list[i]
                                Engine.pushPatternUndo("SYNTH Preset")
                                InstPresets.applySynth(p)
                                presetTempo(p.bpm)
                                hint("SYNTH: ${p.name} – Klang ${InstState.synWaveNames[p.wave]}" + if (p.chord != 0) " · Akkord ${InstState.synChordNames[p.chord]}" else "")
                            }.show()
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showInstPresets(k: Int) {
        if (k == 3) { showSynthPresets(); return }
        val isAcid = k == 1
        val genres = if (isAcid) InstPresets.ACIDS.map { it.genre }.distinct() else InstPresets.DRUMS.map { it.genre }.distinct()
        val items = listOf("🎲 Zufall (Acid-Banger-Generator)") + genres.map { g ->
            val n = if (isAcid) InstPresets.ACIDS.count { it.genre == g } else InstPresets.DRUMS.count { it.genre == g }
            "$g ($n)"
        } + listOf("📥 Eigene MIDI-Datei laden …")
        ui()
            .setTitle(if (isAcid) "ACID 303 · Presets" else "DRUM 909 · Beats")
            .setItems(items.toTypedArray()) { _, w ->
                when {
                    w == 0 -> {
                        Engine.pushPatternUndo("Zufall")
                        if (isAcid) InstPresets.randomAcid() else InstPresets.randomDrums()
                        hint("🎲 Neues Zufalls-Pattern – nochmal für ein anderes")
                    }
                    w == items.size - 1 -> showInstMidi(k)
                    else -> {
                        val g = genres[w - 1]
                        if (isAcid) {
                            val list = InstPresets.ACIDS.filter { it.genre == g }
                            ui().setTitle(g)
                                .setItems(list.map { "${it.name} · ${it.bpm} BPM" }.toTypedArray()) { _, i ->
                                    val p = list[i]
                                    Engine.pushPatternUndo("ACID Preset")
                                    InstPresets.applyAcid(p)
                                    presetTempo(p.bpm)
                                    hint("ACID: ${p.name} – Klang-Einstellungen mitgeladen")
                                }.show()
                        } else {
                            val list = InstPresets.DRUMS.filter { it.genre == g }
                            ui().setTitle(g)
                                .setItems(list.map { if (it.bpm > 0) "${it.name} · ${it.bpm} BPM" else it.name }.toTypedArray()) { _, i ->
                                    val p = list[i]
                                    Engine.pushPatternUndo("DRUM Preset")
                                    InstPresets.applyDrum(p)
                                    if (p.bpm > 0) {
                                        presetTempo(p.bpm)
                                        if (p.swing > 0f) Engine.swing = p.swing
                                    }
                                    hint("DRUM: ${p.name}" + if (p.swing > 0f) " · Swing ${50 + (p.swing * 25).toInt()} %" else "")
                                }.show()
                        }
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Tempo des Presets übernehmen – nur wenn noch keine Loops laufen (sonst bleibt das Master-Tempo). */
    private fun presetTempo(bpm: Int) {
        if (bpm <= 0) return
        if (Engine.currentBpm() <= 0f || (0 until Engine.PADS).none { Engine.hasLoop(it) }) Engine.setBpm(bpm.toFloat())
    }

    // ================================================================ ACID/DRUM: MIDI-Dateien

    private var instIoKind = 1
    private var instIoAdd = false
    /** General-MIDI-Drumnoten für BD, SD, LT, MT, HT, RS, CP, CH, OH, CR, RD. */
    private val GM = intArrayOf(36, 38, 41, 45, 48, 37, 39, 42, 46, 49, 51)

    private fun showInstMidi(k: Int) {
        instIoKind = k
        val name = when (k) { 1 -> "ACID 303"; 3 -> "SYNTH"; else -> "DRUM 909" }
        ui()
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
                        putExtra(Intent.EXTRA_TITLE, "MarvPAD_${when (k) { 1 -> "ACID"; 3 -> "SYNTH"; else -> "DRUM" }}_$stamp.mid")
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
        if (instIoKind == 3) {
            // Synth: Akkorde werden als einzelne Noten geschrieben
            val iv = st.synChordIv[st.synChord.coerceIn(0, st.synChordIv.size - 1)]
            val len = st.synLen
            var k = 0
            while (k < len) {
                if (!st.synGate[k] || (k > 0 && st.synTie[k] && st.synGate[k - 1])) { k++; continue }
                var n = 1
                while (k + n < len && st.synGate[k + n] && st.synTie[k + n]) n++
                val vel = (110 * st.synVel[k]).toInt().coerceIn(1, 127)
                for (o in iv) notes.add(MidiFile.Note(k / 4.0, n / 4.0 - 0.02, (60 + 12 * st.synOct + st.synSemi[k] + o).coerceIn(0, 127), vel))
                k += n
            }
        } else if (instIoKind == 1) {
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
            val bytes = MidiFile.exportRaw(notes, Engine.currentBpm(), when (instIoKind) { 1 -> "Marv PAD · ACID 303"; 3 -> "Marv PAD · SYNTH"; else -> "Marv PAD · DRUM 909" }, if (instIoKind == 2) 9 else 0)
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
            if (instIoKind == 3) {
                // Synth: je Schritt die höchste Note (Melodie); Akkorde stellt der AKKORD-Schalter
                if (!instIoAdd) st.synClear()
                st.synLen = max(if (instIoAdd) st.synLen else 16, need)
                val byStep = parsed.notes.groupBy { Math.round(it.beat * 4).toInt() }
                for ((k, list) in byStep.toSortedMap()) {
                    if (k !in 0 until st.synLen) continue
                    val nt = list.maxByOrNull { it.pitch } ?: continue
                    var semi = nt.pitch - 60 - 12 * st.synOct
                    while (semi > 24) semi -= 12
                    while (semi < -12) semi += 12
                    val steps = max(1, Math.round(nt.len * 4).toInt())
                    for (j in 0 until steps) {
                        val s2 = k + j
                        if (s2 >= st.synLen) break
                        if (j > 0 && byStep.containsKey(s2)) break
                        st.synGate[s2] = true
                        st.synTie[s2] = j > 0
                        st.synSemi[s2] = semi
                        st.synVel[s2] = (nt.vel / 127f).coerceIn(0.05f, 1f)
                    }
                    count++
                }
            } else if (instIoKind == 1) {
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
            toast("$count Noten geladen · ${(when (instIoKind) { 1 -> st.acidLen; 3 -> st.synLen; else -> st.drumLen }) / 16} Takt(e)")
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
        ui()
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
        ui()
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
        ui()
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
                    Engine.setPadName(p, smp.title)
                    runOnUiThread { toast("Pad ${p + 1}: ${smp.title}") }
                } else {
                    val desc = clipToPad(p, data, rate, smp.title + " " + smp.file)
                    Engine.setPadName(p, smp.title)
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
        ui()
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
        ui()
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
        ui()
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

    /** Hauptmenü in Gruppen: Projekt · Aufnahme & Wiedergabe · Aussehen · Audio & MIDI · Hilfe. */
    /** Reiter des Hauptmenüs (zuletzt geöffneter wird gemerkt). */
    private val MENU_TABS = arrayOf("🏠 Projekt", "🎛 Studio", "🎤 Mikro", "🎧 DJ", "⚙ Einstellungen", "❓ Hilfe")

    /** Eintrag im Menü: Text, Aktion, [stay] = Schalter (Menü bleibt offen und zeigt den neuen Stand). Text "§…" = Zwischenüberschrift. */
    private class MItem(val label: String, val stay: Boolean = false, val run: () -> Unit = {})

    private fun menuTab(t: Int): List<MItem> {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val out = ArrayList<MItem>()
        fun sec(t: String) = out.add(MItem("§$t"))
        fun add(label: String, stay: Boolean = false, run: () -> Unit) = out.add(MItem(label, stay, run))
        fun group(g: Int) { val (_, items) = menuGroupItems(g); for (it in items) out.add(MItem(it.first, it.third, it.second)) }
        when (t) {
            0 -> {
                sec("Projekt")
                group(0)
                sec("Weitergeben")
                add("📤 Song exportieren: WAV, Stems, MIDI") { showSongExport() }
                add("📲 Teilen: Song oder Set an WhatsApp, Instagram & Co.") { showShare() }
            }
            1 -> {
                sec("Werkzeuge")
                add("🎬 Szenen-Launcher: Spuren-Kombinationen im Takt starten") {
                    if (djMode) switchMode(false)
                    pads.openLauncher()
                    hint("Zeile = Szene: ▶ startet sie auf der nächsten Eins · Felder antippen = Spur an/aus bzw. Pattern A–D · ▶ lange drücken = leeren")
                }
                add("🔁 Loop Station: 4 Spuren, ein großer Knopf") {
                    if (djMode) switchMode(false)
                    pads.openStation()
                    hint("Großen Knopf tippen: Aufnahme · nochmal: Loop läuft · nochmal: Overdub · ■/▶ stoppt/startet die Spur")
                }
                add("✂ Slicer: Song-Abschnitt auf 16 Pads zerlegen") { if (djMode) switchMode(false); if (Slicer.data == null) pickSlicerSource() else pads.openSlicer() }
                add("👻 Ghost-Producer: Genre-Patterns als Live-Remix") { openGhost() }
                add("🪄 Ein-Tipp-Remix: aus einem Song ein neues Arrangement") { if (djMode) switchMode(false); pickRemixPad() }
                add(if (Engine.scOn) "〽 Sidechain-Pumpen: AN" else "〽 Sidechain-Pumpen (Kick drückt Bass & Flächen)") { showSidechain() }
                add((if (InstState.liveAmt > 0f) "🌱 Lebendige Patterns: " + LIVE_NAMES[liveIdx()] else "🌱 Lebendige Patterns (Variationen & Fills)") + " …") { showLivePatterns() }
                add("🔍 Besten Loop finden (Song auf einem Pad)") {
                    val list = (0 until PadView.VISIBLE_PADS).filter { Engine.hasLoop(it) && !Engine.isOneShot(it) }
                    when {
                        list.isEmpty() -> toast("Erst einen Song auf ein Pad laden")
                        list.size == 1 -> findBestLoop(list[0])
                        else -> ui().setTitle("🔍 In welchem Pad suchen?").setItems(list.map { "Pad ${it + 1}" + (Engine.padName(it)?.let { n -> " · $n" } ?: "") }.toTypedArray()) { _, w -> findBestLoop(list[w]) }.show()
                    }
                }
                sec("Aufnahme & Wiedergabe")
                group(1)
            }
            2 -> {
                sec("Mit der Stimme produzieren")
                add("🎤 Summen → Melodie") { askMicCapture(0) }
                add("🥁 Beatbox → Drums") { askMicCapture(1) }
                add("✋ Klang-Sammler: Geräusche → eigenes Drumkit") { showCollector() }
                add("🎯 Folge dem Song: Tonart & Akkorde übernehmen") { showFollowSong() }
                sec("Live-Mikro")
                add((if (Engine.monitorOn) "☑" else "☐") + " 🎙 Live-Mikrofon mit Effekten …") { showMonitor() }
                add((if (Engine.autoTuneOn) "☑" else "☐") + " 🎚 Auto-Tune …") { showAutoTune() }
                add((if (Engine.harmOn) "☑" else "☐") + " 👥 Harmonizer …") { showHarmonizer() }
                add((if (Engine.voiceSynthOn) "☑" else "☐") + " 🎹 Stimmen-Synth …") { showVoiceSynth() }
                add("🎯 Mikro-Timing einmessen (jetzt ${Engine.micOffsetMs.toInt()} ms)") { calibrateMic() }
            }
            3 -> {
                add(if (djMode) "▦ Zurück zum Pad-Modus" else "🎧 DJ-Modus starten (zwei Decks, Crossfader)") { switchMode(!djMode) }
                if (djMode) {
                    val djLand = prefs.getBoolean("djLand", true)
                    add(if (Dj.gigMode) "🎤 GIG-MODUS AN – beenden" else "🎤 GIG-MODUS starten (Schutz, Bildschirm an, Nicht stören)") { if (Dj.gigMode) stopGig() else showGigCheck() }
                    add(if (djLand) "📱 Querformat (wie ein Controller) – antippen = hochkant" else "📱 Hochkant – antippen = Querformat", true) {
                        prefs.edit().putBoolean("djLand", !djLand).apply()
                        applyDjOrientation()
                    }
                    add(if (Dj.mashArmed) "🎭 Mashup wartet … (antippen = abbrechen)" else "🎭 Mashup-Assistent") { showMashup() }
                }
                sec("Musik")
                add("💿 Musik-Sammlung: Ordner laden, katalogisieren, Smart Crates") { showMusicLibrary() }
                add("☁ Songs aus Dropbox / Drive / Link holen") { showCloudImport(CLOUD_COLLECTION) }
                add("🧠 KI-Stems (Vocals · Drums · Bass · Melodie) …") { showAiStems(0) }
            }
            4 -> {
                sec("Aussehen")
                group(2)
                sec("Audio, Latenz & MIDI")
                group(3)
                sec("Verbindungen")
                add(if (Link.hosting) "📡 Handys gekoppelt (${Link.clients.size}) – Vorlagen & Verteilung" else "📡 Handys koppeln (WLAN / Bluetooth)") { showCoupling() }
            }
            else -> {
                sec("Lernen")
                add("🎓 Lektionen: Schritt für Schritt zum eigenen Song") { showTutorials() }
                group(4)
            }
        }
        return out
    }

    /** Hauptmenü mit Reitern: oben die Bereiche, darunter ihre Funktionen. */
    private fun showMainMenu() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        var tab = prefs.getInt("menuTab", if (djMode) 3 else 0).coerceIn(0, MENU_TABS.size - 1)
        val dens = resources.displayMetrics.density
        fun px(v: Float) = (v * dens).toInt()
        val acc = MenuStyle.accentColor()
        val ink = MenuStyle.ink(this)
        val light = MenuStyle.isLight(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(8f), px(4f), px(8f), 0) }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = android.widget.HorizontalScrollView(this).apply { addView(tabRow); isHorizontalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        root.addView(tabScroll)
        root.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.62f).toInt()))
        var dlg: AlertDialog? = null
        val tabViews = ArrayList<TextView>()
        fun bg(col: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(18f).toFloat(); setColor(col); setStroke(px(1.2f), stroke)
        }
        lateinit var render: () -> Unit
        render = {
            for ((i, v) in tabViews.withIndex()) {
                val on = i == tab
                v.background = bg(if (on) acc else Color.TRANSPARENT, if (on) acc else Color.argb(90, Color.red(ink), Color.green(ink), Color.blue(ink)))
                v.setTextColor(if (on) (if (light) Color.WHITE else Color.BLACK) else ink)
            }
            list.removeAllViews()
            for (it in menuTab(tab)) {
                if (it.label.startsWith("§")) {
                    list.addView(TextView(this).apply {
                        text = it.label.substring(1).uppercase(Locale.GERMANY)
                        textSize = 11f
                        setTextColor(acc)
                        letterSpacing = 0.08f
                        setPadding(px(10f), px(14f), px(10f), px(4f))
                    })
                    continue
                }
                list.addView(TextView(this).apply {
                    text = it.label
                    textSize = 15f
                    setTextColor(ink)
                    setPadding(px(12f), px(11f), px(12f), px(11f))
                    val tv = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { _ ->
                        if (it.stay) { it.run(); render() } else { dlg?.dismiss(); it.run() }
                    }
                })
            }
            scroll.scrollTo(0, 0)
        }
        for ((i, name) in MENU_TABS.withIndex()) {
            val v = TextView(this).apply {
                text = name
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(px(14f), px(8f), px(14f), px(8f))
                setOnClickListener { tab = i; prefs.edit().putInt("menuTab", i).apply(); render() }
            }
            tabViews.add(v)
            tabRow.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(6f); topMargin = px(4f); bottomMargin = px(6f) })
        }
        render()
        dlg = ui().setTitle("Marv PAD · Menü").setView(root).setNegativeButton("Schließen", null).create()
        dlg.show()
        tabScroll.post { tabViews.getOrNull(tab)?.let { tabScroll.smoothScrollTo(max(0, it.left - px(20f)), 0) } }
    }

    /** Lektionen auswählen. */
    private fun showTutorials() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val ls = pads.lessonList() + listOf("🎧 DJ-Grundlagen" to "Zwei Tracks mixen: SYNC, EQ-Übergang, Crossfader, Stems, ⏭ DROP, Mashup")
        val labels = ls.mapIndexed { i, (n, d) -> (if (prefs.getBoolean("lessonDone$i", false)) "✅ " else "") + n + "\n   " + d }.toTypedArray()
        val djIdx = ls.size - 1
        ui().setTitle("🎓 Lektionen").setItems(labels) { _, w ->
            if (w == djIdx) {
                if (!djMode) switchMode(true)
                dj.post { dj.startLesson() }
            } else {
                if (djMode) switchMode(false)
                pads.post { pads.startLesson(w) }
            }
        }
            .setNeutralButton("Kurze Tour (1 min)") { _, _ -> pads.startTour() }
            .setNegativeButton("Schließen", null).show()
    }

    private fun showMenuGroup(g: Int) {
        val (title, items) = menuGroupItems(g)
        ui().setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, w ->
                val it = items[w]
                it.second()
                if (it.third) showMenuGroup(g)
            }
            .setNegativeButton("Zurück") { _, _ -> showMainMenu() }
            .show()
    }

    private fun menuGroupItems(g: Int): Pair<String, ArrayList<Triple<String, () -> Unit, Boolean>>> {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        fun check(on: Boolean) = if (on) "☑" else "☐"
        // Einträge: Text, Aktion, danach Gruppe wieder öffnen (bei Schaltern)
        val items = ArrayList<Triple<String, () -> Unit, Boolean>>()
        val title: String
        when (g) {
            0 -> {
                title = "💾 Projekt & Dateien"
                items.add(Triple("💾 Projekt speichern", { askProjectName(null, 0) }, false))
                items.add(Triple("📂 Projekt laden", { showProjects() }, false))
                items.add(Triple("✨ Neues Projekt", {
                    confirm("Neues Projekt?", "Alle Pads, das Pattern und das Tempo werden geleert. Nicht gespeicherte Änderungen gehen verloren.") {
                        Engine.newProject()
                        toast("Neues Projekt")
                    }
                }, false))
                items.add(Triple("🎙 Meine Aufnahmen", { showRecordings() }, false))
                items.add(Triple("🔴 Meine Set-Aufnahmen (${SetRecorder.list(this).size})", { showSets() }, false))
                items.add(Triple("📚 Bibliothek importieren / exportieren", { showLibraryIo() }, false))
                items.add(Triple("☁ Backup in Dropbox / Google Drive sichern", { showBackup() }, false))
                items.add(Triple("☁ Backup wiederherstellen", { pickRestore() }, false))
                items.add(Triple("🌐 Weitere freie Samples im Netz", { showFreeSampleLinks() }, false))
            }
            1 -> {
                title = "🎙 Aufnahme & Wiedergabe"
                items.add(Triple(check(Engine.bounce) + " 🔁 Bounce: Quellen nach Wave-/Pad-Aufnahme stumm", {
                    Engine.bounce = !Engine.bounce
                    prefs.edit().putBoolean("bounce", Engine.bounce).apply()
                }, true))
                items.add(Triple(if (Engine.tapOverdub) "👆 Tipp auf laufenden Loop: ● OVERDUB" else "👆 Tipp auf laufenden Loop: ■ STOPP", {
                    Engine.tapOverdub = !Engine.tapOverdub
                    prefs.edit().putBoolean("tapOverdub", Engine.tapOverdub).apply()
                }, true))
                items.add(Triple(check(Engine.autoBeatmatch) + " 🎯 Auto-Beatmatch beim Laden (WAV/MP3 ins Tempo)", {
                    Engine.autoBeatmatch = !Engine.autoBeatmatch
                    prefs.edit().putBoolean("autoBeat", Engine.autoBeatmatch).apply()
                }, true))
                items.add(Triple("⏱ Start-Raster: " + Engine.LAUNCH_NAMES[Engine.launchIdx] + " (weiterschalten)", {
                    Engine.launchIdx = (Engine.launchIdx + 1) % Engine.LAUNCH_Q.size
                    prefs.edit().putInt("launchIdx", Engine.launchIdx).apply()
                }, true))
                items.add(Triple("⇄ Abspiel-Modus: " + Engine.PLAY_NAMES[Engine.playMode] + " (weiterschalten)", {
                    Engine.playMode = (Engine.playMode + 1) % Engine.PLAY_NAMES.size
                    prefs.edit().putInt("playMode", Engine.playMode).apply()
                }, true))
            }
            2 -> {
                title = "🎨 Aussehen"
                items.add(Triple("🖼 Design wählen (HUD, Pad- und DJ-Designs)", { showDesigns() }, false))
                items.add(Triple("✨ Menü-Aussehen: Schrift, Hell/Dunkel, Farbe, Größe", { showMenuLook() }, false))
                val gridNames = arrayOf("4×3 – alle 12 Pads", "3×3 – größere Pads (Seite 10–12)", "4×2 – Performance, sehr groß (Seite 9–12)")
                items.add(Triple("▦ Pad-Raster: " + gridNames[pads.padGrid].substringBefore(" –") + " …", {
                    ui().setTitle("Pad-Raster").setSingleChoiceItems(gridNames, pads.padGrid) { d, w ->
                        pads.padGrid = w
                        prefs.edit().putInt("padGrid", w).apply()
                        d.dismiss()
                        if (w > 0) hint("Weitere Pads: Knopf ganz rechts in der SELECT-Leiste wechselt die Seite")
                    }.show()
                }, false))
                items.add(Triple(check(pads.seqFold) + " ▾ Sequenzer-Schritte einklappen (größere Pads)", {
                    pads.seqFold = !pads.seqFold
                    prefs.edit().putBoolean("seqFold", pads.seqFold).apply()
                }, true))
                items.add(Triple(check(pads.statusColors) + " 🚦 Status-Farben (Rot Aufnahme · Grün Play · Gelb Overdub)", {
                    pads.statusColors = !pads.statusColors
                    prefs.edit().putBoolean("statusColors", pads.statusColors).apply()
                }, true))
                items.add(Triple(check(pads.zoomEnabled) + " 🔍 Doppeltipp auf Rahmen = große Ansicht", {
                    pads.zoomEnabled = !pads.zoomEnabled
                    prefs.edit().putBoolean("zoomOn", pads.zoomEnabled).apply()
                    if (!pads.zoomEnabled) pads.closeFocus()
                }, true))
                items.add(Triple(check(zoomLandscape) + " ↻ Große Ansicht automatisch im Querformat", {
                    zoomLandscape = !zoomLandscape
                    prefs.edit().putBoolean("zoomLand", zoomLandscape).apply()
                }, true))
            }
            3 -> {
                title = "🔌 Audio, Latenz & MIDI"
                items.add(Triple("🔌 Audio-Geräte (USB-Interface, Kopfhörer …)", { showAudioDevices() }, false))
                items.add(Triple(String.format(Locale.GERMANY, "⚡ Latenz & Live-Reaktion (%.0f ms)", Engine.outputLatencyMs()), { showLatency() }, false))
                items.add(Triple(check(Engine.monitorOn) + " 🎙 Live-Mikrofon mit Effekten …", { showMonitor() }, false))
                items.add(Triple("🎹 MIDI (" + (if (Midi.deviceNames.isEmpty()) "kein Gerät" else Midi.deviceNames.joinToString(", ")) + ")", { showMidi() }, false))
                val usbCh = Engine.usbOutChannels(this)
                items.add(Triple(check(Engine.quadOut) + " 🎛 4-Kanal-Ausgabe: Master 1/2, Vorhören 3/4" + (if (usbCh >= 4) " (Interface: $usbCh Kanäle)" else " (braucht ein USB-Interface mit 4 Ausgängen)"), {
                    if (usbCh < 4 && !Engine.quadOut) toast("Kein USB-Interface mit 4 Ausgängen erkannt") else setQuad(!Engine.quadOut)
                }, false))
            }
            else -> {
                title = "ℹ Hilfe & Info"
                items.add(Triple(check(pads.infoMode) + " ℹ Info-Modus (Element antippen = Erklärung)", {
                    pads.infoMode = !pads.infoMode
                    prefs.edit().putBoolean("info", pads.infoMode).apply()
                    if (pads.infoMode) toast("Info-Modus an: tippe auf ein Element, um es erklärt zu bekommen")
                }, false))
                items.add(Triple("🎓 Tour: die App in einer Minute", { if (djMode) switchMode(false); pads.startTour() }, false))
                items.add(Triple("📄 Info-Seite: Version & alle Änderungen", { showAbout() }, false))
                items.add(Triple("⏻ Programm schließen", { requestClose() }, false))
            }
        }
        return title to items
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
        ui()
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
        ui()
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
        ui()
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Ja") { _, _ -> onYes() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun askProjectName(song: ShortArray?, songRate: Int, after: (() -> Unit)? = null) {
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
        ui()
            .setTitle("Projekt speichern")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Projekt" }
                toast("Speichere Projekt …")
                Thread {
                    try {
                        Projects.save(applicationContext, name, song, songRate)
                        runOnUiThread {
                            toast("Projekt „$name“ gespeichert")
                            after?.invoke()
                        }
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
        ui()
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
        ui()
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
        ui()
            .setTitle("Meine Aufnahmen")
            .setItems(files.map { Projects.sampleTitle(it) }.toTypedArray()) { _, which ->
                val f = files[which]
                ui()
                    .setTitle(Projects.sampleTitle(f))
                    .setItems(arrayOf("⬇ Auf Pad laden", "🎧 Exportieren (WAV)", "🗑 Löschen")) { _, a ->
                        when (a) {
                            0 -> {
                                val padNames = Array(PadView.VISIBLE_PADS) { "Pad ${it + 1}" }
                                ui()
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
                    val desc = clipToPad(p, data, rate, f.name)
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
        ui()
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
        ui()
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

    private fun showDeckLoad(k: Int, force: Boolean = false) {
        TrackDb.load(this)
        val dd = Dj.decks[k]
        if (Dj.gigMode && Dj.onAir(dd) && !force) {
            confirm("Deck ${"AB"[k]} ist zu hören!", "Gig-Modus: Wirklich etwas anderes in das laufende Deck laden?") { showDeckLoad(k, true) }
            return
        }
        val items = arrayListOf(
            "💿 Sammlung – Crates, Verlauf, Vorschläge (${TrackDb.all().size})",
            "📂 Datei (MP3, WAV, M4A, OGG …)",
            "📚 Bibliothek (Loops & eigene Samples)",
            "🎙 Meine Aufnahmen",
            "▦ Von einem Pad übernehmen",
            "💿 DJ-Bibliothek (mit Cue-Punkten)"
        )
        if (dd.loaded()) items.add(0, "〰 Im Wave-Editor öffnen (Startpunkt, Hot Cues, Raster, Warp/Sync)")
        fun agTxt(d: Dj.Deck): String = if (!d.loaded() || d.lufs.isNaN()) "–" else String.format(Locale.GERMANY, "%+.1f dB", 20 * Math.log10(d.agFactor.toDouble()))
        if (dd.loaded()) {
            items.add(String.format(Locale.GERMANY, "🎚 Tempo/Raster korrigieren (%.1f BPM)", dd.bpm))
            items.add("⏏ Auswerfen")
            items.add(if (dd.libId != null) "💾 Cue-Punkte in der DJ-Bibliothek speichern" else if (dd.colId != null) "💾 Cue-Punkte in der Sammlung speichern" else "💿 Track mit Cue-Punkten in die DJ-Bibliothek")
        }
        val agIdx = items.size
        items.add(if (Dj.autoGain) String.format(Locale.GERMANY, "🔊 Auto-Gain AN (Ziel %.0f LUFS · A %s · B %s) – antippen = aus", Dj.targetLufs, agTxt(Dj.decks[0]), agTxt(Dj.decks[1])) else "🔈 Auto-Gain AUS – antippen = alle Tracks gleich laut")
        items.add("🎯 Auto-Gain-Ziel ändern (jetzt ${Dj.targetLufs.toInt()} LUFS)")
        items.add(if (Dj.colorWave) "🌈 Wellenform farbig (Bass rot · Mitten grün · Höhen blau) – antippen = Deck-Farbe" else "〰 Wellenform in Deck-Farbe – antippen = farbig nach Frequenzen")
        val cloudIdx = items.size
        items.add("☁ Aus Dropbox / Google Drive / OneDrive oder per Link laden")
        val aiIdx = items.size
        items.add("🧠 KI-Stems (Vocals · Drums · Bass · Melodie) …")
        ui()
            .setTitle("Deck ${"AB"[k]} laden")
            .setItems(items.toTypedArray()) { _, w0 ->
                if (w0 == cloudIdx) { showCloudImport(CLOUD_DECK + k); return@setItems }
                if (w0 == aiIdx) { showAiStems(k); return@setItems }
                if (w0 == agIdx) {
                    Dj.autoGain = !Dj.autoGain
                    getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("djAutoGain", Dj.autoGain).apply()
                    hint(if (Dj.autoGain) "Auto-Gain an: alle Tracks auf ${Dj.targetLufs.toInt()} LUFS" else "Auto-Gain aus")
                    return@setItems
                }
                if (w0 == agIdx + 1) {
                    val opts = intArrayOf(-8, -10, -12, -14, -16)
                    ui().setTitle("Auto-Gain: Ziel-Lautheit").setItems(opts.map { "$it LUFS" + when (it) { -8 -> " (sehr laut, wenig Reserve)"; -14 -> " (Standard, sauberes Mischen)"; -16 -> " (leise, viel Reserve)"; else -> "" } }.toTypedArray()) { _, i ->
                        Dj.changeTargetLufs(opts[i].toFloat())
                        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putFloat("djTargetLufs2", Dj.targetLufs).apply()
                    }.show()
                    return@setItems
                }
                if (w0 == agIdx + 2) {
                    Dj.colorWave = !Dj.colorWave
                    getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("djColorWave", Dj.colorWave).apply()
                    return@setItems
                }
                if (dd.loaded() && w0 == 0) {
                    dj.openEditor(k)
                    return@setItems
                }
                val w = (if (dd.loaded()) w0 - 1 else w0) - 1
                if (w == -1) { showCollection(k); return@setItems }
                when (w) {
                    0 -> {
                        djDeck = k
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "audio/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        try { startActivityForResult(i, REQ_DECK) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
                    }
                    1 -> deckFromLibrary(k)
                    2 -> {
                        val files = Projects.listSamples(this)
                        if (files.isEmpty()) { toast("Noch keine Aufnahmen"); return@setItems }
                        ui()
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
                        ui()
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
                        saveDeckCollection(k)
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



    // ================================================================ Gig-Modus

    private var gigPrevFilter = -1
    private val gigAutosave = object : Runnable {
        override fun run() {
            if (!Dj.gigMode) return
            try {
                saveDeckCollection(0)
                saveDeckCollection(1)
                TrackDb.save(this@MainActivity)
            } catch (ignored: Throwable) {}
            window.decorView.postDelayed(this, 20_000L)
        }
    }

    /** Checkliste vor dem Auftritt: Akku, Flugmodus, Nicht stören, Ausgang. */
    private fun showGigCheck() {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val air = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val dndOk = nm.isNotificationPolicyAccessGranted
        val usbCh = Engine.usbOutChannels(this)
        val lines = StringBuilder()
        lines.append(if (pct < 0) "🔋 Akku: ?" else "🔋 Akku $pct %" + (if (charging) " · lädt ✅" else if (pct < 50) " · ⚠ Ladekabel anschließen!" else " · Ladekabel empfohlen")).append("\n")
        lines.append(if (air) "✈ Flugmodus an ✅ (keine Anrufe)" else "✈ Flugmodus aus – empfohlen: einschalten (Anrufe, Updates)").append("\n")
        lines.append(if (dndOk) "🔕 Nicht stören: wird für den Gig eingeschaltet ✅" else "🔕 Nicht stören: braucht einmal deine Erlaubnis (Knopf unten)").append("\n")
        lines.append(if (usbCh >= 4) "🎛 USB-Interface mit $usbCh Kanälen erkannt – Vorhören auf 3/4 möglich" else if (usbCh > 0) "🎛 USB-Audio (Stereo) – Vorhören über Split-Cue (lange auf DECK drücken)" else "🎧 Kein USB-Interface – Vorhören über Split-Cue (lange auf DECK drücken)").append("\n\n")
        lines.append("Im Gig-Modus:\n• Bildschirm bleibt an\n• ein hörbares Deck stoppt nur per Doppeltipp\n• Laden auf ein hörbares Deck fragt nach\n• Cue-Punkte und Sammlung werden alle 20 s gesichert\n• Zurück-Taste fragt nach")
        val b = ui().setTitle("🎤 Gig-Check").setMessage(lines.toString())
            .setPositiveButton("Gig starten") { _, _ -> startGig() }
        if (!air) b.setNeutralButton("✈ Flugmodus …") { _, _ -> try { startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) } catch (e: Exception) { toast("Einstellung nicht gefunden") } }
        else if (!dndOk) b.setNeutralButton("🔕 Erlauben …") { _, _ -> try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } catch (e: Exception) { toast("Einstellung nicht gefunden") } }
        if (usbCh >= 4) b.setNegativeButton(if (Engine.quadOut) "4 Kanäle: AN" else "4 Kanäle nutzen") { _, _ -> setQuad(!Engine.quadOut); showGigCheck() }
        else b.setNegativeButton("Abbrechen", null)
        b.show()
    }

    private fun setQuad(on: Boolean) {
        Engine.quadOut = on
        getSharedPreferences("marvpad", MODE_PRIVATE).edit().putBoolean("quadOut", on).apply()
        scheduleAudioRestart()
        window.decorView.postDelayed({ hint(if (Engine.outChannels == 4) "🎛 4 Kanäle: Master auf 1/2, Vorhören (🎧) auf 3/4" else if (on) "4 Kanäle nicht möglich – das Interface bietet Android nur Stereo an" else "Ausgabe: Stereo") }, 1500L)
    }

    private fun startGig() {
        Dj.gigMode = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            try {
                gigPrevFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS)
            } catch (ignored: Exception) {}
        }
        window.decorView.removeCallbacks(gigAutosave)
        window.decorView.postDelayed(gigAutosave, 20_000L)
        hint("🎤 GIG-MODUS AN – viel Spaß!")
    }

    private fun stopGig() {
        Dj.gigMode = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.removeCallbacks(gigAutosave)
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (gigPrevFilter > 0 && nm.isNotificationPolicyAccessGranted) try { nm.setInterruptionFilter(gigPrevFilter) } catch (ignored: Exception) {}
        gigPrevFilter = -1
        saveDeckCollection(0)
        saveDeckCollection(1)
        hint("Gig-Modus aus – Nicht stören wieder wie vorher")
    }

    @Deprecated("Zurück-Taste")
    override fun onBackPressed() {
        if (pads.slicerOpen) { pads.closeSlicer(); return }
        if (pads.stationOpen) { pads.closeStation(); return }
        if (pads.launcherOpen) { pads.closeLauncher(); return }
        if (pads.ghost.isOpen) { pads.ghost.close(); pads.invalidate(); return }
        if (dj.ghost.isOpen) { dj.ghost.close(); dj.invalidate(); return }
        if (remoteView != null) { leaveRemote(); return }
        if (Dj.gigMode) {
            confirm("Gig läuft", "Wirklich zurück? Die Musik spielt weiter, solange ein Deck läuft.") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ================================================================ Teilen

    private fun showShare() {
        val sets = SetRecorder.list(this)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("🎵 Song einmal durchspielen, aufnehmen und teilen" to {
            hint("Der Song läuft einmal in Echtzeit durch – danach öffnet sich Teilen")
            startBounce(null, false, true)
        })
        if (sets.isNotEmpty()) items.add("🔴 Letzte Set-Aufnahme teilen (${sets[0].nameWithoutExtension})" to { shareWav(sets[0], sets[0].nameWithoutExtension, false) })
        if (sets.size > 1) items.add("📂 Andere Set-Aufnahme wählen …" to { showSets() })
        ui().setTitle("📲 Teilen")
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** WAV als AAC (.m4a) in Musik/Marv PAD ablegen und das Teilen-Menü öffnen. */
    private fun shareWav(f: java.io.File, title: String, deleteAfter: Boolean) {
        val d = resources.displayMetrics.density
        val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt()); addView(pb) }
        val dlg = ui().setTitle("📲 Wird fürs Teilen vorbereitet …").setView(box).setCancelable(false).show()
        Thread {
            try {
                val safe = title.replace(Regex("[^A-Za-z0-9äöüÄÖÜß ._-]+"), "_")
                val uri = ShareAudio.toMusic(this, f, safe) { p -> runOnUiThread { pb.progress = (p * 1000).toInt() } }
                if (deleteAfter) f.delete()
                runOnUiThread {
                    try { dlg.dismiss() } catch (ignored: Exception) {}
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, safe)
                        putExtra(Intent.EXTRA_TEXT, "Gemacht mit Marv PAD 🎛")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Teilen: $safe"))
                    hint("Auch gespeichert unter Musik › Marv PAD")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    try { dlg.dismiss() } catch (ignored: Exception) {}
                    toast("Teilen fehlgeschlagen: ${e.message ?: e}")
                }
            }
        }.start()
    }

    // ================================================================ Ghost-Producer

    private fun openGhost() {
        val g = if (djMode) dj.ghost else pads.ghost
        g.onKeyPick = { pickGhostKey() }
        g.onPickLoopFile = {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
            try { startActivityForResult(i, REQ_GHOST_LOOP) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
        }
        if (djMode) dj.openGhost() else pads.openGhost()
        hint("Karte antippen oder auf eine Spur ziehen – Tempo, Takt-Eins und Tonart kommen vom laufenden Deck")
    }

    private fun pickGhostKey() {
        val names = arrayOf("AUTO (Tonart des Decks)") + (0 until 24).map { Keys.name(it) + "  (" + Keys.camelot(it) + ")" }
        ui().setTitle("Tonart für Bass & Synth")
            .setItems(names) { _, w ->
                if (w == 0) Ghost.keyAuto = true else { Ghost.keyAuto = false; Ghost.keyManual = w - 1 }
            }
            .show()
    }

    // ================================================================ Stems trennen

    private fun splitStems(p: Int) {
        val free = (0 until PadView.VISIBLE_PADS).count { it != p && !Engine.hasLoop(it) }
        if (Engine.loopData(p) == null) { toast("Pad ${p + 1} ist leer"); return }
        if (!AiStems.ready(this)) {
            ui().setTitle("🧠 Stems trennen – Pad ${p + 1}").setItems(arrayOf(
                "🧠 KI-Trennung (Vocals · Drums · Bass · Melodie) – einmalig ~80 MB Modelle laden",
                "⚡ Schnell (Drums · Bass · Melodie & Gesang, ohne KI)"
            )) { _, w ->
                if (w == 0) progressJob("🧠 KI-Modelle laden", { cancel, prog -> AiStems.download(this, cancel, prog) }) { splitStemsAi(p) }
                else splitStemsFast(p, free)
            }.setNegativeButton("Abbrechen", null).show()
            return
        }
        if (free < 4) {
            if (free >= 3) {
                ui().setTitle("Nur $free freie Pads").setMessage("Die KI-Trennung braucht 4 freie Pads (Vocals, Drums, Bass, Melodie). Die schnelle Trennung kommt mit 3 aus.")
                    .setPositiveButton("Schnell trennen") { _, _ -> splitStemsFast(p, free) }
                    .setNegativeButton("Abbrechen", null).show()
            } else toast("Für die KI-Stems werden 4 freie Pads gebraucht (frei: $free)")
            return
        }
        splitStemsAi(p)
    }

    /** KI-Trennung eines Pads auf 4 freie Pads (synchron, Original wird stumm). */
    private fun splitStemsAi(p: Int) {
        val free = (0 until PadView.VISIBLE_PADS).count { it != p && !Engine.hasLoop(it) }
        if (free < 4) { toast("Für die KI-Stems werden 4 freie Pads gebraucht (frei: $free)"); return }
        val len = Engine.padDataLen(p)
        System.gc()
        val rt = Runtime.getRuntime()
        val freeMem = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (len.toLong() * 4 * 10 > freeMem) { toast("Zu lang für den Speicher – erst kürzen (Wave-Editor) oder andere Pads leeren"); return }
        val secs = len / Engine.sampleRate.toFloat()
        hint(String.format(Locale.GERMANY, "🧠 KI trennt Pad %d (%.0f s) – das dauert etwas, die App bleibt bedienbar …", p + 1, secs))
        Thread {
            try {
                Engine.padLoadText[p] = "🧠 KI-Stems"
                Engine.padLoadProg[p] = 0f
                val data = Engine.loopData(p) ?: return@Thread
                val stems = AiStems.separatePad(this, data, Engine.sampleRate, { false }) { f -> Engine.padLoadProg[p] = f } ?: return@Thread
                val base = Engine.padName(p)?.take(10) ?: "P${p + 1}"
                val names = arrayOf("Vocals · $base", "Drums · $base", "Bass · $base", "Melodie · $base")
                val t = Engine.stemsToPads(p, stems, names, PadView.VISIBLE_PADS)
                runOnUiThread {
                    if (t.isEmpty()) toast("Keine freien Pads mehr")
                    else hint("🧠 Vocals → Pad ${t[0] + 1}, Drums → Pad ${t[1] + 1}, Bass → Pad ${t[2] + 1}, Melodie → Pad ${t[3] + 1} · Original stumm (antippen = wieder an)")
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { toast("Zu lang für den Speicher – erst kürzen") }
            } catch (e: Throwable) {
                val c = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                runOnUiThread { toast("KI-Stems fehlgeschlagen: ${c.message ?: c}") }
            } finally {
                Engine.padLoadProg[p] = -1f
            }
        }.start()
    }

    private fun splitStemsFast(p: Int, free: Int) {
        if (free < 3) { toast("Für die 3 Stems werden 3 freie Pads gebraucht (frei: $free)"); return }
        val len = Engine.padDataLen(p)
        System.gc()
        val rt = Runtime.getRuntime()
        val freeMem = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (len.toLong() * 4 * 5 > freeMem) { toast("Zu lang für den Speicher – erst kürzen (Wave-Editor) oder andere Pads leeren"); return }
        val secs = len / Engine.sampleRate.toFloat()
        hint(String.format(Locale.GERMANY, "🎚 Trenne Pad %d (%.0f s) – dauert etwa %.0f s …", p + 1, secs, max(3f, secs * 0.25f)))
        Thread {
            try {
                Engine.padLoadText[p] = "Stems trennen"
                Engine.padLoadProg[p] = 0f
                val data = Engine.loopData(p) ?: return@Thread
                val stems = Stems.split(data, Engine.sampleRate) { f -> Engine.padLoadProg[p] = f }
                val base = Engine.padName(p)?.take(10) ?: "P${p + 1}"
                val names = arrayOf("Drums · $base", "Bass · $base", "Melodie · $base")
                val t = Engine.stemsToPads(p, stems, names, PadView.VISIBLE_PADS)
                runOnUiThread {
                    if (t.isEmpty()) toast("Keine freien Pads mehr")
                    else hint("🎚 Drums → Pad ${t[0] + 1}, Bass → Pad ${t[1] + 1}, Melodie & Gesang → Pad ${t[2] + 1} · Original stumm (antippen = wieder an)")
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { toast("Zu lang für den Speicher – erst kürzen") }
            } catch (e: Throwable) {
                runOnUiThread { toast("Stems fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                Engine.padLoadProg[p] = -1f
            }
        }.start()
    }

    // ================================================================ Slicer

    private fun openSlicerFor(p: Int) {
        if (Slicer.srcPad != p || Slicer.data == null) {
            Slicer.srcPad = p
            Slicer.startBar = 0
            Slicer.bars = if (Engine.padDataLen(p) >= Engine.masterFrames() * 2 && Engine.masterFrames() > 0) 2 else 1
            Slicer.clearPattern()
        }
        if (!Slicer.rebuild()) { toast("Pad ${p + 1} ist leer"); return }
        if (djMode) switchMode(false)
        pads.openSlicer()
        hint("✂ Pad ${p + 1} in 16 Slices – Pads unten links = 1 · ◀ ▶ wählt den Abschnitt · ● REC + ▶ nimmt ein Pattern auf")
    }

    private fun pickSlicerSource() {
        val list = (0 until PadView.VISIBLE_PADS).filter { Engine.hasLoop(it) && !Engine.isOneShot(it) }
        if (list.isEmpty()) { toast("Erst einen Loop oder Song auf ein Pad laden"); return }
        val labels = list.map { p ->
            val bars = if (Engine.masterFrames() > 0) (Engine.padDataLen(p) / Engine.masterFrames()).toInt() else 1
            "Pad ${p + 1}" + (Engine.padName(p)?.let { " · $it" } ?: "") + " · $bars Takt" + if (bars != 1) "e" else ""
        }.toTypedArray()
        ui().setTitle("✂ Slicer · Quelle wählen")
            .setItems(labels) { _, w -> Slicer.clearPattern(); Slicer.srcPad = -1; openSlicerFor(list[w]) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ================================================================ Song-Export

    private var exportKind = 0

    /** Gibt es ein Arrangement (Song-Matrix)? Sonst wird der aktuelle Stand 8 Takte lang aufgenommen. */
    private fun hasArrangement(): Boolean =
        (0 until Engine.songBars).any { b -> (0 until Engine.ARR_ROWS).any { r -> Engine.arrCell(r, b) > 0 } }

    private fun exportBars(): Int = if (hasArrangement()) Engine.songBars else 8

    private fun showSongExport() {
        val bpm = Engine.currentBpm().let { if (it > 0f) it else 120f }
        val bars = exportBars()
        val secs = (bars * 240f / bpm).toInt()
        val what = if (hasArrangement()) "Song-Matrix: $bars Takte" else "kein Song angelegt – aktueller Stand, 8 Takte"
        val items = arrayOf(
            "🎵 Ganzer Song als WAV (Stereo, mit allen Effekten)",
            "🎚 Song + Stems: jede Spur als eigene WAV (ACID, DRUM, SYNTH 1–3, PADS)",
            "🎼 MIDI-Datei: alle Instrument-Spuren über den ganzen Song"
        )
        ui().setTitle("📤 Exportieren · $what · ${secs / 60}:${String.format(Locale.GERMANY, "%02d", secs % 60)} min")
            .setItems(items) { _, w ->
                exportKind = w
                if (w < 2) hint("Ordner wählen – danach läuft der Song einmal in Echtzeit durch und wird aufgenommen")
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                try { startActivityForResult(i, REQ_EXPORT_DIR) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Song einmal in Echtzeit abspielen und dabei Master (und Stems) aufnehmen, danach in den Ordner kopieren. */
    private fun startBounce(tree: Uri?, stems: Boolean, share: Boolean = false) {
        if (SetRecorder.recording) { toast("Es läuft schon eine Aufnahme"); return }
        val rate = Engine.sampleRate
        val bpm = Engine.currentBpm().let { if (it > 0f) it else 120f }
        val song = hasArrangement()
        val bars = exportBars()
        val playSec = bars * 240.0 / bpm
        val totalSec = playSec + 2.5
        Engine.seqStop()
        val oldSong = Engine.songMode
        if (song) Engine.songMode = true
        val tmp = java.io.File(cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        if (!SetRecorder.start(this, rate)) { toast("Aufnahme konnte nicht starten"); return }
        if (stems && !StemRecorder.start(tmp, rate)) { SetRecorder.stop()?.delete(); toast("Stems konnten nicht starten"); return }
        Engine.seqPlay()
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply { textSize = 15f; setTextColor(0xFFEEEEEE.toInt()); setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt()) }
        val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(pb, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = (20 * d).toInt(); rightMargin = (20 * d).toInt() }) }
        var cancelled = false
        var finished = false
        val dlg = ui().setTitle(if (stems) "📤 Song + Stems werden aufgenommen" else "📤 Song wird aufgenommen")
            .setView(box)
            .setNegativeButton("Abbrechen") { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        val h = android.os.Handler(mainLooper)
        var stopped = false
        fun finish() {
            if (finished) return
            finished = true
            Engine.seqStop()
            Engine.songMode = oldSong
            val master = SetRecorder.stop()
            val stemFiles = if (stems) StemRecorder.stop() else emptyList()
            try { dlg.dismiss() } catch (ignored: Exception) {}
            if (cancelled) { master?.delete(); tmp.deleteRecursively(); toast("Export abgebrochen"); return }
            if (share || tree == null) {
                tmp.deleteRecursively()
                if (master != null) shareWav(master, "MarvPAD_Song_" + stamp(), true)
                return
            }
            toast("Speichere …")
            Thread {
                var n = 0
                try {
                    val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                    val base = "MarvPAD_" + stamp()
                    fun copy(f: java.io.File, name: String) {
                        val doc = android.provider.DocumentsContract.createDocument(contentResolver, dir, "audio/wav", name) ?: return
                        contentResolver.openOutputStream(doc)?.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
                        n++
                    }
                    if (master != null) copy(master, "${base}_Song.wav")
                    for ((i, f) in stemFiles.withIndex()) if (StemRecorder.peak[i] > 0.0005f) copy(f, "${base}_${StemRecorder.NAMES[i]}.wav")
                    master?.delete()
                    tmp.deleteRecursively()
                    runOnUiThread { hint("📤 $n Datei(en) gespeichert" + if (stems) " – Stems sind trocken (ohne XY-Effekte und Master-Kette)" else "") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Speichern fehlgeschlagen: ${e.message ?: e}") }
                }
            }.start()
        }
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                val t = SetRecorder.seconds()
                pb.progress = (t / totalSec * 1000).toInt().coerceIn(0, 1000)
                val bar = (t * bpm / 240.0).toInt() + 1
                tv.text = if (t < playSec) "Takt ${bar.coerceAtMost(bars)} / $bars · ${Dj.fmt(t)} / ${Dj.fmt(playSec)}\nApp bitte offen lassen." else "Ausklang …"
                if (!stopped && t >= playSec) { stopped = true; Engine.seqStop() }
                if (cancelled || t >= totalSec) finish() else h.postDelayed(this, 200)
            }
        }
        h.post(tick)
    }

    /** MIDI aller Instrument-Spuren über den ganzen Song (Patterns A–D laut Song-Matrix). */
    private fun writeSongMidi(tree: Uri) {
        val st = InstState
        val song = hasArrangement()
        val bars = exportBars()
        val steps = bars * 16
        val bpm = Engine.currentBpm().let { if (it > 0f) it else 120f }
        val tracks = ArrayList<MidiFile.Track>()
        for (inst in 0 until InstState.INSTS) {
            val notes = ArrayList<MidiFile.Note>()
            // welches Pattern läuft in Schritt s (null = Spur aus)
            val cache = HashMap<Int, InstState.Pat?>()
            fun patAt(s: Int): Pair<InstState.Pat, Int>? {
                val b = s / 16
                val v = if (song) Engine.arrCell(Engine.ARR_INST + inst, b) else (if (st.instOn(inst)) st.curPat[inst] + 1 else 0)
                if (v <= 0) return null
                val p = cache.getOrPut(v) { st.patFor(inst, v - 1) } ?: return null
                return p to v
            }
            if (inst == 1) {
                for (s in 0 until steps) {
                    val (p, _) = patAt(s) ?: continue
                    val k = s % p.len.coerceIn(1, InstState.MAX)
                    for (v in 0 until min(InstState.DV, p.ints.size)) {
                        val x = p.ints[v][k]
                        if (x > 0) notes.add(MidiFile.Note(s / 4.0, 0.25, GM[v], ((if (x == 2) 127 else 96) * (p.floats.getOrNull(v)?.get(k) ?: 1f)).toInt().coerceIn(1, 127)))
                    }
                }
            } else {
                val t = if (inst >= 2) st.synTracks[inst - 2] else null
                var s = 0
                while (s < steps) {
                    val (p, v) = patAt(s) ?: run { s++; null } ?: continue
                    val L = p.len.coerceIn(1, InstState.MAX)
                    val k = s % L
                    val gate = p.bools[0]
                    val tie = p.bools.getOrNull(if (inst == 0) 3 else 1)
                    val prevSame = s > 0 && patAt(s - 1)?.second == v
                    if (!gate[k] || (tie != null && tie[k] && prevSame && gate[Math.floorMod(k - 1, L)])) { s++; continue }
                    var n = 1
                    while (s + n < steps && patAt(s + n)?.second == v && gate[(k + n) % L] && tie != null && tie[(k + n) % L]) n++
                    val semi = p.ints[0][k]
                    val vel = (110 * (p.floats.getOrNull(0)?.get(k) ?: 1f)).toInt().coerceIn(1, 127)
                    if (t == null) {
                        notes.add(MidiFile.Note(s / 4.0, n / 4.0 - 0.02, 36 + 12 * st.acidOct + st.acidTune + semi, vel))
                    } else {
                        val chd = p.ints.getOrNull(1)?.get(k) ?: 0
                        val iv = if (chd > 0) st.synChordIv[(chd - 1).coerceIn(0, st.synChordIv.size - 1)] else st.synChordIv[t.chord.coerceIn(0, st.synChordIv.size - 1)]
                        for (o in iv) notes.add(MidiFile.Note(s / 4.0, n / 4.0 - 0.02, 60 + 12 * t.oct + semi + o, vel))
                    }
                    s += n
                }
            }
            if (notes.isNotEmpty()) tracks.add(MidiFile.Track(InstState.instNames[inst], when (inst) { 0 -> 0; 1 -> 9; else -> inst - 1 }, notes))
        }
        if (tracks.isEmpty()) { toast("Keine Instrument-Noten im Song"); return }
        try {
            val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val doc = android.provider.DocumentsContract.createDocument(contentResolver, dir, "audio/midi", "MarvPAD_${stamp()}.mid")
                ?: throw IllegalStateException("Datei nicht anlegbar")
            contentResolver.openOutputStream(doc)?.use { it.write(MidiFile.exportMulti(tracks, bpm, "Marv PAD Song")) }
            hint("🎼 MIDI gespeichert: ${tracks.size} Spuren, $bars Takte (Arpeggios als Akkorde, Pads nicht enthalten)")
        } catch (e: Exception) {
            toast("MIDI-Export fehlgeschlagen: ${e.message ?: e}")
        }
    }

    // ================================================================ Sidechain

    private fun loadSidechain() {
        val p = getSharedPreferences("marvpad", MODE_PRIVATE)
        Engine.scOn = p.getBoolean("scOn", false)
        Engine.scSource = p.getInt("scSource", 0)
        Engine.scDepth = p.getFloat("scDepth", 0.7f)
        Engine.scRelease = p.getFloat("scRelease", 0.4f)
        Engine.scAcid = p.getBoolean("scAcid", true)
        Engine.scPads = p.getBoolean("scPads", false)
        for (t in Engine.scSyn.indices) Engine.scSyn[t] = p.getBoolean("scSyn$t", true)
    }

    private fun saveSidechain() {
        val e = getSharedPreferences("marvpad", MODE_PRIVATE).edit()
        e.putBoolean("scOn", Engine.scOn).putInt("scSource", Engine.scSource).putFloat("scDepth", Engine.scDepth)
            .putFloat("scRelease", Engine.scRelease).putBoolean("scAcid", Engine.scAcid).putBoolean("scPads", Engine.scPads)
        for (t in Engine.scSyn.indices) e.putBoolean("scSyn$t", Engine.scSyn[t])
        e.apply()
    }

    private fun showSidechain() {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(8f), px(20f), px(8f)) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFFCCCCCC.toInt()); setPadding(0, px(12f), 0, px(2f)) }
        fun check(t: String, on: Boolean, set: (Boolean) -> Unit) = android.widget.CheckBox(this).apply {
            text = t; isChecked = on; setTextColor(0xFFEEEEEE.toInt())
            setOnCheckedChangeListener { _, c -> set(c); saveSidechain() }
        }
        fun bar(v: Float, set: (Float) -> Unit, show: (Float) -> String): View {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tv = TextView(this).apply { textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); minWidth = px(64f); text = show(v) }
            val sb = SeekBar(this).apply {
                max = 100; progress = (v * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) { val f = p / 100f; set(f); tv.text = show(f) }
                    override fun onStartTrackingTouch(b: SeekBar?) {}
                    override fun onStopTrackingTouch(b: SeekBar?) { saveSidechain() }
                })
            }
            row.addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv)
            return row
        }
        box.addView(check("Sidechain an", Engine.scOn) { Engine.scOn = it })
        box.addView(label("Auslöser"))
        val rg = android.widget.RadioGroup(this)
        val r0 = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Bass Drum der DRUM 909"; setTextColor(0xFFEEEEEE.toInt()) }
        val r1 = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Jede Viertel (auch ohne Drums – klassisches House-Pumpen)"; setTextColor(0xFFEEEEEE.toInt()) }
        rg.addView(r0); rg.addView(r1)
        rg.check(if (Engine.scSource == 0) r0.id else r1.id)
        rg.setOnCheckedChangeListener { _, id -> Engine.scSource = if (id == r0.id) 0 else 1; saveSidechain() }
        box.addView(rg)
        box.addView(label("Stärke"))
        box.addView(bar(Engine.scDepth, { Engine.scDepth = it }) { "${(it * 100).toInt()} %" })
        box.addView(label("Zurück auf voll nach"))
        box.addView(bar(Engine.scRelease, { Engine.scRelease = it }) { "${(60 + it * 440).toInt()} ms" })
        box.addView(label("Wird weggedrückt"))
        box.addView(check("ACID-Bass", Engine.scAcid) { Engine.scAcid = it })
        for (t in Engine.scSyn.indices) box.addView(check("SYNTH ${t + 1}", Engine.scSyn[t]) { Engine.scSyn[t] = it })
        box.addView(check("Pads (Loops & Samples)", Engine.scPads) { Engine.scPads = it })
        ui().setTitle("〽 Sidechain-Pumpen")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fertig", null)
            .show()
    }

    // ================================================================ Tonleiter (Piano-Roll)

    /** Akkordfolge: Folge → Tonart → Länge je Akkord → Rhythmus, dann in die aktuelle SYNTH-Spur schreiben. */
    private fun showChordProgression() {
        val st = InstState
        val root = st.scaleRoot
        val prs = st.progressions
        val labels = prs.map { pr ->
            val ch = (0 until pr.degrees.size).joinToString(" – ") { st.progChordName(pr, it, root) }
            pr.name + "\n   " + ch
        }.toTypedArray()
        ui().setTitle("🎼 Akkordfolge (SYNTH ${st.synCur + 1}) · Grundton ${st.scaleNoteNames[root]}")
            .setItems(labels) { _, w ->
                val pr = prs[w]
                ui().setTitle("Grundton der Tonart").setItems(st.scaleNoteNames.map { it + if (pr.minor) "-Moll" else "-Dur" }.toTypedArray()) { _, r ->
                    ui().setTitle("Länge je Akkord").setItems(arrayOf("1 Takt (Folge = ${pr.degrees.size} Takte)", "½ Takt (Folge = ${pr.degrees.size / 2} Takte)")) { _, l ->
                        ui().setTitle("Rhythmus").setItems(st.rhythmNames) { _, rh ->
                            Engine.pushPatternUndo("Akkordfolge")
                            st.writeProgression(pr, r, if (l == 0) 16 else 8, rh)
                            pads.invalidate()
                            val ch = (0 until pr.degrees.size).joinToString(" – ") { st.progChordName(pr, it, r) }
                            hint("🎼 $ch in SYNTH ${st.synCur + 1} – Tipp: ARP an für eine Tonfolge, andere Spur für Bass")
                        }.show()
                    }.show()
                }.show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showScaleMenu() {
        val st = InstState
        val items = arrayOf(
            "Tonleiter: ${st.scaleNames[st.scaleType]}  ▸",
            "Grundton: ${st.scaleNoteNames[st.scaleRoot]}  ▸",
            (if (st.scaleSnap) "☑" else "☐") + " Einrasten: neue/verschobene Noten nur auf Tönen der Tonleiter",
            "Tonleiter aus"
        )
        ui().setTitle("🎼 Tonleiter: " + st.scaleLabel())
            .setItems(items) { _, w ->
                when (w) {
                    0 -> ui().setTitle("Tonleiter").setItems(st.scaleNames) { _, t -> st.scaleType = t; showScaleMenu() }.show()
                    1 -> ui().setTitle("Grundton").setItems(st.scaleNoteNames) { _, r -> st.scaleRoot = r; if (st.scaleType == 0) st.scaleType = 2; showScaleMenu() }.show()
                    2 -> { st.scaleSnap = !st.scaleSnap; showScaleMenu() }
                    else -> { st.scaleType = 0; st.scaleSnap = false }
                }
                pads.invalidate()
            }
            .setNegativeButton("Fertig", null)
            .show()
    }

    // ================================================================ Handys koppeln

    private var btAfter: (() -> Unit)? = null

    /** Bluetooth-Erlaubnis (ab Android 12) holen, danach [then] – ohne Erlaubnis läuft es eben nur über WLAN. */
    private fun withBt(then: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            btAfter = then
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        } else then()
    }

    private fun btAllowed() = android.os.Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun showCoupling() {
        if (Link.hosting) { showHostPanel(); return }
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), px(6f), px(18f), px(6f)) }
        var dlg: AlertDialog? = null
        fun big(label: String, act: () -> Unit) = box.addView(Button(this).apply {
            isAllCaps = false; text = label; textSize = 16f
            setPadding(px(12f), px(14f), px(12f), px(14f))
            setOnClickListener { dlg?.dismiss(); act() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        big("🔊 HANDY A: Dieses Handy macht den Klang (Host)") { withBt { startHosting() } }
        big("📱 HANDY B: Dieses Handy wird Bedienfeld") { withBt { showRemoteConnect() } }
        box.addView(TextView(this).apply {
            textSize = 13f; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, px(14f), 0, 0)
            text = "Erst auf Handy A „Klang (Host)“ wählen, dann auf Handy B „Bedienfeld“.\n\n" +
                "WLAN: beide im selben WLAN – am sichersten im Hotspot von Handy A.\n" +
                "Bluetooth: Handys vorher in den Android-Einstellungen koppeln."
        })
        dlg = ui().setTitle("📡 Handys koppeln")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("Schließen", null)
            .create()
        dlg.show()
    }

    private fun startHosting() {
        ensureStarted()
        Link.onClientsChanged = {
            runOnUiThread {
                hostRefresh?.invoke()
                if (Link.clients.size > lastClientCount && Link.clients.size >= 1) {
                    hint("📡 Handy ${Link.clients.size + 1} gekoppelt – Vorlage wählen: Menü → 📡 Handys gekoppelt")
                }
                lastClientCount = Link.clients.size
            }
        }
        Link.onHostHint = { m -> runOnUiThread { hint(m) } }
        Link.onHostSurface = { h -> runOnUiThread { if (h.startsWith("DJ")) switchMode(true) else if (h == "Pads") switchMode(false) } }
        Link.startHost(this, btAllowed())
        showHostPanel()
    }

    private var lastClientCount = 0
    private var hostRefresh: (() -> Unit)? = null

    /** Wie viele Handys eine Vorlage nutzt (dieses + gekoppelte). */
    private fun presetPhones(p: Link.Preset): Int = 1 + Regex("Handy \\d").findAll(p.name).count()

    private fun showHostPanel() {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), px(8f), px(18f), px(4f)) }
        val info = TextView(this).apply { textSize = 13f; setTextColor(0xFFCCCCCC.toInt()) }
        box.addView(info)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        var dlg: AlertDialog? = null
        fun build() {
            val ips = Link.localIps().joinToString(" · ")
            val n = Link.clients.size
            val pr = Link.PRESETS.getOrNull(Link.currentPreset)
            info.text = "Host läuft: ${Link.deviceName(this)}\n" +
                "WLAN: ${Link.wlanStatus}\n" +
                "Adresse für „Adresse eingeben“: ${ips.ifEmpty { "– (kein WLAN/Hotspot verbunden)" }}\n" +
                "Bluetooth: ${Link.btStatus}\n" +
                (Link.stateError?.let { "⚠ Datenfehler: $it\n" } ?: "") +
                "\nGekoppelt: $n ${if (n == 1) "Handy" else "Handys"}" +
                (if (pr != null) "\nVorlage: ${pr.name}" else "") +
                (if (n == 0) "\n\nAuf dem anderen Handy: Menü → 📡 Handys koppeln → Bedienfeld." else "")
            list.removeAllViews()
            for ((k, c) in Link.clients.withIndex()) {
                list.addView(Button(this).apply {
                    isAllCaps = false
                    text = "Handy ${k + 2}: ${c.name}${if (c.viaBt) " (BT)" else ""}\n→ ${Link.SURFACE_NAMES[c.surface.coerceIn(0, 5)]}   ✎"
                    setOnClickListener {
                        ui().setTitle("Handy ${k + 2} zeigt …")
                            .setItems(Link.SURFACE_NAMES) { _, w -> Link.setSurface(c, w) }
                            .show()
                    }
                })
            }
        }
        build()
        hostRefresh = { build() }
        dlg = ui().setTitle("📡 Handys gekoppelt")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("🧩 Vorlagen", null)
            .setNeutralButton("Host beenden") { _, _ ->
                Link.stopHost(); lastClientCount = 0; toast("Kopplung beendet")
            }
            .setNegativeButton("Fertig", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { showPresets() }
        }
        dlg.setOnDismissListener { hostRefresh = null }
        dlg.show()
    }

    /** Vorlagen: wie die Steuerung auf die Handys verteilt wird – passende zur Anzahl zuerst. */
    private fun showPresets() {
        val have = Link.clients.size + 1
        val order = Link.PRESETS.indices.sortedWith(compareBy({ if (presetPhones(Link.PRESETS[it]) == have) 0 else if (presetPhones(Link.PRESETS[it]) < have) 1 else 2 }, { it }))
        val labels = order.map { i ->
            val p = Link.PRESETS[i]
            val need = presetPhones(p)
            val mark = when {
                need == have -> "✓ "
                need < have -> "◦ "
                else -> "⚠ "
            }
            val extra = if (need > have) "  (braucht $need Handys, $have gekoppelt)" else "  ($need Handys)"
            (if (i == Link.currentPreset) "● " else mark) + p.name + extra
        }.toTypedArray()
        ui().setTitle("🧩 Vorlage: Steuerung verteilen ($have ${if (have == 1) "Handy" else "Handys"})")
            .setItems(labels) { _, w ->
                val i = order[w]
                Link.applyPreset(i)
                toast("Vorlage: " + Link.PRESETS[i].name)
            }
            .setNegativeButton("Zurück", null)
            .show()
    }

    private fun showRemoteConnect() {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), px(8f), px(18f), px(4f)) }
        val status = TextView(this).apply { textSize = 13f; setTextColor(0xFFCCCCCC.toInt()); text = "Suche Marv-PAD-Hosts im WLAN …\n(Auf dem anderen Handy muss der Host laufen: Menü → 📡 → „macht den Klang“)" }
        box.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        var dlg: AlertDialog? = null
        var scanning = false
        fun connecting(name: String) { status.text = "Verbinde mit $name …"; list.isEnabled = false }
        val done: (String?) -> Unit = { err ->
            runOnUiThread {
                if (err == null) { dlg?.dismiss(); Link.stopDiscover(this); enterRemote() }
                else status.text = "❌ $err\nHost gestartet? Gleiches WLAN? Nochmal versuchen."
            }
        }
        fun build() {
            list.removeAllViews()
            for ((name, ip) in Link.found) list.addView(Button(this).apply {
                isAllCaps = false; text = "📶 $name\n$ip"
                setOnClickListener { connecting(name); Link.connectTcp(this@MainActivity, ip, Link.PORT, done) }
            })
            if (btAllowed()) for (dev in Link.bondedDevices(this)) list.addView(Button(this).apply {
                isAllCaps = false
                @Suppress("MissingPermission")
                val nm = try { dev.name } catch (e: SecurityException) { null } ?: dev.address
                text = "🔵 $nm (Bluetooth)"
                setOnClickListener { connecting(nm); Link.connectBt(this@MainActivity, dev, done) }
            })
            list.addView(Button(this).apply {
                isAllCaps = false; text = if (scanning) "🔍 Suche im ganzen WLAN …" else "🔍 Nichts gefunden? Gründlich im WLAN suchen"
                isEnabled = !scanning
                setOnClickListener {
                    scanning = true
                    build()
                    Link.scanSubnet({ runOnUiThread { build() } }) { f ->
                        runOnUiThread {
                            status.text = if (f >= 1f) (if (Link.found.isEmpty()) "Kein Host gefunden. Läuft er auf dem anderen Handy? Gäste-WLANs blockieren Geräte untereinander – dann den Hotspot eines Handys nutzen." else "Gefunden – antippen zum Verbinden:")
                                else "Suche im WLAN … ${(f * 100).toInt()} %"
                            if (f >= 1f) { scanning = false; build() }
                        }
                    }
                }
            })
            list.addView(Button(this).apply {
                isAllCaps = false; text = "⌨ Adresse eingeben (steht am Host)"
                setOnClickListener {
                    val ed = EditText(this@MainActivity).apply { hint = "z. B. 192.168.43.1"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
                    ui().setTitle("Adresse des Hosts").setView(ed)
                        .setPositiveButton("Verbinden") { _, _ ->
                            val ip = ed.text.toString().trim()
                            if (ip.isNotEmpty()) { connecting(ip); Link.connectTcp(this@MainActivity, ip, Link.PORT, done) }
                        }.setNegativeButton("Abbrechen", null).show()
                }
            })
            if (Link.found.isNotEmpty() && !scanning) status.text = "Gefunden – antippen zum Verbinden:"
        }
        build()
        Link.discover(this) { runOnUiThread { build() } }
        dlg = ui().setTitle("📱 Als Bedienfeld verbinden")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("Abbrechen") { _, _ -> Link.stopDiscover(this) }
            .create()
        dlg.show()
    }

    /** Dieses Handy wird Bedienfeld: eigener Klang aus, Oberfläche vom Host. */
    private fun enterRemote() {
        Dj.decks.forEach { if (it.playing) it.playing = false }
        Engine.stop()
        pads.closeFocus()
        val rv = RemoteView(this)
        rv.onLeave = { leaveRemote() }
        remoteView = rv
        Link.onClientEvent = { ev ->
            runOnUiThread {
                if (ev == "lost" && remoteView != null) toast("📡 Verbindung zum Host getrennt")
            }
        }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        setContentView(rv)
        hideSystemBars()
        rv.requestApplyInsets()
        toast("📡 Verbunden – oben die Oberfläche wählen, ✕ beendet")
    }

    private fun leaveRemote() {
        Link.onClientEvent = null
        Link.disconnect()
        remoteView = null
        applyDjOrientation()
        val v = mainView()
        setContentView(v)
        hideSystemBars()
        v.requestApplyInsets()
        ensureStarted()
    }

    // ================================================================ DJ-Sammlung (Crates, Verlauf, Analyse, Vorschläge)

    private var colSort = 0
    private var colCrate = ""
    private var colSuggest = false
    @Volatile private var colBatch = false
    private var colRefresh: (() -> Unit)? = null
    private val SORTS = arrayOf("BPM", "Tonart", "Energie", "Titel", "Neu", "Zuletzt gespielt", "Oft gespielt")
    private val CRATE_ALL = ""
    private val CRATE_HISTORY = "\u0001Verlauf"
    private val CRATE_OPEN = "\u0002Nicht analysiert"
    /** Smart Crates: Kennung beginnt mit diesem Zeichen (z. B. SMART + "peak", "bpm:120", "genre:House", "own:Name"). */
    private val SMART = "\u0003"

    private fun smartTracks(code: String, all: List<TrackDb.Track>): List<TrackDb.Track> {
        val c = code.removePrefix(SMART)
        val now = System.currentTimeMillis()
        return when {
            c == "peak" -> all.filter { it.energy >= 8 }
            c == "warm" -> all.filter { it.energy in 1..4 }
            c == "mid" -> all.filter { it.energy in 5..7 }
            c == "new" -> all.filter { now - it.added < 30 * 86_400_000L }
            c == "never" -> all.filter { it.plays == 0 }
            c == "hot" -> all.filter { it.plays >= 3 }.sortedByDescending { it.plays }
            c == "fresh" -> all.filter { it.lastPlayed > 0 && now - it.lastPlayed > 30 * 86_400_000L }
            c.startsWith("bpm:") -> { val b = c.substring(4).toIntOrNull() ?: 0; all.filter { it.bpm >= b && it.bpm < b + 10 } }
            c.startsWith("key:") -> { val k = c.substring(4); all.filter { it.key >= 0 && Keys.camelot(it.key) == k } }
            c.startsWith("genre:") -> { val g = c.substring(6); all.filter { it.genre.equals(g, true) } }
            c.startsWith("own:") -> { val sm = TrackDb.smartCrates().firstOrNull { it.name == c.substring(4) }; if (sm == null) emptyList() else all.filter { sm.matches(it) } }
            else -> all
        }
    }

    private fun smartLabel(code: String): String {
        val c = code.removePrefix(SMART)
        return when {
            c == "peak" -> "Peak Time"
            c == "warm" -> "Warm-up"
            c == "mid" -> "Mittlere Energie"
            c == "new" -> "Neu"
            c == "never" -> "Nie gespielt"
            c == "hot" -> "Oft gespielt"
            c == "fresh" -> "Lange nicht gespielt"
            c.startsWith("bpm:") -> c.substring(4) + "er BPM"
            c.startsWith("key:") -> "Tonart " + c.substring(4)
            c.startsWith("genre:") -> c.substring(6)
            c.startsWith("own:") -> c.substring(4)
            else -> c
        }
    }

    /** Smart Crates: automatische (Energie, BPM, Tonart, Genre, Spielhäufigkeit) und eigene mit Regeln. */
    private fun showSmartCrates(pick: (String) -> Unit) {
        val all = TrackDb.all().filter { !it.missing }
        val codes = ArrayList<String>()
        val labels = ArrayList<String>()
        fun add(code: String, label: String) {
            val n = smartTracks(SMART + code, all).size
            if (n == 0 && !code.startsWith("own:")) return
            codes.add(SMART + code); labels.add("$label  ($n)")
        }
        for (sm in TrackDb.smartCrates()) add("own:" + sm.name, "⚙ ${sm.name} – ${sm.describe()}")
        add("peak", "⚡ Peak Time – Energie 8–10")
        add("mid", "〰 Mittlere Energie 5–7")
        add("warm", "🌅 Warm-up – Energie 1–4")
        add("new", "🆕 Neu (30 Tage)")
        add("never", "💤 Nie gespielt")
        add("hot", "🔥 Oft gespielt (3× und mehr)")
        add("fresh", "🕸 Lange nicht gespielt (30 Tage)")
        val bpms = all.filter { it.bpm > 0f }.map { (it.bpm / 10f).toInt() * 10 }.distinct().sorted()
        for (b in bpms) add("bpm:$b", "🥁 $b–${b + 9} BPM")
        val genres = all.map { it.genre.trim() }.filter { it.isNotEmpty() }.groupBy { it.lowercase() }.map { it.value.first() }.sortedBy { it.lowercase() }
        for (g in genres) add("genre:$g", "🏷 $g")
        val keys = all.filter { it.key >= 0 }.map { it.key }.distinct().sortedWith(compareBy({ Keys.camelotNum(it) }, { if (Keys.isMinor(it)) 0 else 1 }))
        for (k in keys) add("key:" + Keys.camelot(k), "🎹 ${Keys.camelot(k)} · ${Keys.name(k)}")
        labels.add("＋ Eigene Smart Crate …")
        if (TrackDb.smartCrates().isNotEmpty()) labels.add("🗑 Eigene Smart Crate löschen …")
        ui().setTitle("🧠 Smart Crates").setItems(labels.toTypedArray()) { _, w ->
            when {
                w < codes.size -> pick(codes[w])
                w == codes.size -> editSmartCrate { name -> pick(SMART + "own:" + name) }
                else -> {
                    val sms = TrackDb.smartCrates()
                    ui().setTitle("Löschen").setItems(sms.map { it.name }.toTypedArray()) { _, i -> TrackDb.deleteSmart(sms[i].name); TrackDb.save(this); toast("„${sms[i].name}“ gelöscht") }.show()
                }
            }
        }.setNegativeButton("Abbrechen", null).show()
    }

    /** Eigene Smart Crate anlegen: BPM, Energie, Tonarten (Camelot), Genre, gespielt/nie gespielt, neu. */
    private fun editSmartCrate(done: (String) -> Unit) {
        val dens = resources.displayMetrics.density
        fun px(v: Float) = (v * dens).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), px(6f), px(18f), 0) }
        fun field(h: String, num: Boolean): EditText = EditText(this).apply {
            hint = h; textSize = 14f; isSingleLine = true
            if (num) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        fun row(a: View, b: View) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(a, LinearLayout.LayoutParams(0, -2, 1f)); addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        val name = field("Name (z. B. „Tech House 124–128“)", false)
        val bMin = field("BPM von", true); val bMax = field("BPM bis", true)
        val eMin = field("Energie von (1–10)", true); val eMax = field("Energie bis", true)
        val keys = field("Tonarten, z. B. 8A 9A 8B (leer = alle)", false)
        val genre = field("Genre enthält (leer = alle)", false)
        val days = field("nur neu: letzte … Tage (leer = egal)", true)
        val plays = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val pLabels = arrayOf("egal", "nie gespielt", "schon gespielt")
        for (i in pLabels.indices) plays.addView(android.widget.RadioButton(this).apply { text = pLabels[i]; id = 7100 + i; textSize = 12f })
        plays.check(7100)
        box.addView(name)
        row(bMin, bMax)
        row(eMin, eMax)
        box.addView(keys)
        box.addView(genre)
        box.addView(days)
        box.addView(plays)
        ui().setTitle("＋ Smart Crate").setView(box).setPositiveButton("Anlegen") { _, _ ->
            val nm = name.text.toString().trim().ifEmpty { "Smart ${TrackDb.smartCrates().size + 1}" }
            val ks = keys.text.toString().uppercase().split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.matches(Regex("^(1[0-2]|[1-9])[AB]$")) }.toSet()
            val sm = TrackDb.Smart(nm,
                bMin.text.toString().replace(',', '.').toFloatOrNull() ?: 0f, bMax.text.toString().replace(',', '.').toFloatOrNull() ?: 0f,
                eMin.text.toString().toIntOrNull()?.coerceIn(0, 10) ?: 0, eMax.text.toString().toIntOrNull()?.coerceIn(0, 10) ?: 0,
                ks, genre.text.toString().trim(), plays.checkedRadioButtonId - 7100, days.text.toString().toIntOrNull() ?: 0)
            TrackDb.addSmart(sm)
            TrackDb.save(this)
            toast("Smart Crate „$nm“: ${sm.describe()}")
            done(nm)
        }.setNegativeButton("Abbrechen", null).show()
    }

    /** Basis für Vorschläge und Ampel: das andere (laufende) Deck, sonst dieses. */
    private fun colRef(k: Int): Dj.Deck? {
        val o = Dj.decks[1 - k]
        if (o.loaded()) return o
        val d = Dj.decks[k]
        return if (d.loaded()) d else null
    }

    private fun showCollection(k: Int) {
        TrackDb.load(this)
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        colSort = prefs.getInt("colSort", 0)
        val dens = resources.displayMetrics.density
        fun px(v: Float) = (v * dens).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10f), px(4f), px(10f), 0) }
        val bar1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll1 = android.widget.HorizontalScrollView(this).apply { addView(bar1); isHorizontalScrollBarEnabled = false }
        fun chip(t: String, on: (Button) -> Unit): Button = Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 12f
            minHeight = 0; minimumHeight = 0
            setPadding(px(10f), px(4f), px(10f), px(4f))
            setOnClickListener { on(this) }
            bar1.addView(this, LinearLayout.LayoutParams(-2, px(38f)).apply { rightMargin = px(4f) })
        }
        val search = EditText(this).apply { hint = "🔎 Suchen (Titel)"; textSize = 14f; isSingleLine = true }
        val info = TextView(this).apply { textSize = 11f; setTextColor(Color.argb(190, 200, 200, 200)); setPadding(px(4f), px(2f), 0, px(2f)) }
        val list = android.widget.ListView(this)
        root.addView(scroll1)
        root.addView(search)
        root.addView(info)
        root.addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt()))
        var rows: List<Pair<TrackDb.Track, Double?>> = emptyList()
        var dlg: AlertDialog? = null
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: android.view.ViewGroup): View {
                val tv = (cv as? TextView) ?: TextView(this@MainActivity).apply { setPadding(px(6f), px(7f), px(6f), px(7f)); textSize = 14f }
                val (t, sc) = rows[p]
                val ref = colRef(k)
                val rk = ref?.effKey() ?: -1
                val sb = android.text.SpannableStringBuilder()
                val t0 = sb.length
                sb.append(if (t.missing) "⚠ " else "").append(t.title)
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), t0, sb.length, 0)
                sb.append("\n")
                val meta = sb.length
                if (t.analyzed || t.bpm > 0f) sb.append(String.format(Locale.GERMANY, "%.1f BPM", t.bpm)) else sb.append("– BPM")
                sb.append("  ")
                val ks = sb.length
                sb.append(if (t.key >= 0) Keys.camelot(t.key) + " " + Keys.name(t.key) else "?")
                val kc = if (ref != null) Keys.compat(rk, t.key) else -1
                val col = when (kc) { 0, 1 -> Color.rgb(70, 220, 110); 2 -> Color.rgb(250, 190, 40); 3 -> Color.rgb(255, 90, 90); else -> Color.rgb(170, 170, 170) }
                sb.setSpan(android.text.style.ForegroundColorSpan(col), ks, sb.length, 0)
                if (t.energy > 0) sb.append("  ⚡").append(t.energy.toString())
                if (t.seconds > 0) sb.append("  ").append(Dj.fmt(t.seconds))
                if (t.plays > 0) sb.append("  ▶").append(t.plays.toString())
                if (ref != null && t.bpm > 0f && ref.bpm > 0f) {
                    val d = TrackDb.bpmDiff(ref.effBpm(), t.bpm)
                    if (d < 20) sb.append(String.format(Locale.GERMANY, "  Δ%.1f %%", d))
                }
                if (sc != null) sb.append("  ✨")
                if (t.crates.isNotEmpty()) sb.append("  📁").append(t.crates.joinToString(","))
                sb.setSpan(android.text.style.RelativeSizeSpan(0.82f), meta, sb.length, 0)
                tv.text = sb
                return tv
            }
        }
        list.adapter = adapter
        lateinit var sortBtn: Button
        lateinit var crateBtn: Button
        lateinit var sugBtn: Button
        lateinit var anaBtn: Button
        fun refresh() {
            val q = search.text.toString().trim().lowercase()
            val all = TrackDb.all()
            val ref = colRef(k)
            val base: List<TrackDb.Track> = when (colCrate) {
                CRATE_ALL -> all
                CRATE_HISTORY -> TrackDb.history().reversed().mapNotNull { h -> TrackDb.byId(h.id) }.distinct()
                CRATE_OPEN -> all.filter { !it.analyzed }
                else -> if (colCrate.startsWith(SMART)) smartTracks(colCrate, all) else all.filter { colCrate in it.crates }
            }.filter { q.isEmpty() || it.title.lowercase().contains(q) }
            rows = if (colSuggest && ref != null) {
                val en = TrackDb.byId(ref.colId)?.energy ?: 0
                base.mapNotNull { t -> if (t.id == ref.colId) null else TrackDb.score(t, ref.effBpm(), ref.effKey(), en)?.let { t to it } }.sortedBy { it.second }.map { it.first to it.second }
            } else {
                val l = when (colSort) {
                    0 -> base.sortedWith(compareBy({ it.bpm <= 0f }, { it.bpm }))
                    1 -> base.sortedWith(compareBy({ it.key < 0 }, { Keys.camelotNum(it.key) }, { if (Keys.isMinor(it.key)) 0 else 1 }, { it.bpm }))
                    2 -> base.sortedWith(compareBy({ it.energy <= 0 }, { -it.energy }, { it.bpm }))
                    3 -> base.sortedBy { it.title.lowercase() }
                    4 -> base.sortedByDescending { it.added }
                    5 -> base.sortedByDescending { it.lastPlayed }
                    else -> base.sortedByDescending { it.plays }
                }
                if (colCrate == CRATE_HISTORY) base.map { it to null } else l.map { it to null }
            }
            adapter.notifyDataSetChanged()
            val open = all.count { !it.analyzed }
            sortBtn.text = "⇅ ${SORTS[colSort]}"
            crateBtn.text = when (colCrate) { CRATE_ALL -> "📁 Alle (${all.size})"; CRATE_HISTORY -> "📁 Verlauf"; CRATE_OPEN -> "📁 Nicht analysiert"; else -> if (colCrate.startsWith(SMART)) "🧠 " + smartLabel(colCrate) else "📁 $colCrate" }
            sugBtn.text = if (colSuggest) "✨ Vorschläge AN" else "✨ Vorschläge"
            anaBtn.text = if (colBatch) "■ Analyse stoppen" else "⟳ Analysieren ($open)"
            info.text = when {
                all.isEmpty() -> "Noch leer – „＋ Ordner“ fügt einen ganzen Musik-Ordner hinzu (Tempo, Tonart, Lautheit und Energie werden einmal analysiert und gespeichert)."
                colSuggest && ref == null -> "Vorschläge: erst einen Track in ein Deck laden"
                colSuggest -> String.format(Locale.GERMANY, "✨ %d passende Tracks zu Deck %s (%.1f BPM · %s): Tempo ±6 %%, harmonische Tonart, ähnliche Energie", rows.size, "AB"[ref!!.idx], ref.effBpm(), Keys.camelot(ref.effKey()))
                else -> "${rows.size} Tracks" + (if (open > 0) " · $open noch nicht analysiert" else "") + if (ref != null) " · Farbe der Tonart = passt zu Deck ${"AB"[ref.idx]}" else ""
            }
        }
        colRefresh = { runOnUiThread { refresh() } }
        chip("＋ Ordner") { pickCollectionFolder() }
        chip("＋ Dateien") { pickCollectionFiles() }
        chip("☁ Cloud / Link") { showCloudImport(CLOUD_COLLECTION) }
        anaBtn = chip("⟳ Analysieren") { if (colBatch) colBatch = false else startBatchAnalysis(); refresh() }
        crateBtn = chip("📁 Alle") {
            val cr = TrackDb.crates()
            val labels = listOf("Alle Tracks", "🕘 Verlauf", "⟳ Nicht analysiert", "🧠 Smart Crates (sortieren sich selbst) …") + cr.map { "📁 $it" } + listOf("＋ Neue Crate …", "✎ Crates verwalten …")
            ui().setTitle("Crate").setItems(labels.toTypedArray()) { _, w ->
                when {
                    w == 0 -> colCrate = CRATE_ALL
                    w == 1 -> colCrate = CRATE_HISTORY
                    w == 2 -> colCrate = CRATE_OPEN
                    w == 3 -> { showSmartCrates { code -> colCrate = code; colSuggest = false; refresh() }; return@setItems }
                    w < 4 + cr.size -> colCrate = cr[w - 4]
                    w == 4 + cr.size -> askName("", "Name der Crate (z. B. „Warm-up“, „Peak Time“)") { n -> TrackDb.addCrate(n.trim()); TrackDb.save(this); colCrate = n.trim(); refresh() }
                    else -> manageCrates { refresh() }
                }
                refresh()
            }.show()
        }
        sortBtn = chip("⇅ BPM") {
            ui().setTitle("Sortieren nach").setItems(SORTS) { _, w -> colSort = w; colSuggest = false; prefs.edit().putInt("colSort", w).apply(); refresh() }.show()
        }
        sugBtn = chip("✨ Vorschläge") { colSuggest = !colSuggest; refresh() }
        chip("📤 Tracklist") { shareTracklist() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { refresh() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val t = rows.getOrNull(pos)?.first ?: return@setOnItemClickListener
            dlg?.dismiss()
            loadDeckCollection(k, t)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val t = rows.getOrNull(pos)?.first ?: return@setOnItemLongClickListener true
            trackActions(t, k) { refresh() }
            true
        }
        refresh()
        // Genres aus den Tags nachlesen (für Smart Crates) – schnell, im Hintergrund
        Thread {
            var n = 0
            for (t in TrackDb.all()) if (!t.genreRead && !t.missing) { TrackDb.readGenre(this, t); n++ }
            if (n > 0) { TrackDb.save(this); colRefresh?.invoke() }
        }.start()
        dlg = ui().setTitle("💿 Sammlung → Deck ${"AB"[k]}").setView(root).setNegativeButton("Schließen", null).create()
        dlg.setOnDismissListener { colRefresh = null }
        dlg.show()
    }

    private fun trackActions(t: TrackDb.Track, k: Int, done: () -> Unit) {
        val items = arrayOf("▶ In Deck A laden", "▶ In Deck B laden", "📁 Crates zuordnen …", "⟳ Neu analysieren", "✎ Titel ändern", "🗑 Aus der Sammlung entfernen")
        ui().setTitle(t.title).setItems(items) { _, w ->
            when (w) {
                0 -> loadDeckCollection(0, t)
                1 -> loadDeckCollection(1, t)
                2 -> {
                    val cr = TrackDb.crates()
                    if (cr.isEmpty()) { askName("", "Erste Crate anlegen") { n -> TrackDb.addCrate(n.trim()); t.crates.add(n.trim()); TrackDb.save(this); done() }; return@setItems }
                    val checked = BooleanArray(cr.size) { cr[it] in t.crates }
                    ui().setTitle("Crates für „${t.title}“").setMultiChoiceItems(cr.toTypedArray(), checked) { _, i, on -> if (on) t.crates.add(cr[i]) else t.crates.remove(cr[i]) }
                        .setPositiveButton("OK") { _, _ -> TrackDb.save(this); done() }
                        .setNeutralButton("＋ Neue") { _, _ -> askName("", "Name der Crate") { n -> TrackDb.addCrate(n.trim()); t.crates.add(n.trim()); TrackDb.save(this); done() } }
                        .show()
                }
                3 -> {
                    hint("Analysiere „${t.title}“ …")
                    Thread {
                        try { TrackDb.analyze(this, t); TrackDb.save(this); runOnUiThread { hint(String.format(Locale.GERMANY, "%s: %.1f BPM · %s · Energie %d", t.title, t.bpm, Keys.camelot(t.key), t.energy)); done() } }
                        catch (e: Throwable) { t.missing = true; runOnUiThread { toast("Analyse fehlgeschlagen: ${e.message ?: e}") } }
                    }.start()
                }
                4 -> askName(t.title, "Titel") { n -> t.title = n.trim().ifEmpty { t.title }; TrackDb.save(this); done() }
                else -> confirm("Aus der Sammlung entfernen?", "„${t.title}“ – die Datei selbst bleibt erhalten.") { TrackDb.remove(t); TrackDb.save(this); done() }
            }
        }.show()
    }

    private fun manageCrates(done: () -> Unit) {
        val cr = TrackDb.crates()
        if (cr.isEmpty()) { toast("Noch keine Crates"); return }
        ui().setTitle("Crates verwalten").setItems(cr.map { n -> "📁 $n (${TrackDb.all().count { n in it.crates }})" }.toTypedArray()) { _, i ->
            val n = cr[i]
            ui().setTitle(n).setItems(arrayOf("✎ Umbenennen", "🗑 Löschen (Tracks bleiben in der Sammlung)")) { _, w ->
                if (w == 0) askName(n, "Neuer Name") { nn -> TrackDb.renameCrate(n, nn.trim()); if (colCrate == n) colCrate = nn.trim(); TrackDb.save(this); done() }
                else confirm("Crate löschen?", n) { TrackDb.deleteCrate(n); if (colCrate == n) colCrate = CRATE_ALL; TrackDb.save(this); done() }
            }.show()
        }.show()
    }

    private fun shareTracklist() {
        val h = TrackDb.history()
        if (h.isEmpty()) { toast("Verlauf ist leer"); return }
        val df = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)
        val txt = StringBuilder("Tracklist – Marv PAD\n\n")
        var day = ""
        for ((i, p) in h.withIndex()) {
            val d = df.format(Date(p.at))
            if (d.substring(0, 6) != day) { day = d.substring(0, 6); txt.append("\n").append(day).append("\n") }
            txt.append(String.format(Locale.GERMANY, "%2d. %s  %s  (%.0f BPM · %s)\n", i + 1, d.substring(7), p.title, p.bpm, Keys.camelot(p.key)))
        }
        val opts = arrayOf("📤 Teilen", "🗑 Verlauf leeren")
        ui().setTitle("Verlauf (${h.size} Tracks)").setItems(opts) { _, w ->
            if (w == 0) {
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt.toString()); putExtra(Intent.EXTRA_SUBJECT, "Tracklist") }
                try { startActivity(Intent.createChooser(i, "Tracklist teilen")) } catch (e: Exception) { toast("Teilen nicht möglich") }
            } else confirm("Verlauf leeren?", "Die Tracks bleiben in der Sammlung.") { TrackDb.clearHistory(); TrackDb.save(this) }
        }.show()
    }

    // ================================================================ Mikro-Werkzeuge

    private fun showMicTools() {
        val items = arrayOf(
            "🎤 Summen → Melodie: Melodie summen oder singen, landet als Noten in einer Synth-Spur",
            "🥁 Beatbox → Drums: „Bum-Tss-Ka“ ins Mikro, wird zum Drum-Pattern",
            (if (Engine.autoTuneOn) "🎚 Live-Auto-Tune: AN" else "🎚 Live-Auto-Tune fürs Mikro") + " …",
            "🎯 Folge dem Song: Tonart & Akkorde aus einem Pad oder Deck übernehmen",
            (if (Engine.voiceSynthOn) "🎹 Stimmen-Synth: AN" else "🎹 Stimmen-Synth: singen → ein Synth spielt live mit") + " …",
            (if (Engine.harmOn) "👥 Harmonizer: AN" else "👥 Harmonizer: Begleitstimmen zu deiner Stimme") + " …",
            "✋ Klang-Sammler: Geräusche aufnehmen → eigenes Drumkit auf den Pads",
            "🎯 Mikro-Timing einmessen (für Loops, Summen & Beatbox – jetzt: ${Engine.micOffsetMs.toInt()} ms)"
        )
        ui().setTitle("🎤 Mikro-Werkzeuge").setItems(items) { _, w ->
            when (w) {
                0 -> askMicCapture(0)
                1 -> askMicCapture(1)
                2 -> showAutoTune()
                3 -> showFollowSong()
                4 -> showVoiceSynth()
                5 -> showHarmonizer()
                6 -> showCollector()
                else -> calibrateMic()
            }
        }.setNegativeButton("Schließen", null).show()
    }

    /**
     * Mikro-Timing einmessen: zum Klick 2 Takte lang auf jeden Schlag klatschen oder „tak“ sagen.
     * Der mittlere Versatz (Handy-Mikro, Kopfhörer, Bluetooth …) wird gespeichert und bei Summen/Beatbox abgezogen.
     */
    private fun calibrateMic() {
        if (!hasMic()) { toast("Bitte erst das Mikrofon erlauben"); return }
        if (!Engine.seqPlaying) Engine.seqPlay()
        val metroWas = Engine.metronome
        Engine.metronome = true
        val pad = (18 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad / 2) }
        val sub = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(190, 190, 190)); gravity = Gravity.CENTER; setPadding(pad, 0, pad, pad / 2)
            text = "Klatsche oder sag „tak“ genau auf jeden Klick – 2 Takte lang (8 Schläge). So lernt die App, wie spät das Mikro bei dir ankommt." }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(sub) }
        var cancelled = false
        val dlg = ui().setTitle("🎯 Mikro-Timing einmessen").setView(box).setCancelable(false).setNegativeButton("Abbrechen") { _, _ -> cancelled = true }.create()
        dlg.show()
        val h = android.os.Handler(mainLooper)
        var startStep = Double.NaN
        var startFrame = -1L
        h.post(object : Runnable {
            override fun run() {
                if (cancelled) { Engine.metronome = metroWas; return }
                val now = Engine.micNow()
                val st = Engine.micStep(now, raw = true)
                if (st.isNaN()) { h.postDelayed(this, 40); return }
                if (startStep.isNaN()) { var s0 = Math.ceil(st / 16.0) * 16.0; if (s0 - st < 3.0) s0 += 16.0; startStep = s0; startFrame = now }
                val rel = st - startStep
                when {
                    rel < 0 -> tv.text = "Gleich … ${Math.ceil(-rel / 4.0).toInt()}"
                    rel < 32 -> tv.text = "👏 ${(rel / 4).toInt() + 1} / 8"
                    rel < 33.5 -> tv.text = "👏 …"
                    else -> {
                        Engine.metronome = metroWas
                        dlg.dismiss()
                        val audio = Engine.micCopy(startFrame, (now - startFrame).toInt()) ?: run { toast("Mikrofon-Audio nicht verfügbar"); return }
                        val hits = MicTools.beatbox(audio, Engine.sampleRate)
                        // Versatz je Schlag zum nächsten Viertel (4 Schritte), in ms
                        val offs = hits.map { Engine.micStep(startFrame + it.at, raw = true) - startStep }
                            .filter { !it.isNaN() && it > -2 && it < 34 }
                            .map { x -> val q = Math.round(x / 4.0) * 4.0; (x - q) * Engine.stepMs() }
                            .sorted()
                        if (offs.size < 4) { toast("Zu wenige Schläge erkannt (${offs.size}) – lauter klatschen und nochmal"); return }
                        val med = offs[offs.size / 2]
                        val spread = offs[(offs.size * 3) / 4] - offs[offs.size / 4]
                        ui().setTitle("🎯 Ergebnis").setMessage(String.format(Locale.GERMANY,
                            "%d Schläge erkannt.\nDein Mikro kommt %.0f ms %s an (Streuung %.0f ms).\n\nÜbernehmen? Mikro-Aufnahmen (Loops, Loop Station, Overdubs), Summen und Beatbox werden dann um diesen Wert korrigiert.",
                            offs.size, abs(med), if (med >= 0) "zu spät" else "zu früh", spread))
                            .setPositiveButton("Übernehmen") { _, _ ->
                                Engine.micOffsetMs = med.toFloat()
                                Engine.micCalibrated = true
                                getSharedPreferences("marvpad", MODE_PRIVATE).edit().putFloat("micOffset", med.toFloat()).apply()
                                hint(String.format(Locale.GERMANY, "🎯 Mikro-Timing: %+.0f ms gespeichert", med))
                            }.setNegativeButton("Verwerfen", null).show()
                        return
                    }
                }
                h.postDelayed(this, 40)
            }
        })
    }

    /** Takte und Ziel wählen, dann aufnehmen. [kind] 0 = Summen, 1 = Beatbox. */
    private fun askMicCapture(kind: Int) {
        if (!hasMic()) { toast("Bitte erst das Mikrofon erlauben"); return }
        if (!getSharedPreferences("marvpad", MODE_PRIVATE).contains("micOffset")) {
            ui().setTitle("🎯 Erst einmal einmessen?").setMessage("Damit Summen und Beatbox genau im Takt landen, misst die App einmal kurz, wie spät dein Mikro ankommt (8× zum Klick klatschen, 10 Sekunden). Danach geht es weiter.")
                .setPositiveButton("Einmessen") { _, _ -> calibrateMic() }
                .setNegativeButton("Später") { _, _ -> Engine.micCalibrated = true; getSharedPreferences("marvpad", MODE_PRIVATE).edit().putFloat("micOffset", Engine.micOffsetMs).apply(); askMicCapture(kind) }
                .show()
            return
        }
        val barsOpt = intArrayOf(1, 2, 4)
        val labels = barsOpt.map { "$it Takt" + (if (it > 1) "e" else "") }.toTypedArray()
        ui().setTitle(if (kind == 0) "🎤 Summen → Melodie: Länge" else "🥁 Beatbox → Drums: Länge").setItems(labels) { _, w ->
            val bars = barsOpt[w]
            if (kind == 0) {
                val names = Array(InstState.SYN_TRACKS) { "SYNTH ${it + 1} · ${InstState.synSoundNames.getOrElse(it) { "" }}" + if (InstState.synTracks[it].hasNotes()) " (wird ersetzt)" else "" }
                ui().setTitle("In welche Spur?").setItems(names) { _, t -> micCapture(0, bars, t) }.show()
            } else micCapture(1, bars, 0)
        }.setNegativeButton("Abbrechen", null).show()
    }

    /** Aufnahme im Takt: startet auf der nächsten Eins, dauert [bars] Takte, danach Erkennung. */
    private fun micCapture(kind: Int, bars: Int, track: Int) {
        if (!Engine.seqPlaying) Engine.seqPlay()
        val metroWas = Engine.metronome
        Engine.metronome = true
        val pad = (18 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad) }
        val sub = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(190, 190, 190)); gravity = Gravity.CENTER; setPadding(pad, 0, pad, pad / 2)
            text = if (kind == 0) "Summe oder singe die Melodie im Takt (gern „da-da-da“ – klare Töne werden am besten erkannt)." else "Beatbox im Takt: „Bum“ = Kick, „Ka“/„Psch“ = Snare, „Ts“ = Hi-Hat."
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(sub) }
        var cancelled = false
        val dlg = ui().setTitle(if (kind == 0) "🎤 Summen → Melodie" else "🥁 Beatbox → Drums").setView(box).setCancelable(false)
            .setNegativeButton("Abbrechen") { _, _ -> cancelled = true }.create()
        dlg.show()
        val h = android.os.Handler(mainLooper)
        var startStep = Double.NaN
        var startFrame = -1L
        val total = bars * InstState.STEPS
        h.post(object : Runnable {
            override fun run() {
                if (cancelled) { Engine.metronome = metroWas; return }
                val now = Engine.micNow()
                val st = Engine.micStep(now)
                if (st.isNaN()) { tv.text = "Warte auf den Takt …"; h.postDelayed(this, 40); return }
                if (startStep.isNaN()) {
                    var s0 = Math.ceil(st / 16.0) * 16.0
                    if (s0 - st < 3.0) s0 += 16.0
                    startStep = s0
                    startFrame = now
                }
                val rel = st - startStep
                when {
                    rel < 0 -> tv.text = "Gleich … ${(-rel / 4.0).let { Math.ceil(it).toInt() }}"
                    rel < total -> tv.text = "● Takt ${(rel / 16).toInt() + 1} / $bars   ·   ${(rel % 16 / 4).toInt() + 1}"
                    rel < total + 1.5 -> tv.text = "● …"
                    else -> {
                        Engine.metronome = metroWas
                        tv.text = "Erkenne …"
                        val n = (now - startFrame).toInt()
                        val audio = Engine.micCopy(startFrame, n)
                        if (audio == null) { dlg.dismiss(); toast("Mikrofon-Audio nicht verfügbar"); return }
                        Thread {
                            val msg = try {
                                if (kind == 0) applyHum(audio, startFrame, startStep, bars, track) else applyBeatbox(audio, startFrame, startStep, bars)
                            } catch (e: Throwable) { "Fehler: ${e.message ?: e}" }
                            runOnUiThread { dlg.dismiss(); hint(msg); pads.invalidate() }
                        }.start()
                        return
                    }
                }
                h.postDelayed(this, 40)
            }
        })
    }

    /** Gesummtes in Noten der Synth-Spur [track]. */
    private fun applyHum(audio: FloatArray, f0: Long, startStep: Double, bars: Int, track: Int): String {
        val sr = Engine.sampleRate
        // halbe Rate reicht für Gesang (schneller)
        val half = FloatArray(audio.size / 2) { (audio[2 * it] + audio[2 * it + 1]) * 0.5f }
        val st = InstState
        val notes = MicTools.hum(half, sr / 2)
        if (notes.isEmpty()) return "Keine Töne erkannt – lauter und deutlicher summen"
        val t = st.synTracks[track]
        // Oktave automatisch in die Lage der Spur bringen
        val med = notes.map { it.midi }.sorted()[notes.size / 2]
        val octShift = Math.round((62 + 12 * t.oct - med) / 12.0).toInt() * 12
        Engine.pushPatternUndo("Summen → Melodie")
        t.clear()
        val len = (bars * InstState.STEPS).coerceIn(16, InstState.MAX)
        t.len = len
        var placed = 0
        // Notenanfänge liegen meist auf Achteln: einheitlichen Versatz ausgleichen
        val corr = MicTools.gridOffset(notes.map { Engine.micStep(f0 + it.start.toLong() * 2) - startStep }.filter { !it.isNaN() }, 2.0, 0.6)
        for (nt in notes) {
            val a = Engine.micStep(f0 + nt.start.toLong() * 2) - startStep - corr
            val b = Engine.micStep(f0 + nt.end.toLong() * 2) - startStep - corr
            val s0 = Math.floor(a + 0.5).toInt()
            if (s0 < 0 || s0 >= len) continue
            val l = max(1, Math.floor(b + 0.5).toInt() - s0)
            var semi = nt.midi + octShift - 60 - 12 * t.oct
            if (st.scaleType != 0 && !st.inScale(semi)) {
                for (d in 1..6) { if (st.inScale(semi - d)) { semi -= d; break }; if (st.inScale(semi + d)) { semi += d; break } }
            }
            for (k in s0 until min(len, s0 + l)) { t.gate[k] = true; t.tie[k] = k != s0; t.semi[k] = semi; t.vel[k] = nt.vel; t.chd[k] = 1 }
            placed++
        }
        t.on = true
        st.synCur = track
        return if (placed == 0) "Töne erkannt, aber außerhalb des Takts – nochmal versuchen" else "🎤 $placed Noten in SYNTH ${track + 1}" + (if (octShift != 0) " (Oktave angepasst)" else "") + " · ↶ UNDO macht es rückgängig"
    }

    /** Beatbox in die Drum-Machine (BD, SD, CH). */
    private fun applyBeatbox(audio: FloatArray, f0: Long, startStep: Double, bars: Int): String {
        val hits = MicTools.beatbox(audio, Engine.sampleRate)
        if (hits.isEmpty()) return "Keine Schläge erkannt – näher ans Mikro"
        val st = InstState
        val len = (bars * InstState.STEPS).coerceIn(16, InstState.MAX)
        Engine.pushPatternUndo("Beatbox → Drums")
        val rows = intArrayOf(0, 1, 7)
        for (r in rows) java.util.Arrays.fill(st.drumSteps[r], 0)
        st.drumLen = len
        val cnt = IntArray(3)
        // Rest-Versatz automatisch ausgleichen: liegen die Schläge einheitlich neben dem 1/16-Raster, wird das abgezogen
        val pos = hits.map { Engine.micStep(f0 + it.at) - startStep }.filter { !it.isNaN() }
        val corr = MicTools.gridOffset(pos)
        for (h in hits) {
            val s = Math.floor(Engine.micStep(f0 + h.at) - startStep - corr + 0.5).toInt()
            if (s < 0 || s >= len) continue
            val r = rows[h.kind]
            st.drumSteps[r][s] = if (h.vel > 0.85f) 2 else 1
            st.drumVel[r][s] = h.vel
            cnt[h.kind]++
        }
        // kürzeres Pattern über alle 4 Takte wiederholen
        for (r in rows) for (k in len until InstState.MAX) { st.drumSteps[r][k] = st.drumSteps[r][k % len]; st.drumVel[r][k] = st.drumVel[r][k % len] }
        st.drumOn = true
        val cTxt = if (abs(corr) > 0.05) String.format(Locale.GERMANY, " · Timing %+.0f ms ausgeglichen", -corr * Engine.stepMs()) else ""
        return "🥁 Kick ${cnt[0]} · Snare ${cnt[1]} · Hi-Hat ${cnt[2]}$cTxt · ↶ UNDO macht es rückgängig"
    }

    private fun showAutoTune() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val (box, scroll) = dialogBox()
        if (!headphonesOut()) note(box, "⚠ Ohne Kopfhörer pfeift es über den Lautsprecher (Rückkopplung).", Color.rgb(255, 150, 60))
        check(box, "🎚 Auto-Tune auf dem Live-Mikro", Engine.autoTuneOn) {
            Engine.autoTuneOn = it
            if (it && !Engine.monitorOn) { Engine.monitorOn = true; hint("Mikrofon live hören ist jetzt an") }
            prefs.edit().putBoolean("autoTune", it).apply()
        }
        val keyTxt = if (InstState.scaleType != 0) "Tonleiter: ${InstState.scaleLabel()}" else if (Engine.autoTuneKey >= 0) "Tonart: ${Keys.name(Engine.autoTuneKey)} (${Keys.camelot(Engine.autoTuneKey)})" else "Chromatisch (alle Halbtöne) – über „Folge dem Song“ oder 🎼 Tonleiter eine Tonart setzen"
        note(box, keyTxt, Color.WHITE)
        fun slider(label: String, v: Float, set: (Float) -> Unit) {
            val lab = note(box, "", Color.WHITE)
            fun show(x: Float) { lab.text = label + ": " + (x * 100).toInt() + " %" }
            show(v)
            box.addView(SeekBar(this).apply {
                max = 100; progress = (v * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) { if (fromUser) { set(pr / 100f); show(pr / 100f) } }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        prefs.edit().putFloat("atSpeed", Engine.autoTuneSpeed).putFloat("atMix", Engine.autoTuneMix).apply()
                    }
                })
            })
        }
        slider("Weichheit (0 = harter Roboter-Effekt, 100 = natürlich)", Engine.autoTuneSpeed) { Engine.autoTuneSpeed = it }
        slider("Stärke", Engine.autoTuneMix) { Engine.autoTuneMix = it }
        val live = note(box, "", Color.rgb(180, 220, 255))
        val hh = android.os.Handler(mainLooper)
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")
        fun nm(m: Double) = if (m.isNaN()) "–" else names[Math.floorMod(Math.round(m).toInt(), 12)] + (Math.round(m).toInt() / 12 - 1)
        val tick = object : Runnable {
            override fun run() {
                val a = Engine.autoTuneHeard(); val b = Engine.autoTuneTarget()
                live.text = if (!Engine.autoTuneOn) "" else if (a.isNaN()) "🎙 (singen …)" else String.format(Locale.GERMANY, "🎙 gesungen %s (%+.0f Cent) → %s", nm(a), (a - Math.round(a)) * 100, nm(b))
                if (live.isAttachedToWindow) hh.postDelayed(this, 80)
            }
        }
        note(box, "Mit Effekten aufnehmen: Pad-Eingang auf WAVE – dann landet die gestimmte Stimme im Pad.")
        ui().setTitle("🎚 Live-Auto-Tune").setView(scroll).setPositiveButton("Fertig", null).show()
        hh.postDelayed(tick, 200)
    }

    private fun showVoiceSynth() {
        if (!hasMic()) { toast("Bitte erst das Mikrofon erlauben"); return }
        val (box, scroll) = dialogBox()
        if (!headphonesOut()) note(box, "Tipp: mit Kopfhörern – sonst hört das Mikro den Synth und spielt sich selbst.", Color.rgb(255, 150, 60))
        check(box, "🎹 Stimmen-Synth an (singen oder summen → Synth spielt mit)", Engine.voiceSynthOn) { on -> if (on) Engine.voiceSynthOn = true else Engine.voiceSynthStop() }
        note(box, "Spur (Klang):", Color.WHITE)
        val rg = android.widget.RadioGroup(this)
        for (i in 0 until InstState.SYN_TRACKS) rg.addView(android.widget.RadioButton(this).apply { id = 7300 + i; text = "SYNTH ${i + 1} · ${InstState.synSoundNames.getOrElse(i) { "" }}"; setTextColor(Color.WHITE) })
        rg.check(7300 + Engine.voiceSynthTrack)
        rg.setOnCheckedChangeListener { _, id -> Engine.voiceSynthStop(); Engine.voiceSynthTrack = id - 7300; Engine.voiceSynthOn = true }
        box.addView(rg)
        note(box, "Oktave:", Color.WHITE)
        val og = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        for ((i, o) in intArrayOf(-2, -1, 0, 1, 2).withIndex()) og.addView(android.widget.RadioButton(this).apply { id = 7400 + i; text = if (o > 0) "+$o" else "$o"; setTextColor(Color.WHITE) })
        og.check(7400 + Engine.voiceSynthOct + 2)
        og.setOnCheckedChangeListener { _, id -> Engine.voiceSynthOct = id - 7402 }
        box.addView(og)
        check(box, "Nur den Synth hören (eigene Stimme stumm)", Engine.voiceSynthMute) { Engine.voiceSynthMute = it }
        note(box, "Töne rasten auf die Tonleiter bzw. die Tonart aus „Folge dem Song“ ein. Mit dem Pad-Eingang WAVE kannst du den Synth direkt auf ein Pad aufnehmen.")
        val live = note(box, "", Color.rgb(180, 220, 255))
        val hh = android.os.Handler(mainLooper)
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")
        val tick = object : Runnable {
            override fun run() {
                val nn = Engine.voiceSynthNote()
                live.text = if (!Engine.voiceSynthOn) "" else if (nn < 0) "🎙 (singen …)" else "🎹 spielt " + names[Math.floorMod(nn + 12 * Engine.voiceSynthOct, 12)] + ((nn + 12 * Engine.voiceSynthOct) / 12 - 1)
                if (live.isAttachedToWindow) hh.postDelayed(this, 80)
            }
        }
        ui().setTitle("🎹 Stimmen-Synth").setView(scroll).setPositiveButton("Fertig", null).show()
        hh.postDelayed(tick, 200)
    }

    private fun showHarmonizer() {
        if (!hasMic()) { toast("Bitte erst das Mikrofon erlauben"); return }
        val (box, scroll) = dialogBox()
        if (!headphonesOut()) note(box, "⚠ Ohne Kopfhörer pfeift es über den Lautsprecher (Rückkopplung).", Color.rgb(255, 150, 60))
        check(box, "👥 Harmonizer auf dem Live-Mikro", Engine.harmOn) {
            Engine.harmOn = it
            if (it && !Engine.monitorOn) { Engine.monitorOn = true; hint("Mikrofon live hören ist jetzt an") }
        }
        val opts = listOf("Terz drüber" to intArrayOf(2), "Terz + Quinte drüber (Chor)" to intArrayOf(2, 4), "Terz drunter + Terz drüber" to intArrayOf(-2, 2),
            "Sexte drunter + Terz drüber" to intArrayOf(-3, 2), "Oktave drunter + Quinte" to intArrayOf(-7, 4), "Dreistimmig: Terz, Quinte, Oktave" to intArrayOf(2, 4, 7))
        val rg = android.widget.RadioGroup(this)
        opts.forEachIndexed { i, o -> rg.addView(android.widget.RadioButton(this).apply { id = 7500 + i; text = o.first; setTextColor(Color.WHITE) }) }
        rg.check(7500 + (opts.indexOfFirst { it.second.contentEquals(Engine.harmVoices) }.takeIf { it >= 0 } ?: 1))
        rg.setOnCheckedChangeListener { _, id -> Engine.harmVoices = opts[id - 7500].second }
        box.addView(rg)
        val lab = note(box, "", Color.WHITE)
        fun show() { lab.text = "Lautstärke der Begleitstimmen: ${(Engine.harmLevel * 100).toInt()} %" }
        show()
        box.addView(SeekBar(this).apply {
            max = 100; progress = (Engine.harmLevel * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) { if (fromUser) { Engine.harmLevel = pr / 100f; show() } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        val keyTxt = if (InstState.scaleType != 0) "Leitereigen in ${InstState.scaleLabel()}" else if (Engine.autoTuneKey >= 0) "Leitereigen in ${Keys.name(Engine.autoTuneKey)}" else "Ohne Tonart: feste Intervalle (große Terz, Quinte) – besser vorher „Folge dem Song“ oder 🎼 Tonleiter"
        note(box, keyTxt)
        note(box, "Zusammen mit Auto-Tune klingt es am saubersten.")
        ui().setTitle("👥 Harmonizer").setView(scroll).setPositiveButton("Fertig", null).show()
    }

    /** Klang-Sammler: frei aufnehmen, Klänge schneiden und als One-Shot-Kit auf freie Pads legen. */
    private fun showCollector() {
        if (!hasMic()) { toast("Bitte erst das Mikrofon erlauben"); return }
        val pad = (18 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad / 2); text = "Bereit" }
        val sub = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(190, 190, 190)); gravity = Gravity.CENTER; setPadding(pad, 0, pad, pad / 2)
            text = "Klopfe, klatsche, schnipse, raschle – auf den Tisch, ein Glas, Schlüssel … mit kleinen Pausen dazwischen (max. 30 s). Tiefe Klänge werden Kick, kurze helle Hi-Hat, der Rest Snare und Percussion."
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(sub) }
        var start = -1L
        var running = true
        val h = android.os.Handler(mainLooper)
        val dlg = ui().setTitle("✋ Klang-Sammler").setView(box).setCancelable(false)
            .setPositiveButton("● Aufnahme", null).setNegativeButton("Abbrechen") { _, _ -> running = false }.create()
        dlg.setOnShowListener {
            val b = dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            b.setOnClickListener {
                if (start < 0) {
                    start = Engine.micNow()
                    b.text = "■ Fertig"
                    h.post(object : Runnable {
                        override fun run() {
                            if (!running || start < 0) return
                            val secs = (Engine.micNow() - start) / Engine.sampleRate.toFloat()
                            val lv = (Engine.inputLevel * 100).toInt().coerceAtMost(100)
                            tv.text = String.format(Locale.GERMANY, "● %.1f s   %s", secs, "▮".repeat(lv / 10))
                            if (secs >= 30f) b.performClick() else h.postDelayed(this, 60)
                        }
                    })
                } else {
                    running = false
                    val n = (Engine.micNow() - start).toInt()
                    val audio = Engine.micCopy(start, n)
                    dlg.dismiss()
                    if (audio == null || n < Engine.sampleRate / 2) { toast("Zu kurz"); return@setOnClickListener }
                    hint("✋ Schneide und sortiere …")
                    Thread {
                        val found = try { MicTools.collect(audio, Engine.sampleRate, 5) } catch (e: Throwable) { emptyList() }
                        runOnUiThread { placeCollected(found) }
                    }.start()
                }
            }
        }
        dlg.show()
    }

    private fun placeCollected(found: List<MicTools.Found>) {
        if (found.isEmpty()) { toast("Keine Klänge erkannt – lauter und mit Pausen dazwischen"); return }
        val free = (0 until PadView.VISIBLE_PADS).filter { !Engine.hasLoop(it) }
        if (free.isEmpty()) { toast("Keine freien Pads – erst Pads leeren"); return }
        val names = arrayOf("Kick", "Snare", "Hi-Hat", "Perc")
        val list = found.take(free.size)
        val desc = list.joinToString(", ") { names[it.kind] }
        ui().setTitle("✋ ${list.size} Klänge gefunden").setMessage("$desc\n\nAls ⚡ One-Shots auf die freien Pads ${list.indices.joinToString(", ") { (free[it] + 1).toString() }} legen?")
            .setPositiveButton("Auf Pads + Grundbeat") { _, _ -> putCollected(list, free, true) }
            .setNeutralButton("Nur auf Pads") { _, _ -> putCollected(list, free, false) }
            .setNegativeButton("Verwerfen", null).show()
    }

    private fun putCollected(list: List<MicTools.Found>, free: List<Int>, beat: Boolean) {
        val names = arrayOf("Kick", "Snare", "Hi-Hat", "Perc")
        var perc = 0
        if (beat) Engine.pushPatternUndo("Klang-Sammler Beat")
        for ((i, f) in list.withIndex()) {
            val q = free[i]
            Engine.padOneShot[q] = true
            Engine.importLoop(q, f.data)
            Engine.setPadName(q, "✋ " + names[f.kind] + if (f.kind == 3) " ${++perc}" else "")
            if (beat) {
                val steps = when (f.kind) {
                    0 -> intArrayOf(0, 4, 8, 12)
                    1 -> intArrayOf(4, 12)
                    2 -> intArrayOf(2, 6, 10, 14)
                    else -> if (perc == 1) intArrayOf(7, 15) else if (perc == 2) intArrayOf(3, 11) else intArrayOf()
                }
                for (st in 0 until Engine.STEPS) Engine.setSeqVel(q, st, 0f)
                for (st in steps) Engine.setSeqVel(q, st, if (f.kind == 2) 0.7f else 1f)
            }
        }
        if (beat && !Engine.seqPlaying) Engine.seqPlay()
        pads.invalidate()
        hint("✋ ${list.size} Klänge auf den Pads" + if (beat) " · Grundbeat läuft im Sequenzer" else "")
    }

    // ================================================================ Lebendige Patterns

    private val LIVE_NAMES = arrayOf("Aus", "Dezent", "Mittel", "Wild")
    private val LIVE_AMT = floatArrayOf(0f, 0.3f, 0.6f, 1f)
    private fun liveIdx(): Int = LIVE_AMT.indices.minByOrNull { abs(LIVE_AMT[it] - InstState.liveAmt) } ?: 0

    private fun showLivePatterns() {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val (box, scroll) = dialogBox()
        note(box, "Beim Abspielen verändern sich Drums, Bass und Synths leicht: Ghost-Notes auf der Hi-Hat, lebendige Lautstärke, ab und zu ein Nachbarton oder Oktavsprung. Deine Patterns selbst bleiben unverändert.")
        val rg = android.widget.RadioGroup(this)
        LIVE_NAMES.forEachIndexed { i, n -> rg.addView(android.widget.RadioButton(this).apply { id = 7600 + i; text = n; setTextColor(Color.WHITE) }) }
        rg.check(7600 + liveIdx())
        rg.setOnCheckedChangeListener { _, id -> InstState.liveAmt = LIVE_AMT[id - 7600]; prefs.edit().putFloat("liveAmt", InstState.liveAmt).apply() }
        box.addView(rg)
        check(box, "🥁 Fills vor jeder Phrase (Snare-Wirbel, Toms) und Crash auf der Eins danach", InstState.liveFills) {
            InstState.liveFills = it; prefs.edit().putBoolean("liveFills", it).apply()
        }
        note(box, "Phrase (alle wie viele Takte ein Fill):", Color.WHITE)
        val pg = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        for ((i, b) in intArrayOf(4, 8, 16).withIndex()) pg.addView(android.widget.RadioButton(this).apply { id = 7700 + i; text = "$b Takte"; setTextColor(Color.WHITE) })
        pg.check(7700 + intArrayOf(4, 8, 16).indexOf(InstState.livePhrase).coerceAtLeast(1))
        pg.setOnCheckedChangeListener { _, id -> InstState.livePhrase = intArrayOf(4, 8, 16)[id - 7700]; prefs.edit().putInt("livePhrase", InstState.livePhrase).apply() }
        box.addView(pg)
        ui().setTitle("🌱 Lebendige Patterns").setView(scroll).setPositiveButton("Fertig", null).show()
    }

    // ================================================================ Bester-Loop-Finder

    private fun findBestLoop(p: Int) {
        val bar = Engine.masterFrames().toInt()
        val len = Engine.padDataLen(p)
        if (bar <= 0) { toast("Erst ein Tempo festlegen (Song mit Auto-Beatmatch laden)"); return }
        if (len < bar * 3) { toast("Zu kurz – mindestens 3 Takte"); return }
        val opts = intArrayOf(1, 2, 4, 8).filter { len >= bar * (it + 1) }
        ui().setTitle("🔍 Besten Loop finden – Länge").setItems(opts.map { "$it Takt" + (if (it > 1) "e" else "") }.toTypedArray()) { _, w ->
            val bars = opts[w]
            hint("🔍 Suche …")
            Thread {
                val data = Engine.loopData(p) ?: return@Thread
                val res = LoopFinder.find(data, Engine.sampleRate, bar, bars, 6)
                runOnUiThread {
                    if (res.isEmpty()) { toast("Nichts gefunden"); return@runOnUiThread }
                    val labels = res.mapIndexed { i, c ->
                        String.format(Locale.GERMANY, "%s Takt %d–%d · %d %%   (Wiederholung %d · Naht %d · Fülle %d)",
                            if (i == 0) "⭐" else "  ", c.startBar + 1, c.startBar + bars, (min(1f, c.score) * 100).toInt(), (c.rep * 100).toInt(), (c.seam * 100).toInt(), (c.fill * 100).toInt())
                    }.toTypedArray()
                    var prev = -1
                    ui().setTitle("🔍 Beste $bars-Takt-Loops in Pad ${p + 1}").setItems(labels) { _, k ->
                        val c = res[k]
                        val seg = data.copyOfRange(c.startBar * bar, min(data.size, (c.startBar + bars) * bar))
                        ui().setTitle("Takt ${c.startBar + 1}–${c.startBar + bars}").setItems(arrayOf("▶ Vorhören", "▦ Auf ein freies Pad legen", "↺ Pad ${p + 1} durch diesen Loop ersetzen")) { _, a ->
                            when (a) {
                                0 -> { if (prev >= 0) Engine.noteOff(prev); prev = Engine.preview(seg) }
                                1 -> {
                                    val q = (0 until PadView.VISIBLE_PADS).firstOrNull { !Engine.hasLoop(it) }
                                    if (q == null) toast("Kein freies Pad") else {
                                        Engine.padOneShot[q] = false
                                        Engine.importLoop(q, seg)
                                        Engine.setPadName(q, "🔁 T${c.startBar + 1}–${c.startBar + bars}")
                                        hint("🔁 Loop auf Pad ${q + 1} – antippen startet ihn im Takt")
                                    }
                                }
                                else -> {
                                    val name = Engine.padName(p)
                                    Engine.importLoop(p, seg)
                                    Engine.setPadName(p, "🔁 " + (name?.take(10) ?: "T${c.startBar + 1}"))
                                    hint("Pad ${p + 1}: jetzt der $bars-Takt-Loop · ↶ UNDO holt den Song zurück")
                                }
                            }
                        }.setOnDismissListener { if (prev >= 0) { Engine.noteOff(prev); prev = -1 } }.show()
                    }.setNegativeButton("Schließen", null).show()
                }
            }.start()
        }.show()
    }

    // ================================================================ Ein-Tipp-Remix

    private fun pickRemixPad() {
        val list = (0 until PadView.VISIBLE_PADS).filter { Engine.hasLoop(it) && !Engine.isOneShot(it) && Engine.masterFrames() > 0 && Engine.padDataLen(it) >= Engine.masterFrames() * 8 }
        if (list.isEmpty()) { toast("Erst einen Song (mind. 8 Takte, im Tempo) auf ein Pad laden"); return }
        if (list.size == 1) { oneTapRemix(list[0]); return }
        ui().setTitle("🪄 Ein-Tipp-Remix – welcher Song?").setItems(list.map { "Pad ${it + 1}" + (Engine.padName(it)?.let { n -> " · $n" } ?: "") }.toTypedArray()) { _, w -> oneTapRemix(list[w]) }.show()
    }

    /**
     * Ein-Tipp-Remix: Song in Stems trennen, aus dem stärksten Teil 4-Takt-Loops bauen (Drums, Bass, Melodie, Vocals)
     * und daraus ein neues 64-Takt-Arrangement in der Song-Matrix: Intro → Build-up → Drop → Break → Drop → Outro.
     */
    private fun oneTapRemix(p: Int) {
        val ai = AiStems.ready(this)
        val need = if (ai) 4 else 3
        val free = (0 until PadView.VISIBLE_PADS).filter { it != p && !Engine.hasLoop(it) }
        if (free.size < need) { toast("Für den Remix werden $need freie Pads gebraucht (frei: ${free.size})"); return }
        val bar = Engine.masterFrames()
        if (bar <= 0L || Engine.padDataLen(p) < bar * 8) { toast("Song braucht ein Tempo und mind. 8 Takte"); return }
        val len = Engine.padDataLen(p)
        System.gc()
        val rt = Runtime.getRuntime()
        if (len.toLong() * 4 * 10 > rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) { toast("Zu lang für den Speicher – erst kürzen"); return }
        confirm("🪄 Ein-Tipp-Remix von Pad ${p + 1}?", "Der Song wird in Stems getrennt" + (if (ai) " (🧠 KI)" else " (schnell, ohne Gesang – mit KI-Modellen gibt es auch Vocals)") +
            ". Daraus entstehen 4-Takt-Loops auf freien Pads und ein neues Arrangement (64 Takte). Die Song-Matrix wird dabei ersetzt.") {
            Thread {
                try {
                    Engine.padLoadText[p] = "🪄 Remix"
                    Engine.padLoadProg[p] = 0f
                    val data = Engine.loopData(p) ?: return@Thread
                    val sr = Engine.sampleRate
                    // [vocals, drums, bass, melodie]
                    val st: Array<FloatArray?> = if (ai) {
                        val s = AiStems.separatePad(this, data, sr, { false }) { f -> Engine.padLoadProg[p] = f * 0.95f } ?: return@Thread
                        arrayOf(s[0], s[1], s[2], s[3])
                    } else {
                        val s = Stems.split(data, sr) { f -> Engine.padLoadProg[p] = f * 0.95f }
                        arrayOf(null, s[0], s[1], s[2])
                    }
                    val b4 = (bar * 4).toInt()
                    val blocks = data.size / b4
                    fun en(x: FloatArray?, blk: Int): Double {
                        if (x == null) return 0.0
                        var e = 0.0
                        var i = blk * b4
                        val end = min(x.size, (blk + 1) * b4)
                        while (i < end) { e += x[i] * x[i]; i += 4 }
                        return e
                    }
                    val eV = DoubleArray(blocks) { en(st[0], it) }
                    val eD = DoubleArray(blocks) { en(st[1], it) }
                    val eB = DoubleArray(blocks) { en(st[2], it) }
                    val eM = DoubleArray(blocks) { en(st[3], it) }
                    val drop = (0 until blocks).maxByOrNull { eD[it] + eB[it] + 0.5 * eM[it] + 0.3 * eV[it] } ?: 0
                    val vMax = eV.maxOrNull() ?: 0.0
                    val vBlk = if (vMax <= 0.0 || eV[drop] >= 0.4 * vMax) drop else (0 until blocks).maxByOrNull { eV[it] } ?: drop
                    fun cut(x: FloatArray?, blk: Int): FloatArray? = x?.copyOfRange(blk * b4, min(x.size, (blk + 1) * b4))?.takeIf { it.size == b4 }
                    val loops = listOf("Drums" to cut(st[1], drop), "Bass" to cut(st[2], drop), "Melodie" to cut(st[3], drop), "Vocals" to cut(st[0], vBlk))
                    val base = Engine.padName(p)?.take(8) ?: "P${p + 1}"
                    runOnUiThread {
                        val rows = IntArray(4) { -1 }
                        var fi = 0
                        for ((k, pr) in loops.withIndex()) {
                            val d = pr.second ?: continue
                            if (fi >= free.size) break
                            val q = free[fi++]
                            Engine.padOneShot[q] = false
                            Engine.importLoop(q, d)
                            Engine.setPadName(q, "🪄 ${pr.first} · $base")
                            rows[k] = q
                        }
                        // Arrangement: 0 Drums, 1 Bass, 2 Melodie, 3 Vocals
                        Engine.arrClear()
                        fun on(k: Int, from: Int, to: Int) { val r = rows[k]; if (r >= 0) for (b in from until to) Engine.setArrCell(r, b, 1) }
                        on(0, 0, 8)                                          // Intro: Drums
                        on(0, 8, 16); on(1, 12, 16); on(2, 8, 16); on(3, 12, 16)   // Build-up
                        for (k in 0..3) on(k, 16, 32)                        // Drop
                        on(2, 32, 40); on(3, 32, 40)                         // Break: Melodie + Vocals
                        on(0, 36, 40)                                        // Drums kommen zurück
                        for (k in 0..3) on(k, 40, 56)                        // Drop 2
                        on(0, 56, 64); on(1, 56, 60)                         // Outro
                        Engine.songBars = 64
                        Engine.seqStop()
                        Engine.songMode = true
                        Engine.seqPlay()
                        pads.invalidate()
                        hint("🪄 Remix läuft: Intro → Build-up → Drop → Break → Drop → Outro (Takt ${drop * 4 + 1}–${drop * 4 + 4} des Songs) · Song-Matrix zeigt und ändert den Ablauf")
                    }
                } catch (e: OutOfMemoryError) {
                    runOnUiThread { toast("Zu lang für den Speicher – erst kürzen") }
                } catch (e: Throwable) {
                    val c = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                    runOnUiThread { toast("Remix fehlgeschlagen: ${c.message ?: c}") }
                } finally {
                    Engine.padLoadProg[p] = -1f
                }
            }.start()
        }
    }

    /** Folge dem Song: Tonart (und Akkorde) aus einem Pad oder Deck in die Instrumente übernehmen. */
    private fun showFollowSong() {
        val src = ArrayList<Pair<String, Int>>() // Kennung: Pad ≥ 0, Deck = -1 - k
        for (p in 0 until PadView.VISIBLE_PADS) if (Engine.hasLoop(p) && !Engine.isOneShot(p)) src.add(("▦ Pad ${p + 1}" + (Engine.padName(p)?.let { " · $it" } ?: "")) to p)
        for (k in 0..1) if (Dj.decks[k].loaded()) src.add(("🎧 Deck ${"AB"[k]} · ${Dj.decks[k].title}" + if (Dj.decks[k].key >= 0) " (${Keys.camelot(Dj.decks[k].effKey())})" else "") to (-1 - k))
        if (src.isEmpty()) { toast("Erst einen Song auf ein Pad oder in ein Deck laden"); return }
        ui().setTitle("🎯 Folge dem Song – Quelle").setItems(src.map { it.first }.toTypedArray()) { _, w ->
            val id = src[w].second
            val modes = arrayOf("Nur Tonart (Tonleiter, Auto-Tune, Einrasten)", "Tonart + Akkorde als Fläche (SYNTH 2)", "Tonart + Bass-Linie (SYNTH 3)", "Tonart + Fläche + Bass")
            ui().setTitle("Was übernehmen?").setItems(modes) { _, m -> followSong(id, m) }.show()
        }.setNegativeButton("Abbrechen", null).show()
    }

    private fun followSong(id: Int, mode: Int) {
        hint("🎯 Höre in den Song …")
        Thread {
            try {
                // Mono ~11 kHz, Takte, Tonart
                val sr11: Double
                val x: FloatArray
                val barLen: Int
                val bars: Int
                var key: Int
                var shift = 0
                if (id >= 0) {
                    val d = Engine.loopData(id) ?: throw IllegalStateException("Pad leer")
                    val f = max(1, Math.round(Engine.sampleRate / 11025.0).toInt())
                    sr11 = Engine.sampleRate.toDouble() / f
                    x = FloatArray(d.size / f) { i -> var s = 0f; for (j in 0 until f) s += d[i * f + j]; s / f }
                    val mf = Engine.masterFrames()
                    bars = if (mf > 0) Math.round(d.size.toDouble() / mf).toInt().coerceIn(1, 4) else 4
                    barLen = x.size / max(1, if (mf > 0) Math.round(d.size.toDouble() / mf).toInt() else 4)
                    key = Keys.detectMono(x, sr11).first
                } else {
                    val dd = Dj.decks[-1 - id]
                    val data = dd.data ?: throw IllegalStateException("Deck leer")
                    val f = max(1, Math.round(dd.srcRate / 11025.0).toInt())
                    sr11 = dd.srcRate.toDouble() / f
                    // 4 Takte ab dem aktuellen Takt
                    val barF = dd.beatFrames() * 4
                    val from = (dd.grid + (Math.floor(Dj.barAt(dd, dd.pos)) * 4 + dd.barOffset) * dd.beatFrames()).coerceAtLeast(0.0)
                    bars = 4
                    val n = min(((dd.len - from) / f).toInt(), (barF * 4 / f).toInt()).coerceAtLeast(0)
                    x = FloatArray(n) { i -> var s = 0f; for (j in 0 until f) { val fr = (from + i * f + j).toInt().coerceIn(0, dd.len - 1); s += if (dd.ch == 2) (data[2 * fr] + data[2 * fr + 1]) * 0.5f else data[fr].toFloat() }; s / (f * 32768f) }
                    barLen = (barF / f).toInt()
                    key = if (dd.key >= 0) dd.key else Keys.detectMono(x, sr11).first
                    val ek = if (dd.key >= 0) dd.effKey() else key
                    shift = if (key >= 0 && ek >= 0) Math.floorMod(Keys.root(ek) - Keys.root(key), 12) else 0
                }
                if (key < 0) throw IllegalStateException("Tonart nicht erkannt – zu kurz oder nur Schlagzeug?")
                val starts = IntArray(bars) { it * barLen }
                val ends = IntArray(bars) { (it + 1) * barLen }
                val chords = if (mode > 0) MicTools.chords(x, sr11, starts, ends, key).map { if (it >= 0) Keys.shift(it, shift) else -1 }.toIntArray() else IntArray(0)
                val k2 = Keys.shift(key, shift)
                runOnUiThread {
                    Engine.pushPatternUndo("Folge dem Song")
                    InstState.scaleRoot = Keys.root(k2)
                    InstState.scaleType = if (Keys.isMinor(k2)) 2 else 1
                    Engine.autoTuneKey = k2
                    if (mode == 1 || mode == 3) InstState.writeChords(1, chords, false)
                    if (mode == 2 || mode == 3) InstState.writeChords(2, chords, true)
                    pads.invalidate()
                    val cn = if (chords.isNotEmpty()) " · Akkorde: " + chords.joinToString(" – ") { Keys.name(it) } else ""
                    hint("🎯 Tonart ${Keys.name(k2)} (${Keys.camelot(k2)}) übernommen$cn · ↶ UNDO macht es rückgängig")
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Folge dem Song: ${e.message ?: e}") }
            }
        }.start()
    }

    // ================================================================ Mashup-Assistent

    private fun showMashup() {
        if (Dj.mashArmed) { Dj.mashCancel(); hint("Mashup abgebrochen"); return }
        val a = Dj.decks[0]; val b = Dj.decks[1]
        fun kd(d: Dj.Deck) = if (d.key >= 0) " (${Keys.camelot(d.effKey())})" else ""
        val items = arrayOf(
            "🎤 Gesang von A${kd(a)}  →  über den Beat von B${kd(b)}",
            "🎤 Gesang von B${kd(b)}  →  über den Beat von A${kd(a)}",
            "↩ Mashup beenden: alle Stems wieder an",
            "ℹ So funktioniert's"
        )
        ui().setTitle("🎭 Mashup-Assistent").setItems(items) { _, w ->
            when (w) {
                0, 1 -> {
                    val vi = w
                    val bi = 1 - w
                    val vd = Dj.decks[vi]
                    if (vd.loaded() && vd.stemMask == null) { toast("Stems von Deck ${"AB"[vi]} werden noch berechnet – gleich nochmal"); return@setItems }
                    hint(Dj.mashup(vi, bi))
                }
                2 -> { for (d in Dj.decks) for (s in 0..3) d.stemGain[s] = 1f; Dj.mashCancel(); hint("Alle Stems wieder an") }
                else -> ui().setTitle("🎭 Mashup-Assistent").setMessage(
                    "1. Beat-Track in ein Deck laden und starten.\n2. Track mit Gesang ins andere Deck laden (Stems werden berechnet – mit 🧠 KI am saubersten).\n" +
                    "3. Hier „Gesang von … über den Beat von …“ wählen.\n\n" +
                    "Der Assistent sucht den stärksten Gesangsteil, gleicht die Tonart an (Key-Shift mit Keylock), koppelt das Tempo und startet den Gesang genau auf der nächsten 4-Takt-Grenze. " +
                    "Dabei wird das Gesangs-Deck zum Acapella und das Beat-Deck zum Instrumental. Danach kannst du mit den STEMS-Pads und dem Crossfader frei weiterspielen."
                ).setPositiveButton("OK", null).show()
            }
        }.setNegativeButton("Schließen", null).show()
    }

    // ================================================================ Cloud-Backup

    private var backupSongs = false

    private fun showBackup() {
        val songs = CloudImport.dir(this).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576
        ui().setTitle("☁ Backup sichern (aktuelles Projekt vorher speichern)").setItems(arrayOf(
            "💾 Projekte, Aufnahmen, Bibliothek, Sammlung & Einstellungen",
            "💾 … dazu die Cloud-Songs ($songs MB)"
        )) { _, w ->
            backupSongs = w == 1
            val name = "MarvPAD-Backup-" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(java.util.Date()) + ".zip"
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, name) }
            hint("Speicherort wählen: oben links ☰ → Dropbox oder Google Drive")
            try { startActivityForResult(i, REQ_BACKUP) } catch (e: Exception) { toast("Keine Dateiauswahl gefunden") }
        }.setNegativeButton("Abbrechen", null).show()
    }

    private fun pickRestore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        hint("Backup wählen: oben links ☰ → Dropbox oder Google Drive")
        try { startActivityForResult(i, REQ_RESTORE) } catch (e: Exception) { toast("Keine Dateiauswahl gefunden") }
    }

    private fun writeBackup(u: Uri) {
        val withSongs = backupSongs
        progressJob("☁ Backup sichern", { cancel, prog ->
            Backup.write(this, u, withSongs, cancel, prog)
        }) { toast("☁ Backup gesichert") }
    }

    private fun confirmRestore(u: Uri) {
        confirm("Backup wiederherstellen?", "Projekte, Aufnahmen, Bibliothek, Sammlung und Einstellungen werden durch das Backup ersetzt. Danach startet die App neu.") {
            progressJob("☁ Backup wiederherstellen", { cancel, prog -> Backup.restore(this, u, cancel, prog) }) {
                ui().setTitle("☁ Wiederhergestellt").setMessage("Die App startet jetzt neu.").setCancelable(false).setPositiveButton("OK") { _, _ ->
                    Engine.stop()
                    val i = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                    if (i != null) startActivity(i)
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }.show()
            }
        }
    }

    // ================================================================ KI-Stems

    private fun showAiStems(k: Int) {
        val prefs = getSharedPreferences("marvpad", MODE_PRIVATE)
        val dd = Dj.decks[k]
        if (!AiStems.ready(this)) {
            ui().setTitle("🧠 KI-Stems wie bei Serato").setMessage(
                "Ein neuronales Netz (Spleeter von Deezer) trennt jeden Track sauber in Vocals, Drums, Bass und Melodie – " +
                "viel genauer als die schnelle Trennung.\n\n" +
                "• Einmalig ~80 MB Modelle laden (am besten im WLAN).\n" +
                "• Danach rechnet die App jeden geladenen Track im Hintergrund (je nach Handy etwa ½–2 Minuten für einen 5-Minuten-Song). " +
                "Bis dahin wirken die STEMS-Pads mit der schnellen Trennung, dann wird automatisch umgeschaltet.\n" +
                "• Ergebnisse werden gespeichert – derselbe Track hat beim nächsten Laden sofort KI-Stems."
            ).setPositiveButton("Modelle laden") { _, _ ->
                progressJob("🧠 KI-Modelle laden", { cancel, prog -> AiStems.download(this, cancel, prog) }) {
                    AiStems.auto = true
                    prefs.edit().putBoolean("aiAuto", true).apply()
                    var n = 0
                    for (d in Dj.decks) if (d.loaded()) { AiStems.request(this, d); n++ }
                    toast(if (n > 0) "🧠 KI-Stems bereit – rechne jetzt ${if (n == 2) "beide Decks" else "das Deck"}" else "🧠 KI-Stems bereit – ab jetzt für jeden geladenen Track")
                }
            }.setNegativeButton("Später", null).show()
            return
        }
        val items = arrayListOf(
            if (AiStems.auto) "✅ Automatisch für jeden geladenen Track – antippen = aus" else "⬜ Automatisch beim Laden – antippen = an",
            when {
                !dd.loaded() -> "Deck ${"AB"[k]} ist leer"
                dd.aiProg >= 0f -> "⏳ Deck ${"AB"[k]}: KI rechnet (${(dd.aiProg * 100).toInt()} %)"
                dd.stemMask?.ai == true -> "✔ Deck ${"AB"[k]} hat KI-Stems"
                else -> "▶ Jetzt für Deck ${"AB"[k]} rechnen"
            },
            "🗑 Modelle löschen (${AiStems.sizeMb(this)} MB)"
        )
        AiStems.lastError?.let { items.add("⚠ Letzter Fehler: $it") }
        ui().setTitle("🧠 KI-Stems (Spleeter)").setItems(items.toTypedArray()) { _, w ->
            when (w) {
                0 -> {
                    AiStems.auto = !AiStems.auto
                    prefs.edit().putBoolean("aiAuto", AiStems.auto).apply()
                    if (AiStems.auto) for (d in Dj.decks) if (d.loaded() && d.stemMask?.ai != true && d.aiProg < 0f) AiStems.request(this, d)
                    hint(if (AiStems.auto) "🧠 KI-Stems: automatisch an" else "🧠 KI-Stems: nur auf Wunsch")
                }
                1 -> if (dd.loaded() && dd.aiProg < 0f && dd.stemMask?.ai != true) { AiStems.request(this, dd); hint("🧠 KI-Trennung Deck ${"AB"[k]} läuft …") }
                2 -> confirm("KI-Modelle löschen?", "Die STEMS-Pads nutzen dann wieder die schnelle Trennung.") { AiStems.deleteModels(this); hint("Modelle gelöscht") }
                else -> {}
            }
        }.setNegativeButton("Schließen", null).show()
    }

    /** Hintergrund-Auftrag mit Fortschrittsbalken und Abbrechen. */
    private fun progressJob(title: String, job: (cancel: () -> Boolean, prog: (Float, String) -> Unit) -> Unit, done: () -> Unit) {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val tv = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(200, 200, 200)); setPadding(0, pad / 3, 0, 0) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(bar); addView(tv) }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = ui().setTitle(title).setView(box).setCancelable(false).setNegativeButton("Abbrechen") { _, _ -> cancelled.set(true) }.create()
        dlg.show()
        var lastUi = 0L
        Thread {
            try {
                job({ cancelled.get() }, prog@{ p, n ->
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastUi < 120 && p < 1f) return@prog
                    lastUi = now
                    runOnUiThread {
                        if (p < 0f) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = (p * 1000).toInt() }
                        tv.text = n + if (p >= 0f) "  ·  ${(p * 100).toInt()} %" else ""
                    }
                })
                runOnUiThread { dlg.dismiss(); done() }
            } catch (e: CloudImport.Cancelled) {
                runOnUiThread { dlg.dismiss(); hint("Abgebrochen") }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    val m = when (e) {
                        is java.net.UnknownHostException -> "Keine Internetverbindung"
                        is java.net.SocketTimeoutException -> "Zeitüberschreitung – Verbindung zu langsam?"
                        else -> e.message ?: e.toString()
                    }
                    ui().setTitle("Fehlgeschlagen").setMessage(m).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    // ================================================================ Cloud-Import (Dropbox, Drive, OneDrive, Links)

    private var cloudTarget = CLOUD_COLLECTION

    /** Woher? Cloud-App über die Dateiauswahl oder ein geteilter Link. [target]: Pad, Sammlung oder Deck. */
    private fun showCloudImport(target: Int) {
        cloudTarget = target
        val multi = target == CLOUD_COLLECTION
        val dest = when {
            target == CLOUD_COLLECTION -> "in die Sammlung"
            target >= CLOUD_DECK -> "in Deck ${"AB"[target - CLOUD_DECK]}"
            else -> "auf Pad ${target + 1}"
        }
        ui().setTitle("☁ Online-Speicher → $dest").setItems(arrayOf(
            "🔗 Geteilten Link einfügen – Dropbox (auch ganze Ordner), Google Drive, OneDrive, MP3-Link",
            "📲 Aus der Dropbox-/Drive-/OneDrive-App wählen" + if (multi) " (mehrere möglich)" else "",
            "ℹ So geht's"
        )) { _, w ->
            when (w) {
                0 -> askCloudLink()
                1 -> {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi)
                        putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    }
                    hint("In der Auswahl oben links ☰ antippen und Dropbox / Drive / OneDrive wählen")
                    try { startActivityForResult(i, REQ_CLOUD_FILES) } catch (e: Exception) { toast("Keine Dateiauswahl gefunden") }
                }
                else -> ui().setTitle("☁ Songs aus dem Online-Speicher").setMessage(
                    "LINK (ohne App):\n" +
                    "• Dropbox: Datei oder Ordner → Teilen → Link kopieren. Ein ganzer Ordner wird als ZIP geladen und entpackt – alle Songs darin landen in der Sammlung.\n" +
                    "• Google Drive: Teilen → „Jeder mit dem Link“ → Link kopieren (einzelne Dateien).\n" +
                    "• OneDrive: Teilen → Link kopieren.\n" +
                    "• Oder ein direkter Link auf eine MP3/WAV.\n\n" +
                    "APP:\nIst die Dropbox-, Drive- oder OneDrive-App installiert, erscheint sie in der Dateiauswahl unter ☰. " +
                    "Dort einfach die Songs antippen.\n\n" +
                    "Die Songs werden aufs Handy kopiert (Ordner Musik/Cloud der App). So spielen sie auch ohne Internet – wichtig beim Auflegen."
                ).setPositiveButton("OK", null).show()
            }
        }.setNegativeButton("Abbrechen", null).show()
    }

    private fun askCloudLink() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() } catch (e: Exception) { null }
        val et = EditText(this).apply {
            hint = "https://www.dropbox.com/…"
            isSingleLine = true
            textSize = 14f
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            if (clip != null && (clip.startsWith("http") || clip.contains("dropbox.com") || clip.contains("drive.google") || clip.contains("1drv.ms"))) setText(clip)
        }
        val pad = (18 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(et) }
        ui().setTitle("🔗 Link einfügen").setView(box).setPositiveButton("Laden") { _, _ ->
            val link = et.text.toString().trim()
            if (link.length < 8) { toast("Kein Link"); return@setPositiveButton }
            cloudRun("Lade herunter …") { cancel, prog -> CloudImport.download(this, link, cancel, prog) }
        }.setNegativeButton("Abbrechen", null).show()
    }

    /** Läuft im Hintergrund mit Fortschritt und Abbrechen, liefert die Dateien an das Ziel. */
    private fun cloudRun(title: String, job: (cancel: () -> Boolean, prog: (Float, String) -> Unit) -> List<java.io.File>) {
        val target = cloudTarget
        val pad = (18 * resources.displayMetrics.density).toInt()
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val tv = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(200, 200, 200)); setPadding(0, pad / 3, 0, 0) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(bar); addView(tv) }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = ui().setTitle(title).setView(box).setCancelable(false).setNegativeButton("Abbrechen") { _, _ -> cancelled.set(true) }.create()
        dlg.show()
        var lastUi = 0L
        Thread {
            try {
                val files = job({ cancelled.get() }, prog@{ p, n ->
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastUi < 120 && p < 1f) return@prog
                    lastUi = now
                    runOnUiThread {
                        if (p < 0f) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = (p * 1000).toInt() }
                        tv.text = n + if (p >= 0f) "  ·  ${(p * 100).toInt()} %" else ""
                    }
                })
                runOnUiThread {
                    dlg.dismiss()
                    cloudDeliver(target, files)
                }
            } catch (e: CloudImport.Cancelled) {
                runOnUiThread { dlg.dismiss(); hint("Abgebrochen") }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    val m = when (e) {
                        is java.net.UnknownHostException -> "Keine Internetverbindung"
                        is java.net.SocketTimeoutException -> "Zeitüberschreitung – Verbindung zu langsam?"
                        else -> e.message ?: e.toString()
                    }
                    ui().setTitle("☁ Import fehlgeschlagen").setMessage(m).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun cloudDeliver(target: Int, files: List<java.io.File>) {
        if (files.isEmpty()) return
        val uris = files.map { Uri.fromFile(it) to it.nameWithoutExtension }
        when {
            target == CLOUD_COLLECTION -> addToCollection(uris)
            target >= CLOUD_DECK -> {
                djDeck = target - CLOUD_DECK
                if (files.size > 1) addToCollection(uris.drop(1))
                onActivityResult(REQ_DECK, RESULT_OK, Intent().setData(uris[0].first))
            }
            else -> {
                menuPad = target
                if (files.size > 1) { addToCollection(uris.drop(1)); hint("Erster Song aufs Pad, die übrigen ${files.size - 1} in die Sammlung") }
                onActivityResult(REQ_IMPORT, RESULT_OK, Intent().setData(uris[0].first))
            }
        }
    }

    private fun pickCollectionFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(i, REQ_COL_TREE) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
    }

    private fun pickCollectionFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(i, REQ_COL_FILES) } catch (e: Exception) { toast("Kein Dateimanager gefunden") }
    }

    private fun addToCollection(uris: List<Pair<Uri, String>>) {
        TrackDb.load(this)
        var n = 0
        for ((u, name) in uris) if (TrackDb.add(u, name).second) n++
        TrackDb.save(this)
        hint(if (n > 0) "💿 $n Tracks in der Sammlung – Analyse läuft im Hintergrund" else "Alle Tracks waren schon in der Sammlung")
        colRefresh?.invoke()
        if (n > 0) startBatchAnalysis()
    }

    /** Ordner-Analyse: alle noch nicht analysierten Tracks nacheinander im Hintergrund. */
    private fun startBatchAnalysis() {
        if (colBatch) return
        colBatch = true
        Thread {
            var done = 0
            try {
                while (colBatch) {
                    val t = TrackDb.all().firstOrNull { !it.analyzed && !it.missing } ?: break
                    try {
                        TrackDb.analyze(this, t)
                    } catch (e: OutOfMemoryError) {
                        t.missing = true
                    } catch (e: Throwable) {
                        t.missing = true
                    }
                    done++
                    if (done % 3 == 0) TrackDb.save(this)
                    colRefresh?.invoke()
                    if (done % 10 == 0) runOnUiThread { hint("Analyse: $done Tracks fertig …") }
                }
            } finally {
                colBatch = false
                TrackDb.save(this)
                colRefresh?.invoke()
                val left = TrackDb.all().count { !it.analyzed && !it.missing }
                if (done > 0) runOnUiThread { hint("⟳ Analyse fertig: $done Tracks" + if (left > 0) " · $left offen" else "") }
            }
        }.start()
    }

    /** Track aus der Sammlung ins Deck – mit gespeichertem Raster, Tonart, Phrasen und Cue-Punkten. */
    private fun loadDeckCollection(k: Int, t: TrackDb.Track) {
        val dd = Dj.decks[k]
        saveDeckCollection(k)
        dd.loading = true
        hint("Deck ${"AB"[k]}: lade ${t.title} …")
        Thread {
            try {
                val tr = deckDecode(dd) { Dj.decode(this, Uri.parse(t.uri)) }
                Dj.load(dd, tr, t.title, if (t.bpm > 0f) t.bpm else Warp.bpmFromName(t.title))
                val r = dd.srcRate.toDouble()
                if (t.analyzed || t.bpm > 0f) {
                    dd.grid = t.grid * r
                    dd.barOffset = t.barOffset
                    dd.phraseOff = t.phraseOff
                }
                if (t.key >= 0) { dd.keyGen++; dd.key = t.key; dd.keyConf = t.keyConf }
                // Song-Struktur zum gespeicherten Raster neu einteilen
                if (t.analyzed || t.bpm > 0f) try { Dj.analyzeParts(dd) } catch (ignored: Throwable) {}
                if (t.cue >= 0) { dd.cuePoint = t.cue * r; dd.seekTo = dd.cuePoint } else { dd.cuePoint = dd.grid; dd.seekTo = dd.grid }
                for (h in dd.hot.indices) dd.hot[h] = if (h < t.hot.size && t.hot[h] >= 0) t.hot[h] * r else -1.0
                dd.colId = t.id
                t.missing = false
                if (!t.analyzed) {
                    // erste Werte aus dem Deck übernehmen, die volle Analyse folgt im Hintergrund
                    t.bpm = dd.bpm
                    t.grid = dd.grid / r
                    t.lufs = dd.lufs
                    t.seconds = dd.seconds()
                }
                TrackDb.played(t)
                TrackDb.save(this)
                deckSig[k] = deckSigOf(dd)
                runOnUiThread { hint(String.format(Locale.GERMANY, "Deck %s: %s · %.1f BPM · %s", "AB"[k], t.title, dd.bpm, Keys.camelot(dd.key))) }
                if (!t.analyzed) {
                    // Rest der Analyse (Tonart, Energie, Lautheit) direkt aus dem Deck – ohne erneutes Laden, Raster bleibt
                    val data = dd.data
                    val ch = dd.ch; val len = dd.len; val rate = dd.srcRate
                    if (data != null) Thread {
                        try {
                            TrackDb.analyzeData(this, t, data, ch, len, rate, true)
                            if (dd.colId == t.id && dd.key < 0 && t.key >= 0) { dd.keyGen++; dd.key = t.key; dd.keyConf = t.keyConf }
                            TrackDb.save(this)
                            colRefresh?.invoke()
                        } catch (ignored: Throwable) {}
                    }.start()
                }
            } catch (e: SecurityException) {
                t.missing = true
                runOnUiThread { toast("Kein Zugriff mehr auf „${t.title}“ – Ordner bitte neu hinzufügen") }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { toast("Track zu groß für den Speicher") }
            } catch (e: Throwable) {
                t.missing = true
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                dd.loading = false; dd.loadProg = -1f
            }
        }.start()
    }

    /** Fingerabdruck der speicherbaren Deck-Werte (Änderungen erkennen). */
    private fun deckSigOf(dd: Dj.Deck): String =
        "${dd.colId}|${dd.bpm}|${dd.grid}|${dd.cuePoint}|${dd.hot.joinToString(",")}|${dd.key}|${dd.keyConf}|${dd.barOffset}|${dd.phraseOff}"
    private val deckSig = arrayOf("", "")

    /** Automatisch sichern: Änderungen an Tempo, Raster, Tonart, Takt-Eins und Cue-Punkten landen sofort in der Sammlung. */
    private val deckAutosave = object : Runnable {
        override fun run() {
            try { for (k in 0..1) saveDeckIfChanged(k) } catch (ignored: Throwable) {}
            window.decorView.removeCallbacks(this)
            window.decorView.postDelayed(this, 5_000L)
        }
    }

    private fun saveDeckIfChanged(k: Int) {
        val dd = Dj.decks[k]
        if (dd.loading || dd.colId == null || !dd.loaded()) return
        val s = deckSigOf(dd)
        if (s == deckSig[k]) return
        deckSig[k] = s
        saveDeckCollection(k)
    }

    /** Geänderte Cue-Punkte, Raster und Tonart eines Sammlungs-Tracks speichern. */
    private fun saveDeckCollection(k: Int) {
        val dd = Dj.decks[k]
        val t = TrackDb.byId(dd.colId) ?: return
        if (!dd.loaded() || dd.loading) return
        val r = dd.srcRate.toDouble()
        t.cue = dd.cuePoint / r
        for (h in t.hot.indices) t.hot[h] = if (h < dd.hot.size && dd.hot[h] >= 0) dd.hot[h] / r else -1.0
        t.bpm = dd.bpm
        t.grid = dd.grid / r
        if (dd.key >= 0) { t.key = dd.key; t.keyConf = dd.keyConf }
        t.barOffset = dd.barOffset
        t.phraseOff = dd.phraseOff
        TrackDb.save(this)
    }

    /** Tonart eines Decks: Keylock, Key-Shift, harmonisch an das andere Deck anpassen, von Hand setzen. */
    private fun showKeyMenu(k: Int) {
        val dd = Dj.decks[k]
        if (!dd.loaded()) return
        val o = Dj.decks[1 - k]
        val ek = dd.effKey()
        val ok = if (o.loaded()) o.effKey() else -1
        val title = if (dd.key < 0) "Deck ${"AB"[k]} · Tonart unbekannt" else
            String.format(Locale.GERMANY, "Deck %s · %s (%s)%s · Sicherheit %d %%", "AB"[k], Keys.camelot(ek), Keys.name(ek),
                if (dd.keyShift != 0) " · Original ${Keys.camelot(dd.key)}" else "", (dd.keyConf * 100).toInt())
        val fit = if (ok >= 0 && dd.key >= 0) Keys.bestShift(ok, Keys.shift(dd.key, 0)) else null
        val compatTxt = when (if (ok >= 0) Keys.compat(ok, ek) else -1) {
            0 -> "✅ gleiche Tonart wie Deck ${"AB"[1 - k]}"
            1 -> "✅ passt harmonisch zu Deck ${"AB"[1 - k]} (${Keys.camelot(ok)})"
            2 -> "🟡 Energie-Wechsel zu Deck ${"AB"[1 - k]} (${Keys.camelot(ok)}) – geht, klingt spannend"
            3 -> "🔴 beißt sich mit Deck ${"AB"[1 - k]} (${Keys.camelot(ok)})"
            else -> "Anderes Deck: keine Tonart"
        }
        val items = arrayListOf<String>()
        val acts = arrayListOf<() -> Unit>()
        items.add(compatTxt); acts.add { showKeyMenu(k) }
        items.add(if (dd.keylock) "🔒 Keylock AN – Tonhöhe bleibt beim Pitchen (antippen = aus)" else "🔓 Keylock AUS – Pitch verändert die Tonhöhe wie bei Platten (antippen = an)")
        acts.add {
            dd.keylock = !dd.keylock
            dd.gReset = true
            hint(if (dd.keylock) "🔒 Keylock Deck ${"AB"[k]}: Tempo ändern, Tonart bleibt" else "Keylock Deck ${"AB"[k]} aus")
        }
        if (fit != null && fit != dd.keyShift && o.loaded()) {
            items.add("🎯 Passend zu Deck ${"AB"[1 - k]} machen: " + (if (fit == 0) "Original-Tonart" else (if (fit > 0) "+" else "") + "$fit Halbton") + " → ${Keys.camelot(Keys.shift(dd.key, fit))}")
            acts.add { dd.keyShift = fit; dd.gReset = true; hint("Deck ${"AB"[k]}: Tonart ${Keys.camelot(dd.effKey())} – passt zu ${Keys.camelot(o.effKey())}") }
        }
        for (st in intArrayOf(1, -1, 2, -2)) {
            val ns = (dd.keyShift + st).coerceIn(-6, 6)
            items.add((if (st > 0) "♯ +$st" else "♭ $st") + " Halbton → " + (if (dd.key >= 0) Keys.camelot(Keys.shift(dd.key, ns)) else "") + "  (jetzt ${if (dd.keyShift > 0) "+" else ""}${dd.keyShift})")
            acts.add { dd.keyShift = ns; dd.gReset = true; hint("Deck ${"AB"[k]}: Key-Shift ${if (ns > 0) "+" else ""}$ns" + if (dd.key >= 0) " → ${Keys.camelot(dd.effKey())}" else "") }
        }
        if (dd.keyShift != 0) {
            items.add("⟲ Original-Tonart"); acts.add { dd.keyShift = 0; dd.gReset = true; hint("Deck ${"AB"[k]}: Original-Tonart ${Keys.camelot(dd.effKey())}") }
        }
        items.add("🔎 Tonart neu erkennen"); acts.add { hint("Erkenne Tonart …"); Dj.detectKeyAsync(dd) { runOnUiThread { hint("Deck ${"AB"[k]}: ${Keys.camelot(dd.key)} (${Keys.name(dd.key)}) · Sicherheit ${(dd.keyConf * 100).toInt()} %") } } }
        items.add("✎ Tonart von Hand setzen …"); acts.add {
            val order = (0 until 24).sortedWith(compareBy({ Keys.camelotNum(it) }, { if (Keys.isMinor(it)) 0 else 1 }))
            ui().setTitle("Tonart Deck ${"AB"[k]}")
                .setItems(order.map { "${Keys.camelot(it)} · ${Keys.name(it)}" }.toTypedArray()) { _, i ->
                    dd.keyGen++
                    dd.key = order[i]
                    dd.keyConf = 1f
                    if (dd.libId != null || dd.colId != null) saveDeckToLib(k)
                }.show()
        }
        items.add("ℹ Camelot-Rad: Nachbarn (±1) und A↔B mischen sich harmonisch"); acts.add {
            ui().setTitle("Harmonisch mixen")
                .setMessage("Jede Tonart hat eine Nummer auf dem Camelot-Rad (1–12) und einen Buchstaben: A = Moll, B = Dur.\n\n✅ Gleiche Nummer, oder ±1 mit gleichem Buchstaben (8A → 7A / 9A), oder A↔B mit gleicher Nummer (8A ↔ 8B): klingt sauber.\n🟡 ±2 (Energie-Boost) oder Buchstabe wechseln und ±1: hörbar, aber spannend.\n🔴 Alles andere beißt sich meist.\n\nOhne Keylock ändert Pitchen die Tonart mit (~ hinter der Tonart = verschoben). Keylock 🔒 hält sie fest; Key-Shift verschiebt sie gezielt.")
                .setPositiveButton("OK", null).show()
        }
        ui().setTitle(title).setItems(items.toTypedArray()) { _, w -> acts[w]() }.setNegativeButton("Schließen", null).show()
    }

    /** Cue-Punkte des Decks in der DJ-Bibliothek sichern (bzw. den Track neu aufnehmen). */
    private fun saveDeckToLib(k: Int) {
        val dd = Dj.decks[k]
        if (dd.colId != null && dd.libId == null) { saveDeckCollection(k); hint("💾 Cue-Punkte in der Sammlung gespeichert"); return }
        val r = dd.srcRate.toDouble()
        val hot = DoubleArray(dd.hot.size) { if (dd.hot[it] >= 0) dd.hot[it] / r else -1.0 }
        val id = dd.libId
        if (id != null) {
            DjLibrary.update(this, id, dd.bpm, dd.grid / r, dd.cuePoint / r, hot, dd.key)
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
                    val it = DjLibrary.add(this, mono, rate, name, dd.bpm, dd.grid / r, dd.cuePoint / r, hot, dd.key)
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
        ui()
            .setTitle(String.format(Locale.GERMANY, "Deck %s · %.1f BPM", "AB"[k], dd.bpm))
            .setItems(arrayOf("× 2 (doppelt)", "÷ 2 (halb)", "Tempo eingeben …", "Schlag 1 = aktuelle Position (Raster setzen)",
                "Takt-Eins = der Schlag hier (Taktlinien verschieben)", "Phrase (16 Takte) beginnt in diesem Takt", "Takte und Phrasen neu erkennen")) { _, w ->
                when (w) {
                    0 -> { dd.bpm = dd.bpm * 2f; Dj.analyzePhrase(dd) }
                    1 -> { dd.bpm = dd.bpm / 2f; Dj.analyzePhrase(dd) }
                    2 -> askName(String.format(Locale.US, "%.1f", dd.bpm), "Tempo (BPM)") { t ->
                        t.replace(',', '.').toFloatOrNull()?.let { v -> if (v in 40f..240f) { dd.bpm = v; Dj.analyzePhrase(dd) } }
                    }
                    3 -> {
                        dd.grid = dd.pos
                        dd.cuePoint = dd.pos
                        Dj.analyzePhrase(dd)
                        hint("Raster gesetzt: Schlag 1 liegt jetzt hier")
                    }
                    4 -> {
                        val b = Math.round(Dj.beatIndex(dd, dd.pos)).toLong()
                        dd.barOffset = Math.floorMod(b, 4L).toInt()
                        hint("Takt-Eins liegt jetzt auf diesem Schlag")
                    }
                    5 -> {
                        val bar = Math.floor(Dj.barAt(dd, dd.pos) + 0.05).toLong()
                        dd.phraseOff = Math.floorMod(bar, 16L).toInt()
                        hint("Phrase beginnt in diesem Takt – die gelbe Linie zeigt alle 16 Takte")
                    }
                    else -> { Dj.analyzePhrase(dd); hint("Takte und Phrasen neu erkannt") }
                }
                if (dd.sync) dd.syncReq = true
            }
            .show()
    }

    private fun deckFromLibrary(k: Int) {
        val user = Projects.userSamples(this)
        val cats = (SampleLibrary.categories + user.map { it.category }).distinct()
        ui()
            .setTitle("Bibliothek → Deck ${"AB"[k]}")
            .setItems(cats.toTypedArray()) { _, ci ->
                val cat = cats[ci]
                val own = user.filter { it.category == cat }
                val lib = SampleLibrary.samples.filter { it.category == cat }
                val labels = own.map { "★ " + it.title } + lib.map { it.title }
                ui()
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
                dd.loadProg = 0.2f
                val data = get()
                dd.loadProg = 0.7f
                val rate = Engine.sampleRate
                // kurze Loops: Tempo wie beim Pad-Import (Länge + Rhythmus), lange Tracks: eigene Analyse
                val nameBpm = Warp.bpmFromName("$title ${file ?: ""}") ?: if (data.size < rate * 30) Warp.analyze(data, rate, title).bpm else null
                Dj.load(dd, Dj.fromMono(data, rate), title, nameBpm)
                runOnUiThread { hint(String.format(Locale.GERMANY, "Deck %s: %s · %.1f BPM", "AB"[k], title, dd.bpm)) }
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                dd.loading = false; dd.loadProg = -1f
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
                val t = deckDecode(dd) { Dj.decode(this, uri) }
                Dj.load(dd, t, name.substringBeforeLast('.'), Warp.bpmFromName(name))
                runOnUiThread { hint(String.format(Locale.GERMANY, "Deck %s: %s · %.1f BPM · %s", "AB"[k], name, dd.bpm, Dj.fmt(dd.seconds()))) }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { toast("Track zu groß für den Speicher") }
            } catch (e: Throwable) {
                runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
            } finally {
                dd.loading = false; dd.loadProg = -1f
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

    /**
     * Bei 90/120-Hz-Displays zeichnet die Oberfläche sonst doppelt so oft – das kostet CPU, die dem Audio fehlt,
     * und heizt das Handy auf (dann drosselt es den Takt → Knackser).
     */
    private fun applyFrameRate(limit: Boolean) {
        try {
            val lp = window.attributes
            var mode = 0
            if (limit && Build.VERSION.SDK_INT >= 23) {
                @Suppress("DEPRECATION")
                val d = windowManager.defaultDisplay
                val cur = d.mode
                // gleicher Bildschirmmodus, nur mit ~60 Hz
                mode = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && it.refreshRate in 55f..65f }
                    .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }?.modeId ?: 0
            }
            lp.preferredDisplayModeId = mode
            lp.preferredRefreshRate = if (limit) 60f else 0f
            window.attributes = lp
        } catch (ignored: Throwable) {}
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
        check(box, "Bildrate auf 60 Hz begrenzen – mehr Rechenzeit fürs Audio, weniger Wärme und Akku (Touch und Ton reagieren genauso schnell)", prefs.getBoolean("fps60", true)) {
            prefs.edit().putBoolean("fps60", it).apply()
            applyFrameRate(it)
        }
        note(box, "ONE SHOTS und Noten klingen immer im nächsten Audio-Block (wenige Millisekunden). Touch-Ereignisse werden ohne Bildtakt-Verzögerung zugestellt.")
        ui()
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
        ui()
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
            ui().setTitle("MIDI-Kanal der Pads")
                .setSingleChoiceItems(names, Midi.channel) { d, w -> Midi.channel = w; chLabel(); d.dismiss() }.show()
        }
        box.addView(chBtn)
        val baseBtn = Button(this)
        fun baseLabel() { baseBtn.text = "Pad-Noten ab ${Midi.noteName(Midi.padBase)} (${Midi.padBase}) …" }
        baseLabel()
        baseBtn.setOnClickListener {
            val opts = (24..84).toList()
            ui().setTitle("Pad 1 = Note …")
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
            ui().setTitle("Kanal für die Tastatur (chromatisch, C4 = Originalton)")
                .setSingleChoiceItems(names, Midi.keysChannel) { d, w ->
                    Midi.keysChannel = w
                    d.dismiss()
                    val pn = arrayOf("Pad aus der offenen Piano-Roll") + Array(PadView.VISIBLE_PADS) { "Pad ${it + 1}" }
                    ui().setTitle("Welches Pad spielt die Tastatur?")
                        .setItems(pn) { _, p -> Midi.keysPad = p - 1; keysLabel() }.show()
                    keysLabel()
                }.show()
        }
        box.addView(keysBtn)
        box.addView(Button(this).apply {
            text = "🎛 Regler & Fader zuordnen (CC lernen) …"
            setOnClickListener { midiCcMap() }
        })
        box.addView(Button(this).apply {
            text = "🎧 DJ-Controller: Tasten lernen, Vorlagen, Jog-Rad …"
            setOnClickListener { midiDjMap() }
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
                ui().setTitle("MIDI-Geräte")
                    .setMultiChoiceItems(all.toTypedArray(), on) { _, w, c -> on[w] = c }
                    .setPositiveButton("OK") { _, _ ->
                        Midi.disabled.clear()
                        for (i in all.indices) if (!on[i]) Midi.disabled.add(all[i])
                        Midi.reopen()
                    }.show()
            }
        })
        ui()
            .setTitle("🎹 MIDI")
            .setView(scroll)
            .setPositiveButton("Fertig", null)
            .setOnDismissListener {
                live.removeCallbacks(ticker)
                Midi.learnPad = -1
                Midi.learnCc = -1
                Midi.learnBtn = -1
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
        ui()
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

    /** DJ-Controller: Tasten je Deck lernen, Vorlage laden, Jog-Rad-Kodierung. */
    private fun midiDjMap() {
        val n = Midi.djBtn.size
        val labels = ArrayList<String>()
        labels.add("📋 Vorlage: Pioneer DDJ-400 / DDJ-FLX4 laden (bitte prüfen)")
        labels.add("🌀 Jog-Rad: " + (if (Midi.jogMode == 0) "Mitte 64 (Pioneer, Numark)" else "1/127 (Hercules, Traktor)") + " – antippen wechselt")
        labels.add("🎚 Regler, Fader, EQ, Jog-Räder (CC) …")
        labels.add("🗑 Alle DJ-Tasten löschen")
        for (i in 0 until n) labels.add(Midi.djBtnName(i) + ": " + Midi.noteLabel(Midi.djBtn[i]))
        ui().setTitle("🎧 DJ-Controller – Taste antippen, dann am Controller drücken")
            .setItems(labels.toTypedArray()) { _, w ->
                when (w) {
                    0 -> {
                        Midi.presetDdj()
                        Midi.save(getSharedPreferences("marvpad", MODE_PRIVATE))
                        ui().setTitle("Vorlage DDJ-400 / DDJ-FLX4 geladen")
                            .setMessage("Belegt: Play, Cue, Sync, Kopfhörer, Hot Cues 1–4 (Pads im Hot-Cue-Modus), Jog-Räder (Rand = Pitch-Bend, oben = Scratch), Kanal-Fader, Crossfader, EQ, Gain, Filter (CFX), Tempo-Fader.\n\nDie Werte stammen aus dem freien Mixxx-Mapping – ich konnte sie nicht am Gerät prüfen. Stimmt etwas nicht, die Taste hier einfach neu lernen. Unter „Letztes Ereignis“ im MIDI-Menü siehst du, was der Controller sendet.")
                            .setPositiveButton("OK", null).show()
                    }
                    1 -> { Midi.jogMode = 1 - Midi.jogMode; Midi.save(getSharedPreferences("marvpad", MODE_PRIVATE)); midiDjMap() }
                    2 -> midiCcMap()
                    3 -> confirm("Alle DJ-Tasten löschen?", "Regler-Zuordnungen bleiben.") { java.util.Arrays.fill(Midi.djBtn, -1); Midi.save(getSharedPreferences("marvpad", MODE_PRIVATE)) }
                    else -> {
                        val i = w - 4
                        Midi.learnBtn = i
                        hint("${Midi.djBtnName(i)}: jetzt die Taste am Controller drücken …")
                    }
                }
            }
            .setNegativeButton("Fertig") { _, _ -> Midi.learnBtn = -1; Midi.save(getSharedPreferences("marvpad", MODE_PRIVATE)) }
            .show()
    }

    private fun midiCcMap() {
        val labels = Array(Midi.TARGETS.size) { i -> Midi.TARGETS[i] + ": " + Midi.ccLabel(Midi.ccMap[i]) }
        ui()
            .setTitle("Ziel wählen")
            .setItems(labels) { _, t ->
                ui()
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
        ui()
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
        ui()
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
        if (requestCode == REQ_LIB_IN) {
            if (resultCode != RESULT_OK || data == null) return
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
            else data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importLibraryUris(uris)
            return
        }
        if (requestCode == REQ_GHOST_LOOP) {
            val u = data?.data
            if (resultCode != RESULT_OK || u == null) return
            val name = (displayName(u) ?: "Loop").substringBeforeLast('.')
            hint("∞ $name: Tempo und Tonart werden erkannt …")
            Thread {
                try {
                    val x = AudioFiles.decode(this, u, Engine.sampleRate, 60)
                    GhostLoop.load(x, Engine.sampleRate, name)
                    runOnUiThread { (if (djMode) dj.ghost else pads.ghost).loopLoaded(); hint("∞ $name läuft im Takt") }
                } catch (e: Throwable) {
                    runOnUiThread { toast("Loop konnte nicht geladen werden: ${e.message ?: e}") }
                }
            }.start()
            return
        }
        if (requestCode == REQ_EXPORT_DIR) {
            val tree = data?.data
            if (resultCode != RESULT_OK || tree == null) return
            if (exportKind == 2) writeSongMidi(tree) else startBounce(tree, exportKind == 1)
            return
        }
        if (requestCode == REQ_BACKUP || requestCode == REQ_RESTORE) {
            val u = data?.data
            if (resultCode != RESULT_OK || u == null) return
            if (requestCode == REQ_BACKUP) writeBackup(u) else confirmRestore(u)
            return
        }
        if (requestCode == REQ_CLOUD_FILES) {
            if (resultCode != RESULT_OK || data == null) return
            val list = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (q in 0 until clip.itemCount) clip.getItemAt(q).uri?.let { list.add(it) } else data.data?.let { list.add(it) }
            if (list.isNotEmpty()) cloudRun("Kopiere aufs Handy …") { cancel, prog ->
                list.mapIndexed { i, u -> CloudImport.copyFromUri(this, u, cancel) { p, n -> prog((i + (if (p < 0) 0.5f else p)) / list.size, if (list.size > 1) "${i + 1}/${list.size}: $n" else n) } }
            }
            return
        }
        if (requestCode == REQ_COL_FILES || requestCode == REQ_COL_TREE) {
            if (resultCode != RESULT_OK || data == null) return
            if (requestCode == REQ_COL_TREE) {
                val tree = data.data ?: return
                try { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (ignored: Exception) {}
                hint("Lese Ordner …")
                Thread {
                    val found = TrackDb.scanTree(this, tree)
                    runOnUiThread { if (found.isEmpty()) toast("Keine Audiodateien gefunden") else addToCollection(found) }
                }.start()
            } else {
                val uris = ArrayList<Pair<Uri, String>>()
                val clip = data.clipData
                val list = ArrayList<Uri>()
                if (clip != null) for (q in 0 until clip.itemCount) clip.getItemAt(q).uri?.let { list.add(it) } else data.data?.let { list.add(it) }
                for (u in list) {
                    try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (ignored: Exception) {}
                    uris.add(u to (displayName(u) ?: "Track").substringBeforeLast('.'))
                }
                addToCollection(uris)
            }
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val pad = menuPad
        when (requestCode) {
            REQ_STEMS -> writeStems(uri)
            REQ_LIB_OUT -> {
                val w = pendingLibExport ?: return
                pendingLibExport = null
                toast("Exportiere …")
                Thread {
                    try {
                        val msg = contentResolver.openOutputStream(uri)?.use { out -> w(out) } ?: "Export fehlgeschlagen"
                        runOnUiThread { toast(msg) }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Export fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
            }
            REQ_DECK -> {
                // Datei landet automatisch in der Sammlung (dauerhafter Zugriff, Analyse im Hintergrund)
                var inCol: TrackDb.Track? = null
                try {
                    // dauerhafte Leseberechtigung nur bei Dateiauswahl (Cloud-Downloads liegen schon im App-Ordner)
                    if (uri.scheme == "content") try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (ignored: Exception) {}
                    TrackDb.load(this)
                    val (t, isNew) = TrackDb.add(uri, (displayName(uri) ?: "Track").substringBeforeLast('.'))
                    if (isNew) TrackDb.save(this)
                    inCol = t
                } catch (ignored: Exception) {}
                if (inCol != null) loadDeckCollection(djDeck, inCol) else loadDeckUri(djDeck, uri)
            }
            REQ_MIDI_OUT -> writeMidi(uri)
            REQ_MIDI_IN -> readMidi(uri)
            REQ_INST_OUT -> writeInstMidi(uri)
            REQ_INST_IN -> readInstMidi(uri)
            REQ_SET -> {
                val f = pendingSet ?: return
                toast("Exportiere …")
                Thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) } }
                        runOnUiThread { toast("Set exportiert") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Export fehlgeschlagen: ${e.message ?: e}") }
                    }
                }.start()
            }
            REQ_IMPORT -> {
                if (pad < 0) return
                toast("Importiere …")
                val rate = Engine.sampleRate
                Thread {
                    try { padProgress(pad) {
                        LoadProgress.stage(0f, 0.6f)
                        val secs = memSeconds(rate)
                        val samples = AudioFiles.decode(this, uri, rate, secs)
                        if (samples.size >= rate.toLong() * secs - rate && secs < Engine.MAX_IMPORT_SECONDS)
                            runOnUiThread { hint("Speicher knapp: auf ${secs / 60}:${String.format(Locale.GERMANY, "%02d", secs % 60)} min gekürzt – andere Pads leeren gibt mehr Platz") }
                        if (Engine.padOneShot[pad]) {
                            Engine.importLoop(pad, samples)
                            runOnUiThread { toast("Pad ${pad + 1}: Import fertig") }
                        } else {
                            val name = displayName(uri)
                            val desc = clipToPad(pad, samples, rate, name)
                            runOnUiThread { hint("Pad ${pad + 1}: ${name ?: "Datei"} · $desc · antippen startet ihn auf der Eins") }
                        }
                    } } catch (e: OutOfMemoryError) {
                        runOnUiThread { toast("Datei zu lang für den Speicher – bitte kürzeren Song oder Ausschnitt wählen") }
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

    private fun displayName(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** 📂 auf einem leeren Pad: Datei, Bibliothek oder eigene Aufnahme. */
    /** Drumset auf die 12 Pads legen – ersetzt alles oder füllt nur leere Pads. */
    private fun showDrumKits() {
        val kits = SampleLibrary.KITS
        ui()
            .setTitle("🥁 Drumset auf die Pads legen")
            .setItems(kits.map { k -> k.name + "\n   " + k.files.mapNotNull { SampleLibrary.byFile(it)?.title?.substringBefore(" (") }.take(6).joinToString(" · ") + " …" }.toTypedArray()) { _, which ->
                val kit = kits[which]
                val empty = (0 until min(12, kit.files.size)).filter { !Engine.hasLoop(it) }
                ui()
                    .setTitle(kit.name)
                    .setMessage("Pad 1–12 belegen (oben links = Pad 1, Kick und Snare liegen unten links). Alle Pads werden ⚡ One-Shots.")
                    .setPositiveButton("Alle 12 ersetzen") { _, _ -> loadKit(kit, (0 until min(12, kit.files.size)).toList()) }
                    .setNeutralButton("Nur leere (${empty.size})") { _, _ -> if (empty.isEmpty()) hint("Keine leeren Pads") else loadKit(kit, empty) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun loadKit(kit: SampleLibrary.Kit, pads: List<Int>) {
        val rate = Engine.sampleRate
        hint("Lade ${kit.name} …")
        Thread {
            try {
                val data = ArrayList<FloatArray>()
                val names = ArrayList<String?>()
                for (p in pads) {
                    val f = kit.files[p]
                    data.add(AudioFiles.decodeAsset(this, f, rate, Engine.MAX_SECONDS))
                    names.add(SampleLibrary.byFile(f)?.title?.substringBefore(" (") ?: f.substringAfterLast('/').substringBefore('.'))
                }
                Engine.loadKit(pads, data, names)
                runOnUiThread { hint("🥁 ${kit.name} auf ${pads.size} Pads – ↶ UNDO holt die alten Pads zurück") }
            } catch (e: Exception) {
                runOnUiThread { toast("Drumset laden fehlgeschlagen: ${e.message ?: e}") }
            }
        }.start()
    }

    private fun showImportMenu(p: Int) {
        menuPad = p
        val shot = Engine.padOneShot[p]
        ui()
            .setTitle("Pad ${p + 1} laden")
            .setItems(arrayOf(
                "📂 Datei (WAV / MP3) – Tempo wird erkannt und gewarpt",
                "📚 Bibliothek & eigene Aufnahmen",
                "💿 Aus der Musik-Sammlung (schon analysiert – lädt schneller)",
                "☁ Aus Dropbox / Google Drive / OneDrive oder per Link",
                if (shot) "Modus: ⚡ ONE SHOT  →  auf ∞ LOOP" else "Modus: ∞ LOOP  →  auf ⚡ ONE SHOT"
            )) { _, w ->
                when (w) {
                    0 -> pickImport()
                    1 -> showLibrary(p)
                    2 -> pickCollectionForPad(p)
                    3 -> showCloudImport(p)
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
                    ui()
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
        ui()
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
            ui()
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
        ui()
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
        private const val REQ_SET = 19
        private const val REQ_LIB_IN = 20
        private const val REQ_LIB_OUT = 21
        private const val REQ_COL_TREE = 22
        private const val REQ_COL_FILES = 23
        private const val REQ_BT = 24
        private const val REQ_EXPORT_DIR = 25
        private const val REQ_GHOST_LOOP = 26
        private const val REQ_CLOUD_FILES = 27
        private const val REQ_BACKUP = 28
        private const val REQ_RESTORE = 29
        /** Ziel eines Cloud-Imports: Pad 0..n, die Sammlung oder ein Deck (CLOUD_DECK + 0/1). */
        private const val CLOUD_COLLECTION = -1
        private const val CLOUD_DECK = 1000
    }
}
