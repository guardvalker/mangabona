package mihon.feature.migration.hop

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * A manga found in another source while looking for the chapter being read.
 */
data class HopCandidate(
    val manga: Manga,
    val sourceName: String,
)

/**
 * Looks for the same manga in every other source, so the reader can jump to the same chapter and page without going
 * through the whole search / open / check cycle by hand.
 *
 * Results are kept in memory by [hopKey] so that after hopping to a candidate the list is still there, and the user
 * can keep rotating through sources.
 */
@Inject
class ChapterHopFinder(
    private val preferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {

    fun cached(hopKey: String): List<HopCandidate>? = cache[hopKey]

    /**
     * Emits the candidates as soon as each source answers, starting with the origin so it can always be returned to.
     */
    fun search(hopKey: String, origin: Manga): Flow<HopCandidate> = channelFlow {
        val engine = SmartSourceSearchEngine(null)
        val semaphore = Semaphore(5)
        val originCandidate = HopCandidate(origin, sourceManager.getOrStub(origin.source).getNameForMangaInfo())
        val found = mutableListOf(originCandidate)
        send(originCandidate)

        val sources = preferences.migrationSources.get()
            .mapNotNull { sourceManager.get(it) }
            .ifEmpty { sourceManager.getOnlineSources() }

        val jobs = sources.map { source ->
            launch {
                semaphore.withPermit {
                    try {
                        val result = engine.regularSearch(source, origin.title) ?: return@withPermit
                        if (result.url == origin.url && source.id == origin.source) return@withPermit

                        val local = networkToLocalManga(result)
                        try {
                            updateMangaFromRemote(local, fetchChapters = true).getOrThrow()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e)
                            return@withPermit
                        }
                        val candidate = HopCandidate(getManga.await(local.id) ?: local, source.getNameForMangaInfo())
                        synchronized(found) { found += candidate }
                        send(candidate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Find & migrate failed for source ${source.id}" }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        cache[hopKey] = synchronized(found) { found.toList() }
    }

    /**
     * The chapter of [manga] that matches the one being read: same chapter number, or same name when the number
     * could not be parsed.
     */
    suspend fun findChapter(manga: Manga, chapterNumber: Double, chapterName: String): Chapter? {
        val chapters = getChaptersByMangaId.await(manga.id)
        return if (chapterNumber >= 0) {
            chapters.firstOrNull { it.chapterNumber == chapterNumber }
        } else {
            chapters.firstOrNull { it.name == chapterName }
        }
    }

    private companion object {
        val cache = ConcurrentHashMap<String, List<HopCandidate>>()
    }
}
