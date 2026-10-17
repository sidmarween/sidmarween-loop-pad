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
        "Deck A Pitch", "Deck B Pitch", "Mikrofon (Mithören)", "Deck A Effekt", "Deck B Effekt"
    )
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
                    message(running, data[0], if (need > 1) data[1] else 0, dev)
                    have = 0
                }
            }
        }
    }

    private fun chOk(ch: Int): Boolean = channel == 0 || ch == channel

    private fun message(status: Int, d1: Int, d2: Int, dev: String) {
        if (!enabled) return
        val type = status and 0xF0
        val ch = (status and 0x0F) + 1
        when (type) {
            0x90, 0x80 -> {
                val on = type == 0x90 && d2 > 0
                lastEvent = "$dev · Kanal $ch · ${if (on) "Note an" else "Note aus"} ${noteName(d1)} ($d1)" + if (on) " · Anschlag $d2" else ""
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
                    for (k in ccMap.indices) if (k != t && ccMap[k] == d1) ccMap[k] = -1
                    ccMap[t] = d1
                    learnCc = -1
                    onHint?.invoke("${TARGETS[t]} ← CC $d1")
                    return
                }
                if (!chOk(ch)) return
                if (d1 == 123 || d1 == 120) { // All Notes Off
                    Engine.allNotesOff()
                    return
                }
                val v = d2 / 127f
                for (t in ccMap.indices) if (ccMap[t] == d1) cc(t, v)
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
            val r = Engine.liveDown(p, v)
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

    private fun cc(t: Int, v: Float) {
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
        }
    }

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
