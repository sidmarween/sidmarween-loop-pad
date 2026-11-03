package de.marween.looppad

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process

/**
 * 📲 Aus anderer App sampeln (Android 10+): nimmt den Ton auf, den andere Apps gerade abspielen
 * (YouTube, Browser, Spiele, andere Musik-Apps …) – über die Android-Funktion „Audio-Wiedergabe aufnehmen“.
 * Läuft als Vordergrund-Dienst mit Benachrichtigung (■ STOPP & AUF PAD), damit man in der anderen App bleiben kann.
 * Apps können das Mitschneiden sperren (z. B. viele Streaming-Dienste) – dann kommt nur Stille an.
 */
class AppCapture : Service() {

    companion object {
        const val MAX_SEC = 90
        private const val CH = "marvpad_capture"
        private const val NOTE_ID = 4711
        private const val ACT_STOP = "de.marween.looppad.CAPTURE_STOP"
        private const val ACT_CANCEL = "de.marween.looppad.CAPTURE_CANCEL"

        @Volatile var running = false; private set
        @Volatile var level = 0f; private set
        @Volatile var frames = 0; private set
        @Volatile var error: String? = null
        /** Fertige Aufnahme, die die App beim nächsten Öffnen auf ein Pad legt. */
        @Volatile var result: FloatArray? = null
        /** Wird (im Audio-Thread des Dienstes) gerufen, sobald [result] bereitliegt. */
        @Volatile var onResult: (() -> Unit)? = null

        fun seconds(): Float = frames / Engine.sampleRate.toFloat()

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, AppCapture::class.java).putExtra("code", code).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context, keep: Boolean) {
            if (!running) return
            ctx.startService(Intent(ctx, AppCapture::class.java).setAction(if (keep) ACT_STOP else ACT_CANCEL))
        }

        /** Stille am Anfang und Ende abschneiden (mit kurzem Vorlauf/Ausklang). */
        fun trim(a: FloatArray, rate: Int): FloatArray {
            var peak = 0f
            for (x in a) { val v = Math.abs(x); if (v > peak) peak = v }
            if (peak < 1e-4f) return FloatArray(0)
            val thr = maxOf(peak * 0.02f, 3e-4f)
            var s = 0; while (s < a.size && Math.abs(a[s]) < thr) s++
            var e = a.size - 1; while (e > s && Math.abs(a[e]) < thr) e--
            s = maxOf(0, s - rate / 200)
            e = minOf(a.size - 1, e + rate / 20)
            return a.copyOfRange(s, e + 1)
        }
    }

    private var projection: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var stopMode = 0   // 0 läuft, 1 behalten, 2 verwerfen

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP -> { stopMode = 1; if (thread == null) finish(); return START_NOT_STICKY }
            ACT_CANCEL -> { stopMode = 2; if (thread == null) finish(); return START_NOT_STICKY }
        }
        if (running || intent == null) return START_NOT_STICKY
        // Erst Vordergrund (Pflicht ab Android 14), dann die Freigabe einlösen
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTE_ID, note())
        } catch (e: Exception) { error = "Dienst nicht startbar: ${e.message}"; stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT < 29) { error = "Erst ab Android 10 möglich"; finish(); return START_NOT_STICKY }
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra("data")
        val code = intent.getIntExtra("code", 0)
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data ?: throw IllegalStateException("keine Freigabe"))
                ?: throw IllegalStateException("Freigabe abgelehnt")
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (stopMode == 0) stopMode = 1 }
            }, null)
            projection = mp
            begin(mp)
        } catch (e: Throwable) {
            error = "Aufnahme nicht möglich: ${e.message ?: e}"
            finish()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun begin(mp: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        val rate = Engine.sampleRate
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())          // Marv PAD selbst nicht mitschneiden
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val r = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(minBuf, rate * 8 / 5))
            .setAudioPlaybackCaptureConfig(cfg).build()
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("Aufnahme-Gerät belegt") }
        rec = r
        error = null; frames = 0; level = 0f; result = null; stopMode = 0
        running = true
        val maxFrames = rate * MAX_SEC
        val buf = FloatArray(maxFrames)
        thread = Thread({
            val tmp = FloatArray(2048)
            var lastNote = 0L
            try {
                r.startRecording()
                while (stopMode == 0) {
                    val n = r.read(tmp, 0, tmp.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { if (n < 0) break else continue }
                    var pk = 0f
                    var i = 0
                    while (i + 1 < n) {
                        val m = (tmp[i] + tmp[i + 1]) * 0.5f
                        val a = Math.abs(m); if (a > pk) pk = a
                        if (frames < maxFrames) { buf[frames] = m; frames++ }
                        i += 2
                    }
                    level = maxOf(pk, level * 0.85f)
                    if (frames >= maxFrames) { stopMode = 1; break }
                    val now = System.currentTimeMillis()
                    if (now - lastNote > 1000) { lastNote = now; updateNote() }
                }
            } catch (e: Throwable) { error = "Aufnahme abgebrochen: ${e.message ?: e}" }
            try { r.stop() } catch (e: Throwable) { }
            if (stopMode != 2 && frames > 0) {
                val t = trim(buf.copyOf(frames), rate)
                if (t.size < rate / 20) error = error ?: "Nur Stille aufgenommen – die App sperrt das Mitschneiden vermutlich (z. B. Streaming-Dienste) oder es lief gerade nichts."
                else result = t
            }
            thread = null
            finish()
            onResult?.invoke()
        }, "AppCapture").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    private fun finish() {
        running = false
        try { rec?.release() } catch (e: Throwable) { }
        rec = null
        try { projection?.stop() } catch (e: Throwable) { }
        projection = null
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (e: Throwable) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (thread != null) stopMode = if (stopMode == 0) 1 else stopMode
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Benachrichtigung
    private fun pi(action: String?, req: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == null)
            PendingIntent.getActivity(this, req, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        else PendingIntent.getService(this, req, Intent(this, AppCapture::class.java).setAction(action), flags)
    }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Aus anderer App sampeln", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        val sec = if (running) String.format(java.util.Locale.GERMANY, "%.0f s von %d s", seconds(), MAX_SEC) else "startet …"
        @Suppress("DEPRECATION")
        return b.setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("⏺ Marv PAD nimmt auf")
            .setContentText("Ton anderer Apps · $sec")
            .setOngoing(true)
            .setContentIntent(pi(null, 1))
            .addAction(Notification.Action.Builder(null, "■ STOPP & AUF PAD", pi(ACT_STOP, 2)).build())
            .addAction(Notification.Action.Builder(null, "✕ VERWERFEN", pi(ACT_CANCEL, 3)).build())
            .build()
    }

    private fun updateNote() {
        try { getSystemService(NotificationManager::class.java).notify(NOTE_ID, note()) } catch (e: Throwable) { }
    }
}
