package me.synology.davidevinavil.casa

import org.json.JSONObject

// Protocollo (haier/ac_ble.h), uguale alla pagina web Clima BT:
//   servizio 5e6b0001-...
//   0002 stato (lettura, JSON)   0003 rev (notifica)   0004 comando (scrittura)
//   0005 info (lettura, JSON)    0006 reti Wi-Fi viste (lettura, testo)
//   comando = "PIN|chiave=valore", a pezzi da 18 byte (gli intermedi con "+")

val MODES = listOf("auto", "cool", "heat", "dry", "fan")
val FANS = listOf("auto", "low", "medium", "high")
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
