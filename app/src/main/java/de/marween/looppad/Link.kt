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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
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
    /** UDP-Rundruf: der Host meldet sich jede Sekunde im Netz (falls mDNS/NSD blockiert ist). */
    const val BEACON_PORT = 47821
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
        @Volatile var lastState: String? = null
        @Volatile var lastSent = 0L
        private val lock = Any()

        /** Senden läuft immer über einen eigenen Thread (Netzwerk im UI-Thread verbietet Android). */
        private val outQ = java.util.concurrent.LinkedBlockingQueue<String>()
        private val writer = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (alive) {
                    val first = outQ.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val sb = StringBuilder(first).append('\n')
                    // was schon wartet, gleich mitschicken (ein Paket statt vieler)
                    while (sb.length < 64_000) { val nx = outQ.poll() ?: break; sb.append(nx).append('\n') }
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: Exception) { close() }
        }.apply { isDaemon = true; name = "MarvLink-send"; start() }

        fun send(line: String) {
            if (!alive) return
            // Stau (z. B. langsames Bluetooth): alte Zustände verwerfen, Befehle behalten
            if (outQ.size > 200) outQ.removeIf { it.startsWith("S ") }
            outQ.offer(line)
        }

        private val rd by lazy { BufferedReader(InputStreamReader(inp, Charsets.UTF_8), 8192) }
        fun reader(): BufferedReader = rd

        fun close() {
            if (!alive) return
            alive = false
            try { closer() } catch (ignored: Exception) {}
        }
    }

    // ================================================================ WLAN schnell halten

    private var wifiLock: WifiManager.WifiLock? = null

    /** WLAN-Stromsparen aus, solange gekoppelt ist (spart sonst 50–200 ms Verzögerung). */
    @Suppress("DEPRECATION")
    fun lowLatency(ctx: Context, on: Boolean) {
        try {
            if (on) {
                if (wifiLock?.isHeld == true) return
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "marvpad-link").apply { setReferenceCounted(false); acquire() }
            } else {
                wifiLock?.release()
                wifiLock = null
            }
        } catch (ignored: Exception) {}
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
    /** Status für die Anzeige am Host. */
    @Volatile var wlanStatus = "–"
    @Volatile var btStatus = "–"
    @Volatile var stateError: String? = null
    /** Host: was dieses Handy selbst zeigt (für die Vorlage) – setzt die Oberfläche. */
    @Volatile var onHostSurface: ((String) -> Unit)? = null

    fun deviceName(ctx: Context): String = try {
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") ?: android.os.Build.MODEL
    } catch (e: Exception) { android.os.Build.MODEL }

    /** Eigene WLAN-/Hotspot-Adressen (für die manuelle Eingabe am anderen Handy) – Mobilfunk-Adressen weggelassen. */
    fun localIps(): List<String> = localNets().map { it.first.hostAddress ?: "" }.filter { it.isNotEmpty() }

    private fun isLan(name: String) = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("p2p") ||
        name.startsWith("eth") || name.startsWith("softap") || name.startsWith("bt-pan") || name.startsWith("rndis") || name.startsWith("usb")

    /** (Adresse, Rundruf-Adresse) aller WLAN/Hotspot/LAN-Schnittstellen. */
    private fun localNets(): List<Pair<InetAddress, InetAddress?>> {
        val lan = ArrayList<Pair<InetAddress, InetAddress?>>()
        val other = ArrayList<Pair<InetAddress, InetAddress?>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a !is Inet4Address || a.isLoopbackAddress) continue
                    (if (isLan(ni.name ?: "")) lan else other).add(a to ia.broadcast)
                }
            }
        } catch (ignored: Exception) {}
        return lan.ifEmpty { other.filter { it.first.isSiteLocalAddress } }
    }

    @SuppressLint("MissingPermission")
    fun startHost(ctx: Context, withBt: Boolean) {
        if (hosting) return
        hosting = true
        stateError = null
        lowLatency(ctx, true)
        val myName = deviceName(ctx)
        // WLAN: TCP-Server + im Netz bekannt machen (mDNS und UDP-Rundruf)
        wlanStatus = "startet …"
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                server = ss
                wlanStatus = "bereit (Port $PORT)"
                onClientsChanged?.invoke()
                while (hosting) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    val c = Conn(s.inetAddress?.hostAddress ?: "WLAN", s.getInputStream(), s.getOutputStream(), { s.close() }, false)
                    handshake(c, myName)
                }
            } catch (e: Exception) {
                if (hosting) { wlanStatus = "Fehler: ${e.message}"; onHostHint?.invoke("WLAN-Server: ${e.message}"); onClientsChanged?.invoke() }
            }
        }.apply { isDaemon = true }.start()
        Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply { broadcast = true }
                val msg = "MARVPAD|$myName|$PORT".toByteArray(Charsets.UTF_8)
                while (hosting) {
                    val targets = HashSet<InetAddress>()
                    for ((_, b) in localNets()) if (b != null) targets.add(b)
                    try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (ignored: Exception) {}
                    for (t in targets) try { sock.send(DatagramPacket(msg, msg.size, t, BEACON_PORT)) } catch (ignored: Exception) {}
                    Thread.sleep(1000)
                }
            } catch (ignored: Exception) {
            } finally { try { sock?.close() } catch (ignored: Exception) {} }
        }.apply { isDaemon = true }.start()
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("marvpad").apply { setReferenceCounted(false); acquire() }
            val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply { serviceName = "MarvPAD " + myName.filter { it.code in 32..126 }.take(40); serviceType = SERVICE; port = PORT }
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
        btStatus = if (withBt) "startet …" else "keine Erlaubnis – nur WLAN"
        if (withBt) Thread {
            try {
                val ad = btAdapter(ctx)
                if (ad == null) { btStatus = "nicht vorhanden"; onClientsChanged?.invoke(); return@Thread }
                if (!ad.isEnabled) { btStatus = "Bluetooth ist aus – einschalten und Host neu starten"; onClientsChanged?.invoke(); return@Thread }
                val bs = ad.listenUsingRfcommWithServiceRecord("Marv PAD", BT_UUID)
                btServer = bs
                btStatus = "bereit (wartet auf gekoppelte Handys)"
                onClientsChanged?.invoke()
                while (hosting) {
                    val s = bs.accept() ?: break
                    val c = Conn(try { s.remoteDevice?.name ?: "Bluetooth" } catch (e: SecurityException) { "Bluetooth" }, s.inputStream, s.outputStream, { s.close() }, true)
                    handshake(c, myName)
                }
            } catch (e: Exception) {
                if (hosting) { btStatus = "Fehler: ${e.message ?: e.javaClass.simpleName}"; onHostHint?.invoke("Bluetooth: ${e.message ?: "nicht verfügbar"}"); onClientsChanged?.invoke() }
            }
        }.apply { isDaemon = true }.start()
        // Zustände senden (~25 pro Sekunde) und Uhren abgleichen
        Thread {
            var n = 0
            while (hosting) {
                try {
                    if (clients.isNotEmpty()) {
                        val st = try {
                            "S " + hostState().toString()
                        } catch (e: Throwable) {
                            val msg = e.message ?: e.javaClass.simpleName
                            if (stateError != msg) { stateError = msg; for (c in clients) c.send("E $msg") }
                            null
                        }
                        val now = System.nanoTime()
                        for (c in clients) {
                            if (!c.alive) { clients.remove(c); onClientsChanged?.invoke(); continue }
                            // Bluetooth ist langsamer: höchstens 10× pro Sekunde; unverändert nur alle 0,5 s
                            val due = now - c.lastSent >= (if (c.viaBt) 100_000_000L else 18_000_000L)
                            if (st != null && due && (st != c.lastState || now - c.lastSent > 500_000_000L)) {
                                c.send(st)
                                c.lastState = st
                                c.lastSent = now
                            }
                            if (n % 25 == 0) c.send("PING ${System.nanoTime()}")
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
                    Thread.sleep(20)
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

    /** Erste Zeile entscheidet: PROBE (Netzsuche) bekommt nur eine Antwort, alles andere ist eine Oberfläche. */
    private fun handshake(c: Conn, myName: String) {
        Thread {
            try {
                val first = c.reader().readLine()
                if (first == null) { c.close(); return@Thread }
                if (first.startsWith("PROBE")) {
                    c.send("MARVPAD $myName")
                    Thread.sleep(150)
                    c.close()
                    return@Thread
                }
                hostServe(c)
                try { hostCommand(c, first) } catch (ignored: Exception) {}
            } catch (e: Exception) { c.close() }
        }.apply { isDaemon = true }.start()
    }

    private fun hostServe(c: Conn) {
        Thread.currentThread().priority = Thread.MAX_PRIORITY
        clients.add(c)
        // nächste freie Oberfläche als Vorschlag (die Vorlage verteilt neu)
        c.surface = defaultSurfaceFor(clients.indexOf(c))
        c.send("SURF ${c.surface}")
        c.send("PING ${System.nanoTime()}")
        onClientsChanged?.invoke()
        onHostHint?.invoke("📡 ${c.peerName} verbunden")
        Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
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
        wifiLock?.let { try { it.release() } catch (ignored: Exception) {} }
        wifiLock = null
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
    /** JSON verträgt kein NaN/Unendlich – solche Werte als 0 senden. */
    private fun JSONObject.num(k: String, v: Double): JSONObject = put(k, if (v.isNaN() || v.isInfinite()) 0.0 else v)
    private fun JSONObject.num(k: String, v: Float): JSONObject = num(k, v.toDouble())

    private fun hostState(): JSONObject {
        val j = JSONObject()
        j.num("bpm", Engine.currentBpm().toDouble())
        j.put("play", Engine.seqPlaying)
        j.put("rec", Engine.seqRecording)
        j.num("vol", Engine.knobs[3])
        j.num("fxX", Engine.fxX)
        j.num("fxY", Engine.fxY)
        j.put("fxT", Engine.fxTouch)
        j.put("fx", Fx.NAMES.getOrElse(Engine.fxType) { "FX" })
        j.put("pxA", Engine.xfA)
        j.put("pxB", Engine.xfB)
        j.num("pxP", Engine.xfPos)
        val pa = JSONArray()
        for (p in 0 until 12) {
            val o = JSONArray()
            o.put(Engine.state(p).ordinal)
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
            val o = JSONObject()
            try {
                val d = Dj.decks[k]
                o.put("ok", d.loaded())
                o.put("t", d.title)
                o.num("pos", if (d.len > 0) d.pos / d.len else 0.0)
                o.num("rem", if (d.loaded() && d.srcRate > 0) (d.len - d.pos) / d.srcRate / max(0.01, d.ratio) else 0.0)
                o.num("bpm", d.effBpm().toDouble())
                o.put("key", Keys.camelot(d.effKey()))
                o.put("pl", d.playing); o.put("sy", d.sync); o.put("hp", d.cue); o.put("kl", d.keylock)
                o.put("lp", d.loopOn)
                o.num("lb", d.loopBeats.toDouble())
                o.num("rb", d.rollBeats.toDouble())
                o.put("sl", d.slipOn)
                o.num("jb", d.jumpBars.toDouble())
                o.put("hot", JSONArray().apply { d.hot.forEach { put(if (it >= 0) 1 else 0) } })
                o.num("fd", d.fader.toDouble()); o.num("hi", d.hi.toDouble()); o.num("mi", d.mid.toDouble()); o.num("lo", d.lo.toDouble())
                o.num("fl", d.filter.toDouble()); o.num("gn", d.gain.toDouble()); o.num("pt", d.pitch.toDouble())
                o.put("lv", Math.round(d.level * 100))
                o.put("eo", d.echoOn); o.put("sw", d.sweepMode); o.put("ce", d.censor)
                if (d.loaded() && d.bpm > 0f) {
                    val (inP, toNext) = Dj.phraseInfo(d, d.pos)
                    o.put("ph", "${inP + 1}/16 · noch ${Math.ceil(toNext / 4.0 - 1e-6).toInt()} T")
                }
            } catch (e: Exception) { o.put("ok", false) }
            da.put(o)
        }
        j.put("decks", da)
        j.num("xf", Dj.xfade.toDouble())
        return j
    }

    /** Befehl einer Oberfläche ausführen (Host). */
    private fun hostCommand(c: Conn, line: String) {
        val a = line.split(' ')
        fun f(i: Int) = a.getOrNull(i)?.toFloatOrNull() ?: 0f
        fun n(i: Int) = a.getOrNull(i)?.toIntOrNull() ?: 0
        fun l(i: Int) = a.getOrNull(i)?.toLongOrNull() ?: 0L
        when (a[0]) {
            "K" -> return
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
                    // sofort auslösen (kein Zeitstempel-Ausgleich – der würde Verzögerung hinzufügen)
                    val r = Engine.liveDown(p, f(3).coerceIn(0.05f, 1f), 0L)
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

    /** Letzte Fehlermeldung des Hosts (z. B. wenn er keine Daten schicken kann). */
    @Volatile var hostError: String? = null
        private set
    @Volatile var connectedAt = 0L
        private set

    private fun tcpError(e: Exception, host: String): String = when (e) {
        is java.net.SocketTimeoutException -> "Zeitüberschreitung bei $host – läuft dort der Host? Gleiches WLAN? (Gäste-WLANs blockieren Verbindungen zwischen Geräten – dann Hotspot nehmen)"
        is java.net.ConnectException -> "$host lehnt ab – dort ist kein Host gestartet (Menü → 📡 → „macht den Klang“)"
        is java.net.NoRouteToHostException -> "$host nicht erreichbar – anderes Netz?"
        is java.net.UnknownHostException -> "Adresse „$host“ ungültig"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun connectTcp(ctx: Context, host: String, port: Int = PORT, done: (String?) -> Unit) {
        Thread {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host.trim(), port), 5000)
                val c = Conn(host, s.getInputStream(), s.getOutputStream(), { s.close() }, false)
                clientRun(ctx, c)
                done(null)
            } catch (e: Exception) {
                done(tcpError(e, host))
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun connectBt(ctx: Context, dev: BluetoothDevice, done: (String?) -> Unit) {
        Thread {
            // Suche stoppen braucht ab Android 12 eine eigene Erlaubnis – nur versuchen
            try { btAdapter(ctx)?.cancelDiscovery() } catch (ignored: Throwable) {}
            var last: Exception? = null
            val makers = listOf<() -> BluetoothSocket>(
                { dev.createRfcommSocketToServiceRecord(BT_UUID) },
                { dev.createInsecureRfcommSocketToServiceRecord(BT_UUID) }
            )
            for (mk in makers) {
                var s: BluetoothSocket? = null
                try {
                    s = mk()
                    s.connect()
                    val sock = s
                    val nm = try { dev.name } catch (e: SecurityException) { null } ?: "Bluetooth"
                    val c = Conn(nm, sock.inputStream, sock.outputStream, { sock.close() }, true)
                    clientRun(ctx, c)
                    done(null)
                    return@Thread
                } catch (e: Exception) {
                    last = e
                    try { s?.close() } catch (ignored: Exception) {}
                }
            }
            val m = last?.message ?: ""
            done(when {
                last is SecurityException -> "Bluetooth-Erlaubnis fehlt (Einstellungen → Apps → Marv PAD → Geräte in der Nähe)"
                m.contains("read failed") || m.contains("timeout") || m.contains("closed") ->
                    "Das andere Handy antwortet nicht über Bluetooth. Dort Menü → 📡 → „macht den Klang“ starten und prüfen, ob „Bluetooth: bereit“ angezeigt wird."
                else -> "Bluetooth: ${m.ifEmpty { last?.javaClass?.simpleName ?: "Fehler" }}"
            })
        }.start()
    }

    private fun clientRun(ctx: Context, c: Conn) {
        client = c
        if (!c.viaBt) lowLatency(ctx, true)
        // kleines Lebenszeichen: hält das WLAN des Handys wach (sonst schläft es zwischen zwei Berührungen ein)
        if (!c.viaBt) Thread {
            while (c.alive) { c.send("K"); try { Thread.sleep(50) } catch (e: InterruptedException) { break } }
        }.apply { isDaemon = true }.start()
        hostError = null
        state = null
        connectedAt = System.currentTimeMillis()
        c.send("HELLO " + deviceName(ctx))
        Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
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
                        line.startsWith("E ") -> hostError = line.substring(2)
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
        if (!hosting) { wifiLock?.let { try { it.release() } catch (ignored: Exception) {} }; wifiLock = null }
        client?.close()
        client = null
        state = null
    }

    // ================================================================ Suchen

    @Volatile var found: List<Pair<String, String>> = emptyList()
        private set
    private var discovery: NsdManager.DiscoveryListener? = null

    /** Hosts im WLAN suchen (Name → Adresse). */
    @Volatile private var discovering = false
    private var clientLock: WifiManager.MulticastLock? = null
    private val resolveQueue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()

    @Synchronized private fun addFound(name: String, host: String, changed: () -> Unit) {
        if (localIps().contains(host)) return // das eigene Handy
        if (found.none { it.second == host }) { found = found + (name to host); changed() }
    }

    /** Hosts im WLAN suchen (Name → Adresse): mDNS/NSD und UDP-Rundruf gleichzeitig. */
    fun discover(ctx: Context, changed: () -> Unit) {
        stopDiscover(ctx)
        found = emptyList()
        discovering = true
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            clientLock = wm.createMulticastLock("marvpad-find").apply { setReferenceCounted(false); acquire() }
        } catch (ignored: Exception) {}
        // 1) UDP-Rundruf des Hosts hören
        Thread {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(BEACON_PORT)) }
                val buf = ByteArray(512)
                while (discovering) {
                    try {
                        val pk = DatagramPacket(buf, buf.size)
                        sock.receive(pk)
                        val msg = String(pk.data, 0, pk.length, Charsets.UTF_8)
                        if (msg.startsWith("MARVPAD|")) {
                            val name = msg.split('|').getOrNull(1) ?: "Marv PAD"
                            val host = pk.address?.hostAddress ?: continue
                            addFound(name, host, changed)
                        }
                    } catch (ignored: java.net.SocketTimeoutException) {}
                }
            } catch (ignored: Exception) {
            } finally { try { sock?.close() } catch (ignored: Exception) {} }
        }.apply { isDaemon = true }.start()
        // 2) mDNS/NSD (Auflösungen nacheinander – Android erlaubt nur eine gleichzeitig)
        val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        resolveQueue.clear()
        Thread {
            while (discovering) {
                val i = try { resolveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null } ?: continue
                val done = java.util.concurrent.CountDownLatch(1)
                try {
                    @Suppress("DEPRECATION")
                    m.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, e: Int) { done.countDown() }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val h = si.host
                            if (h is Inet4Address) addFound(si.serviceName, h.hostAddress ?: "", changed)
                            done.countDown()
                        }
                    })
                    done.await(3, java.util.concurrent.TimeUnit.SECONDS)
                } catch (ignored: Exception) {}
            }
        }.apply { isDaemon = true }.start()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) { resolveQueue.offer(i) }
        }
        try {
            m.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l)
            discovery = l
        } catch (ignored: Exception) {}
    }

    /** 3) Gründlich: alle Adressen im eigenen WLAN (/24) auf den Marv-PAD-Port prüfen. [progress] 0..1 */
    fun scanSubnet(changed: () -> Unit, progress: (Float) -> Unit) {
        Thread {
            val bases = localNets().mapNotNull { (a, _) -> a.hostAddress?.substringBeforeLast('.') }.distinct()
            if (bases.isEmpty()) { progress(1f); return@Thread }
            val all = bases.flatMap { b -> (1..254).map { "$b.$it" } }.filter { it !in localIps() }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
            val doneN = java.util.concurrent.atomic.AtomicInteger()
            for (ip in all) pool.execute {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, PORT), 400)
                        s.soTimeout = 900
                        s.getOutputStream().write("PROBE\n".toByteArray())
                        s.getOutputStream().flush()
                        val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                        if (line != null && line.startsWith("MARVPAD")) addFound(line.removePrefix("MARVPAD").trim().ifEmpty { "Marv PAD" }, ip, changed)
                    }
                } catch (ignored: Exception) {}
                val n = doneN.incrementAndGet()
                if (n % 16 == 0 || n == all.size) progress(n.toFloat() / all.size)
            }
            pool.shutdown()
        }.apply { isDaemon = true }.start()
    }

    fun stopDiscover(ctx: Context) {
        discovering = false
        try { clientLock?.release() } catch (ignored: Exception) {}
        clientLock = null
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
