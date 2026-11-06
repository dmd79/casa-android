package me.synology.davidevinavil.casa

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DIAGNOSI PROVVISORIA (da togliere): dove si perde il primo tocco tornando
 * dalle Telecamere col gesto indietro. In alto sullo schermo le ultime righe:
 *  A = tocco arrivato all'app, W = alla WebView delle pagine, C = alla
 *  WebView delle telecamere, J = evento nella pagina, K = tasto indietro,
 *  P = scheda mostrata. Il numero davanti sono i millisecondi.
 */
object Diag {
    val lines = MutableStateFlow(listOf<String>())

    fun log(s: String) {
        val t = SystemClock.uptimeMillis() % 100000
        val l = (lines.value + "$t $s").takeLast(14)
        lines.value = l
    }

    private fun act(e: MotionEvent) = when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> "giu"
        MotionEvent.ACTION_UP -> "su"
        MotionEvent.ACTION_CANCEL -> "ANNULLATO"
        else -> null
    }

    fun touch(tag: String, e: MotionEvent) {
        val a = act(e) ?: return
        log("$tag $a ${e.x.toInt()},${e.y.toInt()}")
    }

    @SuppressLint("ClickableViewAccessibility")
    fun watch(v: View, tag: String) {
        v.setOnTouchListener { _, e -> touch(tag, e); false }
    }

    /** Nella pagina: pointerdown, pointerup, pointercancel e click, prima di tutto il resto. */
    const val JS = """(function(){if(window.__dg)return;window.__dg=1;
function d(ev){try{var t=ev.target,n=t&&t.closest?t.closest('[data-k]'):null;
CasaApp.dbg(ev.type+(n?' '+n.dataset.k:' '+(t&&t.tagName||'')));}catch(x){}}
['pointerdown','pointerup','pointercancel','click'].forEach(function(k){document.addEventListener(k,d,true);});
document.addEventListener('visibilitychange',function(){try{CasaApp.dbg('visibile '+!document.hidden);}catch(x){}});})();"""
}

@Composable
fun DiagOverlay() {
    val l by Diag.lines.collectAsState()
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 40.dp)) {
        Column(Modifier.background(Color(0xB0000000)).padding(4.dp)) {
            for (s in l) Text(s, color = Color(0xFF7CFC00), fontSize = 10.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
