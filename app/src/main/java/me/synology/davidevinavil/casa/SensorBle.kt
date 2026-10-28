package me.synology.davidevinavil.casa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

// Protocollo (ext-sensor/sensor_ble.h), sensore esterno:
//   servizio 8b5c0001-3a7d-4e2f-9c1b-5d4e3f2a1b70
//   0002 stato (lettura cifrata, JSON)   0003 rev (notifica)
//   0004 comando (scrittura cifrata): "modo=espnow", "modo=wifi\t<ssid>\t<pw>\t<pw relay>", "ota"
//        errori ATT: 0x80 comando gia' in coda, 0x81 sconosciuto
object SensorUuids {
    val SVC: UUID = UUID.fromString("8b5c0001-3a7d-4e2f-9c1b-5d4e3f2a1b70")
    val STATE: UUID = UUID.fromString("8b5c0002-3a7d-4e2f-9c1b-5d4e3f2a1b70")
    val REV: UUID = UUID.fromString("8b5c0003-3a7d-4e2f-9c1b-5d4e3f2a1b70")
    val CMD: UUID = UUID.fromString("8b5c0004-3a7d-4e2f-9c1b-5d4e3f2a1b70")
}

/** Sensore esterno salvato nell'app (gia' abbinato). */
data class BtSensor(val address: String, val name: String)

/**
 * Stato letto via Bluetooth. [mode]: 0 da configurare, 1 Wi-Fi, 2 Solo ESP-NOW.
 * [hooked]: agganciato al termostato via ESP-NOW, sul canale [ch].
 */
data class SensorState(
    val name: String, val t: Double?, val h: Double?, val ts: Long, val id: String, val mode: Int,
    val mac: String, val hooked: Boolean, val ch: Int, val md5: String, val ota: String, val ver: String,
    val read: Long = System.currentTimeMillis(),
) {
    companion object {
        fun parse(b: ByteArray?): SensorState? = try {
            val j = JSONObject(String(b ?: return null))
            fun n(k: String) = if (!j.has(k) || j.isNull(k)) null else j.optDouble(k).takeIf { !it.isNaN() }
            SensorState(j.optString("n"), n("t"), n("h"), j.optLong("ts"), j.optString("id").lowercase(), j.optInt("m"),
                j.optString("mac"), j.optBoolean("ag"), j.optInt("ch"), j.optString("md5"), j.optString("ota"), j.optString("v"))
        } catch (e: Exception) { null }
    }
}

/**
 * Sensori esterni via Bluetooth: solo collegamenti brevi (leggere lo stato,
 * mandare un comando), uno per volta. Abbinamento: BtPairer (BtPair.kt).
 */
@SuppressLint("MissingPermission")
class SensorBle(private val ctx: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val lock = Mutex()

    /** Ultimo stato letto di ogni sensore (indirizzo -> stato). */
    val stati = MutableStateFlow<Map<String, SensorState>>(emptyMap())
    val found = MutableStateFlow<List<Found>>(emptyList())
    val scanning = MutableStateFlow(false)

    private fun dev(addr: String): BluetoothDevice? {
        val ad = adapter?.takeIf { it.isEnabled } ?: return null
        val d = try { ad.getRemoteDevice(addr) } catch (e: Exception) { return null }
        return d.takeIf { it.bondState == BluetoothDevice.BOND_BONDED }      // niente abbinamenti a sorpresa
    }

    /** Lettura breve; null se non risponde (o non abbinato). */
    suspend fun peek(addr: String, timeout: Long = 7000): SensorState? = lock.withLock {
        val d = dev(addr) ?: return@withLock null
        val c = GateConn(ctx, d, scope, SensorUuids.SVC, SensorUuids.REV)
        try {
            if (!c.connect(timeout)) return@withLock null
            val s = SensorState.parse(c.read(SensorUuids.STATE)) ?: return@withLock null
            stati.value = stati.value + (addr to s)
            if (s.name.isNotBlank()) store.putSensor(BtSensor(addr, s.name))
            s
        } finally { c.close() }
    }

    /** Comando al sensore; null se accettato, altrimenti il motivo. */
    suspend fun cmd(addr: String, cmd: String, timeout: Long = 9000): String? = lock.withLock {
        val d = dev(addr) ?: return@withLock "sensore non abbinato o Bluetooth spento"
        val c = GateConn(ctx, d, scope, SensorUuids.SVC, SensorUuids.REV)
        try {
            if (!c.connect(timeout)) return@withLock "fuori portata"
            when (val st = c.write(SensorUuids.CMD, cmd.toByteArray(Charsets.UTF_8))) {
                0 -> null
                0x80 -> "un altro comando è in corso, riprova"
                0x81 -> "comando non riconosciuto (firmware vecchio?)"
                5, 8, 15, 0x89 -> "abbinamento perso: abbinalo di nuovo"
                -1 -> "nessuna risposta"
                else -> "non riuscito ($st)"
            }
        } finally { c.close() }
    }

    /** Toglie il sensore dall'app e l'abbinamento dal telefono. */
    fun forget(addr: String) {
        store.removeSensor(addr)
        stati.value = stati.value - addr
        try {
            val d = adapter?.getRemoteDevice(addr) ?: return
            if (d.bondState != BluetoothDevice.BOND_NONE) d.javaClass.getMethod("removeBond").invoke(d)
        } catch (_: Exception) {}
    }

    // ── ricerca (Aggiungi) ────────────────────────────────

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val a = r.device.address
            val n = r.scanRecord?.deviceName ?: r.device.name ?: "Sensore"
            found.value = (found.value.filter { it.address != a } + Found(a, n, r.rssi)).sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) { scanning.value = false }
    }
    private var scanJob: Job? = null

    fun startScan() {
        val sc = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        stopScan()
        found.value = emptyList()
        val f = ScanFilter.Builder().setServiceUuid(ParcelUuid(SensorUuids.SVC)).build()
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
