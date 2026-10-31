package me.synology.davidevinavil.casa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// Ordine dei riquadri di Clima, Sensori e Accessi, scelto dall'utente.
// "Organizza" accanto al titolo: su ogni riquadro compaiono due frecce,
// ◀ lo sposta prima e ▶ dopo (nell'ordine di lettura della griglia).
// L'ordine resta salvato nell'app (Store, "ord:<pagina>"); i riquadri
// nuovi vanno in fondo, quelli che spariscono tengono il loro posto se
// tornano.
// ============================================================

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

    /** Sposta [k] di [d] posti fra i riquadri mostrati ([keys], gia' in ordine). */
    fun move(keys: List<String>, k: String, d: Int) {
        val i = keys.indexOf(k)
        val j = i + d
        if (i < 0 || j !in keys.indices) return
        val m = keys.toMutableList()
        m[i] = m[j].also { m[j] = m[i] }
        // le chiavi dei riquadri che ora non ci sono restano, in fondo
        order = m + order.filter { it !in m }
        store.setOrder(name, order)
    }
}

@Composable
fun rememberOrganizer(store: Store, name: String) = remember(name) { Organizer(store, name) }

/** Titolo della pagina con "Organizza" / "Fine" a destra ([show]: almeno due riquadri). */
@Composable
fun OrgTitle(text: String, org: Organizer, show: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Title(text, Modifier.weight(1f))
        if (show || org.editing) Text(
            if (org.editing) "Fine" else "Organizza",
            color = Color(0xFF00C6FF), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { org.editing = !org.editing }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * Un riquadro della griglia. Fuori da "Organizza" e' solo il riquadro;
 * dentro, il riquadro si scurisce, non si apre al tocco e mostra le frecce.
 */
@Composable
fun RowScope.OrgTile(org: Organizer, keys: List<String>, key: String, content: @Composable (Modifier) -> Unit) {
    if (!org.editing) { content(Modifier.weight(1f)); return }
    val i = keys.indexOf(key)
    Box(Modifier.weight(1f)) {
        content(Modifier.fillMaxWidth())
        Box(
            Modifier.matchParentSize().clip(RoundedCornerShape(R_CARD)).background(Color.Black.copy(alpha = .55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OrgArrow("◀", i > 0) { org.move(keys, key, -1) }
                OrgArrow("▶", i in 0 until keys.lastIndex) { org.move(keys, key, 1) }
            }
        }
    }
}

@Composable
private fun OrgArrow(sym: String, enabled: Boolean, onClick: () -> Unit) {
    val c = if (enabled) Color(0xFF00C6FF) else Color(0xFF5A6266)
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.copy(alpha = .15f)).border(BW, c, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(sym, color = c, fontSize = 18.sp) }
}
