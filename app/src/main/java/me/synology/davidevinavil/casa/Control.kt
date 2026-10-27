package me.synology.davidevinavil.casa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ControlScreen(ble: Ble, st: AcState, link: Link, onSettings: () -> Unit, onDevices: () -> Unit) {
    val on = st.isOn
    val mc = modeColor(st.mode)
    var tmrDlg by remember { mutableStateOf(false) }
    // il comando parte nella coda del collegamento: non si perde cambiando schermata
    // [apply] mostra subito il valore scelto e lo tiene per 4 s anche sopra le
    // letture: deve mettere un valore fisso, deciso al tocco. "Il successivo
    // di quello letto" saltava un passo appena arrivava lo stato vero.
    fun cmd(k: String, v: String, apply: ((AcState) -> AcState)? = null) { ble.launch { send(k, v, apply) } }

    Column(Modifier.fillMaxSize()) {
        // ── barra in alto ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(st.name.ifBlank { "Condizionatore" } + " ▾", color = pal.text, fontSize = 26.sp, maxLines = 1,
                modifier = Modifier.weight(1f).clickable(onClick = onDevices))
            val (txt, col) = when (link) {
                Link.CONNECTED -> "Collegato" to pal.ok
                Link.BT_OFF -> "Bluetooth spento" to pal.danger
                else -> "Ricollego…" to pal.danger
            }
            Text(txt, color = col, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(pal.card)
                .border(1.dp, col, RoundedCornerShape(18.dp)).clickable { if (link != Link.CONNECTED) ble.resume() }
                .padding(horizontal = 12.dp, vertical = 6.dp))
            Box(Modifier.padding(start = 8.dp).size(42.dp).clip(CircleShape).background(pal.card)
                .border(1.dp, pal.border, CircleShape).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                SvgIcon(Icons.gear, pal.value, 22.dp)
            }
        }

        // ── split e getto d'aria ──
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(270.dp)) {
            Airflow(st, Modifier.fillMaxWidth().height(170.dp).offset(y = 100.dp))
            AcUnit(st, Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(100.dp))
        }

        // ── accensione e temperatura ──
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val pc = if (on) mc else pal.danger
                Box(Modifier.size(100.dp).clip(CircleShape).background(pc.copy(alpha = .25f)).border(2.dp, pc, CircleShape)
                    .clickable { val m = if (st.isOn) "off" else ble.store.lastMode; cmd("power", "1") { it.copy(mode = m) } },
                    contentAlignment = Alignment.Center) { SvgIcon(Icons.power, pc, 46.dp, stroke = 2.2f) }
                Text(if (on) "Acceso" else "Spento", color = pal.text, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val t = st.target?.roundToInt()
                    Arrow(Icons.down) { if (on && t != null) { val n = max(16, t - 1); cmd("target", "$n") { it.copy(target = n.toDouble()) } } }
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.widthIn(min = 64.dp)) {
                        Text(t?.toString() ?: "--", color = if (on) pal.accent else pal.muted, fontSize = 38.sp)
                        Text("°C", color = if (on) pal.accent else pal.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    Arrow(Icons.up) { if (on && t != null) { val n = min(30, t + 1); cmd("target", "$n") { it.copy(target = n.toDouble()) } } }
                }
                Text("obiettivo", color = pal.muted, fontSize = 14.sp)
            }
        }

        // ── modi ──
        Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            MODES.forEach { m ->
                val sel = on && st.mode == m
                val c = modeColor(m)
                Box(Modifier.size(62.dp).clip(CircleShape).background(if (sel) c.copy(alpha = .3f) else Color.Transparent)
                    .border(if (sel) 2.dp else 1.dp, if (sel) c else pal.border, CircleShape)
                    .clickable { cmd("mode", m) { it.copy(mode = m) } }, contentAlignment = Alignment.Center) {
                    ModeIcon(m, c.copy(alpha = if (on) 1f else .5f), 30.dp)
                }
            }
        }

        // ── ventola, alette, timer ──
        Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val fi = FANS.indexOf(st.fan)
            Chip(Modifier.weight(1f), onTap = { val f = FANS.after(st.fan); cmd("fan", f) { it.copy(fan = f) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Icons.fan(pal.card), if (on) pal.value else pal.muted, 26.dp)
                    Text(" " + (if (fi <= 0) "A" else "$fi"), color = pal.value, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
            }
            Chip(Modifier.weight(1f), onTap = { val v = VSWINGS.after(st.vswing); cmd("vswing", v) { it.copy(vswing = v) } }) {
                SwingIcon(true, st.vswing, on)
            }
            Chip(Modifier.weight(1f), visible = st.hasHswing,
                onTap = { val h = HSWINGS.after(st.hswing); cmd("hswing", h) { it.copy(hswing = h) } }) {
                SwingIcon(false, st.hswing, on)
            }
            Chip(Modifier.weight(1f), active = st.timer, onLong = { tmrDlg = true },
                onTap = { val t = !st.timer; cmd("timer", if (t) "on" else "off") { it.copy(timer = t, timerLeft = if (t) it.timerDur else 0) } }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val c = if (st.timer) pal.accent else pal.muted
                    Text("Timer", color = c, fontSize = 13.sp)
                    Text(if (st.timer) "${st.timerLeft / 60}:${(st.timerLeft % 60).toString().padStart(2, '0')}" else "Spento",
                        color = if (st.timer) pal.accent else pal.value, fontSize = 17.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Note("Tocco: opzione successiva · Pressione lunga sul Timer: durata", Modifier.fillMaxWidth(), center = true)
    }

    if (tmrDlg) TimerDialog(st, onDismiss = { tmrDlg = false }) { m ->
        tmrDlg = false
        ble.launch {
            send("timer_dur", "$m") { it.copy(timerDur = m) }
            send("timer", "on") { it.copy(timerDur = m, timer = true, timerLeft = m) }
        }
    }
}

@Composable
private fun Arrow(icon: List<IconPart>, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(pal.accent.copy(alpha = .15f)).border(2.dp, pal.accent, CircleShape)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { SvgIcon(icon, pal.accent, 26.dp, stroke = 3f) }
}

@Composable
private fun Chip(modifier: Modifier, active: Boolean = false, visible: Boolean = true, onLong: (() -> Unit)? = null,
                 onTap: () -> Unit, content: @Composable () -> Unit) {
    if (!visible) { Spacer(modifier.height(58.dp)); return }
    Box(
        modifier.height(58.dp).clip(RoundedCornerShape(12.dp)).background(if (active) pal.sel else pal.card)
            .border(if (active) 2.dp else 1.dp, if (active) pal.accent else pal.border, RoundedCornerShape(12.dp))
            .pointerInput(onLong, onTap) { detectTapGestures(onTap = { onTap() }, onLongPress = onLong?.let { l -> { _: Offset -> l() } }) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ── unita' interna disegnata ──
@Composable
private fun AcUnit(st: AcState, modifier: Modifier) {
    val on = st.isOn
    val mc = modeColor(st.mode)
    Box(modifier.shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color(0xFFECEAE5))
        .border(1.dp, Color(0xFFC9C5BD), RoundedCornerShape(24.dp))) {
        Text(st.name, color = Color(0xFF5F5B55), fontSize = 17.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
        Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 30.dp).height(1.dp).background(Color(0xFFDCD8D0)))
        Text(st.current?.let { String.format(java.util.Locale.ITALY, "%.1f°", it) } ?: "--",
            color = if (on) mc else Color(0xFF555555), fontSize = 28.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF151515)).widthIn(min = 110.dp).padding(horizontal = 14.dp, vertical = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp).size(8.dp).clip(CircleShape)
            .background(if (on) mc else Color(0xFF555555)))
        Box(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 8.dp).fillMaxWidth()
            .height(12.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF3A3A3A)))
    }
}

// ── getto d'aria animato (stessa logica della pagina web e del termostato) ──
@Composable
private fun Airflow(st: AcState, modifier: Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    val ph = remember { mutableFloatStateOf(0f) }
    val osc = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { frame = it } }
    Canvas(modifier) {
        frame // ridisegna a ogni fotogramma
        if (!st.isOn) return@Canvas
        val w = size.width; val k = w / 480f
        val vi = max(0, VSWINGS.indexOf(st.vswing)); val hi = max(0, HSWINGS.indexOf(st.hswing)); val fi = max(0, FANS.indexOf(st.fan))
        val va0 = floatArrayOf(30f, 5f, 0f, 12f, 30f, 45f, 58f, 52f); val ha0 = floatArrayOf(0f, -34f, -18f, 0f, 18f, 34f)
        osc.floatValue += .03f
        var va = va0[vi]; var ha = if (st.hasHswing) ha0[hi] else 0f
        if (vi == 0) va = 30f + 22f * sin(osc.floatValue)
        if (st.hasHswing && hi == 0) ha = 26f * sin(osc.floatValue * .5f + .7f)
        val sp = if (fi == 0) 2 else fi
        ph.floatValue += .012f * (.6f + .3f * sp)
        val l = (80f + 120f * va / 60f) * k * .95f
        val lean = ha * PI.toFloat() / 180f
        val n = 2 + sp
        val col = modeColor(st.mode)
        floatArrayOf(70f, 130f, 190f, 290f, 350f, 410f).forEachIndexed { j, x0 ->
            val x = x0 * k
            fun pt(s: Float): Offset {
                val u = s / l
                return Offset(x + sin(lean) * s * .9f + (x - 240f * k) * .25f * u + 3.5f * k * sin(s / (14f * k) + ph.floatValue * 6.283f + j) * u,
                    s * cos(lean) * .95f)
            }
            val glow = Path()
            var s = 0f
            while (s <= l + .01f) { val p = pt(s); if (s == 0f) glow.moveTo(p.x, p.y) else glow.lineTo(p.x, p.y); s += l / 14f }
            drawPath(glow, col.copy(alpha = .16f), style = Stroke(7f * k, cap = StrokeCap.Round))
            for (d in 0 until n) {
                val u = ((d.toFloat() / n) + ph.floatValue + j * .17f) % 1f
                val s0 = u * l
                val dash = Path()
                for (q in 0..3) { val p = pt(s0 + 14f * k * q / 3f); if (q == 0) dash.moveTo(p.x, p.y) else dash.lineTo(p.x, p.y) }
                drawPath(dash, col.copy(alpha = ((1f - u).toDouble().pow(1.2) * .95).toFloat()), style = Stroke(3f * k, cap = StrokeCap.Round))
            }
        }
    }
}

// ── icone delle alette (vista di lato / dall'alto), come sul termostato ──
@Composable
private fun SwingIcon(vert: Boolean, pos: String, on: Boolean) {
    val col = if (on) pal.accent else pal.muted
    val mu = pal.muted
    Canvas(Modifier.size(86.dp, 48.dp)) {
        scale(size.width / 86f, pivot = Offset.Zero) {
            fun arrow(x0: Float, y0: Float, a: Float, len: Float, c: Color) {
                val r = a * PI.toFloat() / 180f
                val x1 = x0 + len * cos(r); val y1 = y0 + len * sin(r)
                val p = Path().apply {
                    moveTo(x0, y0); lineTo(x1, y1)
                    for (sg in listOf(-1f, 1f)) { val b = r + PI.toFloat() + sg * .5f; moveTo(x1, y1); lineTo(x1 + 6f * cos(b), y1 + 6f * sin(b)) }
                }
                drawPath(p, c, style = Stroke(3f, cap = StrokeCap.Round))
            }
            if (vert) {
                drawRect(mu, Offset(17f, 8f), Size(15f, 10f))
                val a = mapOf("Health Up" to 10f, "Max Up" to 0f, "Up" to 18f, "Center" to 38f, "Down" to 58f, "Max Down" to 76f, "Health Down" to 66f)
                if (pos == "Auto") listOf(5f, 40f, 75f).forEachIndexed { i, x -> arrow(32f, 14f, x, 26f, if (i == 1) col else mu) }
                else arrow(32f, 14f, a[pos] ?: 38f, 28f, col)
                if (pos.startsWith("Health")) drawOval(Color(0xFF39D19C), Offset(51.5f, 7f), Size(9f, 6f))
            } else {
                drawRect(mu, Offset(27f, 8f), Size(32f, 6f))
                val a = mapOf("Max Left" to 135f, "Left" to 112f, "Center" to 90f, "Right" to 68f, "Max Right" to 45f)
                if (pos == "Auto") listOf(130f, 90f, 50f).forEachIndexed { i, x -> arrow(43f, 15f, x, 24f, if (i == 1) col else mu) }
                else arrow(43f, 15f, a[pos] ?: 90f, 26f, col)
            }
        }
    }
}

// ── durata del timer ──
@Composable
private fun TimerDialog(st: AcState, onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    val start = if (st.timer && st.timerLeft > 0) max(15, (st.timerLeft + 14) / 15 * 15) else st.timerDur
    var m by remember { mutableFloatStateOf(start.coerceIn(15, 480).toFloat()) }
    fun fmt(x: Int): String { val h = x / 60; val r = x % 60; return if (h > 0 && r > 0) "$h h $r min" else if (h > 0) "$h h" else "$r min" }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.bg,
        title = { Text("Si spegne tra", color = pal.muted, fontSize = 16.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(m.roundToInt()), color = pal.text, fontSize = 44.sp)
                Slider(value = m, onValueChange = { m = (it / 15f).roundToInt() * 15f }, valueRange = 15f..480f, steps = 30,
                    colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60, 120, 240, 480).forEach { p ->
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(pal.card).border(1.dp, pal.border, RoundedCornerShape(20.dp))
                            .clickable { m = p.toFloat() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(if (p < 60) "$p min" else "${p / 60} h", color = pal.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(m.roundToInt()) }) {
            Text(if (st.timer) "Aggiorna timer" else "Avvia timer", color = pal.accent, fontSize = 17.sp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = pal.value, fontSize = 17.sp) } },
    )
}
