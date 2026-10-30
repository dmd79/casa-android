package me.synology.davidevinavil.casa

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class GScr { LIST, SCAN, PAIR, CTL, FW }

// colori della pagina del dispositivo (gate.css)
private val G_BLUE = Color(0xFF00C6FF)
private val G_YEL = Color(0xFFE7C12C)
private val G_DIM = Color(0xFF8A93A3)

// icone dei pulsanti, come gate.js (da chiusi)
private fun dot(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
private val ICO_GARAGE = listOf(IconPart("M3 21V9l9-6 9 6v12"), IconPart("M6 21V11h12v10"), IconPart("M6 13.5h12M6 16h12M6 18.5h12M6 21h12"))
private val ICO_CANCELLO = listOf(IconPart("M2.8 21V5.6M21.2 21V5.6M1.8 21h20.4"),
    IconPart("M4.2 20.2V10.2Q8 7.4 11.2 7.8v12.4z"), IconPart("M7.7 8.6v11.6"),
    IconPart("M19.8 20.2V10.2Q16 7.4 12.8 7.8v12.4z"), IconPart("M16.3 8.6v11.6"))
private val ICO_CANCELLINO = listOf(IconPart("M5.5 21V7.5M18.5 21V7.5M3.5 21h17"), IconPart(dot(5.5f, 6f, 1.3f)), IconPart(dot(18.5f, 6f, 1.3f)),
    IconPart("M7 20.5V11.2Q12 7 17 11.2v9.3z"), IconPart("M10.3 9.6v10.9M13.7 9.6v10.9"))

fun gateIcon(azione: String): List<IconPart> = when (azione.lowercase()) {
    "garage" -> ICO_GARAGE; "cancellino" -> ICO_CANCELLINO; else -> ICO_CANCELLO
}

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

/** Cancelli e garage via Bluetooth: elenco, ricerca, abbinamento e comandi. */
@Composable
fun GateApp(gb: GateBle, store: Store, go: MutableStateFlow<String?>, setWho: (String) -> Unit) {
    val gates by store.gates.collectAsState()
    var scr by rememberSaveable { mutableStateOf(GScr.LIST) }
    var sel by rememberSaveable { mutableStateOf<String?>(null) }       // cancello sullo schermo
    var pairAddr by rememberSaveable { mutableStateOf("") }
    var pairName by rememberSaveable { mutableStateOf("") }
    var perm by remember { mutableStateOf(gb.hasPermissions()) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { gb.toasts.collect { snack.showSnackbar(it) } }
    // richieste dalla barra (un cancello, la ricerca, l'elenco)
    LaunchedEffect(go) {
        go.collect { r ->
            if (r == null) return@collect
            when {
                r == "scan" -> scr = GScr.SCAN
                r == "list" -> scr = GScr.LIST
                r.startsWith("dev:") -> { sel = r.removePrefix("dev:"); scr = GScr.CTL }
            }
            go.value = null
        }
    }
    if (scr == GScr.CTL && gates.none { it.address == sel }) scr = GScr.LIST

    // indietro: ricerca e abbinamento tornano all'elenco; da elenco e
    // cancello ci pensa la barra (torna alle pagine)
    BackHandler(enabled = scr == GScr.SCAN || scr == GScr.PAIR || scr == GScr.FW) {
        scr = when (scr) { GScr.PAIR -> GScr.SCAN; GScr.FW -> GScr.CTL; else -> GScr.LIST }
    }

    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            when {
                !perm -> GatePermission { perm = gb.hasPermissions() }
                scr == GScr.SCAN -> GateScan(gb, store, onBack = { scr = GScr.LIST }) { f ->
                    if (gates.any { it.address == f.address }) { sel = f.address; scr = GScr.CTL }
                    else { pairAddr = f.address; pairName = f.name; scr = GScr.PAIR }
                }
                scr == GScr.PAIR -> GatePair(gb, pairAddr, pairName, onBack = { scr = GScr.SCAN }) { sel = pairAddr; scr = GScr.CTL }
                scr == GScr.CTL && sel != null -> GateCtl(gb, store, sel!!, setWho, onBack = { scr = GScr.LIST }, onFw = { scr = GScr.FW }) { scr = GScr.LIST }
                scr == GScr.FW && sel != null -> {
                    val a = sel!!
                    val gst by gb.state.collectAsState()
                    DisposableEffect(a) { gb.watch(a); onDispose { gb.unwatch() } }
                    DevFwUpdate(gates.firstOrNull { it.address == a }?.name ?: "Cancello", "cancello", a, store,
                        gst?.ota.orEmpty(), gst?.md5.orEmpty(), "",
                        where = { gb.state.value?.let { if (it.wifiOn == true && it.ip.isNotBlank()) it.ip else null } },
                        setOta = { u -> gb.cmd(a, "ota\t$u") },
                        readMd5 = { gb.state.value?.md5 },
                        onBack = { scr = GScr.CTL })
                }
                else -> GateList(gb, store, onAdd = { scr = GScr.SCAN }) { sel = it; scr = GScr.CTL }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

// ── permesso ──────────────────────────────────────────────

@Composable
private fun GatePermission(onResult: () -> Unit) {
    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    LaunchedEffect(Unit) { ask.launch(perms) }
    Column(Modifier.fillMaxSize()) {
        Title("Gate Bluetooth")
        Sub("aprire da vicino, anche senza internet")
        BigIcon(Icons.bt)
        Text("Per trovare e aprire cancelli e garage serve il permesso «Dispositivi nelle vicinanze».",
            color = pal.value, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(26.dp))
        Btn("Consenti") { ask.launch(perms) }
    }
}

@Composable
private fun BigIcon(icon: List<IconPart>) {
    Box(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(130.dp).clip(CircleShape).background(pal.card).border(BW, pal.border, CircleShape),
            contentAlignment = Alignment.Center) { SvgIcon(icon, pal.accent, 60.dp) }
    }
}

// ── elenco ────────────────────────────────────────────────

@Composable
private fun GateList(gb: GateBle, store: Store, onAdd: () -> Unit, onPick: (String) -> Unit) {
    val gates by store.gates.collectAsState()
    val stati by gb.stati.collectAsState()
    var del by remember { mutableStateOf<BtGate?>(null) }
    del?.let { g ->
        Confirm("Dimenticare ${g.name}?", "Il telefono non lo aprirà più via Bluetooth finché non lo abbini di nuovo. Le scorciatoie che lo usavano si liberano.",
            "Dimentica", danger = true, onDismiss = { del = null }) { del = null; gb.forget(g.address) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Title("Gate Bluetooth")
        Sub(if (gates.isEmpty()) "nessuno ancora: cercalo qui sotto" else "tocca per aprire")
        Spacer(Modifier.height(20.dp))
        gates.forEach { g ->
            GateRow(g.name, gateSub(g, stati[g.address]), onDelete = { del = g }) { onPick(g.address) }
        }
        Spacer(Modifier.height(18.dp))
        Btn("Aggiungi gate Bluetooth", onClick = onAdd)
        Note("Da vicino apre anche senza internet e senza Wi-Fi. Ogni telefono si abbina una volta sola, con il codice mostrato dalla pagina del dispositivo.", center = true)
    }
}

/** "garage aperto · cancellino chiuso", oppure i pulsanti. */
fun gateSub(g: BtGate, st: Map<String, String>?): String =
    st?.entries?.joinToString(" · ") { "${it.key} ${it.value}" }?.ifBlank { null }
        ?: g.azioni.joinToString(" · ").ifBlank { "via Bluetooth" }

@Composable
private fun GateRow(title: String, sub: String, onDelete: () -> Unit, onClick: () -> Unit) {
    DeviceRow(title, sub, onDelete = onDelete, onClick = onClick)
}

// ── ricerca ───────────────────────────────────────────────

@Composable
private fun GateScan(gb: GateBle, store: Store, onBack: () -> Unit, onPick: (Found) -> Unit) {
    val found by gb.found.collectAsState()
    val scanning by gb.scanning.collectAsState()
    val saved by store.gates.collectAsState()
    LaunchedEffect(Unit) { gb.startScan() }
    DisposableEffect(Unit) { onDispose { gb.stopScan() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Aggiungi", onBack)
        Sub(if (scanning) "cerco cancelli e garage vicini…" else "ricerca finita")
        Spacer(Modifier.height(18.dp))
        found.forEach { f ->
            val known = saved.any { it.address == f.address }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(R_CARD)).background(pal.card)
                    .border(BW, pal.border, RoundedCornerShape(R_CARD)).clickable { onPick(f) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = pal.text, fontSize = 19.sp, maxLines = 1)
                    Text(if (known) "già abbinato" else f.address, color = pal.muted, fontSize = 14.sp)
                }
                SignalBars(f.rssi)
            }
        }
        if (!scanning && found.isEmpty())
            Note("Nessun gate trovato. Con la rete a posto il gate non si annuncia in Bluetooth: apri prima l'abbinamento dalla sua pagina web (Rete → Bluetooth → «Abbina telefono») e resta a pochi metri.", center = true)
        Spacer(Modifier.height(18.dp))
        Btn("Cerca di nuovo", secondary = true, enabled = !scanning) { gb.startScan() }
    }
}

// ── abbinamento ───────────────────────────────────────────

@Composable
private fun GatePair(gb: GateBle, addr: String, name: String, onBack: () -> Unit, onDone: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Abbina", onBack)
        BigIcon(Icons.bt)
        Text(name, color = pal.text, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        listOf(
            "Pagina web del gate → Rete → Bluetooth → «Abbina telefono»: compare un codice di 6 cifre, valido 3 minuti. Senza Wi-Fi la pagina si apre collegandosi all'hotspot del gate.",
            "Tocca «Abbina» qui sotto.",
            "Android chiede il codice: scrivi quello della pagina.",
        ).forEachIndexed { i, s ->
            Row(Modifier.padding(bottom = 8.dp)) {
                Text("${i + 1}.", color = pal.value, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                Text(s, color = pal.value, fontSize = 16.sp, lineHeight = 22.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Btn(if (busy) "In attesa del codice…" else "Abbina", enabled = !busy) {
            busy = true; err = ""
            scope.launch {
                val r = gb.pair(addr, name)
                busy = false
                if (r == null) onDone() else err = r
            }
        }
        if (err.isNotEmpty()) Text(err, color = pal.danger, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Note("Fatto una volta, il telefono resta abbinato anche dopo riavvii e aggiornamenti del dispositivo.", center = true)
    }
}

// ── comandi ───────────────────────────────────────────────

@Composable
private fun GateCtl(gb: GateBle, store: Store, addr: String, setWho: (String) -> Unit, onBack: () -> Unit, onFw: () -> Unit = {}, onForgot: () -> Unit) {
    val gates by store.gates.collectAsState()
    val g = gates.firstOrNull { it.address == addr }
    DisposableEffect(addr) { gb.watch(addr); onDispose { gb.unwatch() } }
    val link by gb.link.collectAsState()
    val st by gb.state.collectAsState()
    val log by gb.log.collectAsState()
    val azioni = st?.azioni?.takeIf { it.isNotEmpty() } ?: g?.azioni ?: emptyList()
    val name = g?.name ?: st?.name ?: "Cancello"
    var who by remember { mutableStateOf(store.who) }
    var askWho by remember { mutableStateOf(false) }
    var after by remember { mutableStateOf<String?>(null) }      // azione da aprire dopo aver chiesto il nome
    var forget by remember { mutableStateOf(false) }
    var netAsk by remember { mutableStateOf(false) }
    val mark = remember { mutableStateMapOf<String, String>() }   // azione -> "busy" / "done"
    val scope = rememberCoroutineScope()
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    fun fire(a: String) {
        if (who.isBlank()) { after = a; askWho = true; return }
        if (mark[a] == "busy") return
        mark[a] = "busy"
        scope.launch {
            val err = gb.open(addr, a, who)
            if (err == null) { mark[a] = "done"; delay(1800); mark.remove(a) }
            else { mark.remove(a); gb.toasts.tryEmit("${cap(a)}: $err") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back(name, onBack)
        val (txt, col) = when (link) {
            Link.CONNECTED -> "collegato via Bluetooth" to pal.ok
            Link.BT_OFF -> "Bluetooth del telefono spento" to pal.danger
            Link.NO_PERMISSION -> "manca il permesso Bluetooth" to pal.danger
            else -> "cerco il dispositivo… avvicinati" to G_YEL
        }
        Row(Modifier.padding(top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(col))
            Text("  $txt", color = pal.value, fontSize = 14.sp)
        }
        if (link == Link.BT_OFF) {
            Btn("Accendi il Bluetooth", secondary = true) { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(pal.card)
                .border(BW, pal.border, RoundedCornerShape(12.dp)).clickable { askWho = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("APRI COME", color = pal.muted, fontSize = 12.sp, letterSpacing = 1.sp)
            Text(who.ifBlank { "scegli" }, color = if (who.isBlank()) G_YEL else G_BLUE, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        if (azioni.isEmpty()) Note("Leggo i pulsanti dal dispositivo…", center = true)
        azioni.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { a -> GateButton(Modifier.weight(1f), a, st?.stati?.get(a), mark[a]) { fire(a) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (log.isNotEmpty()) {
            Section("ULTIME APERTURE")
            log.take(8).forEach { e -> EventRow(e) }
        }
        Note("Funziona anche senza internet e senza Wi-Fi: basta essere vicino.")
        // Wi-Fi del dispositivo (firmware che lo dice)
        st?.takeIf { it.wifiOn != null }?.let { s ->
            Section("IMPOSTAZIONI")
            RowCard("Wi-Fi", when {
                s.wifiOn == true -> "${s.ssid} · collegato"
                s.ssid.isBlank() -> "non impostato"
                else -> "${s.ssid} · non collegato"
            }) { netAsk = true }
            if (s.ota.isNotBlank()) RowCard("Aggiorna firmware", "anche senza internet", onClick = onFw)
        }
        Spacer(Modifier.height(24.dp))
        Btn("Dimentica questo cancello", secondary = true) { forget = true }
        Spacer(Modifier.height(16.dp))
    }

    if (netAsk) WifiNetAsk(name, st?.ssid.orEmpty(), onDismiss = { netAsk = false }) { ssid, pw ->
        netAsk = false
        scope.launch {
            val e = gb.cmd(addr, "wifi\t$ssid\t$pw")
            gb.toasts.tryEmit(e?.let { "Wi-Fi: $it" } ?: "Mi collego a «$ssid»: se non risponde resta sulla rete di prima.")
        }
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
    if (forget) Confirm("Dimenticare $name?", "Il telefono non lo aprirà più via Bluetooth finché non lo abbini di nuovo.",
        "Dimentica", danger = true, onDismiss = { forget = false }) {
        forget = false
        gb.forget(addr)
        onForgot()
    }
}

val ICO_TRASH = listOf(IconPart("M4 7h16M9 7V4.5h6V7M6.5 7l1 13h9l1-13M10 11v6M14 11v6"))

/**
 * Riga di un dispositivo Bluetooth salvato: tocco per aprirlo, cestino per
 * dimenticarlo (anche se non e' a portata). [selected]: quello collegato.
 */
@Composable
fun DeviceRow(title: String, sub: String, subColor: Color? = null, selected: Boolean = false, onDelete: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(R_CARD))
            .background(if (selected) pal.sel else pal.card)
            .border(BW, if (selected) pal.accent else pal.border, RoundedCornerShape(R_CARD))
            .clickable(onClick = onClick).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.text, fontSize = 19.sp, maxLines = 1)
            Text(sub, color = subColor ?: pal.muted, fontSize = 14.sp, maxLines = 1)
        }
        Box(Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(10.dp)) { SvgIcon(ICO_TRASH, pal.value, 22.dp) }
        Text("›", color = pal.accent, fontSize = 28.sp, modifier = Modifier.padding(start = 2.dp, end = 4.dp))
    }
}

/** Collegamento a un'altra schermata, con titolo e riga sotto. */
@Composable
fun LinkCard(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(R_CARD)).background(pal.card)
            .border(BW, pal.border, RoundedCornerShape(R_CARD)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.text, fontSize = 18.sp)
            Text(sub, color = pal.muted, fontSize = 14.sp)
        }
        Text("›", color = pal.accent, fontSize = 26.sp)
    }
}

/** Simboli del canale: Wi-Fi (relay) e Bluetooth. */
val ICO_WIFI = listOf(IconPart("M2 8.8a15 15 0 0 1 20 0"), IconPart("M5.5 12.6a10 10 0 0 1 13 0"), IconPart("M9 16.3a5 5 0 0 1 6 0"), IconPart("M11.9 20h.2"))
val ICO_BT = listOf(IconPart("M7 7l10 10-5 5V2l5 5L7 17"))

/**
 * Pulsante di un accesso, come nella pagina del dispositivo. [ch]: simbolo
 * del canale in alto a destra ("wifi", "bt", null = nessuno). [sub] cambia la
 * seconda riga (per esempio il nome del dispositivo); [off] = non raggiungibile.
 */
@Composable
fun GateButton(modifier: Modifier, a: String, stato: String?, mark: String?, ch: String? = null, sub: String? = null,
               off: Boolean = false, note: String? = null, onClick: () -> Unit) {
    val col = when { off -> pal.muted; mark == "done" -> pal.ok; stato == "aperto" -> G_YEL; else -> G_BLUE }
    val line = when {
        mark == "done" -> "inviato ✓"; mark == "busy" -> "invio…"; off -> "non raggiungibile"; note != null -> note
        stato == "aperto" -> "aperto"; stato == "chiuso" -> "chiuso"; else -> "impulso"
    }
    // fondo e bordo nella tonalita' dello stato, come i riquadri del Clima:
    // il colore pieno resta solo a icona e nome, il bordo e' quello di tutti
    val bg = if (off) pal.card else tintBg(pal.card, col)
    val bd = if (off) pal.border else tintBd(pal.card, col)
    Box(modifier.clip(RoundedCornerShape(R_CARD)).background(bg).border(BW, bd, RoundedCornerShape(R_CARD))
        .clickable(enabled = mark != "busy", onClick = onClick).alpha(if (mark == "busy") .6f else 1f)) {
        Column(
            Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp, start = 6.dp, end = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GateIcon(a, stato == "aperto", col, 42.dp)      // animata come nella pagina del gate
            Text(a.uppercase(), color = col, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, maxLines = 1)
            Text(if (sub != null && mark == null && !off) "$sub · $line" else line, color = G_DIM, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1)
        }
        if (ch != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 9.dp)) {
            SvgIcon(if (ch == "bt") ICO_BT else ICO_WIFI, chColor(ch, off), 14.dp, stroke = 2f)   // colore fisso del canale
        }
    }
}

@Composable
private fun EventRow(e: GateEvent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(cap(e.azione) + if (e.bypass) " (by-pass)" else "", color = pal.text, fontSize = 16.sp)
            Text(listOf(e.canaleNome, e.utente).filter { it.isNotBlank() }.joinToString(" · "), color = pal.muted, fontSize = 13.sp)
        }
        Text(when_(e.t), color = pal.value, fontSize = 14.sp)
    }
}

/** "23:41" oggi, "26/10 23:41" gli altri giorni. */
private fun when_(t: Long): String {
    if (t <= 0) return "ora non nota"
    val d = Date(t * 1000)
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { time = d }
    val today = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (today) "HH:mm" else "dd/MM HH:mm", Locale.ITALY).format(d)
}
