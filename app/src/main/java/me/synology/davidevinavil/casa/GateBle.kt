package me.synology.davidevinavil.casa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

// Protocollo (gate/gate_ble.h), cancelli e garage del modulo gate:
//   servizio 6a7e0001-4f2c-4e1b-9d8a-1b2c3d4e5f60
//   0002 stato (lettura cifrata, JSON)   0003 rev (notifica)
//   0004 comando (scrittura cifrata) "open|<azione>|<utente>"
//        errori ATT: 0x80 gia' in corso, 0x81 azione sconosciuta, 0x82 Bluetooth spento
//   0005 storico (lettura cifrata, come /gate/log)
// Tutto passa solo con il telefono abbinato (codice di 6 cifre, una volta).
object GateUuids {
    val SVC: UUID = UUID.fromString("6a7e0001-4f2c-4e1b-9d8a-1b2c3d4e5f60")
    val STATE: UUID = UUID.fromString("6a7e0002-4f2c-4e1b-9d8a-1b2c3d4e5f60")
    val REV: UUID = UUID.fromString("6a7e0003-4f2c-4e1b-9d8a-1b2c3d4e5f60")
    val CMD: UUID = UUID.fromString("6a7e0004-4f2c-4e1b-9d8a-1b2c3d4e5f60")
    val LOG: UUID = UUID.fromString("6a7e0005-4f2c-4e1b-9d8a-1b2c3d4e5f60")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Cancello Bluetooth salvato nell'app (gia' abbinato). */
data class BtGate(val address: String, val name: String, val azioni: List<String>)

data class GateState(val name: String, val azioni: List<String>, val stati: Map<String, String>, val t: Long) {
    companion object {
        fun parse(b: ByteArray?): GateState? = try {
            val j = JSONObject(String(b ?: return null))
            val a = j.optJSONArray("a")
            val s = j.optJSONObject("s")
            GateState(
                j.optString("n"),
                if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) },
                buildMap { s?.keys()?.forEach { k -> put(k, s.optString(k)) } },
                j.optLong("t"),
            )
        } catch (e: Exception) { null }
    }
}

/** Un'apertura dello storico (gate_log.h). */
data class GateEvent(val t: Long, val canale: Int, val azione: String, val utente: String, val bypass: Boolean) {
    val canaleNome get() = when (canale) { 1 -> "web"; 2 -> "relay"; 3 -> "pulsante"; 4 -> "Home Assistant"; 5 -> "Bluetooth"; else -> "?" }

    companion object {
        fun parse(b: ByteArray?): List<GateEvent> = try {
            val j = JSONObject(String(b ?: return emptyList()))
            val az = j.optJSONArray("azioni")
            val ev = j.optJSONArray("eventi") ?: return emptyList()
            (0 until ev.length()).mapNotNull { ev.optJSONObject(it) }.map { e ->
                val i = e.optInt("a")
                GateEvent(e.optLong("t"), e.optInt("c"), az?.optString(i)?.takeIf { it.isNotBlank() } ?: "?",
                    e.optString("u"), (e.optInt("f") and 1) != 0)
            }
        } catch (e: Exception) { emptyList() }
    }
}

/** Un collegamento GATT al cancello, con le operazioni in coda (una alla volta). */
@SuppressLint("MissingPermission")
private class GateConn(private val ctx: Context, val dev: BluetoothDevice, private val scope: CoroutineScope) {
    private var gatt: BluetoothGatt? = null
    private val lock = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    private val ready = CompletableDeferred<Boolean>()
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    @Volatile private var closed = false
    @Volatile var alive = false
        private set
    var onRev: (() -> Unit)? = null
    var onLost: (() -> Unit)? = null

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                scope.launch { delay(300); if (!closed && !g.discoverServices()) g.disconnect() }
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            alive = false
            pending?.complete(null)
            if (!ready.isCompleted) ready.complete(false) else if (!closed) onLost?.invoke()
            try { g.close() } catch (_: Exception) {}
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(GateUuids.SVC)
            if (s == null) { ready.complete(false); g.disconnect(); return }
            s.characteristics.forEach { chars[it.uuid] = it }
            alive = true
            ready.complete(true)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pending?.complete(true)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == GateUuids.REV) onRev?.invoke()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == GateUuids.REV) onRev?.invoke()
        }
    }

    /** Collega e trova il servizio; false se non risponde entro [timeout]. */
    suspend fun connect(timeout: Long): Boolean {
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(timeout) { ready.await() } ?: false
        if (!ok) { close(); return false }
        op { it.requestMtu(185) }     // stato e storico in un colpo solo
        return true
    }

    private suspend fun op(timeout: Long = 6000, start: (BluetoothGatt) -> Boolean): Any? = lock.withLock {
        val g = gatt ?: return null
        if (!alive) return null
        val d = CompletableDeferred<Any?>()
        pending = d
        val ok = try { start(g) } catch (e: Exception) { false }
        val r = if (ok) withTimeoutOrNull(timeout) { d.await() } else null
        pending = null
        r
    }

    suspend fun read(u: UUID): ByteArray? {
        val c = chars[u] ?: return null
        return op { it.readCharacteristic(c) } as? ByteArray
    }

    /** Stato ATT della scrittura (0 = riuscita), -1 senza risposta. */
    @Suppress("DEPRECATION")
    suspend fun write(u: UUID, b: ByteArray): Int {
        val c = chars[u] ?: return -1
        return op(8000) { g ->
            if (Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(c, b, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = b; g.writeCharacteristic(c) }
        } as? Int ?: -1
    }

    @Suppress("DEPRECATION")
    suspend fun notifyRev() {
        val c = chars[GateUuids.REV] ?: return
        val g = gatt ?: return
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(GateUuids.CCCD) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op { gg ->
            if (Build.VERSION.SDK_INT >= 33) gg.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            else { d.value = v; gg.writeDescriptor(d) }
        }
    }

    fun close() {
        closed = true
        alive = false
        pending?.complete(null)
        if (!ready.isCompleted) ready.complete(false)
        val g = gatt ?: return
        gatt = null
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
    }
}

/**
 * Cancelli e garage via Bluetooth: canale di riserva quando internet o il
 * Wi-Fi non ci sono. Il collegamento resta aperto solo mentre se ne guarda
 * uno ([watch]); le aperture da Plancia, riquadri e widget aprono un
 * collegamento breve. Il firmware accetta 2 telefoni insieme: niente
 * collegamenti tenuti in sottofondo.
 */
@SuppressLint("MissingPermission")
class GateBle(private val ctx: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    val found = MutableStateFlow<List<Found>>(emptyList())
    val scanning = MutableStateFlow(false)
    val link = MutableStateFlow(Link.IDLE)
    val state = MutableStateFlow<GateState?>(null)
    val log = MutableStateFlow<List<GateEvent>>(emptyList())
    /** Ultimo stato letto di ogni cancello (pannello Accessi e Plancia). */
    val stati = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    @Volatile private var conn: GateConn? = null
    @Volatile private var watched: String? = null
    private var watchJob: Job? = null
    private val tempLock = Mutex()

    fun hasPermissions(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    val btOn: Boolean get() = adapter?.isEnabled == true

    fun launch(block: suspend GateBle.() -> Unit): Job = scope.launch { block(this@GateBle) }

    // ── cancello aperto sullo schermo ─────────────────────

    fun watch(addr: String) {
        if (watched == addr && watchJob?.isActive == true) return
        unwatch()
        watched = addr
        state.value = null
        log.value = emptyList()
        watchJob = scope.launch { loop(addr) }
    }

    fun unwatch() {
        watchJob?.cancel()
        watchJob = null
        conn?.close()
        conn = null
        watched = null
        link.value = Link.IDLE
    }

    private suspend fun loop(addr: String) {
        while (true) {   // finisce con unwatch(): delay/await si interrompono
            if (!hasPermissions()) { link.value = Link.NO_PERMISSION; delay(2000); continue }
            val ad = adapter
            if (ad == null || !ad.isEnabled) { link.value = Link.BT_OFF; delay(2000); continue }
            if (link.value != Link.CONNECTED) link.value = Link.SEARCHING
            val c = GateConn(ctx, ad.getRemoteDevice(addr), scope)
            val lost = CompletableDeferred<Unit>()
            c.onLost = { lost.complete(Unit) }
            c.onRev = { scope.launch { refresh(c, addr) } }
            try {
                if (!c.connect(10_000)) { link.value = Link.SEARCHING; delay(2000); continue }
                conn = c
                c.notifyRev()
                link.value = Link.CONNECTED
                refresh(c, addr)
                lost.await()
            } finally {
                if (conn === c) conn = null
                c.close()
            }
            link.value = Link.SEARCHING
            delay(1500)
        }
    }

    private suspend fun refresh(c: GateConn, addr: String) {
        val s = GateState.parse(c.read(GateUuids.STATE)) ?: return
        keep(addr, s)
        if (watched == addr) state.value = s
        val l = GateEvent.parse(c.read(GateUuids.LOG))
        if (watched == addr) log.value = l
    }

    /** Stato letto: in memoria e, se cambiano nome o pulsanti, nell'elenco salvato. */
    private fun keep(addr: String, s: GateState) {
        stati.value = stati.value + (addr to s.stati)
        val g = store.gates.value.firstOrNull { it.address == addr } ?: return
        val n = s.name.ifBlank { g.name }
        val a = s.azioni.ifEmpty { g.azioni }
        if (n != g.name || a != g.azioni) store.putGate(BtGate(addr, n, a))
    }

    // ── apertura ──────────────────────────────────────────

    /** Apre; null se riuscito, altrimenti il motivo in breve. */
    suspend fun open(addr: String, azione: String, who: String, timeout: Long = 9000): String? {
        if (!hasPermissions()) return "permesso Bluetooth mancante"
        val ad = adapter ?: return "Bluetooth assente"
        if (!ad.isEnabled) return "Bluetooth del telefono spento"
        val dev = try { ad.getRemoteDevice(addr) } catch (e: Exception) { return "indirizzo non valido" }
        if (dev.bondState != BluetoothDevice.BOND_BONDED) return "telefono non abbinato"
        val nome = who.replace("|", " ").trim().take(15).ifBlank { "App" }
        val cmd = "open|$azione|$nome".toByteArray(Charsets.UTF_8)
        // cancello gia' collegato (schermata aperta): si usa quel collegamento
        if (watched == addr) {
            val c = withTimeoutOrNull(timeout) {
                while (conn?.alive != true) delay(200)
                conn
            } ?: return "fuori portata"
            return reason(c.write(GateUuids.CMD, cmd))
        }
        return tempLock.withLock {
            val c = GateConn(ctx, dev, scope)
            try {
                if (!c.connect(timeout)) return@withLock "fuori portata"
                val r = reason(c.write(GateUuids.CMD, cmd))
                if (r == null) GateState.parse(c.read(GateUuids.STATE))?.let { keep(addr, it) }
                r
            } finally { c.close() }
        }
    }

    private fun reason(st: Int): String? = when (st) {
        0 -> null
        0x80 -> "già in corso"
        0x81 -> "azione sconosciuta"
        0x82 -> "Bluetooth spento sul dispositivo"
        5, 8, 15, 0x89 -> "abbinamento perso: abbinalo di nuovo"
        -1 -> "nessuna risposta"
        else -> "non riuscito ($st)"
    }

    // ── abbinamento ───────────────────────────────────────

    /**
     * Abbina (Android chiede il codice a 6 cifre mostrato dal dispositivo) e
     * salva il cancello. null se riuscito, altrimenti il motivo.
     */
    suspend fun pair(addr: String, name: String): String? {
        stopScan()
        if (!hasPermissions()) return "permesso Bluetooth mancante"
        val ad = adapter?.takeIf { it.isEnabled } ?: return "accendi il Bluetooth del telefono"
        val dev = try { ad.getRemoteDevice(addr) } catch (e: Exception) { return "indirizzo non valido" }
        val bonded = CompletableDeferred<Int>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != addr) return
                val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (s == BluetoothDevice.BOND_BONDED || s == BluetoothDevice.BOND_NONE) bonded.complete(s)
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        val c = GateConn(ctx, dev, scope)
        try {
            if (!c.connect(12_000)) return "non lo trovo: avvicinati al dispositivo"
            if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                if (!dev.createBond()) return "abbinamento non avviato, riprova"
                // c'e' da scrivere il codice nella finestra di Android: tempo largo
                val r = withTimeoutOrNull(150_000) { bonded.await() } ?: return "tempo scaduto"
                if (r != BluetoothDevice.BOND_BONDED)
                    return "abbinamento rifiutato: premi prima «Abbina telefono» sulla pagina del dispositivo e scrivi il codice giusto"
                delay(800)     // cifratura del collegamento appena abbinato
            }
            val s = GateState.parse(c.read(GateUuids.STATE))
            store.putGate(BtGate(addr, s?.name?.ifBlank { null } ?: name, s?.azioni ?: emptyList()))
            s?.let { keep(addr, it) }
            return null
        } finally {
            c.close()
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
        }
    }

    /** Toglie il cancello dall'app, dalle scorciatoie che lo usavano e l'abbinamento dal telefono. */
    fun forget(addr: String) {
        if (watched == addr) unwatch()
        store.removeGate(addr)
        stati.value = stati.value - addr
        var slots = false
        for (n in 1..3) if (store.slot(n)?.dev == "bt:$addr") { store.setSlot(n, null); slots = true }
        if (slots) refreshShortcuts(ctx)
        try {
            val d = adapter?.getRemoteDevice(addr) ?: return
            d.javaClass.getMethod("removeBond").invoke(d)
        } catch (_: Exception) {}
    }

    // ── ricerca ───────────────────────────────────────────

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val a = r.device.address
            val n = r.scanRecord?.deviceName ?: r.device.name ?: "Cancello"
            found.value = (found.value.filter { it.address != a } + Found(a, n, r.rssi)).sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
            toasts.tryEmit("Ricerca non riuscita (errore $errorCode)")
        }
    }
    private var scanJob: Job? = null

    fun startScan() {
        if (!hasPermissions()) return
        val sc = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (sc == null) { toasts.tryEmit("Accendi il Bluetooth per cercare"); return }
        stopScan()
        found.value = emptyList()
        val f = ScanFilter.Builder().setServiceUuid(ParcelUuid(GateUuids.SVC)).build()
        val st = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { sc.startScan(listOf(f), st, scanCb) } catch (e: Exception) { return }
        scanning.value = true
        scanJob = scope.launch { delay(15_000); stopScan() }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        if (!scanning.value) return
        scanning.value = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }
}
