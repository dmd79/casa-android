package me.synology.davidevinavil.casa

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

private val main = Handler(Looper.getMainLooper())

/**
 * Apre un pulsante degli Accessi (fuori dal thread principale).
 * "bt:<indirizzo>": via Bluetooth. Gli altri via relay; se il relay non
 * risponde e lo stesso cancello (stesso nome) e' abbinato via Bluetooth,
 * si prova quello. null se riuscito, altrimenti il motivo.
 */
fun openGateBtn(app: CasaApp, b: GateBtn, cookie: String?, who: String): String? {
    if (b.dev.startsWith("bt:")) return runBlocking { app.gates.open(b.dev.removePrefix("bt:"), b.azione, who, 8000) }
    val err = Relay.open(cookie, b, who) ?: return null
    if (err != "relay non raggiungibile") return err
    val g = app.store.gates.value.firstOrNull { it.name.trim().equals(b.devName.trim(), ignoreCase = true) && b.azione in it.azioni }
        ?: return err
    return runBlocking { app.gates.open(g.address, b.azione, who, 8000) }?.let { err }
}

/** Pulsanti dei cancelli Bluetooth abbinati, per le scorciatoie. */
fun btGateBtns(store: Store): List<GateBtn> =
    store.gates.value.flatMap { g -> g.azioni.map { GateBtn("bt:" + g.address, g.name, it) } }

/** Dopo aver cambiato le scorciatoie: aggiorna riquadri e widget. */
fun refreshShortcuts(ctx: Context) {
    GateWidget.refreshAll(ctx)
    listOf(GateTile1::class.java, GateTile2::class.java, GateTile3::class.java).forEach {
        try { TileService.requestListeningState(ctx, ComponentName(ctx, it)) } catch (_: Exception) {}
    }
}

// ── impostazioni rapide ───────────────────────────────────

/**
 * Riquadro n: apre il pulsante degli Accessi assegnato. A telefono bloccato
 * chiede prima di sbloccarlo (unlockAndRun): un cancello non si apre dal blocco schermo.
 */
abstract class GateTile(private val n: Int) : TileService() {
    private val store get() = (application as CasaApp).store

    override fun onStartListening() = show(null)

    private fun show(sub: String?, active: Boolean = false) {
        val t = qsTile ?: return
        val s = store.slot(n)
        t.label = s?.label ?: "Accesso $n"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = sub ?: if (s == null) "da scegliere" else s.devName
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        val s = store.slot(n)
        if (s == null) { openApp(); return }
        if (isLocked) unlockAndRun { go(s) } else go(s)
    }

    private fun go(s: GateBtn) {
        show("invio…", active = true)
        val cookie = Relay.cookie()
        val who = store.who
        thread {
            val err = openGateBtn(application as CasaApp, s, cookie, who)
            main.post {
                show(if (err == null) "inviato ✓" else err, active = err == null)
                main.postDelayed({ show(null) }, 3000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("tab", "SCORC")
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, n, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(i)
    }
}

class GateTile1 : GateTile(1)
class GateTile2 : GateTile(2)
class GateTile3 : GateTile(3)

// ── widget della schermata Home ───────────────────────────

class GateWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, views(ctx, emptyMap())) }
    }

    override fun onReceive(ctx: Context, i: Intent) {
        super.onReceive(ctx, i)
        if (i.action != ACTION) return
        val n = i.getIntExtra("n", 0)
        val store = (ctx.applicationContext as CasaApp).store
        val s = store.slot(n) ?: return
        val cookie = Relay.cookie()
        val who = store.who
        refreshAll(ctx, mapOf(n to "…"))
        val pr = goAsync()
        thread {
            val err = openGateBtn(ctx.applicationContext as CasaApp, s, cookie, who)
            main.post {
                refreshAll(ctx, mapOf(n to if (err == null) "✓" else "✕"))
                Toast.makeText(ctx, if (err == null) "${s.label}: inviato" else "${s.label}: $err", Toast.LENGTH_SHORT).show()
                main.postDelayed({ refreshAll(ctx) }, 2500)
                pr.finish()
            }
        }
    }

    companion object {
        const val ACTION = "me.synology.davidevinavil.casa.GATE"
        private val IDS = intArrayOf(R.id.g1, R.id.g2, R.id.g3)

        fun refreshAll(ctx: Context, marks: Map<Int, String> = emptyMap()) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, GateWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx, marks))
        }

        private fun views(ctx: Context, marks: Map<Int, String>): RemoteViews {
            val store = (ctx.applicationContext as CasaApp).store
            val v = RemoteViews(ctx.packageName, R.layout.widget_gates)
            var any = false
            for (n in 1..3) {
                val id = IDS[n - 1]
                val s = store.slot(n)
                if (s == null) { v.setViewVisibility(id, View.GONE); continue }
                any = true
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, marks[n]?.let { "$it ${s.label}" } ?: s.label)
                val pi = PendingIntent.getBroadcast(ctx, n,
                    Intent(ctx, GateWidget::class.java).setAction(ACTION).putExtra("n", n),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(id, pi)
            }
            v.setViewVisibility(R.id.empty, if (any) View.GONE else View.VISIBLE)
            if (!any) v.setOnClickPendingIntent(R.id.empty, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).putExtra("tab", "SCORC"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return v
        }
    }
}
