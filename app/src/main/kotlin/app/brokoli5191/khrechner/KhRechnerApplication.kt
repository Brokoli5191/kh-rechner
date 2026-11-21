package app.brokoli5191.khrechner

import android.app.Application

open class KhRechnerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        // Fetch Google's scanner module in the background; until it is there the app scans itself.
        container.scanner.install()
    }

    /** Tests override this to swap the scanner and the network for fakes. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
