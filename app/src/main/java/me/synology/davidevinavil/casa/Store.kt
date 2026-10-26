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
