package me.synology.davidevinavil.casa

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberUpdatedState
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
enum class Pane { WEB, CLIMA, SCORC, GATE, ACC, CLIMI, NEW, SENS }

/** Voci della barra: una per tipo di dispositivo. */
enum class Cat(val label: String) { ALL("Tutti"), DASH("Plancia"), HEAT("Termostati"), AC("Clima"), GATE("Accessi"), SENS("Sensori"), MORE("Altro") }

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
    val gb = app.gates

    // elenco dei dispositivi del relay: subito quello salvato, poi aggiornato
    var devs by remember { mutableStateOf(Relay.parseList(store.relayList)) }
    var lastLoad by remember { mutableStateOf(0L) }
    fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastLoad < 20_000) return
        lastLoad = System.currentTimeMillis()
        val cookie = Relay.cookie()
        scope.launch {
            val raw = withContext(Dispatchers.IO) { Relay.listRaw(cookie) }
            if (raw == null) return@launch
            store.relayList = raw
            devs = Relay.parseList(raw)
        }
    }
    // Bluetooth del clima solo mentre se ne guarda la schermata (come i cancelli)
    LaunchedEffect(view) {
        ble.active = view == Pane.CLIMA
        if (view == Pane.CLIMA) ble.resume() else ble.disconnect()
    }
    // Dispositivi nuovi non ancora entrati nel relay ("Nuovo dispositivo"):
    // si riprova finche' il relay non li accetta (dopo il loro primo collegamento)
    val pendingLogins by store.pending.collectAsState()
    val viewNow by rememberUpdatedState(view)
    LaunchedEffect(pendingLogins.isNotEmpty()) {
        while (store.pending.value.isNotEmpty()) {
            for ((id, pw, _) in store.pending.value) {
                when (withContext(Dispatchers.IO) { Relay.login(id, pw) }) {
                    // la lista "Tutti" si ricarica solo se e' quella sullo schermo e
                    // ferma: ricaricarla mentre si apriva un'altra pagina (per
                    // esempio un sensore dalla scheda Sensori) annullava l'apertura
                    // e si finiva su "Tutti"
                    Relay.Login.OK -> {
                        store.removePending(id); refresh(true)
                        if (viewNow == Pane.WEB && !nav.loading && !nav.busy.value && Uri.parse(nav.url.value).path == "/_home") web.reload()
                    }
                    Relay.Login.BAD -> store.removePending(id)       // password diversa: si entra a mano
                    else -> {}
                }
            }
            delay(30_000)
        }
    }
    LaunchedEffect(Unit) {
        nav.onLoaded = { refresh() }
        while (true) { refresh(); delay(60_000) }
    }

    val url by nav.url.collectAsState()
    val curDev by nav.dev.collectAsState()
    val bts by store.devices.collectAsState()
    val btGates by store.gates.collectAsState()
    val btSensors by store.sensors.collectAsState()
    val savedOrder by store.barOrder.collectAsState()
    val ord = barOrder(savedOrder)
    val btAddr by ble.address.collectAsState()
    val btState by ble.state.collectAsState()
    val btLink by ble.link.collectAsState()
    var pending by remember { mutableStateOf<Cat?>(null) }   // tocco appena fatto, pagina in arrivo
    var sheet by remember { mutableStateOf<Cat?>(null) }

    val pageCat = webCat(url, devs.firstOrNull { it.id == curDev })
    LaunchedEffect(url) { pending = null }
    val active = when (view) { Pane.CLIMA, Pane.CLIMI -> Cat.AC; Pane.SCORC, Pane.NEW -> Cat.MORE; Pane.GATE, Pane.ACC -> Cat.GATE; Pane.SENS -> Cat.SENS; Pane.WEB -> pending ?: pageCat }

    // Copertura solo arrivando da una schermata dell'app (Clima, Accessi...):
    // sotto c'e' rimasta un'altra pagina, che si vedrebbe per un attimo. Da
    // pagina a pagina invece resta quella dove si era, come in un browser.
    fun go(path: String, c: Cat) {
        pending = c
        if (view != Pane.WEB) nav.busy.value = true
        setView(Pane.WEB)
        web.loadUrl(Relay.BASE + path)
    }
    val busy by nav.busy.collectAsState()
    // copertura al massimo 5 s (rete lenta o pagina che non arriva)
    LaunchedEffect(busy) { if (busy) { delay(5000); nav.busy.value = false } }
    fun openDev(d: RelayDev) {
        // accesso scaduto: pagina di accesso con la password
        if (!d.auth) { go("/_login?id=" + d.id, Cat.ALL); return }
        nav.dev.value = d.id
        go("/_go?d=" + d.id, when (d.type) { "thermostat" -> Cat.HEAT; "ac" -> Cat.AC; "gate" -> Cat.GATE; "sensor" -> Cat.SENS; else -> Cat.MORE })
    }
    fun openBt(a: String) { ble.connectTo(a); setView(Pane.CLIMA) }
    fun openGate(r: String) { app.gateGo.value = r; setView(Pane.GATE) }
    // "Apri come": lo stesso nome nell'app e nelle pagine del relay
    fun setWho(n: String) {
        store.who = n
        web.evaluateJavascript("try{localStorage.setItem('gate_utente'," + org.json.JSONObject.quote(store.who) + ")}catch(e){}", null)
    }

    val heat = devs.filter { it.type == "thermostat" }
    val acs = devs.filter { it.type == "ac" }
    val gates = devs.filter { it.type == "gate" }
    val sensors = devs.filter { it.type == "sensor" }
    val others = devs.filter { it.type == "other" }
    val cats = Cat.entries.filter {
        when (it) {
            Cat.HEAT -> heat.isNotEmpty(); Cat.AC -> acs.isNotEmpty() || bts.isNotEmpty(); Cat.GATE -> gates.isNotEmpty() || btGates.isNotEmpty(); Cat.SENS -> sensors.isNotEmpty() || btSensors.isNotEmpty()
            Cat.DASH -> devs.isNotEmpty(); else -> true
        }
    }.sortedBy { c -> CAT_KEY[c]?.let { ord.indexOf(it) } ?: Int.MAX_VALUE }   // Altro resta in fondo

    fun tap(c: Cat) {
        when (c) {
            Cat.ALL -> go("/_home", c)
            Cat.DASH -> go("/_plancia", c)
            Cat.GATE -> setView(Pane.ACC)        // subito i pulsanti, relay o Bluetooth
            Cat.SENS -> setView(Pane.SENS)       // relay o Bluetooth; grafici nella pagina web
            Cat.HEAT -> if (heat.size == 1) openDev(heat[0]) else { refresh(true); sheet = c }
            Cat.AC -> setView(Pane.CLIMI)        // tutti i condizionatori, relay o Bluetooth
            Cat.MORE -> sheet = c
        }
    }

    // indietro: da Clima BT o Scorciatoie si torna alle pagine; dalle pagine
    // (senza altro da chiudere) l'app va in secondo piano
    BackHandler {
        when (view) {
            Pane.WEB -> act?.moveTaskToBack(true)
            Pane.GATE -> setView(Pane.ACC)      // dai cancelli Bluetooth alla scheda Accessi
            Pane.CLIMA -> setView(Pane.CLIMI)   // dal condizionatore Bluetooth alla pagina Clima
            else -> setView(Pane.WEB)
        }
    }

    Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CasaWeb(web, visible = view == Pane.WEB)
            // pagina nuova in arrivo: niente lampo di quella di prima
            PageCover(view == Pane.WEB && busy)
            if (view != Pane.WEB) Box(Modifier.fillMaxSize().background(pal.bg)) {
                holder.SaveableStateProvider(view.name) {
                    when (view) {
                        Pane.CLIMA -> App(ble, store, app.climaGo)
                        Pane.GATE -> GateApp(gb, store, app.gateGo, ::setWho)
                        Pane.CLIMI -> ClimaScreen(app, openRelay = { openDev(it) }, openBt = { openBt(it) },
                            onList = { app.climaGo.value = Scr.DEVICES; setView(Pane.CLIMA) })
                        Pane.ACC -> AccessScreen(app, ::setWho, onHistory = { go("/_gates", Cat.GATE) }, onBt = { openGate("list") },
                            onRelogin = { id -> go("/_login?id=" + id, Cat.ALL) })
                        Pane.NEW -> SetupScreen(app, onClose = { refresh(true); go("/_home", Cat.ALL) })
                        Pane.SENS -> SensorsApp(app, app.sensorGo, openRelay = { openDev(it) }, onCharts = { go("/_sensors", Cat.SENS) })
                        else -> ShortcutsScreen(store, web)
                    }
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
                    Text(c.label, color = col, fontSize = if (cats.size > 6) 10.sp else 11.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }

    // "Abbina / Dimentica Bluetooth" chiesti dalla lista del relay
    BtPairDialogs(app.pairer)
    // scelta del sensore per la temperatura, chiesta dalla pagina del condizionatore
    val acPick by app.acSrc.pick.collectAsState()
    acPick?.let { id -> AcSrcSheet(app, id, devs.firstOrNull { it.id == id }?.name ?: "condizionatore") { app.acSrc.pick.value = null } }

    val open = sheet ?: return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun pick(f: () -> Unit) = { sheet = null; f() }
    val items: List<Item> = when (open) {
        Cat.HEAT -> heat.map { d ->
            Item(d.name, devSub(d), Icons.flame, if (d.heat) modeColor("heat") else null,
                selected = view == Pane.WEB && pageCat == Cat.HEAT && curDev == d.id, onClick = pick { openDev(d) })
        }
        else -> buildList {
            // le voci del relay nell'ordine di Personalizza, poi quelle dell'app
            val rel: List<Pair<String, Item>> = listOfNotNull(
                if (others.isNotEmpty()) "other" to Item("Altri dispositivi", "${others.size}", Icons.dots, null, onClick = pick { go("/_cat?t=other", Cat.MORE) }) else null,
                "guests" to Item("Ospiti", "inviti e permessi", Icons.users, null, onClick = pick { go("/_guests", Cat.MORE) }),
            )
            rel.sortedBy { ord.indexOf(it.first) }.forEach { add(it.second) }
            add(Item("Nuovo dispositivo", "Wi-Fi, relay e Bluetooth dall'hotspot", Icons.plus, null, onClick = pick { setView(Pane.NEW) }))
            add(Item("Scorciatoie", "cancelli dalle impostazioni rapide e dal widget", Icons.gate, null, onClick = pick { setView(Pane.SCORC) }))
            add(Item("Gate Bluetooth", "elenco, aggiungi, dimentica", Icons.bt, null, onClick = pick { openGate("list") }))
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
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(R_CARD))
            .background(if (it.selected) pal.sel else pal.card)
            .border(BW, if (it.selected) pal.accent else pal.border, RoundedCornerShape(R_CARD))
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
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(BW, pal.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

/**
 * Copertura sopra la WebView mentre arriva la pagina nuova: stesso nero delle
 * pagine dei dispositivi, se ne va in dissolvenza. Funzione a parte: dentro la
 * colonna della barra AnimatedVisibility prenderebbe la versione della colonna
 * (non chiamabile da li').
 */
@Composable
private fun PageCover(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = EnterTransition.None, exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0F)))
    }
}

/** Chiavi delle voci in Personalizza (relay). */
private val DEF_ORDER = listOf("all", "dash", "thermostat", "ac", "gate", "other", "sensors", "guests")
private val CAT_KEY = mapOf(Cat.ALL to "all", Cat.DASH to "dash", Cat.HEAT to "thermostat", Cat.AC to "ac", Cat.GATE to "gate", Cat.SENS to "sensors")

/** Ordine completo come lo calcola il relay: le voci note salvate, Plancia dopo Tutti se manca, poi le altre. */
private fun barOrder(saved: List<String>): List<String> {
    val o = saved.filter { it in DEF_ORDER }.distinct().toMutableList()
    if (o.isNotEmpty() && "dash" !in o) o.add(o.indexOf("all") + 1, "dash")
    DEF_ORDER.forEach { if (it !in o) o.add(it) }
    return o
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
        "/_sensors" -> Cat.SENS
        "/_guests", "/_custom" -> Cat.MORE
        "/" -> when (dev?.type) { "thermostat" -> Cat.HEAT; "ac" -> Cat.AC; "gate" -> Cat.GATE; "sensor" -> Cat.SENS; null -> null; else -> Cat.MORE }
        else -> null
    }
}

private fun fmt(v: Double) = String.format(Locale.ITALY, if (v % 1.0 == 0.0) "%.0f" else "%.1f", v)

private fun devSub(d: RelayDev): String {
    if (!d.auth) return "accedi di nuovo"
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
    Cat.AC -> Icons.cool; Cat.GATE -> Icons.gate; Cat.SENS -> Icons.gauge; Cat.MORE -> Icons.dots
}
