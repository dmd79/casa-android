package me.synology.davidevinavil.casa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

/** Un condizionatore della pagina Clima: via relay, via Bluetooth o tutti e due (stesso nome). */
private data class Ac(val name: String, val relay: RelayDev?, val bt: Saved?)

// colori dei modi, come la Plancia del relay
private fun acColor(m: String) = when (m) {
    "cool" -> Color(0xFF00C6FF); "heat" -> Color(0xFFFF6B35); "heat_cool", "auto" -> Color(0xFFA78BFA)
    "dry" -> Color(0xFF39D19C); "fan_only", "fan" -> Color(0xFFE7C12C); else -> Color(0xFF6B7479)
}
private fun acLabel(m: String) = when (m) {
    "cool" -> "Raffresca"; "heat" -> "Riscalda"; "heat_cool", "auto" -> "Auto"
    "dry" -> "Deumidifica"; "fan_only", "fan" -> "Ventola"; else -> "Spento"
}
private fun t1(v: Double?) = v?.let { String.format(Locale.ITALY, "%.1f", it) } ?: "--"
private fun t0(v: Double?) = v?.let { String.format(Locale.ITALY, if (it % 1.0 == 0.0) "%.0f" else "%.1f", it) }

/**
 * Pagina Clima: tutti i condizionatori in riquadri. Relay se raggiungibile e in
 * linea, altrimenti Bluetooth: stato letto con un collegamento breve, uno per
 * volta, solo per quelli abbinati; se non rispondono "Non raggiungibile".
 */
@Composable
fun ClimaScreen(app: CasaApp, openRelay: (RelayDev) -> Unit, openBt: (String) -> Unit, onList: () -> Unit) {
    val store = app.store
    val ble = app.ble
    var devs by remember { mutableStateOf(Relay.parseList(store.relayList).filter { it.type == "ac" }) }
    var relayOk by remember { mutableStateOf(store.relayUp) }   // si riparte da com'era l'ultima volta
    var loaded by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<RelayDev?>(null) }       // scelta del sensore (tocco lungo)
    val acChoice by app.acSrc.choice.collectAsState()
    val acOpts by app.acSrc.options.collectAsState()
    val bts by store.devices.collectAsState()
    val snaps by ble.snaps.collectAsState()

    // relay: come in Accessi (da raggiungibile al Bluetooth dopo due giri
    // falliti, da non raggiungibile al relay alla prima risposta)
    LaunchedEffect(Unit) {
        var fails = 0
        while (true) {
            if (!app.fg) { delay(1000); continue }      // in secondo piano niente rete: non conta
            val cookie = Relay.cookie()
            var raw = withContext(Dispatchers.IO) { Relay.listRaw(cookie) }
            if (raw == null && relayOk) { delay(1500); raw = withContext(Dispatchers.IO) { Relay.listRaw(cookie) } }
            if (raw == null && !app.fg) continue        // andata in secondo piano durante la richiesta
            if (raw != null) {
                store.relayList = raw; devs = Relay.parseList(raw).filter { it.type == "ac" }; fails = 0; relayOk = true
                withContext(Dispatchers.IO) { app.acSrc.refresh(cookie) }      // sensori scelti per la temperatura (al massimo ogni minuto)
            }
            else if (++fails >= 2 || !relayOk) relayOk = false
            store.relayUp = relayOk
            loaded = true
            delay(if (raw != null) 5000 else 3000)
        }
    }

    fun same(a: String, b: String) = sameName(a, b)
    val list = buildList {
        devs.forEach { d -> add(Ac(d.name, d, bts.firstOrNull { same(it.name, d.name) })) }
        bts.forEach { b -> if (none { same(it.name, b.name) }) add(Ac(b.name, null, b)) }
    }
    fun viaRelay(x: Ac) = x.relay != null && (relayOk && x.relay.online || x.bt == null)

    // Bluetooth: lettura breve dei condizionatori non coperti dal relay, uno
    // per volta, poi ogni minuto finche' la pagina e' aperta.
    // null = non ancora letto, false = non risponde.
    val reach = remember { mutableStateMapOf<String, Boolean>() }
    val btWanted = list.filter { !viaRelay(it) && it.bt != null }.map { it.bt!!.address }
    LaunchedEffect(btWanted.joinToString()) {
        if (btWanted.isEmpty()) return@LaunchedEffect
        delay(600)          // prima il relay (se risponde, il Bluetooth non serve)
        while (true) {
            btWanted.forEach { a -> reach[a] = ble.peek(a) != null }
            delay(60_000)
        }
    }

    Column(Modifier.fillMaxSize().background(pal.bg).padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
        Title("Clima", Modifier.padding(bottom = 14.dp))
        if (loaded && !relayOk && list.isNotEmpty()) {
            val y = Color(0xFFE7C12C)
            Text(if (bts.isNotEmpty()) "Relay non raggiungibile: apro via Bluetooth, da vicino" else "Relay non raggiungibile",
                color = y, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp))
                    .background(y.copy(alpha = .12f)).border(1.dp, y, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
        }
        if (list.isEmpty()) Note(if (loaded) "Nessun condizionatore. Entra nei condizionatori dalla scheda Tutti, oppure abbinane uno via Bluetooth qui sotto."
                                 else "Leggo i condizionatori…", center = true)
        list.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { x ->
                    if (viaRelay(x)) {
                        val d = x.relay!!
                        val on = d.online && relayOk
                        // temperatura da un sensore scelto (umidita' compresa), altrimenti dello split
                        val src = acChoice[d.id]?.let { k -> acOpts.firstOrNull { it.key == k } ?: app.acSrc.of(d.id) }
                        AcCard(Modifier.weight(1f), x.name, src?.t ?: if (src == null) d.cur else null, if (d.mode != "off") d.tgt else null,
                            d.mode, "wifi", if (on) null else "Non raggiungibile", hum = src?.h, src = src?.name,
                            onLong = { pickFor = d }) { openRelay(d) }
                    } else {
                        val b = x.bt!!
                        val s = snaps[b.address]
                        val r = reach[b.address]
                        val status = when {
                            !ble.isBonded(b.address) -> "Da abbinare"
                            r == null -> "Cerco…"
                            r == false || s == null -> "Non raggiungibile"
                            else -> null
                        }
                        AcCard(Modifier.weight(1f), x.name, s?.current, if (s != null && s.mode != "off") s.target else null,
                            s?.mode ?: "off", "bt", status) { openBt(b.address) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        LinkCard("Condizionatori Bluetooth", "elenco, aggiungi, dimentica", onClick = onList)
        if (devs.isNotEmpty()) Note("Tieni premuto un riquadro per scegliere la temperatura mostrata.", center = true)
        Spacer(Modifier.height(16.dp))
    }
    pickFor?.let { d -> AcSrcSheet(app, d.id, d.name) { pickFor = null } }
}

/**
 * Riquadro di un condizionatore. [status]: al posto del modo, in grigio e senza
 * valori (Cerco…, Non raggiungibile…). [src]: temperatura (e [hum]) da quel
 * sensore invece che dallo split. Due decimali, niente a capo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AcCard(modifier: Modifier, name: String, cur: Double?, tgt: Double?, mode: String, ch: String, status: String?,
                   hum: Double? = null, src: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val off = status != null || mode == "off" || mode.isBlank()
    val col = if (status != null) Color(0xFF6B7479) else acColor(mode)
    Column(
        // fondo e bordo uguali ai riquadri dei Sensori; il modo si vede dalla scritta colorata in fondo
        modifier.heightIn(min = 128.dp).clip(RoundedCornerShape(16.dp)).background(pal.card)
            .border(1.dp, pal.border, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        // stesso schema dei riquadri dei Sensori: nome e canale; temperatura con
        // l'icona della provenienza; umidita' con la goccia; in fondo impostata e modo
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Color(0xFFC9D1D4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SvgIcon(if (ch == "bt") ICO_BT else ICO_WIFI, chColor(ch, status != null), 14.dp, stroke = 2f)
        }
        ValueRows(if (status != null) null else cur, if (src != null && status == null) hum else null, hasHum = src != null && status == null,
            icon = if (src != null) ICO_THERMO else ICO_AC, iconColor = if (src != null) CH_SENS else Color(0xFF8A9599), dim = status != null)
        val tg = (if (status != null) null else t0(tgt))?.let { "→ $it °C  " }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tg != null) Text(tg, color = Color(0xFF8A9599), fontSize = 12.sp, maxLines = 1, softWrap = false)
            Text("● " + (status ?: acLabel(mode)).uppercase(), color = if (off) Color(0xFF6B7479) else col,
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

// Colori dei simboli (Clima e Sensori): relay verde, Bluetooth blu, sensore
// scelto giallo, umidita' azzurra come sul termostato; grigio se non raggiungibile
val CH_WIFI = Color(0xFF39D19C)
val CH_BT = Color(0xFF4F8CFF)
val CH_SENS = Color(0xFFE7C12C)
val HUM_BLUE = Color(0xFF00C6FF)
fun chColor(ch: String, off: Boolean) = if (off) Color(0xFF5A6266) else if (ch == "bt") CH_BT else CH_WIFI

val ICO_THERMO = listOf(IconPart("M14 14.8V4a2 2 0 0 0-4 0v10.8a4 4 0 1 0 4 0z"))
val ICO_DROP = listOf(IconPart("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z"))
val ICO_AC = listOf(IconPart("M4.5 5h15a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"), IconPart("M6 11h12M8 17v2M12 17v3M16 17v2"))

/**
 * Temperatura con l'icona della provenienza (termometro giallo = sensore,
 * condizionatore grigio = split) e, sotto, l'umidita' con la goccia azzurra.
 * Stessa altezza con o senza umidita'. Due decimali, niente a capo.
 */
@Composable
fun ValueRows(t: Double?, h: Double?, hasHum: Boolean, icon: List<IconPart>, iconColor: Color, dim: Boolean) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp)) { SvgIcon(icon, if (dim) Color(0xFF5A6266) else iconColor, 17.dp, stroke = 1.8f) }
        Text(if (dim) "--" else d2(t), color = if (dim) pal.muted else pal.text, fontSize = 30.sp, fontWeight = FontWeight.Light,
            maxLines = 1, softWrap = false)
        Text(" °C", color = Color(0xFF8A9599), fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 10.dp))
    }
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (hasHum) {
            Box(Modifier.width(22.dp)) { SvgIcon(ICO_DROP, if (dim) Color(0xFF5A6266) else HUM_BLUE, 16.dp, stroke = 2f) }
            Text("${if (dim) "--" else d2(h)} %", color = if (dim) pal.muted else HUM_BLUE, fontSize = 16.sp, maxLines = 1, softWrap = false)
        }
    }
}
