package app.brokoli5191.khrechner.data

import android.content.ContentResolver
import android.net.Uri
import androidx.datastore.core.DataStore
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class ImportMode { MERGE, REPLACE }

/** Reads and writes export files through the Storage Access Framework. */
class BackupRepository(
    private val store: DataStore<AppData>,
    private val contentResolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** @return number of entries written. @throws IOException if the document cannot be written. */
    suspend fun exportTo(uri: Uri): Int = withContext(io) {
        val data = store.data.first()
        val text = BackupCodec.encode(data, Instant.now())
        val stream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output stream for $uri")
        stream.use { it.write(text.encodeToByteArray()) }
        data.entries.size
    }

    /** Reads and validates a document without changing anything yet. */
    suspend fun read(uri: Uri): ImportOutcome = withContext(io) {
        val text = try {
            val stream = contentResolver.openInputStream(uri) ?: throw IOException("no input stream for $uri")
            stream.use { input ->
                // An export is a few kilobytes; refuse to pull an arbitrary large file into memory.
                val bytes = input.readNBytes(MAX_IMPORT_BYTES + 1)
                if (bytes.size > MAX_IMPORT_BYTES) return@withContext ImportOutcome.Failure(ImportError.NOT_A_BACKUP)
                bytes.decodeToString()
            }
        } catch (_: IOException) {
            return@withContext ImportOutcome.Failure(ImportError.UNREADABLE)
        } catch (_: SecurityException) {
            return@withContext ImportOutcome.Failure(ImportError.UNREADABLE)
        }
        BackupCodec.decode(text)
    }

    suspend fun apply(incoming: AppData, mode: ImportMode): MergeResult {
        var result = MergeResult(incoming, addedEntries = incoming.entries.size, skippedEntries = 0)
        store.updateData { current ->
            when (mode) {
                ImportMode.REPLACE -> incoming
                ImportMode.MERGE ->
                    BackupCodec.merge(current, incoming) { UUID.randomUUID().toString() }
                        .also { result = it }
                        .data
            }
        }
        return result
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    }
}
