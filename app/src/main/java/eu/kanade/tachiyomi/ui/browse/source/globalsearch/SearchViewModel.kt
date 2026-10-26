package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class SearchViewModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
    private val preferences: SourcePreferences,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow<State>(initialState)

    // Subclasses can't touch the backing field (Kotlin forbids a visibility modifier on one),
    // so state writes from them go through here.
    protected fun updateState(function: (State) -> State) {
        state.update(function)
    }

    // A source that never answers must not hold up the rest, so these only count requests in flight; a
    // request that takes too long is abandoned (its thread is left to end by itself) instead of waited for.
    private val requestSlots = Semaphore(10)
    private val detailsSlots = Semaphore(12)
    private val sourceSlots = ConcurrentHashMap<Long, Semaphore>()

    private suspend fun <T> abandonable(timeout: Duration, block: suspend () -> T): T {
        val request = viewModelScope.async(Dispatchers.IO) { block() }
        try {
            return withTimeout(timeout) { request.await() }
        } catch (e: Throwable) {
            request.cancel()
            throw e
        }
    }
    private var searchJob: Job? = null
    private var chapterCountsJob: Job? = null
    private var detailsJob: Job? = null
    private val detailsTried = mutableSetOf<Long>()
    private var loadMoreJob: Job? = null

    // Last page asked of each source, and whether it said there is another one
    private val sourcePages = ConcurrentHashMap<Long, Int>()
    private val sourceHasNext = ConcurrentHashMap<Long, Boolean>()

    private val enabledLanguages = sourcePreferences.enabledLanguages.get()
    private val disabledSources = sourcePreferences.disabledSources.get()
    protected val pinnedSources = sourcePreferences.pinnedSources.get()

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null
    private var lastExcludedExtensions: Set<String> = emptySet()

    protected var extensionFilter: String? = null

    open val sortComparator = { map: Map<Source, SearchItemResult> ->
        compareBy<Source>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        viewModelScope.launch {
            // The extensions that can be left out of a search
            val extensions = extensionManager.getLoadedExtensions()
                .filter { ext -> ext.sources.any { it.lang in enabledLanguages || it.isLocal() } }
                .map { it.pkgName to it.name }
                .sortedBy { it.second.lowercase() }
            state.update { it.copy(extensions = extensions) }
        }
        viewModelScope.launch {
            preferences.globalSearchFilterState.changes().collectLatest { onlyShowHasResults ->
                state.update { it.copy(onlyShowHasResults = onlyShowHasResults) }
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

    open suspend fun getEnabledSources(): List<Source> {
        return sourceManager.getAll()
            .filter { (it.lang in enabledLanguages || it.isLocal()) && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private suspend fun getSelectedSources(): List<Source> {
        val excluded = state.value.filters.excludedExtensions
        val enabledSources = getEnabledSources().let { sources ->
            if (excluded.isEmpty()) {
                sources
            } else {
                val excludedIds = extensionManager.getLoadedExtensions()
                    .filter { it.pkgName in excluded }
                    .flatMap { it.sources }
                    .map { it.id }
                    .toSet()
                sources.filter { it.id !in excludedIds }
            }
        }

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.getLoadedExtensions()
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filter { it in enabledSources }
    }

    fun updateSearchQuery(query: String?) {
        state.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        state.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState.toggle()
    }

    /**
     * Applies the filters picked in the sheet. Without a word to search, the filters alone list what the
     * sources have (their popular titles) and keep the ones that match; with no filters either there is
     * nothing to show. Nothing is requested until this is called.
     */
    fun applyFilters(filters: SearchFilters) {
        state.update { it.copy(filters = filters) }
        if (!state.value.searchQuery.isNullOrBlank()) {
            search()
        } else {
            if (filters.isActive) {
                search()
            } else {
                searchJob?.cancel()
                detailsJob?.cancel()
                lastQuery = null
                state.update { it.copy(items = emptyMap(), detailsProgress = null) }
            }
        }
        if (filters.statuses.isNotEmpty() || filters.tags.isNotEmpty()) loadFilterDetails()
    }

    /**
     * Search listings rarely carry the status or the tags, so when the filter is opened the titles that lack
     * them get their details fetched (one request per title, not stored), which is what makes filtering by
     * them useful.
     */
    fun loadFilterDetails() {
        if (detailsJob?.isActive == true) return

        detailsJob = viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            var total = 0
            while (isActive) {
                val pending = state.value.unifiedGroupsUnfiltered
                    .filter { group -> group.entries.none { state.value.hasDetails(it.manga) } }
                    .map { it.entries.first() }
                    .filter { it.manga.id !in detailsTried }
                    .take(MAX_DETAIL_REQUESTS)
                if (pending.isEmpty()) break

                detailsTried += pending.map { it.manga.id }
                total += pending.size
                state.update { it.copy(detailsProgress = done to total) }
                pending.map { entry ->
                    async {
                        try {
                            // Few at a time per source, as they tend to throttle and queue the rest
                            val manga = sourceSlots.getOrPut(entry.source.id) { Semaphore(2) }.withPermit {
                                detailsSlots.withPermit {
                                    abandonable(DETAILS_TIMEOUT) {
                                        entry.source.getMangaUpdate(
                                            manga = entry.manga.toSManga(),
                                            chapters = emptyList(),
                                            fetchDetails = true,
                                            fetchChapters = false,
                                        )
                                    }
                                }
                            }.manga
                            val details = MangaDetails(
                                status = manga.status.toLong(),
                                genres = manga.getGenres().orEmpty(),
                            )
                            state.update { it.copy(details = it.details + (entry.manga.id to details)) }
                        } catch (e: TimeoutCancellationException) {
                            // Too slow, left without details
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Left without details, so a filter won't match it
                        }
                        done++
                        state.update { it.copy(detailsProgress = done to total) }
                    }
                }
                    .awaitAll()
            }
            state.update { it.copy(detailsProgress = null) }
        }
    }

    /**
     * Opens the chooser for a title found in several sources, counting the chapters each one has so the
     * fullest one can be picked.
     */
    fun openUnifiedGroup(group: UnifiedGroup) {
        chapterCountsJob?.cancel()
        state.update {
            it.copy(
                unifiedDialog = group,
                chapterCounts = group.entries.associate { entry -> entry.manga.id to ChapterCount.Loading },
            )
        }
        chapterCountsJob = viewModelScope.launch(Dispatchers.IO) {
            group.entries.map { entry ->
                async {
                    val count = try {
                        withTimeout(CHAPTER_COUNT_TIMEOUT) {
                            withContext(Dispatchers.IO) {
                                entry.source.getMangaUpdate(
                                    manga = entry.manga.toSManga(),
                                    chapters = emptyList(),
                                    fetchDetails = false,
                                    fetchChapters = true,
                                )
                            }
                        }.chapters.size.let { ChapterCount.Count(it) }
                    } catch (e: TimeoutCancellationException) {
                        ChapterCount.Failed
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ChapterCount.Failed
                    }
                    state.update { it.copy(chapterCounts = it.chapterCounts + (entry.manga.id to count)) }
                }
            }
                .awaitAll()
        }
    }

    fun closeUnifiedGroup() {
        chapterCountsJob?.cancel()
        state.update { it.copy(unifiedDialog = null, chapterCounts = emptyMap()) }
    }

    fun search() {
        val query = state.value.searchQuery.orEmpty()
        val sourceFilter = state.value.sourceFilter

        if (query.isBlank() && !state.value.filters.isActive) return

        val sameQuery = this.lastQuery == query
        val excluded = state.value.filters.excludedExtensions
        if (sameQuery && this.lastSourceFilter == sourceFilter && this.lastExcludedExtensions == excluded) return

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter
        this.lastExcludedExtensions = excluded

        searchJob?.cancel()
        detailsJob?.cancel()
        loadMoreJob?.cancel()
        if (!sameQuery) {
            sourcePages.clear()
            sourceHasNext.clear()
        }
        state.update { it.copy(detailsProgress = null, loadingMore = false) }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val sources = getSelectedSources()

            // Reuse previous results if possible
            if (sameQuery) {
                val existingResults = state.value.items
                updateItems(
                    sources
                        .associateWith { existingResults[it] ?: SearchItemResult.Loading },
                )
            } else {
                updateItems(
                    sources
                        .associateWith { SearchItemResult.Loading },
                )
            }

            sources.map { source ->
                async {
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }

                    try {
                        val page = requestSlots.withPermit { abandonable(SEARCH_TIMEOUT) {
                            if (query.isBlank()) {
                                source.getPopularManga(1)
                            } else {
                                source.getSearchManga(1, query, source.getFilterList())
                            }
                        } }

                        val titles = page.mangas
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }

                        if (isActive) {
                            sourcePages[source.id] = 1
                            sourceHasNext[source.id] = page.hasNextPage
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }
                .awaitAll()

            state.update { it.copy(canLoadMore = sourceHasNext.values.any { next -> next }) }
            state.value.filters.let { if (it.statuses.isNotEmpty() || it.tags.isNotEmpty()) loadFilterDetails() }
        }
    }

    /**
     * Asks every source that has more for its next page and adds the titles to the ones already found.
     */
    fun loadMore() {
        if (state.value.loadingMore) return
        val query = state.value.searchQuery.orEmpty()
        val sources = state.value.items.keys.filter { sourceHasNext[it.id] == true }
        if (sources.isEmpty()) return

        loadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            state.update { it.copy(loadingMore = true) }
            sources.map { source ->
                async {
                    val pageNumber = (sourcePages[source.id] ?: 1) + 1
                    try {
                        val page = requestSlots.withPermit {
                            abandonable(LOAD_MORE_TIMEOUT) {
                                if (query.isBlank()) {
                                    source.getPopularManga(pageNumber)
                                } else {
                                    source.getSearchManga(pageNumber, query, source.getFilterList())
                                }
                            }
                        }
                        val known = (state.value.items[source] as? SearchItemResult.Success)?.result.orEmpty()
                        val knownUrls = known.map { it.url }.toSet()
                        val titles = page.mangas
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .filter { it.url !in knownUrls }
                            .let { networkToLocalManga(it) }
                        sourcePages[source.id] = pageNumber
                        sourceHasNext[source.id] = page.hasNextPage && titles.isNotEmpty()
                        updateItem(source, SearchItemResult.Success(known + titles))
                    } catch (e: TimeoutCancellationException) {
                        sourceHasNext[source.id] = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        sourceHasNext[source.id] = false
                    }
                }
            }
                .awaitAll()

            state.update { it.copy(loadingMore = false, canLoadMore = sourceHasNext.values.any { next -> next }) }
            state.value.filters.let {
                if (it.statuses.isNotEmpty() || it.tags.isNotEmpty()) loadFilterDetails()
            }
        }
    }

    private fun updateItems(items: Map<Source, SearchItemResult>) {
        state.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items)),
            )
        }
    }

    private fun updateItem(source: Source, result: SearchItemResult) {
        state.update { currentState ->
            val newItems = currentState.items + (source to result)
            currentState.copy(
                items = newItems.toSortedMap(sortComparator(newItems)),
            )
        }
    }

    fun setMigrateDialog(currentId: Long, target: Manga) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = getManga.await(currentId) ?: return@launch
            state.update { it.copy(dialog = Dialog.Migrate(target, current)) }
        }
    }

    fun clearDialog() {
        state.update { it.copy(dialog = null) }
    }

    @Immutable
    data class State(
        val from: Manga? = null,
        val searchQuery: String? = null,
        val sourceFilter: SourceFilter = SourceFilter.All,
        val onlyShowHasResults: Boolean = false,
        val items: Map<Source, SearchItemResult> = mapOf(),
        val dialog: Dialog? = null,
        val filters: SearchFilters = SearchFilters(),
        val canLoadMore: Boolean = false,
        val loadingMore: Boolean = false,
        val extensions: List<Pair<String, String>> = emptyList(),
        val details: Map<Long, MangaDetails> = emptyMap(),
        val detailsProgress: Pair<Int, Int>? = null,
        val unifiedDialog: UnifiedGroup? = null,
        val chapterCounts: Map<Long, ChapterCount> = emptyMap(),
    ) {
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
        val unifiedGroupsUnfiltered: List<UnifiedGroup> = buildUnifiedGroups(items)
        val unifiedGroups: List<UnifiedGroup> = unifiedGroupsUnfiltered.filtered()

        // Tags of the titles found, the most common first, then the usual ones
        val availableTags: List<Pair<String, Int>> by lazy {
            val seen = unifiedGroupsUnfiltered
                .flatMap { group -> group.entries.flatMap { genresOf(it.manga) }.distinctBy { it.lowercase() } }
                .groupBy { it.lowercase() }
                .map { (_, names) -> names.first() to names.size }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            // The usual ones are offered from the start, before there is anything searched
            val known = seen.map { it.first.lowercase() }.toSet()
            seen + COMMON_TAGS.filter { it.lowercase() !in known }.map { it to 0 }
        }

        fun statusOf(manga: Manga): Long = details[manga.id]?.status ?: manga.status

        fun genresOf(manga: Manga): List<String> = details[manga.id]?.genres ?: manga.genre.orEmpty()

        /** Titles whose status or tags are known, which is all a filter on them can go by */
        val titlesWithDetails: Int get() = unifiedGroupsUnfiltered.count { g -> g.entries.any { hasDetails(it.manga) } }

        fun hasDetails(manga: Manga): Boolean = manga.id in details || manga.status != 0L || !manga.genre.isNullOrEmpty()

        private fun List<UnifiedGroup>.filtered(): List<UnifiedGroup> {
            if (!filters.isActive) return this
            return mapNotNull { group ->
                val entries = group.entries.filter { entry ->
                    when (filters.library) {
                        LibraryFilter.Any -> true
                        LibraryFilter.InLibrary -> entry.manga.favorite
                        LibraryFilter.NotInLibrary -> !entry.manga.favorite
                    } &&
                        (filters.statuses.isEmpty() || statusOf(entry.manga) in filters.statuses) &&
                        filters.tags.all { tag -> genresOf(entry.manga).any { it.lowercase() == tag } }
                }
                if (entries.isEmpty()) null else group.copy(entries = entries)
            }
        }
    }

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }
}

/**
 * The same title as found in each source.
 */
@Immutable
data class UnifiedGroup(val key: String, val title: String, val entries: List<UnifiedEntry>)

@Immutable
data class UnifiedEntry(val source: Source, val manga: Manga)

sealed interface ChapterCount {
    data object Loading : ChapterCount
    data class Count(val value: Int) : ChapterCount
    data object Failed : ChapterCount
}

/**
 * Titles that only differ in case or punctuation are taken as the same one. Groups found in more sources
 * go first, and the rest keep the order the sources answered in.
 */
private fun buildUnifiedGroups(items: Map<Source, SearchItemResult>): List<UnifiedGroup> {
    return items
        .flatMap { (source, result) ->
            (result as? SearchItemResult.Success)?.result.orEmpty().map { UnifiedEntry(source, it) }
        }
        .groupBy { entry ->
            val title = entry.manga.title.lowercase()
            title.filter { it.isLetterOrDigit() }.ifEmpty { title }
        }
        .map { (key, entries) -> UnifiedGroup(key, entries.first().manga.title, entries) }
        .sortedByDescending { it.entries.size }
}

private val CHAPTER_COUNT_TIMEOUT = 12.seconds
private val DETAILS_TIMEOUT = 15.seconds
private val SEARCH_TIMEOUT = 30.seconds
private val LOAD_MORE_TIMEOUT = 25.seconds

enum class SourceFilter {
    All,
}

/**
 * Narrows the titles found. Any of the chosen statuses may match, but a title needs all the chosen tags.
 * Tags are kept lowercase. Excluded extensions (by package name) aren't searched at all.
 */
@Immutable
data class SearchFilters(
    val statuses: Set<Long> = emptySet(),
    val tags: Set<String> = emptySet(),
    val library: LibraryFilter = LibraryFilter.Any,
    val excludedExtensions: Set<String> = emptySet(),
) {
    val count: Int get() = statuses.size + tags.size + excludedExtensions.size + if (library == LibraryFilter.Any) 0 else 1
    val isActive: Boolean get() = count > 0
}

enum class LibraryFilter {
    Any,
    InLibrary,
    NotInLibrary,
}

@Immutable
data class MangaDetails(val status: Long, val genres: List<String>)

private const val MAX_DETAIL_REQUESTS = 120

private val COMMON_TAGS = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Historical", "Horror", "Isekai", "Josei",
    "Martial Arts", "Mecha", "Mystery", "Psychological", "Romance", "School Life", "Sci-Fi", "Seinen",
    "Shoujo", "Shounen", "Slice of Life", "Sports", "Supernatural", "Thriller", "Tragedy", "Webtoon",
)

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<Manga>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
