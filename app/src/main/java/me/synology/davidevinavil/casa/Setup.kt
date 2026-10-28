package me.synology.davidevinavil.casa

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Risposta di /bt/abbina dall'hotspot: codice, nome, codice del relay e tipo. */
data class HotspotDev(val code: String, val name: String, val id: String, val type: String)

/**
 * La pagina dell'hotspot di un dispositivo (192.168.4.1). L'hotspot non ha
 * internet: Android manderebbe le richieste sui dati mobili, quindi si
 * usa esplicitamente la rete Wi-Fi.
 */
object Hotspot {
    private const val HOST = "http://192.168.4.1"

    @Suppress("DEPRECATION")
    fun wifi(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private fun post(net: Network, path: String): JSONObject? = try {
        val c = net.openConnection(URL(HOST + path)) as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 3000
        c.readTimeout = 5000
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
        c.setRequestProperty("Connection", "close")
        c.outputStream.close()
        val body = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        JSONObject(body)
    } catch (e: Exception) { null }

    /** Il telefono e' sull'hotspot di un dispositivo: apre l'abbinamento e da' il codice. */
    fun abbina(ctx: Context): HotspotDev? {
        val net = wifi(ctx) ?: return null
        val j = post(net, "/bt/abbina") ?: return null
        val code = j.optString("codice").filter { it.isDigit() }
        if (!j.optBoolean("ok") || code.length != 6) return null
        val t = j.optString("tipo")
        return HotspotDev(code, j.optString("nome"), j.optString("id").lowercase(), if (t == "gate" || t == "sensor") t else "ac")
    }

    /** Wi-Fi di casa e password del relay; null se accettati, altrimenti il motivo. */
    fun config(ctx: Context, ssid: String, pass: String, relay: String): String? {
        val net = wifi(ctx) ?: return "hotspot non piu' collegato"
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val j = post(net, "/bt/config?ssid=${e(ssid)}&pass=${e(pass)}&relay=${e(relay)}") ?: return "il dispositivo non risponde"
        return if (j.optBoolean("ok")) null else j.optString("err").ifBlank { "non accettato" }
    }

    /** Rete del telefono: nome e se e' a 5 GHz (serve il permesso di posizione). */
    @Suppress("DEPRECATION")
    fun phoneWifi(ctx: Context): Pair<String, Boolean>? {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val i = wm.connectionInfo ?: return null
        val s = i.ssid?.trim('"').orEmpty()
        if (s.isBlank() || s == "<unknown ssid>") return null
        return s to (i.frequency > 4900)
    }

    /** Reti vicine col nome che finisce per "Hotspot". */
    @Suppress("DEPRECATION")
    fun nearby(ctx: Context, scan: Boolean): List<String> = try {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        if (scan) wm?.startScan()
        wm?.scanResults.orEmpty().map { it.SSID.orEmpty() }.filter { it.endsWith("hotspot", true) }.distinct().sorted()
    } catch (e: Exception) { emptyList() }

    /** Il telefono ha di nuovo internet (non l'hotspot). */
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    fun openWifiSettings(ctx: Context) {
        val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

private enum class St { HOTSPOT, MODE, WIFI, RELAY, WORK, DONE }

/** Rete Wi-Fi o Solo ESP-NOW (solo sensori esterni). */
private val ICO_RADIO = listOf(IconPart("M12 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4z"), IconPart("M16.2 7.8a6 6 0 0 1 0 8.4"),
    IconPart("M7.8 16.2a6 6 0 0 1 0-8.4"), IconPart("M19 5a10 10 0 0 1 0 14"), IconPart("M5 19A10 10 0 0 1 5 5"))

/**
 * "Nuovo dispositivo" (condizionatore, gate o sensore esterno col firmware
 * col Bluetooth). Prima l'hotspot del dispositivo (che dice cos'e'), poi:
 *  - sensore esterno: Wi-Fi o Solo ESP-NOW;
 *  - Wi-Fi di casa e password del relay (non in Solo ESP-NOW);
 *  - impostazioni e abbinamento Bluetooth, infine l'ingresso nel relay
 *    (subito o appena il dispositivo si collega la prima volta).
 * La rete del telefono si legge all'inizio, prima di passare all'hotspot.
 */
@Composable
fun SetupScreen(app: CasaApp, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf(St.HOTSPOT) }
    var ssid by remember { mutableStateOf("") }
    var fromPhone by remember { mutableStateOf(false) }
    var ghz5 by remember { mutableStateOf(false) }
    var wpass by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var rpw by remember { mutableStateOf("") }
    var rpw2 by remember { mutableStateOf("") }
    var espnow by remember { mutableStateOf(false) }       // sensore: Solo ESP-NOW
    var near by remember { mutableStateOf(emptyList<String>()) }
    var step by remember { mutableStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var dev by remember { mutableStateOf<HotspotDev?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var relayIn by remember { mutableStateOf(false) }
    var btAddr by remember { mutableStateOf<String?>(null) }
    var hook by remember { mutableStateOf<SensorState?>(null) }
    var hookGiveUp by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Hotspot.phoneWifi(ctx)?.let { (s, f) -> if (ssid.isBlank()) { ssid = s; fromPhone = true; ghz5 = f } }
    }
    LaunchedEffect(Unit) {
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
        }
        perms.launch(need.toTypedArray())
    }

    val sensor = dev?.type == "sensor"
    fun back() {
        job?.cancel()
        st = when (st) {
            St.RELAY -> St.WIFI
            St.WIFI -> if (sensor) St.MODE else St.HOTSPOT
            St.MODE -> St.HOTSPOT
            else -> { onClose(); st }
        }
    }
    BackHandler { back() }
    fun afterHotspot() { st = if (sensor) St.MODE else St.WIFI }

    // Dalle impostazioni in poi, tutto da solo (telefono ancora sull'hotspot)
    fun work() {
        st = St.WORK; step = 0; err = null; relayIn = false; btAddr = null; hook = null; hookGiveUp = false
        job = scope.launch {
            // 0. codice (di nuovo: la finestra di 3 minuti riparte da adesso)
            var d: HotspotDev? = null
            for (i in 1..10) {
                d = withContext(Dispatchers.IO) { Hotspot.abbina(ctx) }
                if (d != null) break
                delay(2000)
            }
            val dv = d ?: run { err = "Il dispositivo non risponde sull'hotspot: ricollegati e riprova."; return@launch }
            dev = dv; code = dv.code; step = 1
            // 1. Wi-Fi e password del relay dall'hotspot (in Solo ESP-NOW dopo, via Bluetooth)
            if (!espnow) {
                val e1 = withContext(Dispatchers.IO) { Hotspot.config(ctx, ssid.trim(), wpass, rpw) }
                if (e1 != null) { err = "Impostazioni non accettate: $e1."; return@launch }
                if (dv.id.isNotBlank()) app.store.addPending(dv.id, rpw, dv.name)
            }
            step = 2
            // 2. Bluetooth (sempre acceso senza rete)
            val e2 = app.pairer.bond(dv.type, dv.name, dv.code)
            if (e2 != null) {
                err = e2 + if (espnow) "" else " Wi-Fi e password del relay sono già salvati sul dispositivo: puoi abbinarlo più tardi."
                return@launch
            }
            btAddr = app.store.sensors.value.firstOrNull { it.name.trim().equals(dv.name.trim(), true) }?.address
            if (espnow) {
                // Solo ESP-NOW: comando via Bluetooth, il sensore si riavvia col Wi-Fi spento
                val a = btAddr
                val e3 = if (a == null) "sensore non trovato dopo l'abbinamento" else app.sensors.cmd(a, "modo=espnow")
                if (e3 != null) { err = "Modo non impostato: $e3. Riprova dalla scheda del sensore."; return@launch }
                step = 4
                st = St.DONE
                return@launch
            }
            step = 3
            // 3. di nuovo su internet, poi subito un tentativo di ingresso nel relay
            withTimeoutOrNull(120_000) { while (!Hotspot.online(ctx)) delay(1500) }
            if (Hotspot.online(ctx) && dv.id.isNotBlank()) {
                val r = withContext(Dispatchers.IO) { Relay.login(dv.id, rpw) }
                if (r == Relay.Login.OK) { app.store.removePending(dv.id); relayIn = true }
            }
            step = 4
            st = St.DONE
        }
    }

    // Solo ESP-NOW finito: aggancio al termostato per al massimo un minuto (il
    // sensore si riavvia, poi cerca il canale); senza termostato si smette di
    // aspettare, il sensore continua a cercarlo da solo
    LaunchedEffect(st, btAddr) {
        val a = btAddr
        if (st != St.DONE || !espnow || a == null) return@LaunchedEffect
        delay(8000)
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            app.sensors.peek(a)?.let { hook = it; if (it.hooked) return@LaunchedEffect }
            delay(5000)
        }
        hookGiveUp = true
    }

    // hotspot vicini; appena il telefono e' sull'hotspot di un dispositivo si va avanti
    LaunchedEffect(st) {
        if (st != St.HOTSPOT) return@LaunchedEffect
        near = Hotspot.nearby(ctx, scan = true)
        while (true) {
            delay(2000)
            near = Hotspot.nearby(ctx, scan = false)
            val d = withContext(Dispatchers.IO) { if (Hotspot.wifi(ctx) != null) Hotspot.abbina(ctx) else null }
            if (d != null) { dev = d; afterHotspot(); break }
        }
    }

    Box(Modifier.fillMaxSize().background(pal.bg).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (st != St.DONE) {
                Back("Nuovo dispositivo", ::back)
                val n = when (st) { St.HOTSPOT -> 1; St.MODE -> 2; St.WIFI -> if (sensor) 3 else 2; St.RELAY -> if (sensor) 4 else 3; else -> 5 }
                val tot = if (sensor) 5 else 4
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..tot).forEach { i ->
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(when { i < n -> pal.ok; i == n -> pal.accent; else -> pal.border }))
                    }
                }
            }
            when (st) {
                St.HOTSPOT -> {
                    H("Collegati al dispositivo")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.card)
                        .border(1.dp, pal.border, RoundedCornerShape(16.dp)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        SvgIcon(ICO_WIFI, pal.accent, 34.dp)
                        Text("Il dispositivo senza rete accende un suo Wi-Fi, di solito «nome Hotspot». Collegati lì dalle impostazioni Wi-Fi del telefono, con la sua password.",
                            color = pal.value, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        if (near.isNotEmpty()) {
                            Text("VISTI QUI VICINO", color = pal.muted, fontSize = 11.sp, letterSpacing = 1.sp,
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp))
                            near.forEach { Text(it, color = pal.text, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Btn("Apri impostazioni Wi-Fi") { Hotspot.openWifiSettings(ctx) }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = pal.accent, strokeWidth = 2.dp)
                        Text("  Aspetto il collegamento a un hotspot…", color = pal.text, fontSize = 15.sp)
                    }
                    Note("Appena sei collegato l'app va avanti da sola. Android potrebbe dire «nessuna connessione a internet»: è normale, resta collegato.", center = true)
                }
                St.MODE -> {
                    H("Come funziona ${dev?.name ?: "il sensore"}?")
                    P("Il dispositivo è un sensore esterno: scegli come manda i valori al termostato.")
                    ModeOpt(ICO_WIFI, "Wi-Fi", "rete di casa e relay: valori anche da fuori casa, aggiornamenti automatici. Anche ESP-NOW sul canale del router.", !espnow) { espnow = false }
                    ModeOpt(ICO_RADIO, "Solo ESP-NOW", "dove il Wi-Fi non arriva: diretto al termostato. Da vicino via Bluetooth; aggiornamenti dall'app.", espnow) { espnow = true }
                    Note("Si cambia quando vuoi dall'app, via Bluetooth.", center = true)
                    Spacer(Modifier.height(14.dp))
                    Btn("Avanti") { if (espnow) work() else st = St.WIFI }
                }
                St.WIFI -> {
                    H("Wi-Fi di casa")
                    P("La rete a cui si collegherà il dispositivo.")
                    Field("Rete", ssid, { ssid = it; fromPhone = false; ghz5 = false },
                        tag = if (fromPhone && !manual) "del telefono" else null)
                    Field("Password", wpass, { wpass = it }, secret = !showPw,
                        trailing = { Text(if (showPw) "nascondi" else "mostra", color = pal.muted, fontSize = 13.sp,
                            modifier = Modifier.clickable { showPw = !showPw }.padding(8.dp)) })
                    if (!manual) Text("Qui non c'è Wi-Fi: lo scrivo a mano", color = pal.accent, fontSize = 15.sp,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp).clickable { manual = true; ssid = ""; fromPhone = false; ghz5 = false })
                    if (ghz5) Warn("Il telefono è su una rete a 5 GHz: il dispositivo vede solo il 2,4 GHz. Se la rete ha un nome diverso per il 2,4 GHz, scrivi quello.")
                    Note("Il dispositivo vede solo il Wi-Fi a 2,4 GHz. Se non c'è, resta in Bluetooth e si collega appena la rete arriva.", center = true)
                    val ok = ssid.isNotBlank() && ssid.length <= 32 && wpass.length in 8..63
                    if (wpass.isNotEmpty() && wpass.length < 8) Warn("La password del Wi-Fi ha almeno 8 caratteri.")
                    Spacer(Modifier.height(14.dp))
                    Btn("Avanti", enabled = ok) { st = St.RELAY }
                }
                St.RELAY -> {
                    H("Accesso da fuori casa")
                    P("La password con cui il dispositivo entra nel relay. L'app la usa anche per aggiungerlo alla tua lista.")
                    Field("Password del relay", rpw, { rpw = it.take(32) }, secret = true)
                    Field("Conferma", rpw2, { rpw2 = it.take(32) }, secret = true,
                        trailing = if (rpw2.isNotEmpty() && rpw2 == rpw) { { SvgIcon(listOf(IconPart("M5 12.5l4.5 4.5L19 7.5")), pal.ok, 20.dp, stroke = 2.4f) } } else null)
                    if (rpw.isNotEmpty() && rpw.length < 6) Warn("Almeno 6 caratteri.")
                    else if (rpw2.isNotEmpty() && rpw2 != rpw) Warn("Le due password non sono uguali.")
                    Note("Senza internet si salva lo stesso: il relay lo aggiunge quando arriva.", center = true)
                    Spacer(Modifier.height(14.dp))
                    Btn("Avanti", enabled = rpw.length >= 6 && rpw == rpw2) { work() }
                }
                St.WORK -> {
                    H(dev?.let { "Configuro ${it.name}" } ?: "Configuro il dispositivo")
                    code?.let {
                        Text(it.chunked(3).joinToString(" "), color = pal.accent, fontSize = 44.sp, letterSpacing = 4.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        P("Se Android chiede il codice, scrivi questo.", center = true)
                    }
                    val steps = if (espnow) listOf("Collegato all'hotspot", "Codice dal dispositivo", "Abbinamento e Solo ESP-NOW")
                                else listOf("Collegato all'hotspot", "Wi-Fi e relay impostati", "Abbinamento Bluetooth", "Di nuovo sulla tua rete")
                    Column(Modifier.padding(top = 16.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        steps.forEachIndexed { i, t ->
                            StepRow(t, ok = i < step, now = i == step && err == null, bad = i == step && err != null)
                        }
                    }
                    if (step == 3 && !espnow) Note("Se il telefono resta sull'hotspot, torna sulla tua rete Wi-Fi o sui dati mobili.", center = true)
                    err?.let { Warn(it) }
                    Spacer(Modifier.height(18.dp))
                    if (err != null) {
                        Btn("Riprova") { st = St.HOTSPOT }
                        Spacer(Modifier.height(8.dp))
                        Btn("Chiudi", secondary = true, onClick = onClose)
                    } else Btn("Annulla", secondary = true) { job?.cancel(); onClose() }
                }
                St.DONE -> {
                    Spacer(Modifier.height(30.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SvgIcon(listOf(IconPart("M5 12.5l4.5 4.5L19 7.5")), pal.ok, 58.dp, stroke = 2.4f) }
                    Text("${dev?.name ?: "Dispositivo"} aggiunto", color = pal.text, fontSize = 24.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(pal.card)
                        .border(1.dp, pal.border, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                        if (espnow) {
                            DoneRow(ICO_RADIO, "Funzionamento", "Solo ESP-NOW", pal.muted)
                            DoneRow(ICO_BT, "Bluetooth", "abbinato: da vicino anche senza internet", pal.ok)
                            val h = hook
                            when {
                                h?.hooked == true -> DoneRow(ICO_RADIO, "Termostato", "agganciato, canale ${h.ch}", pal.ok)
                                hookGiveUp -> DoneRow(ICO_RADIO, "Termostato", "non trovato: inserisci il MAC sul termostato, il sensore continua a cercarlo", Color(0xFFE7C12C))
                                else -> DoneRow(ICO_RADIO, "Termostato", "cerco il canale…", Color(0xFFE7C12C))
                            }
                        } else {
                            DoneRow(ICO_BT, "Bluetooth", "abbinato: da vicino anche senza internet", pal.ok)
                            DoneRow(ICO_WIFI, "Wi-Fi", "«${ssid.trim()}» salvato: si collega da solo", pal.muted)
                            if (relayIn) DoneRow(LOCK, "Relay", "nella tua lista", pal.ok)
                            else DoneRow(LOCK, "Relay", "lo aggiungo alla lista appena è online", Color(0xFFE7C12C))
                        }
                    }
                    if (espnow) {
                        val mac = hook?.mac ?: btAddr?.let { a -> app.sensors.stati.value[a]?.mac }
                        if (!mac.isNullOrBlank()) MacBox(mac)
                        Note("Sul termostato aggiungi il sensore ESP-NOW con questo MAC. Il MAC lo ritrovi sempre nella scheda del sensore.", center = true)
                    } else Note("Tutto salvato sul dispositivo: senza Wi-Fi o senza internet resta in Bluetooth e passa al relay quando la rete c'è.", center = true)
                    Spacer(Modifier.height(14.dp))
                    Btn("Fine", onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun ModeOpt(icon: List<IconPart>, t: String, s: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(if (on) pal.sel else pal.card)
            .border(1.dp, if (on) pal.accent else pal.border, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SvgIcon(icon, if (on) pal.accent else pal.value, 26.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t, color = pal.text, fontSize = 17.sp)
            Text(s, color = pal.muted, fontSize = 13.sp)
        }
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).border(if (on) 6.dp else 2.dp, if (on) pal.accent else pal.border, RoundedCornerShape(10.dp)))
    }
}

/** MAC da inserire nel termostato (Solo ESP-NOW), con copia. */
@Composable
fun MacBox(mac: String) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(pal.sel)
        .border(1.dp, pal.accent.copy(alpha = .35f), RoundedCornerShape(16.dp)).clickable {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("MAC", mac))
        }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MAC da inserire nel termostato", color = pal.value, fontSize = 13.sp)
        Text(mac, color = pal.accent, fontSize = 22.sp, letterSpacing = 1.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 6.dp))
        Text("tocca per copiare", color = pal.accent, fontSize = 12.sp)
    }
}

private val LOCK = listOf(IconPart("M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z"), IconPart("M8 11V8a4 4 0 0 1 8 0v3"))

@Composable private fun H(t: String) = Text(t, color = pal.text, fontSize = 22.sp, modifier = Modifier.padding(bottom = 10.dp))

@Composable private fun P(t: String, center: Boolean = false) =
    Text(t, color = pal.value, fontSize = 15.sp, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))

@Composable private fun Warn(t: String) =
    Text(t, color = Color(0xFFE7C12C), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))

@Composable
private fun Field(label: String, v: String, set: (String) -> Unit, secret: Boolean = false, tag: String? = null,
                  trailing: (@Composable () -> Unit)? = null) {
    Text(label, color = pal.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, start = 4.dp, bottom = 4.dp))
    OutlinedTextField(
        value = v, onValueChange = set, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        trailingIcon = when {
            trailing != null -> trailing
            tag != null -> { { Text(tag, color = pal.accent, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp)) } }
            else -> null
        },
    )
}

@Composable
private fun StepRow(t: String, ok: Boolean, now: Boolean, bad: Boolean) {
    val red = Color(0xFFFF5A5A)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
            when {
                ok -> SvgIcon(listOf(IconPart("M5 12.5l4.5 4.5L19 7.5")), pal.ok, 16.dp, stroke = 2.4f)
                now -> CircularProgressIndicator(Modifier.size(16.dp), color = pal.accent, strokeWidth = 2.dp)
                else -> Text("•", color = if (bad) red else pal.muted, fontSize = 16.sp)
            }
        }
        Text(t, fontSize = 17.sp, color = when { ok -> pal.ok; bad -> red; now -> pal.text; else -> pal.muted })
    }
}

@Composable
private fun DoneRow(icon: List<IconPart>, t: String, s: String, c: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(icon, pal.accent, 22.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(t, color = pal.text, fontSize = 17.sp)
            Text(s, color = c, fontSize = 13.sp)
        }
    }
}
