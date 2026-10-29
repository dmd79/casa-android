package me.synology.davidevinavil.casa

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private enum class SScr { MAIN, LIST, SCAN, DETAIL, UPDATE }

/** Un sensore della pagina: via relay, via Bluetooth o tutti e due (stesso nome). */
private data class Sn(val name: String, val relay: RelayDev?, val bt: BtSensor?)

private fun f1(v: Double?) = v?.let { String.format(Locale.ITALY, "%.1f", it) } ?: "--"
private fun f0(v: Double?) = v?.let { String.format(Locale.ITALY, "%.0f", it) } ?: "--"
private fun modoNome(m: Int) = when (m) { 1 -> "Wi-Fi"; 2 -> "Solo ESP-NOW"; else -> "da configurare" }

/**
 * Sensori esterni. Pagina principale: riquadri via relay se raggiungibile e in
 * linea, altrimenti via Bluetooth (lettura breve, uno per volta). Poi elenco dei
 * sensori Bluetooth, ricerca, scheda del sensore e aggiornamento senza internet.
 */
@Composable
fun SensorsApp(app: CasaApp, go: MutableStateFlow<String?>, openRelay: (RelayDev) -> Unit, onCharts: () -> Unit) {
    var scr by rememberSaveable { mutableStateOf(SScr.MAIN) }
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(go) {
        go.collect { r ->
            if (r == null) return@collect
            when {
                r == "list" -> scr = SScr.LIST
                r.startsWith("dev:") -> { sel = r.removePrefix("dev:"); scr = SScr.DETAIL }
            }
            go.value = null
        }
    }
    BackHandler(enabled = scr != SScr.MAIN) {
        scr = when (scr) { SScr.SCAN -> SScr.LIST; SScr.UPDATE -> SScr.DETAIL; else -> SScr.MAIN }
    }
    Box(Modifier.fillMaxSize().background(pal.bg).padding(horizontal = 20.dp, vertical = 16.dp)) {
        when (scr) {
            SScr.MAIN -> SensorsMain(app, openRelay, onCharts, openBt = { sel = it; scr = SScr.DETAIL }, onList = { scr = SScr.LIST })
            SScr.LIST -> SensorList(app, onBack = { scr = SScr.MAIN }, onAdd = { scr = SScr.SCAN }) { sel = it; scr = SScr.DETAIL }
            SScr.SCAN -> SensorScan(app, onBack = { scr = SScr.LIST }) { scr = SScr.LIST }
            SScr.DETAIL -> sel?.let { SensorDetail(app, it, onBack = { scr = SScr.MAIN }, onUpdate = { scr = SScr.UPDATE }, onGone = { scr = SScr.LIST }) }
            SScr.UPDATE -> sel?.let { SensorUpdate(app, it, onBack = { scr = SScr.DETAIL }) }
        }
    }
}

// ── pagina principale ─────────────────────────────────────

@Composable
private fun SensorsMain(app: CasaApp, openRelay: (RelayDev) -> Unit, onCharts: () -> Unit, openBt: (String) -> Unit, onList: () -> Unit) {
    val store = app.store
    val sb = app.sensors
    var devs by remember { mutableStateOf(Relay.parseList(store.relayList).filter { it.type == "sensor" }) }
    var relayOk by remember { mutableStateOf(store.relayUp) }
    var loaded by remember { mutableStateOf(false) }
    val bts by store.sensors.collectAsState()
    val stati by sb.stati.collectAsState()

    // relay come in Clima e Accessi (in secondo piano non si conta)
    LaunchedEffect(Unit) {
        var fails = 0
        while (true) {
            if (!app.fg) { delay(1000); continue }
            val cookie = Relay.cookie()
            var raw = withContext(Dispatchers.IO) { Relay.listRaw(cookie) }
            if (raw == null && relayOk) { delay(1500); raw = withContext(Dispatchers.IO) { Relay.listRaw(cookie) } }
            if (raw == null && !app.fg) continue
            if (raw != null) { store.relayList = raw; devs = Relay.parseList(raw).filter { it.type == "sensor" }; fails = 0; relayOk = true }
            else if (++fails >= 2 || !relayOk) relayOk = false
            store.relayUp = relayOk
            loaded = true
            delay(if (raw != null) 5000 else 3000)
        }
    }

    fun same(a: String, b: String) = sameName(a, b)
    val list = buildList {
        devs.forEach { d -> add(Sn(d.name, d, bts.firstOrNull { same(it.name, d.name) })) }
        bts.forEach { b -> if (none { same(it.name, b.name) }) add(Sn(b.name, null, b)) }
    }
    fun viaRelay(x: Sn) = x.relay != null && (relayOk && x.relay.online || x.bt == null)

    // Bluetooth: lettura breve dei sensori non coperti dal relay, uno per volta, poi ogni minuto
    val reach = remember { mutableStateMapOf<String, Boolean>() }
    val btWanted = list.filter { !viaRelay(it) && it.bt != null }.map { it.bt!!.address }
    LaunchedEffect(btWanted.joinToString()) {
        if (btWanted.isEmpty()) return@LaunchedEffect
        delay(600)
        while (true) {
            btWanted.forEach { a -> reach[a] = sb.peek(a) != null }
            delay(60_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Title("Sensori", Modifier.padding(bottom = 14.dp))
        if (loaded && !relayOk && list.isNotEmpty()) {
            val y = Color(0xFFE7C12C)
            Text(if (bts.isNotEmpty()) "Relay non raggiungibile: leggo via Bluetooth, da vicino" else "Relay non raggiungibile",
                color = y, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp))
                    .background(y.copy(alpha = .12f)).border(1.dp, y, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
        }
        if (list.isEmpty()) Note(if (loaded) "Nessun sensore. Entra nei sensori dalla scheda Tutti, oppure abbinane uno via Bluetooth qui sotto."
                                 else "Leggo i sensori…", center = true)
        list.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { x ->
                    if (viaRelay(x)) {
                        val d = x.relay!!
                        val on = d.online && relayOk
                        SensorCard(Modifier.weight(1f), x.name, d.cur, d.hum, "wifi", if (on) null else "non raggiungibile") { openRelay(d) }
                    } else {
                        val b = x.bt!!
                        val s = stati[b.address]
                        val r = reach[b.address]
                        val status = when {
                            r == null && s == null -> "cerco…"
                            r == false -> "non raggiungibile"
                            else -> null
                        }
                        SensorCard(Modifier.weight(1f), x.name, s?.t, s?.h, "bt", status) { openBt(b.address) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        RowCard("Grafici e storico", onClick = onCharts)
        LinkCard("Sensori Bluetooth", "elenco, aggiungi, dimentica", onClick = onList)
        Spacer(Modifier.height(16.dp))
    }
}


@Composable
private fun SensorCard(modifier: Modifier, name: String, t: Double?, h: Double?, ch: String, status: String?, onClick: () -> Unit) {
    val off = status != null
    Column(
        modifier.heightIn(min = 128.dp).clip(RoundedCornerShape(16.dp)).background(pal.card)
            .border(1.dp, pal.border, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        // stesso schema dei riquadri del Clima
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Color(0xFFC9D1D4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SvgIcon(if (ch == "bt") ICO_BT else ICO_WIFI, chColor(ch, off), 14.dp, stroke = 2f)
        }
        ValueRows(t, h, hasHum = true, icon = ICO_THERMO, iconColor = CH_SENS, dim = off)
        Text(status ?: if (ch == "bt") "da vicino, Bluetooth" else "via relay", color = pal.muted, fontSize = 11.sp,
            letterSpacing = .5.sp, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
    }
}

// ── elenco dei sensori Bluetooth ──────────────────────────

@Composable
private fun SensorList(app: CasaApp, onBack: () -> Unit, onAdd: () -> Unit, onPick: (String) -> Unit) {
    val list by app.store.sensors.collectAsState()
    val stati by app.sensors.stati.collectAsState()
    var del by remember { mutableStateOf<BtSensor?>(null) }
    del?.let { s ->
        Confirm("Dimenticare ${s.name}?", "Il telefono non lo leggerà più via Bluetooth finché non lo abbini di nuovo. Via relay resta tutto com'è.",
            "Dimentica", danger = true, onDismiss = { del = null }) { del = null; app.sensors.forget(s.address) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Sensori Bluetooth", onBack)
        Sub(if (list.isEmpty()) "nessuno ancora" else "abbinati a questo telefono")
        Spacer(Modifier.height(18.dp))
        list.forEach { s ->
            val v = stati[s.address]
            DeviceRow(s.name, v?.let { "${d2(it.t)} °C · ${d2(it.h)} %" } ?: "tocca per leggere", onDelete = { del = s }) { onPick(s.address) }
        }
        Spacer(Modifier.height(16.dp))
        Btn("Aggiungi sensore Bluetooth", onClick = onAdd)
        Note("Più comodo: «Abbina Bluetooth» nella lista del relay, oppure Altro → Nuovo dispositivo. Da qui serve il codice della pagina del sensore (WIFI → Bluetooth → Abbina telefono).", center = true)
    }
}

@Composable
private fun SensorScan(app: CasaApp, onBack: () -> Unit, onDone: () -> Unit) {
    val sb = app.sensors
    val found by sb.found.collectAsState()
    val scanning by sb.scanning.collectAsState()
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf<Found?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { sb.startScan() }
    DisposableEffect(Unit) { onDispose { sb.stopScan() } }
    pick?.let { f ->
        Ask("Abbina ${f.name}", "Il codice di 6 cifre della pagina del sensore (WIFI → Bluetooth → Abbina telefono).", "", ok = "Abbina",
            onDismiss = { pick = null }) { v ->
            pick = null
            val c = v.filter(Char::isDigit)
            busy = f.name; err = ""
            scope.launch {
                val e = app.pairer.bond("sensor", f.name, if (c.length == 6) c else "")
                busy = null
                if (e == null) onDone() else err = e
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Aggiungi", onBack)
        Sub(when { busy != null -> "abbino $busy…"; scanning -> "cerco i sensori vicini…"; else -> "ricerca finita" })
        Spacer(Modifier.height(18.dp))
        found.forEach { f ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(pal.card)
                    .border(1.dp, pal.border, RoundedCornerShape(16.dp)).clickable(enabled = busy == null) { sb.stopScan(); pick = f }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = pal.text, fontSize = 19.sp, maxLines = 1)
                    Text(f.address, color = pal.muted, fontSize = 14.sp)
                }
                SignalBars(f.rssi)
            }
        }
        if (err.isNotBlank()) Text(err, color = Color(0xFFE7C12C), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        if (!scanning && found.isEmpty()) Note("Nessun sensore trovato. Avvicinati: il sensore ha il Bluetooth sempre acceso.", center = true)
        Spacer(Modifier.height(18.dp))
        Btn("Cerca di nuovo", secondary = true, enabled = !scanning && busy == null) { sb.startScan() }
    }
}

// ── scheda del sensore (Bluetooth) ────────────────────────

@Composable
private fun SensorDetail(app: CasaApp, addr: String, onBack: () -> Unit, onUpdate: () -> Unit, onGone: () -> Unit) {
    val sb = app.sensors
    val list by app.store.sensors.collectAsState()
    val stati by sb.stati.collectAsState()
    val name = list.firstOrNull { it.address == addr }?.name ?: "Sensore"
    val s = stati[addr]
    var reach by remember { mutableStateOf<Boolean?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }       // "espnow", "wifi", "forget"
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // lettura breve ogni 5 s mentre la scheda e' aperta
    LaunchedEffect(addr) {
        while (true) {
            reach = sb.peek(addr) != null
            delay(5000)
        }
    }
    when (ask) {
        "forget" -> Confirm("Dimenticare il Bluetooth di $name?", "Via relay resta tutto com'è.", "Dimentica", danger = true,
            onDismiss = { ask = null }) { ask = null; sb.forget(addr); onGone() }
        "espnow" -> Confirm("Passare a Solo ESP-NOW?", "Il sensore spegne il Wi-Fi e il relay, si riavvia e cerca il termostato. Da vicino resta il Bluetooth.",
            "Passa a ESP-NOW", onDismiss = { ask = null }) {
            ask = null
            scope.launch { msg = sb.cmd(addr, "modo=espnow")?.let { "Non riuscito: $it" } ?: "Fatto: il sensore si riavvia in Solo ESP-NOW." }
        }
        "net" -> WifiNetAsk(name, s?.ssid.orEmpty(), onDismiss = { ask = null }) { ssid, pw ->
            ask = null
            scope.launch {
                msg = sb.cmd(addr, "wifi=$ssid\t$pw")?.let { "Non riuscito: $it" }
                    ?: "Mi collego a «$ssid»: se non risponde resta sulla rete di prima."
            }
        }
        "wifi" -> WifiAsk(onDismiss = { ask = null }) { ssid, pw, rpw ->
            ask = null
            scope.launch {
                val e = sb.cmd(addr, "modo=wifi\t$ssid\t$pw\t$rpw")
                if (e == null) {
                    s?.id?.takeIf { it.isNotBlank() }?.let { app.store.addPending(it, rpw, name) }
                    msg = "Fatto: il sensore si riavvia in Wi-Fi. Lo aggiungo alla lista del relay appena è online."
                } else msg = "Non riuscito: $e"
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back(name, onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)) {
            SvgIcon(ICO_BT, if (reach == true) pal.accent else pal.muted, 13.dp, stroke = 2f)
            Text(when (reach) { null -> " mi collego…"; true -> " collegato via Bluetooth"; false -> " non raggiungibile: avvicinati" },
                color = pal.muted, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(d2(s?.t), color = pal.text, fontSize = 44.sp, fontWeight = FontWeight.Light)
            Text(" °C   ", color = Color(0xFF8A9599), fontSize = 15.sp, modifier = Modifier.padding(bottom = 9.dp))
            Text("${d2(s?.h)} %", color = HUM_BLUE, fontSize = 20.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (s != null) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(pal.card)
                .border(1.dp, pal.border, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 4.dp)) {
                Kv("Funzionamento", modoNome(s.mode))
                when {
                    s.mode == 2 -> Kv("Wi-Fi", "spento", pal.muted)
                    s.wifiOn == true -> Kv("Wi-Fi", "${s.ssid} · collegato", pal.ok)
                    s.wifiOn == false -> Kv("Wi-Fi", if (s.ssid.isBlank()) "non impostato" else "${s.ssid} · non collegato", Color(0xFFE7C12C))
                }
                if (s.mode != 0) Kv("Termostato", if (s.hooked) "agganciato, canale ${s.ch}" else "non agganciato",
                    if (s.hooked) pal.ok else Color(0xFFE7C12C))
                Kv("MAC", s.mac)
                Kv("Firmware", s.ver.ifBlank { "--" })
            }
            if (s.mode == 2) MacBox(s.mac)
        }
        if (msg.isNotBlank()) Text(msg, color = pal.value, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(10.dp))
        if (s != null) {
            Section("IMPOSTAZIONI")
            if (s.mode == 1 && s.wifiOn != null) RowCard("Wi-Fi", s.ssid.ifBlank { "--" }) { ask = "net" }
            RowCard("Funzionamento", if (s.mode == 1) "passa a ESP-NOW" else "passa a Wi-Fi") { ask = if (s.mode == 1) "espnow" else "wifi" }
            if (s.mode == 1) Note("In Wi-Fi si aggiorna da solo: niente da fare qui.")
            else RowCard("Aggiorna firmware", "senza internet, dall'hotspot", onClick = onUpdate)
        }
        Btn("Dimentica Bluetooth", secondary = true, modifier = Modifier.padding(top = 8.dp)) { ask = "forget" }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Kv(k: String, v: String, c: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = pal.muted, fontSize = 14.sp, maxLines = 1)
        // valore lungo: va a capo nel suo spazio, allineato a destra (l'etichetta resta intera)
        Text(v, color = c ?: pal.text, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
}

/** Wi-Fi e password del relay per passare a Wi-Fi (via Bluetooth). */
@Composable
private fun WifiAsk(onDismiss: () -> Unit, onOk: (String, String, String) -> Unit) {
    val ctx = LocalContext.current
    var ssid by remember { mutableStateOf(Hotspot.phoneWifi(ctx)?.first ?: "") }
    var pw by remember { mutableStateOf("") }
    var rpw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(setOf<Int>()) }        // password visibili
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.bg,
        title = { Text("Passa a Wi-Fi", color = pal.text, fontSize = 21.sp) },
        text = {
            Column {
                Text("Rete di casa (2,4 GHz) e password del relay. Il sensore si riavvia in Wi-Fi.", color = pal.value, fontSize = 15.sp)
                listOf(Triple("Rete", ssid, false), Triple("Password Wi-Fi", pw, true), Triple("Password del relay", rpw, true)).forEachIndexed { i, (l, v, sec) ->
                    androidx.compose.material3.OutlinedTextField(
                        value = v, onValueChange = { n -> when (i) { 0 -> ssid = n; 1 -> pw = n; else -> rpw = n.take(32) } },
                        label = { Text(l) }, singleLine = true, colors = fieldColors(),
                        visualTransformation = if (sec && i !in show) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        trailingIcon = if (sec) { { ShowPw(i in show) { show = if (i in show) show - i else show + i } } } else null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            val ok = ssid.isNotBlank() && ssid.length <= 32 && pw.length in 8..63 && rpw.length >= 6 && !ssid.contains('\t') && !pw.contains('\t') && !rpw.contains('\t')
            androidx.compose.material3.TextButton(enabled = ok, onClick = { onOk(ssid.trim(), pw, rpw) }) {
                Text("Passa a Wi-Fi", color = if (ok) pal.accent else pal.muted, fontSize = 17.sp)
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Annulla", color = pal.value, fontSize = 17.sp) } },
    )
}

// ── aggiornamento senza internet ──────────────────────────

/**
 * Firmware scaricato dall'indirizzo OTA del sensore (con internet), poi
 * caricato sulla sua pagina dall'hotspot (http://192.168.4.1/update, come
 * "OTA Update" del portale).
 */
object FwUpdate {
    /**
     * Data di compilazione scritta dentro il firmware ("29/10/2026 13:10"):
     * ESPHome ci mette l'ora della build ("2026-10-29 13:10:25 +0100").
     * Serve a vedere prima di caricarlo se il file e' quello appena compilato.
     */
    fun built(f: File): String? = try {
        val t = String(f.readBytes(), Charsets.ISO_8859_1)
        Regex("(20\\d\\d)-(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):\\d\\d [+-]\\d{4}").find(t)?.destructured
            ?.let { (y, mo, d, h, mi) -> "$d/$mo/$y $h:$mi" }
    } catch (_: Exception) { null }

    private fun url(base: String, file: String) = base.trim().trimEnd('/') + "/" + file

    fun md5(base: String): String? = try {
        val c = URL(url(base, "firmware.md5")).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        val t = if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        t?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()?.takeIf { it.length == 32 }
    } catch (e: Exception) { null }

    /** Scarica il firmware in [out]; null se riuscito, altrimenti il motivo. */
    fun download(base: String, out: File, progress: (Int) -> Unit): String? = try {
        val c = URL(url(base, "firmware.bin")).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        if (c.responseCode != 200) "server: errore ${c.responseCode}" else {
            val tot = c.contentLengthLong
            var n = 0L
            c.inputStream.use { inp -> out.outputStream().use { o ->
                val b = ByteArray(16384)
                while (true) { val r = inp.read(b); if (r < 0) break; o.write(b, 0, r); n += r; if (tot > 0) progress((n * 100 / tot).toInt()) }
            } }
            c.disconnect()
            if (n < 100_000) "file troppo piccolo" else null
        }
    } catch (e: Exception) { "download non riuscito (${e.message ?: "rete"})" }

    const val HOTSPOT = "192.168.4.1"

    // hotspot: sulla rete Wi-Fi del telefono (non ha internet); altrimenti la rete normale
    private fun open(ctx: Context, host: String, path: String): HttpURLConnection? {
        val u = URL("http://$host$path")
        return if (host == HOTSPOT) Hotspot.wifi(ctx)?.openConnection(u) as? HttpURLConnection
        else u.openConnection() as HttpURLConnection
    }

    /** La pagina del dispositivo risponde ([host]: hotspot o indirizzo in casa). */
    fun pageUp(ctx: Context, host: String = HOTSPOT): Boolean = try {
        val c0 = open(ctx, host, "/")
        if (c0 == null) false else {
            val c = c0
            c.connectTimeout = 2500; c.readTimeout = 4000
            val ok = c.responseCode in 200..399
            c.disconnect()
            ok
        }
    } catch (e: Exception) { false }

    /**
     * Come [upload], con un secondo tentativo automatico se il caricamento si
     * interrompe (es. "Broken pipe" verso la fine): succedeva quasi sempre al
     * primo tentativo, mai al secondo, senza che il dispositivo si riavviasse.
     * [retrying] avvisa la pagina che si riprova.
     */
    fun uploadRetry(ctx: Context, f: File, host: String = HOTSPOT, retrying: () -> Unit = {}, progress: (Int) -> Unit): String? {
        val e = upload(ctx, f, host, progress) ?: return null
        if (!e.startsWith("caricamento interrotto")) return e
        retrying()
        Thread.sleep(3000)
        // la pagina deve rispondere di nuovo (fino a 20 s), poi si ricarica tutto da capo
        var up = false
        for (i in 0 until 10) { if (pageUp(ctx, host)) { up = true; break }; Thread.sleep(2000) }
        if (!up) return e
        progress(0)
        return upload(ctx, f, host, progress)
    }

    /** Carica il firmware; null se riuscito, altrimenti il motivo. */
    fun upload(ctx: Context, f: File, host: String = HOTSPOT, progress: (Int) -> Unit): String? {
        if (host == HOTSPOT && Hotspot.wifi(ctx) == null) return "hotspot non collegato"
        val bnd = "----casa" + System.currentTimeMillis()
        val head = "--$bnd\r\nContent-Disposition: form-data; name=\"update\"; filename=\"firmware.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
        val tail = "\r\n--$bnd--\r\n".toByteArray()
        return try {
            val c = open(ctx, host, "/update") ?: return "hotspot non collegato"
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 5000; c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$bnd")
            val tot = head.size + f.length() + tail.size
            c.setFixedLengthStreamingMode(tot)
            c.outputStream.use { o ->
                o.write(head)
                var n = 0L
                f.inputStream().use { inp ->
                    val b = ByteArray(8192)
                    while (true) { val r = inp.read(b); if (r < 0) break; o.write(b, 0, r); n += r; progress((n * 100 / f.length()).toInt()) }
                }
                o.write(tail)
            }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code in 200..399 && !body.contains("fail", true)) null else "il dispositivo ha rifiutato il file ($code)"
        } catch (e: Exception) { "caricamento interrotto (${e.message ?: "rete"})" }
    }
}

/** Prime 8 cifre di un md5 ("?" se non c'e'). */
internal fun word8(m: String?) = m.orEmpty().trim().take(8).ifBlank { "?" }

/** File del firmware scelto sul telefono, copiato nella cache. */
internal data class LocalFw(val file: java.io.File, val name: String, val size: Long, val built: String? = null)

/** Copia il file scelto e controlla che sia un firmware ESP32 (primo byte 0xE9, almeno 100 KB). */
internal fun copyFw(ctx: Context, uri: android.net.Uri): Pair<LocalFw?, String?> = try {
    var name = "firmware.bin"
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
    }
    val out = File(ctx.cacheDir, "fw_local.bin")
    ctx.contentResolver.openInputStream(uri)?.use { inp -> out.outputStream().use { inp.copyTo(it) } }
    val first = out.inputStream().use { it.read() }
    when {
        out.length() < 100_000 -> null to "file troppo piccolo per un firmware"
        first != 0xE9 -> null to "non è un firmware ESP32 (.bin)"
        else -> LocalFw(out, name, out.length(), FwUpdate.built(out)) to null
    }
} catch (e: Exception) { null to "file non letto (${e.message ?: "errore"})" }

@Composable
private fun SensorUpdate(app: CasaApp, addr: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sb = app.sensors
    val s = sb.stati.collectAsState().value[addr]
    // stato fresco: dopo un aggiornamento l'md5 ricordato e' quello di prima
    LaunchedEffect(addr) { withContext(Dispatchers.IO) { try { sb.peek(addr) } catch (_: Exception) {} } }
    val name = app.store.sensors.collectAsState().value.firstOrNull { it.address == addr }?.name ?: "sensore"
    // scelta della fonte
    var started by remember { mutableStateOf(false) }
    var useFile by remember { mutableStateOf(false) }
    // quello scritto nell'app (ricordato per questo sensore); se non c'e', quello del sensore
    var url by remember { mutableStateOf(app.store.fwUrl(addr).ifBlank { s?.ota ?: "" }) }
    var edited by remember { mutableStateOf(false) }
    // si ricorda subito, anche senza aggiornare
    LaunchedEffect(url) { val u = url.trim().trimEnd('/'); if (edited && u.startsWith("http")) app.store.setFwUrl(addr, u) }
    var avail by remember { mutableStateOf<String?>(null) }      // md5 disponibile all'indirizzo, "" = non trovato
    var local by remember { mutableStateOf<LocalFw?>(null) }
    var pickErr by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (f, e) = withContext(Dispatchers.IO) { copyFw(ctx, uri) }
            local = f; pickErr = e
        }
    }
    // indirizzo del sensore appena letto (se nell'app non ce n'e' uno scelto)
    LaunchedEffect(s?.ota) { if (url.isBlank() && s != null && s.ota.isNotBlank()) url = s.ota }
    if (s == null) LaunchedEffect(Unit) { sb.peek(addr) }
    // versione disponibile all'indirizzo (con un attimo di pausa mentre si scrive)
    LaunchedEffect(url, useFile) {
        avail = null
        if (useFile || !url.trim().startsWith("http")) return@LaunchedEffect
        delay(700)
        avail = withContext(Dispatchers.IO) { FwUpdate.md5(url) } ?: ""
    }

    // avanzamento: 0 firmware pronto, 1 hotspot, 2 collegati, 3 carico, 4 riavvio, 5 fatto
    var step by remember { mutableStateOf(0) }
    var pct by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    var go by remember { mutableStateOf(0) }
    var built by remember { mutableStateOf<String?>(null) }     // data di compilazione del firmware scaricato
    var retry by remember { mutableStateOf(false) }             // secondo tentativo automatico in corso
    LaunchedEffect(go) {
        if (go == 0) return@LaunchedEffect
        retry = false
        err = null; step = 0; pct = 0
        val st = s ?: sb.peek(addr)
        // 0. firmware: dal telefono o scaricato, finche' c'e' la rete
        val f: File = if (useFile) {
            local?.file ?: run { err = "Scegli prima il file del firmware."; return@LaunchedEffect }
        } else {
            val u = url.trim().trimEnd('/')
            app.store.setFwUrl(addr, u)
            // indirizzo cambiato: lo prende anche il sensore (pagina e aggiornamenti da solo)
            if (st != null && u != st.ota.trimEnd('/')) { try { sb.cmd(addr, "otaurl=$u") } catch (_: Exception) {} }
            val out = File(ctx.cacheDir, "fw_sensor.bin")
            val e0 = withContext(Dispatchers.IO) { FwUpdate.download(u, out) { p -> pct = p } }
            if (e0 != null) { err = "Firmware non scaricato: $e0. Senza rete scegli «File sul telefono»."; return@LaunchedEffect }
            built = withContext(Dispatchers.IO) { FwUpdate.built(out) }
            out
        }
        step = 1; pct = 0
        // 1. hotspot del sensore (da configurare c'e' gia'; in Wi-Fi arriva da solo se la rete manca)
        if (st?.mode == 2) {
            val e1 = sb.cmd(addr, "ota")
            if (e1 != null) { err = "Il sensore non ha acceso l'hotspot: $e1."; return@LaunchedEffect }
        }
        step = 2
        // 2. telefono sull'hotspot (10 minuti al massimo)
        val up = withTimeoutOrNull(600_000) { while (!withContext(Dispatchers.IO) { FwUpdate.pageUp(ctx) }) delay(2000); true }
        if (up != true) { err = "Hotspot non raggiunto entro 10 minuti."; return@LaunchedEffect }
        step = 3
        // 3. caricamento
        val e3 = withContext(Dispatchers.IO) { FwUpdate.uploadRetry(ctx, f, retrying = { retry = true }) { p -> pct = p } }
        if (e3 != null) { err = "Aggiornamento non riuscito: $e3."; return@LaunchedEffect }
        step = 4
        delay(8000)
        if (!useFile) f.delete()
        step = 5
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Aggiorna $name", onBack)
        if (!started) {
            Text("Da dove prendo il firmware?", color = pal.text, fontSize = 20.sp, modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))
            SrcOpt(ICO_NET, "Da indirizzo", !useFile, { useFile = false }) {
                Text("Cartella con firmware.bin e firmware.md5", color = pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                androidx.compose.material3.OutlinedTextField(value = url, onValueChange = { url = it; edited = true }, singleLine = false,
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                if (s != null && url.trim() != s.ota) Text("Ripristina quello del sensore", color = pal.accent, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable { url = s.ota; edited = true })
                val inst = s?.md5?.lowercase().orEmpty()
                Text(when (val a = avail) {
                    null -> if (url.trim().startsWith("http")) "Controllo l'indirizzo…" else "Indirizzo http:// o https://"
                    "" -> "Nessun firmware.md5 a questo indirizzo (o niente rete)"
                    inst -> "Già aggiornato: si può ricaricare lo stesso"
                    // le prime cifre dei due md5: si vede subito se il file sul server e' un altro
                    else -> "Versione nuova disponibile (${word8(inst)} → ${word8(a)})"
                }, color = if (avail != null && avail != "" && avail != inst) pal.ok else pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            SrcOpt(ICO_FILE, "File sul telefono", useFile, { useFile = true }) {
                val l = local
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(pal.bg)
                    .border(1.dp, pal.border, RoundedCornerShape(10.dp)).clickable { picker.launch(arrayOf("*/*")) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l?.name ?: "Nessun file scelto", color = pal.text, fontSize = 15.sp)
                        Text(l?.let { String.format(Locale.ITALY, "%.2f MB", it.size / 1048576.0) + (it.built?.let { b -> " · compilato il $b" } ?: "") } ?: "tocca per scegliere il .bin", color = pal.muted, fontSize = 12.sp)
                    }
                    Text(if (l == null) "Scegli" else "Cambia", color = pal.accent, fontSize = 14.sp)
                }
                pickErr?.let { Text(it, color = Color(0xFFE7C12C), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            Note(if (useFile) "Nessuna rete necessaria: il file va direttamente al sensore dall'hotspot."
                 else "Scarico prima, finché c'è la rete; poi ti colleghi all'hotspot del sensore.", center = true)
            Spacer(Modifier.height(6.dp))
            Btn(if (useFile) "Aggiorna" else "Scarica e aggiorna", enabled = if (useFile) local != null else url.trim().startsWith("http")) {
                started = true; go++
            }
            return@Column
        }
        Column(Modifier.padding(top = 16.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(when { useFile -> "File pronto"; step == 0 && pct > 0 -> "Scarico il firmware $pct %"; else -> "Firmware scaricato" } +
                    ((if (useFile) local?.built else built)?.let { " (compilato il $it)" } ?: ""),
                "Hotspot del sensore acceso", "Collegati all'hotspot", if (step == 3) (if (retry) "Interrotto, riprovo: $pct %" else "Caricamento $pct %") else "Caricamento", "Riavvio").forEachIndexed { i, t ->
                val ok = i < step; val now = i == step && err == null && step < 5; val bad = i == step && err != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                        when {
                            ok -> SvgIcon(listOf(IconPart("M5 12.5l4.5 4.5L19 7.5")), pal.ok, 16.dp, stroke = 2.4f)
                            now -> CircularProgressIndicator(Modifier.size(16.dp), color = pal.accent, strokeWidth = 2.dp)
                            else -> Text("•", color = if (bad) Color(0xFFFF5A5A) else pal.muted, fontSize = 16.sp)
                        }
                    }
                    Text(t, fontSize = 17.sp, color = when { ok -> pal.ok; bad -> Color(0xFFFF5A5A); now -> pal.text; else -> pal.muted })
                }
            }
        }
        if (step == 3) LinearProgressIndicator(progress = { pct / 100f }, color = pal.accent, trackColor = pal.border,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        if (step == 2 && err == null) {
            Note("Nelle impostazioni Wi-Fi del telefono collegati all'hotspot del sensore (di solito «Ext-Sensor Hotspot»), con la sua password. Resta collegato finché non finisce.", center = true)
            Btn("Apri impostazioni Wi-Fi") { Hotspot.openWifiSettings(ctx) }
        }
        if (step == 5) Note("Fatto: il sensore si è riavviato e torna nel suo modo. Puoi tornare sulla tua rete.", center = true)
        err?.let { Text(it, color = Color(0xFFE7C12C), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        if (err != null) {
            Btn("Riprova") { go++ }
            Btn("Cambia fonte", secondary = true, modifier = Modifier.padding(top = 8.dp)) { started = false; err = null; step = 0 }
        }
        Btn(if (step == 5 || err != null) "Chiudi" else "Annulla", secondary = true, modifier = Modifier.padding(top = 8.dp), onClick = onBack)
    }
}

internal val ICO_NET = listOf(IconPart("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z"), IconPart("M3 12h18"), IconPart("M12 3a14 14 0 0 1 0 18"), IconPart("M12 3a14 14 0 0 0 0 18"))
internal val ICO_FILE = listOf(IconPart("M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8z"), IconPart("M14 3v5h5"))

@Composable
internal fun SrcOpt(icon: List<IconPart>, t: String, on: Boolean, onPick: () -> Unit, body: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(if (on) pal.sel else pal.card)
            .border(1.dp, if (on) pal.accent else pal.border, RoundedCornerShape(16.dp)).clickable(onClick = onPick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SvgIcon(icon, if (on) pal.accent else pal.value, 22.dp)
            Text(t, color = pal.text, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).border(if (on) 6.dp else 2.dp, if (on) pal.accent else pal.border, RoundedCornerShape(10.dp)))
        }
        if (on) body()
    }
}
