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
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("Cache-Control", "no-store")
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        return c
    }

    private fun text(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to body
    }

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

    /** Apre; null se riuscito, altrimenti il motivo. */
    fun open(cookie: String?, b: GateBtn, who: String): String? = try {
        val q = "d=" + enc(b.dev) + "&azione=" + enc(b.azione) + "&utente=" + enc(who.ifBlank { "App" }.take(15))
        val c = conn("/_gateopen?$q", cookie, "POST")
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
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
    } catch (e: Exception) { "relay non raggiungibile" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
