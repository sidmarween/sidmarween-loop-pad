package de.marween.looppad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Anschlagsdynamik für Touch-Pads.
 * 0 = FEST (immer voll), 1 = HÖHE (oben im Pad laut, unten leise),
 * 2 = STÄRKE: wie fest getippt wurde – aus dem Stoß, den der Beschleunigungssensor beim Tippen misst
 * (wie GarageBand), ergänzt um die Fingerfläche; beides passt sich selbst an dein Spiel an.
 */
object TouchVelocity {
    @Volatile var mode = 0
    /** Kurve: 0 = weich (schon leichtes Tippen ist laut), 1 = linear, 2 = hart (laut nur bei kräftigem Tippen). */
    @Volatile var curve = 1
    /** Leisester Anschlag (0..1). */
    @Volatile var floor = 0.2f

    val MODES = arrayOf("FEST", "HÖHE", "STÄRKE")
    val CURVES = arrayOf("weich", "linear", "hart")

    // Beschleunigung: Ringpuffer (Zeit, Stoß-Stärke)
    private const val N = 256
    private val tNs = LongArray(N)
    private val mag = FloatArray(N)
    @Volatile private var head = 0
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var registered = false
    private var sm: SensorManager? = null
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            // Schwerkraft herausfiltern (langsamer Tiefpass), übrig bleibt der Stoß
            gx += (x - gx) * 0.05f; gy += (y - gy) * 0.05f; gz += (z - gz) * 0.05f
            val dx = x - gx; val dy = y - gy; val dz = z - gz
            val h = head
            // Sensor-Zeit (seit Start inkl. Tiefschlaf) auf System.nanoTime umrechnen
            tNs[h] = e.timestamp - (android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime())
            mag[h] = sqrt(dx * dx + dy * dy + dz * dz)
            head = (h + 1) % N
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    /** Sensor nur, solange STÄRKE gewählt ist (spart Akku). */
    fun update(ctx: Context) {
        val want = mode == 2
        if (want == registered) return
        val m = sm ?: (ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also { sm = it } ?: return
        if (want) {
            val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            registered = m.registerListener(listener, acc, SensorManager.SENSOR_DELAY_FASTEST)
        } else {
            m.unregisterListener(listener)
            registered = false
        }
    }

    fun stop() {
        sm?.unregisterListener(listener)
        registered = false
    }

    // selbst lernende Bereiche (letzte Anschläge)
    private val histA = ArrayList<Float>()
    private val histS = ArrayList<Float>()

    private fun norm(hist: ArrayList<Float>, v: Float): Float? {
        hist.add(v)
        if (hist.size > 32) hist.removeAt(0)
        if (hist.size < 5) return null
        val sorted = hist.sorted()
        val lo = sorted[(sorted.size * 0.1f).toInt()]
        val hi = sorted[(sorted.size * 0.92f).toInt().coerceAtMost(sorted.size - 1)]
        if (hi - lo < 1e-4f) return null
        return ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    private fun shape(v: Float): Float {
        val c = when (curve) { 0 -> v.pow(0.55f); 2 -> v.pow(1.7f); else -> v }
        return (floor + (1f - floor) * c).coerceIn(0.05f, 1f)
    }

    /**
     * Lautstärke eines Anschlags. [top]/[bottom] = Pad-Grenzen (für HÖHE), [touchNanos] = Zeitpunkt der Berührung.
     */
    fun of(e: MotionEvent, idx: Int, y: Float, top: Float, bottom: Float, touchNanos: Long): Float {
        when (mode) {
            0 -> return 1f
            1 -> return shape((1f - (y - top) / max(1f, bottom - top)).coerceIn(0f, 1f))
        }
        // STÄRKE: größter Stoß kurz vor/um die Berührung
        var peak = 0f
        val now = System.nanoTime()
        val from = touchNanos - 40_000_000L
        for (k in 0 until N) {
            val t = tNs[k]
            if (t in from..now) peak = max(peak, mag[k])
        }
        val area = max(e.getTouchMajor(idx), e.getSize(idx) * 100f) + max(0f, e.getPressure(idx) - 0.5f) * 40f
        val a = if (registered && peak > 0f) norm(histA, peak) else null
        val s = norm(histS, area)
        val v = when {
            a != null && s != null -> a * 0.75f + s * 0.25f
            a != null -> a
            s != null -> s
            else -> 0.8f
        }
        return shape(v)
    }

    /** Berührungszeitpunkt (System.nanoTime-Basis) eines Zeigers. */
    fun eventNanos(e: MotionEvent): Long = try {
        if (android.os.Build.VERSION.SDK_INT >= 34) e.eventTimeNanos else e.eventTime * 1_000_000L
    } catch (t: Throwable) { e.eventTime * 1_000_000L }
}
