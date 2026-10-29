package me.synology.davidevinavil.casa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.util.Log
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

private const val TAG = "ClimaBle"

object Uuids {
    val SVC: UUID = UUID.fromString("5e6b0001-6c2a-4b9b-9b3e-3c1a2b4d5e01")
    val STATE: UUID = UUID.fromString("5e6b0002-6c2a-4b9b-9b3e-3c1a2b4d5e01")
    val REV: UUID = UUID.fromString("5e6b0003-6c2a-4b9b-9b3e-3c1a2b4d5e01")
    val CMD: UUID = UUID.fromString("5e6b0004-6c2a-4b9b-9b3e-3c1a2b4d5e01")
    val INFO: UUID = UUID.fromString("5e6b0005-6c2a-4b9b-9b3e-3c1a2b4d5e01")
    val SCAN: UUID = UUID.fromString("5e6b0006-6c2a-4b9b-9b3e-3c1a2b4d5e01")
    val SCHED: UUID = UUID.fromString("5e6b0007-6c2a-4b9b-9b3e-3c1a2b4d5e01")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** UNPAIRED: collegato ma il telefono non e' abbinato (o il condizionatore l'ha dimenticato). */
enum class Link { NO_PERMISSION, BT_OFF, IDLE, SEARCHING, CONNECTED, UNPAIRED }

/**
 * Collegamento al condizionatore.
 *
 * Ricollegamento automatico: l'indirizzo dell'ultimo condizionatore e' salvato.
 * Si prova prima un collegamento diretto (veloce, se e' a portata); se non
 * risponde entro 10 s si passa ad "autoConnect": Android lo cerca da solo,
 * senza limiti di tempo, e si collega appena lo sente. Se il collegamento cade
 * si riparte da capo; spegnendo il Bluetooth del telefono tutto si ferma e
 * riparte quando lo si riaccende.
 */
@SuppressLint("MissingPermission")
class Ble(private val ctx: Context, val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter: BluetoothAdapter?
        get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    val link = MutableStateFlow(Link.IDLE)
    val address = MutableStateFlow<String?>(null)
    val state = MutableStateFlow<AcState?>(null)
    val info = MutableStateFlow<AcInfo?>(null)
    val nets = MutableStateFlow<List<WifiNet>>(emptyList())
    val found = MutableStateFlow<List<Found>>(emptyList())
    val scanning = MutableStateFlow(false)
    /** Ultimo stato letto di ogni condizionatore (indirizzo -> stato e ora). */
    val snaps = MutableStateFlow(loadSnaps())

    /** Avvisi da mostrare in basso. */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var gatt: BluetoothGatt? = null
    private var autoMode = false          // tentativo in corso con autoConnect
    private var wasConnected = false
    private var wanted = false            // l'utente vuole essere collegato
    private var gen = 0
    private var chState: BluetoothGattCharacteristic? = null
    private var chCmd: BluetoothGattCharacteristic? = null
    private var chInfo: BluetoothGattCharacteristic? = null
    private var chScan: BluetoothGattCharacteristic? = null
    private var chSched: BluetoothGattCharacteristic? = null
    /** Programmazione (firmware con la caratteristica 0007); null = non disponibile. */
    val sched = MutableStateFlow<Sched?>(null)
    val hasSched get() = chSched != null
    val hasInfo get() = chInfo != null

    // Il Bluetooth fa una sola operazione alla volta: letture e scritture in coda.
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null

    // dopo un comando, per 4 s vale il valore scelto (lo stato letto puo' essere vecchio)
    private var hold = 0L
    private var local: ((AcState) -> AcState)? = null

    init {
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        closeGatt()
                        link.value = Link.BT_OFF
                    }
                    BluetoothAdapter.STATE_ON -> scope.launch {
                        link.value = Link.IDLE
                        delay(1000)           // lo stack Bluetooth ci mette un attimo
                        if (wanted) open()
                    }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    fun hasPermissions(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    val btOn: Boolean get() = adapter?.isEnabled == true

    // ── collegamento ─────────────────────────────────────

    /**
     * Schermata Bluetooth del clima aperta. Come per i cancelli il collegamento
     * c'e' solo mentre la si guarda: il condizionatore ha 2 posti e via relay
     * (Wi-Fi) il Bluetooth non serve. La imposta la barra (Nav.kt).
     */
    @Volatile var active = false

    /** Schermata aperta o app di nuovo in primo piano: ultimo condizionatore, senza toccare niente. */
    fun resume() {
        if (!active) return
        if (!hasPermissions()) { link.value = Link.NO_PERMISSION; return }
        if (link.value == Link.NO_PERMISSION) link.value = Link.IDLE
        if (!wanted) {
            store.last?.let { connectTo(it) }
            return
        }
        // in attesa da un po' (autoConnect): un tentativo diretto, piu' rapido
        if (link.value == Link.SEARCHING && autoMode) open()
        else if (link.value != Link.CONNECTED && link.value != Link.SEARCHING) open()
    }

    fun connectTo(addr: String) {
        if (address.value == addr && wanted && link.value != Link.IDLE) return
        stopScan()
        store.last = addr
        address.value = addr
        state.value = null
        info.value = null
        wasConnected = false
        wanted = true
        open()
    }

    /** Smette di collegarsi (per cambiare condizionatore o dimenticarlo). */
    fun disconnect() {
        wanted = false
        closeGatt()
        state.value = null
        info.value = null
        if (link.value != Link.BT_OFF && link.value != Link.NO_PERMISSION) link.value = Link.IDLE
    }

    /** Toglie il condizionatore: si scollega se era quello, via anche l'abbinamento del telefono. */
    fun forget(addr: String) {
        if (address.value == addr) { disconnect(); address.value = null }
        store.forget(addr)
        try {
            val d = adapter?.getRemoteDevice(addr) ?: return
            if (d.bondState != BluetoothDevice.BOND_NONE) d.javaClass.getMethod("removeBond").invoke(d)
        } catch (_: Exception) {}
    }

    private fun open() {
        val a = address.value ?: return
        if (!hasPermissions()) { link.value = Link.NO_PERMISSION; return }
        val ad = adapter
        if (ad == null || !ad.isEnabled) { link.value = Link.BT_OFF; return }
        closeGatt()
        val my = ++gen
        link.value = Link.SEARCHING
        val dev = try { ad.getRemoteDevice(a) } catch (e: Exception) { return }
        autoMode = false
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        scope.launch {
            delay(10_000)
            if (gen == my && link.value == Link.SEARCHING && !autoMode) startAuto(dev)
        }
    }

    // Android lo cerca da solo, senza timeout, e si collega appena lo sente
    private fun startAuto(dev: BluetoothDevice) {
        if (!wanted || adapter?.isEnabled != true) return
        closeGatt()
        gen++
        autoMode = true
        link.value = Link.SEARCHING
        gatt = dev.connectGatt(ctx, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        pending?.complete(null)
        chState = null; chCmd = null; chInfo = null; chScan = null
        val g = gatt ?: return
        gatt = null
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) { try { g.close() } catch (_: Exception) {}; return }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                scope.launch {
                    delay(300)
                    if (g === gatt && !g.discoverServices()) g.disconnect()
                }
                return
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            Log.d(TAG, "scollegato, status=$status auto=$autoMode")
            pending?.complete(null)
            chState = null; chCmd = null; chInfo = null; chScan = null; chSched = null
            if (!wanted || adapter?.isEnabled != true) return
            link.value = Link.SEARCHING
            val dev = g.device
            val cameFromLink = wasConnected
            wasConnected = false
            scope.launch {
                if (cameFromLink) { delay(1000); if (wanted && gatt === g) open() }   // era collegato: subito diretto
                else if (!autoMode) { if (gatt === g) startAuto(dev) }               // diretto fallito: autoConnect
                else { delay(2000); if (wanted && gatt === g) startAuto(dev) }       // errore in autoConnect: riprova
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            scope.launch { setup(g) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33)
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == Uuids.REV) scope.launch { readState(); readSched() }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == Uuids.REV) scope.launch { readState(); readSched() }
        }
    }

    /** Collegamento per cui si e' gia' svuotato l'elenco in memoria delle caratteristiche. */
    private var cacheRefreshed: BluetoothGatt? = null

    private suspend fun setup(g: BluetoothGatt) {
        val s = g.getService(Uuids.SVC)
        if (s == null) { g.disconnect(); return }
        chState = s.getCharacteristic(Uuids.STATE)
        chCmd = s.getCharacteristic(Uuids.CMD)
        chInfo = s.getCharacteristic(Uuids.INFO)      // assenti col firmware vecchio
        chScan = s.getCharacteristic(Uuids.SCAN)
        chSched = s.getCharacteristic(Uuids.SCHED)      // solo col firmware con la programmazione via Bluetooth
        // Android tiene in memoria l'elenco delle caratteristiche dei dispositivi
        // abbinati: dopo un aggiornamento del firmware che ne aggiunge (info,
        // programmazione...) continuerebbe a non vederle. Se ne manca qualcuna si
        // svuota quella memoria e si rilegge l'elenco, una volta per collegamento.
        if ((chSched == null || chInfo == null || chScan == null) && cacheRefreshed !== g) {
            cacheRefreshed = g
            val ok = try { g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false } catch (_: Exception) { false }
            Log.d(TAG, "caratteristiche mancanti: rilettura dell'elenco ($ok)")
            if (ok) { delay(500); if (g === gatt && g.discoverServices()) return }
        }
        val rev = s.getCharacteristic(Uuids.REV)
        if (chState == null || chCmd == null) { g.disconnect(); return }
        if (rev != null) notify(g, rev)
        wasConnected = true
        address.value?.let { store.put(it, store.name(it).ifBlank { g.device.name ?: "" }) }
        // Stato, comandi e info passano solo col telefono abbinato (codice a 6
        // cifre): senza abbinamento si chiede di farlo, invece di leggere e far
        // partire un abbinamento di Android che il condizionatore rifiuta.
        if (g.device.bondState != BluetoothDevice.BOND_BONDED) { link.value = Link.UNPAIRED; return }
        link.value = Link.CONNECTED
        readState()
        if (state.value == null) { delay(1500); readState() }
        // ora del telefono: senza internet il condizionatore non ce l'ha e gli orari non partirebbero
        if (chSched != null) { send("time", (System.currentTimeMillis() / 1000).toString()); readSched() }
        // abbinato qui ma dimenticato dal condizionatore ("Dimentica telefoni")
        if (state.value == null && gatt === g) link.value = Link.UNPAIRED
    }

    /**
     * Abbina il telefono al condizionatore collegato: Android chiede il codice
     * a 6 cifre mostrato dalla pagina web. null se riuscito, altrimenti il motivo.
     */
    suspend fun pair(): String? {
        val a = address.value ?: return "nessun condizionatore scelto"
        val ad = adapter?.takeIf { it.isEnabled } ?: return "accendi il Bluetooth del telefono"
        val dev = try { ad.getRemoteDevice(a) } catch (e: Exception) { return "indirizzo non valido" }
        // abbinamento rimasto solo sul telefono: si toglie e si rifa'
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {
            try { dev.javaClass.getMethod("removeBond").invoke(dev) } catch (_: Exception) {}
            delay(1500)
        }
        val done = CompletableDeferred<Int>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != a) return
                val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (s == BluetoothDevice.BOND_BONDED || s == BluetoothDevice.BOND_NONE) done.complete(s)
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        try {
            if (!dev.createBond()) return "abbinamento non avviato, riprova"
            // c'e' da scrivere il codice nella finestra di Android: tempo largo
            val r = withTimeoutOrNull(150_000) { done.await() } ?: return "tempo scaduto"
            if (r != BluetoothDevice.BOND_BONDED)
                return "abbinamento rifiutato: premi prima «Abbina telefono» nella pagina web del condizionatore e scrivi il codice giusto"
        } finally {
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
        }
        delay(800)          // cifratura del collegamento appena abbinato
        val g = gatt
        if (g != null && chState != null) {
            link.value = Link.CONNECTED
            readState()
        }
        if (state.value == null) open()      // collegamento caduto nel frattempo: da capo
        return null
    }

    // ── coda delle operazioni ────────────────────────────

    private suspend fun op(timeout: Long = 5000, start: (BluetoothGatt) -> Boolean): Any? = opLock.withLock {
        val g = gatt ?: return null
        val d = CompletableDeferred<Any?>()
        pending = d
        val ok = try { start(g) } catch (e: Exception) { false }
        val r = if (ok) withTimeoutOrNull(timeout) { d.await() } else null
        pending = null
        r
    }

    private suspend fun read(c: BluetoothGattCharacteristic): ByteArray? =
        op { it.readCharacteristic(c) } as? ByteArray

    @Suppress("DEPRECATION")
    private suspend fun write(c: BluetoothGattCharacteristic, b: ByteArray): Boolean = op { g ->
        if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(c, b, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = b; g.writeCharacteristic(c) }
    } == true

    @Suppress("DEPRECATION")
    private suspend fun notify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Uuids.CCCD) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op { gg ->
            if (Build.VERSION.SDK_INT >= 33) gg.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            else { d.value = v; gg.writeDescriptor(d) }
        }
    }

    // ── stato e comandi ──────────────────────────────────

    suspend fun readState() {
        if (link.value == Link.UNPAIRED) return
        val c = chState ?: return
        val b = read(c) ?: return
        val j = try { JSONObject(String(b)) } catch (e: Exception) { return }
        var s = AcState.parse(j)
        if (System.currentTimeMillis() < hold) local?.let { s = it(s) }
        state.value = s
        val a = address.value
        if (a != null) keepSnap(a, s)
        if (a != null && s.name.isNotBlank()) store.put(a, s.name)
        if (s.isOn) store.lastMode = s.mode
    }

    /** Invia "chiave=valore" (niente PIN: protegge l'abbinamento cifrato). [apply] cambia subito lo stato mostrato. */
    suspend fun send(k: String, v: String, apply: ((AcState) -> AcState)? = null): Boolean {
        val c = chCmd
        if (c == null) { toasts.tryEmit("Non collegato: comando non inviato"); return false }
        if (apply != null) {
            local = apply
            hold = System.currentTimeMillis() + 4000
            state.value = state.value?.let(apply)
        }
        for (p in pieces("$k=$v".toByteArray(Charsets.UTF_8))) {
            if (!write(c, p)) {
                delay(300)
                if (!write(c, p)) { toasts.tryEmit("Comando non inviato ($k)"); return false }
            }
        }
        return true
    }

    fun launch(block: suspend Ble.() -> Unit): Job = scope.launch { block(this@Ble) }

    // ── programmazione ───────────────────────────────────

    suspend fun readSched() {
        if (link.value != Link.CONNECTED) return
        val c = chSched ?: return
        val b = read(c) ?: return
        Sched.parse(String(b))?.let { sched.value = it }
    }

    /** Comandi come la pagina web (add | del i | set i campo valore), uno dopo l'altro, poi si rilegge. */
    suspend fun schedCmd(vararg cmds: String): Boolean {
        var ok = true
        for (c in cmds) if (!send("sched", c)) { ok = false; break }
        delay(250)
        readSched()
        return ok
    }

    suspend fun schedOn(on: Boolean) { send("schedon", if (on) "1" else "0"); delay(250); readSched() }

    suspend fun readInfo() {
        if (link.value != Link.CONNECTED) return
        val c = chInfo ?: return
        val b = read(c) ?: return
        try { info.value = AcInfo.parse(JSONObject(String(b))) } catch (_: Exception) {}
    }

    suspend fun readNets() {
        if (link.value != Link.CONNECTED) return
        val c = chScan ?: return
        val b = read(c) ?: return
        val l = parseNets(String(b))
        if (l.isNotEmpty()) nets.value = l
    }

    // ── lettura al volo (pagina Clima) ───────────────────

    private val peekLock = Mutex()

    /** Telefono abbinato a questo condizionatore. */
    fun isBonded(addr: String): Boolean = try {
        hasPermissions() && adapter?.getRemoteDevice(addr)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: Exception) { false }

    /**
     * Collegamento breve per leggere lo stato di un condizionatore abbinato e
     * a portata, poi si stacca. Mai con la schermata Bluetooth aperta (li'
     * c'e' gia' il collegamento vero). null se non risponde.
     */
    suspend fun peek(addr: String, timeout: Long = 6000): AcState? = peekLock.withLock {
        if (active || !hasPermissions()) return@withLock null
        val ad = adapter?.takeIf { it.isEnabled } ?: return@withLock null
        val dev = try { ad.getRemoteDevice(addr) } catch (e: Exception) { return@withLock null }
        if (dev.bondState != BluetoothDevice.BOND_BONDED) return@withLock null     // niente abbinamenti a sorpresa
        val c = GateConn(ctx, dev, scope, Uuids.SVC, Uuids.REV)
        try {
            if (!c.connect(timeout)) return@withLock null
            val b = c.read(Uuids.STATE) ?: return@withLock null
            val s = try { AcState.parse(JSONObject(String(b))) } catch (e: Exception) { return@withLock null }
            keepSnap(addr, s)
            if (s.name.isNotBlank()) store.put(addr, s.name)
            s
        } finally { c.close() }
    }

    private fun keepSnap(addr: String, s: AcState) {
        snaps.value = snaps.value + (addr to AcSnap(s.mode, s.current, s.target, System.currentTimeMillis()))
        val o = JSONObject()
        snaps.value.forEach { (k, v) ->
            o.put(k, JSONObject().put("m", v.mode).put("c", v.current ?: JSONObject.NULL).put("g", v.target ?: JSONObject.NULL).put("t", v.t))
        }
        store.acSnaps = o.toString()
    }

    private fun loadSnaps(): Map<String, AcSnap> = try {
        val o = JSONObject(store.acSnaps)
        buildMap {
            o.keys().forEach { k ->
                val v = o.getJSONObject(k)
                fun n(x: String) = if (v.isNull(x)) null else v.optDouble(x).takeIf { !it.isNaN() }
                put(k, AcSnap(v.optString("m", "off"), n("c"), n("g"), v.optLong("t")))
            }
        }
    } catch (e: Exception) { emptyMap() }

    // ── ricerca di condizionatori nuovi ──────────────────

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val a = r.device.address
            val n = r.scanRecord?.deviceName ?: r.device.name ?: "Condizionatore"
            found.value = (found.value.filter { it.address != a } + Found(a, n, r.rssi)).sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
            toasts.tryEmit("Ricerca non riuscita (errore $errorCode)")
        }
    }
    private var scanJob: Job? = null

    fun startScan() {
        if (!hasPermissions()) { link.value = Link.NO_PERMISSION; return }
        val sc = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (sc == null) { toasts.tryEmit("Accendi il Bluetooth per cercare"); return }
        stopScan()
        found.value = emptyList()
        val f = ScanFilter.Builder().setServiceUuid(ParcelUuid(Uuids.SVC)).build()
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
