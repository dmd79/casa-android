package me.synology.davidevinavil.casa

import android.view.MotionEvent
import android.view.View

/**
 * Tornando dalle Telecamere alle pagine col gesto indietro, il primo tocco
 * arrivava all'app ma Compose non lo passava alla WebView (si perdeva fra le
 * due WebView: il gesto dal bordo era cominciato su quella delle telecamere
 * ed era stato annullato dal sistema). Solo per quel primo tocco, se la
 * WebView delle pagine non lo riceve, glielo si consegna direttamente.
 */
object TouchFix {
    /** Appena tornati dalle Telecamere: il prossimo tocco va controllato. */
    @Volatile var armed = false
    /** La WebView delle pagine ha ricevuto il "dito giu'" del tocco in corso. */
    @Volatile var got = false

    fun inside(v: View, ev: MotionEvent): Boolean {
        if (!v.isShown) return false
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return ev.x >= loc[0] && ev.x < loc[0] + v.width && ev.y >= loc[1] && ev.y < loc[1] + v.height
    }

    fun forward(v: View, ev: MotionEvent) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val e = MotionEvent.obtain(ev)
        e.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
        try { v.dispatchTouchEvent(e) } catch (x: Exception) {} finally { e.recycle() }
    }
}
