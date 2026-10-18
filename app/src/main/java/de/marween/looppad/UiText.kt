package de.marween.looppad

import android.graphics.Paint
import kotlin.math.max

/**
 * Passt eine Beschriftung in [maxW] ein: zuerst wird die Schrift bis [minSize] verkleinert,
 * reicht das nicht, wird mit „…“ gekürzt. Setzt p.textSize und liefert den Text zum Zeichnen.
 */
fun fitLabel(p: Paint, s: String, maxW: Float, size: Float, minSize: Float): String {
    p.textSize = size
    if (maxW <= 0f || s.isEmpty()) return s
    val w = p.measureText(s)
    if (w <= maxW) return s
    p.textSize = max(minSize, size * maxW / w * 0.98f)
    if (p.measureText(s) <= maxW) return s
    var t = s
    while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
    return t.trimEnd() + "…"
}
