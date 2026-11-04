package me.synology.davidevinavil.casa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

// ============================================================
// Pagina Termostati: tutti i termostati del relay in riquadri, come Clima
// e Sensori (con un solo termostato la barra apre subito la sua pagina).
// Colori come nella Plancia e come l'alone del quadrante del termostato:
// riscaldamento spento blu, acceso (pronto) arancio, fiamma accesa rosso;
// scritta "Acqua calda" quando la fiamma e' per l'acqua sanitaria.
// Un tocco apre la home del termostato; ordine con "✎ Modifica" (Order.kt).
// ============================================================

private val TH_OFF = Color(0xFF2F96FF)
private val TH_IDLE = Color(0xFFFF8C00)
private val TH_FLAME = Color(0xFFFF281E)

private fun tgt0(v: Double?) = v?.let { String.format(Locale.ITALY, if (it % 1.0 == 0.0) "%.0f" else "%.1f", it) }

/** Stato della caldaia: dal relay (ch/flame); con un relay vecchio dal modo e dall'azione del clima. */
private fun thState(d: RelayDev): String {
    val flame = d.flame ?: d.heat
    val ch = d.ch ?: (d.mode.isNotBlank() && d.mode != "off")
    return if (flame) "flame" else if (ch) "idle" else "off"
}

@Composable
fun ThermoScreen(app: CasaApp, openRelay: (RelayDev) -> Unit) {
    val store = app.store
    var devs by remember { mutableStateOf(Relay.parseList(store.relayList).filter { it.type == "thermostat" }) }
    var relayOk by remember { mutableStateOf(store.relayUp) }

    // stato dal relay ogni 5 s, come la pagina Clima
    LaunchedEffect(Unit) {
        var fails = 0
        while (true) {
            if (!app.fg) { delay(1000); continue }
            val cookie = Relay.cookie()
            val raw = withContext(Dispatchers.IO) { Relay.listRaw(cookie) }
            if (raw != null) {
                store.relayList = raw; devs = Relay.parseList(raw).filter { it.type == "thermostat" }; fails = 0; relayOk = true
            } else if (++fails >= 2) relayOk = false
            store.relayUp = relayOk
            delay(if (raw != null) 5000 else 3000)
        }
    }

    val org = rememberOrganizer(store, "termostati")
    val shown = devs.ordered(org.order) { it.id }
    val keys = shown.map { it.id }

    Column(Modifier.fillMaxSize().background(pal.bg).padding(horizontal = 20.dp, vertical = 16.dp)) {
        OrgTitle("Termostati", org, shown.size > 1)
        // titolo e Modifica fermi in alto, sotto si scorre
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (shown.isEmpty()) Note("Nessun termostato. Entra nei termostati dalla scheda Tutti.", center = true)
            shown.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().orgRow(org, row.map { it.id }).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { d -> OrgTile(org, keys, d.id) { m ->
                        val status = when {
                            !d.auth -> "accedi di nuovo"
                            !relayOk || !d.online -> "non raggiungibile"
                            else -> null
                        }
                        ThermoCard(m, d, status) { openRelay(d) }
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ThermoCard(modifier: Modifier, d: RelayDev, status: String?, onClick: () -> Unit) {
    val st = thState(d)
    val off = status != null
    val col = when { off -> pal.off; st == "flame" -> TH_FLAME; st == "idle" -> TH_IDLE; else -> TH_OFF }
    val label = status ?: when (st) { "flame" -> if (d.dhw) "Acqua calda" else "Riscalda"; "idle" -> "In attesa"; else -> "Spento" }
    // fondo e bordo nella tonalita' dello stato (scollegato o accesso scaduto: neutro)
    val bg = if (off) pal.card else tintBg(pal.card, col)
    val bd = if (off) pal.border else tintBd(pal.card, col)
    Column(
        modifier.heightIn(min = 128.dp).clip(RoundedCornerShape(R_CARD)).background(bg)
            .border(BW, bd, RoundedCornerShape(R_CARD))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.name, color = pal.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SvgIcon(Icons.flame, if (off) pal.dim else ink(col), 15.dp, stroke = 2f)
        }
        ValueRows(if (off) null else d.cur, if (off) null else d.hum, hasHum = d.hum != null && !off,
            icon = ICO_THERMO, iconColor = CH_SENS, dim = off)
        val tg = (if (off) null else tgt0(d.tgt))?.let { "→ $it °C  " }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tg != null) Text(tg, color = pal.sub, fontSize = 12.sp, maxLines = 1, softWrap = false)
            // scritta dello stato su una pastiglia scura: resta leggibile sul fondo colorato
            Text("● " + label.uppercase(), color = if (off) col else pillFg(col),
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (off) (if (pal.light) pal.bg else Color.Black.copy(alpha = 0.45f)) else pillBg(col))
                    .padding(horizontal = 7.dp, vertical = 2.dp))
        }
    }
}
