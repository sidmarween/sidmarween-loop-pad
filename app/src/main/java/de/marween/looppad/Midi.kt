package de.marween.looppad

import android.content.Context
import android.content.SharedPreferences
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MIDI: Controller (USB, virtuelle Geräte) spielen Pads, Tastatur und Regler.
 * Alles konfigurierbar (Kanal, Noten der Pads, Lernen, CC-Zuordnung, Clock, Transport).
 * Pad-Anschläge laufen direkt im MIDI-Thread in die Engine – ohne Umweg über die Oberfläche.
 */
object Midi {
    // ---------------------------------------------------------------- Einstellungen
    @Volatile var enabled = true
    /** 0 = alle Kanäle (Omni), sonst 1..16. */
    @Volatile var channel = 0
    /** Tiefste Pad-Note (36 = C1, wie bei MPC/Maschine). */
    @Volatile var padBase = 36
    /** Gelernte Noten je Pad (-1 = Grundton + Pad-Nummer). */
    val padNotes = IntArray(12) { -1 }
    @Volatile var velocity = true
    /** Kanal für die Tastatur (chromatisch spielen), 0 = aus. */
    @Volatile var keysChannel = 2
    /** Pad, das die Tastatur spielt (-1 = das gerade in der Piano-Roll geöffnete bzw. Pad 1). */
    @Volatile var keysPad = -1
    @Volatile var clockIn = false
    @Volatile var clockOut = false
    @Volatile var transportIn = true
    @Volatile var programScenes = true
    /** Geräte, die ausgeschaltet wurden (Name). */
    val disabled = HashSet<String>()

    val TARGETS = arrayOf(
        "XY-Effekt X", "XY-Effekt Y", "MIX", "Regler 2 (STEIL …)", "Regler 3 (DRIVE …)", "Master-VOL", "SWING",
        "Crossfader", "Deck A Lautstärke", "Deck B Lautstärke", "Deck A Filter", "Deck B Filter",
        "Deck A Pitch", "Deck B Pitch", "Mikrofon (Mithören)", "Deck A Effekt", "Deck B Effekt",
        "Deck A HI", "Deck A MID", "Deck A LOW", "Deck B HI", "Deck B MID", "Deck B LOW", "Deck A Gain", "Deck B Gain",
        "Deck A Jog-Rad Rand (Pitch-Bend)", "Deck B Jog-Rad Rand (Pitch-Bend)", "Deck A Jog-Rad oben (Scratch)", "Deck B Jog-Rad oben (Scratch)",
        "Vorhören-Lautstärke"
    )

    /** DJ-Tasten je Deck (gelernt als Kanal + Note). */
    val DJ_BTN_NAMES = arrayOf(
        "▶ Play/Pause", "CUE", "SYNC", "🎧 Vorhören", "Hot Cue 1", "Hot Cue 2", "Hot Cue 3", "Hot Cue 4",
        "LOOP an/aus", "Loop ½", "Loop ×2", "Beat Jump ◀", "Beat Jump ▶", "Keylock", "ECHO OUT", "BRAKE", "Jog-Rad berührt", "Loop Roll 1/8 (halten)", "SLIP"
    )
    const val DJ_PER_DECK = 19
    /** Kanal*128 + Note je DJ-Taste (-1 = keine). */
    val djBtn = IntArray(DJ_PER_DECK * 2) { -1 }
    @Volatile var learnBtn = -1
    private val jogTouch = BooleanArray(2)
    private val cueHeld = BooleanArray(2)

    fun djBtnName(i: Int): String = "Deck ${"AB"[i / DJ_PER_DECK]} · ${DJ_BTN_NAMES[i % DJ_PER_DECK]}"

    /** Zuordnung lesbar: „CC 19“ (alle Kanäle) bzw. „CC 19 · K2“. */
    fun ccLabel(code: Int): String = when {
        code < 0 -> "–"
        code < 128 -> "CC $code"
        else -> "CC ${(code - 128) % 128} · K${(code - 128) / 128 + 1}"
    }

    fun noteLabel(code: Int): String = if (code < 0) "–" else "${noteName(code % 128)} (${code % 128}) · K${code / 128 + 1}"

    /**
     * Vorlage Pioneer DDJ-400 / DDJ-FLX4 (Werte wie im Mixxx-Mapping; bitte prüfen und bei Bedarf
     * einzeln neu lernen): Deck A Kanal 1, Deck B Kanal 2, Hot-Cue-Pads Kanal 8 / 10, Mixer Kanal 7.
     */
    fun presetDdj() {
        java.util.Arrays.fill(djBtn, -1)
        for (k in 0..1) {
            val ch = k
            val base = k * DJ_PER_DECK
            djBtn[base + 0] = ch * 128 + 0x0B
            djBtn[base + 1] = ch * 128 + 0x0C
            djBtn[base + 2] = ch * 128 + 0x58
            djBtn[base + 3] = ch * 128 + 0x54
            val padCh = if (k == 0) 7 else 9
            for (h in 0..3) djBtn[base + 4 + h] = padCh * 128 + h
            djBtn[base + 16] = ch * 128 + 0x36
        }
        fun cc(t: Int, ch: Int, n: Int) { ccMap[t] = 128 + ch * 128 + n }
        cc(7, 6, 0x1F)
        cc(8, 0, 0x13); cc(9, 1, 0x13)
        cc(10, 6, 0x17); cc(11, 6, 0x18)
        cc(12, 0, 0x00); cc(13, 1, 0x00)
        cc(17, 0, 0x07); cc(18, 0, 0x0B); cc(19, 0, 0x0F)
        cc(20, 1, 0x07); cc(21, 1, 0x0B); cc(22, 1, 0x0F)
        cc(23, 0, 0x04); cc(24, 1, 0x04)
        cc(25, 0, 0x21); cc(26, 1, 0x21)
        cc(27, 0, 0x22); cc(28, 1, 0x22)
    }
    /** CC-Nummer je Ziel (-1 = keine). */
    val ccMap = IntArray(TARGETS.size) { -1 }

    /** Lernen: Pad (0..11) bzw. Ziel – das nächste MIDI-Ereignis wird zugeordnet. */
    @Volatile var learnPad = -1
    @Volatile var learnCc = -1

    /** Rückmeldungen an die Oberfläche (Hinweis, Pad-Anzeige). */
    @Volatile var onHint: ((String) -> Unit)? = null
    @Volatile var onPad: ((Int, Boolean) -> Unit)? = null
    @Volatile var onKeysNote: ((Int, Boolean) -> Unit)? = null
    /** Aktuell geöffnetes Piano-Roll-Pad (setzt die Oberfläche). */
    @Volatile var openRollPad = -1

    // ---------------------------------------------------------------- Laufzeit
    private var manager: MidiManager? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val devices = ArrayList<MidiDevice>()
    private val outPorts = ArrayList<MidiOutputPort>()
    private val inPorts = ArrayList<MidiInputPort>()
    @Volatile var deviceNames: List<String> = emptyList()
        private set
    @Volatile var lastEvent = ""
        private set

    private val padLaunched = BooleanArray(12)
    private val padHeld = BooleanArray(12)
    private val keyVoices = HashMap<Int, Int>()
    private val keyEvents = HashMap<Int, Engine.NoteEv>()

    fun load(p: SharedPreferences) {
        val s = p.getString("midi", null) ?: return
        try {
            val o = JSONObject(s)
            enabled = o.optBoolean("on", true)
            channel = o.optInt("ch", 0)
            padBase = o.optInt("base", 36)
            velocity = o.optBoolean("vel", true)
            keysChannel = o.optInt("keysCh", 2)
            keysPad = o.optInt("keysPad", -1)
            clockIn = o.optBoolean("clockIn", false)
            clockOut = o.optBoolean("clockOut", false)
            transportIn = o.optBoolean("transport", true)
            programScenes = o.optBoolean("prog", true)
            o.optJSONArray("pads")?.let { a -> for (i in 0 until min(12, a.length())) padNotes[i] = a.optInt(i, -1) }
            o.optJSONArray("cc")?.let { a -> for (i in 0 until min(ccMap.size, a.length())) ccMap[i] = a.optInt(i, -1) }
            o.optJSONArray("djBtn")?.let { a -> for (i in 0 until min(djBtn.size, a.length())) djBtn[i] = a.optInt(i, -1) }
            jogMode = o.optInt("jog", 0)
            o.optJSONArray("outMode")?.let { a -> for (i in 0 until min(5, a.length())) outMode[i] = a.optInt(i, 0) }
            o.optJSONArray("outCh")?.let { a -> for (i in 0 until min(5, a.length())) outCh[i] = a.optInt(i, outCh[i]).coerceIn(1, 16) }
            outEarlyMs = o.optDouble("outEarly", 0.0).toFloat()
            disabled.clear()
            o.optJSONArray("off")?.let { a -> for (i in 0 until a.length()) disabled.add(a.optString(i)) }
        } catch (ignored: Exception) {}
    }

    fun save(p: SharedPreferences) {
        val o = JSONObject()
        o.put("on", enabled); o.put("ch", channel); o.put("base", padBase); o.put("vel", velocity)
        o.put("keysCh", keysChannel); o.put("keysPad", keysPad); o.put("clockIn", clockIn); o.put("clockOut", clockOut)
        o.put("transport", transportIn); o.put("prog", programScenes)
        o.put("pads", JSONArray().apply { padNotes.forEach { put(it) } })
        o.put("cc", JSONArray().apply { ccMap.forEach { put(it) } })
        o.put("djBtn", JSONArray().apply { djBtn.forEach { put(it) } })
        o.put("jog", jogMode)
        o.put("outMode", JSONArray().apply { outMode.forEach { put(it) } })
        o.put("outCh", JSONArray().apply { outCh.forEach { put(it) } })
        o.put("outEarly", outEarlyMs.toDouble())
        o.put("off", JSONArray().apply { disabled.forEach { put(it) } })
        p.edit().putString("midi", o.toString()).apply()
    }

    /** Note eines Pads (-1 = keine, wenn die Note einem anderen Pad gelernt wurde). */
    fun padNote(i: Int): Int = when {
        padNotes[i] >= 0 -> padNotes[i]
        padNotes[i] == -1 -> padBase + i
        else -> -1
    }

    fun noteName(n: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H")
        return names[Math.floorMod(n, 12)] + (n / 12 - 2)
    }

    // ---------------------------------------------------------------- Geräte

    fun start(ctx: Context) {
        if (manager != null) return
        if (!ctx.packageManager.hasSystemFeature("android.software.midi")) return
        val m = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        manager = m
        val t = HandlerThread("MarvPAD-MIDI", Process.THREAD_PRIORITY_URGENT_AUDIO)
        t.start()
        thread = t
        handler = Handler(t.looper)
        @Suppress("DEPRECATION")
        for (info in m.devices) open(info)
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                open(device)
                onHint?.invoke("🎹 MIDI verbunden: ${nameOf(device)}")
            }
            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                closeDevice(device)
                onHint?.invoke("MIDI getrennt: ${nameOf(device)}")
            }
        }, handler)
        startClockThread()
    }

    fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: listOfNotNull(p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)).joinToString(" ").ifBlank { "MIDI-Gerät ${info.id}" }
    }

    /** Alle Geräte neu öffnen (nach Änderung der Geräte-Auswahl). */
    fun reopen() {
        val m = manager ?: return
        handler?.post {
            synchronized(devices) {
                for (p in outPorts) try { p.close() } catch (ignored: Exception) {}
                for (p in inPorts) try { p.close() } catch (ignored: Exception) {}
                for (d in devices) try { d.close() } catch (ignored: Exception) {}
                outPorts.clear(); inPorts.clear(); devices.clear()
                deviceNames = emptyList()
            }
            @Suppress("DEPRECATION")
            for (info in m.devices) open(info)
        }
    }

    /** Alle angeschlossenen Geräte (auch ausgeschaltete) – für die Einstellungen. */
    fun allDevices(): List<String> {
        val m = manager ?: return emptyList()
        @Suppress("DEPRECATION")
        return m.devices.map { nameOf(it) }.distinct()
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        val name = nameOf(info)
        if (name in disabled) return
        // eigene virtuelle Ports und reine Synthesizer überspringen wir nicht – die Auswahl macht der Nutzer
        m.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            synchronized(devices) {
                devices.add(dev)
                // Ausgänge des Geräts = was der Controller sendet
                for (pi in info.ports) {
                    if (pi.type == android.media.midi.MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                        dev.openOutputPort(pi.portNumber)?.let { op ->
                            op.connect(Parser(name))
                            outPorts.add(op)
                        }
                    } else if (pi.type == android.media.midi.MidiDeviceInfo.PortInfo.TYPE_INPUT) {
                        dev.openInputPort(pi.portNumber)?.let { inPorts.add(it) }
                    }
                }
                deviceNames = (deviceNames + name).distinct()
            }
        }, handler)
    }

    private fun closeDevice(info: MidiDeviceInfo) {
        synchronized(devices) {
            val it = devices.iterator()
            while (it.hasNext()) {
                val d = it.next()
                if (d.info.id == info.id) {
                    try { d.close() } catch (ignored: Exception) {}
                    it.remove()
                }
            }
            deviceNames = devices.map { nameOf(it.info) }.distinct()
        }
    }

    // ---------------------------------------------------------------- Eingang

    private class Parser(val dev: String) : MidiReceiver() {
        private var running = 0
        private val data = IntArray(2)
        private var have = 0
        private var need = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) {
                    realtime(b, timestamp)
                    continue
                }
                if (b >= 0x80) {
                    if (b >= 0xF0) {
                        running = 0
                        need = 0
                        continue
                    }
                    running = b
                    have = 0
                    need = when (b and 0xF0) { 0xC0, 0xD0 -> 1; else -> 2 }
                    continue
                }
                if (running == 0) continue
                data[have++] = b
                if (have >= need) {
                    curTs = timestamp
                    message(running, data[0], if (need > 1) data[1] else 0, dev)
                    have = 0
                }
            }
        }
    }

    /** Zeitstempel des gerade verarbeiteten MIDI-Ereignisses (System.nanoTime-Basis). */
    @Volatile private var curTs = 0L

    private fun chOk(ch: Int): Boolean = channel == 0 || ch == channel

    private fun message(status: Int, d1: Int, d2: Int, dev: String) {
        if (!enabled) return
        val type = status and 0xF0
        val ch = (status and 0x0F) + 1
        when (type) {
            0x90, 0x80 -> {
                val on = type == 0x90 && d2 > 0
                lastEvent = "$dev · Kanal $ch · ${if (on) "Note an" else "Note aus"} ${noteName(d1)} ($d1)" + if (on) " · Anschlag $d2" else ""
                val code = (ch - 1) * 128 + d1
                if (on && learnBtn >= 0) {
                    val b = learnBtn
                    for (k in djBtn.indices) if (k != b && djBtn[k] == code) djBtn[k] = -1
                    djBtn[b] = code
                    learnBtn = -1
                    onHint?.invoke("${djBtnName(b)} ← ${noteLabel(code)}")
                    return
                }
                val bi = djBtn.indexOf(code)
                if (bi >= 0) {
                    djButton(bi, on)
                    return
                }
                if (on && learnPad >= 0) {
                    val p = learnPad
                    for (k in 0 until 12) if (k != p && padNote(k) == d1) padNotes[k] = -2
                    padNotes[p] = d1
                    learnPad = -1
                    onHint?.invoke("Pad ${p + 1} ← ${noteName(d1)} ($d1)")
                    return
                }
                if (keysChannel > 0 && ch == keysChannel && keysChannel != channel) {
                    keys(d1, on, d2)
                    return
                }
                if (!chOk(ch)) return
                val pad = (0 until 12).firstOrNull { padNote(it) == d1 }
                if (pad != null) {
                    pad(pad, on, d2)
                } else if (keysChannel == 0 || channel == 0 && keysChannel == ch) {
                    keys(d1, on, d2)
                }
            }
            0xB0 -> {
                lastEvent = "$dev · Kanal $ch · CC $d1 = $d2"
                if (learnCc >= 0) {
                    val t = learnCc
                    // mit Kanal lernen: DJ-Controller nutzen dieselbe CC-Nummer je Deck auf eigenem Kanal
                    val code = 128 + (ch - 1) * 128 + d1
                    for (k in ccMap.indices) if (k != t && ccMap[k] == code) ccMap[k] = -1
                    ccMap[t] = code
                    learnCc = -1
                    onHint?.invoke("${TARGETS[t]} ← ${ccLabel(code)}")
                    return
                }
                if (!chOk(ch)) return
                if (d1 == 123 || d1 == 120) { // All Notes Off
                    Engine.allNotesOff()
                    return
                }
                val v = d2 / 127f
                val code = 128 + (ch - 1) * 128 + d1
                for (t in ccMap.indices) if (ccMap[t] == d1 || ccMap[t] == code) cc(t, v, d2)
            }
            0xC0 -> {
                lastEvent = "$dev · Kanal $ch · Programm ${d1 + 1}"
                if (programScenes && chOk(ch)) Engine.launchScene(d1 % 8)
            }
            0xE0 -> {
                lastEvent = "$dev · Kanal $ch · Pitchbend"
            }
        }
    }

    private fun pad(p: Int, on: Boolean, vel: Int) {
        if (on) {
            if (padHeld[p]) return
            padHeld[p] = true
            val v = if (velocity) max(0.05f, vel / 127f) else 1f
            val r = Engine.liveDown(p, v, curTs)
            padLaunched[p] = (r and Engine.LIVE_LAUNCHED) != 0
            if ((r and Engine.LIVE_SHOT) != 0 && Engine.repeatOn) Engine.repeatPadDown(p)
        } else {
            if (!padHeld[p]) return
            padHeld[p] = false
            Engine.repeatPadUp(p)
            Engine.liveUp(p, padLaunched[p])
        }
        onPad?.invoke(p, on)
    }

    private fun keys(note: Int, on: Boolean, vel: Int) {
        val p = when {
            keysPad >= 0 -> keysPad
            openRollPad >= 0 -> openRollPad
            else -> 0
        }
        val semi = note - 60
        if (on) {
            keyVoices.remove(note)?.let { Engine.noteOff(it) }
            keyVoices[note] = Engine.noteOn(p, semi)
            Engine.recordNoteOn(p, semi)?.let { keyEvents[note] = it }
        } else {
            keyVoices.remove(note)?.let { Engine.noteOff(it) }
            keyEvents.remove(note)?.let { Engine.recordNoteOff(it) }
        }
        onKeysNote?.invoke(semi, on)
    }

    private fun cc(t: Int, v: Float, raw: Int = (v * 127).toInt()) {
        val a = Dj.decks[0]
        val b = Dj.decks[1]
        when (t) {
            0 -> { Engine.fxX = v; Engine.fxTouch = true; ccTouch() }
            1 -> { Engine.fxY = v; Engine.fxTouch = true; ccTouch() }
            2 -> Engine.knobs[0] = v
            3 -> Engine.knobs[1] = v
            4 -> Engine.knobs[2] = v
            5 -> Engine.knobs[3] = v
            6 -> Engine.swing = v * 0.5f
            7 -> Dj.xfade = v
            8 -> a.fader = v
            9 -> b.fader = v
            10 -> a.filter = v
            11 -> b.filter = v
            12 -> a.pitch = v * 2f - 1f
            13 -> b.pitch = v * 2f - 1f
            14 -> Engine.monitorGain = v * 1.5f
            15 -> a.fxAmt = v
            16 -> b.fxAmt = v
            17 -> a.hi = v
            18 -> a.mid = v
            19 -> a.lo = v
            20 -> b.hi = v
            21 -> b.mid = v
            22 -> b.lo = v
            23 -> a.gain = v
            24 -> b.gain = v
            25, 26, 27, 28 -> jog(if (t == 25 || t == 27) 0 else 1, raw, t >= 27)
            29 -> Dj.cueGain = v * 1.5f
        }
    }

    // ---------------------------------------------------------------- DJ-Controller

    private val bendOff = arrayOf(Runnable { Dj.decks[0].bend = 0f }, Runnable { Dj.decks[1].bend = 0f })
    private val scratchOff = arrayOf(Runnable { if (!jogTouch[0]) Dj.scratchEnd(Dj.decks[0]) }, Runnable { if (!jogTouch[1]) Dj.scratchEnd(Dj.decks[1]) })
    /** Jog-Rad-Kodierung: 0 = Mitte 64 (Pioneer, Numark), 1 = 1/127 (Hercules, Traktor). */
    @Volatile var jogMode = 0

    /** Jog-Rad (relativ): berührt bzw. „oben“ = Scratchen, Rand = Tempo kurz biegen (Angleichen per Hand). */
    private fun jog(k: Int, raw: Int, top: Boolean) {
        val d = Dj.decks[k]
        if (!d.loaded()) return
        val dv = if (jogMode == 0) raw - 64 else if (raw < 64) raw else raw - 128
        if (dv == 0) return
        val h = handler
        if (jogTouch[k] || top) {
            if (!d.scratching) Dj.scratchStart(d)
            d.scratchTarget = (d.scratchTarget + dv * d.srcRate / 420.0).coerceIn(0.0, (d.len - 2).toDouble())
            if (!jogTouch[k] && h != null) {
                // ohne Berühr-Taste: Scratchen endet, wenn das Rad stillsteht
                h.removeCallbacks(scratchOff[k])
                h.postDelayed(scratchOff[k], 150L)
            }
            return
        }
        d.bend = (dv * 0.006f).coerceIn(-0.4f, 0.4f)
        if (h != null) {
            h.removeCallbacks(bendOff[k])
            h.postDelayed(bendOff[k], 70L)
        }
    }

    private fun djButton(i: Int, on: Boolean) {
        val k = i / DJ_PER_DECK
        val b = i % DJ_PER_DECK
        val d = Dj.decks[k]
        when (b) {
            0 -> if (on) { if (!(Dj.gigMode && Dj.onAir(d) && !gigSecond(k))) Dj.togglePlay(d) }
            1 -> {
                if (!d.loaded()) return
                if (on) {
                    if (d.playing) Dj.cuePress(d)
                    else if (abs(d.pos - d.cuePoint) < d.srcRate * 0.02) { cueHeld[k] = true; d.playing = true }
                    else Dj.cuePress(d)
                } else if (cueHeld[k]) {
                    cueHeld[k] = false
                    d.playing = false
                    d.seekTo = d.cuePoint
                }
            }
            2 -> if (on) Dj.toggleSync(d)
            3 -> if (on) d.cue = !d.cue
            4, 5, 6, 7 -> if (on) Dj.hotCue(d, b - 4, false)
            8 -> if (on) Dj.toggleLoop(d)
            9 -> if (on) Dj.setLoopBeats(d, d.loopBeats / 2f)
            10 -> if (on) Dj.setLoopBeats(d, d.loopBeats * 2f)
            11 -> if (on) Dj.beatJump(d, -d.jumpBars)
            12 -> if (on) Dj.beatJump(d, d.jumpBars)
            13 -> if (on) { d.keylock = !d.keylock; d.gReset = true }
            14 -> if (on) Dj.echoOut(d)
            15 -> if (on) Dj.brake(d, false)
            16 -> {
                jogTouch[k] = on
                if (on) Dj.scratchStart(d) else Dj.scratchEnd(d)
            }
            17 -> if (on) Dj.rollStart(d, 0.5f) else Dj.rollEnd(d)
            18 -> if (on) d.slipOn = !d.slipOn
        }
        onDjButton?.invoke()
    }

    private val gigTap = LongArray(2)

    /** Gig-Modus: zweiter Druck innerhalb 0,5 s stoppt ein hörbares Deck. */
    private fun gigSecond(k: Int): Boolean {
        val now = System.currentTimeMillis()
        val ok = now - gigTap[k] < 500
        gigTap[k] = if (ok) 0 else now
        if (!ok) onHint?.invoke("GIG: Deck ${"AB"[k]} ist zu hören – zum Stoppen nochmal drücken")
        return ok
    }

    /** DJ-Taste ausgelöst (Oberfläche neu zeichnen). */
    @Volatile var onDjButton: (() -> Unit)? = null

    private val releaseTouch = Runnable { Engine.fxTouch = false }

    /** XY per Regler: Effekt bleibt an, solange gedreht wird. */
    private fun ccTouch() {
        val h = handler ?: return
        h.removeCallbacks(releaseTouch)
        h.postDelayed(releaseTouch, 600L)
    }

    // ---------------------------------------------------------------- Clock & Transport

    private val clockTimes = LongArray(24)
    private var clockIdx = 0
    private var clockCount = 0
    @Volatile var extBpm = 0f
        private set

    private fun realtime(b: Int, ts: Long) {
        if (!enabled) return
        when (b) {
            0xF8 -> if (clockIn) {
                val now = if (ts > 0) ts else System.nanoTime()
                clockTimes[clockIdx] = now
                clockIdx = (clockIdx + 1) % 24
                clockCount++
                if (clockCount >= 24 && clockCount % 12 == 0) {
                    val oldest = clockTimes[clockIdx]
                    val span = now - oldest
                    if (span > 0) {
                        val bpm = (60e9 * 23 / 24 / span).toFloat()
                        if (bpm in 40f..240f) {
                            extBpm = bpm
                            val cur = Engine.currentBpm()
                            if (abs(cur - bpm) > 0.4f) Engine.setBpm(Math.round(bpm * 10f) / 10f)
                        }
                    }
                }
            }
            0xFA -> if (transportIn) { clockCount = 0; Engine.transportPlay(false) }
            0xFB -> if (transportIn) Engine.transportPlay(false)
            0xFC -> if (transportIn) Engine.transportStop()
        }
    }

    private fun sendAll(bytes: ByteArray, at: Long) {
        synchronized(devices) {
            for (p in inPorts) try { p.send(bytes, 0, bytes.size, at) } catch (ignored: Exception) {}
        }
    }

    private var clockThread: Thread? = null

    // ---------------------------------------------------------------- MIDI-Ausgang der Instrumente

    /** Instrumente, die Noten nach außen senden können. */
    val OUT_NAMES = arrayOf("303 (Acid-Bass)", "909 (Drums)", "Synth 1", "Synth 2", "Synth 3")
    /** Je Instrument: 0 = nur intern, 1 = nur extern (internes stumm), 2 = beides. */
    val outMode = IntArray(5)
    /** MIDI-Kanal 1..16 je Instrument. */
    val outCh = intArrayOf(1, 10, 2, 3, 4)
    /** Feinabgleich (ms): positiv = Noten früher senden (gleicht die Verzögerung des externen Geräts aus). */
    @Volatile var outEarlyMs = 0f
    /** Drum-Noten (General MIDI) je Drum-Stimme BD SD LT MT HT RS CP CH OH CR RD. */
    val DRUM_NOTES = intArrayOf(36, 38, 41, 45, 48, 37, 39, 42, 46, 49, 51)

    fun outActive(i: Int): Boolean = outMode[i] != 0 && hasOutputs()
    /** Spielt das interne Instrument (nicht nur extern)? */
    fun internalOn(i: Int): Boolean = outMode[i] != 1 || !hasOutputs()
    fun hasOutputs(): Boolean = inPorts.size > 0  // ohne Sperre: wird im Audio-Thread abgefragt

    private class OutEv(val at: Long, val b0: Int, val b1: Int, val b2: Int)
    private val outQ = java.util.concurrent.LinkedBlockingQueue<OutEv>()
    /** Klingende Noten je Kanal (für „alle aus“ beim Stopp). */
    private val sounding = Array(16) { BooleanArray(128) }
    private var outThread: Thread? = null

    /** Note an/aus für Instrument [i] zum Zeitpunkt [nanos] (System.nanoTime-Basis). Läuft ohne Warten (Audio-Thread). */
    fun outNote(i: Int, nanos: Long, note: Int, vel: Int, on: Boolean) {
        if (note !in 0..127) return
        val ch = (outCh[i] - 1).coerceIn(0, 15)
        val at = nanos - (outEarlyMs * 1_000_000f).toLong()
        outQ.offer(OutEv(at, (if (on) 0x90 else 0x80) or ch, note, if (on) vel.coerceIn(1, 127) else 0))
        ensureOutThread()
    }

    /** Alle klingenden Noten beenden (Stopp, Ausschalten). */
    fun outAllOff() {
        outQ.offer(OutEv(-1L, 0, 0, 0))
        ensureOutThread()
    }

    private fun ensureOutThread() {
        if (outThread != null) return
        synchronized(this) {
            if (outThread != null) return
            outThread = Thread({
                val buf = ByteArray(3)
                while (true) {
                    val e = try { outQ.take() } catch (x: InterruptedException) { return@Thread }
                    try {
                        if (e.at == -1L) {
                            // alle aus: gemerkte Noten einzeln + „All Notes Off“ je benutztem Kanal
                            for (ch in 0 until 16) {
                                var any = false
                                for (n in 0 until 128) if (sounding[ch][n]) { sounding[ch][n] = false; any = true; sendAll(byteArrayOf((0x80 or ch).toByte(), n.toByte(), 0), 0) }
                                if (any) sendAll(byteArrayOf((0xB0 or ch).toByte(), 123, 0), 0)
                            }
                            continue
                        }
                        val ch = e.b0 and 0x0F
                        if (e.b0 and 0xF0 == 0x90) sounding[ch][e.b1] = true else sounding[ch][e.b1] = false
                        buf[0] = e.b0.toByte(); buf[1] = e.b1.toByte(); buf[2] = e.b2.toByte()
                        // Zeitstempel in der Zukunft: Android plant die Ausgabe genau
                        sendAll(buf.copyOf(), if (e.at > System.nanoTime()) e.at else 0L)
                    } catch (ignored: Exception) {}
                }
            }, "MarvPAD-MidiOut").also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY; it.start() }
        }
    }

    /** MIDI-Clock senden (24 pro Viertel) + Start/Stopp mit dem Transport. */
    private fun startClockThread() {
        if (clockThread != null) return
        clockThread = Thread({
            val tick = byteArrayOf(0xF8.toByte())
            var wasPlaying = false
            var next = System.nanoTime()
            while (true) {
                try {
                    val bpm = Engine.currentBpm()
                    val playing = Engine.seqPlaying
                    if (!clockOut || bpm <= 0f || inPorts.isEmpty()) {
                        wasPlaying = false
                        Thread.sleep(50)
                        next = System.nanoTime()
                        continue
                    }
                    if (playing != wasPlaying) {
                        sendAll(byteArrayOf((if (playing) 0xFA else 0xFC).toByte()), 0)
                        wasPlaying = playing
                    }
                    val period = (60e9 / bpm / 24).toLong()
                    val now = System.nanoTime()
                    if (next < now - period * 4) next = now
                    // Zeitstempel in der Zukunft: Android plant die Ausgabe genau
                    sendAll(tick, next)
                    next += period
                    val sleep = (next - System.nanoTime() - 2_000_000L) / 1_000_000L
                    if (sleep > 0) Thread.sleep(sleep)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (ignored: Exception) {
                    try { Thread.sleep(50) } catch (e: InterruptedException) { return@Thread }
                }
            }
        }, "MarvPAD-Clock").also {
            it.isDaemon = true
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }
}
