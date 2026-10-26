package me.synology.davidevinavil.casa

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import java.util.Locale

/** Cosa occupa lo schermo sopra la barra. */
enum class Pane { WEB, CLIMA, SCORC }

/** Voci della barra: una per tipo di dispositivo. */
enum class Cat(val label: String) { ALL("Tutti"), DASH("Plancia"), HEAT("Termostati"), AC("Clima"), GATE("Accessi"), MORE("Altro") }

/** Voce dei pannelli: un dispositivo del relay, un condizionatore Bluetooth o una pagina. */
private class Item(
    val title: String, val sub: String, val icon: List<IconPart>, val color: Color?,
    val badge: String? = null, val selected: Boolean = false, val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(app: CasaApp, web: WebView, nav: WebNav, view: Pane, setView: (Pane) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val act = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    val store = app.store
    val ble = app.ble

    // elenco dei dispositivi del relay: subito quello salvato, poi aggiornato
    var devs by remember { mutableStateOf(Relay.parseList(store.relayList)) }
    var lastLoad by remember { mutableStateOf(0L) }
    fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastLoad < 20_000) return
        lastLoad = System.currentTimeMillis()
        val cookie = Relay.cookie()
        scope.launch {
            val raw = withContext(Dispatchers.IO) { Relay.listRaw(cookie) } ?: return@launch
            store.relayList = raw
            devs = Relay.parseList(raw)
        }
    }
    LaunchedEffect(Unit) {
        nav.onLoaded = { refresh() }
        while (true) { refresh(); delay(60_000) }
    }

    val url by nav.url.collectAsState()
    val curDev by nav.dev.collectAsState()
    val bts by store.devices.collectAsState()
    val btAddr by ble.address.collectAsState()
    val btState by ble.state.collectAsState()
    val btLink by ble.link.collectAsState()
    var pending by remember { mutableStateOf<Cat?>(null) }   // tocco appena fatto, pagina in arrivo
    var sheet by remember { mutableStateOf<Cat?>(null) }

    val pageCat = webCat(url, devs.firstOrNull { it.id == curDev })
    LaunchedEffect(url) { pending = null }
    val active = when (view) { Pane.CLIMA -> Cat.AC; Pane.SCORC -> Cat.MORE; Pane.WEB -> pending ?: pageCat }

    fun go(path: String, c: Cat) { pending = c; setView(Pane.WEB); web.loadUrl(Relay.BASE + path) }
    fun openDev(d: RelayDev) {
        nav.dev.value = d.id
        go("/_go?d=" + d.id, when (d.type) { "thermostat" -> Cat.HEAT; "ac" -> Cat.AC; "gate" -> Cat.GATE; else -> Cat.MORE })
    }
    fun openBt(a: String) { ble.connectTo(a); setView(Pane.CLIMA) }

    val heat = devs.filter { it.type == "thermostat" }
    val acs = devs.filter { it.type == "ac" }
    val gates = devs.filter { it.type == "gate" }
    val sensors = devs.filter { it.type == "sensor" }
    val others = devs.filter { it.type == "other" }
    val cats = Cat.entries.filter {
        when (it) {
            Cat.HEAT -> heat.isNotEmpty(); Cat.AC -> acs.isNotEmpty() || bts.isNotEmpty(); Cat.GATE -> gates.isNotEmpty()
            Cat.DASH -> devs.isNotEmpty(); else -> true
        }
    }

    fun tap(c: Cat) {
        when (c) {
            Cat.ALL -> go("/_home", c)
            Cat.DASH -> go("/_plancia", c)
            Cat.GATE -> go("/_gates", c)
            Cat.HEAT -> if (heat.size == 1) openDev(heat[0]) else { refresh(true); sheet = c }
            Cat.AC -> when {
                acs.size == 1 && bts.isEmpty() -> openDev(acs[0])
                acs.isEmpty() && bts.size == 1 -> openBt(bts[0].address)
                else -> { refresh(true); sheet = c }
            }
            Cat.MORE -> sheet = c
        }
    }

    // indietro: da Clima BT o Scorciatoie si torna alle pagine; dalle pagine
    // (senza altro da chiudere) l'app va in secondo piano
    BackHandler { if (view != Pane.WEB) setView(Pane.WEB) else act?.moveTaskToBack(true) }

    Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CasaWeb(web, visible = view == Pane.WEB)
            if (view != Pane.WEB) Box(Modifier.fillMaxSize().background(pal.bg)) {
                holder.SaveableStateProvider(view.name) {
                    if (view == Pane.CLIMA) App(ble, store, app.climaGo) else ShortcutsScreen(store, web)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(pal.card).navigationBarsPadding().height(62.dp)) {
            cats.forEach { c ->
                val on = c == active
                val col = if (on) pal.accent else pal.muted
                Column(Modifier.weight(1f).fillMaxSize().clickable { tap(c) }.padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    SvgIcon(catIcon(c), col, 23.dp)
                    Text(c.label, color = col, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }

    val open = sheet ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun pick(f: () -> Unit) = { sheet = null; f() }
    val items: List<Item> = when (open) {
        Cat.HEAT -> heat.map { d ->
            Item(d.name, devSub(d), Icons.flame, if (d.heat) modeColor("heat") else null,
                selected = view == Pane.WEB && pageCat == Cat.HEAT && curDev == d.id, onClick = pick { openDev(d) })
        }
        Cat.AC -> acs.map { d ->
            Item(d.name, devSub(d), Icons.cool, if (d.online && d.mode in MODES) modeColor(d.mode) else null, badge = "Wi-Fi",
                selected = view == Pane.WEB && pageCat == Cat.AC && curDev == d.id, onClick = pick { openDev(d) })
        } + bts.map { b ->
            val cur = b.address == btAddr
            val st = if (cur) btState else null
            val sub = when {
                st != null && st.isOn -> modeLabel(st.mode) + (st.target?.let { " · ${fmt(it)}°" } ?: "") + (st.current?.let { " · ora ${fmt(it)}°" } ?: "")
                st != null -> "Spento" + (st.current?.let { " · ora ${fmt(it)}°" } ?: "")
                cur && btLink == Link.SEARCHING -> "in ricerca…"
                else -> "via Bluetooth"
            }
            Item(b.name, sub, Icons.cool, if (st != null && st.isOn) modeColor(st.mode) else null, badge = "BT",
                selected = view == Pane.CLIMA && cur, onClick = pick { openBt(b.address) })
        } + Item("Aggiungi condizionatore Bluetooth", "cercalo qui vicino", Icons.bt, null,
            onClick = pick { app.climaGo.value = Scr.SCAN; setView(Pane.CLIMA) })
        else -> buildList {
            if (sensors.isNotEmpty()) add(Item("Sensori", "${sensors.size} " + if (sensors.size == 1) "sensore" else "sensori", Icons.gauge, null, onClick = pick { go("/_sensors", Cat.MORE) }))
            if (others.isNotEmpty()) add(Item("Altri dispositivi", "${others.size}", Icons.dots, null, onClick = pick { go("/_cat?t=other", Cat.MORE) }))
            add(Item("Ospiti", "inviti e permessi", Icons.users, null, onClick = pick { go("/_guests", Cat.MORE) }))
            add(Item("Scorciatoie", "cancelli dalle impostazioni rapide e dal widget", Icons.gate, null, onClick = pick { setView(Pane.SCORC) }))
            add(Item("Condizionatori Bluetooth", "elenco, aggiungi, dimentica", Icons.bt, null, onClick = pick { app.climaGo.value = Scr.DEVICES; setView(Pane.CLIMA) }))
            add(Item("Personalizza", "pagina iniziale e ordine", Icons.gear, null, onClick = pick { go("/_custom", Cat.MORE) }))
        }
    }
    ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = state, containerColor = pal.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(open.label, color = pal.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            items.forEach { SheetRow(it) }
        }
    }
}

@Composable
private fun SheetRow(it: Item) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(if (it.selected) pal.sel else pal.card)
            .border(if (it.selected) 2.dp else 1.dp, if (it.selected) pal.accent else pal.border, RoundedCornerShape(14.dp))
            .clickable(onClick = it.onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background((it.color ?: pal.muted).copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            SvgIcon(it.icon, it.color ?: pal.value, 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(it.title, color = pal.text, fontSize = 17.sp, maxLines = 1)
            Text(it.sub, color = pal.muted, fontSize = 13.sp, maxLines = 1)
        }
        it.badge?.let { b ->
            Text(b, color = if (b == "BT") pal.accent else pal.value, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, pal.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

/** Categoria della pagina aperta nella WebView. */
private fun webCat(url: String, dev: RelayDev?): Cat? {
    if (!url.startsWith(Relay.BASE)) return null
    val u = Uri.parse(url)
    return when (u.path) {
        "/_home" -> Cat.ALL
        "/_plancia" -> Cat.DASH
        "/_gates" -> Cat.GATE
        "/_cat" -> when (u.getQueryParameter("t")) { "thermostat" -> Cat.HEAT; "ac" -> Cat.AC; "gate" -> Cat.GATE; else -> Cat.MORE }
        "/_sensors", "/_guests", "/_custom" -> Cat.MORE
        "/" -> when (dev?.type) { "thermostat" -> Cat.HEAT; "ac" -> Cat.AC; "gate" -> Cat.GATE; null -> null; else -> Cat.MORE }
        else -> null
    }
}

private fun fmt(v: Double) = String.format(Locale.ITALY, if (v % 1.0 == 0.0) "%.0f" else "%.1f", v)

private fun devSub(d: RelayDev): String {
    if (!d.online) return "non in linea"
    val now = d.cur?.let { "ora ${fmt(it)}°" }
    return when (d.type) {
        "thermostat" -> listOfNotNull(if (d.heat) "Scalda" else null, d.tgt?.let { "obiettivo ${fmt(it)}°" }, now).joinToString(" · ").ifBlank { "in linea" }
        "ac" -> (if (d.mode in MODES) modeLabel(d.mode) + (d.tgt?.let { " ${fmt(it)}°" } ?: "") else "Spento") + (now?.let { " · $it" } ?: "")
        else -> listOfNotNull(now, d.hum?.let { "${fmt(it)}%" }).joinToString(" · ").ifBlank { "in linea" }
    }
}

private fun catIcon(c: Cat) = when (c) {
    Cat.ALL -> Icons.house; Cat.DASH -> Icons.dash; Cat.HEAT -> Icons.flame
    Cat.AC -> Icons.cool; Cat.GATE -> Icons.gate; Cat.MORE -> Icons.dots
}
