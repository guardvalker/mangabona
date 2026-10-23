package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Gets the notes of the latest releases from GitHub, the same ones the updater shows.
 */
@Inject
class ChangelogFetcher(
    private val network: NetworkHelper,
    private val json: Json,
) {

    suspend fun await(): List<ChangelogEntry> {
        return withContext(Dispatchers.IO) {
            with(json) {
                network.client
                    .newCall(GET("https://api.github.com/repos/$GITHUB_REPO/releases?per_page=$RELEASES_COUNT"))
                    .awaitSuccess()
                    .parseAs<List<GithubReleaseNotes>>()
                    .filterNot { it.draft }
                    .map { ChangelogEntry(version = it.tag.removePrefix("v"), notes = it.body.orEmpty().trim()) }
            }
        }
    }
}

data class ChangelogEntry(val version: String, val notes: String)

@Serializable
private data class GithubReleaseNotes(
    @SerialName("tag_name") val tag: String,
    @SerialName("body") val body: String? = null,
    @SerialName("draft") val draft: Boolean = false,
)

private const val RELEASES_COUNT = 15
