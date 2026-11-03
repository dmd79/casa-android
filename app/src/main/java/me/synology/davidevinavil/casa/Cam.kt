package me.synology.davidevinavil.casa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Telecamere: la pagina di Frigate passata dal relay (/frigate/), in una
 * WebView sua. Cambiando scheda il live resta li' (si ferma soltanto); dopo
 * qualche minuto lontano si chiude, per non consumare dati in rete mobile.
 * Il video a schermo intero di Frigate va in orizzontale senza barre.
 */
class CamWeb(private val act: Activity) {
    private var view: WebView? = null
    private var full: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var oldOrient = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    /** Pagina caricata (live aperto o ricordato). */
    var loaded = false
        private set

    val web: WebView get() = view ?: make().also { view = it }

    fun open() {
        web.onResume()
        if (!loaded) { loaded = true; web.loadUrl(START) }
    }

    /** Scheda lasciata: si ferma il video, la pagina resta. */
    fun pause() { view?.onPause() }

    /** Lontano da un po': si chiude il live (la prossima volta si ricarica). */
    fun close() {
        if (!loaded) return
        loaded = false
        view?.loadUrl("about:blank")
        view?.clearHistory()
    }

    /** Indietro: esce dallo schermo intero, poi indietro dentro Frigate. */
    fun back(): Boolean {
        if (full != null) { hideFull(); return true }
        val w = view ?: return false
        if (w.canGoBack() && w.url?.startsWith(START) == true) {
            // non si torna sulla pagina vuota della chiusura
            val prev = w.copyBackForwardList().let { l -> l.getItemAtIndex(l.currentIndex - 1)?.url }
            if (prev != null && prev != "about:blank") { w.goBack(); return true }
        }
        return false
    }

    fun destroy() { view?.destroy(); view = null }

    private fun hideFull() {
        val v = full ?: return
        (act.window.decorView as ViewGroup).removeView(v)
        full = null
        act.requestedOrientation = oldOrient
        WindowCompat.getInsetsController(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        fullCb?.onCustomViewHidden()
        fullCb = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun make(): WebView = WebView(act).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // il live di Frigate parte da solo (senza audio finche' non lo si accende)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.textZoom = 100
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setBackgroundColor(0xFF0D0D0D.toInt())

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if ("${u.scheme}://${u.authority}" == Relay.BASE) return false
                // link esterni: fuori dall'app
                try { act.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(v: WebView, url: String?) { CookieManager.getInstance().flush() }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                loaded = false
                v.loadDataWithBaseURL(null, offline(), "text/html", "utf-8", null)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (full != null) { cb.onCustomViewHidden(); return }
                full = v; fullCb = cb
                v.setBackgroundColor(Color.BLACK)
                (act.window.decorView as ViewGroup).addView(v, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                oldOrient = act.requestedOrientation
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() = hideFull()
        }
    }

    private fun offline() = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{background:#0d0d0d;color:#f2f0eb;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;margin:0 24px;text-align:center}
p{color:#b3afa8;font-size:17px;line-height:1.5}button{margin-top:20px;border:0;border-radius:30px;padding:16px 40px;font-size:20px;background:#2fc0ff;color:#0d0d0d}</style></head>
<body><h2 style="font-weight:400">Telecamere non raggiungibili</h2><p>Controlla la connessione e che Frigate sia avviato.</p>
<button onclick="location.href='$START'">Riprova</button></body></html>"""

    companion object {
        val START = Relay.BASE + "/frigate/"
        /** Lontano dalla scheda per piu' di cosi': il live si chiude. */
        const val KEEP_MS = 120_000L
    }
}

@Composable
fun CamPane(cam: CamWeb, visible: Boolean) {
    // aperta: si carica (la prima volta) e riparte; lasciata: si ferma, e dopo
    // un po' si chiude
    LaunchedEffect(visible) {
        if (visible) cam.open()
        else if (cam.loaded) { cam.pause(); delay(CamWeb.KEEP_MS); cam.close() }
    }
    if (!visible && !cam.loaded) return
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        (cam.web.parent as? ViewGroup)?.removeView(cam.web)
        cam.web
    }, update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE })
}
