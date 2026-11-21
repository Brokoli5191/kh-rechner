package app.brokoli5191.khrechner

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.brokoli5191.khrechner.data.AapsHandoff
import app.brokoli5191.khrechner.data.AndroidAapsHandoff
import app.brokoli5191.khrechner.data.AppData
import app.brokoli5191.khrechner.data.BackupRepository
import app.brokoli5191.khrechner.data.BarcodeScanner
import app.brokoli5191.khrechner.data.EntryRepository
import app.brokoli5191.khrechner.data.GmsBarcodeScanner
import app.brokoli5191.khrechner.data.LibraryCatalog
import app.brokoli5191.khrechner.data.OpenFoodFactsClient
import app.brokoli5191.khrechner.data.ProductRepository
import app.brokoli5191.khrechner.data.ProductSource
import app.brokoli5191.khrechner.data.ScannerController
import app.brokoli5191.khrechner.data.Settings
import app.brokoli5191.khrechner.data.SettingsRepository
import app.brokoli5191.khrechner.data.jsonDataStore
import app.brokoli5191.khrechner.ui.calculator.CalculatorViewModel
import app.brokoli5191.khrechner.ui.saved.SavedViewModel
import app.brokoli5191.khrechner.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency wiring. One instance lives in [KhRechnerApplication].
 *
 * The parameters with defaults are the app's only doors to the outside world (Google Play
 * services, the network, AAPS); tests pass fakes for them.
 */
class AppContainer(
    context: Context,
    barcodeScanner: BarcodeScanner = GmsBarcodeScanner(context.applicationContext),
    productSource: ProductSource = OpenFoodFactsClient(USER_AGENT),
    val aaps: AapsHandoff = AndroidAapsHandoff(context.applicationContext),
) {

    private val appContext = context.applicationContext
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appData = jsonDataStore(appContext, "app_data.json", AppData.serializer(), AppData(), storeScope)

    val settings = SettingsRepository(
        jsonDataStore(appContext, "settings.json", Settings.serializer(), Settings(), storeScope),
    )
    val entries = EntryRepository(appData)
    val products = ProductRepository(appData, productSource)
    val backup = BackupRepository(appData, appContext.contentResolver)
    val scanner = ScannerController(barcodeScanner, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    val libraries = LibraryCatalog(open = { appContext.resources.openRawResource(R.raw.aboutlibraries) })

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { CalculatorViewModel(createSavedStateHandle(), entries, products, scanner) }
        initializer { SavedViewModel(entries) }
        initializer { SettingsViewModel(settings, backup, scanner, libraries) }
    }

    private companion object {
        // Open Food Facts asks every client to identify itself as "AppName/Version (contact)".
        const val USER_AGENT = "KHRechner/${BuildConfig.VERSION_NAME} (github.com/Brokoli5191/kh-rechner)"
    }
}
