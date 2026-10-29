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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

enum class Scr { DEVICES, SCAN, CTL, SET, WIFI, SCHED, FW }

@Composable
fun App(ble: Ble, store: Store, go: MutableStateFlow<Scr?>? = null) {
    val link by ble.link.collectAsState()
    val addr by ble.address.collectAsState()
    val st by ble.state.collectAsState()
    var scr by rememberSaveable { mutableStateOf(if (store.last != null) Scr.CTL else Scr.DEVICES) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { ble.toasts.collect { snack.showSnackbar(it) } }
    // richieste dalla barra di Casa (per esempio "Aggiungi condizionatore Bluetooth")
    LaunchedEffect(go) { val g = go ?: return@LaunchedEffect; g.collect { r -> if (r != null) { scr = r; g.value = null } } }

    BackHandler(enabled = scr != Scr.CTL && !(scr == Scr.DEVICES && addr == null)) {
        scr = when (scr) {
            Scr.WIFI, Scr.FW -> Scr.SET
            Scr.SCAN -> if (addr != null) Scr.CTL else Scr.DEVICES
            else -> Scr.CTL
        }
    }
    if (scr == Scr.CTL && addr == null) scr = Scr.DEVICES

    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            when {
                link == Link.NO_PERMISSION -> PermissionScreen(ble)
                scr == Scr.DEVICES -> DevicesScreen(ble, store, onAdd = { scr = Scr.SCAN }, onPick = {
                    ble.connectTo(it); scr = Scr.CTL })
                scr == Scr.SCAN -> ScanScreen(ble, store, onBack = { scr = if (addr != null) Scr.CTL else Scr.DEVICES }) {
                    store.put(it.address, it.name); ble.connectTo(it.address); scr = Scr.CTL }
                scr == Scr.SET && st != null -> SettingsScreen(ble, store, onBack = { scr = Scr.CTL },
                    onWifi = { scr = Scr.WIFI }, onForgot = { scr = Scr.DEVICES }, onFw = { scr = Scr.FW })
                scr == Scr.FW && st != null && addr != null -> {
                    val inf by ble.info.collectAsState()
                    DevFwUpdate(st!!.name.ifBlank { "condizionatore" }, "condizionatore", addr!!, store, inf?.ota.orEmpty(), inf?.md5.orEmpty(),
                        "Haier Hotspot", where = { ble.readInfo(); ble.info.value?.let { if (it.wifi && it.ip.isNotBlank()) it.ip else null } },
                        onBack = { scr = Scr.SET })
                }
                scr == Scr.WIFI && st != null -> WifiScreen(ble, onBack = { scr = Scr.SET })
                scr == Scr.SCHED && st != null -> SchedScreen(ble, st!!.name, onBack = { scr = Scr.CTL })
                link == Link.UNPAIRED -> PairScreen(ble, store, onDevices = { scr = Scr.DEVICES })
                st == null -> WaitScreen(ble, store, link, onDevices = { scr = Scr.DEVICES })
                else -> ControlScreen(ble, st!!, link, onSettings = { scr = Scr.SET }, onDevices = { scr = Scr.DEVICES }, onSched = { scr = Scr.SCHED })
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

// ── permessi ──────────────────────────────────────────────

@Composable
fun PermissionScreen(ble: Ble) {
    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ble.resume() }
    LaunchedEffect(Unit) { ask.launch(perms) }
    Column(Modifier.fillMaxSize()) {
        Title("Clima BT")
        Sub("condizionatori via Bluetooth")
        BigBt()
        Text("Per trovare e comandare i condizionatori serve il permesso «Dispositivi nelle vicinanze».",
            color = pal.value, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(26.dp))
        Btn("Consenti") { ask.launch(perms) }
        Note("Se l'hai negato due volte, Android non lo chiede più: Impostazioni → App → Clima BT → Autorizzazioni.", center = true)
    }
}

@Composable
private fun BigBt() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp, bottom = 30.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(140.dp).clip(CircleShape).background(pal.card).border(1.dp, pal.border, CircleShape),
            contentAlignment = Alignment.Center) { SvgIcon(Icons.bt, pal.accent, 64.dp) }
    }
}

// ── in attesa del collegamento ────────────────────────────

@Composable
fun WaitScreen(ble: Ble, store: Store, link: Link, onDevices: () -> Unit) {
    val addr by ble.address.collectAsState()
    val name = store.name(addr).ifBlank { "il condizionatore" }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { ble.resume() }
    Column(Modifier.fillMaxSize()) {
        Title(store.name(addr).ifBlank { "Condizionatore" })
        Sub("via Bluetooth, senza Wi-Fi")
        BigBt()
        val msg = when (link) {
            Link.BT_OFF -> "Bluetooth del telefono spento: mi collego appena lo riaccendi"
            Link.CONNECTED -> "Collegato, leggo lo stato…"
            else -> "Cerco $name… mi collego appena è a portata"
        }
        Text(msg, color = pal.value, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(26.dp))
        if (link == Link.BT_OFF) Btn("Accendi il Bluetooth") { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        Spacer(Modifier.weight(1f))
        Btn("Altri condizionatori", secondary = true, onClick = onDevices)
    }
}

// ── abbinamento (una volta per telefono) ──────────────────

@Composable
fun PairScreen(ble: Ble, store: Store, onDevices: () -> Unit) {
    val addr by ble.address.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Title(store.name(addr).ifBlank { "Condizionatore" })
        Sub("via Bluetooth, anche senza Wi-Fi")
        BigBt()
        listOf(
            "Pagina web del condizionatore → Rete → Bluetooth → «Abbina telefono»: compare un codice di 6 cifre, valido 3 minuti. Senza Wi-Fi la pagina si apre collegandosi all'hotspot del condizionatore.",
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
                val r = ble.pair()
                busy = false
                if (r != null) err = r
            }
        }
        if (err.isNotEmpty()) Text(err, color = pal.danger, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))
        Btn("Altri condizionatori", secondary = true, onClick = onDevices)
        Note("Fatto una volta, il telefono resta abbinato anche dopo riavvii e aggiornamenti del condizionatore.", center = true)
    }
}

// ── condizionatori salvati ────────────────────────────────

@Composable
fun DevicesScreen(ble: Ble, store: Store, onAdd: () -> Unit, onPick: (String) -> Unit) {
    val list by store.devices.collectAsState()
    val addr by ble.address.collectAsState()
    val link by ble.link.collectAsState()
    var del by remember { mutableStateOf<Saved?>(null) }
    del?.let { d ->
        Confirm("Dimenticare ${d.name}?", "Va tolto dall'elenco e dagli abbinamenti del telefono. Potrai aggiungerlo di nuovo cercandolo.",
            "Dimentica", danger = true, onDismiss = { del = null }) { del = null; ble.forget(d.address) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Title("Condizionatori")
        Sub(if (list.isEmpty()) "nessuno ancora: cercalo qui sotto" else "tocca per collegarti")
        Spacer(Modifier.height(20.dp))
        list.forEach { d ->
            val cur = d.address == addr
            val sub = when {
                !cur -> d.address
                link == Link.CONNECTED -> "collegato"
                link == Link.BT_OFF -> "Bluetooth spento"
                else -> "in ricerca…"
            }
            DeviceRow(d.name, sub, subColor = if (cur && link == Link.CONNECTED) pal.ok else null, selected = cur,
                onDelete = { del = d }) { onPick(d.address) }
        }
        Spacer(Modifier.height(18.dp))
        Btn("Aggiungi condizionatore", onClick = onAdd)
        Note("L'app si collega da sola all'ultimo usato, anche dopo aver spento e riacceso il Bluetooth.", center = true)
    }
}

@Composable
fun ScanScreen(ble: Ble, store: Store, onBack: () -> Unit, onPick: (Found) -> Unit) {
    val found by ble.found.collectAsState()
    val scanning by ble.scanning.collectAsState()
    val saved by store.devices.collectAsState()
    LaunchedEffect(Unit) { ble.startScan() }
    DisposableEffect(Unit) { onDispose { ble.stopScan() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Aggiungi", onBack)
        Sub(if (scanning) "cerco i condizionatori vicini…" else "ricerca finita")
        Spacer(Modifier.height(18.dp))
        found.forEach { f ->
            val known = saved.any { it.address == f.address }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(pal.card)
                    .border(1.dp, pal.border, RoundedCornerShape(16.dp)).clickable { onPick(f) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(f.name, color = pal.text, fontSize = 19.sp)
                    Text(if (known) "già salvato" else f.address, color = pal.muted, fontSize = 14.sp)
                }
                SignalBars(f.rssi)
            }
        }
        if (!scanning && found.isEmpty())
            Note("Nessun condizionatore trovato. Con la rete a posto il condizionatore non si annuncia in Bluetooth: apri prima l'abbinamento dalla sua pagina web (Rete → Bluetooth → «Abbina telefono») e resta a pochi metri.", center = true)
        Spacer(Modifier.height(18.dp))
        Btn("Cerca di nuovo", secondary = true, enabled = !scanning) { ble.startScan() }
    }
}

@Composable
fun SignalBars(rssi: Int) {
    val lv = when { rssi > -60 -> 3; rssi > -70 -> 2; rssi > -80 -> 1; else -> 0 }
    val col = if (lv >= 2) pal.ok else androidx.compose.ui.graphics.Color(0xFFE7C12C)
    Row(verticalAlignment = Alignment.Bottom) {
        (0..3).forEach { b ->
            Box(Modifier.padding(start = 3.dp).width(5.dp).height((7 + b * 5).dp).clip(RoundedCornerShape(2.dp))
                .background(if (b <= lv) col else pal.border))
        }
        Text("  $rssi", color = pal.muted, fontSize = 14.sp)
    }
}

@Composable
fun Back(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = pal.accent, fontSize = 38.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
        Text(title, color = pal.text, fontSize = 26.sp)
    }
}

// ── impostazioni ──────────────────────────────────────────

@Composable
fun SettingsScreen(ble: Ble, store: Store, onBack: () -> Unit, onWifi: () -> Unit, onForgot: () -> Unit, onFw: () -> Unit = {}) {
    val st by ble.state.collectAsState()
    val info by ble.info.collectAsState()
    val addr by ble.address.collectAsState()
    var dlg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { ble.readInfo(); delay(3000) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Impostazioni", onBack)
        Sub(st?.name ?: "")
        Section("RETE")
        RowCard("Wi-Fi", info?.let { if (it.wifi) "${it.ssid} · ${it.rssi} dBm" else "non collegato" } ?: "--") {
            if (ble.hasInfo) onWifi() }
        Note("Wi-Fi, relay e Bluetooth insieme: da casa e da fuori via relay, da vicino anche senza internet via Bluetooth.")
        Section("INFO")
        val i = info
        val rows = if (i == null) listOf((if (ble.hasInfo) "Lettura…" else "Aggiorna il firmware per vedere le info") to "")
        else listOf(
            "Memoria libera" to kb(i.heap), "Blocco più grande" to kb(i.block), "Acceso da" to uptime(i.uptime),
            "Ultimo riavvio" to i.reset.ifBlank { "--" }, "Indirizzo IP" to (if (i.wifi && i.ip.isNotBlank()) i.ip else "--"),
            "Hotspot" to (if (i.ap) "acceso" else "spento"), "Firmware" to i.fw)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.card)
            .border(1.dp, pal.border, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 4.dp)) {
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, color = pal.value, fontSize = 16.sp, maxLines = 1)
                    // valore lungo (es. "Ultimo riavvio"): va a capo nel suo spazio, allineato a destra
                    Text(v, color = if (k == "Blocco più grande" && (i?.block ?: 9999) < 4096) pal.danger else pal.text, fontSize = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f).padding(start = 16.dp))
                }
            }
        }
        Section("DISPOSITIVO")
        RowCard("Nome", st?.name ?: "--") { dlg = "name" }
        if (info?.ota?.isNotBlank() == true) RowCard("Aggiorna firmware", "anche senza internet", onClick = onFw)
        RowCard("Dimentica questo condizionatore") { dlg = "forget" }
        Note("App ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp), center = true)
    }

    when (dlg) {
        "name" -> Ask("Nome del condizionatore", "", st?.name ?: "", ok = "Salva", onDismiss = { dlg = null }) { v ->
            dlg = null
            val n = v.trim()
            if (n.isEmpty()) { ble.toasts.tryEmit("Nome vuoto: non salvato"); return@Ask }
            ble.launch { if (send("name", n) { it.copy(name = n) }) { addr?.let { store.put(it, n) }; toasts.emit("Nome inviato: $n") } }
        }
        "forget" -> Confirm("Dimenticare il condizionatore?", "Si scollega e va tolto dall'elenco, insieme al PIN salvato. Potrai aggiungerlo di nuovo cercandolo.",
            "Dimentica", danger = true, onDismiss = { dlg = null }) {
            dlg = null
            val a = addr
            if (a != null) ble.forget(a) else { ble.disconnect(); ble.address.value = null }
            onForgot()
        }
    }
}

private fun kb(b: Long) = String.format(java.util.Locale.ITALY, "%.1f KB", b / 1024.0)
private fun uptime(s: Long): String {
    val d = s / 86400; val h = s % 86400 / 3600; val m = s % 3600 / 60
    return (if (d > 0) "$d g " else "") + (if (d > 0 || h > 0) "$h h " else "") + "$m min"
}

// ── rete Wi-Fi ────────────────────────────────────────────

@Composable
fun WifiScreen(ble: Ble, onBack: () -> Unit) {
    val nets by ble.nets.collectAsState()
    var sel by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        msg = "Cerco le reti…"; sel = ""
        ble.send("scan", "1")
        repeat(6) { delay(2000); ble.readNets() }
        msg = ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back("Rete Wi-Fi", onBack)
        Sub("reti viste dal condizionatore")
        Spacer(Modifier.height(18.dp))
        nets.take(10).forEach { n ->
            val on = n.ssid == sel
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (on) pal.sel else pal.card)
                    .border(if (on) 2.dp else 1.dp, if (on) pal.accent else pal.border, RoundedCornerShape(16.dp))
                    .clickable { sel = n.ssid; pw = "" }.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) { Text(n.ssid, color = pal.text, fontSize = 18.sp); SignalBars(n.rssi) }
        }
        if (sel.isNotEmpty()) {
            Note("Password di $sel")
            OutlinedTextField(
                value = pw, onValueChange = { pw = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                placeholder = { Text("password") },
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { ShowPw(show) { show = !show } },
                colors = fieldColors(),
            )
            Spacer(Modifier.height(18.dp))
            Btn("Collega") {
                val s = sel
                ble.launch { send("wifi", s + "\t" + pw) }
                msg = "Collegamento a $s… controlla lo stato nelle Impostazioni"; sel = ""
            }
            Note("Il Bluetooth resta acceso", center = true)
        }
        if (msg.isNotEmpty()) Note(msg, Modifier.fillMaxWidth(), center = true)
        Spacer(Modifier.height(18.dp))
        Btn("Cerca di nuovo", secondary = true) { round++ }
    }
}

// ── finestre ──────────────────────────────────────────────

/** "mostra" / "nascondi" in fondo a un campo password. */
@Composable
fun ShowPw(show: Boolean, toggle: () -> Unit) =
    Text(if (show) "nascondi" else "mostra", color = pal.muted, fontSize = 13.sp,
        modifier = Modifier.clickable(onClick = toggle).padding(horizontal = 10.dp, vertical = 8.dp))

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = pal.text, unfocusedTextColor = pal.text, focusedBorderColor = pal.accent,
    unfocusedBorderColor = pal.border, cursorColor = pal.accent, focusedContainerColor = pal.card, unfocusedContainerColor = pal.card,
)

@Composable
fun Confirm(title: String, text: String, ok: String, danger: Boolean = false, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.bg,
        title = { Text(title, color = pal.text, fontSize = 21.sp) },
        text = { Text(text, color = pal.value, fontSize = 16.sp) },
        confirmButton = { TextButton(onClick = onOk) { Text(ok, color = if (danger) pal.danger else pal.accent, fontSize = 17.sp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = pal.value, fontSize = 17.sp) } },
    )
}

@Composable
fun Ask(title: String, text: String, initial: String, number: Boolean = false, secret: Boolean = false, ok: String,
        onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.bg,
        title = { Text(title, color = pal.text, fontSize = 21.sp) },
        text = {
            Column {
                if (text.isNotEmpty()) Text(text, color = pal.value, fontSize = 16.sp)
                OutlinedTextField(
                    value = v, onValueChange = { v = if (number) it.filter(Char::isDigit).take(4) else it },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = fieldColors(),
                    visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.NumberPassword else KeyboardType.Text),
                    trailingIcon = if (secret) { { ShowPw(show) { show = !show } } } else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text(ok, color = pal.accent, fontSize = 17.sp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = pal.value, fontSize = 17.sp) } },
    )
}
