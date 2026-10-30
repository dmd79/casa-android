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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * "Aggiorna firmware" di condizionatore e gate collegati in Bluetooth, come
 * quello dell'ext-sensor: firmware da un indirizzo (http o https) o da un
 * file sul telefono, poi caricato sulla pagina del dispositivo (/update).
 *  - dispositivo sul Wi-Fi di casa e telefono sulla stessa rete: al suo IP;
 *  - senza Wi-Fi: dal suo hotspot (acceso da solo quando la rete manca).
 * [where]: IP del dispositivo se e' collegato al Wi-Fi, letto via Bluetooth.
 * [key]: dove ricordare l'indirizzo scelto (indirizzo Bluetooth). [hotspot]: nome
 * dell'hotspot, vuoto se non si conosce (gate: ognuno ha il suo).
 * [setOta]: manda al dispositivo l'indirizzo degli aggiornamenti scelto qui
 * (null se accettato), cosi' app e dispositivo usano lo stesso.
 * [readMd5]: md5 del firmware in uso letto ora dal dispositivo, per verificare
 * dopo il riavvio che abbia proprio il file caricato.
 */
@Composable
fun DevFwUpdate(name: String, word: String, key: String, store: Store, devOta: String, devMd5: String,
                hotspot: String, where: suspend () -> String?, setOta: (suspend (String) -> String?)? = null,
                readMd5: (suspend () -> String?)? = null, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var started by remember { mutableStateOf(false) }
    var useFile by remember { mutableStateOf(false) }
    // quello scritto nell'app (ricordato per questo dispositivo); se non c'e', quello del dispositivo
    var url by remember { mutableStateOf(store.fwUrl(key).ifBlank { devOta }) }
    var edited by remember { mutableStateOf(false) }
    // si ricorda subito, anche senza aggiornare
    LaunchedEffect(url) { val u = url.trim().trimEnd('/'); if (edited && u.startsWith("http")) store.setFwUrl(key, u) }
    var avail by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableStateOf<LocalFw?>(null) }
    var pickErr by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (f, e) = withContext(Dispatchers.IO) { copyFw(ctx, uri) }
            local = f; pickErr = e
        }
    }
    LaunchedEffect(devOta) { if (url.isBlank() && devOta.isNotBlank()) url = devOta }
    LaunchedEffect(url, useFile) {
        avail = null
        if (useFile || !url.trim().startsWith("http")) return@LaunchedEffect
        delay(700)
        avail = withContext(Dispatchers.IO) { FwUpdate.md5(url) } ?: ""
    }

    // 0 firmware pronto, 1 dispositivo trovato, 2 collegamento, 3 carico, 4 riavvio, 5 fatto
    var step by remember { mutableStateOf(0) }
    var pct by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf<String?>(null) }     // null: hotspot
    var go by remember { mutableStateOf(0) }
    var built by remember { mutableStateOf<String?>(null) }     // data di compilazione del firmware scaricato
    var retry by remember { mutableStateOf(false) }             // secondo tentativo automatico in corso
    var verified by remember { mutableStateOf<Boolean?>(null) } // dopo il riavvio: true stesso md5, false diverso, null non letto
    LaunchedEffect(go) {
        retry = false; verified = null
        if (go == 0) return@LaunchedEffect
        err = null; step = 0; pct = 0; host = null
        // 0. firmware: dal telefono o scaricato, finche' c'e' la rete
        val f: File = if (useFile) {
            local?.file ?: run { err = "Scegli prima il file del firmware."; return@LaunchedEffect }
        } else {
            val u = url.trim().trimEnd('/')
            store.setFwUrl(key, u)
            // indirizzo cambiato: lo prende anche il dispositivo (pagina e aggiornamenti da solo)
            if (setOta != null && u != devOta.trimEnd('/')) { try { setOta(u) } catch (_: Exception) {} }
            val out = File(ctx.cacheDir, "fw_dev.bin")
            val e0 = withContext(Dispatchers.IO) { FwUpdate.download(u, out) { p -> pct = p } }
            if (e0 != null) { err = "Firmware non scaricato: $e0. Senza rete scegli «File sul telefono»."; return@LaunchedEffect }
            built = withContext(Dispatchers.IO) { FwUpdate.built(out) }
            out
        }
        step = 1; pct = 0
        // 1. dove si trova: sul Wi-Fi di casa (IP) o solo hotspot
        val ip = try { where() } catch (_: Exception) { null }
        if (!ip.isNullOrBlank()) {
            if (!withContext(Dispatchers.IO) { FwUpdate.pageUp(ctx, ip) }) {
                err = "Il $word è sul Wi-Fi di casa ($ip) ma il telefono non lo raggiunge: collega il telefono allo stesso Wi-Fi e riprova."
                return@LaunchedEffect
            }
            host = ip
            step = 3
        } else {
            step = 2
            // 2. telefono sull'hotspot (10 minuti al massimo)
            val up = withTimeoutOrNull(600_000) { while (!withContext(Dispatchers.IO) { FwUpdate.pageUp(ctx) }) delay(2000); true }
            if (up != true) { err = "Hotspot non raggiunto entro 10 minuti."; return@LaunchedEffect }
            step = 3
        }
        // 3. caricamento
        val h = host ?: FwUpdate.HOTSPOT
        val want = withContext(Dispatchers.IO) { FwUpdate.fileMd5(f) }
        val oldMd5 = devMd5
        val e3 = withContext(Dispatchers.IO) { FwUpdate.uploadRetry(ctx, f, h, retrying = { retry = true }) { p -> pct = p } }
        if (e3 != null) { err = "Aggiornamento non riuscito: $e3."; return@LaunchedEffect }
        step = 4
        delay(8000)
        if (!useFile) f.delete()
        // 4. verifica: il dispositivo riavviato ha proprio questo firmware?
        readMd5?.let { r -> verified = FwUpdate.verify(want, oldMd5, r) }
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
                if (devOta.isNotBlank() && url.trim() != devOta) Text("Ripristina quello del $word", color = pal.accent, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable { url = devOta; edited = true })
                val inst = devMd5.lowercase()
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
                    .border(BW, pal.border, RoundedCornerShape(10.dp)).clickable { picker.launch(arrayOf("*/*")) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l?.name ?: "Nessun file scelto", color = pal.text, fontSize = 15.sp)
                        Text(l?.let { String.format(Locale.ITALY, "%.2f MB", it.size / 1048576.0) + (it.built?.let { b -> " · compilato il $b" } ?: "") } ?: "tocca per scegliere il .bin", color = pal.muted, fontSize = 12.sp)
                    }
                    Text(if (l == null) "Scegli" else "Cambia", color = pal.accent, fontSize = 14.sp)
                }
                pickErr?.let { Text(it, color = Color(0xFFE7C12C), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            Note(if (useFile) "Nessuna rete necessaria: il file va al $word dal Wi-Fi di casa o dal suo hotspot."
                 else "Scarico prima, finché c'è la rete; poi lo carico sul $word.", center = true)
            Spacer(Modifier.height(6.dp))
            Btn(if (useFile) "Aggiorna" else "Scarica e aggiorna", enabled = if (useFile) local != null else url.trim().startsWith("http")) {
                started = true; go++
            }
            return@Column
        }
        val lan = host != null
        Text(if (lan) "Il $word è sul Wi-Fi di casa: carico il firmware al suo indirizzo, senza hotspot."
             else if (step >= 2) "Nessun Wi-Fi: il $word ha acceso il suo hotspot." else "Preparo l'aggiornamento.",
            color = pal.value, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
        Column(Modifier.padding(top = 14.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                // con la data della build: se il file sul server e' vecchio si vede subito
                when { useFile -> "File pronto"; step == 0 && pct > 0 -> "Scarico il firmware $pct %"; else -> "Firmware scaricato" } +
                    ((if (useFile) local?.built else built)?.let { " (compilato il $it)" } ?: ""),
                if (lan) "Trovato in rete: $host" else "Cerco il $word",
                if (lan) "Stessa rete del telefono" else if (hotspot.isBlank()) "Collegati all'hotspot del $word" else "Collegati all'hotspot «$hotspot»",
                if (step == 3) (if (retry) "Interrotto, riprovo: $pct %" else "Caricamento $pct %") else "Caricamento",
                if (step == 4) "Riavvio e verifica" else "Riavvio",
            ).forEachIndexed { i, t ->
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
            Note("Nelle impostazioni Wi-Fi del telefono collegati all'hotspot del $word" + (if (hotspot.isBlank()) "" else " («$hotspot»)") +
                ", con la sua password. Appena sei collegato va avanti da solo.", center = true)
            Btn("Apri impostazioni Wi-Fi") { Hotspot.openWifiSettings(ctx) }
        }
        if (step == 5) Note((when (verified) {
            true -> "Fatto e verificato: il $word ha il firmware nuovo."
            false -> "Il $word si è riavviato ma non ha il firmware caricato (md5 diverso): riprova l'aggiornamento."
            else -> "Fatto: il $word si è riavviato (non verificato in Bluetooth)."
        }) + " Lo ritrovi in Bluetooth o sul relay." +
            if (lan) "" else " Puoi tornare sulla tua rete.", center = true)
        err?.let { Text(it, color = Color(0xFFE7C12C), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        if (err != null) {
            Btn("Riprova") { go++ }
            Btn("Cambia fonte", secondary = true, modifier = Modifier.padding(top = 8.dp)) { started = false; err = null; step = 0 }
        }
        Btn(if (step == 5 || err != null) "Chiudi" else "Annulla", secondary = true, modifier = Modifier.padding(top = 8.dp), onClick = onBack)
    }
}
