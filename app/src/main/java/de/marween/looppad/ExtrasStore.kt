package de.marween.looppad

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Speichert die Einstellungen aller Zusatz-Instrumente im Projekt (Euklid, Atmos, Wolf, Architect, Orbits, Touch-Lab,
 * Modular, Bass-Labor, Organ-Bass, Vibe, Chaos, Organism, FX-Gen, Vocoder, Chord-Pad, Harmonizer, Vocal-Chop,
 * Performance-Szenen). Je Instrument steht unten, welche Felder dazugehören – gelesen und geschrieben wird per
 * Reflexion (R8 benennt in dieser App nichts um, siehe proguard-rules.pro). Unbekannte/fehlende Felder werden
 * übersprungen, so bleiben alte Projekte ladbar.
 */
object ExtrasStore {
    private val SPEC: List<Pair<Any, Array<String>>> = listOf(
        Euclid to arrayOf("on", "density", "complexity", "morph", "level", "poly", "laneOn", "laneVol", "laneN", "laneK", "laneRot", "laneHit", "acc", "kit", "presetIdx"),
        Atmos to arrayOf("on", "x", "y", "level", "blend", "evolve", "density", "shimmer", "wander", "keyAuto", "keyManual", "fadeSec", "timbre", "chordSet", "presetIdx"),
        Wolf to arrayOf("on", "srcPad", "chaos", "density", "variation", "level", "hold", "seed", "muteSrc", "presetIdx", "weights"),
        Architect to arrayOf("on", "type", "len", "steps", "acc", "tune", "level", "mute", "snap", "material", "air", "chaos", "x", "y", "vol", "pitchTrack", "presetIdx"),
        Orbits to arrayOf("on", "sats", "wells", "core", "stardust", "collision", "gravity", "vol", "presetIdx", "wellPull"),
        TouchLab to arrayOf("mode", "vol", "space", "corners", "terrain", "orbit", "gSound", "spec", "specPlay", "specBars", "specScale"),
        Modular to arrayOf("pos", "knob", "cables", "drone", "note", "patchIdx"),
        BassLab to arrayOf("visc", "sub", "spit", "drive", "craterCut", "craterRes", "juice", "tight", "thumbSub", "talk", "mPreset", "fPreset"),
        OrganBass to arrayOf("click", "decay", "wood", "bars", "sub", "tone", "legato", "preset"),
        Vibe to arrayOf("chord", "drift", "pumpRate", "pump", "mode", "drive", "sub", "track", "presetIdx"),
        Chaos to arrayOf("marrow", "material", "scratch", "wet", "stutterRate", "resLevel", "rootPc", "presetIdx"),
        GrooveOrganism to arrayOf("on", "frozen", "mutation", "density", "ghosts", "accents", "anchor", "locked", "presetIdx"),
        FxGen to arrayOf("bars", "intensity", "vol", "quant"),
        Vocoder to arrayOf("carrier", "formant", "sibilance", "dry", "level", "release", "robot", "followChords"),
        ChordPad to arrayOf("root", "minor", "voicing", "strum", "autoBass", "track", "recorded"),
        Harmonizer to arrayOf("source", "x", "y", "level", "space", "width", "octave", "leadTune", "muteOrig", "autoMode", "presetIdx", "midiIn"),
        VocalChop to arrayOf("vol", "gate", "reverse", "stutter", "pitch", "release", "tone", "chopMode", "patIdx"),
        MixMorph to arrayOf("autoBars", "presetIdx"),
    )

    private fun key(o: Any) = o.javaClass.simpleName

    /** Grundeinstellungen beim App-Start (für alte Projekte ohne Zusatz-Instrumente). */
    private var defaults: JSONObject? = null
    fun captureDefaults() { if (defaults == null) defaults = try { toJson() } catch (e: Exception) { null } }
    fun resetToDefaults() { defaults?.let { fromJson(JSONObject(it.toString())) } }

    private fun field(c: Class<*>, name: String): Field? = try {
        c.getDeclaredField(name).also { it.isAccessible = true }
    } catch (e: Exception) { null }

    /** Kleiner Schnappschuss für ↶ (ohne Mikro-Aufnahme und Szenen-Speicher). */
    fun snapshot(): String = toJson(false).toString()
    fun restoreSnapshot(s: String) { try { fromJson(JSONObject(s)) } catch (e: Exception) { } }

    // ---------------------------------------------------------------- Schreiben
    fun toJson(full: Boolean = true): JSONObject {
        val j = JSONObject()
        for ((obj, names) in SPEC) {
            val o = JSONObject()
            for (n in names) {
                val f = field(obj.javaClass, n) ?: continue
                try { enc(f.get(if (Modifier.isStatic(f.modifiers)) null else obj))?.let { o.put(n, it) } } catch (e: Exception) { }
            }
            j.put(key(obj), o)
        }
        if (full) {
            try { j.put("vocalSrc", VocalChop.sourceJson()) } catch (e: Exception) { }
            try { j.put("morphSnaps", MixMorph.snapsJson()) } catch (e: Exception) { }
        }
        return j
    }

    private fun enc(v: Any?): Any? = when (v) {
        null -> null
        is Float -> v.toDouble()
        is Double, is Int, is Long, is Boolean, is String -> v
        is FloatArray -> JSONArray().apply { for (x in v) put(x.toDouble()) }
        is DoubleArray -> JSONArray().apply { for (x in v) put(x) }
        is IntArray -> JSONArray().apply { for (x in v) put(x) }
        is BooleanArray -> JSONArray().apply { for (x in v) put(x) }
        is Array<*> -> JSONArray().apply { for (x in v) put(enc(x) ?: JSONObject.NULL) }
        is List<*> -> JSONArray().apply { for (x in v) put(enc(x) ?: JSONObject.NULL) }
        else -> if (v.javaClass.name.startsWith("de.marween.looppad")) encObj(v) else null
    }

    /** Kleine Objekte (z. B. Orbits-Satellit): alle einfachen Felder. */
    /** Laufzeit-Felder kleiner Objekte, die nicht gespeichert werden (Bewegung, Anzeige). */
    private val SKIP = mapOf("Sat" to setOf("m", "prevPhi", "inWell", "accent", "x", "y", "flash", "speed"))

    private fun encObj(o: Any): JSONObject {
        val j = JSONObject()
        val skip = SKIP[o.javaClass.simpleName] ?: emptySet()
        for (f in o.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.name in skip) continue
            val t = f.type
            if (t == java.lang.Float.TYPE || t == java.lang.Integer.TYPE || t == java.lang.Boolean.TYPE || t == java.lang.Double.TYPE) {
                f.isAccessible = true
                enc(f.get(o))?.let { j.put(f.name, it) }
            }
        }
        return j
    }

    // ---------------------------------------------------------------- Laden
    fun fromJson(j: JSONObject?) {
        if (j == null) return
        for ((obj, names) in SPEC) {
            val o = j.optJSONObject(key(obj)) ?: continue
            for (n in names) {
                if (!o.has(n)) continue
                val f = field(obj.javaClass, n) ?: continue
                val target = if (Modifier.isStatic(f.modifiers)) null else obj
                try { dec(f, target, o.get(n)) } catch (e: Exception) { }
            }
        }
        // Folge-Zustände neu aufbauen
        if (j.has("vocalSrc")) try { VocalChop.restoreSource(j.optJSONObject("vocalSrc")) } catch (e: Exception) { }
        if (j.has("morphSnaps")) try { MixMorph.snapsFromJson(j.optJSONObject("morphSnaps")) } catch (e: Exception) { }
    }

    private fun dec(f: Field, owner: Any?, v: Any) {
        val t = f.type
        when {
            t == java.lang.Float.TYPE -> f.setFloat(owner, (v as Number).toFloat())
            t == java.lang.Double.TYPE -> f.setDouble(owner, (v as Number).toDouble())
            t == java.lang.Integer.TYPE -> f.setInt(owner, (v as Number).toInt())
            t == java.lang.Long.TYPE -> f.setLong(owner, (v as Number).toLong())
            t == java.lang.Boolean.TYPE -> f.setBoolean(owner, v as Boolean)
            t == String::class.java -> f.set(owner, v.toString())
            else -> into(f.get(owner), v)
        }
    }

    /** Inhalt in ein bestehendes Feld-Objekt (Array, Liste, kleines Objekt) übernehmen. */
    @Suppress("UNCHECKED_CAST")
    private fun into(cur: Any?, v: Any) {
        when (cur) {
            is FloatArray -> { val a = v as JSONArray; for (i in 0 until minOf(cur.size, a.length())) cur[i] = a.getDouble(i).toFloat() }
            is DoubleArray -> { val a = v as JSONArray; for (i in 0 until minOf(cur.size, a.length())) cur[i] = a.getDouble(i) }
            is IntArray -> { val a = v as JSONArray; for (i in 0 until minOf(cur.size, a.length())) cur[i] = a.getInt(i) }
            is BooleanArray -> { val a = v as JSONArray; for (i in 0 until minOf(cur.size, a.length())) cur[i] = a.getBoolean(i) }
            is Array<*> -> { val a = v as JSONArray; for (i in 0 until minOf(cur.size, a.length())) { val e = cur[i]; val x = a.get(i); if (e != null && x != JSONObject.NULL) into(e, x) } }
            is MutableList<*> -> {
                val a = v as JSONArray
                val list = cur as MutableList<Any>
                val items = ArrayList<Any>()
                for (i in 0 until a.length()) {
                    when (val x = a.get(i)) {
                        is JSONArray -> items.add(IntArray(x.length()) { k -> x.getInt(k) })
                        is Number -> items.add(x.toInt())
                    }
                }
                list.clear(); list.addAll(items)
            }
            null -> {}
            else -> if (v is JSONObject && cur.javaClass.name.startsWith("de.marween.looppad")) {
                for (f in cur.javaClass.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || !v.has(f.name)) continue
                    f.isAccessible = true
                    try { dec(f, cur, v.get(f.name)) } catch (e: Exception) { }
                }
            }
        }
    }
}
