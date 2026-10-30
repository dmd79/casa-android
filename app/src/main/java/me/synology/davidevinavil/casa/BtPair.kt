package me.synology.davidevinavil.casa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Finestra di abbinamento: [step] e' il passo in corso (0 codice, 1 ricerca,
 * 2 abbinamento); [done] tutto riuscito; [err] il motivo dello stop.
 */
data class PairUi(val type: String, val name: String, val step: Int, val code: String? = null,
                  val done: Boolean = false, val err: String? = null)

private fun normName(s: String) = s.trim().lowercase()

/**
 * "Abbina Bluetooth" dalla lista dei dispositivi del relay (ac e gate col
 * firmware relay-o-Bluetooth):
 *  1. il dispositivo, via relay (/_btpair), apre l'abbinamento e risponde col
 *     codice; senza codice entro 10 s ci si ferma (Bluetooth del telefono intatto);
 *  2. ricerca in Bluetooth del dispositivo (ha acceso il Bluetooth per l'abbinamento);
 *  3. abbinamento, col codice scritto dall'app se Android lo permette.
 * Riuscito: lo si salva nell'app e il dispositivo torna da solo sul relay
 * dopo pochi secondi; non riuscito: ci torna alla chiusura della finestra (3 minuti).
 */
@SuppressLint("MissingPermission")
class BtPairer(private val ctx: Context, private val store: Store, private val ble: Ble, private val gb: GateBle,
               private val sensors: SensorBle) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    val ui = MutableStateFlow<PairUi?>(null)
    /** Conferma di "Dimentica Bluetooth": tipo e nome. */
    val forgetAsk = MutableStateFlow<Pair<String, String>?>(null)
    /** La lista del relay ridisegna simboli e tasti. */
    @Volatile var notify: () -> Unit = {}
    private var job: Job? = null

    /** Nomi (minuscoli) dei dispositivi abbinati nell'app: {"ac":[...],"gate":[...],"sensor":[...]}. */
    fun pairedJson(): String = JSONObject()
        .put("ac", org.json.JSONArray(store.devices.value.map { normName(it.name) }))
        .put("gate", org.json.JSONArray(store.gates.value.map { normName(it.name) }))
        .put("sensor", org.json.JSONArray(store.sensors.value.map { normName(it.name) }))
        .toString()

    fun start(id: String, type: String, name: String, cookie: String?) {
        if (job?.isActive == true) return
        ui.value = PairUi(type, name, 0)
        job = scope.launch {
            try { run(id, type, name, cookie) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("non riuscito: ${e.message ?: "errore"}") }
        }
    }

    fun cancel() { job?.cancel(); job = null; ui.value = null }

    fun close() { if (job?.isActive != true) ui.value = null }

    private fun fail(msg: String) { ui.value = ui.value?.copy(err = msg) }

    private suspend fun run(id: String, type: String, name: String, cookie: String?) {
        // prima di chiedere il codice: senza permesso o Bluetooth inutile far cambiare il dispositivo
        if (!gb.hasPermissions()) return fail("Manca il permesso Bluetooth: aprilo una volta da Altro → Gate Bluetooth. Il dispositivo resta sul relay.")
        val ad = adapter
        if (ad == null || !ad.isEnabled) return fail("Accendi il Bluetooth del telefono e riprova. Il dispositivo resta sul relay.")

        // 1. codice dal dispositivo, tramite il relay
        val (code, err) = withContext(Dispatchers.IO) { Relay.btPair(cookie, id) }
        if (code == null) return fail(when (err) {
            null -> "$name non ha dato il codice entro 10 secondi."
            else -> "$name non ha dato il codice ($err)."
        } + " Il Bluetooth del telefono non è stato toccato: riprova tra poco.")
        ui.value = ui.value?.copy(code = code, step = 1)
        val later = if (type == "sensor") "" else " Torna da solo sul relay entro 3 minuti."

        // 2-3. ricerca e abbinamento
        val err2 = bond(type, name, code) { ui.value = ui.value?.copy(step = 2) }
        if (err2 != null) return fail("$err2$later")
        ui.value = ui.value?.copy(done = true)
        notify()
    }

    /**
     * Cerca [name] in Bluetooth (il dispositivo ha appena aperto l'abbinamento
     * col codice [code]), lo abbina scrivendo il codice e lo salva nell'app.
     * null se riuscito, altrimenti il motivo. [onFound]: trovato, si abbina.
     * Usato anche da "Nuovo dispositivo" (SetupScreen.kt): [addr] dispositivo
     * gia' trovato (niente ricerca per nome), [after] eseguito sul
     * collegamento appena abbinato (Wi-Fi e relay), null se riuscito.
     */
    internal suspend fun bond(type: String, name: String, code: String, addr: String? = null,
                              after: (suspend (GateConn) -> String?)? = null, onFound: () -> Unit = {}): String? {
        if (!gb.hasPermissions()) return "Manca il permesso Bluetooth."
        if (adapter?.isEnabled != true) return "Il Bluetooth del telefono è spento."
        gb.stopScan(); ble.stopScan(); sensors.stopScan()
        val svc = when (type) { "gate" -> GateUuids.SVC; "sensor" -> SensorUuids.SVC; else -> Uuids.SVC }
        val rev = when (type) { "gate" -> GateUuids.REV; "sensor" -> SensorUuids.REV; else -> Uuids.REV }
        val (dev, advName) = (if (addr != null) adapter?.getRemoteDevice(addr)?.let { it to name } else find(svc, name))
            ?: return "Non trovo $name in Bluetooth: avvicinati e riprova."
        onFound()
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {          // rimasto solo sul telefono: da rifare
            try { dev.javaClass.getMethod("removeBond").invoke(dev) } catch (_: Exception) {}
            delay(1500)
        }
        val bonded = CompletableDeferred<Int>()
        val rxBond = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (extraDev(i)?.address != dev.address) return
                val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (s == BluetoothDevice.BOND_BONDED || s == BluetoothDevice.BOND_NONE) bonded.complete(s)
            }
        }
        // Android chiede il codice: lo scrive l'app (se non riesce resta la sua finestra)
        val rxPin = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = extraDev(i) ?: return
                if (d.address != dev.address) return
                try {
                    if (code.length != 6) return             // codice non noto: lo scrive chi abbina
                    when (i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)) {
                        BluetoothDevice.PAIRING_VARIANT_PIN, 5 -> if (d.setPin(code.toByteArray()) && isOrderedBroadcast) abortBroadcast()
                        1 -> {   // PAIRING_VARIANT_PASSKEY (vecchie versioni di Android)
                            d.javaClass.getMethod("setPasskey", Int::class.javaPrimitiveType).invoke(d, code.toInt())
                            if (isOrderedBroadcast) abortBroadcast()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        ContextCompat.registerReceiver(ctx, rxBond, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(ctx, rxPin, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1 },
            ContextCompat.RECEIVER_EXPORTED)
        // il primo collegamento subito dopo la ricerca a volte non va (errore 133 di Android): si riprova
        var c = GateConn(ctx, dev, scope, svc, rev)
        try {
            var ok = false
            for (t in 1..3) {
                if (t > 1) { c.close(); delay(1500); c = GateConn(ctx, dev, scope, svc, rev) }
                if (c.connect(12_000)) { ok = true; break }
            }
            if (!ok) return "Trovato «$advName» (${dev.address}) ma non accetta il collegamento: avvicinati e riprova."
            if (!dev.createBond()) return "Abbinamento non avviato: riprova."
            val r = withTimeoutOrNull(150_000) { bonded.await() } ?: return "Tempo scaduto: il codice non è stato scritto."
            if (r != BluetoothDevice.BOND_BONDED) return "Abbinamento rifiutato: codice sbagliato o finestra chiusa."
            delay(800)        // cifratura del collegamento appena abbinato
            // nome e pulsanti dal dispositivo, poi nell'elenco dell'app
            if (type == "gate") {
                val s = GateState.parse(c.read(GateUuids.STATE))
                store.putGate(BtGate(dev.address, s?.name?.ifBlank { null } ?: name, s?.azioni ?: emptyList()))
            } else if (type == "sensor") {
                val s = SensorState.parse(c.read(SensorUuids.STATE))
                store.putSensor(BtSensor(dev.address, s?.name?.ifBlank { null } ?: name))
                if (s != null) sensors.stati.value = sensors.stati.value + (dev.address to s)
            } else {
                val n = try { AcState.parse(JSONObject(String(c.read(Uuids.STATE) ?: ByteArray(0)))).name } catch (e: Exception) { "" }
                store.put(dev.address, n.ifBlank { name })
            }
            after?.invoke(c)?.let { notify(); return it }
        } finally {
            c.close()
            try { ctx.unregisterReceiver(rxBond) } catch (_: Exception) {}
            try { ctx.unregisterReceiver(rxPin) } catch (_: Exception) {}
        }
        notify()
        return null
    }

    private fun extraDev(i: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /**
     * Cerca il dispositivo col servizio [svc] e lo stesso nome (anche tagliato
     * nell'annuncio, dai 20 caratteri). Solo per nome: un dispositivo senza
     * rete ha il Bluetooth acceso e non va preso al suo posto. Il dispositivo
     * accende il Bluetooth qualche secondo dopo aver dato il codice: 45 s.
     */
    private suspend fun find(svc: java.util.UUID, name: String): Pair<BluetoothDevice, String>? {
        val sc = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return null
        val want = normName(name)
        val hit = CompletableDeferred<Pair<BluetoothDevice, String>>()
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                val raw = r.scanRecord?.deviceName ?: r.device.name ?: return
                val n = normName(raw)
                if (n == want || (n.length >= 20 && want.startsWith(n))) hit.complete(r.device to raw)
            }
        }
        val f = ScanFilter.Builder().setServiceUuid(ParcelUuid(svc)).build()
        val st = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { sc.startScan(listOf(f), st, cb) } catch (e: Exception) { return null }
        try {
            return withTimeoutOrNull(45_000) { hit.await() }
        } finally {
            try { sc.stopScan(cb) } catch (_: Exception) {}
        }
    }

    /** "Dimentica Bluetooth" confermato: via dall'app e dagli abbinamenti del telefono. */
    fun forget(type: String, name: String) {
        val n = normName(name)
        when (type) {
            "gate" -> store.gates.value.filter { normName(it.name) == n }.forEach { gb.forget(it.address) }
            "sensor" -> store.sensors.value.filter { normName(it.name) == n }.forEach { sensors.forget(it.address) }
            else -> store.devices.value.filter { normName(it.name) == n }.forEach { ble.forget(it.address) }
        }
        notify()
    }
}

// ── finestre ─────────────────────────────────────────────

private val ICO_CHECK = listOf(IconPart("M4 12.5l5 5L20 6.5"))
private val ICO_X = listOf(IconPart("M5 5l14 14M19 5L5 19"))

@Composable
fun BtPairDialogs(p: BtPairer) {
    val u by p.ui.collectAsState()
    val f by p.forgetAsk.collectAsState()
    f?.let { (type, name) ->
        Confirm("Dimenticare il Bluetooth di $name?",
            "Il telefono non lo userà più da vicino senza internet, finché non lo abbini di nuovo. Via relay resta tutto com'è.",
            "Dimentica", danger = true, onDismiss = { p.forgetAsk.value = null }) {
            p.forgetAsk.value = null
            p.forget(type, name)
        }
    }
    val s = u ?: return
    val end = s.done || s.err != null
    Dialog(onDismissRequest = { if (end) p.close() }, properties = DialogProperties(dismissOnClickOutside = end, dismissOnBackPress = true)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(pal.bg)
                .border(BW, pal.border, RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val red = Color(0xFFFF5A5A)
            when {
                s.done -> SvgIcon(ICO_CHECK, pal.ok, 52.dp, stroke = 2.6f)
                s.err != null -> SvgIcon(ICO_X, red, 48.dp, stroke = 2.6f)
                else -> SvgIcon(ICO_BT, pal.accent, 52.dp, stroke = 2.2f)
            }
            Text(when { s.done -> "${s.name} abbinato"; s.err != null -> "Abbinamento annullato"; else -> "Abbina ${s.name}" },
                color = pal.text, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            val body = when {
                s.done -> when (s.type) {
                    "gate" -> "Da vicino lo apri anche senza internet. Torna sul relay tra pochi secondi."
                    "sensor" -> "Da vicino leggi temperatura e umidità anche senza internet."
                    else -> "Da vicino lo comandi anche senza internet. Torna sul relay tra pochi secondi."
                }
                s.err != null -> s.err
                s.code == null -> "Chiedo il codice al dispositivo tramite il relay…"
                else -> null
            }
            if (!s.done && s.err == null && s.code != null) {
                Text(s.code.chunked(3).joinToString(" "), color = pal.accent, fontSize = 46.sp, letterSpacing = 6.sp)
                Text("Se Android chiede il codice, scrivi questo.", color = pal.value, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp))
            }
            if (body != null) Text(body, color = pal.value, fontSize = 16.sp, textAlign = TextAlign.Center)
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Codice dal dispositivo", "Ricerca in Bluetooth", "Abbinamento").forEachIndexed { i, t ->
                    val ok = s.done || i < s.step
                    val bad = s.err != null && i == s.step
                    val now = !s.done && s.err == null && i == s.step
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                            when {
                                ok -> SvgIcon(ICO_CHECK, pal.ok, 16.dp, stroke = 2.4f)
                                now -> CircularProgressIndicator(Modifier.size(16.dp), color = pal.accent, strokeWidth = 2.dp)
                                else -> Text("•", color = if (bad) red else pal.muted, fontSize = 16.sp)
                            }
                        }
                        Text(t, fontSize = 17.sp, color = when { ok -> pal.ok; bad -> red; now -> pal.text; else -> pal.muted })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                if (end) TextButton(onClick = { p.close() }) { Text("Chiudi", color = pal.accent, fontSize = 18.sp) }
                else TextButton(onClick = { p.cancel() }) { Text("Annulla", color = pal.value, fontSize = 18.sp) }
            }
        }
    }
}
