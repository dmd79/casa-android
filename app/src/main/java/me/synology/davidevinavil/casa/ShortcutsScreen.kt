package me.synology.davidevinavil.casa

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ShortcutsScreen(store: Store, web: WebView) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var who by remember { mutableStateOf(store.who) }
    var rev by remember { mutableIntStateOf(0) }          // ridisegna dopo una scelta
    var pick by remember { mutableIntStateOf(0) }         // riquadro in scelta (0 = nessuno)
    var gates by remember { mutableStateOf<List<GateBtn>?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        val cookie = Relay.cookie()
        scope.launch {
            gates = withContext(Dispatchers.IO) { try { Relay.gates(cookie) } catch (e: Exception) { emptyList() } }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
        Title("Scorciatoie")
        Sub("aprire cancelli e garage senza aprire l'app")

        Section("APRI COME")
        OutlinedTextField(
            value = who, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            placeholder = { Text("il tuo nome") },
            onValueChange = { v ->
                who = v.take(15)
                store.who = who
                // lo stesso nome nelle pagine del relay
                web.evaluateJavascript("try{localStorage.setItem('gate_utente'," + JSONObject.quote(store.who) + ")}catch(e){}", null)
            },
        )
        Note("Compare nello storico delle aperture, come «Apri come» nella pagina Accessi.")

        Section("ACCESSI RAPIDI")
        key(rev) {
            for (n in 1..3) {
                val s = store.slot(n)
                RowCard("Accesso $n", s?.let { it.label + " · " + it.devName + if (it.dev.startsWith("bt:")) " (BT)" else "" } ?: "nessuno") {
                    pick = n
                    if (gates == null && !loading) load()
                }
            }
        }
        Note("Lo stesso pulsante vale per il riquadro n delle impostazioni rapide e per il widget. Se il relay non si raggiunge e il cancello è abbinato via Bluetooth, apre via Bluetooth.")

        Section("AGGIUNGI")
        RowCard("Riquadri nelle impostazioni rapide") { addTiles(ctx) }
        RowCard("Widget nella schermata Home") { addWidget(ctx) }
        Note("Dal riquadro, a telefono bloccato, Android chiede prima di sbloccarlo. Le aperture usano l'accesso fatto nella scheda Casa: se un giorno chiedono di entrare, rientra lì con la password.")
        Spacer(Modifier.height(20.dp))
    }

    if (pick > 0) {
        val n = pick
        AlertDialog(
            onDismissRequest = { pick = 0 }, containerColor = pal.bg,
            title = { Text("Accesso $n", color = pal.text, fontSize = 21.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val l = gates
                    // via Bluetooth solo i cancelli che il relay non ha: per gli altri
                    // il Bluetooth entra da solo quando il relay non si raggiunge
                    val bt = btGateBtns(store).filter { b ->
                        l == null || l.none { it.devName.trim().equals(b.devName.trim(), ignoreCase = true) && it.azione == b.azione }
                    }
                    bt.forEach { b ->
                        Choice(b.label, b.devName + " · Bluetooth") { store.setSlot(n, b); refreshShortcuts(ctx); rev++; pick = 0 }
                    }
                    when {
                        loading || l == null -> Text("Leggo gli accessi dal relay…", color = pal.value)
                        l.isEmpty() && bt.isEmpty() -> Text("Nessun accesso trovato. Nella scheda Casa entra nei dispositivi dei cancelli con la loro password, poi riprova.", color = pal.value)
                        else -> l.forEach { b ->
                            Choice(b.label, b.devName + " · relay") { store.setSlot(n, b); refreshShortcuts(ctx); rev++; pick = 0 }
                        }
                    }
                    if (store.slot(n) != null) Choice("Nessuno", "togli la scorciatoia") { store.setSlot(n, null); refreshShortcuts(ctx); rev++; pick = 0 }
                }
            },
            confirmButton = { TextButton(onClick = { load() }) { Text("Aggiorna", color = pal.accent) } },
            dismissButton = { TextButton(onClick = { pick = 0 }) { Text("Chiudi", color = pal.value) } },
        )
    }
}

@Composable
private fun Choice(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(pal.card)
        .border(1.dp, pal.border, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = pal.text, fontSize = 18.sp)
        Text(sub, color = pal.muted, fontSize = 14.sp)
    }
}

private fun addTiles(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        val sbm = ctx.getSystemService(StatusBarManager::class.java)
        // uno alla volta: Android mostra una richiesta per ogni riquadro
        val tiles = listOf(GateTile1::class.java to 1, GateTile2::class.java to 2, GateTile3::class.java to 3)
            .filter { (ctx.applicationContext as CasaApp).store.slot(it.second) != null }
        if (tiles.isEmpty()) { Toast.makeText(ctx, "Scegli prima almeno un accesso", Toast.LENGTH_SHORT).show(); return }
        fun ask(i: Int) {
            if (i >= tiles.size) return
            val (cls, n) = tiles[i]
            val label = (ctx.applicationContext as CasaApp).store.slot(n)?.label ?: "Accesso $n"
            sbm.requestAddTileService(ComponentName(ctx, cls), label, Icon.createWithResource(ctx, R.drawable.ic_gate),
                ctx.mainExecutor) { ask(i + 1) }
        }
        ask(0)
    } else {
        Toast.makeText(ctx, "Apri le impostazioni rapide, tocca la matita e trascina i riquadri «Accesso»", Toast.LENGTH_LONG).show()
    }
}

private fun addWidget(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, GateWidget::class.java), null, null)
    else Toast.makeText(ctx, "Tieni premuto sulla schermata Home → Widget → Casa", Toast.LENGTH_LONG).show()
}
