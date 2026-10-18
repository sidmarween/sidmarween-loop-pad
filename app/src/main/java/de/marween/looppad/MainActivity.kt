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
        if (toDj) pads.closeFocus()
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
        pads.onPadFxEditor = { p -> showPadFxEditor(p) }
        pads.onEditorSave = { p, data, slices, cues -> showEditorSave(p, data, slices, cues) }
        pads.onRollIo = { p -> showRollIo(p) }
        pads.onInstMidi = { k -> showInstMidi(k) }
        pads.onInstPresets = { k -> showInstPresets(k) }
        pads.onLaunchQuantChanged = {
            prefs.edit().putInt("launchIdx", Engine.launchIdx).apply()
            hint(if (Engine.LAUNCH_Q[Engine.launchIdx] <= 0f) "Start-Raster SOFORT: Loops starten/stoppen beim Antippen"
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
        dj.onSetRec = { toggleSetRec() }
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
        // läuft eine Set-Aufnahme, bleibt das Audio an
        if (!pickerOpen && !djPlaying && !SetRecorder.recording) Engine.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
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
                        Engine.padSource[p] = w
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
                    Engine.padSource[p] = 3
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
                    Engine.padSource[p] = 2
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

    /** Datei auf ein Pad laden – mit Auto-Beatmatch (einmal analysiert, dann gemerkt) oder im Original-Tempo. */
    private fun clipToPad(p: Int, data: FloatArray, rate: Int, name: String?): String {
        if (!Engine.autoBeatmatch) return Engine.importFree(p, data)
        val key = beatKey(name, data)
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
                val data = AudioFiles.decodeFile(smp.file, rate, Engine.MAX_SECONDS)
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
                    val data = AudioFiles.decode(this, u, rate, Engine.MAX_SECONDS)
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
                    .setItems(arrayOf("📤 Exportieren (WAV)", "✎ Umbenennen", "🎧 In Deck A laden", "🗑 Löschen")) { _, a ->
                        when (a) {
                            0 -> exportSet(f)
                            1 -> renameSet(f)
                            2 -> {
                                val dd = Dj.decks[0]
                                dd.loading = true
                                Thread {
                                    try {
                                        val t = Dj.decode(this, Uri.fromFile(f))
                                        Dj.load(dd, t, f.nameWithoutExtension, null)
                                        runOnUiThread { hint("Deck A: ${f.nameWithoutExtension}"); switchMode(true) }
                                    } catch (e: Throwable) {
                                        runOnUiThread { toast("Laden fehlgeschlagen: ${e.message ?: e}") }
                                    } finally { dd.loading = false }
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
        val items = listOf("🎲 Zufallsmelodie") + genres.map { g -> "$g (${InstPresets.SYNTHS.count { it.genre == g }})" } + listOf("📥 Eigene MIDI-Datei laden …")
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
                    else -> {
                        val g = genres[w - 1]
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
    private fun showMainMenu() {
        val groups = listOf(
            "💾 Projekt & Dateien" to { showMenuGroup(0) },
            "🎙 Aufnahme & Wiedergabe" to { showMenuGroup(1) },
            "🎨 Aussehen: Designs, Schriften, Ansicht" to { showMenuGroup(2) },
            "🔌 Audio, Latenz & MIDI" to { showMenuGroup(3) },
            "ℹ Hilfe & Info" to { showMenuGroup(4) },
            (if (djMode) "▦ Zurück zum Pad-Modus" else "🎧 DJ-Modus (zwei Decks, Crossfader)") to { switchMode(!djMode) }
        )
        ui().setTitle("Marv PAD · Einstellungen")
            .setItems(groups.map { it.first }.toTypedArray()) { _, w -> groups[w].second() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun showMenuGroup(g: Int) {
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
            }
            else -> {
                title = "ℹ Hilfe & Info"
                items.add(Triple(check(pads.infoMode) + " ℹ Info-Modus (Element antippen = Erklärung)", {
                    pads.infoMode = !pads.infoMode
                    prefs.edit().putBoolean("info", pads.infoMode).apply()
                    if (pads.infoMode) toast("Info-Modus an: tippe auf ein Element, um es erklärt zu bekommen")
                }, false))
                items.add(Triple("📄 Info-Seite: Version & alle Änderungen", { showAbout() }, false))
                items.add(Triple("⏻ Programm schließen", { requestClose() }, false))
            }
        }
        ui().setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, w ->
                val it = items[w]
                it.second()
                if (it.third) showMenuGroup(g)
            }
            .setNegativeButton("Zurück") { _, _ -> showMainMenu() }
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
        ui()
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
        ui()
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

    private fun midiCcMap() {
        val labels = Array(Midi.TARGETS.size) { i -> Midi.TARGETS[i] + ": " + (if (Midi.ccMap[i] < 0) "–" else "CC ${Midi.ccMap[i]}") }
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
            REQ_DECK -> loadDeckUri(djDeck, uri)
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
                    try {
                        val samples = AudioFiles.decode(this, uri, rate, Engine.MAX_SECONDS)
                        if (Engine.padOneShot[pad]) {
                            Engine.importLoop(pad, samples)
                            runOnUiThread { toast("Pad ${pad + 1}: Import fertig") }
                        } else {
                            val name = displayName(uri)
                            val desc = clipToPad(pad, samples, rate, name)
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
    }
}
