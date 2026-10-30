package me.synology.davidevinavil.casa

import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar

private val DAYS = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")
private val DAYS_FULL = listOf("Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica")
private val MODE_LBL = mapOf("cool" to "Raffresca", "heat" to "Riscalda", "heat_cool" to "Auto", "dry" to "Deumid.", "fan_only" to "Ventola", "off" to "Spento")
private val FAN_LBL = mapOf("auto" to "Auto", "low" to "Bassa", "medium" to "Media", "high" to "Alta")
private fun schedColor(m: String) = when (m) {
    "cool" -> Color(0xFF00C6FF); "heat" -> Color(0xFFFF8A3D); "heat_cool" -> Color(0xFFA78BFA)
    "dry" -> Color(0xFF39D19C); "fan_only" -> Color(0xFFE7C12C); else -> Color(0xFF5A6266)
}
private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
private fun withTemp(m: String) = m == "cool" || m == "heat" || m == "heat_cool" || m == "dry"
/** Oggi: 0 = lunedi'. */
private fun today() = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7

/**
 * Programmazione del condizionatore via Bluetooth, come quella del termostato:
 * giorni coi grafici, grafico del giorno, orari del giorno con la modifica,
 * copia di un giorno su altri. Sul condizionatore un orario puo' valere per
 * piu' giorni (24 orari in tutto): cambiarlo lo cambia per tutti i suoi giorni.
 * I giorni di un orario si cambiano con "Copia … su", come nella pagina web.
 */
@Composable
fun SchedScreen(ble: Ble, name: String, onBack: () -> Unit) {
    val sc by ble.sched.collectAsState()
    var day by remember { mutableStateOf(today()) }
    var edit by remember { mutableStateOf(false) }
    var editSel by remember { mutableStateOf<Int?>(null) }      // orario aperto dal pallino del grafico
    var copy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ble.launch { readSched() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Back(name, onBack)
        Spacer(Modifier.height(10.dp))
        val s = sc
        if (s == null) {
            Note(if (ble.hasSched) "Leggo la programmazione…" else "Questo condizionatore non ha ancora il firmware con la programmazione via Bluetooth.", center = true)
            return@Column
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(R_CARD)).background(pal.card)
            .border(BW, pal.border, RoundedCornerShape(R_CARD)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PROGRAMMAZIONE", color = pal.value, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${s.slots.size}/24", color = pal.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                OnPill(s.on) { ble.launch { schedOn(!s.on) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAYS.forEachIndexed { i, d ->
                    val on = i == day
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(BW, if (on) pal.accent else pal.border, RoundedCornerShape(10.dp))
                        .clickable { day = i }.padding(horizontal = 3.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d, color = if (on) pal.accent else pal.value, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                            if (i == today()) Box(Modifier.size(5.dp).clip(CircleShape).background(pal.text))
                        }
                        DayGraph(s, i, Modifier.fillMaxWidth().height(24.dp), big = false)
                    }
                }
            }
            // toccando un pallino si apre quell'orario, come sul termostato e sulla pagina web
            DayGraph(s, day, Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp), big = true,
                onDot = { editSel = it; edit = true })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Programma ${DAYS[day]}", color = pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(pal.accent.copy(alpha = .1f))
                        .border(BW, pal.accent, RoundedCornerShape(22.dp)).clickable { editSel = null; edit = true }.padding(vertical = 11.dp))
                Text("Copia ${DAYS[day]} su…", color = pal.value, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).border(BW, pal.border, RoundedCornerShape(22.dp))
                        .clickable { copy = true }.padding(vertical = 11.dp))
            }
        }
        Note("Tocca un giorno per vederne il grafico, un pallino per aprire quell'orario. Senza internet il condizionatore prende l'ora dal telefono quando ti colleghi in Bluetooth.", center = true)
    }
    val s = sc ?: return
    if (edit) DaySheet(ble, s, day, editSel) { edit = false }
    if (copy) CopySheet(ble, s, day) { copy = false }
}

@Composable
private fun OnPill(on: Boolean, onClick: () -> Unit) {
    val c = if (on) pal.ok else pal.muted
    Row(Modifier.clip(RoundedCornerShape(R_CARD)).border(BW, c, RoundedCornerShape(R_CARD)).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Text(if (on) " On" else " Off", color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Grafico di un giorno: per ogni tratto la temperatura e il colore del modo
 * (spento e ventola in basso). Prima del primo orario vale l'ultimo del giorno.
 * [big]: assi, punti con ora e temperatura, linea dell'ora attuale.
 */
@Composable
private fun DayGraph(s: Sched, day: Int, modifier: Modifier, big: Boolean, onDot: ((Int) -> Unit)? = null) {
    val idx = s.day(day).filter { it.value.enabled }
    val list = idx.map { it.value }
    val nowMin = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    val isToday = day == today()
    val muted = pal.muted
    val grid = pal.border
    val txt = pal.text
    val ctx = LocalContext.current
    val dens = ctx.resources.displayMetrics.scaledDensity
    // tocco su un pallino (entro ~20 dp): stesse coordinate del disegno
    val tap = if (onDot == null || list.isEmpty()) Modifier else Modifier.pointerInput(s, day) {
        detectTapGestures { o ->
            val left = 26.dp.toPx(); val right = size.width - 6.dp.toPx()
            val top = 40.dp.toPx(); val bottom = size.height - 20.dp.toPx()
            fun x(min: Int) = left + (right - left) * min / 1440f
            fun y(t: Int, m: String) = if (!withTemp(m)) bottom - 3f else bottom - (bottom - top) * (t - 16) / 14f
            val hit = idx.minByOrNull { val dx = x(it.value.min) - o.x; val dy = y(it.value.temp, it.value.modeKey) - o.y; dx * dx + dy * dy }
            if (hit != null) {
                val dx = x(hit.value.min) - o.x; val dy = y(hit.value.temp, hit.value.modeKey) - o.y
                if (dx * dx + dy * dy < (20.dp.toPx()).let { it * it }) onDot(hit.index)
            }
        }
    }
    Canvas(modifier.then(tap)) {
        val left = if (big) 26.dp.toPx() else 0f
        val right = size.width - if (big) 6.dp.toPx() else 0f
        val top = if (big) 40.dp.toPx() else 2f
        val bottom = size.height - if (big) 20.dp.toPx() else 2f
        fun x(min: Int) = left + (right - left) * min / 1440f
        fun y(t: Int, m: String) = if (!withTemp(m)) bottom - 3f else bottom - (bottom - top) * (t - 16) / 14f
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 11 * dens; typeface = android.graphics.Typeface.MONOSPACE }
        if (big) listOf(20, 25).forEach { t ->
            val yy = y(t, "cool")
            drawLine(grid, Offset(left, yy), Offset(right, yy), 1f)
            paint.color = android.graphics.Color.GRAY; paint.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText("$t°", 0f, yy + 4 * dens, paint)
        }
        if (list.isEmpty()) {
            drawLine(muted, Offset(left, bottom - 3f), Offset(right, bottom - 3f), 3f)
        } else {
            val prev = list.last()
            val pts = listOf(Triple(0, prev.temp, prev.modeKey)) + list.map { Triple(it.min, it.temp, it.modeKey) }
            for (i in pts.indices) {
                val (a, t, m) = pts[i]
                val b = if (i + 1 < pts.size) pts[i + 1].first else 1440
                val c = schedColor(m)
                val yy = y(t, m)
                drawRect(c.copy(alpha = .18f), Offset(x(a), yy), Size(x(b) - x(a), bottom - yy))
                drawLine(c, Offset(x(a), yy), Offset(x(b), yy), if (big) 3.dp.toPx() else 2f)
                if (i > 0) {
                    val py = y(pts[i - 1].second, pts[i - 1].third)
                    drawLine(Color(0xFF777777), Offset(x(a), minOf(py, yy)), Offset(x(a), maxOf(py, yy)), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    if (big) {
                        drawCircle(Color(0xFFEEEEEE), 6.dp.toPx(), Offset(x(a), yy))
                        drawCircle(c, 6.dp.toPx(), Offset(x(a), yy), style = Stroke(3.dp.toPx()))
                        paint.textAlign = android.graphics.Paint.Align.CENTER
                        paint.color = android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
                        drawContext.canvas.nativeCanvas.drawText(hm(a / 60, a % 60), x(a), yy - 24 * dens, paint)
                        paint.color = android.graphics.Color.argb(255, 221, 221, 221)
                        drawContext.canvas.nativeCanvas.drawText(if (withTemp(m)) "$t°" else MODE_LBL[m]?.lowercase() ?: "", x(a), yy - 11 * dens, paint)
                    }
                }
            }
        }
        if (big) {
            if (isToday) drawLine(txt, Offset(x(nowMin), top - 20 * dens), Offset(x(nowMin), bottom), 1.5f)
            paint.color = android.graphics.Color.GRAY; paint.textAlign = android.graphics.Paint.Align.CENTER
            listOf(0, 6, 12, 18, 24).forEach { h -> drawContext.canvas.nativeCanvas.drawText("%02d".format(h), x(h * 60), size.height - 4 * dens, paint) }
        }
    }
}

// ── orari di un giorno ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySheet(ble: Ble, s: Sched, day: Int, start: Int?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val list = s.day(day)
    var sel by remember { mutableStateOf(start) }               // indice nello store
    // −/+ della temperatura: si mostra subito e si manda una volta sola quando ci si ferma
    var pendT by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // (orario, temperatura)
    LaunchedEffect(pendT) {
        val p = pendT ?: return@LaunchedEffect
        delay(600)
        ble.launch { schedCmd("set ${p.first} temp ${p.second}") }
    }
    LaunchedEffect(s) { pendT?.let { p -> if (s.slots.getOrNull(p.first)?.temp == p.second) pendT = null } }
    var adding by remember { mutableStateOf(false) }
    // orario appena aggiunto: si seleziona quando arriva
    LaunchedEffect(s.slots.size) { if (adding) { sel = s.slots.size - 1; adding = false } }
    val cur = sel?.let { s.slots.getOrNull(it) }
    fun cmd(vararg c: String) { ble.launch { schedCmd(*c) } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DAYS_FULL[day], color = pal.accent, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${list.size} orari · ${s.slots.size}/24", color = pal.muted, fontSize = 13.sp)
                CloseX(onClose)
            }
            DayGraph(s, day, Modifier.fillMaxWidth().height(56.dp).padding(vertical = 6.dp), big = false)
            list.forEach { (i, x) ->
                val c = schedColor(x.modeKey)
                val on = sel == i
                Row(Modifier.fillMaxWidth().padding(bottom = 5.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.copy(alpha = .15f) else Color.Transparent)
                    .border(BW, if (on) c else pal.border, RoundedCornerShape(12.dp))
                    .clickable { sel = i }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (x.enabled) c else pal.muted))
                    Text("  " + hm(x.hour, x.minute), color = if (x.enabled) pal.text else pal.muted, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                    Text("  " + (MODE_LBL[x.modeKey] ?: ""), color = if (x.enabled) c else pal.muted, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    if (withTemp(x.modeKey)) Text("${x.temp}°", color = pal.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (s.slots.size < 24) Text("+ Aggiungi orario", color = pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(20.dp)).background(pal.accent.copy(alpha = .1f))
                    .border(BW, pal.accent, RoundedCornerShape(20.dp)).clickable {
                        // nuovo orario solo per questo giorno (il firmware lo crea per tutti i giorni)
                        val n = s.slots.size
                        adding = true
                        cmd("add", "set $n days ${day + 1}")
                    }.padding(vertical = 9.dp))
            else Note("Massimo 24 orari in tutto.", center = true)
            val x = cur
            if (x != null && sel != null) {
                val i = sel!!
                Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(R_CARD)).border(BW, pal.border, RoundedCornerShape(R_CARD)).padding(10.dp)) {
                    Lbl("ORA")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pal.card).clickable {
                        TimePickerDialog(ctx, { _, h, m -> cmd("set $i hour $h", "set $i minute $m") }, x.hour, x.minute, true).show()
                    }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(hm(x.hour, x.minute), color = pal.text, fontSize = 30.sp, fontFamily = FontFamily.Monospace)
                        Text("   🕒", fontSize = 18.sp)
                    }
                    Lbl("MODO")
                    SCHED_MODES.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pair.forEach { m ->
                                val c = schedColor(m)
                                val on = x.modeKey == m
                                Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) c.copy(alpha = .15f) else Color.Transparent)
                                    .border(BW, if (on) c else pal.border, RoundedCornerShape(10.dp)).clickable { if (!on) cmd("set $i mode $m") }
                                    .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(MODE_LBL[m] ?: m, color = if (on) c else pal.value, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    if (on && withTemp(m)) {
                                        val t = pendT?.takeIf { it.first == i }?.second ?: x.temp
                                        Text("−", color = c, fontSize = 17.sp, modifier = Modifier.clickable { if (t > 16) pendT = i to t - 1 }.padding(horizontal = 5.dp))
                                        Text("$t°", color = c, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                                        Text("+", color = c, fontSize = 17.sp, modifier = Modifier.clickable { if (t < 30) pendT = i to t + 1 }.padding(horizontal = 5.dp))
                                    }
                                }
                            }
                        }
                    }
                    Lbl("ALTRO")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val fan = SCHED_FANS.getOrElse(x.fan) { "auto" }
                        // niente Auto negli orari Spento o Ventola (lo split la rifiuta)
                        val sm = SCHED_MODES.getOrElse(x.mode) { "cool" }
                        val fl = if (sm == "fan_only" || sm == "off") SCHED_FANS.drop(1) else SCHED_FANS
                        Chip2("Ventola", FAN_LBL[fan] ?: fan, Modifier.weight(1f)) {
                            cmd("set $i fan ${if (fan in fl) fl.after(fan) else fl.first()}")
                        }
                        Chip2("Deflettore ↕", swLbl(SCHED_SWV.getOrElse(x.swv) { "keep" }), Modifier.weight(1f)) { cmd("set $i swv ${SCHED_SWV[(x.swv + 1) % SCHED_SWV.size]}") }
                        Chip2("Deflettore ↔", swLbl(SCHED_SWH.getOrElse(x.swh) { "keep" }), Modifier.weight(1f)) { cmd("set $i swh ${SCHED_SWH[(x.swh + 1) % SCHED_SWH.size]}") }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OnPill(x.enabled) { cmd("set $i en ${if (x.enabled) "off" else "on"}") }
                        Spacer(Modifier.weight(1f))
                        // cestino: toglie il giorno aperto; se era l'unico giorno, l'orario sparisce
                        Text("🗑", fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(R_CARD)).border(BW, pal.danger, RoundedCornerShape(R_CARD))
                            .clickable {
                                sel = null
                                if (x.days == (1 shl day)) cmd("del $i") else cmd("set $i day $day off")
                            }.padding(horizontal = 14.dp, vertical = 3.dp))
                    }
                    if (Integer.bitCount(x.days) > 1) Note("Vale per ${Integer.bitCount(x.days)} giorni: le modifiche valgono per tutti. Il cestino lo toglie solo da ${DAYS_FULL[day]}.")
                }
            }
        }
    }
}

/** ✕ per chiudere un riquadro, come nella pagina web. */
@Composable
private fun CloseX(onClick: () -> Unit) {
    Box(Modifier.padding(start = 10.dp).size(32.dp).clip(CircleShape).border(BW, pal.border, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text("✕", color = pal.text, fontSize = 15.sp) }
}

private fun swLbl(v: String) = when (v) {
    "keep" -> "non toccare"; "auto" -> "auto"; else -> v.replace('_', ' ')
}

@Composable private fun Lbl(t: String) = Text(t, color = pal.muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

@Composable
private fun Chip2(t: String, v: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(pal.card).border(BW, pal.border, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(t, color = pal.muted, fontSize = 10.sp, maxLines = 1)
        Text(v, color = pal.text, fontSize = 12.sp, maxLines = 1)
    }
}

// ── copia di un giorno ────────────────────────────────────

/**
 * "Sostituisci la programmazione di": i giorni scelti perdono i loro orari e
 * prendono quelli del giorno [src]. Senza doppioni: agli orari di [src] si
 * aggiungono quei giorni, dagli altri si tolgono (e un orario rimasto senza
 * giorni si cancella).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopySheet(ble: Ble, s: Sched, src: Int, onClose: () -> Unit) {
    var dst by remember { mutableStateOf(setOf<Int>()) }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copia ${DAYS_FULL[src]}", color = pal.accent, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                CloseX(onClose)
            }
            DayGraph(s, src, Modifier.fillMaxWidth().height(70.dp).padding(vertical = 8.dp), big = false)
            Text("Sostituisci la programmazione di:", color = pal.text, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAYS.forEachIndexed { i, d ->
                    val on = i in dst
                    Text(d, color = when { i == src -> pal.muted.copy(alpha = .5f); on -> pal.accent; else -> pal.text }, fontSize = 13.sp, textAlign = TextAlign.Center,
                        maxLines = 1, modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                            .background(if (on) pal.accent.copy(alpha = .15f) else Color.Transparent)
                            .border(BW, if (on) pal.accent else pal.border, RoundedCornerShape(18.dp))
                            .clickable(enabled = i != src) { dst = if (on) dst - i else dst + i }.padding(vertical = 10.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Tutti i giorni" to (0..6).toSet(), "Lun - Ven" to (0..4).toSet(), "Sab - Dom" to setOf(5, 6)).forEach { (l, set) ->
                    Text(l, color = pal.text, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).border(BW, pal.value, RoundedCornerShape(18.dp))
                            .clickable { dst = set - src }.padding(vertical = 9.dp))
                }
            }
            Note("Gli orari di quei giorni vengono sostituiti.")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Annulla", Modifier.weight(1f), secondary = true, onClick = onClose)
            Btn(if (busy) "Copio…" else "Copia", Modifier.weight(1f), enabled = dst.isNotEmpty() && !busy) {
                busy = true
                val mask = dst.fold(0) { a, d -> a or (1 shl d) }
                val sets = mutableListOf<String>()
                val dels = mutableListOf<Int>()
                s.slots.forEachIndexed { i, x ->
                    var nd = x.days and mask.inv()
                    if (x.has(src)) nd = nd or mask
                    if (nd == 0) dels += i
                    else if (nd != x.days) sets += "set $i days " + (0..6).filter { (nd shr it) and 1 == 1 }.joinToString("") { (it + 1).toString() }
                }
                // prima i cambi (indici stabili), poi le cancellazioni dall'ultima
                val all = sets + dels.sortedDescending().map { "del $it" }
                ble.launch { schedCmd(*all.toTypedArray()) }
                onClose()
            }
            }
        }
    }
}
