package me.synology.davidevinavil.casa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

// Prima degli script della pagina:
// - bt_web=1: la pagina non manda a "Installa l'app" (siamo gia' l'app);
// - navigator.share: la WebView non ce l'ha, lo passa ad Android (inviti degli ospiti).
private const val START_JS =
    "try{localStorage.setItem('bt_web','1')}catch(e){};" +
    "if(window.CasaApp)navigator.share=function(d){d=d||{};CasaApp.share(d.title||'',d.url||'',d.text||'');return Promise.resolve();};"

private const val WHO_JS = "(function(){try{return localStorage.getItem('gate_utente')||''}catch(e){return ''}})()"

/** Una sola WebView per tutta la vita dell'Activity: cambiando scheda non si ricarica. */
@SuppressLint("SetJavaScriptEnabled")
fun makeWeb(ctx: Context, store: Store): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(this, true)
    addJavascriptInterface(Bridge(ctx.applicationContext), "CasaApp")
    val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (early) WebViewCompat.addDocumentStartJavaScript(this, START_JS, setOf(Relay.BASE))

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val u = r.url
            if ("${u.scheme}://${u.authority}" == Relay.BASE) return false
            // link esterni (o altre app): fuori dalla WebView
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return true
        }

        override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
            if (!early) v.evaluateJavascript(START_JS, null)
        }

        override fun onPageFinished(v: WebView, url: String?) {
            cm.flush()
            if (!early) {
                v.evaluateJavascript(START_JS, null)
                // senza script anticipati la prima volta puo' finire su "Installa l'app"
                if (url?.contains("/_install") == true) { v.loadUrl(Relay.BASE + "/_start"); return }
            }
            // "Apri come" della pagina: lo usano anche riquadri e widget
            v.evaluateJavascript(WHO_JS) { r ->
                val n = r?.trim('"').orEmpty()
                if (n.isNotBlank() && n != "null") store.who = n
            }
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (!r.isForMainFrame) return
            v.loadDataWithBaseURL(null, offlinePage(), "text/html", "utf-8", null)
        }
    }
    webChromeClient = WebChromeClient()
    setBackgroundColor(0xFF0D0D0D.toInt())
    loadUrl(Relay.BASE + "/_start")
}

/** Da JavaScript ad Android. */
class Bridge(private val ctx: Context) {
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
fun CasaWeb(web: WebView) {
    val act = LocalContext.current as? Activity
    BackHandler { if (web.canGoBack()) web.goBack() else act?.moveTaskToBack(true) }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        (web.parent as? ViewGroup)?.removeView(web)
        web
    })
}

fun WebView.reloadStart() = loadUrl(Relay.BASE + "/_start")
