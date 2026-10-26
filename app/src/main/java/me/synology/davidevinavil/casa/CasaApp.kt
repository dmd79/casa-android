package me.synology.davidevinavil.casa

import android.app.Application

/** Il collegamento vive qui: resta anche ruotando lo schermo o passando ad altre app. */
class CasaApp : Application() {
    lateinit var store: Store
    lateinit var ble: Ble
    /** Schermata di Clima BT chiesta dalla barra (per esempio la ricerca). */
    val climaGo = kotlinx.coroutines.flow.MutableStateFlow<Scr?>(null)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        ble = Ble(this, store)
        ble.resume()
    }
}
