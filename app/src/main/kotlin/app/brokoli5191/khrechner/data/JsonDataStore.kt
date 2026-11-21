package app.brokoli5191.khrechner.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Lenient on read so a file written by a newer app version still loads. */
internal val StorageJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

private class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T = try {
        StorageJson.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable ${serializer.descriptor.serialName}", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable ${serializer.descriptor.serialName}", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(StorageJson.encodeToString(serializer, t).encodeToByteArray())
    }
}

/** A DataStore that keeps one JSON document in `files/datastore/<fileName>`. */
fun <T> jsonDataStore(
    context: Context,
    fileName: String,
    serializer: KSerializer<T>,
    defaultValue: T,
    scope: CoroutineScope,
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(serializer, defaultValue),
    corruptionHandler = ReplaceFileCorruptionHandler { defaultValue },
    scope = scope,
    produceFile = { context.dataStoreFile(fileName) },
)
