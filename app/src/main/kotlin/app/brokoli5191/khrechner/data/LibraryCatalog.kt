package app.brokoli5191.khrechner.data

import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One open-source project the app is built with; artifacts of the same Maven group are folded together. */
data class LibraryGroup(
    val name: String,
    /** Set if every artifact of the group has the same version. */
    val version: String?,
    val moduleCount: Int,
    val licences: List<String>,
    /** Project page, or else the licence text. */
    val url: String?,
)

/**
 * The dependency list that the AboutLibraries Gradle plugin writes to `res/raw/aboutlibraries.json`
 * at build time. Only that file's format is used; the UI is the app's own.
 */
class LibraryCatalog(
    private val open: () -> InputStream,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun load(): List<LibraryGroup> = withContext(io) {
        try {
            parse(open().use { it.readBytes().decodeToString() })
        } catch (_: Exception) {
            // A missing or unreadable list is not worth crashing the about page for.
            emptyList()
        }
    }

    @Serializable
    private data class Catalog(
        val libraries: List<Library> = emptyList(),
        val licenses: Map<String, Licence> = emptyMap(),
    )

    @Serializable
    private data class Library(
        val uniqueId: String,
        val artifactVersion: String? = null,
        val name: String? = null,
        val website: String? = null,
        val licenses: List<String> = emptyList(),
    )

    @Serializable
    private data class Licence(val name: String? = null, val url: String? = null, val spdxId: String? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** @throws SerializationException if [text] is not in the expected format. */
        fun parse(text: String): List<LibraryGroup> {
            val catalog = json.decodeFromString(Catalog.serializer(), text)
            return catalog.libraries
                // A BOM only pins versions; it is not code that ships in the app.
                .filterNot { it.uniqueId.endsWith("-bom") }
                .groupBy { it.uniqueId.substringBefore(':') }
                .map { (group, artifacts) ->
                    val licences = artifacts.flatMap { it.licenses }.distinct().mapNotNull(catalog.licenses::get)
                    LibraryGroup(
                        name = artifacts.singleOrNull()?.name?.takeIf { it.isNotBlank() } ?: group,
                        version = artifacts.mapNotNull { it.artifactVersion }.distinct().singleOrNull(),
                        moduleCount = artifacts.size,
                        licences = licences.mapNotNull { it.spdxId ?: it.name }.distinct(),
                        url = artifacts.firstNotNullOfOrNull { it.website?.takeIf(::isWebLink) }
                            ?: licences.firstNotNullOfOrNull { it.url?.takeIf(::isWebLink) },
                    )
                }
                .sortedBy { it.name.lowercase() }
        }

        private fun isWebLink(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")
    }
}
