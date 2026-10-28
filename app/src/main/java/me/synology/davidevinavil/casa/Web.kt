package me.synology.davidevinavil.casa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow

// Prima degli script della pagina:
// - bt_web=1: la pagina non manda a "Installa l'app" (siamo gia' l'app);
// - navigator.share: la WebView non ce l'ha, lo passa ad Android (inviti degli ospiti);
// - la barra dei dispositivi del relay si nasconde: nell'app c'e' quella nativa
//   (nel browser e per gli ospiti resta com'e').
private const val START_JS =
    "try{localStorage.setItem('bt_web','1')}catch(e){};" +
    // La pagina del termostato ricostruisce il documento e toglie gli stili
    // aggiunti. Un MutationObserver vede la barra appena viene inserita e la
    // nasconde prima che lo schermo si ridisegni: nessun lampo. L'altezza
    // della pagina torna quella intera (senza lo spazio della barra).
    // Il relay, appena inserisce la barra, misura subito --bt-vh col suo
    // fit() (barra ancora visibile: pagina piu' corta di ~56 px) e lo rifa'
    // ogni volta che la ricrea: qui l'altezza si rimette giusta a ogni
    // controllo, sull'<html> attuale (dopo la ricostruzione e' un altro).
    "if(!window.__casaBar){window.__casaBar=1;" +
    "var fit=function(){var h=innerHeight;if(h>0){var D=document.documentElement;if(D&&D.style.getPropertyValue('--bt-vh')!==h+'px')D.style.setProperty('--bt-vh',h+'px');}};" +
    "var F=function(){var b=document.getElementById('bt-bar');if(b&&b.style.display!=='none')b.style.setProperty('display','none','important');" +
    "window.__btFit=fit;fit();};" +
    "new MutationObserver(F).observe(document,{childList:true,subtree:true});" +
    "F();addEventListener('resize',function(){window.__btFit();});setInterval(F,1000);}" +
    // Personalizza salva l'ordine della barra nel localStorage della pagina:
    // appena cambia lo si passa alla barra nativa (che e' quella che si vede).
    "if(window.CasaApp&&!Storage.prototype.__casa){Storage.prototype.__casa=1;var SI=Storage.prototype.setItem;" +
    "Storage.prototype.setItem=function(k,v){SI.apply(this,arguments);if(k==='bt_bar_order')try{CasaApp.order(String(v))}catch(e){}};}" +
    "if(window.CasaApp)navigator.share=function(d){d=d||{};CasaApp.share(d.title||'',d.url||'',d.text||'');return Promise.resolve();};"

// pagina di un dispositivo ("/"): quale, dall'ultima pagina salvata dal relay
private const val LAST_JS = "(function(){try{return localStorage.getItem('bt_last')||''}catch(e){return ''}})()"

/** Dove si trova la WebView: la barra nativa evidenzia la categoria giusta. */
class WebNav {
    val url = MutableStateFlow("")
    val dev = MutableStateFlow<String?>(null)     // dispositivo aperto (pagina "/")
    /**
     * Pagina nuova chiesta dalla barra e non ancora disegnata: la WebView
     * mostrerebbe per un attimo quella di prima (es. la home del termostato
     * aprendo un condizionatore), quindi la si copre finche' non arriva.
     */
    val busy = MutableStateFlow(false)
    /** Pagina finita di caricare: occasione per riaggiornare l'elenco. */
    var onLoaded: () -> Unit = {}

    fun note(u: String?) {
        if (u == null || !u.startsWith(Relay.BASE)) return
        Uri.parse(u).takeIf { it.path == "/_go" }?.getQueryParameter("d")?.let { dev.value = it.lowercase() }
    }
}

private const val ORDER_JS = "(function(){try{return localStorage.getItem('bt_bar_order')||''}catch(e){return ''}})()"

private const val WHO_JS = "(function(){try{return localStorage.getItem('gate_utente')||''}catch(e){return ''}})()"

/** Una sola WebView per tutta la vita dell'Activity: cambiando scheda non si ricarica. */
@SuppressLint("SetJavaScriptEnabled")
fun makeWeb(ctx: Context, store: Store, nav: WebNav): WebView = WebView(ctx).apply {
    // Altezza fissa = quella dello spazio sullo schermo. Senza questo, dentro
    // Compose la WebView e' "wrap_content" e prende l'altezza dal contenuto:
    // le pagine dei dispositivi, alte quanto la finestra, si vedono con una
    // finestra bassa e larga (impaginazione orizzontale, quadrante sparito).
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    // come Chrome: rispetta il <meta viewport> delle pagine e non ingrandisce
    // il testo con la dimensione caratteri di sistema (altrimenti le pagine
    // dei dispositivi passano all'impaginazione larga e si tagliano)
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.textZoom = 100
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(this, true)
    addJavascriptInterface(Bridge(ctx.applicationContext, store, this), "CasaApp")
    val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (early) WebViewCompat.addDocumentStartJavaScript(this, START_JS, setOf(Relay.BASE))

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val u = r.url
            if ("${u.scheme}://${u.authority}" == Relay.BASE) { nav.note(u.toString()); return false }
            // link esterni (o altre app): fuori dalla WebView
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return true
        }

        override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
            nav.note(url)
            if (!early) v.evaluateJavascript(START_JS, null)
        }

        override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
            nav.note(url)
            if (url != null) nav.url.value = url
        }

        override fun onPageFinished(v: WebView, url: String?) {
            // Copertura via a pagina caricata, e un attimo dopo: il termostato
            // ricostruisce la pagina appena carica, e togliendola alla prima
            // schermata (ancora vuota) si vedeva un lampo.
            v.postDelayed({ nav.busy.value = false }, 180)
            cm.flush()
            if (!early) {
                v.evaluateJavascript(START_JS, null)
                // senza script anticipati la prima volta puo' finire su "Installa l'app"
                if (url?.contains("/_install") == true) { v.loadUrl(Relay.BASE + "/_start"); return }
            }
            if (url != null) nav.url.value = url
            v.evaluateJavascript(START_JS, null)   // anche dopo il caricamento (gia' fatto: non si ripete)
            if (url != null && Uri.parse(url).path == "/") v.evaluateJavascript(LAST_JS) { r -> nav.note(Relay.BASE + r?.trim('"').orEmpty()) }
            nav.onLoaded()
            // ordine della barra salvato in Personalizza (anche quello di prima)
            v.evaluateJavascript(ORDER_JS) { r -> runCatching { org.json.JSONArray("[$r]").getString(0) }.getOrNull()?.let { store.setBarOrder(it) } }
            // "Apri come" della pagina: lo usano anche riquadri e widget
            v.evaluateJavascript(WHO_JS) { r ->
                val n = r?.trim('"').orEmpty()
                if (n.isNotBlank() && n != "null") store.who = n
            }
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (!r.isForMainFrame) return
            nav.busy.value = false
            v.loadDataWithBaseURL(null, offlinePage(), "text/html", "utf-8", null)
        }
    }
    webChromeClient = WebChromeClient()
    setBackgroundColor(0xFF0D0D0D.toInt())
    // La prima pagina si carica solo quando la WebView ha la sua misura vera:
    // caricata prima (0x0) la Home del termostato calcola il quadrante su
    // un'area vuota e resta impaginata male.
    loadWhenSized()
}

private fun WebView.loadWhenSized() {
    if (width > 0 && height > 0) { loadUrl(Relay.BASE + "/_start"); return }
    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
            if (r - l <= 0 || b - t <= 0) return
            v.removeOnLayoutChangeListener(this)
            // dopo il layout, non durante
            v.post { (v as WebView).loadUrl(Relay.BASE + "/_start") }
        }
    })
}

/** Da JavaScript ad Android. */
class Bridge(private val ctx: Context, private val store: Store, private val web: WebView) {
    private val gb get() = (ctx as CasaApp).gates
    private val pairer get() = (ctx as CasaApp).pairer

    init {
        // abbinato o dimenticato: la lista del relay ridisegna simboli e tasti
        pairer.notify = { web.post { web.evaluateJavascript("window.__btPairedChanged&&window.__btPairedChanged()", null) } }
    }

    /** Dispositivi abbinati in Bluetooth nell'app, per nome: {"ac":[...],"gate":[...]}. */
    @JavascriptInterface
    fun btPaired(): String = pairer.pairedJson()

    /** "Abbina Bluetooth" nella lista del relay ([type] "ac" o "gate"). */
    @JavascriptInterface
    fun btPair(id: String, type: String, name: String) {
        if (type != "ac" && type != "gate" && type != "sensor") return
        pairer.start(id, type, name, Relay.cookie())
    }

    /** "Dimentica Bluetooth": prima la conferma. */
    @JavascriptInterface
    fun btForget(type: String, name: String) {
        if (type != "ac" && type != "gate" && type != "sensor") return
        pairer.forgetAsk.value = type to name
    }

    /** Cancelli Bluetooth salvati nell'app, per la Plancia: [{addr,name,azioni,stati}]. */
    @JavascriptInterface
    fun btGates(): String {
        val st = gb.stati.value
        val a = org.json.JSONArray()
        store.gates.value.forEach { g ->
            val s = org.json.JSONObject()
            st[g.address]?.forEach { (k, v) -> s.put(k, v) }
            a.put(org.json.JSONObject().put("addr", g.address).put("name", g.name)
                .put("azioni", org.json.JSONArray(g.azioni)).put("stati", s))
        }
        return a.toString()
    }

    /** Apertura dalla Plancia; la risposta torna con window.__btDone(id, {ok, err}). */
    @JavascriptInterface
    fun btOpen(id: String, addr: String, azione: String, who: String) {
        gb.launch {
            val err = open(addr, azione, who)
            val r = org.json.JSONObject().put("ok", err == null)
            if (err != null) r.put("err", if (err == "già in corso") "gia' in corso" else err)
            val js = "window.__btDone&&window.__btDone(" + org.json.JSONObject.quote(id) + "," + r + ")"
            web.post { web.evaluateJavascript(js, null) }
        }
    }

    /** Personalizza ha salvato un nuovo ordine della barra. */
    @JavascriptInterface
    fun order(json: String) = store.setBarOrder(json)

    @JavascriptInterface
    fun share(title: String, url: String, text: String) {
        val body = listOf(text, url).filter { it.isNotBlank() }.joinToString("\n")
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body).putExtra(Intent.EXTRA_SUBJECT, title)
        ctx.startActivity(Intent.createChooser(i, title.ifBlank { null }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun offlinePage() = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{background:#0d0d0d;color:#f2f0eb;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;margin:0 24px;text-align:center}
p{color:#b3afa8;font-size:17px;line-height:1.5}button{margin-top:20px;border:0;border-radius:30px;padding:16px 40px;font-size:20px;background:#2fc0ff;color:#0d0d0d}</style></head>
<body><h2 style="font-weight:400">Relay non raggiungibile</h2><p>Controlla la connessione. Clima BT funziona anche senza rete: è nella scheda in basso.</p>
<button onclick="location.href='${Relay.BASE}/_start'">Riprova</button></body></html>"""

@Composable
fun CasaWeb(web: WebView, visible: Boolean) {
    val act = LocalContext.current as? Activity
    BackHandler(enabled = visible) { if (web.canGoBack()) web.goBack() else act?.moveTaskToBack(true) }
    // Resta sempre al suo posto, con la sua misura: sulle altre schede e' solo
    // nascosta. Cosi' la pagina non viene mai ridimensionata a zero (il
    // quadrante del termostato non si rompe) e non si ricarica.
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        (web.parent as? ViewGroup)?.removeView(web)
        web
    }, update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE })
}

fun WebView.reloadStart() = loadUrl(Relay.BASE + "/_start")
