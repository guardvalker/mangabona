package eu.kanade.tachiyomi.ui.browse.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.domain.extension.model.ContentWarning
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Feed with what the latest listing of every installed source has right now.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ExploreViewModel(
    private val extensionManager: ExtensionManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
    private val getLibraryManga: GetLibraryManga,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private val dispatcher = Dispatchers.IO.limitedParallelism(5)
    private var job: Job? = null
    private var started = false
    private val suggested = mutableSetOf<String>()

    // What each source answered, kept so changing the filter doesn't ask everyone again
    private val cache = ConcurrentHashMap<Long, List<Manga>>()

    init {
        viewModelScope.launch {
            combine(
                extensionManager.loadedExtensionsFlow,
                sourcePreferences.exploreShownExtensions.changes(),
                sourcePreferences.exploreHiddenExtensions.changes(),
            ) { extensions, shown, hidden ->
                extensions
                    .filter { extension -> extension.sources.any { it.supportsLatest } }
                    .sortedBy { it.name.lowercase() }
                    .map {
                        val isNsfw = it.contentWarning == ContentWarning.NSFW
                        ExploreFilterOption(
                            pkgName = it.pkgName,
                            name = it.name,
                            isNsfw = isNsfw,
                            isShown = isVisible(it.pkgName, isNsfw, shown, hidden),
                        )
                    }
            }
                .collectLatest { options -> state.update { it.copy(filterOptions = options) } }
        }
    }

    /**
     * Loads once when the section is first shown, so opening Browse alone doesn't hit every source.
     */
    fun loadIfNeeded() {
        if (!started) load()
    }

    fun refresh() {
        cache.clear()
        load()
    }

    /**
     * Called when the filter closes, to show or hide what changed.
     */
    fun applyFilter() {
        if (started) load()
    }

    /**
     * Picks a title from the feed that resembles what the library is full of. Titles already suggested
     * are skipped, so pressing again keeps rotating. Null when there is nothing left to suggest.
     */
    suspend fun pickRandom(): Manga? = withContext(Dispatchers.IO) {
        state.update { it.copy(isPicking = true) }
        try {
            val profile = getLibraryManga.await()
                .flatMap { it.manga.genre.orEmpty() }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .groupingBy { it }
                .eachCount()

            val pool = state.value.items
                .filter { !it.manga.favorite && "${it.source.id}-${it.manga.url}" !in suggested }
                .shuffled()

            var best: ExploreEntry? = null
            var bestScore = 0
            if (profile.isNotEmpty()) {
                // A few at a time, since a listing rarely carries genres and each needs its own request
                for (batch in pool.chunked(PICK_BATCH).take(PICK_BATCHES)) {
                    val scored = batch.map { entry ->
                        async { entry to score(entry, profile) }
                    }.awaitAll()
                    // Weighted by how much of the library they match, so lesser genres still show up now and then
                    val matches = scored.filter { it.second > 0 }
                    if (matches.isNotEmpty()) {
                        var roll = (0 until matches.sumOf { it.second }).random()
                        val chosen = matches.first { (_, points) ->
                            roll -= points
                            roll < 0
                        }
                        best = chosen.first
                        bestScore = chosen.second
                        break
                    }
                }
            }

            val entry = best ?: pool.firstOrNull() ?: return@withContext null
            suggested += "${entry.source.id}-${entry.manga.url}"
            logcat(LogPriority.DEBUG) { "Explore pick: ${entry.manga.title} (score $bestScore)" }
            entry.manga
        } finally {
            state.update { it.copy(isPicking = false) }
        }
    }

    private suspend fun score(entry: ExploreEntry, profile: Map<String, Int>): Int {
        val genres = entry.manga.genre?.takeIf { it.isNotEmpty() } ?: try {
            withTimeout(PICK_TIMEOUT) {
                entry.source.getMangaUpdate(
                    manga = SManga.create().also {
                        it.url = entry.manga.url
                        it.title = entry.manga.title
                    },
                    chapters = emptyList(),
                    fetchDetails = true,
                    fetchChapters = false,
                ).manga.getGenres()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return genres.orEmpty().sumOf { profile[it.trim().lowercase()] ?: 0 }
    }

    fun toggleExtension(option: ExploreFilterOption) {
        val shown = sourcePreferences.exploreShownExtensions
        val hidden = sourcePreferences.exploreHiddenExtensions
        if (!option.isShown) {
            hidden.getAndSet { it - option.pkgName }
            if (option.isNsfw) shown.getAndSet { it + option.pkgName }
        } else {
            shown.getAndSet { it - option.pkgName }
            hidden.getAndSet { it + option.pkgName }
        }
    }

    private fun isVisible(pkgName: String, isNsfw: Boolean, shown: Set<String>, hidden: Set<String>): Boolean {
        return pkgName !in hidden && (pkgName in shown || !isNsfw)
    }

    private fun load() {
        started = true
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            val enabledLanguages = sourcePreferences.enabledLanguages.get()
            val disabledSources = sourcePreferences.disabledSources.get()
            val pinnedSources = sourcePreferences.pinnedSources.get()
            val shown = sourcePreferences.exploreShownExtensions.get()
            val hidden = sourcePreferences.exploreHiddenExtensions.get()

            val sources = extensionManager.getLoadedExtensions()
                .filter { isVisible(it.pkgName, it.contentWarning == ContentWarning.NSFW, shown, hidden) }
                .flatMap { it.sources }
                .distinctBy { it.id }
                .filter {
                    it.supportsLatest && !it.isLocal() &&
                        it.lang in enabledLanguages && "${it.id}" !in disabledSources
                }
                .sortedWith(compareBy({ "${it.id}" !in pinnedSources }, { it.name.lowercase() }))

            val pending = sources.filter { !cache.containsKey(it.id) }
            state.update {
                State(
                    isLoading = pending.isNotEmpty(),
                    total = pending.size,
                    items = if (pending.isEmpty()) interleave(sources) else emptyList(),
                    filterOptions = it.filterOptions,
                )
            }

            pending.map { source ->
                async {
                    try {
                        val titles = withTimeout(SOURCE_TIMEOUT) {
                            withContext(dispatcher) { source.getLatestUpdates(1) }
                        }
                            .mangas
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }
                        cache[source.id] = titles
                    } catch (e: TimeoutCancellationException) {
                        // Only this source gave up, the rest keep going
                        state.update { it.copy(failed = it.failed + 1) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.INFO, e) { "Explore: ${source.name} failed" }
                        state.update { it.copy(failed = it.failed + 1) }
                    } finally {
                        state.update { it.copy(done = it.done + 1) }
                    }
                }
            }
                .awaitAll()

            // Shown only once everything answered (or timed out) so the covers don't jump around while loading
            state.update { it.copy(isLoading = false, items = interleave(sources)) }
        }
    }

    /**
     * One title from each source in turn, so a single source with a long list doesn't take the whole feed.
     */
    private fun interleave(sources: List<Source>): List<ExploreEntry> {
        val lists = sources.mapNotNull { source -> cache[source.id]?.takeIf { it.isNotEmpty() }?.let { source to it } }
        val longest = lists.maxOfOrNull { it.second.size } ?: 0
        return buildList {
            for (index in 0 until longest) {
                for ((source, titles) in lists) {
                    titles.getOrNull(index)?.let { add(ExploreEntry(source, it)) }
                }
            }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val items: List<ExploreEntry> = emptyList(),
        val filterOptions: List<ExploreFilterOption> = emptyList(),
        val isPicking: Boolean = false,
    )
}

@Immutable
data class ExploreEntry(val source: Source, val manga: Manga)

@Immutable
data class ExploreFilterOption(
    val pkgName: String,
    val name: String,
    val isNsfw: Boolean,
    val isShown: Boolean,
)

private val SOURCE_TIMEOUT = 15.seconds
private val PICK_TIMEOUT = 8.seconds
private const val PICK_BATCH = 6
private const val PICK_BATCHES = 3
