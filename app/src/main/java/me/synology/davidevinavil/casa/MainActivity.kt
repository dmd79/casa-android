package me.synology.davidevinavil.casa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab(val label: String) { CASA("Casa"), CLIMA("Clima BT"), SCORC("Scorciatoie") }

class MainActivity : ComponentActivity() {
    private val app get() = application as CasaApp
    private lateinit var web: WebView
    private val tab = MutableStateFlow(Tab.CASA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tab.value = runCatching { Tab.valueOf(intent.getStringExtra("tab") ?: app.store.tab) }.getOrDefault(Tab.CASA)
        web = makeWeb(this, app.store)
        setContent {
            ClimaTheme {
                val t by tab.collectAsState()
                Root(app, web, t) { tab.value = it; app.store.tab = it.name }
            }
        }
    }

    // da riquadro o widget: apre la scheda richiesta
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { n -> runCatching { Tab.valueOf(n) }.getOrNull()?.let { tab.value = it } }
    }

    override fun onStart() {
        super.onStart()
        app.ble.resume()   // tornando in primo piano: ricollega subito il clima se serve
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

@Composable
fun Root(app: CasaApp, web: WebView, tab: Tab, onTab: (Tab) -> Unit) {
    // ogni scheda ricorda dov'era (Clima BT: impostazioni, elenco...) anche cambiando scheda
    val holder = rememberSaveableStateHolder()
    val act = LocalContext.current as? Activity
    // indietro senza altro da chiudere: l'app va in secondo piano (non si chiude,
    // cosi' la pagina del relay e il collegamento al clima restano pronti)
    BackHandler { act?.moveTaskToBack(true) }
    var diag by remember { mutableStateOf<String?>(null) }
    diag?.let { d ->
        AlertDialog(onDismissRequest = { diag = null }, containerColor = pal.bg,
            title = { Text("Misure della pagina", color = pal.text) },
            text = { SelectionContainer { Text(d, color = pal.value, fontSize = 15.sp) } },
            confirmButton = { TextButton(onClick = { diag = null }) { Text("Chiudi", color = pal.accent) } })
    }
    Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CasaWeb(web, visible = tab == Tab.CASA)
            if (tab != Tab.CASA) Box(Modifier.fillMaxSize().background(pal.bg)) {
                holder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        Tab.CLIMA -> App(app.ble, app.store)
                        else -> ShortcutsScreen(app.store, web)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(pal.card).navigationBarsPadding().height(64.dp)) {
            Tab.entries.forEach { t ->
                val on = t == tab
                val col = if (on) pal.accent else pal.muted
                Column(Modifier.weight(1f).fillMaxSize().pointerInput(t, on) {
                        detectTapGestures(
                            // di nuovo su Casa: torna alla pagina iniziale del relay
                            onTap = { if (on && t == Tab.CASA) web.reloadStart() else onTab(t) },
                            // pressione lunga su Casa: misure della pagina, per capire problemi di impaginazione
                            onLongPress = { if (t == Tab.CASA) web.evaluateJavascript(DIAG_JS) { r -> diag = r?.trim('"') } },
                        )
                    }.padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    SvgIcon(when (t) { Tab.CASA -> Icons.house; Tab.CLIMA -> Icons.cool; Tab.SCORC -> Icons.gate }, col, 24.dp)
                    Text(t.label, color = col, fontSize = 12.sp)
                }
            }
        }
    }
}
