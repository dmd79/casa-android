package me.synology.davidevinavil.casa

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Pal(
    val bg: Color, val card: Color, val border: Color, val accent: Color, val text: Color,
    val value: Color, val muted: Color, val sel: Color, val danger: Color, val ok: Color, val onAccent: Color,
    /** Tema chiaro (segue Android). */
    val light: Boolean = false,
    /** Nome nei riquadri, scritte secondarie, simboli spenti, stato spento, avvisi, errori. */
    val name: Color = Color(0xFFC9D1D4), val sub: Color = Color(0xFF8A9599), val dim: Color = Color(0xFF5A6266),
    val off: Color = Color(0xFF6B7479), val warn: Color = Color(0xFFE7C12C), val bad: Color = Color(0xFFFF5A5A),
)

private val DarkPal = Pal(
    bg = Color(0xFF0D0D0D), card = Color(0xFF161A1C), border = Color(0xFF26343A), accent = Color(0xFF2FC0FF),
    text = Color(0xFFF2F0EB), value = Color(0xFFB3AFA8), muted = Color(0xFF666666), sel = Color(0xFF122831),
    danger = Color(0xFFF5075C), ok = Color(0xFF39D19C), onAccent = Color(0xFF0D0D0D),
)
private val LightPal = Pal(
    bg = Color(0xFFF4F3EF), card = Color.White, border = Color(0xFFD9D6CF), accent = Color(0xFF0A7FBF),
    text = Color(0xFF1A1A1A), value = Color(0xFF5F5B55), muted = Color(0xFF8A867F), sel = Color(0xFFDCEEFA),
    danger = Color(0xFFD6004C), ok = Color(0xFF1FA77A), onAccent = Color.White,
    light = true, name = Color(0xFF33383B), sub = Color(0xFF6B7479), dim = Color(0xFFA7AEB1),
    off = Color(0xFF8F969A), warn = Color(0xFFB58900), bad = Color(0xFFD63A3A),
)

val LocalPal = staticCompositionLocalOf { DarkPal }

/**
 * Bordo unico di tutti i riquadri (relay, Wi-Fi e Bluetooth), come la Plancia:
 * sempre 1 dp. Selezione e stato si vedono dal colore, non dallo spessore.
 */
val BW = 1.dp
/** Angoli dei riquadri: uguali in tutte le pagine. */
val R_CARD = 16.dp
/** Fondo e bordo nella tonalita' di un colore (modo del clima, stato del cancello). */
fun tintBg(card: Color, c: Color) = androidx.compose.ui.graphics.lerp(card, c, 0.13f)
fun tintBd(card: Color, c: Color) = androidx.compose.ui.graphics.lerp(card, c, 0.55f)
val pal: Pal @Composable get() = LocalPal.current

/**
 * Colore di un modo o di uno stato usato per scritte e simboli: sul fondo
 * chiaro i colori vivi (azzurro, giallo) si leggono male, si scuriscono.
 */
@Composable
fun ink(c: Color): Color = if (pal.light) androidx.compose.ui.graphics.lerp(c, Color.Black, 0.28f) else c

/** Pastiglia del modo (Clima, Termostati): scura col colore del modo, in chiaro piena del colore con la scritta bianca. */
@Composable
fun pillBg(c: Color): Color = if (pal.light) ink(c) else Color.Black.copy(alpha = 0.45f)
@Composable
fun pillFg(c: Color): Color = if (pal.light) Color.White else c

/** Colore di ogni modo, uguale al termostato e alla pagina web. */
fun modeColor(m: String) = when (m) {
    "auto" -> Color(0xFF9670D6); "cool" -> Color(0xFF2FC0FF); "heat" -> Color(0xFFFF6600)
    "dry" -> Color(0xFF39D19C); "fan" -> Color(0xFFB3AFA8); else -> Color(0xFF666666)
}
fun modeLabel(m: String) = when (m) {
    "auto" -> "Auto"; "cool" -> "Freddo"; "heat" -> "Caldo"; "dry" -> "Deumid."; "fan" -> "Ventola"; else -> "Spento"
}

/**
 * Chiaro o scuro come Android. Le pagine del relay seguono lo stesso tema
 * (prefers-color-scheme della WebView); quelle dei dispositivi restano scure.
 */
@Composable
fun ClimaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(primary = p.accent, background = p.bg, surface = p.bg, onSurface = p.text)
        else lightColorScheme(primary = p.accent, background = p.bg, surface = p.bg, onSurface = p.text)
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ── icone dai tracciati SVG della pagina web (viewBox 24×24) ──

/** [tint]: zona che sull'icona accesa si riempie (colore al 28%), il contorno resta. */
class IconPart(val d: String, val fill: Boolean = false, val color: Color? = null, val tint: Boolean = false)

@Composable
fun SvgIcon(parts: List<IconPart>, color: Color, size: Dp, stroke: Float = 2f, on: Boolean = false) {
    val paths = remember(parts) { parts.map { PathParser().parsePathString(it.d).toPath() } }
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            paths.forEachIndexed { i, p ->
                val part = parts[i]
                if (on && part.tint) drawPath(p, (part.color ?: color).copy(alpha = .28f), style = Fill)
                drawPath(p, part.color ?: color,
                    style = if (part.fill) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/** Rettangolo con gli angoli arrotondati (come <rect rx> dell'SVG). */
private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

/** Icone della barra: stesso tratto (1.8), angoli arrotondati, zona piena sulla voce aperta. */
object NavIcons {
    val house = listOf(IconPart("M4 10.5L12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19z", tint = true), IconPart("M9.5 20.5v-5.5h5v5.5"))
    val dash = listOf(IconPart(rrect(3.5f, 3.5f, 7.5f, 9f, 2f), tint = true), IconPart(rrect(13f, 3.5f, 7.5f, 5.5f, 2f), tint = true),
        IconPart(rrect(13f, 11f, 7.5f, 9.5f, 2f), tint = true), IconPart(rrect(3.5f, 14.5f, 7.5f, 6f, 2f), tint = true))
    val heat = listOf(IconPart("M12 3.5c.6 3 4.8 5.2 4.8 10a4.8 4.8 0 0 1-9.6 0c0-2.4 1.4-3.7 2.1-5.6 1 .9 1.7 1.9 2 3.1.8-2.3.7-5 .7-7.5z", tint = true), IconPart("M5 20.5h14"))
    val ac = listOf(IconPart(rrect(3f, 4.5f, 18f, 8f, 2.5f), tint = true), IconPart("M6.5 9.5h11"), IconPart("M8 15.5q-1 1.5 0 3M12 15.5q-1 1.5 0 3M16 15.5q-1 1.5 0 3"))
    val gate = listOf(IconPart("M4 20.5V10q8-5 16 0v10.5z", tint = true), IconPart("M8.5 7.6v12.9M12 7v13.5M15.5 7.6v12.9"))
    val sens = listOf(IconPart("M9.5 5a2.5 2.5 0 0 1 5 0v8.6a4.5 4.5 0 1 1-5 0z", tint = true), IconPart("M12 17.5v-7"), IconPart("M18 5.5h2.5M18 8.5h2.5"))
    val cam = listOf(IconPart("M3.5 7.5l13-3.5 1.6 5.8-13 3.5z", tint = true), IconPart("M18.1 9.8l2.4-.7M11 12l1.3 4.3H6.5M6.5 13.5v6"))
    val more = listOf(IconPart(circle(12f, 12f, 8.5f), tint = true), IconPart(circle(8.2f, 12f, 1.2f), fill = true),
        IconPart(circle(12f, 12f, 1.2f), fill = true), IconPart(circle(15.8f, 12f, 1.2f), fill = true))
}

object Icons {
    val power = listOf(IconPart("M12 3v8"), IconPart("M6.3 6.3a8 8 0 1 0 11.4 0"))
    val up = listOf(IconPart("M6 15l6-6 6 6"))
    val down = listOf(IconPart("M6 9l6 6 6-6"))
    val gear = listOf(IconPart(circle(12f, 12f, 3.2f)),
        IconPart("M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"))
    val bt = listOf(IconPart("M7 7l10 10-5 5V2l5 5L7 17"))
    val plus = listOf(IconPart("M12 5v14"), IconPart("M5 12h14"))
    val flame = listOf(IconPart("M12 3c.5 3.5 5 5.5 5 10.5a5 5 0 0 1-10 0c0-2.5 1.5-3.8 2.2-5.8 1 1 1.8 2 2.1 3.3.9-2.4.7-5.2.7-8z"))
    val dash = listOf(IconPart("M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z"))
    val dots = listOf(IconPart(circle(5f, 12f, 1.8f), fill = true), IconPart(circle(12f, 12f, 1.8f), fill = true), IconPart(circle(19f, 12f, 1.8f), fill = true))
    val gauge = listOf(IconPart("M10 4a2 2 0 1 1 4 0v9.5a4 4 0 1 1-4 0z"), IconPart("M12 17.5v-6"))
    val users = listOf(IconPart(circle(9f, 8f, 3.5f)), IconPart("M2.5 20c.5-3.5 3.2-5.5 6.5-5.5s6 2 6.5 5.5"), IconPart("M16 4.6a3.5 3.5 0 0 1 0 6.8M18.5 14.8c1.8.8 2.8 2.7 3 5.2"))
    val cam = listOf(IconPart("M4.5 6.5h9a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"), IconPart("M15.5 10.5l6-3.5v10l-6-3.5"))
    val house = listOf(IconPart("M3 11l9-8 9 8M5 9.5V21h14V9.5M10 21v-6h4v6"))
    val gate = listOf(IconPart("M3 21V6M21 21V6M3 9q4.5-3 9 0q4.5-3 9 0M7 8v13M12 9v12M17 8v13"))
    val cool = listOf(IconPart("M12 2v20M3.3 7l17.4 10M3.3 17L20.7 7M9 4l3 2 3-2M9 20l3-2 3 2"))
    val heat = listOf(IconPart(circle(12f, 12f, 4.5f), fill = true),
        IconPart("M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M4.6 19.4l2.1-2.1M17.3 6.7l2.1-2.1"))
    val dry = listOf(IconPart("M12 2C9 7 6 10 6 14a6 6 0 0 0 12 0c0-4-3-7-6-12z", fill = true))
    fun fan(hole: Color) = listOf(
        IconPart(circle(12f, 6.5f, 4.2f), fill = true), IconPart(circle(6.5f, 15.5f, 4.2f), fill = true),
        IconPart(circle(17.5f, 15.5f, 4.2f), fill = true), IconPart(circle(12f, 12f, 1.8f), fill = true, color = hole))
}

@Composable
fun ModeIcon(mode: String, color: Color, size: Dp) {
    when (mode) {
        "auto" -> Text("A", color = color, fontSize = (size.value * 0.85f).sp)
        "cool" -> SvgIcon(Icons.cool, color, size)
        "heat" -> SvgIcon(Icons.heat, color, size)
        "dry" -> SvgIcon(Icons.dry, color, size)
        else -> SvgIcon(Icons.fan(pal.bg), color, size)
    }
}

// ── pezzi comuni ──

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, danger: Boolean = false,
        enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { danger -> pal.danger; secondary -> pal.card; else -> pal.accent }
    val fg = when { danger -> Color.White; secondary -> pal.text; else -> pal.onAccent }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(if (enabled) bg else pal.border)
            .then(if (secondary) Modifier.border(BorderStroke(BW, pal.border), RoundedCornerShape(32.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 20.sp) }
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = pal.text, fontSize = 28.sp, maxLines = 1)

@Composable
fun Sub(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = pal.muted, fontSize = 16.sp)

@Composable
fun Note(text: String, modifier: Modifier = Modifier, center: Boolean = false) =
    Text(text, modifier.padding(top = 8.dp), color = pal.muted,
        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp,
            textAlign = if (center) TextAlign.Center else TextAlign.Start))

@Composable
fun Section(text: String) = Text(text, Modifier.padding(top = 22.dp, bottom = 8.dp, start = 2.dp), color = pal.muted, fontSize = 13.sp)

@Composable
fun RowCard(label: String, value: String = "", onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(R_CARD)).background(pal.card)
            .border(BW, pal.border, RoundedCornerShape(R_CARD)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        // una riga sola: se non ci sta si accorcia la scritta, non si va a capo
        Text(label, color = pal.text, fontSize = 18.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = pal.value, fontSize = 15.sp, maxLines = 1, softWrap = false)
            Text("  ›", color = pal.accent, fontSize = 26.sp)
        }
    }
}
