package me.synology.davidevinavil.casa

import android.app.Application

/** Il collegamento vive qui: resta anche ruotando lo schermo o passando ad altre app. */
class CasaApp : Application() {
    lateinit var store: Store
    lateinit var ble: Ble
    /** Cancelli e garage via Bluetooth. */
    lateinit var gates: GateBle
    /** Schermata dei cancelli Bluetooth chiesta dalla barra: "list", "scan" o "dev:<indirizzo>". */
    val gateGo = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Schermata di Clima BT chiesta dalla barra (per esempio la ricerca). */
    val climaGo = kotlinx.coroutines.flow.MutableStateFlow<Scr?>(null)
    /** "Abbina / Dimentica Bluetooth" dalla lista del relay. */
    lateinit var pairer: BtPairer
    /**
     * App sullo schermo. In secondo piano Android blocca la rete: le
     * richieste al relay fallirebbero e Clima/Accessi lo crederebbero giu'
     * (riaprendo si vedeva il Bluetooth per qualche secondo).
     */
    @Volatile var fg = false

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        ble = Ble(this, store)
        gates = GateBle(this, store)
        pairer = BtPairer(this, store, ble, gates)
        ble.resume()
    }
}
