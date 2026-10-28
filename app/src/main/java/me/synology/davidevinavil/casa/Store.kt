package me.synology.davidevinavil.casa

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Condizionatori salvati, PIN di ciascuno e ultimo usato (resta anche chiudendo l'app). */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("clima", Context.MODE_PRIVATE)

    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<Saved>> = _devices

    var last: String?
        get() = p.getString("last", null)
        set(v) = p.edit().putString("last", v).apply()

    var lastMode: String
        get() = p.getString("lastMode", "cool") ?: "cool"
        set(v) = p.edit().putString("lastMode", v).apply()

    // ── Casa ──
    /** Nome "Apri come" degli Accessi (lo stesso della pagina del relay). */
    var who: String
        get() = p.getString("who", "") ?: ""
        set(v) = p.edit().putString("who", v.trim().take(15)).apply()

    /** Ultimo elenco dei dispositivi del relay: la barra compare subito all'avvio. */
    var relayList: String
        get() = p.getString("relayList", "[]") ?: "[]"
        set(v) = p.edit().putString("relayList", v).apply()

    /**
     * Dispositivi nuovi da far entrare nel relay ("Nuovo dispositivo"): il
     * relay li accetta solo dopo il loro primo collegamento. Codice del
     * relay, password e nome; tolti appena entrati.
     */
    private val _pending = MutableStateFlow(loadPending())
    val pending: StateFlow<List<Triple<String, String, String>>> = _pending

    fun addPending(id: String, pw: String, name: String) = savePending(_pending.value.filter { it.first != id } + Triple(id, pw, name))
    fun removePending(id: String) = savePending(_pending.value.filter { it.first != id })

    private fun savePending(l: List<Triple<String, String, String>>) {
        _pending.value = l
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.first).put("pw", it.second).put("n", it.third)) }
        p.edit().putString("pendingLogins", a.toString()).apply()
    }

    private fun loadPending(): List<Triple<String, String, String>> = try {
        val a = JSONArray(p.getString("pendingLogins", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Triple(o.getString("id"), o.getString("pw"), o.optString("n")) }
    } catch (e: Exception) { emptyList() }

    /** Ordine della barra scelto in Personalizza (chiavi del relay: all, dash, thermostat...). */
    private val _barOrder = MutableStateFlow(parseOrder(p.getString("barOrder", "[]")))
    val barOrder: StateFlow<List<String>> = _barOrder

    fun setBarOrder(json: String?) {
        val o = parseOrder(json)
        if (o.isEmpty() || o == _barOrder.value) return
        _barOrder.value = o
        p.edit().putString("barOrder", JSONArray(o).toString()).apply()
    }

    private fun parseOrder(json: String?): List<String> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    /** Cancelli e garage Bluetooth abbinati (indirizzo, nome, pulsanti). */
    /** Indirizzo degli aggiornamenti scelto nell'app per un dispositivo (vuoto = quello del dispositivo). */
    fun fwUrl(address: String): String = p.getString("fwUrl:$address", "") ?: ""
    fun setFwUrl(address: String, url: String) = p.edit().putString("fwUrl:$address", url.trim()).apply()

    /** Sensori esterni abbinati in Bluetooth. */
    private val _sensors = MutableStateFlow(loadSensors())
    val sensors: StateFlow<List<BtSensor>> = _sensors

    /** Nuovo o nome cambiato (non riscrive se uguale). */
    fun putSensor(s: BtSensor) {
        val cur = _sensors.value
        val old = cur.firstOrNull { it.address == s.address }
        if (old == s) return
        saveSensors(if (old != null) cur.map { if (it.address == s.address) s else it } else cur + s)
    }

    fun removeSensor(address: String) = saveSensors(_sensors.value.filter { it.address != address })

    private fun saveSensors(list: List<BtSensor>) {
        _sensors.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("a", it.address).put("n", it.name)) }
        p.edit().putString("btSensors", a.toString()).apply()
    }

    private fun loadSensors(): List<BtSensor> = try {
        val a = JSONArray(p.getString("btSensors", "[]"))
        (0 until a.length()).map { i -> val o = a.getJSONObject(i); BtSensor(o.getString("a"), o.optString("n")) }
    } catch (e: Exception) { emptyList() }

    private val _gates = MutableStateFlow(loadGates())
    val gates: StateFlow<List<BtGate>> = _gates

    fun putGate(g: BtGate) {
        val cur = _gates.value
        saveGates(if (cur.any { it.address == g.address }) cur.map { if (it.address == g.address) g else it } else cur + g)
    }

    fun removeGate(address: String) = saveGates(_gates.value.filter { it.address != address })

    private fun saveGates(list: List<BtGate>) {
        _gates.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("a", it.address).put("n", it.name).put("z", JSONArray(it.azioni))) }
        p.edit().putString("btGates", a.toString()).apply()
    }

    private fun loadGates(): List<BtGate> = try {
        val a = JSONArray(p.getString("btGates", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val z = o.optJSONArray("z")
            BtGate(o.getString("a"), o.optString("n"), if (z == null) emptyList() else (0 until z.length()).map { z.getString(it) })
        }
    } catch (e: Exception) { emptyList() }

    /** Ultimo elenco dei cancelli del relay (senza rete la scheda Accessi li mostra lo stesso). */
    var relayGates: String
        get() = p.getString("relayGates", "[]") ?: "[]"
        set(v) = p.edit().putString("relayGates", v).apply()

    /**
     * Com'era il relay l'ultima volta: Accessi e Clima ripartono da qui
     * (raggiungibile: Wi-Fi subito; no: Bluetooth subito finche' non risponde).
     */
    var relayUp: Boolean
        get() = p.getBoolean("relayUp", true)
        set(v) = p.edit().putBoolean("relayUp", v).apply()

    /** Ultimo stato letto via Bluetooth di ogni condizionatore (pagina Clima). */
    var acSnaps: String
        get() = p.getString("acSnaps", "{}") ?: "{}"
        set(v) = p.edit().putString("acSnaps", v).apply()

    var tab: String
        get() = p.getString("tab", "CASA") ?: "CASA"
        set(v) = p.edit().putString("tab", v).apply()

    /** Pulsante degli Accessi assegnato a riquadro/widget n (1..3). */
    fun slot(n: Int): GateBtn? = try {
        p.getString("slot$n", null)?.let { JSONObject(it).let { o -> GateBtn(o.getString("d"), o.optString("n"), o.getString("a")) } }
    } catch (e: Exception) { null }

    fun setSlot(n: Int, b: GateBtn?) {
        if (b == null) p.edit().remove("slot$n").apply()
        else p.edit().putString("slot$n", JSONObject().put("d", b.dev).put("n", b.devName).put("a", b.azione).toString()).apply()
    }

    fun pin(address: String): String = p.getString("pin:$address", "") ?: ""
    fun setPin(address: String, pin: String) = p.edit().putString("pin:$address", pin).apply()

    fun name(address: String?): String =
        _devices.value.firstOrNull { it.address == address }?.name ?: ""

    /** Aggiunge o rinomina; l'ultimo usato va in cima. */
    fun put(address: String, name: String) {
        val cur = _devices.value
        val old = cur.firstOrNull { it.address == address }
        val nm = name.ifBlank { old?.name ?: "Condizionatore" }
        if (old != null && old.name == nm) return
        val list = if (old != null) cur.map { if (it.address == address) Saved(address, nm) else it }
                   else cur + Saved(address, nm)
        save(list)
    }

    fun forget(address: String) {
        save(_devices.value.filter { it.address != address })
        p.edit().remove("pin:$address").apply()
        if (last == address) last = _devices.value.firstOrNull()?.address
    }

    private fun save(list: List<Saved>) {
        _devices.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("a", it.address).put("n", it.name)) }
        p.edit().putString("devices", a.toString()).apply()
    }

    private fun load(): List<Saved> = try {
        val a = JSONArray(p.getString("devices", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Saved(o.getString("a"), o.optString("n")) } }
    } catch (e: Exception) { emptyList() }
}
