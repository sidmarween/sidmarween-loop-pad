package de.marween.looppad

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Direkt-USB-Modus: liest USB-MIDI-Controller selbst über die USB-Schnittstelle (an Android-MIDI vorbei).
 * Für Handys, bei denen Android das Gerät zwar erkennt, aber keine Daten weiterreicht.
 * USB-MIDI kommt in 4-Byte-Paketen (Kabelnummer/CIN + bis zu 3 MIDI-Bytes), die hier in den normalen Parser gehen.
 */
object UsbMidiDirect {
    private const val ACTION = "de.marween.looppad.USB_MIDI_PERMISSION"
    @Volatile var running = false; private set
    /** Verlauf der letzten Zustände (für den Monitor-Screenshot). */
    val history = java.util.concurrent.ConcurrentLinkedDeque<String>()
    @Volatile var status = "aus"
        private set(v) {
            field = v
            history.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date()) + " " + v)
            while (history.size > 8) history.pollFirst()
        }
    /** Zähler für die Diagnose: USB-Lesevorgänge mit Daten, empfangene Bytes, davon MIDI-Ereignisse. */
    @Volatile var reads = 0L; private set
    @Volatile var bytes = 0L; private set
    @Volatile var events = 0L; private set
    @Volatile var lastRaw = ""; private set
    @Volatile var info = ""; private set
    fun statusLine(): String = if (!running) status else "$status · USB-Pakete $reads · Bytes $bytes · MIDI $events" + (if (lastRaw.isNotEmpty()) " · zuletzt $lastRaw" else "")
    @Volatile private var stop = false
    private var conn: UsbDeviceConnection? = null
    private var thread: Thread? = null
    private var receiverOn = false
    private var askedAt = 0L

    /** USB-MIDI-Schnittstelle (Audio-Klasse 1, Unterklasse 3) mit Eingangs-Endpunkt suchen. */
    private fun midiIn(d: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            val isMidi = f.interfaceClass == UsbConstants.USB_CLASS_AUDIO && f.interfaceSubclass == 3
            if (!isMidi && f.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.type == UsbConstants.USB_ENDPOINT_XFER_INT)) return f to ep
            }
        }
        return null
    }

    fun start(ctx: Context): String {
        if (running) return status
        stop = false
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return "USB nicht verfügbar".also { status = it }
        val dev = um.deviceList.values.firstOrNull { midiIn(it) != null } ?: return "Kein USB-MIDI-Gerät am Anschluss".also { status = it }
        if (!um.hasPermission(dev)) {
            val app = ctx.applicationContext
            if (!receiverOn) {
                receiverOn = true
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val um2 = app.getSystemService(Context.USB_SERVICE) as? UsbManager
                        val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || (um2?.deviceList?.values?.any { midiIn(it) != null && um2.hasPermission(it) } == true)
                        if (ok) { status = "USB-Freigabe erteilt"; start(app) }
                        else { status = "USB-Zugriff abgelehnt"; Midi.onHint?.invoke("USB-Zugriff abgelehnt – Direkt-USB geht so nicht") }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, IntentFilter(ACTION))
            }
            try {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION).setPackage(app.packageName), flags)
                um.requestPermission(dev, pi)
                status = "Warte auf USB-Freigabe …"
            } catch (e: Throwable) {
                status = "Freigabe-Anfrage scheiterte (${e.javaClass.simpleName}: ${e.message?.take(80)}) – 2. Versuch"
                try {
                    val pi = PendingIntent.getBroadcast(app, 1, Intent(ACTION).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
                    um.requestPermission(dev, pi)
                    status = "Warte auf USB-Freigabe … (2. Weg)"
                } catch (e2: Throwable) { status = "⚠ Android verweigert die USB-Anfrage: ${e2.javaClass.simpleName}: ${e2.message?.take(80)}" }
            }
            askedAt = System.currentTimeMillis()
            return status
        }
        val (intf, ep) = midiIn(dev)!!
        info = "Schnittstelle ${intf.id} (Klasse ${intf.interfaceClass}/${intf.interfaceSubclass}), Endpunkt ${ep.address} " +
            (if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "Bulk" else "Interrupt") + ", ${ep.maxPacketSize} Bytes"
        reads = 0; bytes = 0; events = 0; lastRaw = ""
        status = "öffne ${dev.deviceName} …"
        val c = try { um.openDevice(dev) } catch (e: Throwable) { null } ?: return "USB-Gerät ließ sich nicht öffnen".also { status = it }
        if (!c.claimInterface(intf, true)) { c.close(); return "USB-Schnittstelle belegt".also { status = it } }
        conn = c
        stop = false
        running = true
        val name = try { listOfNotNull(dev.manufacturerName, dev.productName).joinToString(" ").ifBlank { null } } catch (e: Throwable) { null } ?: "USB-MIDI"
        status = "✓ Direkt-USB läuft: $name"
        Midi.onHint?.invoke("🔌 Direkt-USB: $name")
        thread = Thread({
            val buf = ByteArray(maxOf(64, ep.maxPacketSize))
            val m = ByteArray(3)
            while (!stop) {
                val n = try { c.bulkTransfer(ep, buf, buf.size, 250) } catch (e: Throwable) { -2 }
                if (n == -2 || (n < 0 && um.deviceList.values.none { it.deviceId == dev.deviceId })) break   // abgezogen
                if (n > 0) {
                    reads++; bytes += n
                    lastRaw = (0 until minOf(n, 8)).joinToString(" ") { String.format("%02X", buf[it]) }
                }
                var i = 0
                while (i + 4 <= n) {
                    val len = when (buf[i].toInt() and 0x0F) {
                        0x5, 0xF -> 1
                        0x2, 0x6, 0xC, 0xD -> 2
                        0x3, 0x4, 0x7, 0x8, 0x9, 0xA, 0xB, 0xE -> 3
                        else -> 0
                    }
                    if (len > 0) { m[0] = buf[i + 1]; m[1] = buf[i + 2]; m[2] = buf[i + 3]; Midi.feed(name, m, len); events++ }
                    i += 4
                }
            }
            try { c.releaseInterface(intf) } catch (e: Throwable) { }
            try { c.close() } catch (e: Throwable) { }
            conn = null
            running = false
            status = if (stop) "aus" else "Gerät getrennt"
        }, "MarvPAD-USB-MIDI").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        return status
    }

    fun stop() { stop = true }

    private var watchOn = false
    /** Ein-/Ausstecken beobachten: im Direkt-Modus beim Einstecken sofort wieder öffnen (Android-MIDI-Meldungen
     *  kommen auf manchen Samsung-Handys nicht zuverlässig). */
    fun watch(ctx: Context) {
        if (watchOn) return
        watchOn = true
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (Midi.usbDirect) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ start(app) }, 700)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { stop = true; status = "Gerät abgezogen" }
                }
            }
        }
        val f = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, f)
    }

    /** Läuft Direkt-USB noch? Sonst (falls eingeschaltet und ein Gerät steckt) neu starten – z. B. beim Zurückkehren in die App. */
    fun ensure(ctx: Context) { if (Midi.usbDirect && !running && (!status.startsWith("Warte") || System.currentTimeMillis() - askedAt > 8000)) start(ctx) }
}
