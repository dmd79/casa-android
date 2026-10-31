package me.synology.davidevinavil.casa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

// ============================================================
// Ordine dei riquadri di Clima, Sensori, Accessi e dell'elenco Termostati, scelto dall'utente,
// con lo stesso gesto della Plancia: "✎ Modifica" accanto al titolo, i
// riquadri prendono il bordo tratteggiato, si tiene premuto un riquadro e
// lo si trascina su un altro: prende il suo posto (gli altri scorrono).
// "✎ Fine" chiude. Qui niente misure, caselle vuote o riquadri da
// togliere: sono elenchi dei dispositivi, si cambia solo l'ordine.
// L'ordine resta salvato nell'app (Store, "ord:<pagina>"); i riquadri
// nuovi vanno in fondo, quelli che spariscono tengono il posto se tornano.
// ============================================================

private val ORG_LINE = Color(0xFF2B6C8A)     // tratteggio dei riquadri (come la Plancia)
private val ORG_ON = Color(0xFF00C6FF)       // riquadro su cui si lascia quello trascinato

/** Chiave di un nome per l'ordine: come sameName (maiuscole, spazi e segni non contano). */
fun ordKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** [this] nell'ordine [order] (chiavi); quelli non ancora ordinati in fondo, nell'ordine di prima. */
fun <T> List<T>.ordered(order: List<String>, key: (T) -> String): List<T> {
    if (order.isEmpty()) return this
    val pos = order.withIndex().associate { it.value to it.index }
    return withIndex().sortedWith(compareBy({ pos[key(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
}

class Organizer(private val store: Store, private val name: String) {
    var editing by mutableStateOf(false)
    var order by mutableStateOf(store.order(name))
        private set

    // trascinamento in corso
    var dragKey by mutableStateOf<String?>(null)
        private set
    var dragOff by mutableStateOf(Offset.Zero)
        private set
    var overKey by mutableStateOf<String?>(null)
        private set
    private var startRect = Rect.Zero
    internal val bounds = mutableMapOf<String, Rect>()     // posizione di ogni riquadro sullo schermo

    internal fun start(k: String) { dragKey = k; dragOff = Offset.Zero; overKey = null; startRect = bounds[k] ?: Rect.Zero }
    internal fun drag(k: String, d: Offset, keys: List<String>) {
        dragOff += d
        val c = startRect.center + dragOff
        overKey = keys.firstOrNull { it != k && bounds[it]?.contains(c) == true }
    }
    internal fun drop(k: String, keys: List<String>) {
        overKey?.let { moveTo(keys, k, it) }
        cancel()
    }
    internal fun cancel() { dragKey = null; dragOff = Offset.Zero; overKey = null }

    /** [k] al posto di [target] fra i riquadri mostrati ([keys], gia' in ordine); gli altri scorrono. */
    fun moveTo(keys: List<String>, k: String, target: String) {
        val m = keys.toMutableList()
        val j = m.indexOf(target)
        if (!m.remove(k) || j < 0) return
        m.add(j, k)
        // le chiavi dei riquadri che ora non ci sono restano, in fondo
        order = m + order.filter { it !in m }
        store.setOrder(name, order)
    }

    /** Riga della griglia sopra le altre mentre un suo riquadro e' trascinato. */
    fun rowZ(rowKeys: List<String>) = if (dragKey != null && dragKey in rowKeys) 1f else 0f
}

@Composable
fun rememberOrganizer(store: Store, name: String) = remember(name) { Organizer(store, name) }

/** Riga della griglia: sopra le altre mentre un suo riquadro e' trascinato. */
fun Modifier.orgRow(org: Organizer, rowKeys: List<String>) = zIndex(org.rowZ(rowKeys))

/** Come si fa, sotto il titolo durante la modifica (come nella Plancia). */
const val ORG_HINT = "Tieni premuto un riquadro e trascinalo su un altro: prende il suo posto e gli altri scorrono. Tocca Fine quando hai finito."

/**
 * Pulsante "✎ Modifica" / "✎ Fine", uguale a quello della Plancia (.lnk nel
 * relay): scuro col bordo, chiaro durante la modifica.
 */
@Composable
fun OrgButton(org: Organizer) {
    val on = org.editing
    val bg = if (on) Color(0xFFF2F0EB) else Color(0xFF1B2024)
    val bd = if (on) Color(0xFFF2F0EB) else Color(0xFF2B3236)
    val fg = if (on) Color(0xFF111416) else Color(0xFFC9D1D4)
    val sh = RoundedCornerShape(17.dp)
    Row(
        Modifier.clip(sh).background(bg).border(1.dp, bd, sh)
            .clickable { org.cancel(); org.editing = !org.editing }
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("✎", color = fg, fontSize = 13.5.sp)
        Text(if (on) "Fine" else "Modifica", color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Titolo della pagina con il pulsante della modifica a destra ([show]:
 * almeno due riquadri); durante la modifica, sotto, come si fa.
 */
@Composable
fun OrgTitle(text: String, org: Organizer, show: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Title(text, Modifier.weight(1f))
            if (show || org.editing) OrgButton(org)
        }
        if (org.editing) Note(ORG_HINT)
    }
}

/**
 * Un riquadro della griglia. Fuori dalla modifica e' solo il riquadro;
 * dentro ha il bordo tratteggiato, non si apre al tocco e si trascina
 * tenendolo premuto.
 */
@Composable
fun RowScope.OrgTile(org: Organizer, keys: List<String>, key: String, content: @Composable (Modifier) -> Unit) {
    if (!org.editing) { content(Modifier.weight(1f)); return }
    val dragging = org.dragKey == key
    val over = org.overKey == key
    Box(
        Modifier.weight(1f)
            .onGloballyPositioned { org.bounds[key] = it.boundsInRoot() }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) {
                    translationX = org.dragOff.x; translationY = org.dragOff.y
                    scaleX = 1.04f; scaleY = 1.04f; alpha = .9f
                }
            }
    ) {
        content(Modifier.fillMaxWidth())
        // sopra il riquadro: prende i tocchi (il riquadro non si apre) e il trascinamento
        Box(
            Modifier.matchParentSize()
                .background(if (over) ORG_ON.copy(alpha = .14f) else Color.Transparent, RoundedCornerShape(R_CARD))
                .dashed(if (over || dragging) ORG_ON else ORG_LINE, R_CARD, if (over) 2.dp else 1.5.dp)
                .pointerInput(key, keys) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { org.start(key) },
                        onDrag = { ch, d -> ch.consume(); org.drag(key, d, keys) },
                        onDragEnd = { org.drop(key, keys) },
                        onDragCancel = { org.cancel() },
                    )
                }
        )
    }
}

/**
 * Come [OrgTile] ma per un elenco a tutta larghezza (Termostati). [modifier]
 * resta fuori dal bordo tratteggiato (es. lo spazio sotto la riga).
 */
@Composable
fun OrgItem(org: Organizer, keys: List<String>, key: String, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    if (!org.editing) { content(modifier); return }
    val dragging = org.dragKey == key
    val over = org.overKey == key
    Box(
        modifier.fillMaxWidth()
            .onGloballyPositioned { org.bounds[key] = it.boundsInRoot() }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) {
                    translationX = org.dragOff.x; translationY = org.dragOff.y
                    scaleX = 1.03f; scaleY = 1.03f; alpha = .9f
                }
            }
    ) {
        content(Modifier.fillMaxWidth())
        Box(
            Modifier.matchParentSize()
                .background(if (over) ORG_ON.copy(alpha = .14f) else Color.Transparent, RoundedCornerShape(R_CARD))
                .dashed(if (over || dragging) ORG_ON else ORG_LINE, R_CARD, if (over) 2.dp else 1.5.dp)
                .pointerInput(key, keys) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { org.start(key) },
                        onDrag = { ch, d -> ch.consume(); org.drag(key, d, keys) },
                        onDragEnd = { org.drop(key, keys) },
                        onDragCancel = { org.cancel() },
                    )
                }
        )
    }
}

/** Bordo tratteggiato arrotondato, come i riquadri della Plancia in modifica. */
private fun Modifier.dashed(c: Color, r: Dp, w: Dp) = drawWithContent {
    drawContent()
    val sw = w.toPx()
    drawRoundRect(
        c, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(r.toPx() - sw / 2),
        style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
    )
}
