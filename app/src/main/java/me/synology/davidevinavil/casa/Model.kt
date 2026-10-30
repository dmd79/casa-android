package me.synology.davidevinavil.casa

import org.json.JSONObject

// Protocollo (haier/ac_ble.h), uguale alla pagina web Clima BT:
//   servizio 5e6b0001-...
//   0002 stato (lettura, JSON)   0003 rev (notifica)   0004 comando (scrittura)
//   0005 info (lettura, JSON)    0006 reti Wi-Fi viste (lettura, testo)
//   comando = "chiave=valore", a pezzi da 18 byte (gli intermedi con "+"); niente PIN:
//   stato, comandi e info passano solo col telefono abbinato (codice a 6 cifre)

val MODES = listOf("auto", "cool", "heat", "dry", "fan")
val FANS = listOf("auto", "low", "medium", "high")
// Ventola Auto da spento o in Ventola: lo split la rifiuta e poi rifiuta
// anche i comandi successivi (il firmware la scarta comunque, on_control
// in haier.yaml). Qui non si propone nemmeno.
fun fansFor(mode: String) = if (mode !in MODES || mode == "fan") FANS.drop(1) else FANS
val VSWINGS = listOf("Auto", "Health Up", "Max Up", "Up", "Center", "Down", "Max Down", "Health Down")
val HSWINGS = listOf("Auto", "Max Left", "Left", "Center", "Right", "Max Right")

fun <T> List<T>.after(v: T): T = this[(maxOf(0, indexOf(v)) + 1) % size]

data class AcState(
    val name: String = "",
    val mode: String = "off",
    val current: Double? = null,
    val target: Double? = null,
    val fan: String = "auto",
    val vswing: String = "Auto",
    val hswing: String = "Auto",
    val hasHswing: Boolean = true,
    val timer: Boolean = false,
    val timerLeft: Int = 0,      // minuti
    val timerDur: Int = 120,     // minuti
    val last: String = "",
) {
    val isOn get() = mode in MODES
    val fans get() = fansFor(mode)

    companion object {
        fun parse(j: JSONObject) = AcState(
            name = j.optString("name", ""),
            mode = j.optString("mode", "off"),
            current = j.optNum("current"),
            target = j.optNum("target"),
            fan = j.optString("fan", "auto"),
            vswing = j.optString("vswing", "Auto"),
            hswing = j.optString("hswing", "Auto"),
            hasHswing = j.optBoolean("has_hswing", true),
            timer = j.optBoolean("timer", false),
            timerLeft = j.optInt("timer_left", 0),
            timerDur = j.optInt("timer_dur", 120),
            last = j.optString("last", ""),
        )
    }
}

data class AcInfo(
    val mode: String,
    val wifi: Boolean,
    val ssid: String,
    val rssi: Int,
    val heap: Long,
    val block: Long,
    val uptime: Long,
    val reset: String,
    val ip: String,
    val ap: Boolean,
    val fw: String,
    /** Indirizzo degli aggiornamenti e md5 del firmware installato (per "Aggiorna firmware"). */
    val ota: String = "",
    val md5: String = "",
    /** Ora del condizionatore ("gio 29/10 13:17"), vuota se non la sa ancora; null col firmware vecchio. */
    val time: String? = null,
) {
    companion object {
        fun parse(j: JSONObject) = AcInfo(
            mode = j.optString("mode", ""),
            wifi = j.optBoolean("wifi", false),
            ssid = j.optString("ssid", ""),
            rssi = j.optInt("rssi", 0),
            heap = j.optLong("heap", 0),
            block = j.optLong("block", 0),
            uptime = j.optLong("uptime", 0),
            reset = j.optString("reset", ""),
            ip = j.optString("ip", ""),
            ap = j.optBoolean("ap", false),
            fw = j.optString("fw", ""),
            ota = j.optString("ota", ""),
            md5 = j.optString("md5", ""),
            time = if (j.has("tm")) j.optString("tm", "") else null,
        )
    }
}

data class WifiNet(val ssid: String, val rssi: Int)

private val NET_LINE = Regex("""^(.*):\s*(-?\d+)\s*dB""")

fun parseNets(t: String): List<WifiNet> {
    val out = LinkedHashMap<String, WifiNet>()
    t.split("\n").forEach { l ->
        val m = NET_LINE.find(l) ?: return@forEach
        val s = m.groupValues[1].trim()
        if (s.isNotEmpty() && s !in out) out[s] = WifiNet(s, m.groupValues[2].toInt())
    }
    return out.values.sortedByDescending { it.rssi }
}

/** Ultimo stato letto di un condizionatore Bluetooth e quando (ms). */
data class AcSnap(val mode: String, val current: Double?, val target: Double?, val t: Long)

/** Condizionatore trovato cercando. */
data class Found(val address: String, val name: String, val rssi: Int)

/** Condizionatore salvato nell'app. */
data class Saved(val address: String, val name: String)

private fun JSONObject.optNum(k: String): Double? =
    if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

/**
 * Pezzi da 18 byte; gli intermedi con "+" davanti. L'ultimo non deve
 * cominciare con "+" (sembrerebbe intermedio): quel byte passa al pezzo prima.
 */
fun pieces(b: ByteArray): List<ByteArray> {
    val n = 18
    val cut = (b.indices step n).toMutableList()
    if (cut.isEmpty()) return listOf(b)
    val l = cut.size
    if (l > 1 && b[cut[l - 1]] == '+'.code.toByte()) cut[l - 1]++
    return cut.mapIndexed { i, o ->
        val end = if (i + 1 < l) cut[i + 1] else b.size
        val p = b.copyOfRange(o, end)
        if (i == l - 1) p else byteArrayOf('+'.code.toByte()) + p
    }
}

// ── programmazione del condizionatore (haier/haier_scheduler.h) ──
val SCHED_MODES = listOf("cool", "heat", "heat_cool", "dry", "fan_only", "off")
val SCHED_FANS = listOf("auto", "low", "medium", "high")
val SCHED_SWV = listOf("health_up", "max_up", "up", "center", "down", "max_down", "health_down", "auto", "keep")
val SCHED_SWH = listOf("max_left", "left", "center", "right", "max_right", "auto", "keep")

/** Un orario: [days] bit0 = lunedi' ... bit6 = domenica; indici in SCHED_*. */
data class SchedSlot(val hour: Int, val minute: Int, val days: Int, val temp: Int, val mode: Int, val fan: Int,
                     val enabled: Boolean, val swv: Int, val swh: Int) {
    val min get() = hour * 60 + minute
    fun has(day: Int) = (days shr day) and 1 == 1
    val modeKey get() = SCHED_MODES.getOrElse(mode) { "cool" }
}

/** Programmazione letta via Bluetooth (esadecimale: master, numero, 9 byte per orario). */
data class Sched(val on: Boolean, val slots: List<SchedSlot>) {
    /** Orari di un giorno, in ordine d'ora, con l'indice nello store. */
    fun day(d: Int) = slots.withIndex().filter { it.value.has(d) }.sortedBy { it.value.min }

    companion object {
        fun parse(h: String): Sched? = try {
            val b = h.trim().chunked(2).map { it.toInt(16) }
            val n = b[1]
            Sched(b[0] == 1, (0 until n).map { i ->
                val o = 2 + i * 9
                SchedSlot(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5], b[o + 6] and 1 == 1, b[o + 7], b[o + 8])
            })
        } catch (e: Exception) { null }
    }
}

/**
 * Stesso dispositivo nel relay e in Bluetooth: nomi uguali senza contare
 * maiuscole, spazi, trattini e altri segni ("haier 7c0064" = "haier-7c0064").
 */
fun sameName(a: String, b: String): Boolean {
    fun k(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    val x = k(a)
    return x.isNotEmpty() && x == k(b)
}
