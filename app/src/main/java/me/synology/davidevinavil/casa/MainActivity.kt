package me.synology.davidevinavil.casa

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val app get() = application as CasaApp
    private lateinit var web: WebView
    private lateinit var cam: CamWeb
    private val nav = WebNav()
    private val pane = MutableStateFlow(Pane.WEB)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // barre di sistema come il tema del telefono (icone scure sul chiaro)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        handle(intent)
        web = makeWeb(this, app.store, nav)
        cam = CamWeb(this)
        setContent {
            ClimaTheme {
                val p by pane.collectAsState()
                Root(app, web, cam, nav, p) { pane.value = it }
                DiagOverlay()   // DIAGNOSI (provvisoria)
            }
        }
    }

    // DIAGNOSI (provvisoria): tocchi e tasti che arrivano all'app
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Diag.touch("A", ev)
        return super.dispatchTouchEvent(ev)
    }
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_BACK) Diag.log("K back " + (if (ev.action == KeyEvent.ACTION_DOWN) "giu" else "su"))
        return super.dispatchKeyEvent(ev)
    }

    // da riquadro o widget non configurati: apre le Scorciatoie
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.getStringExtra("tab") == "SCORC") pane.value = Pane.SCORC
        // QR del dispositivo: login con il codice gia' scritto, o la sua
        // pagina se e' gia' fra i dispositivi (lo decide il relay)
        linkPath(i)?.let { p ->
            pane.value = Pane.WEB
            if (::web.isInitialized) web.loadUrl(Relay.BASE + p) else nav.startPath = p
        }
    }

    /** Link del relay aperto da fuori: solo l'indirizzo di un dispositivo (/<codice>). */
    private fun linkPath(i: Intent?): String? {
        if (i?.action != Intent.ACTION_VIEW) return null
        val u = i.data ?: return null
        val base = Uri.parse(Relay.BASE)
        if (u.scheme != "https" || u.host != base.host || u.port != base.port) return null
        val code = u.path?.trim('/')?.lowercase() ?: return null
        return if (Regex("[a-z0-9]{8}").matches(code)) "/$code" else null
    }

    override fun onStart() {
        super.onStart()
        app.fg = true
        app.ble.resume()   // tornando in primo piano: ricollega subito il clima se serve
        if (::cam.isInitialized && pane.value == Pane.CAM) cam.open()
    }

    override fun onStop() {
        app.fg = false
        if (::cam.isInitialized) cam.pause()   // in secondo piano il live si ferma
        super.onStop()
    }

    override fun onDestroy() {
        web.destroy()
        cam.destroy()
        super.onDestroy()
    }
}
