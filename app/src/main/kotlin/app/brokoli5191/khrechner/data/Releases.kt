package app.brokoli5191.khrechner.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A published version of the app and where its APK is. */
@Serializable
data class ReleaseInfo(
    /** "1.4.0", without the "v" of the tag. */
    val version: String,
    val apkUrl: String,
    /** Bytes, as GitHub states it; 0 if unknown. */
    val apkSize: Long = 0,
    /** Lowercase hex SHA-256 of the APK as GitHub computed it on upload; null if GitHub gave none. */
    val sha256: String? = null,
    /** The release page with its notes. */
    val pageUrl: String,
)

/** Why looking for, loading or installing an update did not work. */
enum class UpdateFailure {
    NO_CONNECTION,

    /** GitHub answered with an error or with something that is not a release. */
    GITHUB_UNAVAILABLE,

    /** The newest release has no APK attached. */
    NO_APK,

    /** The file that arrived is not the one GitHub listed, or not an APK. */
    DAMAGED,

    /** The APK is not signed with the key of the installed app, or is not this app. */
    WRONG_SIGNATURE,
    NO_STORAGE,

    /** Android refused the installation, e.g. because a device policy forbids it. */
    BLOCKED,
    INSTALL_FAILED,
}

sealed interface ReleaseLookup {
    data class Found(val release: ReleaseInfo) : ReleaseLookup

    /** The repository has no published release. */
    data object NoRelease : ReleaseLookup
    data class Failed(val reason: UpdateFailure) : ReleaseLookup
}

fun interface ReleaseSource {
    suspend fun latest(): ReleaseLookup
}

/**
 * The newest release on GitHub: `GET /repos/{owner}/{repo}/releases/latest`, which leaves out
 * drafts and pre-releases. Unauthenticated, which GitHub allows 60 times an hour per address;
 * the app asks at most once a day. Docs: https://docs.github.com/en/rest/releases/releases
 */
class GitHubReleaseClient(
    private val userAgent: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReleaseSource {

    override suspend fun latest(): ReleaseLookup = withContext(io) {
        val connection = try {
            URL(LATEST_URL).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return@withContext ReleaseLookup.Failed(UpdateFailure.NO_CONNECTION)
        }
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            // GitHub turns away requests without a User-Agent.
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    GitHubReleaseParser.parse(connection.inputStream.use { it.readNBytes(MAX_BODY_BYTES).decodeToString() })
                HttpURLConnection.HTTP_NOT_FOUND -> ReleaseLookup.NoRelease
                else -> ReleaseLookup.Failed(UpdateFailure.GITHUB_UNAVAILABLE)
            }
        } catch (_: IOException) {
            ReleaseLookup.Failed(UpdateFailure.NO_CONNECTION)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/Brokoli5191/kh-rechner/releases/latest"
        const val TIMEOUT_MILLIS = 10_000
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}

/** Turns GitHub's answer into a [ReleaseLookup]. Pure, so it is tested without a network. */
object GitHubReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256 = Regex("""sha256:([0-9a-fA-F]{64})""")

    fun parse(body: String): ReleaseLookup {
        val release = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return ReleaseLookup.Failed(UpdateFailure.GITHUB_UNAVAILABLE)

        val version = release["tag_name"].text()?.trim()?.removePrefix("v")
            ?.takeIf { AppVersion.parse(it) != null }
            ?: return ReleaseLookup.Failed(UpdateFailure.GITHUB_UNAVAILABLE)
        val page = release["html_url"].text()?.takeIf(::isHttps)
            ?: return ReleaseLookup.Failed(UpdateFailure.GITHUB_UNAVAILABLE)

        val apk = (release["assets"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .firstOrNull { asset -> asset["name"].text()?.endsWith(".apk", ignoreCase = true) == true }
            ?: return ReleaseLookup.Failed(UpdateFailure.NO_APK)
        val url = apk["browser_download_url"].text()?.takeIf(::isHttps)
            ?: return ReleaseLookup.Failed(UpdateFailure.NO_APK)

        return ReleaseLookup.Found(
            ReleaseInfo(
                version = version,
                apkUrl = url,
                apkSize = apk["size"].text()?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                sha256 = apk["digest"].text()?.let { sha256.matchEntire(it)?.groupValues?.get(1)?.lowercase() },
                pageUrl = page,
            ),
        )
    }

    private fun isHttps(url: String): Boolean = url.startsWith("https://")

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }?.content
}

/** Version names of the form "1.2.3", as the release workflow writes them. */
object AppVersion {

    /** The numbers of "1.2.3"; null for anything else, pre-release suffixes included. */
    fun parse(version: String): List<Int>? {
        val parts = version.trim().removePrefix("v").split('.')
        if (parts.size !in 1..3) return null
        return parts.map { part -> part.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null }
    }

    /** True if [candidate] is a later version than [current]; false if either cannot be read. */
    fun isNewer(candidate: String, current: String): Boolean {
        val new = parse(candidate) ?: return false
        val old = parse(current) ?: return false
        for (index in 0 until 3) {
            val difference = new.getOrElse(index) { 0 } - old.getOrElse(index) { 0 }
            if (difference != 0) return difference > 0
        }
        return false
    }
}
