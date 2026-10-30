package me.synology.davidevinavil.casa

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Un sensore da cui prendere la temperatura mostrata di un condizionatore.
 * [key]: "<id>:own" (sensore integrato del termostato o ext-sensor del relay)
 * oppure "<id termostato>:u<impronta>" (sensore letto dal termostato).
 */
data class AcSrc(val key: String, val name: String, val kind: String, val sub: String, val t: Double?, val h: Double?)

/** Due decimali, con la virgola: 21,00. */
fun d2(v: Double?) = v?.let { String.format(Locale.ITALY, "%.2f", it) } ?: "--"

/**
 * Temperatura mostrata dei condizionatori: predefinita quella dello split,
 * oppure quella di un sensore (ext-sensor o termostato). Scelta salvata su
 * questo telefono; valori dalla pagina Sensori del relay (/_sensdata).
 */
class AcSources(private val store: Store) {
    val options = MutableStateFlow<List<AcSrc>>(emptyList())
    /** Scelta chiesta dalla pagina del condizionatore: id del condizionatore. */
    val pick = MutableStateFlow<String?>(null)
    /** Cambia la scelta: la pagina del condizionatore si ridisegna. */
    @Volatile var notify: () -> Unit = {}
    val choice = MutableStateFlow(load())
    @Volatile private var lastT = 0L

    private fun load(): Map<String, String> = try {
        val o = JSONObject(store.acSrcJson)
        buildMap { o.keys().forEach { put(it, o.getString(it)) } }
    } catch (e: Exception) { emptyMap() }

    fun set(acId: String, key: String?) {
        val m = choice.value.toMutableMap()
        if (key == null) m.remove(acId) else m[acId] = key
        choice.value = m
        store.acSrcJson = JSONObject(m as Map<*, *>).toString()
        notify()
    }

    /** Sensore scelto per il condizionatore, coi valori attuali (null = quello dello split). */
    fun of(acId: String): AcSrc? {
        val k = choice.value[acId] ?: return null
        return options.value.firstOrNull { it.key == k }
            ?: AcSrc(k, store.acSrcName(k), "", "", null, null)          // non (ancora) letto: "--" col suo nome
    }

    /** Da rileggere (per non avviare richieste a ogni ridisegno della pagina). */
    fun stale(ms: Long = 50_000) = System.currentTimeMillis() - lastT >= ms

    /** Rilegge i sensori dal relay (al massimo ogni [minMs]). Fuori dal thread principale. */
    fun refresh(cookie: String?, minMs: Long = 50_000) {
        val now = System.currentTimeMillis()
        if (now - lastT < minMs) return
        lastT = now
        val devs = Relay.parseList(store.relayList).filter { it.auth && (it.type == "thermostat" || it.type == "sensor") }
        val out = mutableListOf<AcSrc>()
        val seen = HashSet<String>()
        devs.sortedBy { if (it.type == "sensor") 0 else 1 }.forEach { d ->
            val j = Relay.sensData(cookie, d.id) ?: return@forEach
            val own = j.optJSONObject("own")
            fun num(o: JSONObject?, k: String) = o?.optString(k)?.toDoubleOrNull()
            if (d.type == "sensor") {
                out += AcSrc("${d.id}:own", d.name, "sensor", "ext-sensor", num(own, "t"), num(own, "h"))
                seen += d.name.trim().lowercase()
            } else {
                out += AcSrc("${d.id}:own", d.name, "thermo", "sensore integrato", num(own, "t"), num(own, "h"))
                val ss = j.optJSONArray("sensors") ?: return@forEach
                for (i in 0 until ss.length()) {
                    val s = ss.optJSONObject(i) ?: continue
                    val n = s.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Sonda ${s.optInt("n")}"
                    if (n.trim().lowercase() in seen) continue           // gia' fra gli ext-sensor del relay
                    out += AcSrc("${d.id}:u${s.optString("u")}", "${d.name} · $n", "probe",
                        if (s.optString("type") == "espnow") "sensore del termostato (ESP-NOW)" else "sensore del termostato (Wi-Fi)",
                        num(s, "t"), num(s, "h"))
                }
            }
        }
        if (out.isNotEmpty() || devs.isEmpty()) {
            options.value = out
            out.forEach { store.setAcSrcName(it.key, it.name) }
        }
        notify()
    }
}

/** Elenco per la scelta del sensore di un condizionatore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcSrcSheet(app: CasaApp, acId: String, acName: String, onDismiss: () -> Unit) {
    val src = app.acSrc
    val opts by src.options.collectAsState()
    val choice by src.choice.collectAsState()
    LaunchedEffect(Unit) { val c = Relay.cookie(); withContext(Dispatchers.IO) { src.refresh(c, 15_000) } }
    val cur = choice[acId]
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Temperatura di $acName", color = pal.muted, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            SrcRow("Condizionatore", "sensore dello split (predefinito)", "", cur == null) { src.set(acId, null); onDismiss() }
            listOf("sensor" to "SENSORI ESTERNI", "thermo" to "TERMOSTATI").forEach { (k, title) ->
                val l = opts.filter { if (k == "sensor") it.kind == "sensor" else it.kind != "sensor" }
                if (l.isEmpty()) return@forEach
                Text(title, color = pal.muted, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp))
                l.forEach { o ->
                    SrcRow(o.name, o.sub, "${d2(o.t)} °C" + (o.h?.let { " · ${d2(it)} %" } ?: ""), cur == o.key) { src.set(acId, o.key); onDismiss() }
                }
            }
            if (opts.isEmpty()) Note("Leggo i sensori dal relay…", center = true)
            Note("Vale su questo telefono, nell'elenco e nella pagina del condizionatore.", center = true)
        }
    }
}

@Composable
private fun SrcRow(t: String, sub: String, v: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (on) pal.sel else pal.card)
            .border(1.dp, if (on) pal.accent else pal.border, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t, color = pal.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = pal.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (v.isNotBlank()) Text(v, color = pal.value, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
        Box(Modifier.padding(start = 10.dp).size(18.dp).clip(RoundedCornerShape(9.dp))
            .border(if (on) 5.dp else 2.dp, if (on) pal.accent else pal.border, RoundedCornerShape(9.dp)))
    }
}
