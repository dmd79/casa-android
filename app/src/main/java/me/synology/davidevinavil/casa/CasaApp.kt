package me.synology.davidevinavil.casa

import android.app.Application

/** Il collegamento vive qui: resta anche ruotando lo schermo o passando ad altre app. */
class CasaApp : Application() {
    lateinit var store: Store
    lateinit var ble: Ble

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        ble = Ble(this, store)
        ble.resume()
    }
}
