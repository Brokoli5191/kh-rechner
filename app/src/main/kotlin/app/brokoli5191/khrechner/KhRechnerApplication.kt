package app.brokoli5191.khrechner

import android.app.Application

open class KhRechnerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        // Fetch the barcode scanner module in the background so the first scan does not have to.
        container.scanner.requestModule()
    }

    /** Tests override this to swap the scanner and the network for fakes. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
