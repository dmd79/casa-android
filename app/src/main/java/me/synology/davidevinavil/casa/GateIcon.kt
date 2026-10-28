package me.synology.davidevinavil.casa

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

/** Disegni come nella pagina del gate (gate.js, GATE_ICONE): parti fisse e parti che si muovono. */
private class GateDraw(val fixed: List<String>, val moving: List<String>, val arrow: String, val ms: Int)

private val G_GARAGE = GateDraw(listOf("M3 21V9l9-6 9 6v12", "M6 21V11h12v10"), listOf("M6 13.5h12M6 16h12M6 18.5h12M6 21h12"),
    "M12 20v-4.5M9.8 17.6l2.2-2.2 2.2 2.2", 1600)
private val G_CANCELLO = GateDraw(listOf("M2.8 21V5.6M21.2 21V5.6M1.8 21h20.4"),
    listOf("M4.2 20.2V10.2Q8 7.4 11.2 7.8v12.4zM7.7 8.6v11.6", "M19.8 20.2V10.2Q16 7.4 12.8 7.8v12.4zM16.3 8.6v11.6"),
    "M12 19.5v-7M9.8 14.7l2.2-2.2 2.2 2.2", 1600)
private val G_CANCELLINO = GateDraw(listOf("M5.5 21V7.5M18.5 21V7.5M3.5 21h17", "M4.2 6a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z",
    "M17.2 6a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z"),
    listOf("M7 20.5V11.2Q12 7 17 11.2v9.3zM10.3 9.6v10.9M13.7 9.6v10.9M15.3 14.6h.01"),
    "M9.5 15h5.5M13 13l2 2-2 2", 1400)

/**
 * Icona animata di un accesso, come nella pagina del gate: il portone del
 * garage sale, le ante del cancello e del cancellino si aprono, poi la
 * freccia. Alla prima comparsa nessuna animazione (si mostra com'e').
 */
@Composable
fun GateIcon(azione: String, aperto: Boolean, color: Color, size: Dp) {
    val g = when (azione.lowercase()) { "garage" -> G_GARAGE; "cancellino" -> G_CANCELLINO; else -> G_CANCELLO }
    val t by animateFloatAsState(if (aperto) 1f else 0f, tween(g.ms, easing = FastOutSlowInEasing), label = "gate")
    val fixed = remember(g) { g.fixed.map(::p) }
    val moving = remember(g) { g.moving.map(::p) }
    val arrow = remember(g) { p(g.arrow) }
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            val st = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fixed.forEach { drawPath(it, color, style = st) }
            when (g) {
                // portone: sale di 8,4 dentro il vano
                G_GARAGE -> clipRect(6f, 11f, 18f, 21.2f) { translate(0f, -8.4f * t) { drawPath(moving[0], color, style = st) } }
                // ante: si chiudono sui pilastri (16% della larghezza)
                G_CANCELLO -> {
                    antaScale(moving[0], 4.2f, 1f - .84f * t, color, st)
                    antaScale(moving[1], 19.8f, 1f - .84f * t, color, st)
                }
                else -> antaScale(moving[0], 7f, 1f - .82f * t, color, st)
            }
            // freccia a fine corsa
            val a = ((t - .75f) / .25f).coerceIn(0f, 1f)
            if (a > 0f) drawPath(arrow, color.copy(alpha = color.alpha * a), style = st)
        }
    }
}

private fun DrawScope.antaScale(path: Path, px: Float, sx: Float, color: Color, st: Stroke) {
    withTransform({ scale(sx, 1f, pivot = Offset(px, 15f)) }) { drawPath(path, color, style = st) }
}
