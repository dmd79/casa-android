package me.synology.davidevinavil.casa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Un pulsante della scheda Accessi: lo stesso cancello puo' esserci via relay,
 * via Bluetooth o in tutti e due i modi (stesso nome del dispositivo e del
 * pulsante): compare una volta sola.
 */
private data class Acc(val key: String, val azione: String, val dev: String, val relay: RelayGate?, val bt: BtGate?)

// come sameName (Model.kt): maiuscole, spazi e trattini non contano
private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/**
 * Scheda Accessi: tutti i pulsanti insieme. Relay se raggiungibile e il
 * cancello e' in linea, altrimenti Bluetooth (da vicino, anche senza internet).
 */
@Composable
fun AccessScreen(app: CasaApp, setWho: (String) -> Unit, onHistory: () -> Unit, onBt: () -> Unit, onRelogin: (String) -> Unit = {}) {
    val store = app.store
    val gb = app.gates
    var relay by remember { mutableStateOf(Relay.parseGates(store.relayGates)) }
    var relayOk by remember { mutableStateOf(store.relayUp) }   // si riparte da com'era l'ultima volta
    var loaded by remember { mutableStateOf(false) }
    val btGates by store.gates.collectAsState()
    val btStati by gb.stati.collectAsState()
    var who by remember { mutableStateOf(store.who) }
    var askWho by remember { mutableStateOf(false) }
    var after by remember { mutableStateOf<Acc?>(null) }
    val mark = remember { mutableStateMapOf<String, String>() }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Stato dal relay: subito e poi ogni 5 s. Da raggiungibile si passa al
    // Bluetooth solo dopo due giri falliti (una richiesta sfortunata alla
    // riapertura non basta); da non raggiungibile si torna al relay alla
    // prima risposta.
    LaunchedEffect(Unit) {
        var fails = 0
        while (true) {
            if (!app.fg) { delay(1000); continue }      // in secondo piano niente rete: non conta
            val cookie = Relay.cookie()          // sul thread principale, come il resto dell'app
            var r = withContext(Dispatchers.IO) { Relay.gateList(cookie) }
            if (r == null && relayOk) { delay(1500); r = withContext(Dispatchers.IO) { Relay.gateList(cookie) } }
            if (r == null && !app.fg) continue          // andata in secondo piano durante la richiesta
            if (r != null) { relay = r; store.relayGates = Relay.gatesJson(r); fails = 0; relayOk = true }
            else if (++fails >= 2 || !relayOk) relayOk = false
            store.relayUp = relayOk
            loaded = true
            delay(if (r != null) 5000 else 3000)
        }
    }

    val accs = buildList {
        relay.forEach { g ->
            g.azioni.forEach { a ->
                add(Acc(norm(g.name) + "|" + a, a, g.name, g, btGates.firstOrNull { norm(it.name) == norm(g.name) && a in it.azioni }))
            }
        }
        btGates.forEach { b ->
            b.azioni.forEach { a ->
                val k = norm(b.name) + "|" + a
                if (none { it.key == k }) add(Acc(k, a, b.name, null, b))
            }
        }
    }
    // ordine scelto con "Organizza" (Order.kt); chiave = dispositivo|pulsante
    val org = rememberOrganizer(app.store, "accessi")
    val shown = accs.ordered(org.order) { it.key }
    val keys = shown.map { it.key }
    // stesso pulsante su piu' dispositivi (due "cancello"): sotto c'e' anche il nome
    val twice = accs.groupBy { it.azione.lowercase() }.filter { it.value.size > 1 }.keys

    // Bluetooth: lettura breve dei gate su cui si apre via Bluetooth, uno per
    // volta, poi ogni minuto. null = non ancora letto, false = non risponde.
    val reach = remember { mutableStateMapOf<String, Boolean>() }

    fun channel(x: Acc): String? = when {
        x.relay != null && relayOk && x.relay.online -> "wifi"
        x.bt != null -> "bt"
        else -> null
    }

    val btWanted = accs.filter { channel(it) == "bt" }.mapNotNull { it.bt?.address }.distinct()
    LaunchedEffect(btWanted.joinToString()) {
        if (btWanted.isEmpty()) return@LaunchedEffect
        delay(600)
        while (true) {
            btWanted.forEach { a -> reach[a] = gb.peek(a) }
            delay(60_000)
        }
    }

    fun fire(x: Acc) {
        if (who.isBlank()) { after = x; askWho = true; return }
        val ch = channel(x)
        if (ch == null) { scope.launch { snack.showSnackbar("${x.azione.replaceFirstChar { it.uppercase() }}: non raggiungibile") }; return }
        if (mark[x.key] == "busy") return
        mark[x.key] = "busy"
        val cookie = Relay.cookie()
        scope.launch {
            var err = if (ch == "wifi") withContext(Dispatchers.IO) { Relay.open(cookie, GateBtn(x.relay!!.id, x.relay.name, x.azione), who) }
                      else gb.open(x.bt!!.address, x.azione, who)
            // relay non raggiunto (comando di sicuro non partito): Bluetooth
            if (ch == "wifi" && err != null && err in Relay.NOT_SENT && x.bt != null) {
                relayOk = false; store.relayUp = false
                if (gb.open(x.bt.address, x.azione, who) == null) err = null
            }
            if (err == null) {
                if (ch == "bt") x.bt?.let { reach[it.address] = true }
                mark[x.key] = "done"; delay(1800); mark.remove(x.key)
            }
            else { mark.remove(x.key); snack.showSnackbar("${x.azione.replaceFirstChar { it.uppercase() }}: $err") }
        }
    }

    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
            OrgTitle("Accessi", org, accs.size > 1)
            if (loaded && !relayOk && accs.isNotEmpty()) {
                val y = Color(0xFFE7C12C)
                Text(if (btGates.isNotEmpty()) "Relay non raggiungibile: apro via Bluetooth, da vicino" else "Relay non raggiungibile",
                    color = y, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp))
                        .background(y.copy(alpha = .12f)).border(BW, y, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(pal.card)
                    .border(BW, pal.border, RoundedCornerShape(12.dp)).clickable { askWho = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("APRI COME", color = pal.muted, fontSize = 12.sp, letterSpacing = 1.sp)
                Text(who.ifBlank { "scegli" }, color = if (who.isBlank()) Color(0xFFE7C12C) else Color(0xFF00C6FF),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            // cancelli con l'accesso scaduto: restano qui, un tocco porta all'accesso
            relay.filter { !it.auth }.forEach { g ->
                RowCard(g.name, "accedi di nuovo") { onRelogin(g.id) }
            }
            if (accs.isEmpty() && relay.none { !it.auth }) Note(if (loaded) "Nessun accesso. Entra nei dispositivi dei cancelli dalla scheda Tutti, oppure abbina un cancello via Bluetooth qui sotto."
                                     else "Leggo gli accessi…", center = true)
            shown.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().orgRow(org, row.map { it.key }).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { x -> OrgTile(org, keys, x.key) { m ->
                        val ch = channel(x)
                        // Bluetooth: stato solo se ha appena risposto, altrimenti "cerco…" / "non raggiungibile"
                        val r = if (ch == "bt") x.bt?.let { reach[it.address] } else true
                        val stato = if (ch == "wifi") x.relay?.stati?.get(x.azione) else if (r == true) x.bt?.let { btStati[it.address]?.get(x.azione) } else null
                        GateButton(m, x.azione, stato, mark[x.key], ch = ch ?: "wifi",
                            sub = if (x.azione.lowercase() in twice) x.dev else null, off = ch == null || r == false,
                            note = if (ch == "bt" && r == null) "cerco…" else null) { fire(x) }
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (accs.isNotEmpty()) Row(Modifier.padding(top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(ICO_WIFI, CH_WIFI, 13.dp, stroke = 2f)
                Text(" via relay    ", color = pal.muted, fontSize = 12.sp)
                SvgIcon(ICO_BT, CH_BT, 13.dp, stroke = 2f)
                Text(" via Bluetooth", color = pal.muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            RowCard("Storico delle aperture", onClick = onHistory)
            LinkCard("Gate Bluetooth", "elenco, aggiungi, dimentica", onClick = onBt)
            Spacer(Modifier.height(16.dp))
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }

    if (askWho) Ask("Apri come", "Il tuo nome: compare nello storico delle aperture.", who, ok = "Salva",
        onDismiss = { askWho = false; after = null }) { v ->
        val n = v.trim().take(15)
        askWho = false
        if (n.isNotBlank()) {
            who = n
            setWho(n)
            after?.let { after = null; fire(it) }
        }
    }
}
