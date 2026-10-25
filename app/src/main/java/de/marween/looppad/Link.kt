package de.marween.looppad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * Mehrere Handys koppeln: ein Handy ist der „Host“ (dort läuft der Klang), weitere Handys sind
 * Bedien-Oberflächen (Decks, Mixer, Pads, Effekte, Drums …). Übertragen werden nur Steuerbefehle
 * und Zustände, kein Audio. Verbindung über WLAN (gleiches Netz oder Hotspot, schnell) oder Bluetooth.
 * Protokoll: Textzeilen. Die Uhren werden per Ping abgeglichen, damit Anschläge vom anderen Handy
 * mit fester Verzögerung (jitterfrei) erklingen.
 */
object Link {
    const val PORT = 47820
    const val SERVICE = "_marvpad._tcp."
    val BT_UUID: UUID = UUID.fromString("6f2c9a0e-4b7d-4c1a-9e55-3a7b1d2c8f01")

    /** Bedien-Oberflächen. */
    val SURFACES = arrayOf("DECKS", "MIXER", "PADS", "FX", "DRUMS", "PAD-MIXER")
    val SURFACE_NAMES = arrayOf("🎧 DJ-Decks", "🎚 DJ-Mixer", "▦ Pads & Transport", "✨ Effekte (XY)", "🥁 Drum-Pads", "🎛 Pad-Mixer")

    /** Vorlagen: wie die Steuerung verteilt wird (Index 0 = dieses Handy/Host, dann die gekoppelten in Reihenfolge). */
    class Preset(val name: String, val host: String, val remotes: IntArray)
    val PRESETS = listOf(
        Preset("DJ: Decks hier · Mixer auf Handy 2", "DJ-Decks", intArrayOf(1, 0, 3)),
        Preset("DJ: Mixer hier · beide Decks auf Handy 2", "DJ-Modus", intArrayOf(0, 3, 5)),
        Preset("DJ + Sampler: DJ hier · Pads auf Handy 2 · Effekte auf Handy 3", "DJ-Modus", intArrayOf(2, 3, 1)),
        Preset("Live-Looper: Pads hier · Effekte (XY) auf Handy 2 · Mixer auf Handy 3", "Pads", intArrayOf(3, 5, 4)),
        Preset("Band: Pads hier · Drums auf Handy 2 · Effekte auf Handy 3 · Mixer auf Handy 4", "Pads", intArrayOf(4, 3, 5, 0)),
        Preset("Zwei Spieler: Pads hier · Pads auf Handy 2 (gleiche Pads) · Drums auf Handy 3", "Pads", intArrayOf(2, 4, 3)),
        Preset("Mischpult extra: Pads hier · Pad-Mixer auf Handy 2 · Effekte auf Handy 3", "Pads", intArrayOf(5, 3, 2))
    )

    // ================================================================ Gemeinsame Verbindung

    class Conn(val name: String, private val inp: InputStream, private val out: OutputStream, private val closer: () -> Unit, val viaBt: Boolean) {
        @Volatile var alive = true
        @Volatile var surface = -1
        @Volatile var peerName = name
        /** Uhren-Abgleich: Host-Zeit = fremde Zeit − offset; beste Messung = kleinste Laufzeit. */
        @Volatile var offset = 0L
        @Volatile var bestRtt = Long.MAX_VALUE
        @Volatile var rttMs = 0f
        private val lock = Any()

        fun send(line: String) {
            if (!alive) return
            try {
                synchronized(lock) {
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: Exception) { close() }
        }

        fun reader(): BufferedReader = BufferedReader(InputStreamReader(inp, Charsets.UTF_8), 8192)

        fun close() {
            if (!alive) return
            alive = false
            try { closer() } catch (ignored: Exception) {}
        }
    }

    // ================================================================ HOST

    @Volatile var hosting = false
        private set
    val clients = CopyOnWriteArrayList<Conn>()
    private var server: ServerSocket? = null
    private var btServer: BluetoothServerSocket? = null
    private var nsd: NsdManager? = null
    private var nsdReg: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile var onClientsChanged: (() -> Unit)? = null
    @Volatile var onHostHint: ((String) -> Unit)? = null
    /** Host: was dieses Handy selbst zeigt (für die Vorlage) – setzt die Oberfläche. */
    @Volatile var onHostSurface: ((String) -> Unit)? = null

    fun deviceName(ctx: Context): String = try {
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") ?: android.os.Build.MODEL
    } catch (e: Exception) { android.os.Build.MODEL }

    /** Eigene WLAN-/Hotspot-Adressen (für die manuelle Eingabe am anderen Handy). */
    fun localIps(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) out.add(a.hostAddress ?: continue)
            }
        } catch (ignored: Exception) {}
        return out
    }

    @SuppressLint("MissingPermission")
    fun startHost(ctx: Context, withBt: Boolean) {
        if (hosting) return
        hosting = true
        // WLAN: TCP-Server + im Netz bekannt machen (mDNS)
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                server = ss
                while (hosting) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    val c = Conn(s.inetAddress?.hostAddress ?: "WLAN", s.getInputStream(), s.getOutputStream(), { s.close() }, false)
                    hostServe(c)
                }
            } catch (e: Exception) {
                if (hosting) onHostHint?.invoke("WLAN-Server: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("marvpad").apply { setReferenceCounted(false); acquire() }
            val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply { serviceName = "Marv PAD – " + deviceName(ctx); serviceType = SERVICE; port = PORT }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            nsdReg = l
        } catch (ignored: Exception) {}
        // Bluetooth: auf gekoppelte Handys warten
        if (withBt) Thread {
            try {
                val ad = btAdapter(ctx) ?: return@Thread
                val bs = ad.listenUsingRfcommWithServiceRecord("Marv PAD", BT_UUID)
                btServer = bs
                while (hosting) {
                    val s = bs.accept() ?: break
                    val c = Conn(try { s.remoteDevice?.name ?: "Bluetooth" } catch (e: SecurityException) { "Bluetooth" }, s.inputStream, s.outputStream, { s.close() }, true)
                    hostServe(c)
                }
            } catch (e: Exception) {
                if (hosting) onHostHint?.invoke("Bluetooth: ${e.message ?: "nicht verfügbar"}")
            }
        }.apply { isDaemon = true }.start()
        // Zustände senden (~25 pro Sekunde) und Uhren abgleichen
        Thread {
            var n = 0
            while (hosting) {
                try {
                    if (clients.isNotEmpty()) {
                        val st = "S " + hostState().toString()
                        for (c in clients) {
                            if (!c.alive) { clients.remove(c); onClientsChanged?.invoke(); continue }
                            c.send(st)
                            if (n % 12 == 0) c.send("PING ${System.nanoTime()}")
                        }
                        // Wellenformen nur bei Bedarf (neuer Track)
                        for (k in 0..1) {
                            val d = Dj.decks[k]
                            val sig = d.title.hashCode() * 31 + d.len
                            if (sig != waveSig[k]) {
                                waveSig[k] = sig
                                val w = overviewOf(d)
                                for (c in clients) c.send("W $k $w")
                            }
                        }
                    }
                    n++
                    Thread.sleep(40)
                } catch (ignored: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    private val waveSig = IntArray(2)

    /** Wellenform-Übersicht eines Decks: 200 Werte 0..9 als Text. */
    private fun overviewOf(d: Dj.Deck): String {
        val pk = d.peaks
        if (!d.loaded() || pk.isEmpty()) return "-"
        val sb = StringBuilder(200)
        for (i in 0 until 200) {
            val a = (i.toLong() * pk.size / 200).toInt()
            val b = max(a + 1, ((i + 1).toLong() * pk.size / 200).toInt())
            var m = 0
            for (j in a until min(b, pk.size)) m = max(m, pk[j].toInt() and 0xFF)
            sb.append(('0' + (m * 9 / 255)))
        }
        return sb.toString()
    }

    private fun hostServe(c: Conn) {
        clients.add(c)
        // nächste freie Oberfläche als Vorschlag (die Vorlage verteilt neu)
        c.surface = defaultSurfaceFor(clients.indexOf(c))
        c.send("SURF ${c.surface}")
        c.send("PING ${System.nanoTime()}")
        onClientsChanged?.invoke()
        onHostHint?.invoke("📡 ${c.peerName} verbunden")
        Thread {
            try {
                val r = c.reader()
                while (c.alive && hosting) {
                    val line = r.readLine() ?: break
                    try { hostCommand(c, line) } catch (ignored: Exception) {}
                }
            } catch (ignored: Exception) {
            } finally {
                c.close()
                clients.remove(c)
                onClientsChanged?.invoke()
                onHostHint?.invoke("📡 ${c.peerName} getrennt")
            }
        }.apply { isDaemon = true }.start()
    }

    @Volatile var currentPreset = -1
    private fun defaultSurfaceFor(i: Int): Int {
        val p = PRESETS.getOrNull(currentPreset)
        return p?.remotes?.getOrNull(i) ?: intArrayOf(2, 3, 1, 4, 5, 0)[i % 6]
    }

    /** Vorlage anwenden: Handys in Verbindungs-Reihenfolge bekommen ihre Oberfläche. */
    fun applyPreset(i: Int) {
        currentPreset = i
        val p = PRESETS[i]
        for ((k, c) in clients.withIndex()) {
            c.surface = p.remotes.getOrNull(k) ?: c.surface
            c.send("SURF ${c.surface}")
        }
        onHostSurface?.invoke(p.host)
        onClientsChanged?.invoke()
    }

    fun setSurface(c: Conn, s: Int) {
        c.surface = s
        c.send("SURF $s")
        onClientsChanged?.invoke()
    }

    fun stopHost() {
        hosting = false
        for (c in clients) { c.send("BYE"); c.close() }
        clients.clear()
        try { server?.close() } catch (ignored: Exception) {}
        try { btServer?.close() } catch (ignored: Exception) {}
        try { nsdReg?.let { nsd?.unregisterService(it) } } catch (ignored: Exception) {}
        try { multicastLock?.release() } catch (ignored: Exception) {}
        server = null; btServer = null; nsdReg = null
        onClientsChanged?.invoke()
    }

    /** Alles, was die Oberflächen anzeigen. */
    private fun hostState(): JSONObject {
        val j = JSONObject()
        j.put("bpm", Engine.currentBpm().toDouble())
        j.put("play", Engine.seqPlaying)
        j.put("rec", Engine.seqRecording)
        j.put("vol", Engine.knobs[3].toDouble())
        j.put("fxX", Engine.fxX.toDouble()); j.put("fxY", Engine.fxY.toDouble()); j.put("fxT", Engine.fxTouch)
        j.put("fx", Fx.NAMES[Engine.fxType.coerceIn(0, Fx.COUNT - 1)])
        j.put("pxA", Engine.xfA); j.put("pxB", Engine.xfB); j.put("pxP", Engine.xfPos.toDouble())
        val pa = JSONArray()
        for (p in 0 until 12) {
            val o = JSONArray()
            val st = Engine.state(p)
            o.put(st.ordinal)
            o.put(Math.round(Engine.levels[p] * 100))
            o.put(Engine.padName(p) ?: "")
            o.put(if (Engine.isMuted(p)) 1 else 0)
            o.put(if (Engine.solo[p]) 1 else 0)
            o.put(Math.round(Engine.padGain(p) * 100))
            o.put(if (Engine.isOneShot(p)) 1 else 0)
            pa.put(o)
        }
        j.put("pads", pa)
        val da = JSONArray()
        for (k in 0..1) {
            val d = Dj.decks[k]
            val o = JSONObject()
            o.put("ok", d.loaded()); o.put("t", d.title)
            o.put("pos", if (d.len > 0) d.pos / d.len else 0.0)
            o.put("rem", if (d.loaded()) (d.len - d.pos) / d.srcRate / max(0.01, d.ratio) else 0.0)
            o.put("bpm", d.effBpm().toDouble()); o.put("key", Keys.camelot(d.effKey()))
            o.put("pl", d.playing); o.put("sy", d.sync); o.put("hp", d.cue); o.put("kl", d.keylock)
            o.put("lp", d.loopOn); o.put("lb", d.loopBeats.toDouble()); o.put("rb", d.rollBeats.toDouble()); o.put("sl", d.slipOn)
            o.put("jb", d.jumpBars.toDouble())
            o.put("hot", JSONArray().apply { d.hot.forEach { put(if (it >= 0) 1 else 0) } })
            o.put("fd", d.fader.toDouble()); o.put("hi", d.hi.toDouble()); o.put("mi", d.mid.toDouble()); o.put("lo", d.lo.toDouble())
            o.put("fl", d.filter.toDouble()); o.put("gn", d.gain.toDouble()); o.put("pt", d.pitch.toDouble())
            o.put("lv", Math.round(d.level * 100)); o.put("eo", d.echoOn); o.put("sw", d.sweepMode); o.put("ce", d.censor)
            if (d.loaded() && d.bpm > 0f) {
                val (inP, toNext) = Dj.phraseInfo(d, d.pos)
                o.put("ph", "${inP + 1}/16 · noch ${Math.ceil(toNext / 4.0 - 1e-6).toInt()} T")
            }
            da.put(o)
        }
        j.put("decks", da)
        j.put("xf", Dj.xfade.toDouble())
        return j
    }

    /** Befehl einer Oberfläche ausführen (Host). */
    private fun hostCommand(c: Conn, line: String) {
        val a = line.split(' ')
        fun f(i: Int) = a.getOrNull(i)?.toFloatOrNull() ?: 0f
        fun n(i: Int) = a.getOrNull(i)?.toIntOrNull() ?: 0
        fun l(i: Int) = a.getOrNull(i)?.toLongOrNull() ?: 0L
        when (a[0]) {
            "HELLO" -> { c.peerName = line.removePrefix("HELLO ").take(40); onClientsChanged?.invoke() }
            "PONG" -> {
                // PONG <hostSendNanos> <remoteNanos>
                val t0 = l(1)
                val now = System.nanoTime()
                val rtt = now - t0
                if (rtt in 0..2_000_000_000L) {
                    c.rttMs = rtt / 1e6f
                    if (rtt <= c.bestRtt * 1.3 || rtt < 8_000_000L) {
                        c.bestRtt = min(c.bestRtt, rtt)
                        c.offset = l(2) - (t0 + rtt / 2)
                    }
                }
            }
            "PAD" -> {
                // PAD p down|up vel remoteNanos
                val p = n(1)
                if (p !in 0 until 12) return
                if (a.getOrNull(2) == "d") {
                    val ts = l(4).let { if (it > 0L && c.bestRtt < Long.MAX_VALUE) it - c.offset else 0L }
                    val r = Engine.liveDown(p, f(3).coerceIn(0.05f, 1f), ts)
                    remoteLaunched[p] = (r and Engine.LIVE_LAUNCHED) != 0
                } else Engine.liveUp(p, remoteLaunched[p])
            }
            "MUTE" -> Engine.toggleMute(n(1))
            "SOLO" -> Engine.toggleSolo(n(1))
            "PGAIN" -> Engine.setPadGain(n(1), f(2))
            "PXF" -> Engine.xfPos = f(1).coerceIn(0f, 1f)
            "T" -> when (a.getOrNull(1)) {
                "PLAY" -> if (Engine.seqPlaying) Engine.seqStop() else Engine.seqPlay()
                "STOP" -> Engine.seqStop()
                "REC" -> Engine.seqToggleRec()
                "UNDO" -> Engine.undo()
            }
            "VOL" -> Engine.knobs[3] = f(1).coerceIn(0f, 1f)
            "XY" -> { Engine.fxX = f(1).coerceIn(0f, 1f); Engine.fxY = f(2).coerceIn(0f, 1f); Engine.fxTouch = n(3) == 1 }
            "FX" -> {
                val t = Math.floorMod(Engine.fxType + n(1), Fx.COUNT)
                Engine.selectFx(t)
            }
            "XFADE" -> Dj.xfade = f(1).coerceIn(0f, 1f)
            "DK" -> deckCommand(n(1), a)
        }
        Midi.onDjButton?.invoke()
    }

    private val remoteLaunched = BooleanArray(12)
    private val remoteCueHeld = BooleanArray(2)

    private fun deckCommand(k: Int, a: List<String>) {
        if (k !in 0..1) return
        val d = Dj.decks[k]
        val v = a.getOrNull(3)?.toFloatOrNull() ?: 0f
        when (a.getOrNull(2)) {
            "PLAY" -> Dj.togglePlay(d)
            "CUE" -> {
                if (!d.loaded()) return
                if (a.getOrNull(3) == "d") {
                    if (d.playing) Dj.cuePress(d)
                    else if (kotlin.math.abs(d.pos - d.cuePoint) < d.srcRate * 0.02) { remoteCueHeld[k] = true; d.playing = true }
                    else Dj.cuePress(d)
                } else if (remoteCueHeld[k]) { remoteCueHeld[k] = false; d.playing = false; d.seekTo = d.cuePoint }
            }
            "SYNC" -> Dj.toggleSync(d)
            "HP" -> d.cue = !d.cue
            "KL" -> { d.keylock = !d.keylock; d.gReset = true }
            "HOT" -> Dj.hotCue(d, v.toInt().coerceIn(0, d.hot.size - 1), false)
            "HOTDEL" -> Dj.hotCue(d, v.toInt().coerceIn(0, d.hot.size - 1), true)
            "LOOP" -> Dj.toggleLoop(d)
            "LOOPH" -> Dj.setLoopBeats(d, d.loopBeats / 2f)
            "LOOPD" -> Dj.setLoopBeats(d, d.loopBeats * 2f)
            "JUMP" -> Dj.beatJump(d, v)
            "JSIZE" -> d.jumpBars = v.coerceIn(0.25f, 32f)
            "ROLL" -> if (v > 0f) Dj.rollStart(d, v) else Dj.rollEnd(d)
            "SLIP" -> d.slipOn = !d.slipOn
            "ECHO" -> Dj.echoOut(d)
            "BRAKE" -> Dj.brake(d, false)
            "SPIN" -> Dj.brake(d, true)
            "BUILD" -> Dj.toggleSweep(d, 1)
            "SWEEP" -> Dj.toggleSweep(d, 2)
            "CENSOR" -> Dj.censorOn(d, v > 0.5f)
            "FXSTOP" -> Dj.stopAllFx(d)
            "FADER" -> d.fader = v.coerceIn(0f, 1f)
            "HI" -> d.hi = v.coerceIn(0f, 1f)
            "MID" -> d.mid = v.coerceIn(0f, 1f)
            "LOW" -> d.lo = v.coerceIn(0f, 1f)
            "FILTER" -> d.filter = v.coerceIn(0f, 1f)
            "GAIN" -> d.gain = v.coerceIn(0f, 1f)
            "PITCH" -> if (!d.sync) d.pitch = v.coerceIn(-1f, 1f)
            "SEEK" -> if (d.loaded()) { d.seekTo = (v.coerceIn(0f, 1f) * d.len).toDouble(); if (d.sync && d.playing) d.syncReq = true }
            "BEND" -> d.bend = v.coerceIn(-0.4f, 0.4f)
        }
    }

    // ================================================================ OBERFLÄCHE (Client)

    @Volatile var client: Conn? = null
        private set
    @Volatile var state: JSONObject? = null
        private set
    val waves = arrayOf("", "")
    @Volatile var mySurface = 2
    @Volatile var onClientEvent: ((String) -> Unit)? = null

    fun connectTcp(ctx: Context, host: String, port: Int = PORT, done: (String?) -> Unit) {
        Thread {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4000)
                val c = Conn(host, s.getInputStream(), s.getOutputStream(), { s.close() }, false)
                clientRun(ctx, c)
                done(null)
            } catch (e: Exception) {
                done(e.message ?: "Verbindung fehlgeschlagen")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun connectBt(ctx: Context, dev: BluetoothDevice, done: (String?) -> Unit) {
        Thread {
            try {
                btAdapter(ctx)?.cancelDiscovery()
                val s: BluetoothSocket = dev.createRfcommSocketToServiceRecord(BT_UUID)
                s.connect()
                val c = Conn(dev.name ?: "Bluetooth", s.inputStream, s.outputStream, { s.close() }, true)
                clientRun(ctx, c)
                done(null)
            } catch (e: Exception) {
                done(e.message ?: "Bluetooth-Verbindung fehlgeschlagen")
            }
        }.start()
    }

    private fun clientRun(ctx: Context, c: Conn) {
        client = c
        c.send("HELLO " + deviceName(ctx))
        Thread {
            try {
                val r = c.reader()
                while (c.alive) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("S ") -> state = try { JSONObject(line.substring(2)) } catch (e: Exception) { state }
                        line.startsWith("PING ") -> c.send("PONG ${line.substring(5).trim()} ${System.nanoTime()}")
                        line.startsWith("SURF ") -> { mySurface = line.substring(5).trim().toIntOrNull() ?: mySurface; onClientEvent?.invoke("surf") }
                        line.startsWith("W ") -> {
                            val p = line.split(' ')
                            val k = p.getOrNull(1)?.toIntOrNull() ?: -1
                            if (k in 0..1) waves[k] = p.getOrNull(2)?.takeIf { it != "-" } ?: ""
                        }
                        line == "BYE" -> break
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                c.close()
                if (client === c) client = null
                onClientEvent?.invoke("lost")
            }
        }.apply { isDaemon = true }.start()
    }

    fun send(line: String) { client?.send(line) }

    fun disconnect() {
        client?.close()
        client = null
        state = null
    }

    // ================================================================ Suchen

    @Volatile var found: List<Pair<String, String>> = emptyList()
        private set
    private var discovery: NsdManager.DiscoveryListener? = null

    /** Hosts im WLAN suchen (Name → Adresse). */
    fun discover(ctx: Context, changed: () -> Unit) {
        stopDiscover(ctx)
        found = emptyList()
        val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                try {
                    m.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = si.host?.hostAddress ?: return
                            val name = si.serviceName
                            if (found.none { it.second == host }) { found = found + (name to host); changed() }
                        }
                    })
                } catch (ignored: Exception) {}
            }
        }
        try {
            m.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l)
            discovery = l
        } catch (ignored: Exception) {}
    }

    fun stopDiscover(ctx: Context) {
        val l = discovery ?: return
        try { (ctx.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(l) } catch (ignored: Exception) {}
        discovery = null
    }

    fun btAdapter(ctx: Context): BluetoothAdapter? = try {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    } catch (e: Exception) { null }

    @SuppressLint("MissingPermission")
    fun bondedDevices(ctx: Context): List<BluetoothDevice> = try {
        btAdapter(ctx)?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) { emptyList() }
}
