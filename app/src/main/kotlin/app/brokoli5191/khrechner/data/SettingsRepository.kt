package app.brokoli5191.khrechner.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val store: DataStore<Settings>) {

    val settings: Flow<Settings> = store.data

    suspend fun update(transform: (Settings) -> Settings) {
        store.updateData(transform)
    }
}
