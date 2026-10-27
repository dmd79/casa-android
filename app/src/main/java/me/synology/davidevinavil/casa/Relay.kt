package me.synology.davidevinavil.casa

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Pulsante degli Accessi: dispositivo del modulo gate + azione (garage, cancello...). */
data class GateBtn(val dev: String, val devName: String, val azione: String) {
    val label get() = azione.replaceFirstChar { it.uppercase() }
}

/** Cancello del relay con pulsanti e stato (/_gatedata). */
data class RelayGate(val id: String, val name: String, val online: Boolean, val azioni: List<String>, val stati: Map<String, String>)

/** Dispositivo del relay, come lo riassume /_list. */
data class RelayDev(
    val id: String, val name: String, val type: String, val online: Boolean,
    val cur: Double?, val tgt: Double?, val hum: Double?, val heat: Boolean, val mode: String,
) {
    companion object {
        fun parse(o: JSONObject): RelayDev {
            val s = o.optJSONObject("sum") ?: JSONObject()
            fun num(k: String) = if (s.has(k) && !s.isNull(k)) s.optDouble(k).takeIf { !it.isNaN() } else null
            val id = o.optString("id")
            return RelayDev(id, o.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: id,
                o.optString("type").ifBlank { "other" }, o.optBoolean("online"),
                num("cur"), num("tgt"), num("hum"), s.optBoolean("heat"), s.optString("mode"))
        }
    }
}

/**
 * Richieste al relay con la stessa sessione delle pagine (i cookie della
 * WebView): niente password nell'app. Da chiamare fuori dal thread principale;
 * il cookie si legge prima, sul thread principale, con [cookie].
 */
object Relay {
    val BASE: String = BuildConfig.BASE_URL

    fun cookie(): String? = try { CookieManager.getInstance().getCookie(BASE) } catch (e: Exception) { null }

    private fun conn(path: String, cookie: String?, method: String = "GET"): HttpURLConnection {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000     // in casa e in rete mobile risponde molto prima: oltre, e' giu' davvero
        c.readTimeout = 8000
        c.setRequestProperty("Cache-Control", "no-store")
        // niente collegamenti tenuti aperti: riaprendo l'app quello vecchio era
        // gia' chiuso e la prima richiesta falliva ("relay non raggiungibile")
        c.setRequestProperty("Connection", "close")
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        return c
    }

    private fun text(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to body
    }

    /** Dispositivi entrati nell'app (JSON grezzo di /_list, null se non raggiungibile). */
    fun listRaw(cookie: String?): String? = try {
        val (code, body) = text(conn("/_list", cookie))
        if (code == 200 && body.trimStart().startsWith("[")) body else null
    } catch (e: Exception) { null }

    fun parseList(raw: String?): List<RelayDev> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optBoolean("ok") }.map { RelayDev.parse(it) }
    } catch (e: Exception) { emptyList() }

    /** Tutti i pulsanti degli Accessi dei dispositivi entrati nell'app. */
    fun gates(cookie: String?): List<GateBtn> {
        val (code, body) = text(conn("/_list", cookie))
        if (code != 200) return emptyList()
        val list = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<GateBtn>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (!o.optBoolean("ok") || o.optString("type") != "gate") continue
            val id = o.getString("id")
            val g = try { JSONObject(text(conn("/_gatedata?d=$id", cookie)).second) } catch (e: Exception) { continue }
            val name = g.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("name").ifBlank { id }
            val az = g.optJSONObject("log")?.optJSONArray("azioni") ?: continue
            for (k in 0 until az.length()) out += GateBtn(id, name, az.getString(k))
        }
        return out
    }

    /** Cancelli con pulsanti e stato; null se il relay non risponde o non si e' entrati. */
    fun gateList(cookie: String?): List<RelayGate>? = try {
        val (code, body) = text(conn("/_list", cookie))
        if (code != 200 || !body.trimStart().startsWith("[")) null
        else {
            val list = JSONArray(body)
            val out = mutableListOf<RelayGate>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (!o.optBoolean("ok") || o.optString("type") != "gate") continue
                val id = o.getString("id")
                val g = try { JSONObject(text(conn("/_gatedata?d=$id", cookie)).second) } catch (e: Exception) { JSONObject() }
                val az = g.optJSONObject("log")?.optJSONArray("azioni")
                val st = g.optJSONObject("stati")
                out += RelayGate(id,
                    g.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("name").ifBlank { id },
                    g.optBoolean("online", o.optBoolean("online")) && az != null,
                    if (az == null) emptyList() else (0 until az.length()).map { az.getString(it) },
                    buildMap { st?.keys()?.forEach { k -> put(k, st.optString(k)) } })
            }
            out
        }
    } catch (e: Exception) { null }

    /**
     * Elenco dei cancelli salvato, con collegamento e stato dell'ultima
     * lettura: riaprendo la scheda si riparte da li' (niente Bluetooth per
     * un attimo finche' il relay non risponde) e senza rete si vedono lo stesso.
     */
    fun gatesJson(l: List<RelayGate>): String = JSONArray().apply {
        l.forEach { g ->
            val s = JSONObject()
            g.stati.forEach { (k, v) -> s.put(k, v) }
            put(JSONObject().put("id", g.id).put("n", g.name).put("o", g.online).put("z", JSONArray(g.azioni)).put("s", s))
        }
    }.toString()

    fun parseGates(raw: String?): List<RelayGate> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val z = o.optJSONArray("z")
            val s = o.optJSONObject("s")
            RelayGate(o.getString("id"), o.optString("n"), o.optBoolean("o"),
                if (z == null) emptyList() else (0 until z.length()).map { z.getString(it) },
                buildMap { s?.keys()?.forEach { k -> put(k, s.optString(k)) } })
        }
    } catch (e: Exception) { emptyList() }

    /** Messaggi di [open] per cui il comando di sicuro non e' arrivato al cancello. */
    val NOT_SENT = setOf("relay non raggiungibile", "apri Casa ed entra")

    /**
     * Apre; null se riuscito, altrimenti il motivo. "relay non raggiungibile"
     * solo se il collegamento non si e' nemmeno aperto: dopo, il comando
     * potrebbe essere partito (un secondo impulso richiuderebbe il cancello).
     */
    fun open(cookie: String?, b: GateBtn, who: String): String? {
        val q = "d=" + enc(b.dev) + "&azione=" + enc(b.azione) + "&utente=" + enc(who.ifBlank { "App" }.take(15))
        val c = try {
            conn("/_gateopen?$q", cookie, "POST").apply { doOutput = true; setFixedLengthStreamingMode(0); connect() }
        } catch (e: Exception) { return "relay non raggiungibile" }
        return try {
            c.outputStream.close()
            val (code, body) = text(c)
            val j = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            when {
                code == 401 -> "apri Casa ed entra"
                j.optBoolean("ok") -> null
                j.optString("err").startsWith("gia") -> "già in corso"
                j.optString("err").isNotBlank() -> j.optString("err")
                else -> "non riuscito ($code)"
            }
        } catch (e: Exception) { "nessuna risposta dal relay" }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
